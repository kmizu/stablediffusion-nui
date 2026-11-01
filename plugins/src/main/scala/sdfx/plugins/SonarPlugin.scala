package sdfx.plugins

import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import java.nio.file.Paths

/** Settings and infotext keys of Kahsolt/stable-diffusion-webui-sonar, so its PNGs restore and NUI PNGs paste back
  * into it. The source writes `Sonar sampler`, `Sonar momentum` and `Sonar momentum_hist`; the other keys are added
  * here (only when they differ from the defaults or are in use) so a PNG replays exactly.
  */
object SonarPlugin:
  val Samplers: Seq[String] = Seq("Euler a", "Euler", "Naive")
  val Signs: Seq[String] = Seq("pos", "neg", "rand")
  val HistoryInits: Seq[String] = Seq("zero", "rand_init", "rand_new")
  val RefMethods: Seq[String] = Seq("linear", "euler")

  private val Defaults = Map("sampler" -> "Euler a", "momentum" -> "0.95", "momentum_hist" -> "0.75",
    "momentum_hist_init" -> "zero", "momentum_sign" -> "pos", "ref_meth" -> "linear", "ref_hgf" -> "0.2",
    "ref_min_step" -> "0", "ref_max_step" -> "0.75", "upscaler" -> "Lanczos", "upscale_ratio" -> "1",
    "upscale_width" -> "0", "upscale_height" -> "0")

  /** Keys the source writes, then ours. */
  val SourceKeys: Seq[String] = Seq("Sonar sampler", "Sonar momentum", "Sonar momentum_hist")
  private val OptionKeys = Seq("Sonar momentum_hist_init", "Sonar momentum_sign")
  private val RefKeys = Seq("Sonar ref_img", "Sonar ref_meth", "Sonar ref_hgf", "Sonar ref_start_step", "Sonar ref_stop_step")
  private val UpscaleKeys = Seq("Sonar upscaler", "Sonar upscale_ratio", "Sonar upscale_width", "Sonar upscale_height")
  val InfotextKeys: Seq[String] = SourceKeys ++ OptionKeys ++ RefKeys ++ UpscaleKeys

  private def unit(raw: String): Option[String] =
    raw.trim.toDoubleOption.filter(n => n.isFinite && n >= 0 && n <= 1).map(Values.fmt)
  private def nonNegative(raw: String): Option[String] =
    raw.trim.toDoubleOption.filter(n => n.isFinite && n >= 0).map(Values.fmt)
  private def whole(raw: String): Option[String] =
    raw.trim.toDoubleOption.filter(n => n.isFinite && n == math.rint(n) && n >= 0).map(Values.fmt)
  private def oneOf(options: Seq[String])(raw: String): Option[String] = Some(raw.trim).filter(options.contains)

  /** (infotext key, control key, parser) */
  private val Parsers: Seq[(String, String, String => Option[String])] = Seq(
    ("Sonar sampler", "sampler", oneOf(Samplers)), ("Sonar momentum", "momentum", unit),
    ("Sonar momentum_hist", "momentum_hist", unit), ("Sonar momentum_hist_init", "momentum_hist_init", oneOf(HistoryInits)),
    ("Sonar momentum_sign", "momentum_sign", oneOf(Signs)), ("Sonar ref_meth", "ref_meth", oneOf(RefMethods)),
    ("Sonar ref_hgf", "ref_hgf", unit), ("Sonar ref_start_step", "ref_min_step", nonNegative),
    ("Sonar ref_stop_step", "ref_max_step", nonNegative), ("Sonar upscaler", "upscaler", s => Some(s.trim).filter(_.nonEmpty)),
    ("Sonar upscale_ratio", "upscale_ratio", s => nonNegative(s).filter(_.toDouble >= 1)),
    ("Sonar upscale_width", "upscale_width", whole), ("Sonar upscale_height", "upscale_height", whole))

  /** Restores from the source's keys; other PNGs switch the plugin off. Missing keys take the defaults, as in the
    * source. The reference image is a file and cannot be restored; supply it again to repeat such a PNG.
    */
  def replay(fields: Map[String, String]): Option[Values] =
    if !fields.contains("Sonar sampler") then Some(Values(Map("enabled" -> "false")))
    else
      val parsed = Parsers.flatMap((label, key, parse) => fields.get(label).map(raw => parse(raw).map(key -> _)))
      if parsed.contains(None) then None
      else
        val base = Values.defaults(new SonarPlugin().controls).map
        Some(Values(base ++ parsed.flatten ++ Map("enabled" -> "true", "upscale" -> fields.contains("Sonar upscaler").toString)))

/** Sonar (Kahsolt/stable-diffusion-webui-sonar): momentum on the Euler, Euler a and Naive sampler updates, reference
  * image guidance for Naive and a final upscale.
  */
