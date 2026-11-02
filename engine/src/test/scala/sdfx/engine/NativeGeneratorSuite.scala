package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.infotext.Infotext
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

/** Real Generator saving/replay using a Java worker and an isolated app home. */
class NativeGeneratorSuite extends munit.FunSuite:
  override val munitTimeout: Duration=90.seconds
  test("native Generator saves img2img metadata and replays pixels with the same source") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cpFile.nonEmpty && model.nonEmpty,"native worker classpath and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-generator"))
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

  test("Generator routes SDXL through the selected backend and replays size/crop metadata") {
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
      val edit=services.generator.generate(params.copy(steps=4,
        img2img=Some(Img2ImgParams(text.path.toString,denoisingStrength=0.5,resizeMode=2)))).head
      val recorded=PngMetadata.readParameters(edit.path).getOrElse(fail("missing SDXL PNG infotext"))
      val parsed=Infotext.parse(recorded)
      assertEquals(parsed.prompt,params.prompt)
      assertEquals(parsed.negativePrompt,params.negativePrompt)
      assertEquals(parsed.get("Seed"),Some("42"))
      assertEquals(PngMetadata.readImage(edit.path).getWidth,64)
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
