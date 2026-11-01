package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import java.awt.image.BufferedImage

/** Shared settings of w-e-w/sd-webui-nudenet-nsfw-censor (pinned 1fb21cf, AGPL-3.0); the engine side is
  * python/sdfx/nsfw_checker.py.
  */
object NsfwCheckerPlugin:
  /** NudeNet labels in model output order (the engine's threshold / expansion arrays use this order). */
  val Labels: Seq[String] = Seq(
    "Female_genitalia_covered", "Face_female", "Buttocks_exposed", "Female_breast_exposed", "Female_genitalia_exposed",
    "Male_breast_exposed", "Anus_exposed", "Feet_exposed", "Belly_covered", "Feet_covered", "Armpits_covered",
    "Armpits_exposed", "Face_male", "Belly_exposed", "Male_genitalia_exposed", "Anus_covered", "Female_breast_covered",
    "Buttocks_covered")
  def friendly(label: String): String = label.replace('_', ' ')
  /** Upstream's default "Categories to be censored"; every other category starts at threshold 1 (off). */
  val DefaultSelected: Set[String] = Set("Anus_exposed", "Female_breast_exposed", "Female_genitalia_exposed", "Male_genitalia_exposed")
  def defaultThreshold(label: String): Double = if DefaultSelected(label) then 0.25 else 1.0

  val Filters: Seq[String] = Seq("Variable blur", "Gaussian Blur", "Pixelate", "Fill color", "No censor", "Disable")
  val MaskShapes: Seq[String] = Seq("Ellipse", "Rectangle", "Rounded rectangle", "Entire image")
  val EntireImageAlways = "Entire image (always)"
  val InfotextKey = "NudeNet NSFW Censor"
  val ExtrasInfotextKey = "NudeNet NSFW Censor Extras"

  def thresholdKey(label: String) = s"threshold_$label"
  def horizontalKey(label: String) = s"horizontal_$label"
  def verticalKey(label: String) = s"vertical_$label"

  /** The three per-category values in the UI's alphabetical order (as upstream's settings page). */
  def categoryControls: Seq[Control] =
    val sorted = Labels.sortBy(friendly)
    Seq(Section("categories", "Categories: confidence thresholds (1 disables a category)", expanded = false)) ++
      sorted.map(l => Slider(thresholdKey(l), s"${friendly(l)} - threshold", 0, 1, 0.01, defaultThreshold(l))) ++
      Seq(Section("expansion", "Categories: expansion multipliers (non-negative)", expanded = false)) ++
      sorted.flatMap(l => Seq(
        Text(horizontalKey(l), s"${friendly(l)} - horizontal multiplier", "1", placeholder = "1"),
        Text(verticalKey(l), s"${friendly(l)} - vertical multiplier", "1", placeholder = "1")))

  private[plugins] def number(v: Values, key: String, default: Double, nonNegative: Boolean = false): Double =
    val raw = v.str(key, Values.fmt(default)).trim
    val n = if raw.isEmpty then default else raw.toDoubleOption.getOrElse(
      throw new IllegalArgumentException(s"NudeNet NSFW Censor: $key must be a number (got '$raw')"))
    if !n.isFinite || (nonNegative && n < 0) then
      throw new IllegalArgumentException(s"NudeNet NSFW Censor: $key must be a finite${if nonNegative then " non-negative" else ""} number (got '$raw')")
    n

  /** Threshold and expansion arrays in model order. */
  def categoryFields(v: Values): Seq[(String, ujson.Value)] = Seq(
    "thresholds" -> ujson.Arr.from(Labels.map(l => ujson.Num(number(v, thresholdKey(l), defaultThreshold(l))))),
    "expand_horizontal" -> ujson.Arr.from(Labels.map(l => ujson.Num(number(v, horizontalKey(l), 1, nonNegative = true)))),
    "expand_vertical" -> ujson.Arr.from(Labels.map(l => ujson.Num(number(v, verticalKey(l), 1, nonNegative = true)))))

  private val Scalars: Seq[(String, ujson.Value)] = Seq(
    "gen_filter_type" -> ujson.Str("Gaussian Blur"), "live_preview_filter_type" -> ujson.Str("Gaussian Blur"),
    "save_before_censor" -> ujson.Bool(true), "save_mask" -> ujson.Bool(false), "mask_shape" -> ujson.Str("Ellipse"),
    "rectangle_round_radius" -> ujson.Num(0.5), "blur_radius" -> ujson.Num(10), "mask_blend_radius" -> ujson.Num(0),
    "mask_blend_radius_variable_blur" -> ujson.Num(10), "blur_strength_curve" -> ujson.Num(3),
    "pixelation_factor" -> ujson.Num(5), "fill_color" -> ujson.Str("#000000"), "nms_threshold" -> ujson.Num(0.5),
    "verbose_detection" -> ujson.Bool(false))
  private val Defaults: Map[String, ujson.Value] = Scalars.toMap ++ Seq(
    "thresholds" -> ujson.Arr.from(Labels.map(l => ujson.Num(defaultThreshold(l)))),
    "expand_horizontal" -> ujson.Arr.from(Labels.map(_ => ujson.Num(1))),
    "expand_vertical" -> ujson.Arr.from(Labels.map(_ => ujson.Num(1))))

  /** Concise infotext: the generation filter plus every setting that differs from upstream's defaults. */
  def encode(o: ujson.Obj): String =
    val keep = o.value.toSeq.filter((k, value) => Defaults.contains(k) && (k == "gen_filter_type" || !Defaults.get(k).contains(value)))
    ujson.Obj.from(keep).render()

  /** PNG / MCP restoration. Images without the key leave the current setting alone (upstream keeps it in global
    * settings, not per image); a malformed value restores nothing. */
  def replay(fields: Map[String, String]): Option[Values] =
    fields.get(InfotextKey).flatMap { raw =>
      scala.util.Try(ujson.read(raw)).toOption.collect { case o: ujson.Obj if raw.length <= 8192 => o }.flatMap { o =>
        def str(k: String, options: Seq[String]): Option[Option[String]] = o.value.get(k) match
          case None => Some(None)
          case Some(ujson.Str(s)) if options.isEmpty || options.contains(s) => Some(Some(s))
          case _ => None
        def num(k: String, low: Double, high: Double): Option[Option[String]] = o.value.get(k) match
          case None => Some(None)
          case Some(ujson.Num(n)) if n.isFinite && n >= low && n <= high => Some(Some(Values.fmt(n)))
          case _ => None
        def bool(k: String): Option[Option[String]] = o.value.get(k) match
          case None => Some(None)
          case Some(ujson.Bool(b)) => Some(Some(b.toString))
          case _ => None
        def arr(k: String, low: Double, high: Double): Option[Seq[String]] = o.value.get(k) match
          case None => Some(Nil)
          case Some(ujson.Arr(a)) if a.size == Labels.size && a.forall {
            case ujson.Num(n) => n.isFinite && n >= low && n <= high
            case _ => false
          } => Some(a.toSeq.map(x => Values.fmt(x.num)))
          case _ => None
        val inf = Double.PositiveInfinity
        val scalars = Seq(
          "gen_filter_type" -> str("gen_filter_type", Filters), "live_preview_filter_type" -> str("live_preview_filter_type", Filters),
          "save_before_censor" -> bool("save_before_censor"), "save_mask" -> bool("save_mask"),
          "mask_shape" -> str("mask_shape", MaskShapes), "rectangle_round_radius" -> num("rectangle_round_radius", -inf, inf),
          "blur_radius" -> num("blur_radius", 0, 100), "mask_blend_radius" -> num("mask_blend_radius", 0, 100),
          "mask_blend_radius_variable_blur" -> num("mask_blend_radius_variable_blur", 0, 100),
          "blur_strength_curve" -> num("blur_strength_curve", 0, 6), "pixelation_factor" -> num("pixelation_factor", 1, 10),
          "fill_color" -> str("fill_color", Nil).filter(_.forall(_.matches("#[0-9A-Fa-f]{6}"))),
          "nms_threshold" -> num("nms_threshold", 0, 1), "verbose_detection" -> bool("verbose_detection"))
        val arrays = Seq(("thresholds", thresholdKey, 0.0, 1.0), ("expand_horizontal", horizontalKey, 0.0, inf),
          ("expand_vertical", verticalKey, 0.0, inf)).map((k, key, low, high) => (key, arr(k, low, high)))
        if scalars.exists(_._2.isEmpty) || arrays.exists(_._2.isEmpty) then None
        else
          val restored = scalars.flatMap((k, v) => v.get.map(k -> _)) ++
            arrays.flatMap((key, values) => Labels.zip(values.get).map((l, value) => key(l) -> value))
          val defaults = Values.defaults(new NsfwCheckerPlugin().controls).map
          Some(Values(defaults ++ restored.toMap + ("enabled" -> "true")))
      }
    }

