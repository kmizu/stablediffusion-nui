package sdfx.engine

import sdfx.core.config.Settings

/** The engine module's own test classpath has no backend module (those live in pythonEngine and scalaTorchEngine). */
final class EngineBackendSelectionSuite extends munit.FunSuite:
  test("with no backend module installed the startup check answers false instead of throwing") {
    assertEquals(EngineBackend.availableProviders, Vector.empty[String])
    assertEquals(EngineBackend.usesNativeBackend(Settings(), Map.empty), false)
    assertEquals(EngineSelection.usesNativeBackend(Settings(), Map.empty), false)
  }

  test("an explicit scala-torch request still selects the native backend") {
    assertEquals(EngineBackend.usesNativeBackend(Settings(), Map("SDFX_ENGINE_BACKEND" -> "scala-torch")), true)
    assertEquals(EngineBackend.usesNativeBackend(Settings(), Map("SDFX_ENGINE_BACKEND" -> "python")), false)
  }
