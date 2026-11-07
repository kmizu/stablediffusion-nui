package sdfx.engine

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import java.util.concurrent.{CountDownLatch,TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Using
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{ControlNetUnit,GenerationParams,Img2ImgParams}
import sdfx.core.protocol.{EngineError,EngineEvent}

/** Exercises NUI's actual child-worker transport with a real PyTorch ControlNet. */
class NativeControlNetCancellationSuite extends munit.FunSuite:
  override val munitTimeout: Duration=15.minutes
  test("NUI interrupts PyTorch ControlNet reading and generates after retry") {
    val properties=new java.util.Properties()
    val config=Vector(Path.of(".superpowers/single-file-inpaint-test.properties"),
      Path.of("../.superpowers/single-file-inpaint-test.properties")).find(Files.isRegularFile(_))
    config.foreach(path => Using.resource(Files.newInputStream(path))(properties.load))
    def setting(name: String)=sys.env.get(name).orElse(Option(properties.getProperty(name)))
    val model=setting("SCALATORCH_SD15_INPAINT_SINGLE")
    val source=setting("SCALATORCH_SD15_INPAINT_SOURCE")
    val control=setting("SCALATORCH_SD15_CONTROLNET")
    val cp=setting("SCALATORCH_WORKER_CLASSPATH_FILE")
    val javaExecutable=setting("SCALATORCH_TEST_JAVA")
    val tokenizer=setting("SCALATORCH_TEST_TOKENIZER")
    assume(Vector(model,source,control,cp,tokenizer).forall(_.nonEmpty) && control.exists(_.toLowerCase.endsWith(".pth")),
      "real SD1 inpaint, PyTorch ControlNet, worker and tokenizer required")
    val modelPath=Path.of(model.get).toRealPath()
    val controlPath=Path.of(control.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("nui-controlnet-cancellation"))
    val tokenDir=Files.createDirectories(home.root.resolve("scala-torch/tokenizer"))
    Vector("vocab.json","merges.txt").foreach(name => Files.copy(Path.of(tokenizer.get).resolve(name),tokenDir.resolve(name)))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,scalaTorchJava=javaExecutable,
      scalaTorchModel=Some(modelPath.toString),scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      modelDirs=ModelDirs(checkpoints=Seq(modelPath.getParent.toString),controlnet=Seq(controlPath.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      services.loadCheckpoint(modelPath.toString,remember=false)
      val workerPid=services.supervisor.pid.getOrElse(fail("native worker has no PID"))
      val listed=services.catalog.findControlNet(controlPath.getFileName.toString).getOrElse(fail("ControlNet not found"))
      assert(Files.isSameFile(Path.of(listed.path),controlPath))
      val mask=new BufferedImage(256,256,BufferedImage.TYPE_BYTE_GRAY)
      for y <- 0 until 256; x <- 0 until 256 do mask.getRaster.setSample(x,y,0,if x<128 then 0 else 255)
      val maskPath=home.root.resolve("mask.png")
      assert(ImageIO.write(mask,"png",maskPath.toFile))
      val unit=ControlNetUnit(enabled=true,model=controlPath.getFileName.toString,
        image=Some(source.get),preprocessor="canny",weight=1.0)
      val params=GenerationParams(prompt="a green apple",seed=42,samplerName="Euler",scheduler="Uniform",
        steps=4,cfgScale=7.5,width=256,height=256,controlnet=Seq(unit),
        img2img=Some(Img2ImgParams(source.get,mask=Some(maskPath.toString),denoisingStrength=0.75,maskBlur=0)))
      val prepared=services.generator.prepare(params)
      val output=Files.createDirectories(home.root.resolve("worker-output"))
      val request=prepared.request.copy(outputDir=output.toString)
      def countFiles=Using.resource(Files.list(output))(_.count())
      val began=new CountDownLatch(1)
      val handle=services.client.generate(request,prepared.mode,{
        case EngineEvent.Log(_,message) if message=="Reading ControlNet tensor weights" => began.countDown()
        case _ => ()
      })
      assert(began.await(90,TimeUnit.SECONDS),"ControlNet tensor reading did not start")
      handle.interrupt()
      val failure=intercept[EngineError](Await.result(handle.result,30.seconds))
      assert(failure.isInterrupted,s"unexpected error: $failure")
      assertEquals(countFiles,0L,"cancelled request published a file")
      val reloaded=new CountDownLatch(1)
      val retry=services.client.generate(request,prepared.mode,{
        case EngineEvent.Log(_,message) if message=="Reading ControlNet tensor weights" => reloaded.countDown()
        case _ => ()
      })
      val result=Await.result(retry.result,5.minutes)
      assert(reloaded.await(1,TimeUnit.SECONDS),"retry reused an incompletely loaded ControlNet")
      assertEquals(result.images.size,1)
      assert(Files.isRegularFile(Path.of(result.images.head.path)))
      assert(services.supervisor.isRunning)
      assertEquals(services.supervisor.pid,Some(workerPid),"worker restarted during interrupt recovery")
      assert(!Files.exists(home.pythonDir))
    finally services.shutdown()
  }
