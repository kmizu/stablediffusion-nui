package sdfx.engine

import java.nio.file.{Files,Path}
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,HiresParams,Img2ImgParams}
import sdfx.core.png.PngMetadata

class NativeNineChannelUpscaleSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds
  test("NUI CUDA worker runs unmasked latent upscale with a nine-channel model without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_NINE_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker and synthetic model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-nine-upscale"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,
      scalaTorchVaeOffloadWeights=true,lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      services.loadCheckpoint(root.toString,remember=false)
      val image=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 64; x <- 0 until 64 do image.setRGB(x,y,((x*4)<<16)|((y*4)<<8)|64)
      val source=home.root.resolve("source.png");assert(ImageIO.write(image,"png",source.toFile))
      val params=GenerationParams(prompt="a red cat",width=128,height=128,steps=2,seed=42,cfgScale=1,
        samplerName="Euler",scheduler="Normal",extra=Map("rng"->"cpu"),
        img2img=Some(Img2ImgParams(source.toString,denoisingStrength=.5)),
        hooks=Map("latent_upscale"->ujson.Obj("method"->"nearest-exact","scheduler"->"normal")))
      val generated=services.generator.generate(params).head
      val decoded=PngMetadata.readImage(generated.path)
      assertEquals((decoded.getWidth,decoded.getHeight),(128,128))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }

  test("NUI runs nine-channel latent Hires through the native CUDA worker without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_NINE_MODEL")
    val refs=sys.env.get("SCALATORCH_NINE_REFERENCE_DIR")
    assume(cp.nonEmpty && model.nonEmpty && refs.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),
      "CUDA worker, synthetic model and reference required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-nine-hires"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,
      scalaTorchVaeOffloadWeights=true,lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val hires=HiresParams(enabled=true,scale=2,upscaler="Latent (nearest)",secondPassSteps=2,denoisingStrength=.5)
      val params=GenerationParams(prompt="a red cat",width=64,height=64,steps=2,seed=42,cfgScale=7.5,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"),hires=hires)
      val result=services.generator.generate(params).head
      val actual=PngMetadata.readImage(result.path)
      val expected=ImageIO.read(Path.of(refs.get).resolve("nine-latent-nearest-output.png").toFile)
      assertEquals((actual.getWidth,actual.getHeight),(128,128))
      val diff=for y<-0 until 128; x<-0 until 128; shift<-Vector(0,8,16) yield
        math.abs(((actual.getRGB(x,y)>>>shift)&255)-((expected.getRGB(x,y)>>>shift)&255))
      assert(diff.max<=1 && diff.sum.toDouble/diff.size<0.01,s"max=${diff.max} mean=${diff.sum.toDouble/diff.size}")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
