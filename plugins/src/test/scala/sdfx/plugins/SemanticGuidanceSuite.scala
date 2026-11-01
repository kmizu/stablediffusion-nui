package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class SemanticGuidanceSuite extends munit.FunSuite:
  private val plugin = new SemanticGuidancePlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("opt-in controls follow source defaults and write source infotext keys"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    val params = plugin.process(GenerationParams(), enabled("prompt" -> "red scarf, (snow:1.2)", "negative_prompt" -> "rain"))
    assertEquals(params.hooks(plugin.id), ujson.Obj("prompt" -> "red scarf, (snow:1.2)", "negative_prompt" -> "rain",
      "warmup" -> 10, "edit_guidance_scale" -> 1.0, "tail_percentage_threshold" -> 0.05, "momentum_scale" -> 0.3,
      "momentum_beta" -> 0.6))
    assertEquals(params.extra("SEGA Active"), "True")
    assertEquals(params.extra("SEGA Prompt"), "red scarf, (snow:1.2)")
    assertEquals(params.extra("SEGA Warmup Period"), "10")
    assertEquals(params.extra("SEGA Tail Percentage Threshold"), "0.05")
    assertEquals(plugin.requiresEngineFeatures, Seq(plugin.id))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)

  test("PNG infotext round trip restores every control, including prompts with commas"):
    val values = enabled("prompt" -> "red scarf, (snow:1.2)", "negative_prompt" -> "rain, fog", "warmup" -> "7",
      "edit_guidance_scale" -> "4.5", "tail_percentage_threshold" -> "0.2", "momentum_scale" -> "0.1", "momentum_beta" -> "0.9")
    val params = plugin.process(GenerationParams(prompt = "castle"), values)
    val fields = Infotext.parse(Infotext.write(params, ImageFacts(seed = 1))).fields
    val restored = SemanticGuidancePlugin.replay(fields).get
    for key <- Seq("prompt", "negative_prompt", "enabled") do assertEquals(restored.str(key), values.str(key), key)
    for key <- Seq("warmup", "edit_guidance_scale", "tail_percentage_threshold", "momentum_scale", "momentum_beta") do
      assertEquals(restored.double(key), values.double(key), key)

  test("replay disables the plugin for other PNGs and rejects malformed numbers"):
    assertEquals(SemanticGuidancePlugin.replay(Map.empty).get.str("enabled"), "false")
    assertEquals(SemanticGuidancePlugin.replay(Map("SEGA Active" -> "False")).get.str("enabled"), "false")
    assertEquals(SemanticGuidancePlugin.replay(Map("SEGA Active" -> "True", "SEGA Tail Percentage Threshold" -> "2")), None)
    assertEquals(SemanticGuidancePlugin.replay(Map("SEGA Active" -> "True", "SEGA Warmup Period" -> "NaN")), None)

  test("source XYZ axes override fields while the plugin is enabled"):
    val names = plugin.xyzAxes.map(_.name)
    assertEquals(names.head, "[Semantic Guidance] Prompt")
    val prompt = plugin.process(plugin.xyzAxes.head(GenerationParams(), " moon "), enabled())
    assertEquals(prompt.hooks(plugin.id)("prompt").str, "moon")
    val scale = plugin.xyzAxes.find(_.name.endsWith("Guidance Scale")).get
    assertEquals(plugin.process(scale(GenerationParams(), "3"), enabled()).hooks(plugin.id)("edit_guidance_scale").num, 3.0)
