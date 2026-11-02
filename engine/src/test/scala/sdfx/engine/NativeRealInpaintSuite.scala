package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.infotext.Infotext
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path,StandardCopyOption}
import javax.imageio.ImageIO
import scala.concurrent.duration.*

/** Repaints a real 512px SD1.5 image through NUI Generator with no Python process. */
class NativeRealInpaintSuite extends munit.FunSuite:
  override val munitTimeout: Duration=15.minutes

  test("NUI Generator inpaints a real 512px SD1.5 image through the native CUDA worker") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_SD15_MODEL")
    val sourcePath=sys.env.get("SCALATORCH_SD15_SOURCE_IMAGE")
    val outputDir=sys.env.get("SCALATORCH_SAMPLE_OUTPUT_DIR")
    assume(cpFile.nonEmpty && model.nonEmpty && sourcePath.nonEmpty && outputDir.nonEmpty,
      "worker classpath, SD1.5 model, source image and output directory required")

    val root=Path.of(model.get).toAbsolutePath.normalize()
    val source=Path.of(sourcePath.get).toAbsolutePath.normalize()
    val outputs=Path.of(outputDir.get).toAbsolutePath.normalize()
    require(Files.isRegularFile(source),s"missing SD1.5 source image: $source")
    Files.createDirectories(outputs)
    val sourceImage=PngMetadata.readImage(source)
    require(sourceImage.getWidth==512 && sourceImage.getHeight==512,"reference source must be 512x512")

    val home=AppHome(Files.createTempDirectory("native-sd15-inpaint-generator"))
    val scheduler=root.resolve("euler-explicit.json")
    require(Files.isRegularFile(scheduler),s"missing Euler scheduler config: $scheduler")
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=Some(cpFile.get),scalaTorchModel=Some(root.toString),
      scalaTorchScheduler=Some(scheduler.toString),scalaTorchUnetDevice="cuda",
      scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    var failure: Throwable=null
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      services.loadCheckpoint(root.toString,remember=false)

      val maskPath=home.root.resolve("mask.png")
      val mask=new BufferedImage(512,512,BufferedImage.TYPE_BYTE_GRAY)
      for y <- 0 until 512; x <- 0 until 512 do
        mask.setRGB(x,y,if x<256 then 0xff000000 else 0xffffffff)
      assert(ImageIO.write(mask,"png",maskPath.toFile))

      val params=GenerationParams(
        prompt="a photograph of a green apple on a wooden table",
        seed=42,samplerName="DPM++ 2M",scheduler="Karras",steps=20,cfgScale=7.5,
        width=512,height=512,
        img2img=Some(Img2ImgParams(source.toString,mask=Some(maskPath.toString),
          denoisingStrength=0.5,resizeMode=0,maskBlur=0)))
      val generated=services.generator.generate(params).head
      val result=PngMetadata.readImage(generated.path)
      assertEquals(result.getWidth,512)
      assertEquals(result.getHeight,512)
      for y <- 0 until 512; x <- 0 until 256 do
        assertEquals(result.getRGB(x,y),sourceImage.getRGB(x,y),s"preserved pixel ($x,$y)")
      assert((256 until 512).exists(x => (0 until 512).exists(y =>
        result.getRGB(x,y)!=sourceImage.getRGB(x,y))))
      val recorded=PngMetadata.readParameters(generated.path).getOrElse(fail("missing PNG infotext"))
      val parsed=Infotext.parse(recorded)
      assertEquals(parsed.prompt,params.prompt)
      assertEquals(parsed.get("Seed"),Some("42"))
      assertEquals(parsed.get("Denoising strength"),Some("0.5"))
      assert(!Files.exists(home.pythonDir))
      assert(services.supervisor.isRunning)

      Files.copy(source,outputs.resolve("sd15-nui-inpaint-source.png"),StandardCopyOption.REPLACE_EXISTING)
      Files.copy(generated.path,outputs.resolve("sd15-nui-inpaint.png"),StandardCopyOption.REPLACE_EXISTING)
    catch case error: Throwable =>
      failure=error
      throw error
    finally
      try
        services.shutdown()
        val files=Files.walk(home.root)
        try files.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => {
          require(p.toAbsolutePath.normalize().startsWith(home.root.toAbsolutePath.normalize()))
          Files.delete(p);()
        }) finally files.close()
      catch case cleanup: Throwable =>
        if failure!=null then failure.addSuppressed(cleanup) else throw cleanup
  }
