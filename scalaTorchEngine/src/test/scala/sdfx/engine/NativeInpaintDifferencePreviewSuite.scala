package sdfx.engine

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,Settings}

class NativeInpaintDifferencePreviewSuite extends munit.FunSuite:
  test("NUI preview runs through native worker without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty,"native worker and tiny model required")
    given ExecutionContext=ExecutionContext.global
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-difference-preview"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),lastModel=None,lastVae=None)
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      val base=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB)
      val altered=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 16; x <- 0 until 16 do altered.setRGB(x,y,if x>=8 then 0xffffff else 0)
      val a=home.root.resolve("base.png");val b=home.root.resolve("altered.png")
      ImageIO.write(base,"png",a.toFile);ImageIO.write(altered,"png",b.toFile)
      val artifact=Await.result(InpaintDifferencePreview.run(services,a.toString,b.toString,home.root,ujson.Obj("preview_mode"->"effective","blur"->2,"contours"->true)),60.seconds)
      assertEquals((artifact.width,artifact.height),(16,16))
      val mask=ImageIO.read(artifact.mask.toFile)
      assertEquals(mask.getRaster.getSample(8,8,0),255)
      assertEquals(mask.getRaster.getSample(9,8,0),0)
      artifact.cleanup()
      assert(!Files.exists(artifact.directory));assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
