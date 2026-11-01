package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import java.util.Locale

/** Settings and infotext keys of hnmr293/sd-webui-llul, so its PNGs restore and NUI PNGs paste back into it. */
object LlulPlugin:
  val Prefix = "LLuL "
  val EnabledKey = "LLuL Enabled"
  val InfotextKeys: Seq[String] = Seq("Enabled", "Multiply", "Weight", "Layers", "Apply to", "Start steps", "Max steps",
    "Upscaler", "Downscaler", "Interpolation", "x", "y").map(Prefix + _)

  val ApplyTo: Seq[(String, String, String)] = Seq(
    ("apply_resblock", "resblock", "Resblock"), ("apply_transformer", "transformer", "Transformer"),
    ("apply_sattn", "s. attn.", "S. Attn."), ("apply_xattn", "x. attn.", "X. Attn."), ("apply_out", "out", "OUT"))
  val Upscalers: Seq[String] = Seq("Nearest", "Bilinear", "Bicubic")
  val Downscalers: Seq[String] = Seq("Nearest", "Bilinear", "Bicubic", "Area", "Pooling Max", "Pooling Avg")
  val Interpolations: Seq[String] = Seq("Lerp", "SLerp")

  /** The values the source uses until "I know what I am doing." is ticked. */
  private val SimpleAdvanced = Map("layers" -> "OUT", "apply_resblock" -> "false", "apply_transformer" -> "false",
    "apply_sattn" -> "false", "apply_xattn" -> "false", "apply_out" -> "true", "start_steps" -> "5", "max_steps" -> "0",
    "up" -> "Bilinear", "up_aa" -> "false", "down" -> "Bilinear", "down_aa" -> "false", "intp" -> "Lerp")

  /** Python's repr of a list of strings, as the source writes `Layers` and `Apply to`. */
  def pyList(items: Seq[String]): String = items.map(i => s"'$i'").mkString("[", ", ", "]")

  /** The strings inside a Python list repr (`['OUT', 'IN01']`), or None for anything else. */
  def parsePyList(text: String): Option[Seq[String]] =
    val t = text.trim
    if !(t.startsWith("[") && t.endsWith("]")) then None
    else
      val inner = t.drop(1).dropRight(1).trim
      if inner.isEmpty then Some(Nil)
      else
        val parts = inner.split(",").map(_.trim).toSeq
        if parts.forall(p => p.length >= 2 && ((p.startsWith("'") && p.endsWith("'")) || (p.startsWith("\"") && p.endsWith("\""))))
        then Some(parts.map(p => p.substring(1, p.length - 1)))
        else None

  private def pretty(options: Seq[String], name: String): Option[String] =
    options.find(_.equalsIgnoreCase(name))

  private def number(text: String): Option[Double] = text.trim.toDoubleOption.filter(_.isFinite)
  private def whole(text: String, low: Int): Option[Int] =
    number(text).filter(n => n == math.rint(n) && n >= low).map(_.toInt)

  /** A scaler name as written in the infotext (`bilinear-aa`, `nearest-exact`, `pooling max`) to (control, aa). */
  private def scaler(name: String, options: Seq[String]): Option[(String, Boolean)] =
    val lower = name.trim.toLowerCase(Locale.ROOT)
    val aa = lower.endsWith("-aa")
    val base = if aa then lower.dropRight(3) else lower
    pretty(options, if base == "nearest-exact" then "nearest" else base).map(_ -> aa)

  /** PNG restore: `LLuL Enabled` present and true enables the plugin; other PNGs switch it off. Missing keys take the
    * source's defaults. Values the engine would reject give `None`. The advanced box is ticked so that the values apply;
    * `Max steps` equal to the PNG's `Steps` is the source's 0 ("the pass' step count").
    */
  def replay(fields: Map[String, String]): Option[Values] =
    val key = (name: String) => fields.get(Prefix + name)
    if !fields.get(EnabledKey).exists(_.trim.equalsIgnoreCase("true")) then Some(Values(Map("enabled" -> "false")))
    else
      val defaults = Values.defaults(new LlulPlugin().controls).map
      var out = defaults ++ Map("enabled" -> "true", "understand" -> "true")
      var ok = true
      def set(entries: (String, String)*): Unit = out = out ++ entries
      def bad(): Unit = ok = false
      key("Multiply").foreach: v =>
        whole(v, 1).filter(n => (n & (n - 1)) == 0 && n <= 32) match
          case Some(n) => set("multiply" -> Integer.numberOfTrailingZeros(n).toString)
          case None => bad()
      key("Weight").foreach(v => number(v).fold(bad())(n => set("weight" -> Values.fmt(n))))
      key("Layers").foreach: v =>
        if v.trim == "None" then set("layers" -> "")
        else parsePyList(v).fold(bad())(items => set("layers" -> items.mkString(", ")))
      key("Apply to").foreach: v =>
        parsePyList(v).map(_.map(_.toLowerCase(Locale.ROOT))) match
          case Some(items) if items.forall(i => ApplyTo.exists(_._2 == i)) =>
            set(ApplyTo.map((k, name, _) => k -> items.contains(name).toString)*)
          case _ => bad()
      key("Start steps").foreach(v => whole(v, 1).fold(bad())(n => set("start_steps" -> n.toString)))
      key("Max steps").foreach: v =>
        whole(v, 1) match
          case Some(n) => set("max_steps" -> (if fields.get("Steps").flatMap(_.trim.toIntOption).contains(n) then "0" else n.toString))
          case None => bad()
      key("Upscaler").foreach: v =>
        scaler(v, Upscalers).fold(bad())((name, aa) => set("up" -> name, "up_aa" -> aa.toString))
      key("Downscaler").foreach: v =>
        scaler(v, Downscalers).fold(bad())((name, aa) => set("down" -> name, "down_aa" -> aa.toString))
      key("Interpolation").foreach(v => pretty(Interpolations, v.trim).fold(bad())(n => set("intp" -> n)))
      key("x").foreach(v => number(v).fold(bad())(n => set("x" -> Values.fmt(n))))
      key("y").foreach(v => number(v).fold(bad())(n => set("y" -> Values.fmt(n))))
      Option.when(ok)(Values(out))

