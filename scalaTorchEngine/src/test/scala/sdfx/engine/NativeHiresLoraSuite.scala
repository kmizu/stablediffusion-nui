package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,HiresParams,LoraRef}
import sdfx.core.png.PngMetadata
import sdfx.core.protocol.FileEntry
import java.nio.{ByteBuffer,ByteOrder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

class NativeHiresLoraSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds
  test("real SD1 checkpoint switches to a real convolution LoRA for refinement") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_SINGLE_FILE")
    val lora=sys.env.get("SCALATORCH_REAL_LORA")
    val tokenizer=sys.env.get("SCALATORCH_TEST_TOKENIZER")
    assume(cp.nonEmpty && model.nonEmpty && lora.nonEmpty && tokenizer.nonEmpty,"real model, LoRA, tokenizer and worker required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-real-hires-lora"))
    val target=Files.createDirectories(home.root.resolve("scala-torch/tokenizer"))
    Vector("vocab.json","merges.txt").foreach(name => Files.copy(Path.of(tokenizer.get).resolve(name),target.resolve(name)))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      services.catalog.set(services.catalog.snapshot.copy(loras=Seq(FileEntry("lcm",Path.of(lora.get).toRealPath().toString))))
      val params=GenerationParams(prompt="a red apple <lora:lcm:0.5>",width=64,height=64,steps=1,seed=42,
        hooks=Map("img2img_hires_fix"->ujson.Obj("ratio"->1,"upscaler"->"Nearest","steps"->1,
          "denoise"->1,"prompt"->"a red apple <lora:lcm:0.3>")))
      val prepared=services.generator.prepare(params).request
      assertEquals(prepared.params.loras.map(_.name),Seq("lcm"))
      assertEquals(prepared.img2imgHiresLoras.get.map(_.name),Seq("lcm"))
      assertEquals(prepared.img2imgHiresLoras.get.head.weight,0.3)
      val image=PngMetadata.readImage(services.generator.generate(params).head.path)
      assertEquals((image.getWidth,image.getHeight),(64,64))
      val base=GenerationParams(prompt="a red apple",width=64,height=64,steps=2,seed=42,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"))
      def pixels(value: GenerationParams) =
        val generated=PngMetadata.readImage(services.generator.generate(value).head.path)
        generated.getRGB(0,0,64,64,null,0,64).toVector
      val plain=pixels(base)
      val normal=pixels(base.copy(loras=Seq(LoraRef("lcm",weight=.5))))
      assertNotEquals(normal,plain)
      val all=pixels(base.copy(loras=Seq(LoraRef("lcm",weight=.5,lbw=Some("ALL")))))
      val none=pixels(base.copy(loras=Seq(LoraRef("lcm",weight=.5,lbw=Some("NONE")))))
      assertNotEquals(all,none)
      assert(!Files.exists(home.pythonDir))
    finally services.shutdown()
  }
  private def adapter(path: Path,scale: Float,attention: Boolean=false): Unit =
    val name=if attention then "lora_unet_mid_block_attentions_0_transformer_blocks_0_attn1_to_q" else "lora_unet_time_embedding_linear_1"
    val width=if attention then 64 else 32
    val height=if attention then 64 else 128
    val down=if attention then Array.tabulate(width)(i=>(math.sin(i*.31)*.2).toFloat) else Array.fill(width)(1f/width)
    val up=if attention then Array.tabulate(height)(i=>(math.cos(i*.23)*scale).toFloat) else Array.fill(height)(scale)
    val entries=Vector((s"$name.lora_down.weight",Vector(1,width),down),
      (s"$name.lora_up.weight",Vector(height,1),up))
    var offset=0
    val header=entries.map { (key,dims,values) =>
      val start=offset;offset+=values.length*4
      s"\"$key\":{\"dtype\":\"F32\",\"shape\":[${dims.mkString(",")}],\"data_offsets\":[$start,$offset]}"
    }.mkString("{",",","}").getBytes(UTF_8)
    val bytes=ByteBuffer.allocate(8+header.length+offset).order(ByteOrder.LITTLE_ENDIAN)
    bytes.putLong(header.length.toLong).put(header)
    entries.foreach((_,_,values) => values.foreach(bytes.putFloat))
    Files.write(path,bytes.array())

  test("Generator forwards dynamic LoRA rank to the native worker") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-dynamic-lora-nui"))
    val loraDir=Files.createDirectories(home.root.resolve("models/lora"))
    val lora=loraDir.resolve("test.SAFETENSORS");adapter(lora,8f)
    val attention=loraDir.resolve("attention.safetensors");adapter(attention,30f,attention=true)
    sys.env.get("SCALATORCH_TINY_PT_LORA").foreach { archive =>
      Files.copy(Path.of(archive),loraDir.resolve("torch.ckpt"))
    }
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,
      scalaTorchVaeOffloadWeights=true,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      services.catalog.refresh()
      assertEquals(services.catalog.findLora("test").map(_.path),Some(lora.toString))
      assertEquals(services.catalog.findLora("attention").map(_.path),Some(attention.toString))
      val base=GenerationParams(prompt="a red cat",width=64,height=64,steps=2,seed=42,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"))
      def pixels(params: GenerationParams) =
        val image=PngMetadata.readImage(services.generator.generate(params).head.path)
        image.getRGB(0,0,64,64,null,0,64).toVector
      val plain=pixels(base)
      val pixelHires=HiresParams(enabled=true,scale=1,upscaler="Nearest",secondPassSteps=2,denoisingStrength=.5)
      val nearestHires=pixels(base.copy(hires=pixelHires))
      assertEquals(pixels(base.copy(hires=pixelHires.copy(upscaler="Lanczos"))),nearestHires)
      assertNotEquals(pixels(base.copy(hires=pixelHires.copy(upscaler="Latent (nearest)"))),nearestHires)
      val cropped=services.generator.generate(base.copy(hires=pixelHires.copy(resizeX=128,resizeY=72))).head
      val croppedImage=PngMetadata.readImage(cropped.path)
      assertEquals((croppedImage.getWidth,croppedImage.getHeight),(128,72))
      val zero=base.copy(loras=Seq(LoraRef("test",dyn=Some(0))))
      val full=base.copy(loras=Seq(LoraRef("test",dyn=Some(1))))
      assertEquals(services.generator.prepare(zero).request.params.loras.head.dyn,Some(0))
      assertEquals(pixels(zero),plain)
      val fullPixels=pixels(full)
      assertNotEquals(fullPixels,plain)
      sys.env.get("SCALATORCH_TINY_PT_LORA").foreach { archive =>
        assertEquals(services.catalog.findLora("torch").map(_.path),Some(loraDir.resolve("torch.ckpt").toString))
        assertEquals(pixels(base.copy(loras=Seq(LoraRef("torch",dyn=Some(1))))),fullPixels)
        assertEquals(pixels(base.copy(prompt="a red cat <lora:torch:1:dyn=1>")),fullPixels)
      }
      assertEquals(pixels(base.copy(prompt="a red cat <lora:test:1:dyn=0>")),plain)
      assertEquals(pixels(base.copy(prompt="a red cat <lora:test:1:dyn=1>")),fullPixels)
      assertEquals(pixels(base.copy(loras=Seq(LoraRef("test",start=Some(3))))),plain)
      assertEquals(pixels(base.copy(loras=Seq(LoraRef("test",start=Some(1),stop=Some(3))))),fullPixels)
      assertEquals(pixels(base.copy(prompt="a red cat <lora:test:1:start=3>")),plain)
      assertEquals(pixels(base.copy(prompt="a red cat <lora:test:1:step=3-5>")),plain)
      assertEquals(pixels(base.copy(prompt="a red cat <lora:test:1:step=1-3>")),fullPixels)
      val attentionFull=pixels(base.copy(loras=Seq(LoraRef("attention"))))
      assertNotEquals(attentionFull,plain)
      assertEquals(pixels(base.copy(loras=Seq(LoraRef("attention",lbw=Some("ALL"))))),attentionFull)
      assertEquals(pixels(base.copy(prompt="a red cat <lora:attention:1:lbw=NONE>")),plain)
      val combinedBase=base.copy(steps=4)
      val combinedPlain=pixels(combinedBase)
      val combinedFull=pixels(combinedBase.copy(loras=Seq(LoraRef("attention",lbw=Some("MIDD"),dyn=Some(1)))))
      val combinedParams=combinedBase.copy(loras=Seq(LoraRef("attention",lbw=Some("MIDD"),dyn=Some(1),start=Some(2))))
      val combined=pixels(combinedParams)
      assertNotEquals(combined,combinedPlain)
      assertNotEquals(combined,combinedFull)
      assertEquals(pixels(combinedBase.copy(prompt="a red cat <lora:attention:1:lbw=MIDD:dyn=1:start=2>")),combined)
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }

  test("Generator switches LoRA for pixel Hires refinement") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty,"native worker and tiny checkpoint required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-hires-lora"))
    val a=home.root.resolve("base.safetensors");val b=home.root.resolve("refine.safetensors")
    adapter(a,8f);adapter(b,-8f)
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=cp,scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cpu",modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      services.catalog.set(services.catalog.snapshot.copy(loras=Seq(FileEntry("base",a.toString),FileEntry("refine",b.toString))))
      val params=GenerationParams(prompt="a cat <lora:base:1>",width=64,height=64,steps=1,seed=42,
        hooks=Map("img2img_hires_fix"->ujson.Obj("ratio"->1,"upscaler"->"Nearest","steps"->1,
          "denoise"->1,"prompt"->"a cat <lora:refine:1>")))
      val prepared=services.generator.prepare(params).request
      assertEquals(prepared.img2imgHiresLoras.get.map(_.name),Seq("refine"))
      assert(prepared.loraPaths.keySet.contains("base") && prepared.loraPaths.keySet.contains("refine"))
      val image=PngMetadata.readImage(services.generator.generate(params).head.path)
      assertEquals((image.getWidth,image.getHeight),(64,64))
      assert(!Files.exists(home.pythonDir))
    finally services.shutdown()
  }
