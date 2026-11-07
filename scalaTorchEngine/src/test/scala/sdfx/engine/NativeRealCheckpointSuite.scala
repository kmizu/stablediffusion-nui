package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path,StandardCopyOption}
import javax.imageio.ImageIO
import scala.concurrent.duration.*

/** Opt-in real SD1.5 smoke run that leaves source and inpaint PNGs for visual review. */
class NativeRealCheckpointSuite extends munit.FunSuite:
  override val munitTimeout: Duration=15.minutes

  test("real SD1.5 txt2img and masked img2img run through Python-free Generator on CUDA") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_SD15_MODEL")
    val outputDir=sys.env.get("SCALATORCH_SAMPLE_OUTPUT_DIR")
    assume(cpFile.nonEmpty && model.nonEmpty && outputDir.nonEmpty,
      "worker classpath, SD1.5 model and sample output directory required")

    val root=Path.of(model.get).toAbsolutePath.normalize()
    val outputs=Path.of(outputDir.get).toAbsolutePath.normalize()
    Files.createDirectories(outputs)
    val home=AppHome(Files.createTempDirectory("native-sd15-generator"))
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

      val sourceParams=GenerationParams(
        prompt="a red fox sitting in a snowy forest, soft morning light, detailed natural photograph",
        negativePrompt="blurry, low quality, text, watermark",width=256,height=256,
        steps=12,seed=41021)
      val source=services.generator.generate(sourceParams).head
      val sourceImage=PngMetadata.readImage(source.path)
      assertEquals(sourceImage.getWidth,256)
      assertEquals(sourceImage.getHeight,256)
      Files.copy(source.path,outputs.resolve("sd15-source.png"),StandardCopyOption.REPLACE_EXISTING)

      val maskPath=home.root.resolve("mask.png")
      val mask=new BufferedImage(256,256,BufferedImage.TYPE_BYTE_GRAY)
      for y <- 0 until 256; x <- 0 until 256 do
        mask.setRGB(x,y,if x<128 then 0xff000000 else 0xffffffff)
      assert(ImageIO.write(mask,"png",maskPath.toFile))

      val editParams=sourceParams.copy(
        prompt="a red fox in a snowy forest beside a small wooden cabin, soft morning light, detailed natural photograph",
        steps=12,seed=41022,
        img2img=Some(Img2ImgParams(source.path.toString,mask=Some(maskPath.toString),
          denoisingStrength=0.7,resizeMode=0,maskBlur=0)))
      val edit=services.generator.generate(editParams).head
      val editImage=PngMetadata.readImage(edit.path)
      assertEquals(editImage.getWidth,256)
      assertEquals(editImage.getHeight,256)
      for y <- 0 until 256; x <- 0 until 128 do
        assertEquals(editImage.getRGB(x,y),sourceImage.getRGB(x,y),s"preserved pixel ($x,$y)")
      assert((128 until 256).exists(x => (0 until 256).exists(y =>
        editImage.getRGB(x,y)!=sourceImage.getRGB(x,y))))
      assert(!Files.exists(home.pythonDir))
      assert(services.supervisor.isRunning)

      Files.copy(edit.path,outputs.resolve("sd15-masked-inpaint.png"),StandardCopyOption.REPLACE_EXISTING)
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
