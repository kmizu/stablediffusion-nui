package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.png.PngMetadata
import sdfx.core.protocol.LoadModelRequest
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*
import scala.concurrent.Await

/** Opt-in real-checkpoint acceptance through NUI's provider and Generator. */
class NativeSingleFileSuite extends munit.FunSuite:
  override val munitTimeout: Duration=10.minutes
  test("single-file SD1 runs through Generator without a Python installation") {
    val model=sys.env.get("SCALATORCH_SINGLE_FILE")
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val tokenizer=sys.env.get("SCALATORCH_TEST_TOKENIZER")
    assume(model.nonEmpty && cp.nonEmpty && tokenizer.nonEmpty,"real checkpoint, tokenizer and worker classpath required")
    val home=AppHome(Files.createTempDirectory("native-single-file-nui"))
    val path=home.root.resolve("checkpoint.safetensors")
    Files.copy(Path.of(model.get).toRealPath(),path)
    val tokenizerDir=Files.createDirectories(home.root.resolve("scala-torch/tokenizer"))
    Vector("vocab.json","merges.txt").foreach(name => Files.copy(Path.of(tokenizer.get).resolve(name),tokenizerDir.resolve(name)))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=cp,scalaTorchModel=Some(path.toString),scalaTorchUnetDevice="cuda",
      scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      modelDirs=ModelDirs(checkpoints=Seq(path.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      val listed=services.catalog.findCheckpoint(path.getFileName.toString)
      assert(listed.nonEmpty,"configured single-file checkpoint is absent from the catalog")
      assert(Files.isSameFile(Path.of(listed.get.path),path))
      services.loadCheckpoint(path.toString,remember=false)
      intercept[Exception] {
        Await.result(services.client.loadModel(LoadModelRequest(path.toString,dtype="fp16",vramMode="cpu")),2.minutes)
      }
      val replacement=Path.of(tokenizer.get).getParent.resolve("vae").toRealPath()
      Await.result(services.client.setVae(Some(replacement.toString)),2.minutes)
      Await.result(services.client.setVae(None),2.minutes)
      intercept[Exception] {
        Await.result(services.client.setVae(Some(home.root.resolve("missing-vae").toString)),2.minutes)
      }
      Files.write(path,Array[Byte](0,0,0,0,0,0,0,0))
      intercept[Exception] {
        Await.result(services.client.loadModel(LoadModelRequest(path.toString,dtype="fp32",vramMode="cpu")),2.minutes)
      }
      val image=services.generator.generate(GenerationParams(prompt="a red apple",width=64,height=64,steps=1,seed=42)).head
      val decoded=PngMetadata.readImage(image.path)
      assertEquals(decoded.getWidth,64)
      assertEquals(decoded.getHeight,64)
      assert(!Files.exists(home.root.resolve("python")))
    finally services.shutdown()
  }