class SonarPlugin extends HookPlugin:
  import SonarPlugin.*
  def id = "sonar"
  def name = "Sonar"
  def hook = "sonar"
  override def description =
    "Searches the neighbourhood of an image for similar, hopefully better ones: the sampler update follows a " +
    "momentum over the earlier latent differences. While enabled, the sampler chosen here (Euler a, Euler, or the " +
    "simple Naive sampler) replaces the sampler of the request. Momentum current 1 turns the momentum off; " +
    "keep it at 0.85 or more. Naive can also be pulled towards a reference image (shallow fusion). The finished " +
    "image can be upscaled by a ratio or to a size. Use X/Y/Z axes of Sonar momentum and Sonar momentum_hist for the " +
    "source's grid search. ADetailer's own passes run without Sonar."

  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Choice("sampler", "Base Sampler", Samplers, "Euler a"),
    Slider("momentum", "Momentum current", 0.75, 1, 0.01, 0.95),
    Slider("momentum_hist", "Momentum history", 0, 1, 0.01, 0.75),
    Choice("momentum_sign", "Momentum sign (experimental)", Signs, "pos"),
    Choice("momentum_hist_init", "Momentum history init (experimental)", HistoryInits, "zero"),
    Section("ref_section", "Reference image guide (Naive sampler)", expanded = false),
    ImageFile("ref_img", "Reference image file"),
    Choice("ref_meth", "Ref guide step method", RefMethods, "linear"),
    Slider("ref_hgf", "Ref guide factor", 0, 1, 0.01, 0.2),
    Slider("ref_min_step", "Ref start step (<= 1: fraction of the steps)", 0, 150, 0.05, 0),
    Slider("ref_max_step", "Ref stop step (<= 1: fraction of the steps)", 0, 150, 0.05, 0.75),
    Section("upscale_section", "Upscaling", expanded = false),
    Check("upscale", "Upscaling"),
    DynamicChoice("upscaler", "Upscaler", ChoiceSource.Upscalers, "Lanczos", allowNone = false),
    Slider("upscale_ratio", "Upscale ratio", 1, 4, 0.1, 1),
    IntField("upscale_width", "Upscale width (0 = from the ratio or height)", 0, 4096, 0),
    IntField("upscale_height", "Upscale height (0 = from the ratio or width)", 0, 4096, 0))

  def hookFields(v: Values): ujson.Obj =
    val d = Values.defaults(controls)
    ujson.Obj(
      "sampler" -> v.str("sampler", d.str("sampler")), "momentum" -> v.double("momentum", 0.95),
      "momentum_hist" -> v.double("momentum_hist", 0.75), "momentum_sign" -> v.str("momentum_sign", "pos"),
      "momentum_hist_init" -> v.str("momentum_hist_init", "zero"), "ref_img" -> v.str("ref_img").trim,
      "ref_meth" -> v.str("ref_meth", "linear"), "ref_hgf" -> v.double("ref_hgf", 0.2),
      "ref_min_step" -> v.double("ref_min_step", 0), "ref_max_step" -> v.double("ref_max_step", 0.75),
      "upscale" -> v.bool("upscale"), "upscaler" -> v.str("upscaler", "Lanczos"),
      "upscale_ratio" -> v.double("upscale_ratio", 1), "upscale_width" -> v.int("upscale_width"),
      "upscale_height" -> v.int("upscale_height"))

  private def text(o: ujson.Obj, k: String): String = field(o, k)
  private def differs(o: ujson.Obj, k: String): Boolean = text(o, k) != Defaults(k)

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] =
    val refUsed = text(o, "sampler") == "Naive" && text(o, "ref_img").nonEmpty
    val refName = Option(text(o, "ref_img")).filter(_.nonEmpty).map(p => Paths.get(p).getFileName.toString).getOrElse("")
    Seq("Sonar sampler" -> text(o, "sampler"), "Sonar momentum" -> text(o, "momentum"),
      "Sonar momentum_hist" -> text(o, "momentum_hist")) ++
      Option.when(differs(o, "momentum_hist_init"))("Sonar momentum_hist_init" -> text(o, "momentum_hist_init")) ++
      Option.when(differs(o, "momentum_sign"))("Sonar momentum_sign" -> text(o, "momentum_sign")) ++
      (if refUsed then Seq("Sonar ref_img" -> refName, "Sonar ref_meth" -> text(o, "ref_meth"),
        "Sonar ref_hgf" -> text(o, "ref_hgf"), "Sonar ref_start_step" -> text(o, "ref_min_step"),
        "Sonar ref_stop_step" -> text(o, "ref_max_step")) else Nil) ++
      (if o.value.get("upscale").contains(ujson.Bool(true)) then
        Seq("Sonar upscaler" -> text(o, "upscaler"), "Sonar upscale_ratio" -> text(o, "upscale_ratio"),
          "Sonar upscale_width" -> text(o, "upscale_width"), "Sonar upscale_height" -> text(o, "upscale_height"))
       else Nil)

  private def choiceAxis(name: String, f: String): XyzAxis =
    XyzAxis(name, isNumeric = false)((p, s) => Hooks.setOverride(p, hook, f, ujson.Str(s.trim)))

  override def xyzAxes: Seq[XyzAxis] = Seq(
    choiceAxis("Sonar sampler", "sampler"),
    numAxis("Sonar momentum", "momentum"),
    numAxis("Sonar momentum_hist", "momentum_hist"),
    choiceAxis("Sonar momentum_sign", "momentum_sign"),
    choiceAxis("Sonar momentum_hist_init", "momentum_hist_init"),
    numAxis("Sonar ref_hgf", "ref_hgf"))
