package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class ClipIoSuite extends munit.FunSuite:
  private val plugin = new ClipIoPlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("defaults are off, registered once, and need the engine feature"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    assertEquals(plugin.requiresEngineFeatures, Seq("clip_io"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)
    assert(plugin.process(GenerationParams(), enabled()).extra.isEmpty)

  test("input half writes the hook and the Clip Input keys"):
    val params = plugin.process(GenerationParams(), enabled("input_enabled" -> "true", "positive_mode" -> "Directive"))
    val hook = params.hooks(plugin.id)
    assertEquals(hook("input_enabled").bool, true)
    assertEquals(hook("positive_mode").str, "directive")
    assertEquals(hook("negative_mode").str, "off")
    assert(!hook.obj.contains("enabled"))
    assertEquals(params.extra, Map("Clip Input positive" -> "Directive"))

  test("output half writes names, format and the ticked options only"):
    val params = plugin.process(GenerationParams(), enabled("output_enabled" -> "true", "output_name" -> " cat ",
      "negative_output_name" -> "cat, negative", "format" -> "pt", "transpose" -> "true", "token_strings" -> "true"))
    assertEquals(params.hooks(plugin.id)("output_name").str, "cat")
    assertEquals(params.extra, Map("Clip Output" -> "cat", "Clip Output negative" -> "cat, negative",
      "Clip Output format" -> "pt", "Clip Output options" -> "transpose, token strings"))
    val none = plugin.process(GenerationParams(), enabled("output_enabled" -> "true"))
    assert(none.extra.isEmpty) // no name: nothing is written, nothing to replay

  test("PNG infotext round trip restores every control"):
    val values = enabled("input_enabled" -> "true", "positive_mode" -> "Simple", "negative_mode" -> "Directive",
      "output_enabled" -> "true", "output_name" -> "cat", "negative_output_name" -> "dog, neg", "format" -> "pt",
      "transpose" -> "true", "no_special_tokens" -> "true", "ignore_emphasis" -> "true",
      "bypass_normalization" -> "true", "token_strings" -> "true", "overwrite" -> "true")
    val params = plugin.process(GenerationParams(prompt = "castle"), values)
    val fields = Infotext.parse(Infotext.write(params, ImageFacts(seed = 1))).fields
    val restored = ClipIoPlugin.replay(fields).get
    for key <- values.map.keys do assertEquals(restored.str(key).toLowerCase, values.str(key).toLowerCase, key)
    assertEquals(plugin.process(GenerationParams(prompt = "castle"), restored).hooks(plugin.id), params.hooks(plugin.id))

  test("replay switches the plugin off for other PNGs and refuses unknown values"):
    assertEquals(ClipIoPlugin.replay(Map.empty).get.str("enabled"), "false")
    val on = "Clip Input positive" -> "simple"
    val restored = ClipIoPlugin.replay(Map(on)).get
    assertEquals(restored.str("enabled"), "true")
    assertEquals(restored.str("positive_mode"), "Simple")
    assertEquals(restored.str("input_enabled"), "true")
    assertEquals(restored.str("output_enabled"), "false")
    for bad <- Seq("Clip Input negative" -> "python", "Clip Output format" -> "npy", "Clip Output options" -> "transpose, fly") do
      assertEquals(ClipIoPlugin.replay(Map(on, bad)), None, bad.toString)
    val output = ClipIoPlugin.replay(Map("Clip Output" -> "x")).get
    assertEquals(output.str("input_enabled"), "false")
    assertEquals(output.str("output_enabled"), "true")
