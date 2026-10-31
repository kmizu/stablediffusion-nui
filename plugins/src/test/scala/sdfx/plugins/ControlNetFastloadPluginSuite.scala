package sdfx.plugins

import sdfx.core.plugins.api.{PluginKind, Values}

class ControlNetFastloadPluginSuite extends munit.FunSuite:
  test("Fastload controls describe private per-job behavior and default to disabled") {
    val plugin = new ControlNetFastloadPlugin
    assertEquals(plugin.id, "controlnet_fastload")
    assertEquals(plugin.kind, PluginKind.AlwaysOn)
    val defaults = Values.defaults(plugin.controls)
    assert(!plugin.isActive(defaults))
    assertEquals(defaults.str("mode"), "load_only")
    assertEquals(defaults.str("priority"), "current_first")
    assertEquals(defaults.str("save_format"), "embedded")
    assert(plugin.requiresEngineFeatures.contains("controlnet_fastload_data"))
  }

  test("Fastload is discoverable in the built-in AlwaysOn registry") {
    assert(StandardPlugins.alwaysOn.exists(_.id == "controlnet_fastload"))
  }
