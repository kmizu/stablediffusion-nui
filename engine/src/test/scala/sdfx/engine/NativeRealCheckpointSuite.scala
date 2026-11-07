package sdfx.engine

import sdfx.core.model.GenerationParams
import sdfx.core.protocol.*
import java.nio.file.{Files,Path}
import scala.concurrent.Await
import scala.concurrent.duration.*

/** Opt-in full-checkpoint smoke test for low-VRAM Python-free generation. */
class NativeRealCheckpointSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 240.seconds

  private def generate(modelVariable: String,family: String): Unit =
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get(modelVariable)
    assume(cpFile.nonEmpty && model.nonEmpty,s"native worker classpath and $family Diffusers model required")
    val root=Path.of(model.get).toAbsolutePath
    val temp=Files.createTempDirectory(s"nui-native-full-$family")
    val config=temp.resolve("euler.json")
    Files.writeString(config,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear"}""")
    val launch=NativeTestLaunch(cpFile.get,root,config)
    val supervisor=new EngineSupervisor(() => launch,maxRestarts=0)
    val client=new EngineClient(supervisor)
    var failure: Throwable=null
    try
      val hello=supervisor.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      val loaded=Await.result(client.loadModel(LoadModelRequest(root.toString,dtype="fp32",vramMode="cpu")),90.seconds)
      assertEquals(loaded.path,root.toRealPath().toString)
      assert(loaded.hash.matches("[0-9a-f]{64}"))
      val placement=Await.result(client.systemStatus(),10.seconds)("runtime")
      assertEquals(placement("unet_device").str,"cuda:0")
      assertEquals(placement("vae_device").str,"cuda:0")
      assert(placement("unet_offload_weights").bool)
      assert(placement("vae_offload_weights").bool)
      val width=sys.env.getOrElse("SCALATORCH_REAL_WIDTH","64").toInt
      val height=sys.env.getOrElse("SCALATORCH_REAL_HEIGHT","64").toInt
      val steps=sys.env.getOrElse("SCALATORCH_REAL_STEPS","2").toInt
      require(width>=64 && width<=1024 && height>=64 && height<=1024 && steps>=1 && steps<=50,
        "real-checkpoint test dimensions or steps are outside the safe test range")
      val params=GenerationParams(prompt="a red cat",width=width,height=height,steps=steps,seed=4242,samplerName="Heun")
      val result=Await.result(client.generate(GenerationRequest(params,temp.toString),GenerationMode.Txt2Img).result,180.seconds)
      assertEquals(result.images.size,1)
      assertEquals(result.images.head.seed,4242L)
      val image=javax.imageio.ImageIO.read(Path.of(result.images.head.path).toFile)
      assertEquals(image.getWidth,width)
      assertEquals(image.getHeight,height)
      if sys.env.get("SCALATORCH_CUDA_PROFILE").contains("1") then
        supervisor.processSnapshot.toVector.flatMap(_.stderrLines)
          .filter(_.startsWith("[scala-torch CUDA profile]")).foreach(System.err.println)
      Await.result(client.unload(),20.seconds)
    catch case error: Throwable =>
      failure=error
      throw error
    finally
      try
        supervisor.close()
        val files=Files.walk(temp)
        try files.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => {
          require(p.toAbsolutePath.normalize().startsWith(temp.toAbsolutePath.normalize()))
          Files.delete(p);()
        }) finally files.close()
      catch case cleanup: Throwable =>
        if failure!=null then failure.addSuppressed(cleanup) else throw cleanup

  test("NUI EngineClient generates from a full checkpoint with CUDA weight offload") {
    val configured=Vector("SCALATORCH_REAL_SD15_MODEL"->"SD1.5","SCALATORCH_REAL_SDXL_MODEL"->"SDXL")
      .filter((name,_)=>sys.env.get(name).exists(_.trim.nonEmpty))
    require(configured.size<=1,"set only one full-checkpoint model variable per test run")
    assume(configured.nonEmpty,"set SCALATORCH_REAL_SD15_MODEL or SCALATORCH_REAL_SDXL_MODEL")
    generate(configured.head._1,configured.head._2)
  }
