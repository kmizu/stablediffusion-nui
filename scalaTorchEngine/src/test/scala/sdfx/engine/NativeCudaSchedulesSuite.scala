package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,HiresParams,Img2ImgParams}
import sdfx.core.png.PngMetadata
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

class NativeCudaSchedulesSuite extends munit.FunSuite:
  override val munitTimeout: Duration=5.minutes
  test("NUI native CUDA generation and Hires accept each new sampler schedule") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-cuda-schedules"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,
      scalaTorchVaeOffloadWeights=true,lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    var failure: Throwable=null
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      services.loadCheckpoint(root.toString,remember=false)
      val base=GenerationParams(prompt="a red cat",width=64,height=64,steps=3,seed=42,cfgScale=1,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"))
      for sampler <- Vector("Euler","Euler a","DPM++ 2M") do
        val plain=scala.collection.mutable.ArrayBuffer.empty[Vector[Int]]
        val hiresImages=scala.collection.mutable.ArrayBuffer.empty[Vector[Int]]
        for schedule <- Vector("Normal","Simple","SGM Uniform","Polyexponential") do
          val params=base.copy(samplerName=sampler,scheduler=schedule)
          val result=services.generator.generate(params).head
          val image=PngMetadata.readImage(result.path)
          assertEquals((image.getWidth,image.getHeight),(64,64),s"$sampler / $schedule")
          plain+=image.getRGB(0,0,64,64,null,0,64).toVector
          val hires=base.copy(hires=HiresParams(enabled=true,scale=2,secondPassSteps=3,denoisingStrength=.75,
            samplerName=Some(sampler),scheduler=Some(schedule)))
          val enlarged=services.generator.generate(hires).head
          val full=PngMetadata.readImage(enlarged.path)
          assertEquals((full.getWidth,full.getHeight),(128,128),s"Hires $sampler / $schedule")
          hiresImages+=full.getRGB(0,0,128,128,null,0,128).toVector
        assert(plain.distinct.size>=2,s"$sampler schedules must affect generated output")
        assert(hiresImages.distinct.size>=2,s"Hires $sampler schedule override must affect output")
      val input=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 64; x <- 0 until 64 do input.setRGB(x,y,((x*4)<<16)|((y*4)<<8)|64)
      val source=home.root.resolve("latent-source.png")
      assert(javax.imageio.ImageIO.write(input,"png",source.toFile))
      val latent=base.copy(width=128,height=128,steps=2,
        img2img=Some(Img2ImgParams(source.toString,denoisingStrength=0.5)),
        hooks=Map("latent_upscale"->ujson.Obj("method"->"nearest-exact","scheduler"->"simple")))
      val result=services.generator.generate(latent).head
      val upscaled=PngMetadata.readImage(result.path)
      assertEquals((upscaled.getWidth,upscaled.getHeight),(128,128))
      val lowStrength=latent.copy(steps=1,img2img=Some(Img2ImgParams(source.toString,denoisingStrength=.0005)))
      val lowImage=PngMetadata.readImage(services.generator.generate(lowStrength).head.path)
      assertEquals((lowImage.getWidth,lowImage.getHeight),(128,128))
      val lowAncestral=latent.copy(samplerName="Euler a",steps=2,
        img2img=Some(Img2ImgParams(source.toString,denoisingStrength=.001)))
      val ancestralImage=PngMetadata.readImage(services.generator.generate(lowAncestral).head.path)
      assertEquals((ancestralImage.getWidth,ancestralImage.getHeight),(128,128))
      for schedule <- Vector("simple","karras") do
        val longDpm=lowAncestral.copy(samplerName="DPM++ 2M",
          hooks=Map("latent_upscale"->ujson.Obj("method"->"nearest-exact","scheduler"->schedule)))
        val longImage=PngMetadata.readImage(services.generator.generate(longDpm).head.path)
        assertEquals((longImage.getWidth,longImage.getHeight),(128,128))
      val mask=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
      for y <- 0 until 64; x <- 0 until 64 do mask.setRGB(x,y,if x<32 then 0xff000000 else 0xffffffff)
      val maskPath=home.root.resolve("latent-mask.png")
      assert(javax.imageio.ImageIO.write(mask,"png",maskPath.toFile))
      val masked=latent.copy(img2img=Some(Img2ImgParams(source.toString,mask=Some(maskPath.toString),
        denoisingStrength=.5,maskBlur=0)))
      val maskedResult=services.generator.generate(masked).head
      val maskedImage=PngMetadata.readImage(maskedResult.path)
      assertEquals((maskedImage.getWidth,maskedImage.getHeight),(128,128))
      val fullRes=latent.copy(img2img=Some(Img2ImgParams(source.toString,mask=Some(maskPath.toString),
        denoisingStrength=.5,maskBlur=0,inpaintFullRes=true,inpaintFullResPadding=4)))
      val fullResult=services.generator.generate(fullRes).head
      val fullImage=PngMetadata.readImage(fullResult.path)
      assertEquals((fullImage.getWidth,fullImage.getHeight),(64,64))
      assertEquals(fullImage.getRGB(0,0)&0xffffff,input.getRGB(0,0)&0xffffff)
      val altered=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 64; x <- 0 until 64 do altered.setRGB(x,y,if x<32 then input.getRGB(x,y) else 0xffff0080)
      val alteredPath=home.root.resolve("difference-altered.png")
      assert(javax.imageio.ImageIO.write(altered,"png",alteredPath.toFile))
      val plain=base.copy(img2img=Some(Img2ImgParams(alteredPath.toString,mask=Some(maskPath.toString),
        denoisingStrength=.5,maskBlur=0)))
      val diff=plain.copy(img2img=Some(Img2ImgParams(alteredPath.toString,denoisingStrength=.5,maskBlur=0)),
        hooks=Map("inpaint_difference"->ujson.Obj("base_path"->source.toString,"threshold"->1)))
      val expected=PngMetadata.readImage(services.generator.generate(plain).head.path)
      val actual=PngMetadata.readImage(services.generator.generate(diff).head.path)
      assertEquals(actual.getRGB(0,0,64,64,null,0,64).toVector,expected.getRGB(0,0,64,64,null,0,64).toVector)
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
