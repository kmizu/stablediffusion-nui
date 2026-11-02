package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class AestheticGradientsSuite extends munit.FunSuite:
  private val plugin = new AestheticGradientsPlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true", "embedding" -> "sunset") ++ extra)

  test("SDXL weight guidance is visible beside weight without adding an input or changing values"):
    val i = plugin.controls.indexWhere(_.key == "weight")
    val note = plugin.controls(i + 1)
    assert(note.isInstanceOf[sdfx.core.plugins.api.Control.Note])
    assert(note.label.contains("SDXL") && note.label.contains("0.3") && note.label.contains("0.5"))
    assert(!note.hasValue && !Values.defaults(plugin.controls).map.contains(note.key))
    for weight <- Seq("0", "0.3", "0.9") do
      val p = plugin.process(GenerationParams(), enabled("weight" -> weight))
      assertEquals(p.hooks(plugin.id)("weight").num, weight.toDouble)
      assertEquals(AestheticGradientsPlugin.replay(Map("Aesthetic embedding" -> "sunset", "Aesthetic weight" -> weight)).get.double("weight"), weight.toDouble)

  test("defaults write the extension's infotext keys and the engine hook"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    val params = plugin.process(GenerationParams(), enabled())
    val hook = params.hooks(plugin.id)
    assertEquals(hook("embedding").str, "sunset")
    assertEquals(hook("weight").num, 0.9)
    assertEquals(hook("steps").num, 20.0)
    assertEquals(hook("lr").num, 0.0001)
    assertEquals(hook("slerp").bool, false)
    assert(!hook.obj.contains("enabled"))
    assertEquals(params.extra, Map("Aesthetic LR" -> "0.0001", "Aesthetic weight" -> "0.9", "Aesthetic steps" -> "20",
      "Aesthetic embedding" -> "sunset", "Aesthetic slerp" -> "False"))
    assertEquals(plugin.requiresEngineFeatures, Seq("aesthetic_gradients"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)

  test("slerp text and the negative flag are written only when used; paths show as the file name"):
    val params = plugin.process(GenerationParams(), enabled("embedding" -> "/data/aesthetic_embeddings/Style One.pt",
      "slerp" -> "true", "text" -> "masterpiece", "slerp_angle" -> "0.3", "negative" -> "true"))
    assertEquals(params.extra("Aesthetic embedding"), "Style One")
    assertEquals(params.extra("Aesthetic slerp"), "True")
    assertEquals(params.extra("Aesthetic text"), "masterpiece")
    assertEquals(params.extra("Aesthetic slerp angle"), "0.3")
    assertEquals(params.extra("Aesthetic text negative"), "True")
    assertEquals(AestheticGradientsPlugin.embeddingName("a/b/c.safetensors"), "c")
    assertEquals(AestheticGradientsPlugin.embeddingName("plain"), "plain")

  test("PNG infotext round trip restores every control"):
    val values = enabled("weight" -> "0.55", "steps" -> "7", "lr" -> "0.0005", "slerp" -> "true", "text" -> "best quality",
      "slerp_angle" -> "0.25", "negative" -> "true")
    val params = plugin.process(GenerationParams(prompt = "castle"), values)
    val fields = Infotext.parse(Infotext.write(params, ImageFacts(seed = 1))).fields
    val restored = AestheticGradientsPlugin.replay(fields).get
    for key <- Seq("enabled", "embedding", "steps", "slerp", "text", "negative") do
      assertEquals(restored.str(key), values.str(key), key)
    assertEquals(restored.double("weight"), 0.55)
    assertEquals(restored.double("lr"), 0.0005)
    assertEquals(restored.double("slerp_angle"), 0.25)
    assertEquals(plugin.process(GenerationParams(prompt = "castle"), restored).hooks(plugin.id), params.hooks(plugin.id))

  test("fingerprinted metadata pins the actual embedding request without recording a path"):
    val hash = "a" * 64
    val restored = AestheticGradientsPlugin.replay(Map("Aesthetic embedding" -> "style", "Aesthetic embedding SHA256" -> hash)).get
    assertEquals(restored.str("embedding_sha256"), hash)
    val params = plugin.process(GenerationParams(), restored)
    assertEquals(params.hooks(plugin.id)("embedding_sha256").str, hash)
    assert(!params.extra.contains("Aesthetic embedding SHA256")) // worker supplies the hash of the consumed bytes
    assertEquals(AestheticGradientsPlugin.replay(Map("Aesthetic embedding" -> "style", "Aesthetic embedding SHA256" -> "bad")), None)

  test("replay disables the plugin for other PNGs and rejects malformed values"):
    assertEquals(AestheticGradientsPlugin.replay(Map.empty).get.str("enabled"), "false")
    val on = "Aesthetic embedding" -> "sunset"
    assertEquals(AestheticGradientsPlugin.replay(Map(on)).get.str("enabled"), "true")
    for bad <- Seq("Aesthetic weight" -> "1.5", "Aesthetic weight" -> "x", "Aesthetic steps" -> "2.5", "Aesthetic steps" -> "-1",
        "Aesthetic steps" -> "500", "Aesthetic LR" -> "-1", "Aesthetic slerp" -> "maybe", "Aesthetic slerp angle" -> "2",
        "Aesthetic text negative" -> "1") do
      assertEquals(AestheticGradientsPlugin.replay(Map(on, bad)), None, bad.toString)

  test("XYZ axes override single fields"):
    assertEquals(plugin.xyzAxes.map(_.name), Seq("Aesthetic weight", "Aesthetic steps", "Aesthetic learning rate",
      "Aesthetic slerp angle", "Aesthetic embedding", "Aesthetic text"))
    def axis(name: String) = plugin.xyzAxes.find(_.name == name).get
    val weight = plugin.process(axis("Aesthetic weight")(GenerationParams(), "0.3"), enabled())
    assertEquals(weight.hooks(plugin.id)("weight").num, 0.3)
    assertEquals(weight.extra("Aesthetic weight"), "0.3")
    val embedding = plugin.process(axis("Aesthetic embedding")(GenerationParams(), "other"), enabled())
    assertEquals(embedding.extra("Aesthetic embedding"), "other")
    val steps = plugin.process(axis("Aesthetic steps")(GenerationParams(), "5"), enabled())
    assertEquals(steps.extra("Aesthetic steps"), "5")
