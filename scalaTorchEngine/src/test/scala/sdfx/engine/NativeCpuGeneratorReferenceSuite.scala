package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

class NativeCpuGeneratorReferenceSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds
  test("AppServices Generator CPU RNG output agrees with independent PyTorch image") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val fixture=sys.env.get("SCALATORCH_CPU_REFERENCE_64")
    assume(cp.nonEmpty && model.nonEmpty && fixture.nonEmpty,"native worker, model and 64px CPU reference required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-cpu-generator"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=Some(cp.get),scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cpu",scalaTorchVaeDevice="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    var failure: Throwable=null
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="a red cat",width=64,height=64,steps=3,seed=42,cfgScale=1,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"))
      assertEquals(services.generator.prepare(params).request.params.extra.get("rng"),Some("cpu"))
      val image=PngMetadata.readImage(services.generator.generate(params).head.path)
      val reference=ujson.read(Files.readString(Path.of(fixture.get)))("image").arr.map(_.num).toVector
      assertEquals((image.getWidth,image.getHeight),(64,64))
      assertEquals(reference.length,64*64*3)
      val errors=for pixel <- 0 until 4096; channel <- 0 until 3 yield
        val actual=(image.getRGB(pixel%64,pixel/64) >>> (16-8*channel)) & 255
        val expected=math.rint(reference(channel*4096+pixel)*255).toInt
        math.abs(actual-expected)
      assert(errors.max<=1,s"maximum channel error ${errors.max}")
      val fallback=PngMetadata.readImage(services.generator.generate(params.copy(extra=Map("rng"->"gpu"))).head.path)
      assertEquals(fallback.getRGB(0,0,64,64,null,0,64).toVector,image.getRGB(0,0,64,64,null,0,64).toVector,
        "GPU RNG selection on a CPU engine must fall back to the CPU stream")
      val sourcePath=home.root.resolve("img2img-source.png")
      val source=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 64; x <- 0 until 64 do
        val value=(y*64+x)%256
        source.setRGB(x,y,(value<<16)|(value<<8)|value)
      javax.imageio.ImageIO.write(source,"png",sourcePath.toFile)
      val imgParams=params.copy(steps=4,img2img=Some(Img2ImgParams(sourcePath.toString,denoisingStrength=0.75,resizeMode=0)))
      val imgResult=PngMetadata.readImage(services.generator.generate(imgParams).head.path)
      val imgReference=ujson.read(Files.readString(Path.of(fixture.get)))("img2img_image").arr.map(_.num).toVector
      assertEquals((imgResult.getWidth,imgResult.getHeight),(64,64))
      assertEquals(imgReference.length,64*64*3)
      val imgErrors=for pixel <- 0 until 4096; channel <- 0 until 3 yield
        val actual=(imgResult.getRGB(pixel%64,pixel/64) >>> (16-8*channel)) & 255
        val expected=math.rint(imgReference(channel*4096+pixel)*255).toInt
        math.abs(actual-expected)
      assert(imgErrors.max<=1,s"img2img maximum channel error ${imgErrors.max}")
      val maskPath=home.root.resolve("inpaint-mask.png")
      val mask=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
      for y <- 0 until 64; x <- 0 until 64 do mask.setRGB(x,y,if x>=32 then 0xffffffff else 0xff000000)
      javax.imageio.ImageIO.write(mask,"png",maskPath.toFile)
      val inpaintParams=imgParams.copy(img2img=Some(Img2ImgParams(sourcePath.toString,mask=Some(maskPath.toString),
        denoisingStrength=0.75,resizeMode=0,maskBlur=0,inpaintingFill=1)))
      val inpaint=PngMetadata.readImage(services.generator.generate(inpaintParams).head.path)
      val inpaintReference=ujson.read(Files.readString(Path.of(fixture.get)))("inpaint_4_image").arr.map(_.num).toVector
      assertEquals((inpaint.getWidth,inpaint.getHeight),(64,64))
      assertEquals(inpaintReference.length,64*64*3)
      val inpaintErrors=for pixel <- 0 until 4096; channel <- 0 until 3 yield
        val actual=(inpaint.getRGB(pixel%64,pixel/64) >>> (16-8*channel)) & 255
        val expected=math.rint(inpaintReference(channel*4096+pixel)*255).toInt
        math.abs(actual-expected)
      assert(inpaintErrors.max<=1,s"inpaint maximum channel error ${inpaintErrors.max}")
      val alphaPath=home.root.resolve("inpaint-alpha-mask.png")
      val alphaMask=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB)
      for y <- 0 until 64; x <- 0 until 64 do
        alphaMask.setRGB(x,y,if x>=32 then 0xff000000 else 0x00ffffff)
      javax.imageio.ImageIO.write(alphaMask,"png",alphaPath.toFile)
      val alphaParams=inpaintParams.copy(img2img=inpaintParams.img2img.map(_.copy(mask=Some(alphaPath.toString))))
      val alphaResult=PngMetadata.readImage(services.generator.generate(alphaParams).head.path)
      assertEquals(alphaResult.getRGB(0,0,64,64,null,0,64).toVector,inpaint.getRGB(0,0,64,64,null,0,64).toVector,
        "partly transparent masks use alpha through NUI Generator")
      val softenedParams=inpaintParams.copy(img2img=inpaintParams.img2img.map(_.copy(maskBlur=4)))
      val softened=PngMetadata.readImage(services.generator.generate(softenedParams).head.path)
      val softReference=ujson.read(Files.readString(Path.of(fixture.get)))("inpaint_4_soft_image").arr.map(_.num).toVector
      assertEquals(softReference.length,64*64*3)
      val softErrors=for pixel <- 0 until 4096; channel <- 0 until 3 yield
        val actual=(softened.getRGB(pixel%64,pixel/64) >>> (16-8*channel)) & 255
        val expected=math.rint(softReference(channel*4096+pixel)*255).toInt
        math.abs(actual-expected)
      assert(softErrors.max<=1,s"soft inpaint maximum channel error ${softErrors.max}")
      assert(!Files.exists(home.pythonDir),"native generation must not bootstrap Python")
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
