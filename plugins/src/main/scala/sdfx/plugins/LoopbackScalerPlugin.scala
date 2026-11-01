package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

/** Loopback Scaler (Elldreth/loopback_scaler, MIT, revision 184a53e6d22579c578c6ee1bf55d821479213c49), re-implemented
  * from its documented behaviour. The arithmetic below restates the source schedule; the one deliberate change is
  * marked in [[LoopbackScalerPlugin.sizes]].
  */
object LoopbackScalerPlugin:
  val Easings: Seq[String] = Seq("Linear",
    "Ease In: Sine", "Ease In: Cubic", "Ease In: Quint", "Ease In: Circ",
    "Ease Out: Sine", "Ease Out: Cubic", "Ease Out: Quint", "Ease Out: Circ",
    "Ease InOut: Sine", "Ease InOut: Cubic", "Ease InOut: Quint", "Ease InOut: Circ")
  val Levels: Seq[String] = Seq("None", "Low", "Medium", "High")
  /** The engine accepts image sides in this range (GenerationParams.validate). */
  val MinSide = 64
  val MaxSide = 4096

  /** Upstream easing of the loop fraction `perc` (= loop / loops). "Ease In: Quint" is the source's fourth power. */
  def ease(option: String, perc: Double): Double = option match
    case "Linear"            => perc
    case "Ease In: Sine"     => 1 - math.cos((perc * math.Pi) / 2)
    case "Ease In: Cubic"    => perc * perc * perc
    case "Ease In: Quint"    => perc * perc * perc * perc
    case "Ease In: Circ"     => 1 - math.sqrt(1 - math.pow(perc, 2))
    case "Ease Out: Sine"    => math.sin((perc * math.Pi) / 2)
    case "Ease Out: Cubic"   => 1 - math.pow(1 - perc, 3)
    case "Ease Out: Quint"   => 1 - math.pow(1 - perc, 5)
    case "Ease Out: Circ"    => math.sqrt(1 - math.pow(perc - 1, 2))
    case "Ease InOut: Sine"  => -(math.cos(math.Pi * perc) - 1) / 2
    case "Ease InOut: Cubic" => if perc < 0.5 then 4 * perc * perc * perc else 1 - math.pow(-2 * perc + 2, 3) / 2
    case "Ease InOut: Quint" => if perc < 0.5 then 16 * perc * perc * perc * perc * perc else 1 - math.pow(-2 * perc + 2, 5) / 2
    case "Ease InOut: Circ" =>
      if perc < 0.5 then (1 - math.sqrt(1 - math.pow(2 * perc, 2))) / 2 else (math.sqrt(1 - math.pow(-2 * perc + 2, 2)) + 1) / 2
    case other => throw new IllegalArgumentException(s"Loopback Scaler: unknown dimension increase '$other' (one of ${Easings.mkString(", ")})")

  /** Filter repeat count for "None" / "Low" / "Medium" / "High". */
  def iterations(level: String): Int = Levels.indexOf(level) match
    case -1 => throw new IllegalArgumentException(s"Loopback Scaler: unknown filter strength '$level' (one of ${Levels.mkString(", ")})")
    case n  => n

  /** Python `round` (half to even) for the non-negative values used here. */
  private def pyRound(x: Double): Long = math.rint(x).toLong

  /** Source `resize_to_nearest_multiple_of_m`: the shorter side is rounded up to a multiple of `m`, the other side
    * follows the requested aspect ratio and is rounded up to a multiple of `m` as well.
    */
  def nearestMultiple(width: Int, height: Int, m: Int = 8): (Int, Int) =
    if width <= 0 || height <= 0 then throw new IllegalArgumentException(s"Loopback Scaler: degenerate loop size ${width}x$height")
    val aspect = width.toDouble / height
    if width < height then
      val w = math.ceil(width.toDouble / m).toLong * m
      val h = math.ceil(pyRound(w / aspect).toDouble / m).toLong * m
      (w.toInt, h.toInt)
    else
      val h = math.ceil(height.toDouble / m).toLong * m
      val w = math.ceil(pyRound(h * aspect).toDouble / m).toLong * m
      (w.toInt, h.toInt)

  /** Target size of the final loop: the scaled start size with "Use Scale", otherwise the maximum width/height. */
  def finalSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int, useScale: Boolean, scale: Double): (Int, Int) =
    if useScale then (math.floor(width * scale).toInt, math.floor(height * scale).toInt) else (maxWidth, maxHeight)

  /** Per-loop img2img size. Loop `i` eases `i / loops` between the start size and the final target (the last loop
    * uses the target itself); when the start image is relatively taller than the target box the height drives the
    * width, otherwise the width drives the height, both through `nearestMultiple`.
    *
    * Deliberate change: in the width-driven branch the source computes the height from the *height* schedule
    * (`get_height_from_ratio(calc_height, ...)`), which squashes landscape images (768x512 starts at 776x344) and
    * ignores the maximum box (512x512 to 1024x2048 ends at 1024x2048). This uses the width schedule there, so the
    * branch mirrors the height-driven one; see docs/loopback-scaler-verification.md.
    */
  def sizes(width: Int, height: Int, loops: Int, maxWidth: Int, maxHeight: Int, useScale: Boolean, scale: Double,
      easing: String): Seq[(Int, Int)] =
    val baseRatio = height.toDouble / width
    val (finalW, finalH) = finalSize(width, height, maxWidth, maxHeight, useScale, scale)
    if finalW <= 0 || finalH <= 0 then throw new IllegalArgumentException(s"Loopback Scaler: degenerate final size ${finalW}x$finalH")
    val heightDiff = finalH - height
    val widthDiff = finalW - width
    val useHeight = baseRatio >= finalH.toDouble / finalW
    (0 until loops).map { i =>
      val e = ease(easing, i.toDouble / loops)
      val last = i == loops - 1
      val calcH = if last then finalH else (heightDiff * e + height).toInt
      val calcW = if last then finalW else (widthDiff * e + width).toInt
      if useHeight then nearestMultiple(math.floor(calcH / baseRatio).toInt, calcH)
      else nearestMultiple(calcW, math.floor(calcW * baseRatio).toInt)
    }

  /** Source denoising update after every loop: multiply by the change factor and clamp to 0.1..1. */
  def nextDenoising(d: Double, factor: Double): Double = math.min(math.max(d * factor, 0.1), 1)

  /** Validated control values. Every out-of-range or unknown value is rejected rather than clamped. */
  final case class Settings(loops: Int, denoiseChange: Double, maxWidth: Int, maxHeight: Int, useScale: Boolean, scale: Double,
      easing: String, detail: String, blur: String, smooth: String, contour: Boolean,
      sharpness: Double, brightness: Double, color: Double, contrast: Double):
    def identityFinish: Boolean =
      Seq(sharpness, brightness, color, contrast).forall(_ == 1.0) && Seq(detail, blur, smooth).forall(_ == "None") && !contour

    /** Source `extra_generation_params` (written into every loop's infotext). */
    def infotext: ListMap[String, String] = ListMap(
      "Denoising strength change factor" -> Values.fmt(denoiseChange),
      "Dimension increment factor" -> easing,
      "Add Detail" -> detail,
      "Add Blur" -> blur,
      "Smoothing" -> smooth,
      "Contour" -> (if contour then "True" else "False"),
      "Sharpness" -> Values.fmt(sharpness),
      "Brightness" -> Values.fmt(brightness),
      "Color Strength" -> Values.fmt(color),
      "Contrast" -> Values.fmt(contrast)
    )

  object Settings:
    private def number(v: Values, key: String, default: Double, min: Double, max: Double): Double =
      v.map.get(key).map(_.trim) match
        case None | Some("") => default
        case Some(s) =>
          val d = s.toDoubleOption.filter(x => !x.isNaN && !x.isInfinite)
            .getOrElse(throw new IllegalArgumentException(s"Loopback Scaler: $key must be a number (got '$s')"))
          if d < min - 1e-9 || d > max + 1e-9 then
            throw new IllegalArgumentException(s"Loopback Scaler: $key must be in ${Values.fmt(min)}..${Values.fmt(max)} (got $s)")
          d
    private def integer(v: Values, key: String, default: Int, min: Int, max: Int): Int =
      val d = number(v, key, default, min, max)
      if d != math.rint(d) then throw new IllegalArgumentException(s"Loopback Scaler: $key must be a whole number (got ${Values.fmt(d)})")
      d.toInt
    private def choice(v: Values, key: String, options: Seq[String], default: String): String =
      val s = v.str(key, default)
      if options.contains(s) then s
      else throw new IllegalArgumentException(s"Loopback Scaler: $key must be one of ${options.mkString(", ")} (got '$s')")
    private def flag(v: Values, key: String): Boolean = v.map.get(key).map(_.trim.toLowerCase) match
      case None | Some("") | Some("false") | Some("0") | Some("no") => false
      case Some("true") | Some("1") | Some("yes")                   => true
      case Some(s) => throw new IllegalArgumentException(s"Loopback Scaler: $key must be true or false (got '$s')")

    def parse(v: Values): Settings = Settings(
      loops = integer(v, "loops", 4, 1, 32),
      denoiseChange = number(v, "denoising_strength_change_factor", 1.0, 0.9, 1.1),
      maxWidth = integer(v, "max_width", 1024, 512, 4096),
      maxHeight = integer(v, "max_height", 1024, 512, 4096),
      useScale = flag(v, "use_scale"),
      scale = number(v, "scale", 1.0, 0.5, 4.0),
      easing = choice(v, "dimension_increment_factor", Easings, "Linear"),
      detail = choice(v, "detail_strength", Levels, "None"),
      blur = choice(v, "blur_strength", Levels, "None"),
      smooth = choice(v, "smooth_strength", Levels, "None"),
      contour = flag(v, "contour_bool"),
      sharpness = number(v, "sharpness_strength", 1.0, 0.1, 2.0),
      brightness = number(v, "brightness_strength", 1.0, 0.1, 2.0),
      color = number(v, "color_strength", 1.0, 0.1, 2.0),
      contrast = number(v, "contrast_strength", 1.0, 0.1, 2.0)
    )

