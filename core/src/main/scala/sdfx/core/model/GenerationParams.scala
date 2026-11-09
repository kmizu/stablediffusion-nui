package sdfx.core.model

import sdfx.core.json.SnakePickle.{ReadWriter => RW, *}

/** A LoRA reference extracted from `<lora:name:weight>` prompt syntax. */
/** A LoRA to apply. `weight` is the text-encoder multiplier and, unless `unet` is set, the U-Net one too (A1111
  * `<lora:name:te:unet>`). `lbw`, `start`, `stop` are LoRA Block Weight's `lbw=`, `start=`, `stop=` arguments.
  */
case class LoraRef(
    name: String,
    weight: Double = 1.0,
    unet: Option[Double] = None,
    lbw: Option[String] = None,
    start: Option[Double] = None,
    stop: Option[Double] = None,
    dyn: Option[Int] = None
) derives RW

/** Hires fix (second pass) settings. Field meaning follows A1111's hr_* parameters. */
case class HiresParams(
    enabled: Boolean = false,
    scale: Double = 2.0,
    upscaler: String = "Latent",
    secondPassSteps: Int = 0,
    resizeX: Int = 0,
    resizeY: Int = 0,
    denoisingStrength: Double = 0.55,
    samplerName: Option[String] = None,
    scheduler: Option[String] = None,
    prompt: String = "",
    negativePrompt: String = "",
    cfgScale: Option[Double] = None
) derives RW:
  /** Final second-pass size following A1111 `calculate_target_resolution`: explicit resize wins, otherwise
    * `int(w * scale)`; a single resize side keeps the aspect ratio with integer division, both sides crop to
    * exactly that size. The latent is `size // 8`, so the image is floored to a multiple of 8 (min 64).
    */
  def targetSize(width: Int, height: Int): (Int, Int) =
    def f8(v: Int) = math.max(64, v / 8 * 8)
    val w = math.max(1, width)
    val h = math.max(1, height)
    val (tx, ty) = (resizeX, resizeY) match
      case (0, 0) => ((w * scale).toInt, (h * scale).toInt)
      case (x, 0) => (x, x * h / w)
      case (0, y) => (y * w / h, y)
      case (x, y) => (x, y)
    (f8(tx), f8(ty))

/** img2img / inpaint inputs. Image paths are absolute file paths readable by the engine. */
case class Img2ImgParams(
    initImage: String,
    mask: Option[String] = None,
    denoisingStrength: Double = 0.75,
    resizeMode: Int = 0, // 0 just resize, 1 crop and resize, 2 resize and fill
    maskBlur: Int = 4,
    inpaintingFill: Int = 1, // 0 fill, 1 original, 2 latent noise, 3 latent nothing
    inpaintFullRes: Boolean = false,
    inpaintFullResPadding: Int = 32,
    inpaintingMaskInvert: Boolean = false
) derives RW

case class ControlNetUnit(
    enabled: Boolean = true,
    model: String = "",
    image: Option[String] = None,
    preprocessor: String = "none",
    weight: Double = 1.0,
    guidanceStart: Double = 0.0,
    guidanceEnd: Double = 1.0,
    preprocessorArgs: Map[String, Double] = Map.empty,
    resizeMode: Int = 1,
    // Source ControlNet processing: map interpolation, edge thinning, canny resolution and hires soft injection.
    sourceCompatibleResize: Boolean = false,
    controlMode: String = "Balanced",
    processorRes: Int = -1,
    pixelPerfect: Boolean = false
) derives RW

object ControlNetUnit:
  val ControlModes: Seq[String] = Seq("Balanced", "My prompt is more important", "ControlNet is more important")
  /** Preprocessors that apply (canny) or, like source ControlNet, ignore (none/invert) the processor resolution. */
  val ResolutionPreprocessors: Set[String] = Set("none", "", "invert", "canny")

