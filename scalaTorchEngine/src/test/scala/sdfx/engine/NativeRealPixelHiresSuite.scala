package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,HiresParams}
import sdfx.core.png.PngMetadata
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

class NativeRealPixelHiresSuite extends munit.FunSuite:
  override val munitTimeout: Duration=10.minutes
  test("real SD1.5 pixel Hires runs through NUI on CUDA") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_REAL_MODEL")
    val output=sys.env.get("SCALATORCH_HIRES_OUTPUT")
    assume(cp.nonEmpty && model.nonEmpty && output.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"real CUDA fixture required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-real-pixel-hires"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val hires=HiresParams(enabled=true,scale=2,upscaler="Lanczos",secondPassSteps=2,denoisingStrength=.5)
      val request=GenerationParams(prompt="a red barn in a green field, overcast daylight",width=256,height=256,
        steps=2,seed=4260,samplerName="Euler",scheduler="Uniform",hires=hires)
      val result=services.generator.generate(request).head.path
      val image=PngMetadata.readImage(result)
      assertEquals((image.getWidth,image.getHeight),(512,512))
      Files.copy(result,Path.of(output.get))
      assert(!Files.exists(home.pythonDir))
    finally services.shutdown()
  }
