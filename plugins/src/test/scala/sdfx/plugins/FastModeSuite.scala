package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class FastModeSuite extends munit.FunSuite:
  private val plugin = new FastModePlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("off by default, registered once after DeepCache and CFG Schedule, needs both engine features"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    assertEquals(plugin.requiresEngineFeatures, Seq("deepcache", "cfg_schedule"))
    val ids = StandardPlugins.alwaysOn.map(_.id)
    assertEquals(ids.count(_ == plugin.id), 1)
    assert(ids.indexOf(plugin.id) > ids.indexOf("deepcache") && ids.indexOf(plugin.id) > ids.indexOf("cfg_schedule"))

  test("defaults: DeepCache interval 3 with 5 full steps first, and CFG stepping to 1 at 70 % of the steps"):
    val params = plugin.process(GenerationParams(cfgScale = 6.5), enabled())
    assertEquals(params.hooks("deepcache")("interval").num, 3.0)
    assertEquals(params.hooks("deepcache")("branch").num, 0.0)
    assertEquals(params.hooks("deepcache")("full_first").num, 5.0)
    val schedule = params.hooks("cfg_schedule")
    assertEquals(schedule("start_cfg").num, 6.5)
    assertEquals(schedule("end_cfg").num, 1.0)
    assertEqualsDouble(schedule("start").num, 0.69, 1e-9)
    assertEqualsDouble(schedule("end").num, 0.7, 1e-9)
    assertEquals(params.extra(FastModePlugin.InfotextKey), "CFG until 0.7, DeepCache interval 3")

  test("hooks that are already set keep their values"):
    val own = GenerationParams(hooks = Map("deepcache" -> ujson.Obj("interval" -> 5, "branch" -> 1, "full_first" -> 2),
      "cfg_schedule" -> ujson.Obj("start_cfg" -> 9.0, "end_cfg" -> 3.0, "curve" -> "cosine", "start" -> 0.0, "end" -> 1.0)))
    val params = plugin.process(own, enabled())
    assertEquals(params.hooks("deepcache")("interval").num, 5.0)
    assertEquals(params.hooks("cfg_schedule")("end_cfg").num, 3.0)

  test("each part can be left out: no DeepCache below interval 2, no schedule at 1 or with CFG 1"):
    val noCache = plugin.process(GenerationParams(), enabled("interval" -> "0"))
    assert(!noCache.hooks.contains("deepcache") && noCache.hooks.contains("cfg_schedule"))
    val allSteps = plugin.process(GenerationParams(), enabled("guidance_until" -> "1"))
    assert(allSteps.hooks.contains("deepcache") && !allSteps.hooks.contains("cfg_schedule"))
    val noGuidance = plugin.process(GenerationParams(cfgScale = 1.0), enabled())
    assert(!noGuidance.hooks.contains("cfg_schedule"))
