package sdfx.engine

import java.nio.file.{Files,Path,StandardCopyOption}
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{ControlNetUnit,GenerationParams}
import sdfx.core.png.PngMetadata

class NativeRealControlNetSuite extends munit.FunSuite:
  override val munitTimeout: Duration=15.minutes
  test("trained SD1.5 ControlNet runs through NUI on CUDA") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_SD15_MODEL")
    val control=sys.env.get("SCALATORCH_REAL_CONTROLNET")
    val input=sys.env.get("SCALATORCH_CONTROLNET_INPUT")
    val artifacts=sys.env.get("SCALATORCH_CONTROLNET_OUTPUT")
    assume(cp.nonEmpty && model.nonEmpty && control.nonEmpty && input.nonEmpty && artifacts.nonEmpty &&
      sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"real SD1.5, ControlNet and CUDA fixtures required")
    val root=Path.of(model.get).toRealPath(); val controlDir=Path.of(control.get).toRealPath()
    val source=Path.of(input.get).toRealPath(); val out=Path.of(artifacts.get).toAbsolutePath.normalize()
    require(!Files.exists(out),"test output directory already exists")
    Files.createDirectories(out)
    val home=AppHome(Files.createTempDirectory("nui-real-controlnet"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-explicit.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,
      scalaTorchVaeOffloadWeights=true,lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString),controlnet=Seq(controlDir.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      assertEquals(services.catalog.controlnets.map(_.path),Seq(controlDir.toString))
      val plain=GenerationParams(prompt="a red apple on a wooden table",negativePrompt="blurry",width=256,height=256,
        steps=4,seed=42,samplerName="Euler",scheduler="Uniform")
      val controlled=plain.copy(controlnet=Seq(ControlNetUnit(enabled=true,model=controlDir.getFileName.toString,
        image=Some(source.toString),preprocessor="canny",weight=1.0)))
      val a=services.generator.generate(plain).head.path
      val b=services.generator.generate(controlled).head.path
      val half=controlled.controlnet.head.copy(weight=0.5)
      val c=services.generator.generate(plain.copy(controlnet=Seq(half,half))).head.path
      val ai=PngMetadata.readImage(a);val bi=PngMetadata.readImage(b)
      val ci=PngMetadata.readImage(c)
      assertEquals((bi.getWidth,bi.getHeight),(256,256))
      assert((0 until 256).exists(y => (0 until 256).exists(x => ai.getRGB(x,y)!=bi.getRGB(x,y))),"ControlNet did not change output")
      assertEquals(ci.getRGB(0,0,256,256,null,0,256).toVector,bi.getRGB(0,0,256,256,null,0,256).toVector,
        "two half-weight units should match one full-weight unit")
      Files.copy(a,out.resolve("uncontrolled.png"),StandardCopyOption.COPY_ATTRIBUTES)
      Files.copy(b,out.resolve("controlled.png"),StandardCopyOption.COPY_ATTRIBUTES)
      Files.copy(c,out.resolve("two-controlnets.png"),StandardCopyOption.COPY_ATTRIBUTES)
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
