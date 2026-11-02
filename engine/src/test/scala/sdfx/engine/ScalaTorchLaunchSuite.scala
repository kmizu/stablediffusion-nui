package sdfx.engine

import java.nio.file.{Files,Path}

class ScalaTorchLaunchSuite extends munit.FunSuite:
  test("engine selection is isolated behind one backend and preserves Python default/legacy opt-in") {
    val home=sdfx.core.config.AppHome(Files.createTempDirectory("engine backend"))
    try
      val settings=sdfx.core.config.Settings()
      assertEquals(EngineBackend.select(home,settings,false,Map.empty).name,"python")
      assertEquals(EngineBackend.select(home,settings,false,Map("SDFX_ENGINE_BACKEND"->" PYTHON ")).name,"python")
      intercept[IllegalArgumentException](EngineBackend.launch(home,settings,false,Map("SDFX_ENGINE_BACKEND"->"mystery")))
      intercept[IllegalArgumentException](EngineBackend.launch(home,settings,true,Map("SDFX_ENGINE_BACKEND"->"scala-torch")))
      intercept[IllegalArgumentException](EngineBackend.launch(home,settings,false,Map("SDFX_ENGINE_BACKEND"->"scala-torch")))
      intercept[IllegalArgumentException](EngineBackend.launch(home,settings,false,Map("SDFX_SCALATORCH_MODEL"->"")))
    finally
      val files=Files.walk(home.root)
      try files.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.delete(_)) finally files.close()
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
      assertEquals(launch.command.takeRight(2),Seq(temp.toString,scheduler.toString))
      assertEquals(launch.command(launch.command.indexOf("-cp")+1),temp.toString)
      intercept[IllegalArgumentException](ScalaTorchLaunch.fromEnvironment(env.updated("SDFX_SCALATORCH_JAVA",temp.resolve("missing.exe").toString)))
      Files.writeString(cp,temp.resolve("missing.jar").toString)
      intercept[IllegalArgumentException](ScalaTorchLaunch.fromEnvironment(env))
    finally
      val files=Files.list(temp)
      try files.forEach(p => { Files.delete(p);() }) finally files.close()
      Files.delete(temp)
  }
