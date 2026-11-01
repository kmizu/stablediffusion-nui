package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ArrayBuffer

class NsfwCheckerSuite extends munit.FunSuite:
  private val plugin = new NsfwCheckerPlugin
  private val extras = new NsfwCheckerExtrasPlugin
  private def enabled(extra: (String, String)*) = Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)
  private def hook(v: Values) = plugin.process(GenerationParams(), v).hooks(plugin.id).obj

  test("opt-in AlwaysOn with upstream defaults, model-order arrays and registration"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    val o = hook(enabled())
    assertEquals(o("gen_filter_type").str, "Gaussian Blur")
    assertEquals(o("live_preview_filter_type").str, "Gaussian Blur")
    assertEquals(o("save_before_censor"), ujson.Bool(true))
    assertEquals(o("save_mask"), ujson.Bool(false))
    assertEquals(o("nms_threshold").num, 0.5)
    assertEquals(o("rectangle_round_radius").num, 0.5)
    assertEquals(o("fill_color").str, "#000000")
    val thresholds = o("thresholds").arr.map(_.num).toSeq
    assertEquals(thresholds.size, 18)
    assertEquals(thresholds.zipWithIndex.filter(_._1 < 1).map(_._2), Seq(3, 4, 6, 14))
    assert(thresholds.filter(_ < 1).forall(_ == 0.25))
    assertEquals(o("expand_horizontal").arr.map(_.num).toSeq, Seq.fill(18)(1.0))
    assertEquals(NsfwCheckerPlugin.Labels.head, "Female_genitalia_covered")
    assertEquals(NsfwCheckerPlugin.Labels(14), "Male_genitalia_exposed")
    assertEquals(plugin.requiresEngineFeatures, Seq("nsfw_checker"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)
    assertEquals(StandardPlugins.extras.count(_.id == extras.id), 1)
    assertEquals(plugin.process(GenerationParams(), enabled()).extra(NsfwCheckerPlugin.InfotextKey),
      """{"gen_filter_type":"Gaussian Blur"}""")

  test("category controls are collapsible, alphabetical and complete for both plugins"):
    for p <- Seq(plugin, extras) do
      val sections = p.controls.collect { case s: Control.Section => s }
      assertEquals(sections.map(_.expanded), Seq(false, false))
      val thresholds = p.controls.collect { case s: Control.Slider if s.key.startsWith("threshold_") => s }
      assertEquals(thresholds.size, 18)
      assertEquals(thresholds.map(_.label), thresholds.map(_.label).sorted)
      assertEquals(thresholds.find(_.key == "threshold_Face_female").map(_.defaultValue), Some(1.0))
      assertEquals(thresholds.find(_.key == "threshold_Anus_exposed").map(_.defaultValue), Some(0.25))
      val texts = p.controls.collect { case t: Control.Text if t.key.startsWith("horizontal_") || t.key.startsWith("vertical_") => t }
      assertEquals(texts.size, 36)
    assertEquals(plugin.controls.map(_.key).distinct.size, plugin.controls.size)
    assertEquals(extras.controls.map(_.key).distinct.size, extras.controls.size)

  test("custom categories map to model order and invalid expansion text is rejected"):
    val o = hook(enabled("threshold_Face_female" -> "0.3", "horizontal_Face_female" -> "1.5", "vertical_Buttocks_covered" -> "0",
      "rectangle_round_radius" -> "-8", "mask_shape" -> "Rounded rectangle"))
    assertEquals(o("thresholds")(1).num, 0.3)
    assertEquals(o("expand_horizontal")(1).num, 1.5)
    assertEquals(o("expand_vertical")(17).num, 0.0)
    assertEquals(o("rectangle_round_radius").num, -8.0)
    for bad <- Seq("horizontal_Face_male" -> "-1", "vertical_Face_male" -> "wide", "rectangle_round_radius" -> "Infinity") do
      intercept[IllegalArgumentException](hook(enabled(bad)))

  test("PNG infotext round trip restores every changed setting and enables the plugin"):
    val values = enabled("gen_filter_type" -> "Pixelate", "live_preview_filter_type" -> "Disable", "save_before_censor" -> "false",
      "save_mask" -> "true", "mask_shape" -> "Rectangle", "blur_radius" -> "25", "mask_blend_radius" -> "4",
      "pixelation_factor" -> "7.5", "fill_color" -> "#12ab9F", "nms_threshold" -> "1", "verbose_detection" -> "true",
      "threshold_Face_female" -> "0.3", "horizontal_Anus_exposed" -> "2", "vertical_Feet_covered" -> "0.5")
    val params = plugin.process(GenerationParams(prompt = "portrait"), values)
    val text = Infotext.write(params, ImageFacts(seed = 1))
    val fields = Infotext.parse(text).fields
    val restored = NsfwCheckerPlugin.replay(fields).get
    for key <- Seq("enabled", "gen_filter_type", "live_preview_filter_type", "save_before_censor", "save_mask", "mask_shape",
        "fill_color", "verbose_detection") do assertEquals(restored.str(key), values.str(key), key)
    for key <- Seq("blur_radius", "mask_blend_radius", "pixelation_factor", "nms_threshold", "threshold_Face_female",
        "horizontal_Anus_exposed", "vertical_Feet_covered", "threshold_Anus_exposed", "rectangle_round_radius") do
      assertEquals(restored.double(key), values.double(key), key)
    assertEquals(hook(restored), hook(values))

  test("replay leaves the setting alone without the key and rejects malformed values"):
    assertEquals(NsfwCheckerPlugin.replay(Map.empty), None)
    val key = NsfwCheckerPlugin.InfotextKey
    assertEquals(NsfwCheckerPlugin.replay(Map(key -> "{}")).map(_.str("gen_filter_type")), Some("Gaussian Blur"))
    for bad <- Seq("not json", """{"gen_filter_type":"Blur"}""", """{"thresholds":[0.5]}""", """{"nms_threshold":3}""",
        """{"fill_color":"red"}""", """{"expand_vertical":[-1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1]}""", "[]") do
      assertEquals(NsfwCheckerPlugin.replay(Map(key -> bad)), None, bad)

  test("Extras parameters follow upstream override semantics, custom masks and mask outputs"):
    val defaults = Values.defaults(extras.controls)
    val p = extras.toolParams(defaults).toMap
    assertEquals(p("filter_type").str, "Variable blur")
    assertEquals(p("mask_blend_radius").num, 10.0)
    assertEquals(p("nms_threshold").num, 1.0)
    assertEquals(p("output_mask"), ujson.Bool(false))
    assert(!p.contains("mask_path"))
    assert(!extras.controls.collect { case c: Control.Choice if c.key == "filter_type" => c.options }.head.contains("Disable"))
    assert(extras.controls.collect { case c: Control.Choice if c.key == "mask_shape" => c.options }.head.contains("Entire image (always)"))
    val q = extras.toolParams(Values(defaults.map ++ Map("filter_type" -> "Fill color", "mask_blend_radius" -> "3",
      "mask" -> "C:/masks/m.png", "mask_only" -> "true"))).toMap
    assertEquals(q("mask_blend_radius").num, 3.0)
    assertEquals(q("mask_path").str, "C:/masks/m.png")
    assertEquals(q("output_mask"), ujson.Bool(true))

  private def toolContext(censored: Boolean, files: ArrayBuffer[Path], calls: ArrayBuffer[ujson.Obj]): FakeContext =
    new FakeContext():
      override def engineCall(kind: String, params: ujson.Obj): ujson.Value =
        assertEquals(kind, "image_tool")
        assertEquals(params("tool").str, "nsfw_checker")
        calls += params
        val main = Paths.get(initImage(10, 10))
        val mask = Paths.get(initImage(10, 10))
        files ++= Seq(Paths.get(params("image_path").str), main, mask)
        val outputs = if censored && params("output_mask").bool then ujson.Obj("mask" -> mask.toString) else ujson.Obj()
        if !outputs.value.contains("mask") then Files.deleteIfExists(mask)
        ujson.Obj("path" -> main.toString, "outputs" -> outputs, "info" -> ujson.Obj("censored" -> (if censored then 1 else 0)))

  test("Extras transform returns the censored image, optional mask and cleans every temporary file"):
    val files = ArrayBuffer[Path](); val calls = ArrayBuffer[ujson.Obj]()
    val image = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val base = Values.defaults(extras.controls).map
    val both = extras.transform(image, GenerationParams(), Values(base + ("save_mask" -> "true")), toolContext(true, files, calls))
    assertEquals(both.size, 2)
    assertEquals(both.head._2, Seq(NsfwCheckerPlugin.ExtrasInfotextKey -> "Variable blur; Ellipse"))
    assertEquals(both(1)._2.last, "Output" -> "mask")
    val only = extras.transform(image, GenerationParams(), Values(base + ("mask_only" -> "true")), toolContext(true, files, calls))
    assertEquals(only.map(_._2.last), Seq("Output" -> "mask"))
    val ctx = toolContext(false, files, calls)
    val none = extras.transform(image, GenerationParams(), Values(base), ctx)
    assertEquals(none.map(_._2), Seq(Seq(NsfwCheckerPlugin.ExtrasInfotextKey -> "nothing detected")))
    assert(ctx.logs.exists(_.contains("nothing to censor")))
    assert(files.forall(f => !Files.exists(f)), s"leaked: ${files.filter(Files.exists(_))}")
