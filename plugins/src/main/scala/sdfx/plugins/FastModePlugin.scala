package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

object FastModePlugin:
  val Id = "fast_mode"
  val InfotextKey = "Fast mode"

/** One switch for the two training-free speed-ups that were measured on the scala-torch engine (docs/v0.24-verification.md):
  * DeepCache (the deep U-Net features are reused between steps) and guidance for the first part of the steps only (after that the
  * negative prompt is not computed). Built into NUI, not an A1111 extension port.
  *
  * It only fills in what the user has not set: a DeepCache or CFG Schedule plugin that is switched on keeps its own values.
  */
class FastModePlugin extends Plugin:
  import FastModePlugin.*
  def id = Id
  def name = "Fast mode"
  def kind: PluginKind = PluginKind.AlwaysOn
  override def requiresEngineFeatures: Seq[String] = Seq("deepcache", "cfg_schedule")
  override def description =
    "Faster sampling with two approximations at once: DeepCache, and CFG only for the first part of the steps. " +
    "Measured on an RTX 3090: SDXL 1024x1024 in 5.4 s instead of 7.9 s. The image changes a little (slightly softer, fewer fine " +
    "details); switch it off for a final render. DeepCache and CFG Schedule keep their own values when they are switched on."

  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Slider("guidance_until", "CFG until (fraction of the steps; 1 = all steps)", 0.3, 1, 0.05, 0.7),
    IntField("interval", "DeepCache: full step every N steps (0 = no DeepCache)", 0, 10, 3))

  override def process(p: GenerationParams, v: Values): GenerationParams =
    val until = math.max(0.0, math.min(1.0, v.double("guidance_until", 0.7)))
    val interval = v.int("interval", 3)
    val withCache =
      if interval < 2 || p.hooks.contains(DeepCachePlugin.Id) then p
      else Hooks(p, DeepCachePlugin.Id, ujson.Obj("interval" -> interval, "branch" -> 0, "full_first" -> 5))._1
    // The engine skips the negative pass exactly when the scheduled CFG is 1, so the schedule steps from the request's CFG to 1.
    val withGuidance =
      if until >= 1.0 || p.cfgScale <= 1.0 || withCache.hooks.contains("cfg_schedule") then withCache
      else Hooks(withCache, "cfg_schedule", ujson.Obj("start_cfg" -> p.cfgScale, "end_cfg" -> 1.0, "curve" -> "linear",
        "start" -> math.max(0.0, until - 0.01), "end" -> until))._1
    withGuidance.copy(extra = withGuidance.extra + (InfotextKey -> s"CFG until $until, DeepCache interval $interval"))
