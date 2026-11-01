package sdfx.plugins

import java.nio.file.Files
import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class LlulSuite extends munit.FunSuite:
  private val plugin = new LlulPlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("opt-in controls follow the source defaults and write the source keys"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    val params = plugin.process(GenerationParams(width = 512, height = 768, steps = 24), enabled())
    val hook = params.hooks(plugin.id)
    assertEquals(hook("weight").num, 0.15)
    assertEquals(hook("multiply").num, 1.0)
    assertEquals(hook("layers").str, "OUT")
    assertEquals(hook("apply_to").arr.map(_.str).toSeq, Seq("out"))
    assertEquals(hook("start_steps").num, 5.0)
    assertEquals(hook("max_steps").num, 0.0)
    assertEquals(hook("up").str, "bilinear")
    assert(hook("x") == ujson.Null && hook("y") == ujson.Null && !hook.obj.contains("enabled") && !hook.obj.contains("active"))
    // like the source, the centred position and the pass' step count are written out
    val expected = Seq("LLuL Enabled" -> "True", "LLuL Multiply" -> "2", "LLuL Weight" -> "0.15",
      "LLuL Layers" -> "['OUT']", "LLuL Apply to" -> "['out']", "LLuL Start steps" -> "5", "LLuL Max steps" -> "24",
      "LLuL Upscaler" -> "bilinear", "LLuL Downscaler" -> "bilinear", "LLuL Interpolation" -> "lerp",
      "LLuL x" -> "128", "LLuL y" -> "192")
    assertEquals(params.extra, expected.toMap)
    assertEquals(LlulPlugin.InfotextKeys, expected.map(_._1))
    assertEquals(plugin.requiresEngineFeatures, Seq("llul"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)

  test("advanced settings apply only once the box is ticked"):
    val values = Seq("layers" -> "IN01, M00", "apply_out" -> "false", "apply_resblock" -> "true", "apply_sattn" -> "true",
      "start_steps" -> "3", "max_steps" -> "9", "up" -> "Nearest", "down" -> "Pooling Max", "down_aa" -> "false",
      "intp" -> "SLerp", "multiply" -> "3", "weight" -> "-0.5", "x" -> "16", "y" -> "32")
    val hidden = plugin.process(GenerationParams(), enabled(values*))
    assertEquals(hidden.hooks(plugin.id)("layers").str, "OUT")
    assertEquals(hidden.hooks(plugin.id)("intp").str, "lerp")
    assertEquals(hidden.extra("LLuL Multiply"), "8") // the basic settings always apply
    assertEquals(hidden.extra("LLuL x"), "16")
    val shown = plugin.process(GenerationParams(), enabled((values :+ ("understand" -> "true"))*))
    val hook = shown.hooks(plugin.id)
    assertEquals(hook("apply_to").arr.map(_.str).toSeq, Seq("resblock", "s. attn."))
    assertEquals(hook("intp").str, "slerp")
    assertEquals(shown.extra("LLuL Layers"), "['IN01', 'M00']")
    assertEquals(shown.extra("LLuL Apply to"), "['resblock', 's. attn.']")
    assertEquals(shown.extra("LLuL Upscaler"), "nearest-exact")
    assertEquals(shown.extra("LLuL Downscaler"), "pooling max")
    assertEquals(shown.extra("LLuL Max steps"), "9")
    assertEquals(shown.extra("LLuL Interpolation"), "slerp")

  test("a mask is used only when ticked and present, and forces Lerp"):
    val file = Files.createTempFile("llul-mask", ".png")
    val on = plugin.process(GenerationParams(), enabled("understand" -> "true", "intp" -> "SLerp", "use_mask" -> "true",
      "mask" -> file.toString))
    assertEquals(on.hooks(plugin.id)("mask").str, file.toString)
    assertEquals(on.extra("LLuL Interpolation"), "lerp")
    val missing = plugin.process(GenerationParams(), enabled("use_mask" -> "true", "mask" -> "nope.png"))
    assertEquals(missing.hooks(plugin.id)("mask").str, "")
    val unticked = plugin.process(GenerationParams(), enabled("mask" -> file.toString))
    assertEquals(unticked.hooks(plugin.id)("mask").str, "")

  test("PNG infotext round trip restores every control"):
    val values = enabled("understand" -> "true", "weight" -> "0.4", "multiply" -> "2", "x" -> "10", "y" -> "20",
      "layers" -> "IN01, OUT", "apply_out" -> "false", "apply_transformer" -> "true", "apply_xattn" -> "true",
      "start_steps" -> "2", "max_steps" -> "7", "up" -> "Bicubic", "up_aa" -> "true", "down" -> "Area",
      "intp" -> "SLerp")
    val params = plugin.process(GenerationParams(prompt = "castle", steps = 20), values)
    val written = Infotext.write(params, ImageFacts(seed = 1))
    val fields = Infotext.parse(written).fields
    val restored = LlulPlugin.replay(fields).get
    for key <- Seq("enabled", "understand", "multiply", "layers", "apply_out", "apply_transformer", "apply_xattn",
        "apply_resblock", "apply_sattn", "start_steps", "max_steps", "up", "up_aa", "down", "down_aa", "intp", "x", "y") do
      assertEquals(restored.str(key), values.str(key), key)
    assertEquals(restored.double("weight"), 0.4)
    assertEquals(plugin.process(GenerationParams(prompt = "castle", steps = 20), restored).hooks(plugin.id),
      params.hooks(plugin.id))

  test("upstream PNG text restores, Max steps equal to Steps means the pass' steps"):
    val text = "a cat\nSteps: 20, Seed: 1, Size: 512x512, LLuL Enabled: True, LLuL Multiply: 4, LLuL Weight: 0.15, " +
      "LLuL Layers: \"['OUT']\", LLuL Apply to: \"['out']\", LLuL Start steps: 5, LLuL Max steps: 20, " +
      "LLuL Upscaler: nearest-exact, LLuL Downscaler: bilinear-aa, LLuL Interpolation: lerp, LLuL x: 192, LLuL y: 192"
    val restored = LlulPlugin.replay(Infotext.parse(text).fields).get
    assertEquals(restored.int("multiply"), 2)
    assertEquals(restored.int("max_steps"), 0)
    assertEquals(restored.str("up"), "Nearest")
    assertEquals(restored.str("down"), "Bilinear")
    assertEquals(restored.bool("down_aa"), true)
    assertEquals(restored.str("layers"), "OUT")
    val hook = plugin.process(GenerationParams(), restored).hooks(plugin.id)
    assertEquals(hook("x").num, 192.0)
    assertEquals(hook("apply_to").arr.map(_.str).toSeq, Seq("out"))

  test("replay disables the plugin for other PNGs and rejects malformed values"):
    assertEquals(LlulPlugin.replay(Map.empty).get.str("enabled"), "false")
    assertEquals(LlulPlugin.replay(Map("LLuL Enabled" -> "False")).get.str("enabled"), "false")
    val on = "LLuL Enabled" -> "True"
    assertEquals(LlulPlugin.replay(Map(on)).get.str("enabled"), "true")
    for bad <- Seq("LLuL Multiply" -> "3", "LLuL Multiply" -> "0", "LLuL Weight" -> "x", "LLuL Layers" -> "OUT",
        "LLuL Apply to" -> "['bogus']", "LLuL Start steps" -> "0", "LLuL Max steps" -> "0", "LLuL Upscaler" -> "area",
        "LLuL Downscaler" -> "lanczos", "LLuL Interpolation" -> "cubic", "LLuL x" -> "left") do
      assertEquals(LlulPlugin.replay(Map(on, bad)), None, bad.toString)

  test("source XYZ axes override fields and the Enabled axis switches the plugin off"):
    assertEquals(plugin.xyzAxes.map(_.name), Seq("LLuL Enabled", "LLuL Multiply", "LLuL Weight", "LLuL Layers",
      "LLuL Apply to", "LLuL Start steps", "LLuL Max steps", "LLuL Upscaler", "LLuL Upscaler AA", "LLuL Downscaler",
      "LLuL Downscaler AA", "LLuL Interpolation method"))
    def axis(name: String) = plugin.xyzAxes.find(_.name == name).get
    val off = plugin.process(axis("LLuL Enabled")(GenerationParams(), "false"), enabled())
    assert(!off.hooks.contains(plugin.id) && !off.hooks.contains(plugin.id + "_xyz") && !off.extra.contains("LLuL Enabled"))
    val weight = plugin.process(axis("LLuL Weight")(GenerationParams(), "0.3"), enabled())
    assertEquals(weight.hooks(plugin.id)("weight").num, 0.3)
    assertEquals(weight.extra("LLuL Weight"), "0.3")
    val multiply = plugin.process(axis("LLuL Multiply")(GenerationParams(), "3"), enabled())
    assertEquals(multiply.extra("LLuL Multiply"), "8")
    val apply = plugin.process(axis("LLuL Apply to")(GenerationParams(), "Resblock"), enabled())
    assertEquals(apply.hooks(plugin.id)("apply_to").arr.map(_.str).toSeq, Seq("resblock"))
    assertEquals(apply.extra("LLuL Apply to"), "['resblock']")
    val up = plugin.process(axis("LLuL Upscaler")(GenerationParams(), "Bicubic"), enabled())
    assertEquals(up.extra("LLuL Upscaler"), "bicubic")
    val aa = plugin.process(axis("LLuL Downscaler AA")(GenerationParams(), "TRUE"), enabled())
    assertEquals(aa.extra("LLuL Downscaler"), "bilinear-aa")
