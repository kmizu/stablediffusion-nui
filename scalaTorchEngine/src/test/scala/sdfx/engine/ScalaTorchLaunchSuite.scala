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
