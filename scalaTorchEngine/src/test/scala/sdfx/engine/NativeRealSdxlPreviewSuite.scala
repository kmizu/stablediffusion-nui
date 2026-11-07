package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.protocol.EngineEvent
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class NativeRealSdxlPreviewSuite extends munit.FunSuite:
  override val munitTimeout: Duration=20.minutes
  test("official SDXL emits neural previews through NUI on CUDA") {
    val model=sys.env.get("SCALATORCH_SDXL_MODEL")
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val tiny=sys.env.get("SDFX_SCALATORCH_TAESDXL_DIR")
    val output=sys.env.get("SCALATORCH_TAESD_OUTPUT")
    assume(Vector(model,cp,tiny,output).forall(_.nonEmpty),"full SDXL, native worker and TAESDXL weights required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-sdxl-taesd"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",previewEvery=1,previewMethod="taesd",
      scalaTorchClasspath=cp,scalaTorchModel=Some(root.toString),scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    val previews=new java.util.concurrent.ConcurrentLinkedQueue[EngineEvent.Preview]()
    val logs=new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val listener=new GenerationListener:
      override def onPreview(value: EngineEvent.Preview): Unit = {previews.add(value);()}
      override def onLog(value: String): Unit = {logs.add(value);()}
    try
      val hello=services.start()
      assertEquals((hello.python,hello.device),("","cuda:0"))
      services.loadCheckpoint(root.toString,remember=false)
      val generated=services.generator.generateWith(GenerationParams(prompt="a photograph of a red apple on a wooden table",
        width=64,height=64,steps=3,seed=42,samplerName="Euler",scheduler="Uniform"),listener).head
      assert(Files.isRegularFile(generated.path))
      assertEquals(previews.size(),2)
      assert(!logs.asScala.exists(_.contains("TAESD preview unavailable")))
      val dir=Files.createDirectories(Path.of(output.get))
      for (preview,index)<-previews.asScala.toVector.zipWithIndex do
        val bytes=java.util.Base64.getDecoder.decode(preview.jpegB64)
        assert(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))!=null)
        Files.write(dir.resolve(s"sdxl-taesd-$index.jpg"),bytes)
      assert(!Files.exists(home.pythonDir))
    finally services.shutdown()
  }
