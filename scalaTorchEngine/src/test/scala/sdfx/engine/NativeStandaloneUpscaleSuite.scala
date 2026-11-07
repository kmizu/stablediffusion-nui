package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.png.PngMetadata
import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.duration.*

/** Optional application-to-worker acceptance for standalone native upscale. */
class NativeStandaloneUpscaleSuite extends munit.FunSuite:
  override val munitTimeout: Duration=3.minutes
  test("Generator upscale works with the native provider and no Python") {
    val model=sys.env.get("SCALATORCH_TEST_MODEL")
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    assume(model.nonEmpty && cp.nonEmpty,"native test model and worker classpath required")
    val root=Path.of(model.get).toRealPath()
    val appHome=AppHome(Files.createTempDirectory("native-upscale-nui"))
    val upscalers=Files.createDirectory(appHome.root.resolve("upscalers"))
    val modelPath=upscalers.resolve("RealESRGAN_x4plus.pth")
    Files.write(modelPath,Array[Byte](1))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=cp,scalaTorchModel=Some(root.toString),
      scalaTorchScheduler=Some(root.resolve("scheduler/scheduler_config.json").toString),
      hardwareInfoEnabled=true,hardwareInfoRemark="native upscale test",
      modelDirs=ModelDirs(upscalers=Seq(upscalers.toString)))
    val services=new AppServices(appHome,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      assertEquals(services.catalog.findUpscaler("R-ESRGAN 4x+").map(_.path),Some(modelPath.toString))
      val input=appHome.root.resolve("input.png")
      val source=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 2; x <- 0 until 3 do
        val n=10*(1+y*3+x);source.setRGB(x,y,0xff000000 | (n<<16) | (n<<8) | n)
      assert(ImageIO.write(source,"png",input.toFile))
      val result=services.generator.upscaleImage(input,"Nearest",1.5)
      val image=PngMetadata.readImage(result)
      assertEquals((image.getWidth,image.getHeight),(4,3))
      assertEquals((for y <- 0 until 3; x <- 0 until 4 yield image.getRGB(x,y)&255).toVector,
        Vector(10,20,20,30,40,50,50,60,40,50,50,60))
      val metadata=PngMetadata.readTexts(result)(PngMetadata.ParametersKey)
      assert(metadata.contains("Hardware Info:"))
      assert(metadata.contains("Time taken:"))
      assert(metadata.contains("native upscale test"))
      assert(!Files.exists(appHome.pythonDir))
    finally services.shutdown()
  }

  test("Generator resolves and runs the official Real-ESRGAN model on CUDA") {
    val model=sys.env.get("SCALATORCH_TEST_MODEL")
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val realesrgan=sys.env.get("SCALATORCH_REAL_ESRGAN")
    assume(model.nonEmpty && cp.nonEmpty && realesrgan.nonEmpty,"native model, worker classpath and Real-ESRGAN required")
    val root=Path.of(model.get).toRealPath()
    val upscaler=Path.of(realesrgan.get).toRealPath()
    val appHome=AppHome(Files.createTempDirectory("native-realesrgan-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=cp,scalaTorchModel=Some(root.toString),
      scalaTorchScheduler=Some(root.resolve("scheduler/scheduler_config.json").toString),
      scalaTorchUnetDevice="cuda",hardwareInfoEnabled=true,hardwareInfoRemark="cuda upscale test",
      modelDirs=ModelDirs(upscalers=Seq(upscaler.getParent.toString)))
    val services=new AppServices(appHome,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      assert(services.catalog.findUpscaler("R-ESRGAN 4x+").exists(e => Files.isSameFile(Path.of(e.path),upscaler)))
      val input=appHome.root.resolve("input.png")
      val source=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 16; x <- 0 until 16 do source.setRGB(x,y,if (x+y)%2==0 then 0xffcc3820 else 0xff2040aa)
      assert(ImageIO.write(source,"png",input.toFile))
      val result=services.generator.upscaleImage(input,"R-ESRGAN 4x+",2)
      val image=PngMetadata.readImage(result)
      assertEquals((image.getWidth,image.getHeight),(32,32))
      val metadata=PngMetadata.readTexts(result)(PngMetadata.ParametersKey)
      assert(metadata.contains("Hardware Info:"))
      assert(metadata.contains("cuda upscale test"))
      val larger=PngMetadata.readImage(services.generator.upscaleImage(input,"R-ESRGAN 4x+",5))
      assertEquals((larger.getWidth,larger.getHeight),(80,80))
      assert(!Files.exists(appHome.pythonDir))
    finally services.shutdown()
  }
