package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

/** Settings and infotext keys of v0xie/sd-webui-incantations, so its PNGs restore and NUI PNGs paste back into it. */
object IncantationsPlugin:
  val Schedules: Seq[String] = Seq("Constant", "Clamp-Linear (c=4.0)", "Clamp-Linear (c=2.0)", "Clamp-Linear (c=1.0)",
    "Linear", "Inverse-Linear", "Cosine", "Clamp-Cosine (c=4.0)", "Clamp-Cosine (c=2.0)", "Clamp-Cosine (c=1.0)",
    "Sine", "Interval", "PCS (s=0.01)", "PCS (s=0.1)", "PCS (s=1.0)", "PCS (s=2.0)", "PCS (s=4.0)")

  private enum Kind:
    case Bool, Int, Num, Str

  /** (infotext key, hook field, kind, lowest accepted value) in the source's write order per submodule. */
  private val Seg = Seq(("SEG Blur Sigma", "seg_blur_sigma", Kind.Num, Double.NegativeInfinity),
    ("SEG Start Step", "seg_start_step", Kind.Int, Double.NegativeInfinity),
    ("SEG End Step", "seg_end_step", Kind.Int, Double.NegativeInfinity))
  private val Scfg = Seq(("SCFG Scale", "scfg_scale", Kind.Num, Double.NegativeInfinity),
    ("SCFG Rate Min", "scfg_rate_min", Kind.Num, Double.NegativeInfinity),
    ("SCFG Rate Max", "scfg_rate_max", Kind.Num, Double.NegativeInfinity),
    ("SCFG Rate Clamp", "scfg_rate_clamp", Kind.Num, Double.NegativeInfinity),
    ("SCFG Start Step", "scfg_start_step", Kind.Int, Double.NegativeInfinity),
    ("SCFG End Step", "scfg_end_step", Kind.Int, Double.NegativeInfinity),
    ("SCFG R", "scfg_r", Kind.Int, 1.0))
  private val Pag = Seq(("PAG SANF", "pag_sanf", Kind.Bool, 0.0),
    ("PAG Scale", "pag_scale", Kind.Num, Double.NegativeInfinity),
    ("PAG Start Step", "pag_start_step", Kind.Int, Double.NegativeInfinity),
    ("PAG End Step", "pag_end_step", Kind.Int, Double.NegativeInfinity))
  private val Interval = Seq(("CFG Interval Schedule", "cfg_interval_schedule", Kind.Str, 0.0),
    ("CFG Interval Low", "cfg_interval_low", Kind.Num, Double.NegativeInfinity),
    ("CFG Interval High", "cfg_interval_high", Kind.Num, Double.NegativeInfinity))
  private val T2i0 = Seq(("T2I-0 Window Size", "t2i0_window_size", Kind.Int, 0.0),
    ("T2I-0 Step Start", "t2i0_step_start", Kind.Int, Double.NegativeInfinity),
    ("T2I-0 Step End", "t2i0_step_end", Kind.Int, Double.NegativeInfinity),
    ("T2I-0 CbS Score Threshold", "t2i0_correction_threshold", Kind.Num, Double.NegativeInfinity),
    ("T2I-0 CbS Correction Strength", "t2i0_correction_strength", Kind.Num, Double.NegativeInfinity),
    ("T2I-0 CTNMS Alpha", "t2i0_ctnms_alpha", Kind.Num, Double.NegativeInfinity),
    ("T2I-0 CTNMS EMA Smoothing Factor", "t2i0_ema_factor", Kind.Num, Double.NegativeInfinity),
    ("T2I-0 Tokens", "t2i0_tokens", Kind.Str, 0.0))

  /** Submodule (active key, active hook field, value keys). The source restores each Active box from key presence. */
  private val Groups = Seq(("SEG Active", "seg_active", Seg), ("SCFG Active", "scfg_active", Scfg),
    ("PAG Active", "pag_active", Pag), ("CFG Interval Enable", "cfg_interval_enable", Interval),
    ("T2I-0 Active", "t2i0_active", T2i0))
  val ActiveKeys: Seq[String] = Groups.map(_._1)
  val InfotextKeys: Seq[String] = Groups.flatMap((key, _, rows) => key +: rows.map(_._1))

  private def parse(kind: Kind, low: Double, raw: String): Option[String] =
    val v = raw.trim
    kind match
      case Kind.Bool => v.toLowerCase(java.util.Locale.ROOT) match
        case "true" => Some("true")
        case "false" => Some("false")
        case _ => None
      case Kind.Str => Some(raw)
      case Kind.Int => v.toDoubleOption.filter(n => n.isFinite && n == math.rint(n) && n >= low).map(Values.fmt)
      case Kind.Num => v.toDoubleOption.filter(n => n.isFinite && n >= low).map(Values.fmt)

  /** Restores from the source's keys; other PNGs switch the plugin off. Missing keys take the defaults. Unlike the
    * source's paste (which ticks SANF whenever `PAG SANF` is present, also for `False`), SANF follows its value.
    */
  def replay(fields: Map[String, String]): Option[Values] =
    val active = Groups.map { (key, field, _) =>
      val on = if key == "CFG Interval Enable" then fields.get(key).exists(_.trim.equalsIgnoreCase("true"))
               else fields.contains(key)
      field -> on
    }
    if !active.exists(_._2) then Some(Values(Map("enabled" -> "false")))
    else
      val parsed = Groups.flatMap(_._3).map { (label, field, kind, low) =>
        fields.get(label).map(raw => parse(kind, low, raw).map(field -> _))
      }
      val schedule = fields.get("CFG Interval Schedule").forall(s => Schedules.contains(s.trim))
      if parsed.exists(_.contains(None)) || !schedule then None
      else
        val values = parsed.flatten.flatten.toMap.map {
          case ("cfg_interval_schedule", s) => "cfg_interval_schedule" -> s.trim
          case other => other
        }
        Some(Values(Values.defaults(new IncantationsPlugin().controls).map ++ values ++
          active.map((f, on) => f -> on.toString) ++ Map("enabled" -> "true")))

