package sdfx.engine

import sdfx.core.protocol.*
import sdfx.core.model.{GenerationParams,Img2ImgParams}
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
    var failure: Throwable=null
    try
      val hello=supervisor.start()
      assertEquals(hello.python,"")
      val requestedUnetDevice=sys.env.getOrElse("SDFX_SCALATORCH_UNET_DEVICE","cpu")
      val expectedUnetDevice=if requestedUnetDevice=="cuda" then "cuda:0" else requestedUnetDevice
      assertEquals(hello.device,expectedUnetDevice)
      val models=Await.result(client.listModels(Seq(root.getParent.toString)),10.seconds)
      assertEquals(models.map(_.path),Seq(root.toRealPath().toString))
      assert(models.head.sizeMb>0)
      val samplers=Await.result(client.listSamplers(),10.seconds)
      assertEquals(samplers.samplers.map(_.name),Seq("Euler","DPM++ 2M"))
      assertEquals(samplers.samplers.last.defaultScheduler,"Karras")
      val vaes=Await.result(client.listFiles("vae",Seq(root.resolve("vae").toString)),10.seconds)
      assertEquals(vaes.map(_.name),Seq("diffusion_pytorch_model"))
      assert(vaes.head.sizeMb>0)
      assertEquals(Await.result(client.systemStatus(),10.seconds)("device")("active").str,expectedUnetDevice)
      assertEquals(Await.result(client.systemStatus(),10.seconds)("runtime")("unet_device").str,expectedUnetDevice)
      val requestedVaeDevice=sys.env.getOrElse("SDFX_SCALATORCH_VAE_DEVICE","cpu")
      val expectedVaeDevice=if requestedVaeDevice=="cuda" then "cuda:0" else requestedVaeDevice
      assertEquals(Await.result(client.systemStatus(),10.seconds)("runtime")("vae_device").str,expectedVaeDevice)
      val placement=Await.result(client.systemStatus(),10.seconds)("runtime")
      assertEquals(placement("unet_offload_weights").bool,expectedUnetDevice!="cpu" &&
        sys.env.get("SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS").contains("true"))
      assertEquals(placement("vae_offload_weights").bool,expectedVaeDevice!="cpu" &&
        sys.env.get("SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS").contains("true"))
      val loaded=Await.result(client.loadModel(LoadModelRequest(root.toString,dtype="fp32",vramMode="cpu")),30.seconds)
      assertEquals(loaded.path,root.toRealPath().toString)
      assert(loaded.hash.matches("[0-9a-f]{64}"))
      val swapped=Await.result(client.setVae(Some(root.resolve("vae/diffusion_pytorch_model.safetensors").toString)),20.seconds)
      assert(swapped.vaeHash.exists(_.matches("[0-9a-f]{64}")))
      assertEquals(Await.result(client.setVae(None),20.seconds).vaeName,None)
      assertEquals(Await.result(client.systemStatus(),10.seconds)("runtime")("unet_device").str,expectedUnetDevice)
      assertEquals(Await.result(client.systemStatus(),10.seconds)("runtime")("vae_device").str,expectedVaeDevice)
      def mapping(action: String)=Await.result(client.raw("model_keyword",ujson.Obj("action"->action,
        "kind"->"checkpoint","model_path"->root.toString,"mapping_dir"->temp.toString,"keywords"->"a red")),10.seconds)
      assertEquals(mapping("check")("source").str,"no match")
      val saved=mapping("save")
      assertEquals(saved("hash").str,loaded.hash)
      assertEquals(saved("source").str,"custom")
      assertEquals(mapping("check")("keywords").arr.map(_.str).toVector,Vector("a red"))
      val events=new ConcurrentLinkedQueue[EngineEvent]()
      val request=GenerationRequest(GenerationParams(prompt="cat",width=64,height=64,steps=3,seed=42,
        hooks=Map("model_keyword"->ujson.Obj("_mapping_dir"->temp.toString))),temp.toString)
      val result=Await.result(client.generate(request,GenerationMode.Txt2Img,event => { events.add(event);() }).result,45.seconds)
      assertEquals(result.images.size,1)
      assertEquals(result.images.head.seed,42L)
      val image=javax.imageio.ImageIO.read(Path.of(result.images.head.path).toFile)
      assertEquals(image.getWidth,64);assertEquals(image.getHeight,64)
      assertEquals(events.asScala.collect { case p: EngineEvent.Progress => p.step }.toVector,Vector(1,2,3))
      assert(events.asScala.exists(_.isInstanceOf[EngineEvent.Preview]))
      assertEquals(result.images.head.effectivePrompt,Some("a red cat"))
      events.clear()
      val edit=request.copy(params=request.params.copy(steps=4,
        img2img=Some(Img2ImgParams(result.images.head.path,denoisingStrength=0.5,resizeMode=2))))
      val edited=Await.result(client.generate(edit,GenerationMode.Img2Img,event => {events.add(event);()}).result,45.seconds)
      assertEquals(edited.images.size,1)
      assertEquals(edited.images.head.seed,42L)
      assertEquals(edited.images.head.effectivePrompt,Some("a red cat"))
      val editedImage=javax.imageio.ImageIO.read(Path.of(edited.images.head.path).toFile)
      assertEquals(editedImage.getWidth,64);assertEquals(editedImage.getHeight,64)
      assertEquals(events.asScala.collect {case p: EngineEvent.Progress => p.step}.toVector,Vector(1,2))
      assert(mapping("delete")("deleted").bool)
      assertEquals(mapping("check")("source").str,"no match")
      Await.result(client.unload(),10.seconds)
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
  }

  test("NUI EngineClient sends SDXL txt2img and img2img through the Python-free worker") {
    val cpFile=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_SDXL_MODEL")
    assume(cpFile.nonEmpty && model.nonEmpty,"native worker classpath and tiny SDXL model required")
    val root=Path.of(model.get).toAbsolutePath
    val temp=Files.createTempDirectory("nui-native-sdxl")
    val config=temp.resolve("euler.json")
    Files.writeString(config,"""{"num_train_timesteps":1000,"beta_start":0.00085,"beta_end":0.012,"beta_schedule":"scaled_linear"}""")
    val launch=ScalaTorchLaunch.fromEnvironment(Map("SDFX_SCALATORCH_CLASSPATH_FILE"->cpFile.get,
      "SDFX_SCALATORCH_MODEL"->root.toString,"SDFX_SCALATORCH_SCHEDULER"->config.toString)).get
    val supervisor=new EngineSupervisor(() => launch,maxRestarts=0)
    val client=new EngineClient(supervisor)
    var failure: Throwable=null
    try
      assertEquals(supervisor.start().python,"")
      val loaded=Await.result(client.loadModel(LoadModelRequest(root.toString,dtype="fp32",vramMode="cpu")),45.seconds)
      assertEquals(loaded.path,root.toRealPath().toString)
      assert(loaded.hash.matches("[0-9a-f]{64}"))
      val params=GenerationParams(prompt="a red apple",negativePrompt="blurry",width=64,height=64,steps=3,seed=42)
      val request=GenerationRequest(params,temp.toString)
      val generated=Await.result(client.generate(request,GenerationMode.Txt2Img).result,60.seconds)
      assertEquals(generated.images.size,1)
      assertEquals(generated.images.head.seed,42L)
      val image=javax.imageio.ImageIO.read(Path.of(generated.images.head.path).toFile)
      assertEquals(image.getWidth,64);assertEquals(image.getHeight,64)
      val edit=request.copy(params=params.copy(steps=4,
        img2img=Some(Img2ImgParams(generated.images.head.path,denoisingStrength=0.5,resizeMode=2))))
      val edited=Await.result(client.generate(edit,GenerationMode.Img2Img).result,60.seconds)
      val result=javax.imageio.ImageIO.read(Path.of(edited.images.head.path).toFile)
      assertEquals(result.getWidth,64);assertEquals(result.getHeight,64)
      assertEquals(edited.images.head.seed,42L)
      assertEquals(edited.images.head.effectivePrompt,Some("a red apple"))
      Await.result(client.unload(),15.seconds)
      assert(!Files.exists(temp.resolve("python")))
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
  }