/** NudeNet NSFW Censor for generation and live preview (w-e-w/sd-webui-nudenet-nsfw-censor). */
class NsfwCheckerPlugin extends HookPlugin:
  import NsfwCheckerPlugin.*
  def id = "nsfw_checker"
  def name = "NudeNet NSFW Censor"
  def hook = "nsfw_checker"
  override def description =
    "Detects NSFW regions with the bundled NudeNet detector (CPU) and censors them in the final images, after " +
    "ADetailer and Hires refinement, and in live previews. Detected images can also save an uncensored " +
    "-before-censor copy and the -censor-mask; choose the categories, thresholds and region expansion below."
  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Choice("gen_filter_type", "Generation censor filter", Filters, "Gaussian Blur"),
    Choice("live_preview_filter_type", "Live preview censor filter", Filters, "Gaussian Blur"),
    Check("save_before_censor", "Save a copy of the image before applying censor", true),
    Check("save_mask", "Save censor mask"),
    Choice("mask_shape", "Mask shape", MaskShapes, "Ellipse"),
    Text("rectangle_round_radius", "Rectangle round radius (0-1 ratio, >1 scaled unit, <0 pixels)", "0.5", placeholder = "0.5"),
    Slider("blur_radius", "Blur strength", 0, 100, 1, 10),
    Slider("mask_blend_radius", "Mask blend strength", 0, 100, 1, 0),
    Slider("mask_blend_radius_variable_blur", "Mask blend strength - Variable blur", 0, 100, 1, 10),
    Slider("blur_strength_curve", "Blur strength curve - Variable blur", 0, 6, 0.1, 3),
    Slider("pixelation_factor", "Pixelation factor", 1, 10, 0.1, 5),
    Text("fill_color", "Fill color", "#000000", placeholder = "#000000"),
    Slider("nms_threshold", "Non-Maximum Suppression threshold (1 disables)", 0, 1, 0.01, 0.5),
    Check("verbose_detection", "Log detection details")
  ) ++ categoryControls

  def hookFields(v: Values): ujson.Obj = ujson.Obj.from(Seq(
    "gen_filter_type" -> ujson.Str(v.str("gen_filter_type", "Gaussian Blur")),
    "live_preview_filter_type" -> ujson.Str(v.str("live_preview_filter_type", "Gaussian Blur")),
    "save_before_censor" -> ujson.Bool(v.bool("save_before_censor", true)),
    "save_mask" -> ujson.Bool(v.bool("save_mask")),
    "mask_shape" -> ujson.Str(v.str("mask_shape", "Ellipse")),
    "rectangle_round_radius" -> ujson.Num(number(v, "rectangle_round_radius", 0.5)),
    "blur_radius" -> ujson.Num(v.double("blur_radius", 10)),
    "mask_blend_radius" -> ujson.Num(v.double("mask_blend_radius", 0)),
    "mask_blend_radius_variable_blur" -> ujson.Num(v.double("mask_blend_radius_variable_blur", 10)),
    "blur_strength_curve" -> ujson.Num(v.double("blur_strength_curve", 3)),
    "pixelation_factor" -> ujson.Num(v.double("pixelation_factor", 5)),
    "fill_color" -> ujson.Str(v.str("fill_color", "#000000").trim),
    "nms_threshold" -> ujson.Num(v.double("nms_threshold", 0.5)),
    "verbose_detection" -> ujson.Bool(v.bool("verbose_detection"))) ++ categoryFields(v))

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] = Seq(InfotextKey -> encode(o))

