package sdfx.plugins

import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

object DeepCachePlugin:
  val Id = "deepcache"
  val IntervalKey = "DeepCache interval"
  val BranchKey = "DeepCache branch"
  val FirstKey = "DeepCache full first"
  val InfotextKeys: Seq[String] = Seq(IntervalKey, BranchKey, FirstKey)

  private def inRange(raw: String, low: Int, high: Int): Option[String] =
    raw.trim.toIntOption.filter(n => n >= low && n <= high).map(_.toString)

  /** PNG restore: `DeepCache interval` enables the plugin; any other PNG switches it off. Out-of-range values give `None`. */
  def replay(fields: Map[String, String]): Option[Values] =
    if !fields.contains(IntervalKey) then Some(Values(Map("enabled" -> "false")))
    else
      val interval = inRange(fields(IntervalKey), 2, 10)
      val branch = fields.get(BranchKey).map(inRange(_, 0, 3))
      val first = fields.get(FirstKey).map(inRange(_, 0, 50))
      if interval.isEmpty || branch.contains(None) || first.contains(None) then None
      else
        val base = Values.defaults(new DeepCachePlugin().controls).map
        Some(Values(base ++ Map("enabled" -> "true", "interval" -> interval.get) ++ branch.flatten.map("branch" -> _) ++
          first.flatten.map("full_first" -> _)))

/** DeepCache for the U-Net: recompute only the shallow blocks on most steps and reuse the deep features recorded by the
  * last full step. Built into the engine (low-VRAM / speed options, #143), not an A1111 extension port.
  */
class DeepCachePlugin extends HookPlugin:
  import DeepCachePlugin.*
  def id = Id
  def name = "DeepCache"
  def hook = Id
  override def description =
    "Faster sampling by reusing the U-Net's deep features between neighbouring steps (a full step every N steps). " +
    "An approximation: the image differs a little from the exact one (see docs/optimization-verification.md). " +
    "Needs the GPU VRAM mode; ControlNet, Composable LoRA and hooks that patch the U-Net are refused."

  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    IntField("interval", "Full step every N steps", 2, 10, 3),
    IntField("branch", "Cache depth (0 = shallowest, fastest)", 0, 3, 0),
    IntField("full_first", "Full steps at the start (they decide the composition)", 0, 50, 5))

  def hookFields(v: Values): ujson.Obj =
    ujson.Obj("interval" -> v.int("interval", 3), "branch" -> v.int("branch", 0), "full_first" -> v.int("full_first", 5))

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] =
    Seq(IntervalKey -> field(o, "interval"), BranchKey -> field(o, "branch"), FirstKey -> field(o, "full_first"))
