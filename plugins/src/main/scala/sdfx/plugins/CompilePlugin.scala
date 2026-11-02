package sdfx.plugins

import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

object CompilePlugin:
  val Id = "compile"
  val Backends: Seq[String] = Seq("Inductor", "TensorRT")
  val Modes: Seq[String] = Seq("default", "max-autotune-no-cudagraphs", "reduce-overhead")
  val BackendKey = "Compile backend"
  val ModeKey = "Compile mode"
  val BlockKey = "Compile min block size"
  val LevelKey = "Compile optimization level"
  val InfotextKeys: Seq[String] = Seq(BackendKey, ModeKey, BlockKey, LevelKey)

  private def inRange(raw: String, low: Int, high: Int): Option[String] =
    raw.trim.toIntOption.filter(n => n >= low && n <= high).map(_.toString)

  /** PNG restore: `Compile backend` enables the plugin; any other PNG switches it off. Unknown values give `None`. */
  def replay(fields: Map[String, String]): Option[Values] =
    if !fields.contains(BackendKey) then Some(Values(Map("enabled" -> "false")))
    else
      val backend = Backends.find(_.equalsIgnoreCase(fields(BackendKey).trim))
      val mode = fields.get(ModeKey).map(m => Modes.find(_ == m.trim))
      val block = fields.get(BlockKey).map(inRange(_, 1, 64))
      val level = fields.get(LevelKey).map(inRange(_, 0, 5))
      if backend.isEmpty || mode.contains(None) || block.contains(None) || level.contains(None) then None
      else
        val base = Values.defaults(new CompilePlugin().controls).map
        Some(Values(base ++ Map("enabled" -> "true", "backend" -> backend.get) ++ mode.flatten.map("mode" -> _) ++
          block.flatten.map("min_block_size" -> _) ++ level.flatten.map("optimization_level" -> _)))

/** U-Net compilation (NVIDIA/Stable-Diffusion-WebUI-TensorRT, index 236): TensorRT engines through Torch-TensorRT,
  * or torch.compile with Inductor. Engines are built lazily by the first generation at a resolution and batch size.
  */
class CompilePlugin extends HookPlugin:
  import CompilePlugin.*
  def id = Id
  def name = "Compile (TensorRT / Inductor)"
  def hook = Id
  override def description =
    "Speeds up the U-Net. TensorRT builds a static engine per resolution and batch size (the first generation at a " +
    "new size takes minutes; engines are cached on disk), Inductor compiles with torch.compile (the first step compiles). " +
    "Needs the optional packages torch-tensorrt + tensorrt, or triton + a C compiler. TensorRT cannot use LoRAs; " +
    "neither backend combines with ControlNet, offload VRAM modes or hooks that patch the U-Net."

  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Choice("backend", "Backend", Backends, "Inductor"),
    Choice("mode", "Inductor mode", Modes, "default"),
    IntField("min_block_size", "TensorRT minimum block size", 1, 64, 5),
    IntField("optimization_level", "TensorRT builder optimization level", 0, 5, 3),
    Check("cache_engines", "Cache TensorRT engines on disk", true))

  def hookFields(v: Values): ujson.Obj =
    ujson.Obj("backend" -> v.str("backend", "Inductor").trim.toLowerCase, "mode" -> v.str("mode", "default").trim,
      "min_block_size" -> v.int("min_block_size", 5), "optimization_level" -> v.int("optimization_level", 3),
      "cache_engines" -> v.bool("cache_engines", true))

  private def text(o: ujson.Obj, k: String): String = o.value.get(k).collect { case ujson.Str(s) => s }.getOrElse("")

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] =
    if text(o, "backend") == "tensorrt" then
      Seq(BackendKey -> "TensorRT", BlockKey -> field(o, "min_block_size"), LevelKey -> field(o, "optimization_level"))
    else Seq(BackendKey -> "Inductor", ModeKey -> text(o, "mode"))
