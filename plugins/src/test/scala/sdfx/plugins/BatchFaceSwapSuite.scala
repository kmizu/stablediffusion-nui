package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class BatchFaceSwapSuite extends munit.FunSuite:
  private val plugin = new BatchFaceSwapPlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("defaults write the engine hook and one compact infotext key"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    val params = plugin.process(GenerationParams(), enabled())
    val hook = params.hooks(plugin.id)
    assertEquals(hook("detector").str, "face_yolov8n.pt")
    assertEquals(hook("confidence").num, 0.3)
    assertEquals(hook("mask_shape").str, "ellipse")
    assertEquals(hook("mask_size").num, 1.2)
    assertEquals(hook("denoising_strength").num, 0.5)
    assertEquals(hook("resolution").num, 0.0)
    assertEquals(hook("sampler").str, "")
    assert(!hook.obj.contains("enabled") && !hook.obj.contains("custom_detector"))
    assertEquals(params.extra, Map("Batch Face Swap" -> """{"detector":"face_yolov8n.pt"}"""))
    assertEquals(plugin.requiresEngineFeatures, Seq("batch_face_swap"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)

  test("a custom detector wins over the list and the sampler none means the main sampler"):
    val params = plugin.process(GenerationParams(), enabled("custom_detector" -> " /m/face.pt ", "sampler" -> "None"))
    assertEquals(params.hooks(plugin.id)("detector").str, "/m/face.pt")
    assertEquals(params.hooks(plugin.id)("sampler").str, "")

  test("infotext lists the detector and the changed settings only"):
    val params = plugin.process(GenerationParams(), enabled("denoising_strength" -> "0.65", "max_faces" -> "2",
      "prompt" -> "[PROMPT], smile", "color_correction" -> "false"))
    val written = ujson.read(params.extra("Batch Face Swap")).obj
    assertEquals(written.keySet.toSet, Set("detector", "denoising_strength", "max_faces", "prompt", "color_correction"))

  test("PNG infotext round trip restores every control"):
    val values = enabled("detector" -> "face_yolov8m.pt", "confidence" -> "0.45", "max_faces" -> "3",
      "mask_shape" -> "box", "mask_size" -> "1.5", "mask_blur" -> "12", "denoising_strength" -> "0.7", "padding" -> "48",
      "resolution" -> "768", "prompt" -> "[PROMPT] [SEP] wink", "negative_prompt" -> "blurry", "steps" -> "14",
      "cfg_scale" -> "4.5", "sampler" -> "Euler a", "color_correction" -> "false")
    val params = plugin.process(GenerationParams(prompt = "girl"), values)
    val fields = Infotext.parse(Infotext.write(params, ImageFacts(seed = 1))).fields
    val restored = BatchFaceSwapPlugin.replay(fields).get
    for (key, _) <- values.map if key != "enabled" do
      val expected = values.str(key)
      val actual = restored.str(key)
      if Seq("confidence", "mask_size", "denoising_strength", "cfg_scale").contains(key) then
        assertEquals(restored.double(key), values.double(key), key)
      else assertEquals(actual, expected, key)
    assertEquals(restored.str("enabled"), "true")
    assertEquals(plugin.process(GenerationParams(prompt = "girl"), restored).hooks(plugin.id), params.hooks(plugin.id))

  test("a detector outside the list restores into the custom box"):
    val restored = BatchFaceSwapPlugin.replay(Map("Batch Face Swap" -> """{"detector":"/m/anime_face.pt"}""")).get
    assertEquals(restored.str("custom_detector"), "/m/anime_face.pt")
    assertEquals(plugin.process(GenerationParams(), restored).hooks(plugin.id)("detector").str, "/m/anime_face.pt")

  test("replay disables the plugin for other PNGs and rejects what the engine would reject"):
    assertEquals(BatchFaceSwapPlugin.replay(Map.empty).get.str("enabled"), "false")
    assertEquals(BatchFaceSwapPlugin.replay(Map("Batch Face Swap" -> "{}")).get.str("enabled"), "true")
    for bad <- Seq("""{"confidence":1.5}""", """{"max_faces":1.5}""", """{"mask_shape":"star"}""", """{"mask_size":0}""",
        """{"resolution":32}""", """{"steps":500}""", """{"bogus":1}""", """{"detector":" "}""", """{"detector":"None"}""",
        """{"color_correction":"yes"}""", "not json", "[1]") do
      assertEquals(BatchFaceSwapPlugin.replay(Map("Batch Face Swap" -> bad)), None, bad)

  test("XYZ axes override single fields"):
    assertEquals(plugin.xyzAxes.map(_.name), Seq("Batch Face Swap denoising strength", "Batch Face Swap confidence",
      "Batch Face Swap mask size", "Batch Face Swap mask blur", "Batch Face Swap prompt"))
    def axis(name: String) = plugin.xyzAxes.find(_.name == name).get
    val denoise = plugin.process(axis("Batch Face Swap denoising strength")(GenerationParams(), "0.2"), enabled())
    assertEquals(denoise.hooks(plugin.id)("denoising_strength").num, 0.2)
    assert(denoise.extra("Batch Face Swap").contains(""""denoising_strength":0.2"""))
    val prompt = plugin.process(axis("Batch Face Swap prompt")(GenerationParams(), "smile"), enabled())
    assertEquals(prompt.hooks(plugin.id)("prompt").str, "smile")
