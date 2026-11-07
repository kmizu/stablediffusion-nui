package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata
import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.duration.*

class NativeCudaMaskSuite extends munit.FunSuite:
  override val munitTimeout: Duration=5.minutes
  test("NUI CUDA inpainting interprets transparent mask alpha without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-cuda-mask"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=Some(cp.get),scalaTorchModel=Some(root.toString),
      scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),scalaTorchUnetDevice="cuda",
      scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    var failure: Throwable=null
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      services.loadCheckpoint(root.toString,remember=false)
      val source=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      val gray=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      val alpha=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB)
      for y <- 0 until 64; x <- 0 until 64 do
        val v=(y*64+x)%256
        source.setRGB(x,y,(v<<16)|(v<<8)|v)
        gray.setRGB(x,y,if x<32 then 0xff000000 else 0xffffffff)
        alpha.setRGB(x,y,if x<32 then 0x00ffffff else 0xff000000)
      val sourcePath=home.root.resolve("source.png")
      val grayPath=home.root.resolve("gray.png")
      val alphaPath=home.root.resolve("alpha.png")
      ImageIO.write(source,"png",sourcePath.toFile)
      ImageIO.write(gray,"png",grayPath.toFile)
      ImageIO.write(alpha,"png",alphaPath.toFile)
      val params=GenerationParams(prompt="a red cat",width=64,height=64,steps=4,seed=42,cfgScale=1,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"),
        img2img=Some(Img2ImgParams(sourcePath.toString,mask=Some(grayPath.toString),
          denoisingStrength=0.75,resizeMode=0,maskBlur=0,inpaintingFill=1)))
      def generate(p: GenerationParams)=PngMetadata.readImage(services.generator.generate(p).head.path)
      val reference=generate(params)
      val result=generate(params.copy(img2img=params.img2img.map(_.copy(mask=Some(alphaPath.toString)))))
      assertEquals(result.getRGB(0,0,64,64,null,0,64).toVector,reference.getRGB(0,0,64,64,null,0,64).toVector)
      for y <- 0 until 64; x <- 0 until 32 do assertEquals(result.getRGB(x,y),source.getRGB(x,y))
      assert((32 until 64).exists(x => (0 until 64).exists(y => result.getRGB(x,y)!=source.getRGB(x,y))))
      val fullFixture=sys.env.get("SCALATORCH_FULL_INPAINT_REFERENCE")
      if fullFixture.nonEmpty then
        val fullSource=new BufferedImage(80,64,BufferedImage.TYPE_INT_RGB)
        val fullMask=new BufferedImage(80,64,BufferedImage.TYPE_INT_RGB)
        for y <- 0 until 64; x <- 0 until 80 do
          val v=(x*3+y*5)%256
          fullSource.setRGB(x,y,(v<<16)|(v<<8)|v)
          fullMask.setRGB(x,y,if x>=28 && x<52 && y>=20 && y<44 then 0xffffffff else 0xff000000)
        val fullSourcePath=home.root.resolve("full-source.png")
        val fullMaskPath=home.root.resolve("full-mask.png")
        ImageIO.write(fullSource,"png",fullSourcePath.toFile)
        ImageIO.write(fullMask,"png",fullMaskPath.toFile)
        val fullParams=params.copy(img2img=Some(Img2ImgParams(fullSourcePath.toString,mask=Some(fullMaskPath.toString),
          denoisingStrength=0.75,resizeMode=0,maskBlur=2,inpaintingFill=1,inpaintFullRes=true,inpaintFullResPadding=4)))
        val fullResult=generate(fullParams)
        val fixture=ujson.read(Files.readString(Path.of(fullFixture.get)))
        assertEquals((fullResult.getWidth,fullResult.getHeight),(80,64))
        val expected=fixture("rgb").arr.map(_.num.toInt)
        val errors=for y <- 0 until 64; x <- 0 until 80; channel <- 0 until 3 yield
          val actual=(fullResult.getRGB(x,y) >>> (16-channel*8))&255
          math.abs(actual-expected((y*80+x)*3+channel))
        assert(errors.max<=2,s"full resolution inpaint maximum channel error ${errors.max}")
      assert(!Files.exists(home.pythonDir))
    catch case error: Throwable =>
      failure=error
      throw error
    finally
      try
        services.shutdown()
        val paths=Files.walk(home.root)
        try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(path => {
          require(path.toAbsolutePath.normalize().startsWith(home.root.toAbsolutePath.normalize()))
          Files.delete(path);()
        }) finally paths.close()
      catch case cleanup: Throwable =>
        if failure!=null then failure.addSuppressed(cleanup) else throw cleanup
  }
