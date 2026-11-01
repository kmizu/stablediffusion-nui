package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class SonarSuite extends munit.FunSuite:
  private val plugin = new SonarPlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("controls follow the source defaults"):
    val d = Values.defaults(plugin.controls)
    assert(!plugin.isActive(d))
    assertEquals(d.str("sampler"), "Euler a")
    assertEquals(d.str("momentum"), "0.95")
    assertEquals(d.str("momentum_hist"), "0.75")
    assertEquals(d.str("momentum_hist_init"), "zero")
    assertEquals(d.str("momentum_sign"), "pos")
    assertEquals(d.str("ref_meth"), "linear")
    assertEquals(d.str("ref_hgf"), "0.2")
    assertEquals(d.str("ref_max_step"), "0.75")
    assertEquals(d.str("upscaler"), "Lanczos")
    assertEquals(d.str("upscale"), "false")
    assertEquals(plugin.requiresEngineFeatures, Seq("sonar"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)
    assertEquals(plugin.controls.map(_.key).distinct.size, plugin.controls.size)

  test("a default run writes the three source keys only"):
    val params = plugin.process(GenerationParams(), enabled())
    assertEquals(params.extra, Map("Sonar sampler" -> "Euler a", "Sonar momentum" -> "0.95", "Sonar momentum_hist" -> "0.75"))
    val hook = params.hooks(plugin.id)
    assertEquals(hook("momentum").num, 0.95)
    assert(!hook.obj.contains("enabled"))
    assertEquals(hook("upscale").bool, false)

  test("non-default options, the reference group and the upscale group add keys"):
    val params = plugin.process(GenerationParams(), enabled("sampler" -> "Naive", "momentum" -> "0.9",
      "momentum_sign" -> "neg", "momentum_hist_init" -> "rand_new", "ref_img" -> "C:/pics/guide.png",
      "ref_meth" -> "euler", "ref_hgf" -> "0.5", "ref_min_step" -> "2", "ref_max_step" -> "10", "upscale" -> "true",
      "upscaler" -> "R-ESRGAN 4x+", "upscale_ratio" -> "1.5"))
    assertEquals(params.extra, Map("Sonar sampler" -> "Naive", "Sonar momentum" -> "0.9", "Sonar momentum_hist" -> "0.75",
      "Sonar momentum_hist_init" -> "rand_new", "Sonar momentum_sign" -> "neg", "Sonar ref_img" -> "guide.png",
      "Sonar ref_meth" -> "euler", "Sonar ref_hgf" -> "0.5", "Sonar ref_start_step" -> "2", "Sonar ref_stop_step" -> "10",
      "Sonar upscaler" -> "R-ESRGAN 4x+", "Sonar upscale_ratio" -> "1.5", "Sonar upscale_width" -> "0",
      "Sonar upscale_height" -> "0"))
    assertEquals(params.hooks(plugin.id)("ref_img").str, "C:/pics/guide.png")
    // the reference only counts for the Naive sampler
    val euler = plugin.process(GenerationParams(), enabled("ref_img" -> "C:/pics/guide.png"))
    assert(!euler.extra.contains("Sonar ref_img"))
    assertEquals(SonarPlugin.InfotextKeys.toSet, params.extra.keySet)

  test("PNG infotext round trip restores every control but the reference file"):
    val values = enabled("sampler" -> "Naive", "momentum" -> "0.88", "momentum_hist" -> "0.4", "momentum_sign" -> "rand",
      "momentum_hist_init" -> "rand_init", "ref_img" -> "C:/pics/guide.png", "ref_meth" -> "euler", "ref_hgf" -> "0.33",
      "ref_min_step" -> "3", "ref_max_step" -> "0.5", "upscale" -> "true", "upscaler" -> "Nearest",
      "upscale_ratio" -> "2", "upscale_width" -> "1024", "upscale_height" -> "768")
    val params = plugin.process(GenerationParams(prompt = "lion"), values)
    val written = Infotext.write(params, ImageFacts(seed = 1))
    val restored = SonarPlugin.replay(Infotext.parse(written).fields).get
    for (k, v) <- values.map if k != "ref_img" do assertEquals(restored.str(k), v, k)
    assertEquals(restored.str("ref_img"), "")
    val again = plugin.process(GenerationParams(), restored)
    assertEquals(again.hooks(plugin.id)("momentum_sign"), params.hooks(plugin.id)("momentum_sign"))
    assertEquals(again.hooks(plugin.id)("upscale_width"), params.hooks(plugin.id)("upscale_width"))

  test("source PNG text restores with the defaults for the keys it never wrote"):
    val text = "a lion\nSteps: 20, Seed: 1, Size: 512x512, Sonar sampler: Euler, Sonar momentum: 0.85, Sonar momentum_hist: 0.2"
    val restored = SonarPlugin.replay(Infotext.parse(text).fields).get
    assertEquals(restored.str("enabled"), "true")
    assertEquals(restored.str("sampler"), "Euler")
    assertEquals(restored.str("momentum"), "0.85")
    assertEquals(restored.str("momentum_hist"), "0.2")
    assertEquals(restored.str("momentum_sign"), "pos")
    assertEquals(restored.str("upscale"), "false")

  test("replay disables the plugin for other PNGs and rejects malformed values"):
    assertEquals(SonarPlugin.replay(Map("Steps" -> "20")).get.str("enabled"), "false")
    for (k, v) <- Seq("Sonar sampler" -> "DDIM", "Sonar momentum" -> "1.5", "Sonar momentum_hist" -> "nan",
      "Sonar momentum_sign" -> "up", "Sonar ref_meth" -> "cubic", "Sonar upscale_ratio" -> "0.5",
      "Sonar upscale_width" -> "1.5") do
      assertEquals(SonarPlugin.replay(Map("Sonar sampler" -> "Euler a", k -> v)), None, k)

  test("X/Y/Z axes override the fields and the infotext follows"):
    val names = plugin.xyzAxes.map(_.name)
    assertEquals(names, Seq("Sonar sampler", "Sonar momentum", "Sonar momentum_hist", "Sonar momentum_sign",
      "Sonar momentum_hist_init", "Sonar ref_hgf"))
    def axis(name: String) = plugin.xyzAxes.find(_.name == name).get
    assertEquals(StandardPlugins.alwaysOn.flatMap(_.xyzAxes).map(_.name).count(names.contains), names.size)
    val m = plugin.process(axis("Sonar momentum")(GenerationParams(), "0.8"), enabled())
    assertEquals(m.hooks(plugin.id)("momentum").num, 0.8)
    assertEquals(m.extra("Sonar momentum"), "0.8")
    val s = plugin.process(axis("Sonar sampler")(GenerationParams(), " Naive "), enabled())
    assertEquals(s.extra("Sonar sampler"), "Naive")
    val h = plugin.process(axis("Sonar momentum_hist")(axis("Sonar momentum_sign")(GenerationParams(), "neg"), "0.2"), enabled())
    assertEquals(h.extra("Sonar momentum_hist"), "0.2")
    assertEquals(h.extra("Sonar momentum_sign"), "neg")
