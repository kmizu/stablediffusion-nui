package sdfx.plugins

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.plugins.api.Values
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices,EngineLaunch,Job}

class NativeLoopbackIntegrationSuite extends munit.FunSuite:
  override val munitTimeout: Duration=240.seconds
  test("Color enhance plugin uses native worker and preserves reference output without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val source=sys.env.get("SCALATORCH_COLOR_SOURCE")
    val fixture=sys.env.get("SCALATORCH_ENHANCE_FIXTURE")
    assume(cp.nonEmpty && model.nonEmpty && source.nonEmpty && fixture.nonEmpty,"native worker and color fixtures required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-enhance-plugin"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cpu",scalaTorchVaeDevice="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry => { val path=Path.of(entry);(if path.isAbsolute then path else cpFile.getParent.resolve(path)).normalize().toString })
      .mkString(java.io.File.pathSeparator)
    val javaExe=sys.env.get("SCALATORCH_JAVA").map(Path.of(_)).getOrElse(
      Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java"))
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",root.toString,root.resolve("euler-demo.json").toString),root,helloTimeout=30.seconds)
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq(new ColorEnhancePlugin),launchOverride=Some(()=>launch),
      persistSettings=false,installShutdownHook=false)
    val ctx=new FakeContext():
      override def engineCall(kind: String,params: ujson.Obj): ujson.Value=services.generator.engineCall(kind,params)
    try
      assertEquals(services.start().python,"")
      val image=ImageIO.read(Path.of(source.get).toFile)
      val plugin=new ColorEnhancePlugin
      val results=plugin.transform(image,GenerationParams(),Values(Map("strength"->"1.0","percentile"->"99.5")),ctx)
      assertEquals(results.size,1)
      val (actual,metadata)=results.head
      val expected=ImageIO.read(Path.of(fixture.get).resolve("result.png").toFile)
      assertEquals((actual.getWidth,actual.getHeight),(expected.getWidth,expected.getHeight))
      var max=0
      for y <- 0 until actual.getHeight; x <- 0 until actual.getWidth; c <- 0 until 3 do
        val shift=16-c*8
        max=math.max(max,math.abs(((actual.getRGB(x,y)>>>shift)&255)-((expected.getRGB(x,y)>>>shift)&255)))
      assert(max<=2,s"plugin color enhancement max channel error $max")
      assert(metadata.exists(_._1=="color_enhance chroma_gain"))
      val leftovers=Files.list(ctx.dir)
      try assertEquals(leftovers.count(),0L,"plugin temporary input must be removed") finally leftovers.close()
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      for dir <- Seq(home.root,ctx.dir) do
        val paths=Files.walk(dir)
        try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
  test("NUI queues two native img2img loops and finishes their output without Python") {
    val cp=sys.props.get("scalatorch.worker.classpath").orElse(sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE"))
    val model=sys.props.get("scalatorch.tiny.model").orElse(sys.env.get("SCALATORCH_TINY_MODEL"))
    assume(cp.nonEmpty && model.nonEmpty,"native worker distribution and tiny model required")
    val modelRoot=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("nui-native-loopback"))
    val config=home.root.resolve("scheduler.json")
    Files.writeString(config,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear"}""")
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry => { val path=Path.of(entry);(if path.isAbsolute then path else cpFile.getParent.resolve(path)).normalize().toString })
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",modelRoot.toString,config.toString),modelRoot,helloTimeout=30.seconds)
    val settings=Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(modelRoot.getParent.toString)))
    val plugin=new LoopbackScalerPlugin
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq(plugin),launchOverride=Some(()=>launch),
      persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assert(hello.features.contains("loopback_scaler"),"native worker must advertise the complete Loopback plugin")
      assert(services.registry.unsupported(hello.features).isEmpty)
      services.loadCheckpoint(modelRoot.toString,remember=false)
      val source=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 64; x <- 0 until 64 do source.setRGB(x,y,((x*4)<<16)|((y*4)<<8)|64)
      val input=home.root.resolve("input.png");assert(ImageIO.write(source,"png",input.toFile))
      val params=GenerationParams(prompt="a red apple",width=64,height=64,steps=1,seed=42,samplerName="Euler",
        img2img=Some(Img2ImgParams(input.toString,denoisingStrength=1.0)))
      val values=Values(Map("loops"->"2","use_scale"->"true","scale"->"1.0","sharpness_strength"->"1.4",
        "brightness_strength"->"0.8","detail_strength"->"Low"))
      val result=Await.result(services.queue.submit(Job(params).copy(script=Some(plugin->values))),180.seconds)
      assertEquals(result.images.size,1)
      val finished=result.images.head
      val image=PngMetadata.readImage(finished.path)
      assertEquals((image.getWidth,image.getHeight),(64,64))
      assertEquals(finished.seed,43L)
      assertEquals(finished.params.extra("Sharpness"),"1.4")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
