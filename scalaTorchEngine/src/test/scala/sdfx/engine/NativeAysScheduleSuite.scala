package sdfx.engine

import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.png.PngMetadata

class NativeAysScheduleSuite extends munit.FunSuite:
  override val munitTimeout: Duration=10.minutes
  test("NUI generates with Euler and Align Your Steps through the Python-free CUDA worker") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val refs=sys.env.get("SCALATORCH_AYS_REFERENCE_DIR")
    assume(cp.nonEmpty && model.nonEmpty && refs.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker and fixture required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-ays"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,
      scalaTorchVaeOffloadWeights=true,lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="a red cat",width=64,height=64,steps=4,seed=42,cfgScale=7.5,
        samplerName="Euler",scheduler="Align Your Steps",extra=Map("rng"->"cpu"))
      val result=services.generator.generate(params).head
      val actual=PngMetadata.readImage(result.path)
      val expected=ImageIO.read(Path.of(refs.get).resolve("ays-euler-reference.png").toFile)
      val differences=for y<-0 until 64; x<-0 until 64; shift<-Vector(0,8,16) yield
        math.abs(((actual.getRGB(x,y)>>>shift)&255)-((expected.getRGB(x,y)>>>shift)&255))
      assert(differences.max<=1 && differences.sum.toDouble/differences.size<0.01,
        s"max=${differences.max} mean=${differences.sum.toDouble/differences.size}")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }

  test("real SD1.5 runs AYS on the 4GB CUDA device without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_SD15_MODEL")
    val output=sys.env.get("SCALATORCH_AYS_REAL_OUTPUT")
    assume(cp.nonEmpty && model.nonEmpty && output.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"real CUDA fixture required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-real-ays"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-explicit.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,
      scalaTorchVaeOffloadWeights=true,lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="a red fox sitting in a snowy forest, detailed natural photograph",
        negativePrompt="blurry, low quality",width=512,height=512,steps=8,seed=42,cfgScale=7.5,
        samplerName="Euler",scheduler="Align Your Steps",extra=Map("rng"->"cpu"))
      val result=services.generator.generate(params).head
      val image=PngMetadata.readImage(result.path)
      assertEquals((image.getWidth,image.getHeight),(512,512))
      Files.copy(result.path,Path.of(output.get))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
