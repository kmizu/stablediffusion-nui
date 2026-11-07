package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata
import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

/** Verifies the separate pixel Hires hook crosses Generator and the native child-JVM seam. */
class NativeImg2ImgHiresGeneratorSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds

  test("Generator sends built-in pixel Hires refinement through scala-torch without Python") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cpFile.nonEmpty && model.nonEmpty,"native worker classpath and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-img2img-hires-generator"))
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
      val hello=services.start()
      assertEquals(hello.python,"")
      assert(hello.features.contains("img2img_hires_fix"))
      val choices=services.catalog.choices(sdfx.core.plugins.api.ChoiceSource.Upscalers)
      assert(choices.contains("Nearest") && choices.contains("Lanczos"))
      assert(services.catalog.choices(sdfx.core.plugins.api.ChoiceSource.HiresUpscalers).contains("Latent (bicubic)"))
      services.loadCheckpoint(root.toString,remember=false)
      val source=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 64; x <- 0 until 64 do source.setRGB(x,y,0xff305070)
      val input=home.root.resolve("input.png")
      require(javax.imageio.ImageIO.write(source,"png",input.toFile))
      val params=GenerationParams(prompt="a red cat",width=64,height=64,steps=1,seed=42,
        img2img=Some(Img2ImgParams(input.toString,denoisingStrength=1)),
        hooks=Map("img2img_hires_fix"->ujson.Obj("ratio"->2,"upscaler"->"Lanczos","steps"->1,"denoise"->1)))
      val prepared=services.generator.prepare(params)
      assertEquals(prepared.request.params.hooks("img2img_hires_fix")("upscaler").str,"Lanczos")
      val result=services.generator.generate(params).head
      val image=PngMetadata.readImage(result.path)
      assertEquals((image.getWidth,image.getHeight),(128,128),"Hires hook must publish the second-pass dimensions")
      val txtParams=GenerationParams(prompt="a red cat",width=64,height=64,steps=1,seed=42,
        hooks=Map("img2img_hires_fix"->ujson.Obj("ratio"->2,"upscaler"->"Nearest","steps"->1,"denoise"->1)))
      val txtImage=PngMetadata.readImage(services.generator.generate(txtParams).head.path)
      assertEquals((txtImage.getWidth,txtImage.getHeight),(128,128),"txt2img Hires must publish the refined image")
      assert(!Files.exists(home.pythonDir),"native img2img Hires must not bootstrap Python")
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

  test("Generator resolves and runs the official R-ESRGAN checkpoint in the native child JVM") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val upscaler=sys.env.get("SCALATORCH_REALESRGAN_MODEL")
    assume(cpFile.nonEmpty && model.nonEmpty && upscaler.nonEmpty,
      "native worker classpath, tiny model and Real-ESRGAN checkpoint required")
    val root=Path.of(model.get).toAbsolutePath
    val upscalerFile=Path.of(upscaler.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-realesrgan-hires-generator"))
    val scheduler=home.root.resolve("scheduler.json")
    Files.writeString(scheduler,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear"}""")
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=Some(cpFile.get),scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(scheduler.toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString),upscalers=Seq(upscalerFile.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    var failure: Throwable=null
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val source=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 64; x <- 0 until 64 do source.setRGB(x,y,((x*4)&255)<<16 | ((y*4)&255)<<8 | 0x5070)
      val input=home.root.resolve("input.png")
      require(javax.imageio.ImageIO.write(source,"png",input.toFile))
      val params=GenerationParams(prompt="a red cat",width=64,height=64,steps=1,seed=42,
        img2img=Some(Img2ImgParams(input.toString,denoisingStrength=1)),
        hooks=Map("img2img_hires_fix"->ujson.Obj("ratio"->2,"upscaler"->"R-ESRGAN 4x+","steps"->1,"denoise"->1)))
      val prepared=services.generator.prepare(params)
      assertEquals(prepared.request.upscalerPaths.get("R-ESRGAN 4x+"),Some(upscalerFile.toString))
      val result=services.generator.generate(params).head
      val image=PngMetadata.readImage(result.path)
      assertEquals((image.getWidth,image.getHeight),(128,128),"R-ESRGAN Hires must publish requested dimensions")
      assert(!Files.exists(home.pythonDir),"native R-ESRGAN Hires must not bootstrap Python")
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
