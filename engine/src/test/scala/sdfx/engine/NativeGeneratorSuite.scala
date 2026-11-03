package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.infotext.Infotext
import sdfx.core.model.{GenerationParams,Img2ImgParams,LoraRef}
import sdfx.core.png.PngMetadata
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer,ByteOrder}
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.duration.*

/** Real Generator saving/replay using a Java worker and an isolated app home. */
class NativeGeneratorSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds
  private def widenTinyInpaintModel(source: Path,target: Path): Unit =
    import scala.jdk.CollectionConverters.*
    val paths=Files.walk(source)
    try paths.iterator().asScala.foreach { path =>
      val destination=target.resolve(source.relativize(path))
      if Files.isDirectory(path) then Files.createDirectories(destination)
      else Files.copy(path,destination)
    } finally paths.close()
    val weights=target.resolve("unet/diffusion_pytorch_model.safetensors")
    val bytes=Files.readAllBytes(weights)
    val headerLength=ByteBuffer.wrap(bytes,0,8).order(ByteOrder.LITTLE_ENDIAN).getLong.toInt
    val dataStart=8+headerLength
    val original=ujson.read(new String(bytes,8,headerLength,java.nio.charset.StandardCharsets.UTF_8)).obj
    val metadata=original.get("__metadata__")
    val tensors=original.toVector.filterNot(_._1=="__metadata__").map { (name,value) =>
      val fields=value.obj
      val from=fields("data_offsets")(0).num.toInt
      val until=fields("data_offsets")(1).num.toInt
      val payload=java.util.Arrays.copyOfRange(bytes,dataStart+from,dataStart+until)
      if name=="conv_in.weight" then
        val shape=fields("shape").arr.map(_.num.toInt).toVector
        require(fields("dtype").str=="F32" && shape.size==4 && shape(1)==4,
          "tiny fixture conv_in must be F32 with four input channels")
        val (out,_,height,width)=shape match
          case Vector(o,i,h,w) => (o,i,h,w)
          case _ => throw IllegalArgumentException("invalid tiny fixture conv_in shape")
        val sourceWeights=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val widened=ByteBuffer.allocate(out*9*height*width*4).order(ByteOrder.LITTLE_ENDIAN)
        for o <- 0 until out; c <- 0 until 9; y <- 0 until height; x <- 0 until width do
          if c<4 then widened.putFloat(sourceWeights.getFloat(((o*4+c)*height+y)*width*4+x*4))
          else widened.putFloat(.025f*(c-3))
        fields("shape")=ujson.Arr(out,9,height,width)
        (name,value,widened.array())
      else (name,value,payload)
    }.sortBy { (_,value,_) => value("data_offsets")(0).num }
    var offset=0L
    val (tensorHeaders,payloads)=tensors.map { (name,value,payload) =>
      val begin=offset;offset+=payload.length
      val fields=value.obj
      val shape=fields("shape").arr.map(_.num.toLong).mkString(",")
      val json=s"\"$name\":{\"dtype\":\"${fields("dtype").str}\",\"shape\":[$shape],\"data_offsets\":[$begin,$offset]}"
      (json,payload)
    }.unzip
    val metadataHeader=metadata.map(value=>s"\"__metadata__\":${ujson.write(value)}")
    val encoded=("{"+(metadataHeader++tensorHeaders).mkString(",")+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val output=ByteArrayOutputStream()
    output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(encoded.length.toLong).array())
    output.write(encoded)
    payloads.foreach(output.write)
    Files.write(weights,output.toByteArray)
    val config=target.resolve("unet/config.json")
    val modelConfig=ujson.read(Files.readString(config))
    require(modelConfig("in_channels").num.toInt==4,"tiny fixture U-Net must have four input channels")
    modelConfig("in_channels")=ujson.Num(9)
    Files.writeString(config,ujson.write(modelConfig))

  private def writeLinearLora(path: Path,name: String,input: Int,output: Int): Unit =
    val entries=Vector((s"$name.lora_down.weight",Vector(1,input),Array.fill(input)(1f/input)),
      (s"$name.lora_up.weight",Vector(output,1),Array.fill(output)(8f)))
    var offset=0
    val header=entries.map { (key,dims,values) =>
      val begin=offset;offset+=values.length*4
      s"\"$key\":{\"dtype\":\"F32\",\"shape\":[${dims.mkString(",")}],\"data_offsets\":[$begin,$offset]}"
    }.mkString("{",",","}").getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val data=ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN)
    entries.foreach((_,_,values)=>values.foreach(data.putFloat))
    Files.write(path,ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.length.toLong).array()++header++data.array())

  test("native Generator saves img2img metadata and replays pixels with the same source") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cpFile.nonEmpty && model.nonEmpty,"native worker classpath and tiny model required")
    val sourceRoot=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-generator"))
    val root=home.root.resolve("tiny-nine-channel")
    widenTinyInpaintModel(sourceRoot,root)
    val config=home.root.resolve("scheduler.json")
    Files.writeString(config,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear"}""")
    val launch=ScalaTorchLaunch.fromEnvironment(Map("SDFX_SCALATORCH_CLASSPATH_FILE"->cpFile.get,
      "SDFX_SCALATORCH_MODEL"->root.toString,"SDFX_SCALATORCH_SCHEDULER"->config.toString)).get
    val settings=Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,
      launchOverride=Some(() => launch),persistSettings=false,installShutdownHook=false)
    var failure: Throwable=null
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val unet=ujson.read(Files.readString(root.resolve("unet/config.json")))
      val input=unet("block_out_channels")(0).num.toInt
      val outChannels=unet.obj.get("time_embedding_dim").filter(_!=ujson.Null).map(_.num.toInt).getOrElse(input*4)
      val adapter=home.root.resolve("tiny-native.safetensors")
      writeLinearLora(adapter,"lora_unet_time_embedding_linear_1",input,outChannels)
      services.catalog.set(services.catalog.snapshot.copy(loras=Seq(
        sdfx.core.protocol.FileEntry("tiny-native",adapter.toString))))
      val loraParams=GenerationParams(prompt="a red cat",width=64,height=64,steps=1,seed=42)
      val baseline=services.generator.generate(loraParams).head
      val zeroLora=loraParams.copy(loras=Seq(LoraRef("tiny-native",weight=0.0,unet=Some(0.0))))
      assertEquals(services.generator.prepare(zeroLora).request.loraPaths("tiny-native"),adapter.toString)
      val zero=services.generator.generate(zeroLora).head
      val smallPixels=(path: Path) => PngMetadata.readImage(path).getRGB(0,0,64,64,null,0,64).toVector
      assertEquals(smallPixels(zero.path),smallPixels(baseline.path),"zero-scale NUI LoRA must be a no-op")
      val active=services.generator.generate(loraParams.copy(loras=Seq(LoraRef("tiny-native",weight=1.0,unet=Some(1.0))))).head
      val activePixels=smallPixels(active.path)
      assert(activePixels!=smallPixels(baseline.path),"NUI Generator LoRA request must affect native output")
      assertEquals(smallPixels(services.generator.generate(loraParams.copy(loras=Seq(LoraRef("tiny-native",weight=1.0,unet=Some(1.0))))).head.path),
        activePixels,"native NUI LoRA replay must be deterministic")
      assertEquals(smallPixels(services.generator.generate(loraParams).head.path),smallPixels(baseline.path),
        "adapter-free NUI request must return to the base model")
      val batch=services.generator.generate(loraParams.copy(batchSize=2,nIter=2,seed=80))
      assertEquals(batch.size,4,"NUI batch_size and n_iter must produce their full product")
      assertEquals(batch.zipWithIndex.map { (result,index) =>
        val metadata=Infotext.parse(PngMetadata.readParameters(result.path).getOrElse(fail("missing batch PNG parameters")))
        (metadata.get("Seed"),metadata.get("Batch size"),metadata.get("Batch count"))
      },Vector((Some("80"),Some("2"),Some("2")),(Some("81"),Some("2"),Some("2")),
        (Some("82"),Some("2"),Some("2")),(Some("83"),Some("2"),Some("2"))))
      val source=services.generator.generate(GenerationParams(prompt="source",width=64,height=64,steps=1,seed=41)).head
      val params=GenerationParams(prompt="a red cat",negativePrompt="blurry",width=64,height=64,steps=4,seed=42,
        img2img=Some(Img2ImgParams(source.path.toString,denoisingStrength=0.5,resizeMode=2)))
      val output=services.generator.generate(params).head
      assertEquals(output.path.getParent.getParent.getFileName.toString,"img2img")
      val text=PngMetadata.readParameters(output.path).getOrElse(fail("missing PNG parameters"))
      val parsed=Infotext.parse(text)
      assertEquals(parsed.prompt,params.prompt)
      assertEquals(parsed.negativePrompt,params.negativePrompt)
      assertEquals(parsed.get("Seed"),Some("42"))
      assertEquals(parsed.get("Denoising strength"),Some("0.5"))
      assertEquals(parsed.get("Resize mode"),Some("2"))
      val replayParams=Infotext.toParams(parsed,GenerationParams(img2img=Some(Img2ImgParams(source.path.toString))))
      assertEquals(replayParams.steps,4)
      assertEquals(replayParams.img2img.map(_.denoisingStrength),Some(0.5))
      val replay=services.generator.generate(replayParams).head
      def pixels(path: Path)=
        val image=PngMetadata.readImage(path)
        assertEquals(image.getWidth,64);assertEquals(image.getHeight,64)
        image.getRGB(0,0,64,64,null,0,64).toVector
      assertEquals(pixels(replay.path),pixels(output.path))
      val wideSource=home.root.resolve("fullres-source.png")
      val wideMask=home.root.resolve("fullres-mask.png")
      val canvas=new BufferedImage(80,48,BufferedImage.TYPE_INT_RGB)
      val mask=new BufferedImage(80,48,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 48; x <- 0 until 80 do
        canvas.setRGB(x,y,0xffff0000)
        mask.setRGB(x,y,if x>=30 && x<50 && y>=14 && y<34 then 0xffffffff else 0xff000000)
      ImageIO.write(canvas,"png",wideSource.toFile)
      ImageIO.write(mask,"png",wideMask.toFile)
      val fullRes=services.generator.generate(GenerationParams(prompt="edit the selected region",width=64,height=64,
        steps=1,seed=44,img2img=Some(Img2ImgParams(wideSource.toString,mask=Some(wideMask.toString),
          denoisingStrength=1.0,resizeMode=0,maskBlur=0,inpaintingFill=2,inpaintFullRes=true,inpaintFullResPadding=8)))).head
      val restored=PngMetadata.readImage(fullRes.path)
      assertEquals((restored.getWidth,restored.getHeight),(80,48),"full-resolution inpaint must restore the source canvas size")
      assertEquals((restored.getRGB(0,0)&0xffffff),0xff0000,"pixels outside the mask must remain from the source image")
      assert((restored.getRGB(40,24)&0xffffff)!=0xff0000,"the selected region must contain generated pixels")
      val blurredFill=services.generator.generate(GenerationParams(prompt="blur-fill the selected region",
        width=64,height=64,steps=1,seed=45,img2img=Some(Img2ImgParams(wideSource.toString,mask=Some(wideMask.toString),
          denoisingStrength=1.0,resizeMode=0,maskBlur=0,inpaintingFill=0,inpaintFullRes=true,inpaintFullResPadding=8)))).head
      val blurredImage=PngMetadata.readImage(blurredFill.path)
      assertEquals((blurredImage.getWidth,blurredImage.getHeight),(80,48))
      assertEquals((blurredImage.getRGB(0,0)&0xffffff),0xff0000,"blur-fill must preserve pixels outside the mask")
      assert((blurredImage.getRGB(40,24)&0xffffff)!=0xff0000,"blur-fill request must generate the selected region")
      assert(!Files.exists(home.pythonDir))
    catch case error: Throwable =>
      failure=error
      throw error
    finally
      try
        services.shutdown()
        val files=Files.walk(home.root)
        try files.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => {
          require(p.toAbsolutePath.normalize().startsWith(home.root.toAbsolutePath.normalize()))
          Files.delete(p);()
        }) finally files.close()
      catch case cleanup: Throwable =>
        if failure!=null then failure.addSuppressed(cleanup) else throw cleanup
  }

    test("Generator routes masked SDXL through the selected backend and replays metadata") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_SDXL_MODEL")
    assume(cpFile.nonEmpty && model.nonEmpty,"native worker classpath and tiny SDXL model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-generator-sdxl"))
    val scheduler=home.root.resolve("scheduler.json")
    Files.writeString(scheduler,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear"}""")
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=Some(cpFile.get),scalaTorchModel=Some(root.toString),
      scalaTorchScheduler=Some(scheduler.toString),scalaTorchUnetDevice="cuda",
      scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    var failure: Throwable=null
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="a red apple",negativePrompt="blurry",width=64,height=64,steps=3,seed=42,
        extra=Map("sdfx_sdxl_original_height"->"80","sdfx_sdxl_original_width"->"96",
          "sdfx_sdxl_crop_top"->"2","sdfx_sdxl_crop_left"->"4"))
      val text=services.generator.generate(params).head
      val textPixels=PngMetadata.readImage(text.path)
      assertEquals(textPixels.getWidth,64);assertEquals(textPixels.getHeight,64)
      val maskPath=home.root.resolve("mask.png")
      val mask=new BufferedImage(64,64,BufferedImage.TYPE_BYTE_GRAY)
      for y <- 0 until 64; x <- 0 until 64 do mask.setRGB(x,y,if x<32 then 0xff000000 else 0xffffffff)
      ImageIO.write(mask,"png",maskPath.toFile)
      val edit=services.generator.generate(params.copy(steps=4,
        img2img=Some(Img2ImgParams(text.path.toString,mask=Some(maskPath.toString),
          denoisingStrength=0.5,resizeMode=0,maskBlur=0)))).head
      val recorded=PngMetadata.readParameters(edit.path).getOrElse(fail("missing SDXL PNG infotext"))
      val parsed=Infotext.parse(recorded)
      assertEquals(parsed.prompt,params.prompt)
      assertEquals(parsed.negativePrompt,params.negativePrompt)
      assertEquals(parsed.get("Seed"),Some("42"))
      val editedPixels=PngMetadata.readImage(edit.path)
      assertEquals(editedPixels.getWidth,64)
      for y <- 0 until 64; x <- 0 until 32 do assertEquals(editedPixels.getRGB(x,y),textPixels.getRGB(x,y))
      assert((32 until 64).exists(x => (0 until 64).exists(y => editedPixels.getRGB(x,y)!=textPixels.getRGB(x,y))))
      assertEquals(!Files.exists(home.pythonDir),true)
      assert(services.supervisor.isRunning)
    catch case error: Throwable =>
      failure=error
      throw error
    finally
      try
        services.shutdown()
        val files=Files.walk(home.root)
        try files.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => {
          require(p.toAbsolutePath.normalize().startsWith(home.root.toAbsolutePath.normalize()))
          Files.delete(p);()
        }) finally files.close()
      catch case cleanup: Throwable =>
        if failure!=null then failure.addSuppressed(cleanup) else throw cleanup
  }
