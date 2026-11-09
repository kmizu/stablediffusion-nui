package sdfx.core

import sdfx.core.config.Settings

class SettingsMigrationSuite extends munit.FunSuite:
  test("old native-engine defaults (cpu devices, forced fp32 / cpu) are repaired once") {
    val old = Settings(scalaTorchUnetDevice = "cpu", scalaTorchVaeDevice = "cpu", dtype = "fp32", vramMode = "cpu")
    val m = Settings.migrateNativeDefaults(old)
    assertEquals((m.scalaTorchUnetDevice, m.scalaTorchVaeDevice, m.dtype, m.vramMode),
      ("auto", "auto", Settings().dtype, Settings().vramMode))
    assertEquals(m.nativeDefaultsVersion, 1)
    assertEquals(Settings.migrateNativeDefaults(m), m)
  }

  test("choices that cannot be the old defaults are kept") {
    val chosen = Settings(scalaTorchUnetDevice = "cuda:1", scalaTorchVaeDevice = "cpu", dtype = "fp32", vramMode = "gpu")
    assertEquals(Settings.migrateNativeDefaults(chosen), chosen) // nothing to repair: untouched, no version bump
    // after a repair an explicit cpu choice is respected
    val repaired = Settings.migrateNativeDefaults(Settings(scalaTorchUnetDevice = "cpu", scalaTorchVaeDevice = "cpu"))
    val again = Settings.migrateNativeDefaults(repaired.copy(scalaTorchUnetDevice = "cpu", scalaTorchVaeDevice = "cpu"))
    assertEquals((again.scalaTorchUnetDevice, again.scalaTorchVaeDevice), ("cpu", "cpu"))
  }

  test("settings written before the field existed load with version 0 and are migrated") {
    val json = """{"scala_torch_unet_device":"cpu","scala_torch_vae_device":"cpu","dtype":"fp32","vram_mode":"cpu"}"""
    val s = sdfx.core.json.SnakePickle.read[Settings](json)
    assertEquals(s.nativeDefaultsVersion, 0)
    assertEquals(Settings.migrateNativeDefaults(s).scalaTorchUnetDevice, "auto")
  }