/** Incantations (v0xie/sd-webui-incantations): Smoothed Energy Guidance, S-CFG, Perturbed Attention Guidance with
  * the CFG Scheduler, and Multi-Concept T2I-Zero. Seek for Incantations is not provided.
  */
class IncantationsPlugin extends HookPlugin:
  import IncantationsPlugin.*
  def id = "incantations"
  def name = "Incantations"
  def hook = "incantations"
  override def description =
    "Guidance techniques from v0xie's Incantations; each section has its own Active box. Smoothed Energy Guidance " +
    "blurs the middle-block self-attention query of the unconditional prediction (keep CFG near 3, adjust the " +
    "sigma). S-CFG rescales CFG per semantic region (very memory hungry). Perturbed Attention Guidance adds " +
    "guidance from an identity self-attention pass; the CFG Scheduler changes CFG over the steps inside a noise " +
    "interval. Multi-Concept T2I-Zero corrects the prompt embedding (CbS) and suppresses mixing between tokens " +
    "(CTNMS). SEG, S-CFG, PAG and the scheduler need CFG > 1. Cannot combine with native PAG, SAG, Reference-only " +
    "or Neutral Prompt / Composable LoRA branches; PAG, S-CFG and the scheduler also not with other CFG rewrites."

  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Section("seg", "Smoothed Energy Guidance", expanded = false),
    Check("seg_active", "Active"),
    Slider("seg_blur_sigma", "SEG Blur Sigma", 0, 11, 0.5, 11.0),
    Slider("seg_start_step", "Start Step", 0, 150, 1, 0),
    Slider("seg_end_step", "End Step", 0, 150, 1, 150),
    Section("scfg", "S-CFG", expanded = false),
    Check("scfg_active", "Active"),
    Slider("scfg_scale", "SCFG Scale", 0, 10, 0.1, 1.0),
    Slider("scfg_r", "SCFG R", 1, 16, 1, 4),
    Slider("scfg_rate_min", "Min Rate", 0, 30, 0.1, 0.8),
    Slider("scfg_rate_max", "Max Rate", 0, 30, 0.1, 3.0),
    Slider("scfg_rate_clamp", "Clamp Rate", 0, 30, 0.1, 0.0),
    Slider("scfg_start_step", "Start Step", 0, 150, 1, 0),
    Slider("scfg_end_step", "End Step", 0, 150, 1, 150),
    Section("pag", "Perturbed Attention Guidance", expanded = false),
    Check("pag_active", "Active"),
    Check("pag_sanf", "Use Saliency-Adaptive Noise Fusion"),
    Slider("pag_scale", "PAG Scale", 0, 20, 0.5, 0.0),
    Slider("pag_start_step", "Start Step", 0, 150, 1, 0),
    Slider("pag_end_step", "End Step", 0, 150, 1, 150),
    Section("cfg_scheduler", "CFG Scheduler", expanded = false),
    Check("cfg_interval_enable", "Enable CFG Scheduler"),
    Choice("cfg_interval_schedule", "CFG Schedule Type", Schedules, "Constant"),
    Slider("cfg_interval_low", "CFG Noise Interval Low", 0, 100, 0.1, 0.0),
    Slider("cfg_interval_high", "CFG Noise Interval High", 0, 100, 0.1, 100.0),
    Section("t2i0", "Multi-Concept T2I-Zero", expanded = false),
    Check("t2i0_active", "Active"),
    Slider("t2i0_step_start", "Step Start", 0, 150, 1, 1),
    Slider("t2i0_step_end", "Step End", 0, 150, 1, 25),
    Text("t2i0_tokens", "Tokens", placeholder = "token indices to condition on, e.g. 1,4 (empty = all tokens)"),
    Slider("t2i0_window_size", "Correction by Similarities Window Size", 0, 100, 1, 2),
    Slider("t2i0_correction_threshold", "CbS Score Threshold", 0, 1, 0.01, 0.5),
    Slider("t2i0_correction_strength", "CbS Correction Strength", 0, 2, 0.01, 0.0),
    Slider("t2i0_ctnms_alpha", "Alpha for Cross-Token Non-Maximum Suppression", 0, 1, 0.01, 0.1),
    Slider("t2i0_ema_factor", "EMA Smoothing Factor", 0, 4, 0.01, 0.0),
    Note("seek_note", "Seek for Incantations (prompt rewriting from CLIP/BLIP or Deepbooru interrogation) is not " +
      "provided."))

  private val boolFields = Set("seg_active", "scfg_active", "pag_active", "pag_sanf", "cfg_interval_enable", "t2i0_active")
  private val strFields = Set("cfg_interval_schedule", "t2i0_tokens")
  private val intFields = Set("seg_start_step", "seg_end_step", "scfg_start_step", "scfg_end_step", "scfg_r",
    "pag_start_step", "pag_end_step", "t2i0_step_start", "t2i0_step_end", "t2i0_window_size")

  def hookFields(v: Values): ujson.Obj =
    val defaults = Values.defaults(controls)
    ujson.Obj.from(controls.collect { case c if c.hasValue && c.key != "enabled" => c.key }.map { k =>
      val value: ujson.Value =
        if boolFields(k) then ujson.Bool(v.bool(k))
        else if strFields(k) then ujson.Str(v.str(k, defaults.str(k)))
        else if intFields(k) then ujson.Num(v.int(k, defaults.int(k)))
        else ujson.Num(v.double(k, defaults.double(k)))
      k -> value
    })

  private def on(o: ujson.Obj, k: String): Boolean = o.value.get(k).contains(ujson.Bool(true))
  private def text(o: ujson.Obj, k: String): String = o.value.get(k).map(Hooks.num).getOrElse("")

  /** The keys each submodule writes when it runs (SEG skips itself at Blur Sigma 0). The engine likewise does
    * nothing until a submodule is active.
    */
  def infotextKeys(o: ujson.Obj): Seq[(String, String)] =
    def group(active: String, rows: Seq[(String, String, ?, ?)]) = (active -> "True") +: rows.map(r => r._1 -> text(o, r._2))
    val seg = on(o, "seg_active") && o.value.get("seg_blur_sigma").forall(_ != ujson.Num(0))
    (if seg then group("SEG Active", Seg) else Nil) ++
      (if on(o, "scfg_active") then group("SCFG Active", Scfg) else Nil) ++
      (if on(o, "pag_active") then group("PAG Active", Pag) else Nil) ++
      (if on(o, "cfg_interval_enable") then group("CFG Interval Enable", Interval) else Nil) ++
      (if on(o, "t2i0_active") then group("T2I-0 Active", T2i0) else Nil)

  private def overridden(p: GenerationParams, f: String): Boolean =
    p.hooks.get(Hooks.overridesKey(hook)).collect { case o: ujson.Obj => o.value.contains(f) }.getOrElse(false)

  /** The source's axes switch their submodule on unless an earlier axis already set its Active value. */
  private def axis(name: String, f: String, activate: Seq[String], value: String => ujson.Value, numeric: Boolean): XyzAxis =
    XyzAxis(name, isNumeric = numeric) { (p, s) =>
      activate.foldLeft(Hooks.setOverride(p, hook, f, value(s))) { (q, a) =>
        if overridden(q, a) then q else Hooks.setOverride(q, hook, a, ujson.Bool(true))
      }
    }
  private def bool(s: String): ujson.Value = ujson.Bool(s.trim.equalsIgnoreCase("true"))
  private def flag(name: String, f: String, activate: String*) = axis(name, f, activate, bool, numeric = false)
  private def number(name: String, f: String, activate: String*) = axis(name, f, activate, Hooks.numValue, numeric = true)

  override def xyzAxes: Seq[XyzAxis] = Seq(
    flag("[SEG] Active", "seg_active"),
    number("[SEG] SEG Blur Sigma", "seg_blur_sigma", "seg_active"),
    number("[SEG] SEG Start Step", "seg_start_step", "seg_active"),
    number("[SEG] SEG End Step", "seg_end_step", "seg_active"),
    flag("[SCFG] Active", "scfg_active"),
    number("[SCFG] SCFG Scale", "scfg_scale", "scfg_active"),
    number("[SCFG] SCFG Rate Min", "scfg_rate_min", "scfg_active"),
    number("[SCFG] SCFG Rate Max", "scfg_rate_max", "scfg_active"),
    number("[SCFG] SCFG Rate Clamp", "scfg_rate_clamp", "scfg_active"),
    number("[SCFG] SCFG Start Step", "scfg_start_step", "scfg_active"),
    number("[SCFG] SCFG End Step", "scfg_end_step", "scfg_active"),
    number("[SCFG] SCFG R", "scfg_r", "scfg_active"),
    flag("[PAG] Active", "pag_active"),
    flag("[PAG] SANF", "pag_sanf", "pag_active"),
    number("[PAG] PAG Scale", "pag_scale", "pag_active"),
    number("[PAG] PAG Start Step", "pag_start_step", "pag_active"),
    number("[PAG] PAG End Step", "pag_end_step", "pag_active"),
    flag("[PAG] Enable CFG Scheduler", "cfg_interval_enable", "pag_active"),
    number("[PAG] CFG Noise Interval Low", "cfg_interval_low", "pag_active"),
    number("[PAG] CFG Noise Interval High", "cfg_interval_high", "pag_active"),
    axis("[PAG] CFG Schedule Type", "cfg_interval_schedule", Seq("pag_active", "cfg_interval_enable"),
      s => ujson.Str(s.trim), numeric = false),
    flag("[T2I-0] Active", "t2i0_active"),
    number("[T2I-0] Step Start", "t2i0_step_start", "t2i0_active"),
    number("[T2I-0] Step End", "t2i0_step_end", "t2i0_active"),
    number("[T2I-0] CbS Window Size", "t2i0_window_size", "t2i0_active"),
    number("[T2I-0] CbS Score Threshold", "t2i0_correction_threshold", "t2i0_active"),
    number("[T2I-0] CbS Correction Strength", "t2i0_correction_strength", "t2i0_active"),
    number("[T2I-0] CTNMS Alpha", "t2i0_ctnms_alpha", "t2i0_active"),
    number("[T2I-0] CTNMS EMA Smoothing Factor", "t2i0_ema_factor", "t2i0_active"))
