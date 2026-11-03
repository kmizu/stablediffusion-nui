package sdfx.plugins

import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

/** Settings and infotext of the `batch_face_swap` target (kex0/batch-face-swap, index 59, pinned
  * 942ebae6937d9ad7964f285a790e7721f712b9ec, no licence: re-implemented from the README only).
  *
  * The upstream writes no infotext that this port knows of, so NUI writes one key, `Batch Face Swap`, holding the
  * detector and every setting that differs from the defaults as compact JSON; PNG restore and MCP
  * `inspect_generation_metadata` read it back. The engine side is python/sdfx/batch_face_swap.py (the face pass runs
  * through the ADetailer inpaint path).
  */
object BatchFaceSwapPlugin:
  val InfotextKey = "Batch Face Swap"
  val Detectors: Seq[String] = Seq("face_yolov8n.pt", "face_yolov8s.pt", "face_yolov8m.pt", "face_yolov9c.pt", "opencv_face", "mediapipe_face")
  val Shapes: Seq[String] = Seq("ellipse", "box")

  /** Engine default of each hook field, in hook order. */
  private val Defaults: Seq[(String, ujson.Value)] = Seq(
    "detector" -> ujson.Str("face_yolov8n.pt"), "confidence" -> ujson.Num(0.3), "max_faces" -> ujson.Num(0),
    "mask_shape" -> ujson.Str("ellipse"), "mask_size" -> ujson.Num(1.2), "mask_blur" -> ujson.Num(8),
    "denoising_strength" -> ujson.Num(0.5), "padding" -> ujson.Num(32), "resolution" -> ujson.Num(0),
    "prompt" -> ujson.Str(""), "negative_prompt" -> ujson.Str(""), "steps" -> ujson.Num(0), "cfg_scale" -> ujson.Num(0),
    "sampler" -> ujson.Str(""), "color_correction" -> ujson.Bool(true), "checkpoint" -> ujson.Str(""),
    "checkpoint_hash" -> ujson.Str(""))
  private val DefaultMap = Defaults.toMap

  /** (low, high, integer) of the numeric fields, as the engine validates them. */
  private val Ranges: Map[String, (Double, Double, Boolean)] = Map(
    "confidence" -> (0.0, 1.0, false), "max_faces" -> (0.0, 64.0, true), "mask_size" -> (0.3, 3.0, false),
    "mask_blur" -> (0.0, 64.0, true), "denoising_strength" -> (0.0, 1.0, false), "padding" -> (0.0, 512.0, true),
    "resolution" -> (0.0, 2048.0, true), "steps" -> (0.0, 150.0, true), "cfg_scale" -> (0.0, 30.0, false))

  /** Infotext value: the detector and the settings that differ from the defaults. */
  def encode(o: ujson.Obj): String =
    val keep = Defaults.filterNot(_._1 == "checkpoint_hash").flatMap((k, d) => o.value.get(k).filter(v => k == "detector" || v != d).map(k -> _))
    ujson.Obj.from(keep).render()

  private def restoredValue(k: String, v: ujson.Value): Option[String] = (DefaultMap.get(k), v) match
    case (Some(_: ujson.Num), ujson.Num(n)) =>
      Ranges.get(k).filter((lo, hi, int) => n.isFinite && n >= lo && n <= hi && (!int || n == math.rint(n)) && !(k == "resolution" && n != 0 && n < 64))
        .map(_ => Values.fmt(n))
    case (Some(_: ujson.Bool), ujson.Bool(b)) => Some(b.toString)
    case (Some(_: ujson.Str), ujson.Str(s)) =>
      if k == "checkpoint_hash" then Option.when(s.isEmpty || s.matches("(?i)[0-9a-f]{10}"))(s.toLowerCase(java.util.Locale.ROOT))
      else if k == "mask_shape" then Option.when(Shapes.contains(s))(s)
      else if k == "detector" then Option.when(s.trim.nonEmpty && !s.trim.equalsIgnoreCase("none") && s.length <= 512)(s)
      else Option.when(s.length <= 8192)(s)
    case _ => None

  /** PNG restore: the key switches the plugin on with the stored settings; any other PNG switches it off. Unknown
    * fields or values the engine would reject restore nothing (`None`). */
  def replay(fields: Map[String, String]): Option[Values] =
    fields.get(InfotextKey) match
      case None => Some(Values(Map("enabled" -> "false")))
      case Some(raw) =>
        scala.util.Try(ujson.read(raw)).toOption.collect { case o: ujson.Obj if raw.length <= 16384 && !o.value.contains("checkpoint_hash") &&
          fields.get("Face checkpoint hash").forall(_.matches("(?i)[0-9a-f]{10}")) => o }.flatMap { o =>
          val restored = (o.value.toSeq ++ fields.get("Face checkpoint hash").map(s => "checkpoint_hash" -> ujson.Str(s))).map((k, v) => k -> restoredValue(k, v))
          if restored.exists(_._2.isEmpty) then None
          else
            val base = Values.defaults(new BatchFaceSwapPlugin().controls).map
            val custom = restored.toMap.collect { case (k, Some(v)) => k -> v }
            // a detector outside the list goes to the custom-model box
            val detector = custom.get("detector")
            val detectorFields = detector.map(d => if Detectors.contains(d) then Map("detector" -> d, "custom_detector" -> "")
              else Map("custom_detector" -> d)).getOrElse(Map.empty)
            Option.when(!custom.get("checkpoint_hash").exists(_.nonEmpty) || custom.get("checkpoint").exists(_.trim.nonEmpty))(
              Values(base ++ (custom - "detector") ++ detectorFields + ("enabled" -> "true")))
        }

