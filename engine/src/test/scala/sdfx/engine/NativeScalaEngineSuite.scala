package sdfx.engine

import sdfx.core.protocol.*
import sdfx.core.model.GenerationParams
import java.nio.file.{Files,Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Opt-in cross-repository integration; launches Java directly, never the Python engine. */
class NativeScalaEngineSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 90.seconds
  test("Scala worker accepts real NUI serialization and emits typed images and previews") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cpFile.nonEmpty && model.nonEmpty,"native worker classpath and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val temp=Files.createTempDirectory("nui-native-engine")
    val config=temp.resolve("euler.json")
    Files.writeString(config,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear"}""")
    val launch=ScalaTorchLaunch.fromEnvironment(Map("SDFX_SCALATORCH_CLASSPATH_FILE"->cpFile.get,
      "SDFX_SCALATORCH_MODEL"->root.toString,"SDFX_SCALATORCH_SCHEDULER"->config.toString)).get
    val supervisor=new EngineSupervisor(() => launch,maxRestarts=0)
    val client=new EngineClient(supervisor)
    try
      val hello=supervisor.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cpu")
      val models=Await.result(client.listModels(Seq(root.getParent.toString)),10.seconds)
      assertEquals(models.map(_.path),Seq(root.toRealPath().toString))
      assert(models.head.sizeMb>0)
      val samplers=Await.result(client.listSamplers(),10.seconds)
      assertEquals(samplers.samplers.map(_.name),Seq("Euler","DPM++ 2M"))
      assertEquals(samplers.samplers.last.defaultScheduler,"Karras")
      val vaes=Await.result(client.listFiles("vae",Seq(root.resolve("vae").toString)),10.seconds)
      assertEquals(vaes.map(_.name),Seq("diffusion_pytorch_model"))
      assert(vaes.head.sizeMb>0)
      assertEquals(Await.result(client.systemStatus(),10.seconds)("device")("active").str,"cpu")
      val loaded=Await.result(client.loadModel(LoadModelRequest(root.toString,dtype="fp32",vramMode="cpu")),30.seconds)
      assertEquals(loaded.path,root.toRealPath().toString)
      assert(loaded.hash.matches("[0-9a-f]{64}"))
      val events=new ConcurrentLinkedQueue[EngineEvent]()
      val request=GenerationRequest(GenerationParams(prompt="a red cat",width=64,height=64,steps=3,seed=42),temp.toString)
      val result=Await.result(client.generate(request,GenerationMode.Txt2Img,event => { events.add(event);() }).result,45.seconds)
      assertEquals(result.images.size,1)
      assertEquals(result.images.head.seed,42L)
      val image=javax.imageio.ImageIO.read(Path.of(result.images.head.path).toFile)
      assertEquals(image.getWidth,64);assertEquals(image.getHeight,64)
      assertEquals(events.asScala.collect { case p: EngineEvent.Progress => p.step }.toVector,Vector(1,2,3))
      assert(events.asScala.exists(_.isInstanceOf[EngineEvent.Preview]))
      Await.result(client.unload(),10.seconds)
    finally
      supervisor.close()
      val files=Files.list(temp)
      try files.forEach(p => { Files.delete(p);() }) finally files.close()
      Files.delete(temp)
  }
