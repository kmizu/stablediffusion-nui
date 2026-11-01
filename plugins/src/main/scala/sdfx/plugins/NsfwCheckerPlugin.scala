package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import java.awt.image.BufferedImage

/** Shared settings of the `nsfw_checker` target: w-e-w/sd-webui-nudenet-nsfw-censor (index 238, pinned 1fb21cf,
  * AGPL-3.0), and, re-implemented from behaviour only (neither repository has a licence),
  * AUTOMATIC1111/stable-diffusion-webui-nsfw-censor (index 83, SD safety checker) and aria1th/sd-webui-nsfw-filter
  * (index 231, NSFWJS filter). The engine side is python/sdfx/nsfw_checker.py and python/sdfx/nsfw_safety.py.
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
  /** Index 83: written when the SD safety checker is on (upstream writes nothing). */
  val SafetyInfotextKey = "SD safety checker"
  val SafetyFinal = "final images"
  val SafetyFinalAndPreviews = "final images and live previews"
  val SafetyExtrasInfotextKey = "SD safety checker Extras"
  /** Index 231: written when the NSFWJS filter is on, with its safety level (upstream writes nothing). */
  val NsfwjsInfotextKey = "NSFWJS filter"
  val NsfwjsExtrasInfotextKey = "NSFWJS filter Extras"
  /** Top NSFWJS classes treated as NSFW: safe = hentai, porn, sexy; questionable = hentai, porn; explicit = none. */
  val SafetyLevels: Seq[String] = Seq("safe", "questionable", "explicit")
  val InfotextKeys: Seq[String] = Seq(InfotextKey, SafetyInfotextKey, NsfwjsInfotextKey)
  /** Extras checker labels and their engine names. */
  val Checkers: Seq[(String, String)] = Seq("NudeNet" -> "nudenet", "NSFWJS" -> "nsfwjs", "SD safety checker" -> "safety_checker")

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

  private[plugins] def safetyLevel(v: Values): String =
    val level = v.str("nsfwjs_safety_level", "safe").trim
    if !SafetyLevels.contains(level) then
      throw new IllegalArgumentException(s"NSFWJS filter: safety level must be one of ${SafetyLevels.mkString(", ")} (got '$level')")
    level

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

  /** PNG / MCP restoration. Images without any of the three keys leave the current setting alone (upstream keeps it
    * in global settings, not per image); otherwise each checker is on exactly when its key is present (images from
    * before indices 83 / 231 carry only the NudeNet key). A malformed value restores nothing. */
  def replay(fields: Map[String, String]): Option[Values] =
    if !InfotextKeys.exists(fields.contains) then None
    else
      val safety: Option[Map[String, String]] = fields.get(SafetyInfotextKey) match
        case None => Some(Map("safety_checker" -> "false", "safety_checker_live_preview" -> "false"))
        case Some(SafetyFinal) => Some(Map("safety_checker" -> "true", "safety_checker_live_preview" -> "false"))
        case Some(SafetyFinalAndPreviews) => Some(Map("safety_checker" -> "true", "safety_checker_live_preview" -> "true"))
        case _ => None
      val nsfwjs: Option[Map[String, String]] = fields.get(NsfwjsInfotextKey) match
        case None => Some(Map("nsfwjs" -> "false"))
        case Some(level) if SafetyLevels.contains(level) => Some(Map("nsfwjs" -> "true", "nsfwjs_safety_level" -> level))
        case _ => None
      val nudenet: Option[Values] =
        if fields.contains(InfotextKey) then replayNudeNet(fields)
        else Some(Values(Values.defaults(new NsfwCheckerPlugin().controls).map ++ Map("enabled" -> "true", "nudenet" -> "false")))
      for n <- nudenet; s <- safety; j <- nsfwjs yield Values(n.map ++ s ++ j)

  private def replayNudeNet(fields: Map[String, String]): Option[Values] =
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
          Some(Values(defaults ++ restored.toMap + ("enabled" -> "true") + ("nudenet" -> "true")))
      }
    }

/** NSFW checks for generation and live preview: NudeNet region censoring (w-e-w/sd-webui-nudenet-nsfw-censor), the
  * SD safety checker (AUTOMATIC1111/stable-diffusion-webui-nsfw-censor) and the NSFWJS filter
  * (aria1th/sd-webui-nsfw-filter). */
