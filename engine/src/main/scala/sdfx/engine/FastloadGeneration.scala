package sdfx.engine

import sdfx.core.model.{ControlNetUnit, GenerationParams}
import sdfx.core.png.ImageScrambleSecret

/** Private job preparation; payload and source image bytes must not enter generation diagnostics. */
private[engine] object FastloadGeneration:
  final class Prepared(val params: GenerationParams, val units: ujson.Value, val save: Boolean):
    override def toString: String = "FastloadGeneration.Prepared([private])"

  def prepare(params: GenerationParams, plan: FastloadJobPlan, secret: Option[ImageScrambleSecret],
              worker: ujson.Obj => ujson.Value, stage: Array[Byte] => String): Prepared =
    require(secret.isEmpty || plan.mode == "load_only", "Protected sessions cannot save readable Fastload data")
    val hasActive = params.controlnet.exists(_.enabled)
    val willRead = plan.mode != "save_only" && plan.path.nonEmpty &&
      (plan.priority == "loaded_first" || !hasActive)
    val needCurrentBytes = !willRead && plan.mode != "load_only"
    val current = if needCurrentBytes then
      val items = FastloadNativeCapture.encode(params.controlnet, secret)
      worker(ujson.Obj("operation" -> "from_native", "items" -> items))("units")
    else
      // The worker needs only occupancy to decide whether current-first skips the source.
      // In particular, do not decode stale or protected inputs that a loaded unit replaces.
      ujson.Obj("type" -> "list", "items" -> ujson.Arr.from(params.controlnet.map { unit =>
        ujson.Obj("type" -> "dict", "items" -> ujson.Arr(
          ujson.Arr("enabled", ujson.Obj("type" -> "bool", "value" -> unit.enabled))))
      }))
    val result = worker(ujson.Obj("operation" -> "prepare_generation", "mode" -> plan.mode,
      "priority" -> plan.priority, "path" -> plan.path, "current" -> current, "native_forms" -> true))
    val skip = result("load_skipped").bool || plan.mode == "save_only"
    val next = if skip then params else
      val forms = result("forms").arr.toVector
      // Validate every active form before staging any input file. Disabled units retain their
      // original values in the private payload for subsequent saving and have no sampling effect.
      val decoded = forms.map { form =>
        if !form("params")("enabled").bool then None
        else Some(FastloadNativeForm.decode(form))
      }
      val units = decoded.map {
        case None => ControlNetUnit(enabled = false)
        case Some(item) => item.unit.copy(image = item.image.map(stage))
      }
      params.copy(controlnet = units)
    new Prepared(next, result("units"), result("save").bool)