/** Everything needed to produce one generation job. Serialized as the engine `txt2img`/`img2img` params. */
case class GenerationParams(
    prompt: String = "",
    negativePrompt: String = "",
    seed: Long = -1L,
    subseed: Long = -1L,
    subseedStrength: Double = 0.0,
    samplerName: String = "DPM++ 2M",
    scheduler: String = "Automatic",
    steps: Int = 20,
    cfgScale: Double = 7.0,
    width: Int = 512,
    height: Int = 512,
    batchSize: Int = 1,
    nIter: Int = 1,
    clipSkip: Int = 1,
    eta: Option[Double] = None,
    hires: HiresParams = HiresParams(),
    img2img: Option[Img2ImgParams] = None,
    loras: Seq[LoraRef] = Nil,
    controlnet: Seq[ControlNetUnit] = Nil,
    hooks: Map[String, ujson.Value] = Map.empty,
    styles: Seq[String] = Nil,
    checkpoint: Option[String] = None,
    vae: Option[String] = None,
    extra: Map[String, String] = Map.empty
) derives RW:
  def isImg2Img: Boolean = img2img.isDefined
  def isInpaint: Boolean = img2img.exists(_.mask.isDefined) ||
    (img2img.isDefined && hooks.get("inpaint_difference").exists { value =>
      def flag(v: ujson.Value, default: Boolean): Boolean = v match
        case ujson.Bool(b) => b
        case ujson.Num(n) => n != 0
        case ujson.Str(s) => s.trim.toLowerCase(java.util.Locale.ROOT) match
          case "enable" | "enabled" | "true" | "on" | "yes" | "1" => true
          case "disable" | "disabled" | "false" | "off" | "no" | "0" | "" => false
          case _ => default
        case _ => default
      value match
        case o: ujson.Obj => o.value.get("enabled").forall(flag(_, true))
        case b: ujson.Bool => flag(b, false)
        case s: ujson.Str => flag(s, false)
        case _ => false
    })
  def imagesPerJob: Int = batchSize * nIter

  /** Any width and height are accepted; the engine works on whole latents, so the generated size is rounded up to a multiple of 8
    * (at least 64). The infotext records the size that was actually generated. */
  def withEngineSize: GenerationParams =
    val (w, h) = (GenerationParams.engineSide(width), GenerationParams.engineSide(height))
    if (w, h) == (width, height) then this else copy(width = w, height = h)

  /** Basic validation shared by UI, MCP and engine client. Returns human readable problems. */
  def validate: List[String] =
    List(
      Option.when(steps < 1 || steps > 150)(s"steps must be 1..150 (got $steps)"),
      Option.when(width < 1 || width > GenerationParams.MaxSide)(s"width must be 1..${GenerationParams.MaxSide} (got $width)"),
      Option.when(height < 1 || height > GenerationParams.MaxSide)(s"height must be 1..${GenerationParams.MaxSide} (got $height)"),
      Option.when(batchSize < 1 || batchSize > 16)(s"batch size must be 1..16 (got $batchSize)"),
      Option.when(nIter < 1 || nIter > 100)(s"batch count must be 1..100 (got $nIter)"),
      Option.when(cfgScale < 0 || cfgScale > 30)(s"CFG scale must be 0..30 (got $cfgScale)"),
      Option.when(clipSkip < 1 || clipSkip > 12)(s"clip skip must be 1..12 (got $clipSkip)"),
      Option.when(subseedStrength < 0 || subseedStrength > 1)("variation strength must be 0..1"),
      img2img.flatMap(i => Option.when(i.denoisingStrength < 0 || i.denoisingStrength > 1)("denoising strength must be 0..1")),
      Option.when(hires.enabled && (hires.denoisingStrength < 0 || hires.denoisingStrength > 1))("hires denoising must be 0..1"),
      Option.when(controlnet.exists(u => u.enabled && !Set(0, 1, 2)(u.resizeMode)))("ControlNet resize mode must be 0..2")
    ).flatten

object GenerationParams:
  /** Upper bound that only guards against integer overflow; memory limits are the engine's business. */
  val MaxSide: Int = 65536
  /** Rounds a requested side up to a whole number of 8-pixel latent cells (at least 64). */
  def engineSide(v: Int): Int = math.max(64, (v + 7) / 8 * 8)
  val default: GenerationParams = GenerationParams()
