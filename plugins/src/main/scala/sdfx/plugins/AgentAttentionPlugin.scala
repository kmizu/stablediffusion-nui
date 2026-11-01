package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

/** Infotext keys of v0xie/sd-webui-agentattention, so its PNGs restore and NUI PNGs paste back into it. */
object AgentAttentionPlugin:
  val ActiveKey = "AgAt Active"
  private val Bools = Seq(
    ("AgAt Use Second Pass", "use_sp"),
    ("AgAt Use FP32 Precision", "use_fp32"),
    ("AgAt Apply to Hires. Fix Only", "hires_fix_only"))
  private val Ints = Seq(
    ("AgAt Second Pass Step", "sp_step", 0), ("AgAt First Pass sx", "sx", 0), ("AgAt First Pass sy", "sy", 0),
    ("AgAt Second Pass sx", "sp_sx", 0), ("AgAt Second Pass sy", "sp_sy", 0), ("AgAt Max Downsample", "max_downsample", 1))
  private val Ratios = Seq(
    ("AgAt First Pass Ratio", "ratio"), ("AgAt First Pass Agent Ratio", "agent_ratio"),
    ("AgAt Second Pass Ratio", "sp_ratio"), ("AgAt Second Pass Agent Ratio", "sp_agent_ratio"))
  /** A1111's infotext key pattern excludes ".", so "AgAt Apply to Hires. Fix Only: x" parses as "Fix Only: x" (also
    * in A1111, where the source therefore never restores it). Replay accepts both spellings.
    */
  val ParsedHiresKey = "Fix Only"
  val InfotextKeys: Seq[String] = ActiveKey +: (Bools.map(_._1) ++ Ints.map(_._1) ++ Ratios.map(_._1)) :+ ParsedHiresKey

  /** Like the source, the plugin is active whenever `AgAt Active` is present; missing keys take the defaults. */
  def replay(fields: Map[String, String]): Option[Values] =
    if !fields.contains(ActiveKey) then Some(Values(Map("enabled" -> "false")))
    else
      val bools = Bools.map { (label, key) =>
        fields.get(label).orElse(Option.when(key == "hires_fix_only")(fields.get(ParsedHiresKey)).flatten)
          .map(v => v.trim.toLowerCase(java.util.Locale.ROOT) match
          case "true" => Some(key -> "true")
          case "false" => Some(key -> "false")
          case _ => None)
      }
      val ints = Ints.map { (label, key, low) =>
        fields.get(label).map(v => v.trim.toDoubleOption.filter(n => n.isFinite && n == math.rint(n) && n >= low)
          .map(n => key -> Values.fmt(n)))
      }
      val ratios = Ratios.map { (label, key) =>
        fields.get(label).map(v => v.trim.toDoubleOption.filter(n => n.isFinite && n >= 0 && n <= 1).map(n => key -> Values.fmt(n)))
      }
      val parsed = bools ++ ints ++ ratios
      if parsed.exists(_.contains(None)) then None
      else Some(Values(Values.defaults(new AgentAttentionPlugin().controls).map ++ parsed.flatten.flatten.toMap ++
        Map("enabled" -> "true")))

/** Agent Attention (v0xie/sd-webui-agentattention, AgentSD): merges similar self-attention tokens and attends
  * through agent tokens.
  */
