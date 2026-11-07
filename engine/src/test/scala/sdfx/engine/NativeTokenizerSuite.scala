package sdfx.engine

import java.nio.file.{Files,Path}
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.protocol.{LoadModelRequest,GenerationRequest}
import sdfx.core.model.{GenerationParams,HiresParams,LoraRef}

class NativeTokenizerSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds
  test("NUI typed client inspects the loaded native tokenizer without Python") {
    val cp=sys.props.get("scalatorch.worker.classpath").orElse(sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE"))
    val model=sys.props.get("scalatorch.tiny.model").orElse(sys.env.get("SCALATORCH_TINY_MODEL"))
    assume(cp.nonEmpty && model.nonEmpty,"native worker and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val temp=Files.createTempDirectory("nui-native-tokenizer")
    val config=temp.resolve("scheduler.json");Files.writeString(config,"{}")
    val supervisor=new EngineSupervisor(()=>NativeTestLaunch(cp.get,root,config),maxRestarts=0)
    try
      val hello=supervisor.start()
      assertEquals(hello.python,"")
      assert(hello.features.contains("tokenizer"))
      val client=new EngineClient(supervisor)
      Await.result(client.loadModel(LoadModelRequest(root.toString,dtype="fp32",vramMode="cpu")),30.seconds)
      val listed=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"list",
        "embedding_paths"->ujson.Arr(temp.resolve("Example.safetensors").toString))),10.seconds)
      assertEquals(listed("loaded").arr.size,0)
      assertEquals(listed("available").arr.map(_.str).toSeq,Seq("example"))
      val result=Await.result(client.tokenize("Hello, world!"),10.seconds)
      assertEquals(result.inputMode,"text")
      assertEquals(result.streams.size,1)
      assertEquals(result.streams.head.ids,Seq(73,759,198,279,88,377,559,345))
      assertEquals(result.streams.head.decoded,"hello , world !")
      val inspector=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"tokenize",
        "text"->"Hello, world!","embedding_paths"->ujson.Arr())),10.seconds)
      val inspectorTokens=inspector("encoders")("clip_l").arr
      assertEquals(inspectorTokens.map(_("id").num.toInt).toSeq,result.streams.head.ids)
      assertEquals(inspectorTokens.head("token").str,"h")
      assertEquals(inspector("warnings").arr.size,0)
      val inspected=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"inspect",
        "text"->"#73","limit"->3,"embedding_paths"->ujson.Arr())),10.seconds)
      val clip=inspected("encoders")("clip_l")
      assertEquals(clip("ids").arr.map(_.num.toInt).toSeq,Seq(73))
      assertEquals(clip("vectors").arr.size,1)
      assertEquals(clip("nearest").arr.size,3)
      val exportFile=temp.resolve("vector.txt")
      val exported=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"export",
        "text"->"#73","output"->exportFile.toString,"embedding_paths"->ujson.Arr())),10.seconds)
      assertEquals(exported("path").str,exportFile.toString)
      val stored=ujson.read(Files.readString(exportFile))
      assertEquals(stored("clip_l").arr.head.arr.map(_.num).toSeq,clip("vectors").arr.head.arr.map(_.num).toSeq)
      val mixedFile=temp.resolve("native_mixed.safetensors")
      val mixed=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"save",
        "rows"->ujson.Arr(ujson.Obj("name"->"#73","weight"->0.5)),"output"->mixedFile.toString,
        "embedding_paths"->ujson.Arr(),"concat"->false,"combine"->false,"eval"->"",
        "overwrite"->false,"batch_presets"->false)),10.seconds)
      assertEquals(mixed("path").str,mixedFile.toString)
      assert(Files.isRegularFile(mixedFile))
      val active=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"list",
        "embedding_paths"->ujson.Arr(mixedFile.toString))),10.seconds)
      assertEquals(active("loaded").arr.head("name").str,"native_mixed")
      val replacement=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"save",
        "rows"->ujson.Arr(ujson.Obj("name"->"#73","weight"->0.25)),"output"->mixedFile.toString,
        "embedding_paths"->ujson.Arr(mixedFile.toString),"overwrite"->true)),10.seconds)
      assertEquals(replacement("path").str,mixedFile.toString)
      val changed=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"inspect",
        "text"->"native_mixed","limit"->1,"embedding_paths"->ujson.Arr(mixedFile.toString))),10.seconds)
      assertEquals(changed("encoders")("clip_l")("vectors").arr.head.arr.map(_.num).toSeq,
        replacement("encoders")("clip_l")("vectors").arr.head.arr.map(_.num).toSeq)
      val editorFile=temp.resolve("editor_copy.safetensors")
      Files.copy(mixedFile,editorFile)
      val opened=Await.result(client.raw("embedding_editor",ujson.Obj("operation"->"open","name"->"editor_copy",
        "path"->editorFile.toString,"guidance"->"hello:#ff0000")),10.seconds)
      assertEquals(opened("name").str,"editor_copy")
      assertEquals(opened("guidance").arr.size,1)
      assertEquals(opened("vectors")("clip_l").arr.head.arr.map(_.num).toSeq,
        replacement("encoders")("clip_l")("vectors").arr.head.arr.map(_.num).toSeq)
      val guidance=Await.result(client.raw("embedding_editor",ujson.Obj("operation"->"guidance","name"->"editor_copy",
        "draft"->opened,"guidance"->"")),10.seconds)
      assertEquals(guidance("guidance").arr.size,0)
      val beforeEdit=Files.readAllBytes(editorFile).toVector
      opened("vectors")("clip_l")(0)(0)=ujson.Num(0.25)
      val savedEdit=Await.result(client.raw("embedding_editor",ujson.Obj("operation"->"save","name"->"editor_copy",
        "draft"->opened,"overwrite"->true)),10.seconds)
      assert(savedEdit("reloaded").bool)
      assertEquals(Files.readAllBytes(Path.of(savedEdit("backup").str)).toVector,beforeEdit)
      val reopened=Await.result(client.raw("embedding_editor",ujson.Obj("operation"->"open","name"->"editor_copy",
        "path"->editorFile.toString)),10.seconds)
      assertEquals(reopened("vectors")("clip_l")(0)(0).num,0.25)
      val generation=GenerationRequest(GenerationParams(prompt="editor_copy",width=16,height=16,steps=1,seed=42),temp.toString)
      def pixels(path: String): Vector[Int] =
        val image=javax.imageio.ImageIO.read(Path.of(path).toFile)
        image.getRGB(0,0,16,16,null,0,16).toVector
      val normal=Await.result(client.generate(generation,GenerationMode.Txt2Img,_=>()).result,30.seconds)
      val variedRequest=generation.copy(params=generation.params.copy(subseed=1337,subseedStrength=0.5))
      val varied=Await.result(client.generate(variedRequest,GenerationMode.Txt2Img,_=>()).result,30.seconds)
      val variedReplay=Await.result(client.generate(variedRequest,GenerationMode.Txt2Img,_=>()).result,30.seconds)
      assertNotEquals(pixels(varied.images.head.path),pixels(normal.images.head.path))
      assertEquals(pixels(varied.images.head.path),pixels(variedReplay.images.head.path))
      assertEquals(varied.images.head.subseed,1337L)
      val cpuRequest=generation.copy(params=generation.params.copy(extra=Map("rng"->"cpu")))
      val cpuImage=Await.result(client.generate(cpuRequest,GenerationMode.Txt2Img,_=>()).result,30.seconds)
      val cpuReplay=Await.result(client.generate(cpuRequest,GenerationMode.Txt2Img,_=>()).result,30.seconds)
      assertEquals(pixels(cpuImage.images.head.path),pixels(cpuReplay.images.head.path))
      assertNotEquals(pixels(cpuImage.images.head.path),pixels(normal.images.head.path))
      val snapshot=Files.readAllBytes(editorFile).toVector
      reopened("vectors")("clip_l")(0).arr.indices.foreach(i=>reopened("vectors")("clip_l")(0)(i)=ujson.Num(10))
      val preview=Await.result(client.generateOwned(generation,GenerationMode.Txt2Img,_=>(),None,
        editorPreview=Some(EditorPreviewPayload(reopened.render()))).result,30.seconds)
      val restored=Await.result(client.generate(generation,GenerationMode.Txt2Img,_=>()).result,30.seconds)
      assertNotEquals(pixels(preview.images.head.path),pixels(normal.images.head.path))
      assertEquals(pixels(restored.images.head.path),pixels(normal.images.head.path))
      assertEquals(Files.readAllBytes(editorFile).toVector,snapshot)
      val previewExtra=temp.resolve("preview_extra.safetensors")
      Files.copy(editorFile,previewExtra)
      val withExtra=generation.copy(params=generation.params.copy(prompt="editor_copy preview_extra"),
        embeddingPaths=Seq(previewExtra.toString))
      Await.result(client.generateOwned(withExtra,GenerationMode.Txt2Img,_=>(),None,
        editorPreview=Some(EditorPreviewPayload(reopened.render()))).result,30.seconds)
      val afterPreview=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"list",
        "embedding_paths"->ujson.Arr(previewExtra.toString))),10.seconds)
      assert(afterPreview("loaded").arr.exists(_("name").str=="preview_extra"))
      val restoredDraft=Await.result(client.raw("embedding_editor",ujson.Obj("operation"->"open",
        "name"->"editor_copy","path"->editorFile.toString)),10.seconds)
      assertEquals(restoredDraft("vectors")("clip_l")(0)(0).num,0.25)
      val ptOutput=temp.resolve("native_export.pt")
      val ptSaved=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"save",
        "rows"->ujson.Arr(ujson.Obj("name"->"#73","weight"->1)),"output"->ptOutput.toString,
        "embedding_paths"->ujson.Arr(),"step"->42)),10.seconds)
      assertEquals(ptSaved("path").str,ptOutput.toString)
      assert(Files.isRegularFile(ptOutput))
      val binOutput=temp.resolve("native_bin.bin")
      Files.copy(ptOutput,binOutput)
      val binRequest=generation.copy(params=generation.params.copy(prompt="native_bin"))
      val binBaseline=Await.result(client.generate(binRequest,GenerationMode.Txt2Img,_=>()).result,30.seconds)
      val binActive=Await.result(client.generate(binRequest.copy(embeddingPaths=Seq(binOutput.toString)),GenerationMode.Txt2Img,_=>()).result,30.seconds)
      assertNotEquals(pixels(binActive.images.head.path),pixels(binBaseline.images.head.path))
      val binListed=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"list",
        "embedding_paths"->ujson.Arr(binOutput.toString))),10.seconds)
      assert(binListed("loaded").arr.exists(_("name").str=="native_bin"))
      val ptOpen=Await.result(client.raw("embedding_editor",ujson.Obj("operation"->"open","name"->"native_export",
        "path"->ptOutput.toString)),10.seconds)
      assertEquals(ptOpen("vectors")("clip_l").arr.head.arr.map(_.num).toSeq,clip("vectors").arr.head.arr.map(_.num).toSeq)
      val originalPt=Files.readAllBytes(ptOutput).toVector
      ptOpen("vectors")("clip_l")(0)(0)=ujson.Num(0.125)
      val ptEdit=Await.result(client.raw("embedding_editor",ujson.Obj("operation"->"save","name"->"native_export",
        "draft"->ptOpen,"overwrite"->true)),10.seconds)
      assertEquals(Files.readAllBytes(Path.of(ptEdit("backup").str)).toVector,originalPt)
      val ptReopened=Await.result(client.raw("embedding_editor",ujson.Obj("operation"->"open","name"->"native_export",
        "path"->ptOutput.toString)),10.seconds)
      assertEquals(ptReopened("vectors")("clip_l")(0)(0).num,0.125)
      val presetPath=temp.resolve("preset_boost.safetensors")
      val preset=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"save",
        "rows"->ujson.Arr(ujson.Obj("name"->"#73","weight"->1)),"output"->presetPath.toString,
        "embedding_paths"->ujson.Arr(),"eval"->"=v*8")),10.seconds)
      val presetValues=preset("encoders")("clip_l")("vectors").arr.head.arr.map(_.num).toSeq
      val originalValues=clip("vectors").arr.head.arr.map(_.num).toSeq
      assert(presetValues.zip(originalValues).forall((actual,original)=>math.abs(actual-original*8)<1e-6))
      assert(Files.isRegularFile(presetPath))
      val hiresBase=GenerationRequest(GenerationParams(prompt="red apple",width=16,height=16,steps=2,seed=42,
        hires=HiresParams(enabled=true,secondPassSteps=2)),temp.toString)
      val firstHires=Await.result(client.generate(hiresBase,GenerationMode.Txt2Img,_=>()).result,45.seconds)
      val hiresChanged=hiresBase.copy(params=hiresBase.params.copy(hires=hiresBase.params.hires.copy(
        prompt="blue ocean",negativePrompt="grainy",cfgScale=Some(15),samplerName=Some("Euler"),scheduler=Some("Uniform"))))
      val secondHires=Await.result(client.generate(hiresChanged,GenerationMode.Txt2Img,_=>()).result,45.seconds)
      def hiresPixels(path: String)=javax.imageio.ImageIO.read(Path.of(path).toFile).getRGB(0,0,64,64,null,0,64).toVector
      assert(hiresPixels(firstHires.images.head.path)!=hiresPixels(secondHires.images.head.path))
      val channels=ujson.read(Files.readString(root.resolve("unet/config.json")))("block_out_channels")(0).num.toInt
      val adapter=temp.resolve("hires_lora.safetensors")
      val down=Array.fill(channels)(1f/channels);val up=Array.fill(channels*4)(80f)
      val sizeDown=down.length*4;val sizeUp=up.length*4
      val header=s"""{"unet.time_embedding.linear_1.lora_down.weight":{"dtype":"F32","shape":[1,$channels],"data_offsets":[0,$sizeDown]},"unet.time_embedding.linear_1.lora_up.weight":{"dtype":"F32","shape":[${channels*4},1],"data_offsets":[$sizeDown,${sizeDown+sizeUp}]}}""".getBytes(java.nio.charset.StandardCharsets.UTF_8)
      val adapterBytes=java.nio.ByteBuffer.allocate(8+header.length+sizeDown+sizeUp).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      adapterBytes.putLong(header.length.toLong).put(header);down.foreach(adapterBytes.putFloat);up.foreach(adapterBytes.putFloat)
      Files.write(adapter,adapterBytes.array())
      if sys.env.get("SCALATORCH_TEST_CATALOG_DOWNLOAD").contains("1") then
        val catalogDir=temp.resolve("live-catalog")
        val installed=Await.result(client.raw("model_keyword",ujson.Obj("action"->"install_upstream_lora_catalog",
          "mapping_dir"->catalogDir.toString,"confirmed"->true)),45.seconds)
        val installedPath=Path.of(installed("path").str)
        assertEquals(installedPath,catalogDir.resolve("lora-keyword.txt"))
        assertEquals(Files.size(installedPath),31395702L)
      val lora=LoraRef("hires_lora",1.0)
      val withLora=hiresBase.copy(params=hiresBase.params.copy(loras=Seq(lora)),loraPaths=Map("hires_lora"->adapter.toString))
      def loraResult(selected: Option[Seq[LoraRef]])=Await.result(client.generate(withLora.copy(hiresLoras=selected),
        GenerationMode.Txt2Img,_=>()).result,45.seconds)
      val inherited=loraResult(None);val same=loraResult(Some(Seq(lora)));val cleared=loraResult(Some(Seq.empty))
      assertEquals(hiresPixels(same.images.head.path),hiresPixels(inherited.images.head.path))
      assert(hiresPixels(cleared.images.head.path)!=hiresPixels(inherited.images.head.path))
      val keywordHook=ujson.Obj("_mapping_dir"->temp.toString,"lora"->"hires_lora",
        "_lora_path"->adapter.toString,"lora_name"->"hires_lora.safetensors",
        "embedding"->"native_mixed","multiplier"->0.5)
      val selectedKeyword=GenerationRequest(GenerationParams(prompt="cat",width=16,height=16,steps=1,seed=42,
        hooks=Map("model_keyword"->keywordHook)),temp.toString,loraPaths=Map("hires_lora"->adapter.toString),
        embeddingPaths=Seq(mixedFile.toString),manualLoras=Some(Seq.empty))
      val selectedResult=Await.result(client.generate(selectedKeyword,GenerationMode.Txt2Img,_=>()).result,30.seconds)
      assertEquals(selectedResult.images.head.effectivePrompt,Some("<lora:hires_lora:0.5> native_mixed cat"))
      val directKeyword=selectedKeyword.copy(params=selectedKeyword.params.copy(prompt="native_mixed cat",hooks=Map.empty,
        loras=Seq(LoraRef("hires_lora",0.5))),manualLoras=None)
      val directResult=Await.result(client.generate(directKeyword,GenerationMode.Txt2Img,_=>()).result,30.seconds)
      assertEquals(pixels(selectedResult.images.head.path),pixels(directResult.images.head.path))
      val batchPath=temp.resolve("preset_batch.safetensors")
      val batch=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"save",
        "rows"->ujson.Arr(ujson.Obj("name"->"#73","weight"->1)),"output"->batchPath.toString,
        "embedding_paths"->ujson.Arr(),"batch_presets"->true)),30.seconds)
      assertEquals(batch("paths").arr.size,9)
      assert(batch("paths").arr.forall(value=>Files.isRegularFile(Path.of(value.str))))
      val batchList=Await.result(client.raw("embedding_inspector",ujson.Obj("operation"->"list",
        "embedding_paths"->ujson.Arr())),10.seconds)
      assert(batchList("loaded").arr.exists(_("name").str=="preset_batch_Boost"))
      val japanese=Await.result(client.tokenize("日本語 中国語"),10.seconds)
      assertEquals(japanese.streams.head.ids,Seq(152,184,339,152,189,319,154,104,293,150,117,297,151,188,294,154,104,293))
      val ids=Await.result(client.tokenize("0,1","ids"),10.seconds)
      assertEquals(ids.streams.head.ids,Seq(0,1))
      assert(ids.streams.head.tokens.forall(_.special))
    finally
      supervisor.close()
      val paths=Files.walk(temp)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
