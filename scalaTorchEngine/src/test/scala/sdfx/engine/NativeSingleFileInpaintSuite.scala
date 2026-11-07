package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{ControlNetUnit,GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata
import java.awt.image.BufferedImage
import java.nio.file.{Files,Path,StandardCopyOption}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.util.Using

/** Opt-in acceptance of a trained single-file inpainting checkpoint through NUI. */
class NativeSingleFileInpaintSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 20.minutes
  test("Generator runs native single-file inpaint and ControlNet without Python") {
    val properties = new java.util.Properties()
    val config = Vector(Path.of(".superpowers/single-file-inpaint-test.properties"),
      Path.of("../.superpowers/single-file-inpaint-test.properties")).find(Files.isRegularFile(_))
    config.foreach(path => Using.resource(Files.newInputStream(path))(properties.load))
    def setting(name: String): Option[String] = sys.env.get(name).orElse(Option(properties.getProperty(name)))
    val checkpoint=setting("SCALATORCH_SD15_INPAINT_SINGLE")
    val source=setting("SCALATORCH_SD15_INPAINT_SOURCE")
    val output=setting("SCALATORCH_SD15_INPAINT_OUTPUT")
    val cp=setting("SCALATORCH_WORKER_CLASSPATH_FILE")
    val tokenizer=setting("SCALATORCH_TEST_TOKENIZER")
    val control=setting("SCALATORCH_SD15_CONTROLNET")
    assume(Vector(checkpoint,source,output,cp,tokenizer).forall(_.nonEmpty),"real checkpoint, source, output, worker and tokenizer required")
    val model=Path.of(checkpoint.get).toRealPath()
    val sourcePath=Path.of(source.get).toRealPath()
    val sourceImage=PngMetadata.readImage(sourcePath)
    require(sourceImage.getWidth==256 && sourceImage.getHeight==256,"source must be 256px")
    val home=AppHome(Files.createTempDirectory("native-single-file-inpaint-nui"))
    val tokenizerDir=Files.createDirectories(home.root.resolve("scala-torch/tokenizer"))
    Vector("vocab.json","merges.txt").foreach(name => Files.copy(Path.of(tokenizer.get).resolve(name),tokenizerDir.resolve(name)))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=cp,scalaTorchModel=Some(model.toString),scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      modelDirs=ModelDirs(checkpoints=Seq(model.getParent.toString),controlnet=control.toSeq.map { value =>
        val path=Path.of(value)
        if Files.isRegularFile(path) then path.getParent.toString else path.toString
      }))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      services.loadCheckpoint(model.toString,remember=false)
      val mask=new BufferedImage(256,256,BufferedImage.TYPE_BYTE_GRAY)
      for y <- 0 until 256; x <- 0 until 256 do mask.getRaster.setSample(x,y,0,if x<128 then 0 else 255)
      val maskPath=home.root.resolve("mask.png")
      assert(ImageIO.write(mask,"png",maskPath.toFile))
      val params=GenerationParams(prompt="a photograph of a green apple on a wooden table",seed=42,
        samplerName="Euler",scheduler="Uniform",steps=8,cfgScale=7.5,width=256,height=256,
        img2img=Some(Img2ImgParams(sourcePath.toString,mask=Some(maskPath.toString),denoisingStrength=0.75,resizeMode=0,maskBlur=0)))
      val generated=services.generator.generate(params).head
      val result=PngMetadata.readImage(generated.path)
      assertEquals((result.getWidth,result.getHeight),(256,256))
      for y <- 0 until 256; x <- 0 until 128 do assertEquals(result.getRGB(x,y),sourceImage.getRGB(x,y))
      assert((128 until 256).exists(x => (0 until 256).exists(y => result.getRGB(x,y)!=sourceImage.getRGB(x,y))))
      assert(!Files.exists(home.pythonDir))
      val destination=Files.createDirectories(Path.of(output.get)).resolve("nui-single-file-inpaint.png")
      Files.copy(generated.path,destination,StandardCopyOption.REPLACE_EXISTING)
      control.foreach { dir =>
        val listed=services.catalog.findControlNet(Path.of(dir).getFileName.toString)
          .getOrElse(fail("ControlNet checkpoint is absent from the NUI catalog"))
        assert(Files.isSameFile(Path.of(listed.path),Path.of(dir)),"catalog resolved the wrong ControlNet")
        val unit=ControlNetUnit(enabled=true,model=Path.of(dir).getFileName.toString,
          image=Some(sourcePath.toString),preprocessor="canny",weight=1.0)
        val conditioned=services.generator.generate(params.copy(controlnet=Seq(unit))).head
        val controlled=PngMetadata.readImage(conditioned.path)
        for y <- 0 until 256; x <- 0 until 128 do assertEquals(controlled.getRGB(x,y),sourceImage.getRGB(x,y))
        assert((128 until 256).exists(x => (0 until 256).exists(y => controlled.getRGB(x,y)!=result.getRGB(x,y))))
        Files.copy(conditioned.path,Path.of(output.get).resolve("nui-single-file-inpaint-controlnet.png"),
          StandardCopyOption.REPLACE_EXISTING)
        assert(services.supervisor.isRunning,"native worker stopped during ControlNet generation")
      }
    finally services.shutdown()
  }
