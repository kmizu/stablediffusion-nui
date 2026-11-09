package sdfx.engine

import sdfx.core.config.Settings

/** The engine module's own test classpath has no backend module (those live in pythonEngine and scalaTorchEngine). */
final class EngineBackendSelectionSuite extends munit.FunSuite:
  test("with no backend module installed the startup check answers false instead of throwing") {
    assertEquals(EngineBackend.availableProviders, Vector.empty[String])
    assertEquals(EngineBackend.usesNativeBackend(Settings(), Map.empty), false)
    assertEquals(EngineSelection.usesNativeBackend(Settings(), Map.empty), false)
  }

  test("load_model gets the configured precision on the native backend (fp16 or fp32; bf16 becomes fp16) and the saved values on Python") {
    val saved = Settings(dtype = "fp16", vramMode = "auto")
    assertEquals(EngineBackend.loadModes(saved, Map("SDFX_ENGINE_BACKEND" -> "scala-torch")), ("fp16", "auto"))
    assertEquals(EngineBackend.loadModes(saved.copy(engineBackend = "scala-torch", dtype = "fp32"), Map.empty), ("fp32", "auto"))
    assertEquals(EngineBackend.loadModes(saved.copy(dtype = "bf16"), Map("SDFX_ENGINE_BACKEND" -> "scala-torch")), ("fp16", "auto"))
    assertEquals(EngineBackend.loadModes(saved, Map("SDFX_ENGINE_BACKEND" -> "python")), ("fp16", "auto"))
    assertEquals(EngineBackend.loadModes(saved.copy(engineBackend = "python"), Map.empty), ("fp16", "auto"))
  }

  test("an explicit scala-torch request still selects the native backend") {
    assertEquals(EngineBackend.usesNativeBackend(Settings(), Map("SDFX_ENGINE_BACKEND" -> "scala-torch")), true)
    assertEquals(EngineBackend.usesNativeBackend(Settings(), Map("SDFX_ENGINE_BACKEND" -> "python")), false)
  }
