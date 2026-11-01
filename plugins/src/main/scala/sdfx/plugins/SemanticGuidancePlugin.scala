package sdfx.plugins

import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

/** Infotext keys of v0xie/sd-webui-semantic-guidance, so its PNGs restore and NUI PNGs paste back into it. */
object SemanticGuidancePlugin:
  private val Numbers = Seq(
    ("SEGA Warmup Period", "warmup", 0.0, 1000.0),
    ("SEGA Edit Guidance Scale", "edit_guidance_scale", 0.0, 1000.0),
    ("SEGA Tail Percentage Threshold", "tail_percentage_threshold", 0.0, 1.0),
    ("SEGA Momentum Scale", "momentum_scale", -1000.0, 1000.0),
    ("SEGA Momentum Beta", "momentum_beta", -1000.0, 1000.0))

  def replay(fields: Map[String, String]): Option[Values] =
    if !fields.get("SEGA Active").exists(_.equalsIgnoreCase("true")) then Some(Values(Map("enabled" -> "false")))
    else
      val numbers = Numbers.map { (label, key, low, high) =>
        fields.get(label).map(v => v.toDoubleOption.filter(n => n.isFinite && n >= low && n <= high).map(n => key -> Values.fmt(n)))
      }
      if numbers.exists(_.contains(None)) then None
      else Some(Values(numbers.flatten.flatten.toMap ++ Map(
        "enabled" -> "true",
        "prompt" -> fields.getOrElse("SEGA Prompt", ""),
        "negative_prompt" -> fields.getOrElse("SEGA Negative Prompt", ""))))

/** Semantic Guidance (v0xie/sd-webui-semantic-guidance): edits the prompt conditioning towards or away from concepts. */
class SemanticGuidancePlugin extends HookPlugin:
  def id = "semantic_guidance"
  def name = "Semantic Guidance"
  def hook = "semantic_guidance"
  override def description =
    "Adds the comma-separated concepts in its prompt to the image, or removes those in its negative prompt, by editing " +
    "the prompt conditioning after the warmup steps. Composition usually stays the same."
  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Text("prompt", "Prompt", multiline = true, placeholder = "concepts to add, separated by commas"),
    Text("negative_prompt", "Negative prompt", multiline = true, placeholder = "concepts to remove, separated by commas"),
    Slider("warmup", "Warmup period (steps)", 0, 30, 1, 10),
    Slider("edit_guidance_scale", "Edit guidance scale", 0, 20, 0.01, 1.0),
    Slider("tail_percentage_threshold", "Tail percentage threshold", 0, 1, 0.01, 0.05),
    Slider("momentum_scale", "Momentum scale", 0, 1, 0.01, 0.3),
    Slider("momentum_beta", "Momentum beta", 0, 0.999, 0.01, 0.6)
  )
  def hookFields(v: Values): ujson.Obj = ujson.Obj(
    "prompt" -> v.str("prompt"), "negative_prompt" -> v.str("negative_prompt"),
    "warmup" -> v.int("warmup", 10), "edit_guidance_scale" -> v.double("edit_guidance_scale", 1.0),
    "tail_percentage_threshold" -> v.double("tail_percentage_threshold", 0.05),
    "momentum_scale" -> v.double("momentum_scale", 0.3), "momentum_beta" -> v.double("momentum_beta", 0.6))
  def infotextKeys(o: ujson.Obj): Seq[(String, String)] =
    def text(k: String) = o.value.get(k).collect { case ujson.Str(s) => s }.getOrElse("")
    Seq("SEGA Active" -> "True", "SEGA Prompt" -> text("prompt"), "SEGA Negative Prompt" -> text("negative_prompt"),
      "SEGA Warmup Period" -> field(o, "warmup"), "SEGA Edit Guidance Scale" -> field(o, "edit_guidance_scale"),
      "SEGA Tail Percentage Threshold" -> field(o, "tail_percentage_threshold"),
      "SEGA Momentum Scale" -> field(o, "momentum_scale"), "SEGA Momentum Beta" -> field(o, "momentum_beta"))
  override def xyzAxes: Seq[XyzAxis] = Seq(
    XyzAxis("[Semantic Guidance] Prompt")((p, s) => Hooks.setOverride(p, hook, "prompt", ujson.Str(s.trim))),
    XyzAxis("[Semantic Guidance] Negative Prompt")((p, s) => Hooks.setOverride(p, hook, "negative_prompt", ujson.Str(s.trim))),
    numAxis("[Semantic Guidance] Warmup Steps", "warmup"),
    numAxis("[Semantic Guidance] Guidance Scale", "edit_guidance_scale"),
    numAxis("[Semantic Guidance] Tail Percentage Threshold", "tail_percentage_threshold"),
    numAxis("[Semantic Guidance] Momentum Scale", "momentum_scale"),
    numAxis("[Semantic Guidance] Momentum Beta", "momentum_beta"))