/** NudeNet NSFW Censor for Extras: auto-detected regions and/or a custom mask, with upstream's override settings. */
class NsfwCheckerExtrasPlugin extends ExtrasPlugin:
  import NsfwCheckerPlugin.*
  def id = "nsfw_checker_extras"
  def name = "NudeNet NSFW Censor"
  override def description =
    "Censors the image (or each txt2img result) like the extension's Extras tab: NudeNet auto-detection and an " +
    "optional custom mask (white = censor) are combined. The blur radius is in pixels; the mask blend scales with the " +
    "image. No censor with Output only the mask gives just the mask."
  override def controls: Seq[Control] = Seq(
    Check("enable_nudenet", "NudeNet auto-detect", true),
    ImageFile("mask", "Custom mask (optional, white = censor)"),
    Choice("filter_type", "Censor filter", Filters.filterNot(_ == "Disable"), "Variable blur"),
    Choice("mask_shape", "Mask shape", MaskShapes :+ EntireImageAlways, "Ellipse"),
    Text("rectangle_round_radius", "Rectangle round radius (0-1 ratio, >1 scaled unit, <0 pixels)", "0.5", placeholder = "0.5"),
    Slider("blur_radius", "Blur radius", 0, 100, 1, 10),
    Slider("blur_strength_curve", "Blur strength curve (Variable blur)", 0, 6, 0.1, 3),
    Slider("pixelation_factor", "Pixelation factor", 1, 10, 0.1, 5),
    Text("fill_color", "Fill color", "#000000", placeholder = "#000000"),
    Slider("mask_blend_radius", "Mask blend radius", 0, 100, 1, 0),
    Slider("mask_blend_radius_variable_blur", "Variable blur mask blend radius", 0, 100, 1, 10),
    Slider("nms_threshold", "NMS threshold (1 disables)", 0, 1, 0.01, 1),
    Check("save_mask", "Also save the mask"),
    Check("mask_only", "Output only the mask"),
    Check("verbose_detection", "Log detection details")
  ) ++ categoryControls

  private[plugins] def toolParams(v: Values): Seq[(String, ujson.Value)] =
    val filter = v.str("filter_type", "Variable blur")
    val blend = if filter == "Variable blur" then v.double("mask_blend_radius_variable_blur", 10) else v.double("mask_blend_radius", 0)
    Seq(
      "enable_nudenet" -> ujson.Bool(v.bool("enable_nudenet", true)),
      "filter_type" -> ujson.Str(filter),
      "mask_shape" -> ujson.Str(v.str("mask_shape", "Ellipse")),
      "rectangle_round_radius" -> ujson.Num(number(v, "rectangle_round_radius", 0.5)),
      "blur_radius" -> ujson.Num(v.double("blur_radius", 10)),
      "blur_strength_curve" -> ujson.Num(v.double("blur_strength_curve", 3)),
      "pixelation_factor" -> ujson.Num(v.double("pixelation_factor", 5)),
      "fill_color" -> ujson.Str(v.str("fill_color", "#000000").trim),
      "mask_blend_radius" -> ujson.Num(blend),
      "nms_threshold" -> ujson.Num(v.double("nms_threshold", 1)),
      "verbose_detection" -> ujson.Bool(v.bool("verbose_detection")),
      "output_mask" -> ujson.Bool(v.bool("save_mask") || v.bool("mask_only"))
    ) ++ v.str("mask").trim.split("[\\r\\n]+").find(_.nonEmpty).map(m => "mask_path" -> ujson.Str(m)).toSeq ++
      categoryFields(v)

  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext): Seq[(BufferedImage, Seq[(String, String)])] =
    val r = ImageTools.run(ctx, "nsfw_checker", img, toolParams(v)*)
    try
      val censored = r.info.get("censored").contains("1")
      val custom = if v.str("mask").trim.nonEmpty then "; custom mask" else ""
      val post = Seq(ExtrasInfotextKey -> (if censored then s"${v.str("filter_type", "Variable blur")}; ${v.str("mask_shape", "Ellipse")}$custom"
        else "nothing detected"))
      if !censored then ctx.log("NudeNet NSFW Censor: nothing to censor")
      val mask = r.outputs.get("mask").map(path => (Support.readImage(ctx, path), post :+ ("Output" -> "mask")))
      if v.bool("mask_only") then mask.toSeq
      else (r.image, post) +: (if v.bool("save_mask") then mask.toSeq else Nil)
    finally Support.deleteQuietly(r.outputs.values.toSeq*)