/** LLuL, the Local Latent upscaLer (hnmr293/sd-webui-llul): the UNet draws a region of the image at a higher
  * resolution by processing an upscaled crop next to it and blending the result back.
  */
class LlulPlugin extends HookPlugin:
  import LlulPlugin.*
  def id = "llul"
  def name = "LLuL"
  def hook = "llul"
  override def description =
    "Local Latent upscaLer: a square region of the image (1/2^N of its width and height, at the X / Y position) is " +
    "upscaled inside the U-Net, so it is drawn with finer detail, and blended back with the Weight. A mask scales " +
    "the Weight per pixel. The advanced settings pick the layers (resblocks, transformers, attention) with the " +
    "source's layer names such as IN01, M00 or OUT03. Not applied in ADetailer passes."

  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Slider("weight", "Weight", -1, 2, 0.01, 0.15),
    Slider("multiply", "Multiplication (2^N)", 1, 5, 1, 1),
    Text("x", "Region X (pixels from the left; empty = centred)", placeholder = "centred"),
    Text("y", "Region Y (pixels from the top; empty = centred)", placeholder = "centred"),
    Check("use_mask", "Enable mask which scales the weight (black = 0.0, white = 1.0)"),
    ImageFile("mask", "Mask image"),
    Check("force_float", "Force convert half to float on interpolation (for some platforms)"),
    Check("understand", "I know what I am doing."),
    Section("advanced", "Advanced", expanded = false),
    Text("layers", "Layers", "OUT"),
    Check("apply_resblock", "Resblock"),
    Check("apply_transformer", "Transformer"),
    Check("apply_sattn", "S. Attn."),
    Check("apply_xattn", "X. Attn."),
    Check("apply_out", "OUT", defaultValue = true),
    Slider("start_steps", "Start steps", 1, 300, 1, 5),
    Slider("max_steps", "Max steps (0 = the pass' steps)", 0, 300, 1, 0),
    Choice("up", "Upscaling", Upscalers, "Bilinear"),
    Check("up_aa", "Enable AA for Upscaling."),
    Choice("down", "Downscaling", Downscalers, "Bilinear"),
    Check("down_aa", "Enable AA for Downscaling."),
    Choice("intp", "Interpolation method", Interpolations, "Lerp"),
    Note("area_note", "The source's extra output images that show the effective area are not provided."))

  private def effective(v: Values): Values =
    if v.bool("understand") then v else Values(v.map ++ SimpleAdvanced)

  def hookFields(values: Values): ujson.Obj =
    val v = effective(values)
    val mask = v.str("mask").trim
    val useMask = v.bool("use_mask") && ImageHooks.exists(mask)
    def position(k: String): ujson.Value =
      val s = v.str(k).trim
      if s.isEmpty then ujson.Null else s.toDoubleOption.map(ujson.Num(_)).getOrElse(ujson.Str(s))
    ujson.Obj(
      "active" -> true,
      "weight" -> v.double("weight", 0.15),
      "multiply" -> v.int("multiply", 1),
      "x" -> position("x"), "y" -> position("y"),
      "layers" -> v.str("layers", "OUT"),
      "apply_to" -> ujson.Arr.from(ApplyTo.collect { case (k, name, _) if v.bool(k) => ujson.Str(name) }),
      "start_steps" -> v.int("start_steps", 5), "max_steps" -> v.int("max_steps", 0),
      "up" -> v.str("up", "Bilinear").toLowerCase(Locale.ROOT), "up_aa" -> v.bool("up_aa"),
      "down" -> v.str("down", "Bilinear").toLowerCase(Locale.ROOT), "down_aa" -> v.bool("down_aa"),
      "intp" -> (if useMask then "lerp" else v.str("intp", "Lerp").toLowerCase(Locale.ROOT)),
      "force_float" -> v.bool("force_float"),
      "mask" -> (if useMask then mask else ""))

  private def str(o: ujson.Obj, k: String): String = o.value.get(k).map(Hooks.num).getOrElse("")

  private def keys(o: ujson.Obj, width: Int, height: Int, steps: Int): Seq[(String, String)] =
    val n = o.value.get("multiply").collect { case ujson.Num(d) => math.max(d.toInt, 0) }.getOrElse(1)
    val m = 1 << n
    def pos(k: String, size: Int): String = o.value.get(k) match
      case Some(ujson.Null) | None => ((size - size / m) / 2).toString
      case Some(other) => Hooks.num(other)
    val layers = str(o, "layers").trim
    val applyTo = o.value.get("apply_to").collect { case a: ujson.Arr => a.value.map(Hooks.num).toSeq }.getOrElse(Nil)
    val maxSteps = o.value.get("max_steps").collect { case ujson.Num(d) if d >= 1 => d.toInt }.getOrElse(steps)
    def up(name: String, aa: Boolean) = (if name == "nearest" then "nearest-exact" else name) + (if aa then "-aa" else "")
    def down(name: String, aa: Boolean) = name + (if aa then "-aa" else "")
    val flag = (k: String) => o.value.get(k).contains(ujson.Bool(true))
    Seq(
      "LLuL Enabled" -> "True",
      "LLuL Multiply" -> m.toString,
      "LLuL Weight" -> str(o, "weight"),
      "LLuL Layers" -> (if layers.isEmpty then "None" else pyList(layers.split(",").map(_.trim).toSeq)),
      "LLuL Apply to" -> pyList(applyTo),
      "LLuL Start steps" -> str(o, "start_steps"),
      "LLuL Max steps" -> maxSteps.toString,
      "LLuL Upscaler" -> up(str(o, "up"), flag("up_aa")),
      "LLuL Downscaler" -> down(str(o, "down"), flag("down_aa")),
      "LLuL Interpolation" -> str(o, "intp"),
      "LLuL x" -> pos("x", width),
      "LLuL y" -> pos("y", height))

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] = keys(o, 512, 512, 20)

  /** The `LLuL Enabled` axis can switch the plugin off; `active` itself never reaches the engine. */
  override def process(p: GenerationParams, v: Values): GenerationParams =
    val (p2, merged) = Hooks(p, hook, hookFields(v))
    if merged.value.get("active").contains(ujson.Bool(false)) then p2.copy(hooks = p2.hooks.removed(hook))
    else
      val fields = ujson.Obj.from(merged.value.toSeq.filterNot(_._1 == "active"))
      p2.copy(hooks = p2.hooks.updated(hook, fields), extra = p2.extra ++ keys(fields, p.width, p.height, p.steps))

  private def axis(axisName: String, numeric: Boolean = false)(f: String => (String, ujson.Value)): XyzAxis =
    XyzAxis(axisName, isNumeric = numeric): (p, s) =>
      val (field, value) = f(s)
      Hooks.setOverride(p, hook, field, value)
  private def bool(s: String): ujson.Value = ujson.Bool(s.trim.equalsIgnoreCase("true"))
  private def lower(s: String): ujson.Value = ujson.Str(s.trim.toLowerCase(Locale.ROOT))

  /** The source's axes; every axis but Enabled, Multiply and Weight also ticks "I know what I am doing". */
  override def xyzAxes: Seq[XyzAxis] = Seq(
    axis("LLuL Enabled")(s => "active" -> bool(s)),
    axis("LLuL Multiply", numeric = true)(s => "multiply" -> Hooks.numValue(s)),
    axis("LLuL Weight", numeric = true)(s => "weight" -> Hooks.numValue(s)),
    axis("LLuL Layers")(s => "layers" -> ujson.Str(s.trim)),
    axis("LLuL Apply to")(s => "apply_to" -> ujson.Arr.from(s.split(",").map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty).map(ujson.Str(_)))),
    axis("LLuL Start steps", numeric = true)(s => "start_steps" -> Hooks.numValue(s)),
    axis("LLuL Max steps", numeric = true)(s => "max_steps" -> Hooks.numValue(s)),
    axis("LLuL Upscaler")(s => "up" -> lower(s)),
    axis("LLuL Upscaler AA")(s => "up_aa" -> bool(s)),
    axis("LLuL Downscaler")(s => "down" -> lower(s)),
    axis("LLuL Downscaler AA")(s => "down_aa" -> bool(s)),
    axis("LLuL Interpolation method")(s => "intp" -> lower(s)))
