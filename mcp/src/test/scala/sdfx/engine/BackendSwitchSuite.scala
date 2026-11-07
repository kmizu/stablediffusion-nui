package sdfx.engine

import sdfx.core.config.{AppHome,Settings}
import java.nio.file.Files

/** Exercises the provider classpath used by the normal MCP distribution. */
class BackendSwitchSuite extends munit.FunSuite:
  test("normal distribution defaults to Python and can explicitly select either provider") {
    val available=EngineSelection.availableBackends.toSet
    assume(available.contains("python"),"normal distribution only")
    assert(available.contains("scala-torch"),s"missing native provider: $available")
    val home=AppHome(Files.createTempDirectory("backend-switch"))
    val settings=Settings()
    assertEquals(EngineBackend.select(home,settings,false,Map.empty).name,"python")
    assertEquals(EngineBackend.select(home,settings,false,Map("SDFX_ENGINE_BACKEND"->"scala-torch")).name,"scala-torch")
    assertEquals(EngineBackend.select(home,settings,false,Map("SDFX_ENGINE_BACKEND"->"python")).name,"python")
    assert(!EngineSelection.usesNativeBackend(settings,Map.empty))
    assert(EngineSelection.usesNativeBackend(settings,Map("SDFX_ENGINE_BACKEND"->"scala-torch")))
  }
