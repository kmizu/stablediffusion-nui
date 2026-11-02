package sdfx.core.model

/** The controls the Extras "ControlNet Preprocessor" step shows for a module: whether it takes a processor
  * resolution and which threshold sliders (A, B) it uses, mapped to the engine `args` keys. */
object PreprocessorControls:
  /** `key` is the engine argument name; `integer` sliders are whole numbers. */
  final case class Slider(key: String, label: String, min: Double, max: Double, default: Double, step: Double, integer: Boolean = false)
  final case class Controls(resolution: Boolean, sliders: Seq[Slider])

  /** Smallest and largest processor resolution the engine accepts; anything else falls back to 512. */
  val MinResolution = 64
  val MaxResolution = 2048
  val DefaultResolution = 512

  private val Plain = Controls(resolution = false, Nil)
  private val Detector = Controls(resolution = true, Nil)

  private val table: Map[String, Controls] = Map(
    "canny" -> Controls(resolution = true, Seq(
      Slider("low_threshold", "Low threshold", 1, 255, 100, 1, integer = true),
      Slider("high_threshold", "High threshold", 1, 255, 200, 1, integer = true))),
    "recolor_luminance" -> Controls(false, Seq(Slider("gamma_correction", "Gamma correction", 0.1, 2.0, 1.0, 0.01))),
    "recolor_intensity" -> Controls(false, Seq(Slider("gamma_correction", "Gamma correction", 0.1, 2.0, 1.0, 0.01))),
    "tile_resample" -> Controls(false, Seq(Slider("down_sampling_rate", "Down sampling rate", 1.0, 8.0, 1.0, 0.1))),
    "tile_colorfix" -> Controls(false, Seq(Slider("variation", "Variation", 1, 32, 8, 1, integer = true))),
    "blur_gaussian" -> Controls(false, Seq(Slider("sigma", "Sigma", 0.1, 64.0, 9.0, 0.1))),
    "depth_marigold" -> Controls(false, Seq(Slider("steps", "Steps", 1, 20, 4, 1, integer = true))),
    "normal_marigold" -> Controls(false, Seq(Slider("steps", "Steps", 1, 20, 4, 1, integer = true))),
    "openpose" -> Detector, "lineart" -> Detector, "lineart_anime" -> Detector, "softedge_hed" -> Detector,
    "mlsd" -> Detector, "normal_bae" -> Detector
  )

  /** Modules that make sense as a standalone step: `none` and `inpaint` (which needs a mask) are left out. */
  def selectable(all: Seq[String]): Seq[String] = all.filterNot(m => m.isEmpty || m == "none" || m == "inpaint").distinct

  private val ImageExtensions = Set("png", "jpg", "jpeg", "webp", "bmp")
  /** Whether a file name is an image the batch step picks up. */
  def isImage(fileName: String): Boolean =
    val n = fileName.toLowerCase
    val i = n.lastIndexOf('.')
    i > 0 && ImageExtensions(n.substring(i + 1))

  def of(module: String): Controls = table.getOrElse(module, Plain)

  /** Defaults for every slider of the module, as engine args. */
  def defaults(module: String): Map[String, Double] = of(module).sliders.map(s => s.key -> s.default).toMap

  /** Validates caller supplied args: keys the module does not use and out-of-range values are errors. */
  def validate(module: String, args: Map[String, Double]): Either[String, Map[String, Double]] =
    val c = of(module)
    args.view.flatMap { (k, v) =>
      c.sliders.find(_.key == k) match
        case None => Some(s"argument '$k' is not used by preprocessor '$module'")
        case Some(s) if v.isNaN || v < s.min || v > s.max => Some(s"argument '$k' must be between ${s.min} and ${s.max}")
        case _ => None
    }.headOption.toLeft(args)
