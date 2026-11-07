package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,HiresParams,Img2ImgParams}
import sdfx.core.png.PngMetadata
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

/** Verifies NUI Hires settings cross Generator and the replaceable child-JVM backend seam. */
class NativeHiresGeneratorSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds

  test("Generator sends latent Hires through scala-torch and saves the final-sized PNG without Python") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cpFile.nonEmpty && model.nonEmpty,"native worker classpath and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-hires-generator"))
    val scheduler=home.root.resolve("scheduler.json")
    Files.writeString(scheduler,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear"}""")
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=Some(cpFile.get),scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(scheduler.toString),
      scalaTorchUnetDevice="cpu",scalaTorchVaeDevice="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    var failure: Throwable=null
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val hires=HiresParams(enabled=true,scale=2.0,upscaler="Latent (bicubic)",secondPassSteps=1,
        denoisingStrength=0.55)
      val params=GenerationParams(prompt="a red cat",width=64,height=64,steps=1,seed=42,hires=hires)
      val prepared=services.generator.prepare(params)
      assertEquals(prepared.request.params.hires,hires,"Generator serialization must retain Hires settings")
      val first=services.generator.generate(params).head
      val firstImage=PngMetadata.readImage(first.path)
      assertEquals((firstImage.getWidth,firstImage.getHeight),(128,128),"Hires PNG must use the second-pass dimensions")
      val img2imgParams=params.copy(img2img=Some(Img2ImgParams(first.path.toString,
        denoisingStrength=0.5,resizeMode=0)))
      assertEquals(services.generator.prepare(img2imgParams).request.params.hires,hires,
        "Generator serialization must retain latent Hires settings on img2img")
      val refined=services.generator.generate(img2imgParams).head
      val refinedImage=PngMetadata.readImage(refined.path)
      assertEquals((refinedImage.getWidth,refinedImage.getHeight),(128,128),
        "img2img latent Hires PNG must use the second-pass dimensions")
      val combined=params.copy(hires=hires.copy(scale=1.0),
        hooks=Map("img2img_hires_fix"->ujson.Obj("ratio"->1.25,"upscaler"->"Nearest","steps"->1,"denoise"->1)))
      val combinedImage=PngMetadata.readImage(services.generator.generate(combined).head.path)
      assertEquals((combinedImage.getWidth,combinedImage.getHeight),(80,80),
        "pixel Hires Fix must follow the latent Hires pass")
      assert(!Files.exists(home.pythonDir),"native Hires must not bootstrap Python")
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
