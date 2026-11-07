package sdfx.engine

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{ControlNetUnit,GenerationParams,HiresParams}
import sdfx.core.png.PngMetadata
import sdfx.core.protocol.EngineError

class NativeControlNetCatalogSuite extends munit.FunSuite:
  override val munitTimeout: Duration=5.minutes
  test("NUI finds a directly configured ControlNet and rejects changed invalid weights") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val pipeline=sys.env.get("SCALATORCH_CONTROLNET_PIPELINE")
    val control=sys.env.get("SCALATORCH_TINY_CONTROLNET")
    assume(cp.nonEmpty && pipeline.nonEmpty && control.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"tiny CUDA fixtures required")
    val root=Path.of(pipeline.get).toRealPath()
    val original=Path.of(control.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("nui-controlnet-direct"))
    val copied=Files.createDirectories(home.root.resolve("controlnet"))
    for name <- Vector("config.json","diffusion_pytorch_model.safetensors") do Files.copy(original.resolve(name),copied.resolve(name))
    val image=home.root.resolve("input.png")
    assert(ImageIO.write(new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB),"png",image.toFile))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("scheduler_config.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString),controlnet=Seq(copied.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      assertEquals(services.catalog.controlnets.map(_.path),Seq(copied.toString))
      val params=GenerationParams(prompt="a cat",width=64,height=64,steps=1,seed=42,samplerName="Euler",scheduler="Uniform",
        controlnet=Seq(ControlNetUnit(enabled=true,model=copied.getFileName.toString,image=Some(image.toString),preprocessor="none")))
      val result=services.generator.generate(params)
      assertEquals(result.size,1)
      assert(Files.isRegularFile(result.head.path))
      val half=params.controlnet.head.copy(weight=0.5)
      val double=params.copy(controlnet=Seq(half,half))
      def pixels(path: Path): Vector[Int] =
        val image=PngMetadata.readImage(path)
        image.getRGB(0,0,image.getWidth,image.getHeight,null,0,image.getWidth).toVector
      val pair=services.generator.generate(double).head.path
      assertEquals(pixels(pair),pixels(result.head.path),"two half-weight units should match one full-weight unit")
      val hires=HiresParams(enabled=true,scale=2,upscaler="Latent",secondPassSteps=2,denoisingStrength=0.5)
      val oneHires=services.generator.generate(params.copy(hires=hires)).head.path
      val twoHires=services.generator.generate(double.copy(hires=hires)).head.path
      assertEquals(pixels(twoHires),pixels(oneHires),"ControlNet units must combine consistently in both Hires passes")
      Files.write(copied.resolve("diffusion_pytorch_model.safetensors"),Array[Byte](1,2,3))
      intercept[EngineError](services.generator.generate(params))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