class NsfwCheckerPlugin extends HookPlugin:
  import NsfwCheckerPlugin.*
  def id = "nsfw_checker"
  def name = "NSFW Checker"
  def hook = "nsfw_checker"
  override def description =
    "Checks every final image after ADetailer and Hires refinement, just before it is saved. NudeNet (bundled, CPU) " +
    "censors detected regions, also in live previews, and can save an uncensored -before-censor copy and the " +
    "-censor-mask. The SD safety checker (CompVis/stable-diffusion-safety-checker, 1.2 GB, downloaded and hash-checked " +
    "on first use; its model card states no licence) replaces flagged images with black; it does not check live " +
    "previews unless asked. The NSFWJS filter (deepghs/imgutils-models nsfwjs.onnx, MIT, 10 MB, downloaded on first " +
    "use) blurs images whose top class is NSFW at the safety level, in previews and in every saved file. The checks " +
    "can miss content; they are not a guarantee."
  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Check("nudenet", "NudeNet: censor detected regions (settings below)", true),
    Check("safety_checker", "SD safety checker: replace flagged images with black"),
    Check("safety_checker_live_preview", "SD safety checker: also black out flagged live previews (NUI addition, slow on CPU)"),
    Check("nsfwjs", "NSFWJS filter: blur flagged images (radius 20)"),
    Choice("nsfwjs_safety_level", "NSFWJS safety level (safe: hentai, porn, sexy; questionable: hentai, porn; explicit: off)",
      SafetyLevels, "safe"),
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
    "verbose_detection" -> ujson.Bool(v.bool("verbose_detection")),
    "nudenet" -> ujson.Bool(v.bool("nudenet", true)),
    "safety_checker" -> ujson.Bool(v.bool("safety_checker")),
    "safety_checker_live_preview" -> ujson.Bool(v.bool("safety_checker_live_preview")),
    "nsfwjs" -> ujson.Bool(v.bool("nsfwjs")),
    "nsfwjs_safety_level" -> ujson.Str(safetyLevel(v))) ++ categoryFields(v))

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] =
    def on(k: String) = o.value.get(k).contains(ujson.Bool(true))
    (if o.value.get("nudenet").forall(_ == ujson.Bool(true)) then Seq(InfotextKey -> encode(o)) else Nil) ++
      (if on("safety_checker") then Seq(SafetyInfotextKey -> (if on("safety_checker_live_preview") then SafetyFinalAndPreviews else SafetyFinal)) else Nil) ++
      (if on("nsfwjs") then Seq(NsfwjsInfotextKey -> o.value.get("nsfwjs_safety_level").map(_.str).getOrElse("safe")) else Nil)

/** NSFW checks for Extras. NudeNet: auto-detected regions and/or a custom mask, with upstream's override settings.
  * NSFWJS: the filter's save-time pass (and its /safety/predict check with Only report). SD safety checker: the
  * generation check applied to one image (NUI addition). */
class NsfwCheckerExtrasPlugin extends ExtrasPlugin:
  import NsfwCheckerPlugin.*
  def id = "nsfw_checker_extras"
  def name = "NSFW Checker"
  override def description =
    "NudeNet censors the image (or each txt2img result) like the extension's Extras tab: auto-detection and an " +
    "optional custom mask (white = censor) are combined. The blur radius is in pixels; the mask blend scales with the " +
    "image. No censor with Output only the mask gives just the mask. NSFWJS blurs the whole image (radius 20) when " +
    "its top class is NSFW at the safety level; the SD safety checker replaces a flagged image with black. With Only " +
    "report the image is kept and the result is written to the infotext. Both download their model on first use."
  override def controls: Seq[Control] = Seq(
    Choice("checker", "Checker", Checkers.map(_._1), "NudeNet"),
    Choice("nsfwjs_safety_level", "NSFWJS safety level (safe: hentai, porn, sexy; questionable: hentai, porn; explicit: off)",
      SafetyLevels, "safe"),
    Check("report_only", "Only report (NSFWJS / SD safety checker): keep the image, write the result to the infotext"),
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

  private[plugins] def checker(v: Values): String =
    val label = v.str("checker", "NudeNet")
    Checkers.collectFirst { case (`label`, engine) => engine }.getOrElse(
      throw new IllegalArgumentException(s"NSFW Checker: checker must be one of ${Checkers.map(_._1).mkString(", ")} (got '$label')"))

  private[plugins] def toolParams(v: Values): Seq[(String, ujson.Value)] =
    checker(v) match
      case "nudenet" => nudenetParams(v)
      case other =>
        if v.bool("mask_only") || v.str("mask").trim.nonEmpty then
          throw new IllegalArgumentException(s"NSFW Checker: a custom mask and Output only the mask need the NudeNet checker")
        Seq("checker" -> ujson.Str(other), "report_only" -> ujson.Bool(v.bool("report_only"))) ++
          (if other == "nsfwjs" then Seq("safety_level" -> ujson.Str(safetyLevel(v))) else Nil)

  private def nudenetParams(v: Values): Seq[(String, ujson.Value)] =
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
    val params = toolParams(v)
    checker(v) match
      case "nudenet" => nudenet(img, v, ctx, params)
      case kind =>
        val r = ImageTools.run(ctx, "nsfw_checker", img, params*)
        try
          val flagged = r.info.get("is_nsfw").contains("1")
          val kept = v.bool("report_only")
          val verdict = (flagged, kind) match
            case (false, _) => "not NSFW"
            case (true, "nsfwjs") => if kept then "NSFW (reported only)" else "NSFW (blurred)"
            case (true, _) => if kept then "NSFW (reported only)" else "NSFW (black image)"
          val post =
            if kind == "nsfwjs" then
              val level = safetyLevel(v)
              val label = r.info.get("label").filter(_.nonEmpty)
              NsfwjsExtrasInfotextKey -> (if level == "explicit" then "explicit; not classified"
                else s"$level; $verdict${label.map(l => s"; top class $l").getOrElse("")}")
            else SafetyExtrasInfotextKey -> verdict
          if v.bool("save_mask") then ctx.log("NSFW Checker: only NudeNet produces a mask; none saved")
          ctx.log(s"NSFW Checker: ${post._2}")
          Seq((r.image, Seq(post)))
        finally Support.deleteQuietly(r.outputs.values.toSeq*)

  private def nudenet(img: BufferedImage, v: Values, ctx: PluginContext, params: Seq[(String, ujson.Value)]): Seq[(BufferedImage, Seq[(String, String)])] =
    val r = ImageTools.run(ctx, "nsfw_checker", img, params*)
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
