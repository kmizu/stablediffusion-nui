package sdfx.engine

import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata

class NativeDdimSamplerSdxlSuite extends munit.FunSuite:
  override val munitTimeout: Duration=10.minutes
  test("NUI runs tiny SDXL stochastic DDIM without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_SDXL_TINY_MODEL")
    val refs=sys.env.get("SCALATORCH_DDIM_SDXL_REFERENCE_DIR")
    assume(cp.nonEmpty && model.nonEmpty && refs.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"tiny SDXL and CUDA required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-ddim-sdxl"))
    val scheduler=home.root.resolve("scheduler.json")
    Files.writeString(scheduler,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear","clip_sample":false}""")
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(scheduler.toString),scalaTorchUnetDevice="cuda",
      scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="a red apple",negativePrompt="blur",width=64,height=64,steps=4,seed=42,cfgScale=7.5,
        samplerName="DDIM",scheduler="Uniform",eta=Some(1.0),extra=Map("rng"->"cpu"))
      for img<-Vector(false,true) do
        val source=Path.of(refs.get).resolve("ddim-sdxl-source.png")
        val request=if img then params.copy(img2img=Some(Img2ImgParams(source.toString,denoisingStrength=.75))) else params
        val result=services.generator.generate(request).head
        val actual=PngMetadata.readImage(result.path)
        val expected=ImageIO.read(Path.of(refs.get).resolve(s"ddim-sdxl-${if img then "img" else "txt"}-eta1.png").toFile)
        val differences=for y<-0 until 64; x<-0 until 64; shift<-Vector(0,8,16) yield
          math.abs(((actual.getRGB(x,y)>>>shift)&255)-((expected.getRGB(x,y)>>>shift)&255))
        assert(differences.max<=1 && differences.sum.toDouble/differences.size<0.01,
          s"img=$img max=${differences.max} mean=${differences.sum.toDouble/differences.size}")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }

  test("full SDXL generates with DDIM on a 4GB CUDA device without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_SDXL_MODEL")
    val output=sys.env.get("SCALATORCH_DDIM_SDXL_REAL_OUTPUT")
    assume(cp.nonEmpty && model.nonEmpty && output.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"real SDXL and CUDA required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-real-ddim-sdxl"))
    val scheduler=home.root.resolve("scheduler.json")
    Files.writeString(scheduler,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear","clip_sample":false}""")
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(scheduler.toString),scalaTorchUnetDevice="cuda",
      scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="a red apple on a wooden table",negativePrompt="blur",width=256,height=256,
        steps=2,seed=42,cfgScale=7.5,samplerName="DDIM",scheduler="Uniform",eta=Some(1.0),extra=Map("rng"->"cpu"))
      val result=services.generator.generate(params).head
      val image=PngMetadata.readImage(result.path)
      assertEquals((image.getWidth,image.getHeight),(256,256))
      Files.copy(result.path,Path.of(output.get))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
