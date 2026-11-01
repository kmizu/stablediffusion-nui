package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class IncantationsSuite extends munit.FunSuite:
  private val plugin = new IncantationsPlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("controls follow the source defaults and nothing runs until a submodule is active"):
    val d = Values.defaults(plugin.controls)
    assert(!plugin.isActive(d))
    assertEquals(d.str("seg_blur_sigma"), "11")
    assertEquals(d.str("scfg_r"), "4")
    assertEquals(d.str("scfg_rate_min"), "0.8")
    assertEquals(d.str("pag_scale"), "0")
    assertEquals(d.str("cfg_interval_schedule"), "Constant")
    assertEquals(d.str("cfg_interval_high"), "100")
    assertEquals(d.str("t2i0_step_start"), "1")
    assertEquals(d.str("t2i0_step_end"), "25")
    assertEquals(d.str("t2i0_ctnms_alpha"), "0.1")
    assertEquals(IncantationsPlugin.Schedules.size, 17)
    val idle = plugin.process(GenerationParams(), enabled())
    assert(idle.extra.isEmpty && !idle.hooks(plugin.id).obj.exists((k, v) => k.endsWith("_active") && v.bool))
    // SEG Blur Sigma 0 skips SEG entirely, like the source (no infotext)
    val zero = plugin.process(GenerationParams(), enabled("seg_active" -> "true", "seg_blur_sigma" -> "0"))
    assert(zero.extra.isEmpty)
    assertEquals(plugin.requiresEngineFeatures, Seq("incantations"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)

  test("each active submodule writes the source keys in the source order"):
    val params = plugin.process(GenerationParams(), enabled("seg_active" -> "true", "scfg_active" -> "true",
      "pag_active" -> "true", "pag_scale" -> "3", "cfg_interval_enable" -> "true",
      "cfg_interval_schedule" -> "PCS (s=1.0)", "t2i0_active" -> "true", "t2i0_tokens" -> "1,4"))
    val hook = params.hooks(plugin.id)
    assertEquals(hook("seg_active").bool, true)
    assertEquals(hook("scfg_r").num, 4.0)
    assertEquals(hook("cfg_interval_schedule").str, "PCS (s=1.0)")
    assert(!hook.obj.contains("enabled"))
    val expected = Seq("SEG Active" -> "True", "SEG Blur Sigma" -> "11", "SEG Start Step" -> "0",
      "SEG End Step" -> "150", "SCFG Active" -> "True", "SCFG Scale" -> "1", "SCFG Rate Min" -> "0.8",
      "SCFG Rate Max" -> "3", "SCFG Rate Clamp" -> "0", "SCFG Start Step" -> "0", "SCFG End Step" -> "150",
      "SCFG R" -> "4", "PAG Active" -> "True", "PAG SANF" -> "False", "PAG Scale" -> "3", "PAG Start Step" -> "0",
      "PAG End Step" -> "150", "CFG Interval Enable" -> "True", "CFG Interval Schedule" -> "PCS (s=1.0)",
      "CFG Interval Low" -> "0", "CFG Interval High" -> "100", "T2I-0 Active" -> "True", "T2I-0 Window Size" -> "2",
      "T2I-0 Step Start" -> "1", "T2I-0 Step End" -> "25", "T2I-0 CbS Score Threshold" -> "0.5",
      "T2I-0 CbS Correction Strength" -> "0", "T2I-0 CTNMS Alpha" -> "0.1", "T2I-0 CTNMS EMA Smoothing Factor" -> "0",
      "T2I-0 Tokens" -> "1,4")
    assertEquals(plugin.infotextKeys(ujson.Obj.from(hook.obj)), expected)
    assertEquals(params.extra, expected.toMap)
    assertEquals(IncantationsPlugin.InfotextKeys.toSet, expected.map(_._1).toSet)
    val pagOnly = plugin.process(GenerationParams(), enabled("pag_active" -> "true"))
    assertEquals(pagOnly.extra.keySet, Set("PAG Active", "PAG SANF", "PAG Scale", "PAG Start Step", "PAG End Step"))
    val schedulerOnly = plugin.process(GenerationParams(), enabled("cfg_interval_enable" -> "true"))
    assertEquals(schedulerOnly.extra("CFG Interval Enable"), "True")
    assert(!schedulerOnly.extra.contains("PAG Active"))

  test("PNG infotext round trip restores every control"):
    val values = enabled("seg_active" -> "true", "seg_blur_sigma" -> "3.5", "seg_start_step" -> "2",
      "seg_end_step" -> "30", "scfg_active" -> "true", "scfg_scale" -> "1.3", "scfg_r" -> "2",
      "scfg_rate_min" -> "0.5", "scfg_rate_max" -> "10", "scfg_rate_clamp" -> "15", "scfg_start_step" -> "1",
      "scfg_end_step" -> "12", "pag_active" -> "true", "pag_sanf" -> "true", "pag_scale" -> "2.5",
      "pag_start_step" -> "3", "pag_end_step" -> "40", "cfg_interval_enable" -> "true",
      "cfg_interval_schedule" -> "Clamp-Linear (c=4.0)", "cfg_interval_low" -> "0.28", "cfg_interval_high" -> "5.42",
      "t2i0_active" -> "true", "t2i0_step_start" -> "0", "t2i0_step_end" -> "0", "t2i0_tokens" -> "0, 3, 5",
      "t2i0_window_size" -> "4", "t2i0_correction_threshold" -> "0.7", "t2i0_correction_strength" -> "0.25",
      "t2i0_ctnms_alpha" -> "0.05", "t2i0_ema_factor" -> "2")
    val params = plugin.process(GenerationParams(prompt = "lion"), values)
    val written = Infotext.write(params, ImageFacts(seed = 1))
    assert(written.contains("T2I-0 Tokens: \"0, 3, 5\""), written)
    val restored = IncantationsPlugin.replay(Infotext.parse(written).fields).get
    for (k, v) <- values.map do assertEquals(restored.str(k), v, k)
    assertEquals(plugin.process(GenerationParams(), restored).hooks(plugin.id), params.hooks(plugin.id))
    // SANF False round-trips as False (the source's paste would tick it because the key is present)
    val noSanf = plugin.process(GenerationParams(), enabled("pag_active" -> "true", "pag_scale" -> "1"))
    val back = IncantationsPlugin.replay(Infotext.parse(Infotext.write(noSanf, ImageFacts(seed = 1))).fields).get
    assertEquals(back.str("pag_sanf"), "false")
    assertEquals(back.str("seg_active"), "false")

  test("source PNG text restores: Active keys by presence, missing keys take defaults, empty tokens"):
    val text = "a lion\nSteps: 20, Seed: 1, Size: 512x512, SEG Active: True, SEG Blur Sigma: 11, " +
      "SEG Start Step: 0, SEG End Step: 150, T2I-0 Active: True, T2I-0 Window Size: 2, T2I-0 Tokens: , " +
      "CFG Interval Enable: True, CFG Interval Schedule: Interval"
    val restored = IncantationsPlugin.replay(Infotext.parse(text).fields).get
    assertEquals(restored.str("enabled"), "true")
    assertEquals(restored.str("seg_active"), "true")
    assertEquals(restored.str("t2i0_active"), "true")
    assertEquals(restored.str("t2i0_tokens"), "")
    assertEquals(restored.str("cfg_interval_enable"), "true")
    assertEquals(restored.str("cfg_interval_schedule"), "Interval")
    assertEquals(restored.str("pag_active"), "false")
    assertEquals(restored.str("scfg_active"), "false")
    assertEquals(restored.str("t2i0_step_end"), "25")

  test("replay disables the plugin for other PNGs and rejects malformed values"):
    assertEquals(IncantationsPlugin.replay(Map("Steps" -> "20")).get.str("enabled"), "false")
    assertEquals(IncantationsPlugin.replay(Map("CFG Interval Enable" -> "False")).get.str("enabled"), "false")
    for (k, v) <- Seq("SEG Start Step" -> "1.5", "SCFG R" -> "0", "PAG Scale" -> "nan", "PAG SANF" -> "maybe",
      "CFG Interval Schedule" -> "V-Shape", "T2I-0 Window Size" -> "-1") do
      assertEquals(IncantationsPlugin.replay(Map("PAG Active" -> "True", k -> v)), None, k)

  test("source XYZ axes override fields and switch their submodule on unless already set"):
    val names = plugin.xyzAxes.map(_.name)
    assertEquals(names.size, 29)
    assertEquals(names.take(4), Seq("[SEG] Active", "[SEG] SEG Blur Sigma", "[SEG] SEG Start Step", "[SEG] SEG End Step"))
    assert(names.contains("[PAG] CFG Schedule Type") && names.contains("[T2I-0] CTNMS EMA Smoothing Factor"))
    assertEquals(names.distinct.size, names.size)
    val all = StandardPlugins.alwaysOn.flatMap(_.xyzAxes).map(_.name)
    assertEquals(all.count(names.contains), names.size)
    def axis(name: String) = plugin.xyzAxes.find(_.name == name).get
    val sigma = plugin.process(axis("[SEG] SEG Blur Sigma")(GenerationParams(), "2"), enabled())
    assertEquals(sigma.hooks(plugin.id)("seg_active").bool, true)
    assertEquals(sigma.extra("SEG Blur Sigma"), "2")
    val off = plugin.process(axis("[SEG] SEG Blur Sigma")(axis("[SEG] Active")(GenerationParams(), "False"), "2"),
      enabled("seg_active" -> "true"))
    assert(off.extra.isEmpty && !off.hooks(plugin.id)("seg_active").bool)
    val schedule = plugin.process(axis("[PAG] CFG Schedule Type")(GenerationParams(), "Cosine"), enabled())
    assertEquals(schedule.extra("CFG Interval Schedule"), "Cosine")
    assertEquals(schedule.extra("PAG Active"), "True")
    // the source's Noise Interval axes turn PAG on but not the scheduler
    val low = plugin.process(axis("[PAG] CFG Noise Interval Low")(GenerationParams(), "0.28"), enabled())
    assertEquals(low.hooks(plugin.id)("cfg_interval_low").num, 0.28)
    assert(low.extra.contains("PAG Active") && !low.extra.contains("CFG Interval Enable"))
    val t2 = plugin.process(axis("[T2I-0] Active")(GenerationParams(), "TRUE"), enabled())
    assertEquals(t2.extra("T2I-0 Active"), "True")