/** Detects every face of each finished image and redraws it (Batch Face Swap, kex0/batch-face-swap). */
class BatchFaceSwapPlugin extends HookPlugin:
  import BatchFaceSwapPlugin.*
  def id = "batch_face_swap"
  def name = "Batch Face Swap"
  def hook = "batch_face_swap"
  override def description =
    "Detects the faces of every finished image and redraws each one, after ADetailer. The face pass can use its own " +
      "prompt, negative prompt, steps, CFG scale and sampler (the overrides affect only the faces), a face area " +
      "resolution (the magnification of the redrawn crop) and an elliptical or rectangular mask scaled around each " +
      "face. [PROMPT] in the face prompt inserts the main prompt and [SEP] gives each face (left to right) its own. " +
      "YOLO face models download from Bingsu/adetailer on first use; opencv_face needs no download but finds few " +
      "anime faces. mediapipe_face needs the optional MediaPipe package and downloads the short-range BlazeFace model " +
      "on first use. The face pass can use a separate installed checkpoint."

  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Choice("detector", "Face detector", Detectors, "face_yolov8n.pt"),
    Text("custom_detector", "Custom detector (path or Bingsu/adetailer file name, overrides the list)"),
    Slider("confidence", "Detection confidence", 0, 1, 0.01, 0.3),
    IntField("max_faces", "Redraw only the k largest faces (0 = all)", 0, 64, 0),
    Slider("denoising_strength", "Face denoising strength", 0, 1, 0.01, 0.5),
    Text("prompt", "Face prompt", multiline = true, placeholder = "Empty = main prompt. [PROMPT] inserts it, [SEP] splits per face"),
    Text("negative_prompt", "Face negative prompt", multiline = true, placeholder = "Empty = main negative prompt"),
    Section("mask", "Mask and face area", expanded = false),
    Choice("mask_shape", "Mask shape", Shapes, "ellipse"),
    Slider("mask_size", "Mask size (times the detected face box)", 0.3, 3, 0.05, 1.2),
    IntField("mask_blur", "Mask blur", 0, 64, 8),
    IntField("padding", "Crop padding around the mask (pixels)", 0, 512, 32),
    IntField("resolution", "Face area resolution (0 = image size; try 1024 for SDXL)", 0, 2048, 0),
    Check("color_correction", "Match colour and contrast of the redrawn face to the original", true),
    Section("overrides", "Overrides for the face pass", expanded = false),
    DynamicChoice("checkpoint", "Face checkpoint (none = current model)", ChoiceSource.Checkpoints, "", allowNone = true),
    Text("checkpoint_hash", "Expected face checkpoint hash (clear when changing model)", ""),
    IntField("steps", "Steps (0 = main steps)", 0, 150, 0),
    Slider("cfg_scale", "CFG scale (0 = main CFG)", 0, 30, 0.5, 0),
    DynamicChoice("sampler", "Sampler (none = main sampler)", ChoiceSource.Samplers, "", allowNone = true))

  def hookFields(v: Values): ujson.Obj =
    val custom = v.str("custom_detector").trim
    val detector = if custom.nonEmpty then custom else v.str("detector", "face_yolov8n.pt")
    val sampler = v.str("sampler").trim
    val checkpoint = v.str("checkpoint").trim
    ujson.Obj(
      "detector" -> detector,
      "confidence" -> v.double("confidence", 0.3), "max_faces" -> v.int("max_faces", 0),
      "mask_shape" -> v.str("mask_shape", "ellipse"), "mask_size" -> v.double("mask_size", 1.2),
      "mask_blur" -> v.int("mask_blur", 8), "denoising_strength" -> v.double("denoising_strength", 0.5),
      "padding" -> v.int("padding", 32), "resolution" -> v.int("resolution", 0),
      "prompt" -> v.str("prompt"), "negative_prompt" -> v.str("negative_prompt"),
      "steps" -> v.int("steps", 0), "cfg_scale" -> v.double("cfg_scale", 0),
      "sampler" -> (if sampler.equalsIgnoreCase("none") then "" else sampler),
      "checkpoint" -> (if checkpoint.equalsIgnoreCase("none") then "" else checkpoint),
      "checkpoint_hash" -> v.str("checkpoint_hash").trim.toLowerCase(java.util.Locale.ROOT),
      "color_correction" -> v.bool("color_correction", true))

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] = Seq(InfotextKey -> encode(o))

  override def xyzAxes: Seq[XyzAxis] = Seq(
    numAxis("Batch Face Swap denoising strength", "denoising_strength"),
    numAxis("Batch Face Swap confidence", "confidence"),
    numAxis("Batch Face Swap mask size", "mask_size"),
    numAxis("Batch Face Swap mask blur", "mask_blur"),
    XyzAxis("Batch Face Swap prompt")((p, s) => Hooks.setOverride(p, hook, "prompt", ujson.Str(s.trim))))