class AgentAttentionPlugin extends HookPlugin:
  def id = "agent_attention"
  def name = "Agent Attention"
  def hook = "agent_attention"
  override def description =
    "Merges similar tokens in the U-Net self-attention and attends through a few agent tokens (AgentSD), for speed " +
    "and often better detail. The first-pass settings apply until Second Pass Step; there the patch stops, or " +
    "switches to the second-pass settings with Use Second Pass. SDXL needs Max Downsample above 1. Cannot combine " +
    "with PAG, SAG, HyperTile, Token Downsampling, Reference-only or ControlNet-LLLite."
  override def controls: Seq[Control] = Seq(
    Check("enabled", "Active"),
    Check("hires_fix_only", "Apply to Hires. Fix Only"),
    Check("use_fp32", "Use FP32 Precision (for SD2.1)"),
    Check("use_sp", "Use Second Pass"),
    Slider("sp_step", "Second Pass Step", 0, 100, 1, 20),
    Choice("max_downsample", "Max Downsample (for SDXL set to values > 1)", Seq("1", "2", "4", "8"), "1",
      allowCustom = true),
    Section("first_pass", "First Pass"),
    Slider("sx", "sx", 0, 10, 1, 4),
    Slider("sy", "sy", 0, 10, 1, 4),
    Slider("ratio", "Ratio", 0, 1, 0.01, 0.4),
    Slider("agent_ratio", "Agent Ratio", 0, 1, 0.01, 0.95),
    Section("second_pass", "Second Pass"),
    Slider("sp_sx", "sx", 0, 10, 1, 2),
    Slider("sp_sy", "sy", 0, 10, 1, 2),
    Slider("sp_ratio", "Ratio", 0, 1, 0.01, 0.4),
    Slider("sp_agent_ratio", "Agent Ratio", 0, 1, 0.01, 0.5))
  def hookFields(v: Values): ujson.Obj = ujson.Obj(
    "active" -> true,
    "use_sp" -> v.bool("use_sp"), "sp_step" -> v.int("sp_step", 20),
    "sx" -> v.int("sx", 4), "sy" -> v.int("sy", 4), "ratio" -> v.double("ratio", 0.4),
    "agent_ratio" -> v.double("agent_ratio", 0.95),
    "sp_sx" -> v.int("sp_sx", 2), "sp_sy" -> v.int("sp_sy", 2), "sp_ratio" -> v.double("sp_ratio", 0.4),
    "sp_agent_ratio" -> v.double("sp_agent_ratio", 0.5),
    "use_fp32" -> v.bool("use_fp32"), "max_downsample" -> v.int("max_downsample", 1),
    "hires_fix_only" -> v.bool("hires_fix_only"))
  def infotextKeys(o: ujson.Obj): Seq[(String, String)] = Seq(
    "AgAt Active" -> "True",
    "AgAt Use Second Pass" -> field(o, "use_sp"),
    "AgAt Second Pass Step" -> field(o, "sp_step"),
    "AgAt First Pass sx" -> field(o, "sx"),
    "AgAt First Pass sy" -> field(o, "sy"),
    "AgAt First Pass Ratio" -> field(o, "ratio"),
    "AgAt First Pass Agent Ratio" -> field(o, "agent_ratio"),
    "AgAt Second Pass sx" -> field(o, "sp_sx"),
    "AgAt Second Pass sy" -> field(o, "sp_sy"),
    "AgAt Second Pass Ratio" -> field(o, "sp_ratio"),
    "AgAt Second Pass Agent Ratio" -> field(o, "sp_agent_ratio"),
    "AgAt Use FP32 Precision" -> field(o, "use_fp32"),
    "AgAt Max Downsample" -> field(o, "max_downsample"),
    "AgAt Apply to Hires. Fix Only" -> field(o, "hires_fix_only"))

  /** The `[AgentAttention] Active` axis can switch the plugin off; `active` itself never reaches the engine. */
  override def process(p: GenerationParams, v: Values): GenerationParams =
    val (p2, merged) = Hooks(p, hook, hookFields(v))
    if merged.value.get("active").contains(ujson.Bool(false)) then p2.copy(hooks = p2.hooks.removed(hook))
    else
      val fields = ujson.Obj.from(merged.value.toSeq.filterNot(_._1 == "active"))
      p2.copy(hooks = p2.hooks.updated(hook, fields), extra = p2.extra ++ infotextKeys(fields))

  private def boolAxis(axisName: String, f: String): XyzAxis =
    // The source's boolean axes compare the lowercased value with "true".
    XyzAxis(axisName)((p, s) => Hooks.setOverride(p, hook, f, ujson.Bool(s.trim.equalsIgnoreCase("true"))))

  override def xyzAxes: Seq[XyzAxis] = Seq(
    boolAxis("[AgentAttention] Active", "active"),
    boolAxis("[AgentAttention] Use Second Pass", "use_sp"),
    numAxis("[AgentAttention] Second Pass Step", "sp_step"),
    numAxis("[AgentAttention] First Pass sx", "sx"),
    numAxis("[AgentAttention] First Pass sy", "sy"),
    numAxis("[AgentAttention] First Pass Ratio", "ratio"),
    numAxis("[AgentAttention] First Pass Agent Ratio", "agent_ratio"),
    numAxis("[AgentAttention] Second Pass sx", "sp_sx"),
    numAxis("[AgentAttention] Second Pass sy", "sp_sy"),
    numAxis("[AgentAttention] Second Pass Ratio", "sp_ratio"),
    numAxis("[AgentAttention] Second Pass Agent Ratio", "sp_agent_ratio"),
    boolAxis("[AgentAttention] Use FP32", "use_fp32"),
    numAxis("[AgentAttention] Max Downsample", "max_downsample"))
