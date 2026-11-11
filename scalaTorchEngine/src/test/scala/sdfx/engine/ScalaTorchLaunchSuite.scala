package sdfx.engine

import sdfx.engine.scalatorch.ScalaTorchLaunch

import java.nio.file.{Files,Path}

class ScalaTorchLaunchSuite extends munit.FunSuite:
  test("SD1 ckpt starts as a single-file model with a tokenizer") {
    val temp=Files.createTempDirectory("native ckpt launch")
    val checkpoint=Files.write(temp.resolve("model.ckpt"),Array[Byte](1))
    val cp=Files.writeString(temp.resolve("classpath.txt"),temp.toString)
    val tokenizer=Files.createDirectories(temp.resolve("tokenizer"))
    Files.writeString(tokenizer.resolve("vocab.json"),"{}")
    Files.writeString(tokenizer.resolve("merges.txt"),"")
    val env=Map("SDFX_SCALATORCH_CLASSPATH_FILE"->cp.toString,"SDFX_SCALATORCH_MODEL"->checkpoint.toString)
    val launch=ScalaTorchLaunch.fromEnvironment(env).get
    assertEquals(launch.command.takeRight(2),Seq(checkpoint.toString,"-"))
    assertEquals(launch.env.get("SDFX_SCALATORCH_TOKENIZER"),Some(tokenizer.toString))
  }
  test("engine selection is isolated behind one backend and preserves Python default/legacy opt-in") {
    val home=sdfx.core.config.AppHome(Files.createTempDirectory("engine backend"))
    try
      val settings=sdfx.core.config.Settings()
      val pythonAvailable=EngineSelection.availableBackends.contains("python")
      assertEquals(EngineSelection.usesNativeBackend(settings,Map.empty),!pythonAvailable)
      assert(EngineSelection.usesNativeBackend(settings,Map("SDFX_ENGINE_BACKEND"->" scala-torch ")))
      assert(EngineSelection.usesNativeBackend(settings,Map("SDFX_SCALATORCH_MODEL"->"")))
      assert(EngineSelection.usesNativeBackend(settings.copy(engineBackend="scala-torch"),Map.empty))
      assertEquals(EngineBackend.select(home,settings,false,Map.empty).name,if pythonAvailable then "python" else "scala-torch")
      if pythonAvailable then
        assertEquals(EngineBackend.select(home,settings,false,Map("SDFX_ENGINE_BACKEND"->" PYTHON ")).name,"python")
      else intercept[IllegalArgumentException](EngineBackend.select(home,settings,false,Map("SDFX_ENGINE_BACKEND"->" PYTHON ")))
      intercept[IllegalArgumentException](EngineBackend.launch(home,settings,false,Map("SDFX_ENGINE_BACKEND"->"mystery")))
      intercept[IllegalArgumentException](EngineBackend.launch(home,settings,true,Map("SDFX_ENGINE_BACKEND"->"scala-torch")))
      intercept[IllegalArgumentException](EngineBackend.launch(home,settings,false,Map("SDFX_ENGINE_BACKEND"->"scala-torch")))
      intercept[IllegalArgumentException](EngineBackend.launch(home,settings,false,Map("SDFX_SCALATORCH_MODEL"->"")))
    finally
      val files=Files.walk(home.root)
      try files.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.delete(_)) finally files.close()
  }
  test("saved engine backend preference selects scala-torch without environment selector") {
    val temp=Files.createTempDirectory("saved backend")
    try
      val cp=temp.resolve("worker classpath.txt");val scheduler=temp.resolve("scheduler.json")
      Files.writeString(cp,temp.toString);Files.writeString(scheduler,"{}")
      val env=Map("SDFX_SCALATORCH_CLASSPATH_FILE"->cp.toString,
        "SDFX_SCALATORCH_MODEL"->temp.toString,"SDFX_SCALATORCH_SCHEDULER"->scheduler.toString)
      val launch=EngineBackend.launch(sdfx.core.config.AppHome(temp),
        sdfx.core.config.Settings(engineBackend="scala-torch"),false,env)
      assertEquals(launch.command.last,scheduler.toString)
      val fromSaved=EngineBackend.launch(sdfx.core.config.AppHome(temp),sdfx.core.config.Settings(
        engineBackend="scala-torch",scalaTorchClasspath=Some(cp.toString),scalaTorchModel=Some(temp.toString),
        scalaTorchScheduler=Some(scheduler.toString),scalaTorchUnetDevice="cuda",
        scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true),false,Map.empty)
      assertEquals(fromSaved.command.last,scheduler.toString)
      assertEquals(fromSaved.env.get("SDFX_SCALATORCH_UNET_DEVICE"),Some("cuda"))
      assertEquals(fromSaved.env.get("SDFX_SCALATORCH_VAE_DEVICE"),Some("cuda"))
      assertEquals(fromSaved.env.get("SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS"),Some("true"))
      assertEquals(fromSaved.env.get("SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"),Some("true"))
      val overrideDevice=EngineBackend.launch(sdfx.core.config.AppHome(temp),sdfx.core.config.Settings(
        engineBackend="scala-torch",scalaTorchClasspath=Some(cp.toString),scalaTorchModel=Some(temp.toString),
        scalaTorchScheduler=Some(scheduler.toString),scalaTorchUnetDevice="cuda"),false,
        Map("SDFX_SCALATORCH_UNET_DEVICE"->"cpu"))
      assertEquals(overrideDevice.env.get("SDFX_SCALATORCH_UNET_DEVICE"),Some("cpu"))
      intercept[IllegalArgumentException](EngineBackend.launch(sdfx.core.config.AppHome(temp),
        sdfx.core.config.Settings(engineBackend="invalid"),false,env))
    finally
      val files=Files.list(temp)
      try files.forEach(p => { Files.delete(p);() }) finally files.close()
      Files.delete(temp)
  }
  test("native selection is explicit and incomplete configuration never falls back") {
    assert(!ScalaTorchLaunch.requested(Map.empty))
    assertEquals(ScalaTorchLaunch.fromEnvironment(Map.empty),None)
    assert(ScalaTorchLaunch.requested(Map("SDFX_SCALATORCH_MODEL"->"")))
    intercept[IllegalArgumentException](ScalaTorchLaunch.fromEnvironment(Map("SDFX_SCALATORCH_MODEL"->"")))
  }
  test("launch preserves paths containing spaces and requires existing inputs") {
    val temp=Files.createTempDirectory("native launch ")
    try
      val cp=temp.resolve("classpath.txt")
      val scheduler=temp.resolve("scheduler.json")
      Files.writeString(cp,temp.toString)
      Files.writeString(scheduler,"{}")
      val env=Map("SDFX_SCALATORCH_CLASSPATH_FILE"->cp.toString,"SDFX_SCALATORCH_MODEL"->temp.toString,
        "SDFX_SCALATORCH_SCHEDULER"->scheduler.toString)
      val launch=ScalaTorchLaunch.fromEnvironment(env).get
      assert(launch.command.contains("-XX:MaxRAMPercentage=50.0"))
      assertEquals(launch.command.takeRight(2),Seq(temp.toString,scheduler.toString))
      assertEquals(launch.command(launch.command.indexOf("-cp")+1),temp.toString)
      intercept[IllegalArgumentException](ScalaTorchLaunch.fromEnvironment(env.updated("SDFX_SCALATORCH_JAVA",temp.resolve("missing.exe").toString)))
      Files.writeString(cp,temp.resolve("missing.jar").toString)
      intercept[IllegalArgumentException](ScalaTorchLaunch.fromEnvironment(env))
      val managedLaunch=ScalaTorchLaunch.fromEnvironment(Map("SDFX_SCALATORCH_MODEL"->temp.toString,"SDFX_SCALATORCH_SCHEDULER"->scheduler.toString)).get
      assert(managedLaunch.command(managedLaunch.command.indexOf("-cp")+1).contains("scala-torch-models"))
      val home=sdfx.core.config.AppHome(temp)
      val services=new AppServices(home,sdfx.core.config.Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None),
        dryRun=false,builtins=Nil,launchOverride=Some(() => managedLaunch),persistSettings=false,installShutdownHook=false)
      try
        val hello=services.start()
        assert(hello.features.contains("batch_interrogate_wd_v3_partial"))
        assert(hello.features.contains("controlnet_aux"))
      finally services.shutdown()
    finally
      val files=Files.walk(temp)
      try files.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => { Files.deleteIfExists(p);() }) finally files.close()
  }
  test("zero configuration: last used checkpoint, bundled tokenizer, device and memory defaults, no environment variables") {
    val temp=Files.createTempDirectory("native zero config")
    val st=Files.createDirectories(temp.resolve("scala-torch"))
    Files.writeString(st.resolve("worker-classpath.txt"),temp.toString)
    val tok=Files.createDirectories(st.resolve("tokenizer"))
    Files.writeString(tok.resolve("vocab.json"),"{}"); Files.writeString(tok.resolve("merges.txt"),"")
    val ckpts=Files.createDirectories(temp.resolve("Stable-diffusion"))
    Files.write(ckpts.resolve("a-first.safetensors"),Array[Byte](1))
    val wanted=Files.write(ckpts.resolve("b-last.safetensors"),Array[Byte](1))
    def settings(last: Option[String])=sdfx.core.config.Settings(engineBackend="scala-torch",
      modelDirs=sdfx.core.config.ModelDirs(checkpoints=Seq(ckpts.toString)),lastModel=last)
    val home=sdfx.core.config.AppHome(temp)
    val l=EngineBackend.launch(home,settings(Some("b-last")),false,Map.empty)
    assertEquals(l.command.takeRight(2).head,wanted.toAbsolutePath.normalize.toString)
    assertEquals(l.env("SDFX_SCALATORCH_TOKENIZER"),tok.toAbsolutePath.normalize.toString)
    assertEquals(l.env("SDFX_SCALATORCH_UNET_MAX_BYTES"),"auto")
    assertEquals(l.env("SDFX_SCALATORCH_MAX_ATTENTION_SCORE_ELEMENTS"),(1L<<30).toString)
    // cuDNN folder and VRAM limit: absent unless set; a saved setting reaches the worker, an environment value wins
    assert(!l.env.contains("SCALATORCH_CUDNN_PATH") && !l.env.contains("SCALATORCH_CUDA_VRAM_LIMIT_MB"))
    val tuned=settings(Some("b-last")).copy(scalaTorchCudnnDir=Some(" D:/cudnn "),scalaTorchVramLimitMb=Some(6000))
    val tunedLaunch=EngineBackend.launch(home,tuned,false,Map.empty)
    assertEquals(tunedLaunch.env("SCALATORCH_CUDNN_PATH"),"D:/cudnn")
    assertEquals(tunedLaunch.env("SCALATORCH_CUDA_VRAM_LIMIT_MB"),"6000")
    val l4=EngineBackend.launch(home,tuned,false,Map("SCALATORCH_CUDA_VRAM_LIMIT_MB"->"4000"))
    assertEquals(l4.env("SCALATORCH_CUDA_VRAM_LIMIT_MB"),"4000")
    assert(!EngineBackend.launch(home,tuned.copy(scalaTorchVramLimitMb=Some(0)),false,Map.empty).env.contains("SCALATORCH_CUDA_VRAM_LIMIT_MB"))
    assert(Set("cpu","cuda").contains(l.env("SDFX_SCALATORCH_UNET_DEVICE")))
    // without a last model the first checkpoint is used; an explicit environment value wins over the defaults
    val l2=EngineBackend.launch(home,settings(None),false,Map("SDFX_SCALATORCH_UNET_MAX_BYTES"->"123","SDFX_SCALATORCH_UNET_DEVICE"->"cpu"))
    assert(l2.command.takeRight(2).head.endsWith("a-first.safetensors"))
    assertEquals(l2.env("SDFX_SCALATORCH_UNET_MAX_BYTES"),"123")
    assertEquals(l2.env("SDFX_SCALATORCH_UNET_DEVICE"),"cpu")
    // a folder given as SDFX_SCALATORCH_JAVA is accepted when it contains the executable
    val bin=Files.createDirectories(temp.resolve("jdk/bin"))
    val exe=Files.write(bin.resolve(if sys.props("os.name").startsWith("Windows") then "java.exe" else "java"),Array[Byte](1))
    val l3=EngineBackend.launch(home,settings(None),false,Map("SDFX_SCALATORCH_JAVA"->bin.toString))
    assertEquals(l3.command.head,exe.toAbsolutePath.normalize.toString)
    // no checkpoint anywhere -> a clear failure
    intercept[IllegalArgumentException](EngineBackend.launch(home,sdfx.core.config.Settings(engineBackend="scala-torch"),false,Map.empty))
  }
  test("CUDA memory budgets default to auto (free device memory) and an explicit value wins") {
    val temp=Files.createTempDirectory("native budget")
    val st=Files.createDirectories(temp.resolve("scala-torch"))
    Files.writeString(st.resolve("worker-classpath.txt"),temp.toString)
    val tok=Files.createDirectories(st.resolve("tokenizer"))
    Files.writeString(tok.resolve("vocab.json"),"{}"); Files.writeString(tok.resolve("merges.txt"),"")
    val ckpts=Files.createDirectories(temp.resolve("Stable-diffusion"))
    Files.write(ckpts.resolve("m.safetensors"),Array[Byte](1))
    def launch(dtype: String,env: Map[String,String]=Map.empty)=EngineBackend.launch(sdfx.core.config.AppHome(temp),sdfx.core.config.Settings(engineBackend="scala-torch",dtype=dtype,
      modelDirs=sdfx.core.config.ModelDirs(checkpoints=Seq(ckpts.toString))),false,env)
    for d <- Seq("fp16","fp32") do
      assertEquals(launch(d).env("SDFX_SCALATORCH_UNET_MAX_BYTES"),"auto")
      assertEquals(launch(d).env("SDFX_SCALATORCH_VAE_MAX_BYTES"),"auto")
    assertEquals(launch("fp16",Map("SDFX_SCALATORCH_UNET_MAX_BYTES"->"4294967296")).env("SDFX_SCALATORCH_UNET_MAX_BYTES"),"4294967296")
  }
