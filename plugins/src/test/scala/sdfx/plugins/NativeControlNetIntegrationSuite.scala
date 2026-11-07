package sdfx.plugins

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{ControlNetUnit,GenerationParams}
import sdfx.core.plugins.api.Values
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices,EngineLaunch,Job}

class NativeControlNetIntegrationSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds
  test("NUI queue generates with native ControlNet and Detail Daemon without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_CONTROLNET_PIPELINE")
    val control=sys.env.get("SCALATORCH_CONTROLNET_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && control.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),
      "CUDA worker and tiny ControlNet fixtures required")
    val root=Path.of(model.get).toAbsolutePath
    val controlRoot=Path.of(control.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("nui-controlnet-native"))
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry=>{val path=Path.of(entry);(if path.isAbsolute then path else cpFile.getParent.resolve(path)).normalize().toString})
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",root.toString,root.resolve("scheduler_config.json").toString),root,
      env=Map("SDFX_SCALATORCH_UNET_DEVICE"->"cuda","SDFX_SCALATORCH_VAE_DEVICE"->"cuda",
        "SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS"->"true","SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"->"true"),helloTimeout=30.seconds)
    val detail=new DetailDaemonPlugin
    val settings=Settings(dtype="fp32",vramMode="cpu",modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString),
      controlnet=Seq(controlRoot.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq(detail),launchOverride=Some(()=>launch),
      persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"");assertEquals(hello.device,"cuda:0")
      Await.result(services.catalog.refreshAsync(),60.seconds)
      val entry=services.catalog.controlnets.find(e=>Path.of(e.path).toAbsolutePath.normalize()==controlRoot.normalize())
        .getOrElse(fail("ControlNet fixture missing from model catalog"))
      services.loadCheckpoint(root.toString,remember=false)
      val source=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 64; x <- 0 until 64 do source.setRGB(x,y,((x*4)<<16)|((y*4)<<8)|((x+y)*2))
      val image=home.root.resolve("control.png");ImageIO.write(source,"png",image.toFile)
      val params=GenerationParams(prompt="a red cat",width=64,height=64,steps=4,seed=42,cfgScale=7.5,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"))
      val controlled=params.copy(controlnet=Seq(ControlNetUnit(model=entry.name,image=Some(image.toString),weight=.7)))
      val values=Values(Map("enabled"->"true","mode"->"cond","amount"->"0.5","start_offset"->"0.3","end_offset"->"0.3"))
      def pixels(job: Job): Vector[Int] =
        val result=Await.result(services.queue.submit(job),120.seconds).images.head
        val generated=PngMetadata.readImage(result.path)
        assertEquals((generated.getWidth,generated.getHeight),(64,64))
        generated.getRGB(0,0,64,64,null,0,64).toVector
      val plain=pixels(Job(params))
      val conditioned=pixels(Job(controlled))
      val hooked=pixels(Job(controlled).copy(alwaysOn=Seq(detail->values)))
      assertNotEquals(conditioned,plain,"ControlNet must change the image")
      assertNotEquals(hooked,conditioned,"Detail Daemon must change the controlled image")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
