package sdfx.plugins

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path,StandardCopyOption}
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.plugins.api.Values
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices,EngineLaunch,InpaintDifferencePreview,Job}
import scala.concurrent.ExecutionContext

class NativeRealInpaintDifferenceSuite extends munit.FunSuite:
  override val munitTimeout: Duration=20.minutes
  test("real SD1.5 difference preview and plugin generation run on CUDA without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE");val model=sys.env.get("SCALATORCH_SD15_MODEL")
    val input=sys.env.get("SCALATORCH_UPSCALE_SOURCE");val output=sys.env.get("SCALATORCH_SAMPLE_OUTPUT_DIR")
    assume(cp.nonEmpty && model.nonEmpty && input.nonEmpty && output.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA, SD1.5 and source required")
    given ExecutionContext=ExecutionContext.global
    val root=Path.of(model.get).toAbsolutePath;val home=AppHome(Files.createTempDirectory("nui-real-difference"))
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry=>{val p=Path.of(entry);(if p.isAbsolute then p else cpFile.getParent.resolve(p)).normalize().toString}).mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",root.toString,root.resolve("euler-explicit.json").toString),root,
      env=Map("SDFX_SCALATORCH_UNET_DEVICE"->"cuda","SDFX_SCALATORCH_VAE_DEVICE"->"cuda",
        "SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS"->"true","SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"->"true"),helloTimeout=30.seconds)
    val plugin=new InpaintDifferencePlugin
    val settings=Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq(plugin),launchOverride=Some(()=>launch),persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start();assertEquals((hello.python,hello.device),("","cuda:0"));assert(hello.features.contains("inpaint_difference"))
      val original=ImageIO.read(Path.of(input.get).toFile);assertEquals((original.getWidth,original.getHeight),(512,512))
      val altered=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB)
      val graphics=altered.createGraphics()
      try
        graphics.drawImage(original,0,0,null)
        graphics.setColor(java.awt.Color.MAGENTA);graphics.fillRect(184,120,144,144)
      finally graphics.dispose()
      val base=home.root.resolve("base.png");val changed=home.root.resolve("altered.png")
      ImageIO.write(original,"png",base.toFile);ImageIO.write(altered,"png",changed.toFile)
      val artifact=Await.result(InpaintDifferencePreview.run(services,base.toString,changed.toString,home.root,
        ujson.Obj("preview_mode"->"effective","blur"->4,"color"->"#ffffff")),60.seconds)
      val mask=ImageIO.read(artifact.mask.toFile)
      assertEquals(mask.getRaster.getSample(200,140,0),255);assertEquals(mask.getRaster.getSample(50,50,0),0)
      val dir=Files.createTempDirectory(Path.of(output.get),"sd15-inpaint-difference-")
      Files.copy(base,dir.resolve("base.png"));Files.copy(changed,dir.resolve("altered.png"))
      Files.copy(artifact.preview,dir.resolve("preview.png"));Files.copy(artifact.mask,dir.resolve("mask.png"));artifact.cleanup()
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="a red fox sitting in a snowy forest, detailed natural photograph",
        negativePrompt="blurry, low quality",width=512,height=512,steps=6,seed=42,cfgScale=7.5,
        samplerName="Euler",scheduler="Normal",extra=Map("rng"->"cpu"),
        img2img=Some(Img2ImgParams(changed.toString,denoisingStrength=1,maskBlur=4)))
      val values=Values(Map("enabled"->"true","base_path"->base.toString))
      val result=Await.result(services.queue.submit(Job(params).copy(alwaysOn=Seq(plugin->values))),15.minutes)
      val image=PngMetadata.readImage(result.images.head.path);assertEquals((image.getWidth,image.getHeight),(512,512))
      val center=image.getRGB(220,150)&0xffffff
      assert((255-((center>>>16)&255))+((center>>>8)&255)+(255-(center&255))>100,
        "the center of the masked region should substantially change")
      Files.copy(result.images.head.path,dir.resolve("result.png"),StandardCopyOption.COPY_ATTRIBUTES)
      println(s"Real inpaint difference output: $dir")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