/** "Loopback Scaler": img2img in a loop whose output becomes the next input while the size grows towards the maximum
  * (or scaled) size along the chosen easing curve and the denoising strength is multiplied by the change factor each
  * loop. The last loop's image of each batch gets the optional PIL enhancements and filters (engine `image_tool`
  * `loopback_scaler`).
  *
  * Source semantics kept: one image per loop; batch count repeats the whole chain from the original init image while
  * the seed (+1 per loop) and the denoising strength carry on across batches; every loop is a regular saved img2img
  * output and only the finished images of each batch are returned.
  */
class LoopbackScalerPlugin extends Plugin:
  import LoopbackScalerPlugin.*

  def id = "loopback_scaler"
  def name = "Loopback Scaler"
  def kind = PluginKind.Script
  override def tabs: Set[Tab] = Set(Tab.Img2Img)
  override def description = "Repeats img2img, feeding each output back while the size grows step by step towards the " +
    "maximum (or scaled) size, then optionally sharpens, adjusts and filters the final image. img2img only."
  override def enabledBy: Map[String, String] = Map("scale" -> "use_scale")
  override def requiresEngineFeatures: Seq[String] = Seq("loopback_scaler")

  override def controls: Seq[Control] = Seq(
    Choice("dimension_increment_factor", "Dimension Increase", Easings, "Linear"),
    IntField("loops", "Loops", 1, 32, 4),
    Slider("denoising_strength_change_factor", "Denoise Change", 0.9, 1.1, 0.01, 1.0),
    Slider("max_width", "Maximum Image Width", 512, 4096, 64, 1024),
    Slider("max_height", "Maximum Image Height", 512, 4096, 64, 1024),
    Check("use_scale", "Use Scale"),
    Slider("scale", "Scale Final Image", 0.5, 4, 0.1, 1.0),
    Section("final_filters", "Final Image Filters"),
    Choice("detail_strength", "Add Detail", Levels, "None"),
    Choice("blur_strength", "Add Blur", Levels, "None"),
    Choice("smooth_strength", "Smoothing", Levels, "None"),
    Check("contour_bool", "Contour"),
    Slider("sharpness_strength", "Sharpness", 0.1, 2.0, 0.01, 1.0),
    Slider("brightness_strength", "Brightness", 0.1, 2.0, 0.01, 1.0),
    Slider("color_strength", "Color", 0.1, 2.0, 0.01, 1.0),
    Slider("contrast_strength", "Contrast", 0.1, 2.0, 0.01, 1.0),
    Section("info", "Info - Loopback Scaler", expanded = false),
    Note("help_loops", "Loops: how many img2img passes run; the size grows a little each pass and the last pass " +
      "reaches the maximum (or scaled) size. 4 to 10 usually works; more passes add detail but also artifacts."),
    Note("help_denoise", "Denoise Change: multiplies the img2img denoising strength after every pass (1 keeps it; the " +
      "result stays within 0.1..1)."),
    Note("help_dimension", "Dimension Increase: how the size grows across passes. Ease Out grows early, Ease In grows " +
      "late, Ease InOut grows mostly in the middle."),
    Note("help_size", "Maximum Image Width/Height: the box for the final image (rounded up to multiples of 8, keeping " +
      "the aspect ratio). Start from a smaller image such as 344x512 or 512x768. Use Scale instead multiplies the " +
      "img2img width and height by Scale Final Image."),
    Note("help_filters", "Final Image Filters: Sharpness, Brightness, Color and Contrast (1 = unchanged), then Add " +
      "Detail, Smoothing and Add Blur repeated 1-3 times for Low/Medium/High, then Contour, on the last pass only."),
    Note("help_settings", "Suggested img2img settings: Crop and resize, DDIM, 30 steps, 344x512 or 512x768, CFG 6-8, " +
      "denoising 0.2-0.4. Always keep a prompt. Batch size must be 1; use batch count for repeats.")
  )

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    Support.requireImg2Img(p0, name)
    val s = Settings.parse(v)
    if p0.batchSize != 1 then
      throw new IllegalArgumentException(s"$name generates one image per loop (the source forces batch size 1); set " +
        s"batch size to 1 (got ${p0.batchSize}) and use batch count to repeat the chain")
    val plan = sizes(p0.width, p0.height, s.loops, s.maxWidth, s.maxHeight, s.useScale, s.scale, s.easing)
    plan.zipWithIndex.find((wh, _) => wh._1 < MinSide || wh._1 > MaxSide || wh._2 < MinSide || wh._2 > MaxSide).foreach {
      case ((w, h), i) =>
        throw new IllegalArgumentException(s"$name: loop ${i + 1} would be ${w}x$h, outside the engine's " +
          s"$MinSide..$MaxSide range; lower the maximum size or scale")
    }
    val i0 = p0.img2img.get
    val extra = p0.extra ++ s.infotext
    val batchCount = math.max(1, p0.nIter)
    val total = s.loops * batchCount
    val subseed = if p0.subseed < 0 && p0.subseedStrength > 0 then Support.fixSeed(p0.subseed) else p0.subseed
    var seed = Support.fixSeed(p0.seed)
    var denoise = i0.denoisingStrength
    ctx.log(s"$name: ${p0.width}x${p0.height} -> ${plan.last._1}x${plan.last._2} in ${s.loops} loop(s), " +
      s"${s.easing}, denoising change ${Values.fmt(s.denoiseChange)}")
    val finals = ArrayBuffer[OutputImage]()
    var done = 0
    var running = true
    var n = 0
    while running && n < batchCount do
      var init = i0.initImage // every batch restarts from the original init image
      var i = 0
      while running && i < s.loops do
        val (w, h) = plan(i)
        val p = p0.copy(width = w, height = h, seed = seed, subseed = subseed, batchSize = 1, nIter = 1, extra = extra,
          img2img = Some(i0.copy(initImage = init, denoisingStrength = denoise)))
        ctx.status(s"$name: iteration ${i + 1}/${s.loops}, batch ${n + 1}/$batchCount", done, total)
        Support.step(ctx)(ctx.generate(p)).flatMap(_.headOption) match
          case None => running = false
          case Some(o) =>
            done += 1
            if i == s.loops - 1 then
              finish(o, s, ctx) match
                case Some(f) => finals += f
                case None    => running = false
            init = o.path.toString
            seed = o.seed + 1
            denoise = nextDenoising(denoise, s.denoiseChange)
        i += 1
      n += 1
    if !running then ctx.log(s"$name interrupted after $done of $total loop(s); returning ${finals.size} finished image(s)")
    JobOutput(finals.toSeq)

  /** The last loop's output, post-processed and saved as a new img2img image with that loop's infotext. With all
    * filters at their neutral values the enhancement is the identity, so the loop output itself is returned.
    */
  private def finish(o: OutputImage, s: Settings, ctx: PluginContext): Option[OutputImage] =
    if s.identityFinish then Some(o)
    else
      val img = Support.readImage(ctx, o.path)
      Support.step(ctx)(ImageTools.run(ctx, "loopback_scaler", img,
        "sharpness" -> ujson.Num(s.sharpness), "brightness" -> ujson.Num(s.brightness), "color" -> ujson.Num(s.color),
        "contrast" -> ujson.Num(s.contrast), "detail" -> ujson.Str(s.detail), "smooth" -> ujson.Str(s.smooth),
        "blur" -> ujson.Str(s.blur), "contour" -> ujson.Bool(s.contour))).map { r =>
        try ctx.saveImage(r.image, "img2img", o.params, o.infotext)
        finally Support.deleteQuietly(r.outputs.values.toSeq*)
      }
