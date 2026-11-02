package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.protocol.{EngineError, Protocol}
import sdfx.plugins.imageops.ImageOps

import java.awt.image.BufferedImage
import java.awt.Color
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO
import scala.collection.mutable.ArrayBuffer

/** Deterministic PluginContext: "generates" solid colour images derived from the params (img2img may echo the init
  * image) and records every call. Interrupts are simulated like production: after `interruptAfter` successful
  * generations the next `generate` (or `upscale`) throws `EngineError(interrupted)` and only then does
  * `isInterrupted` become true (mid-generation interrupt).
  */
class FakeContext(val interruptAfter: Int = Int.MaxValue, val echoInit: Boolean = false) extends PluginContext:
  val dir: Path = Files.createTempDirectory("sdfx-plugins-test")
  private val gen = ArrayBuffer[GenerationParams]()
  def generated: Seq[GenerationParams] = gen.toSeq
  private val sv = ArrayBuffer[(String, BufferedImage, GenerationParams, String)]()
  def saved: Seq[(String, BufferedImage, GenerationParams, String)] = sv.toSeq
  /** Snapshot of each img2img call's init image and mask (plugins may delete their temp files afterwards). */
  val inputs = ArrayBuffer[(BufferedImage, Option[BufferedImage])]()
  /** Every output file path ever returned by generate. */
  val outputPaths = ArrayBuffer[Path]()
  val upscales = ArrayBuffer[(Path, String, Double)]()
  val logs = ArrayBuffer[String]()
  private var n = 0
  private var nextRandom = 1000L

  def colorOf(p: GenerationParams, seed: Long): Int =
    (seed, p.steps, p.cfgScale, p.prompt, p.negativePrompt, p.checkpoint, p.samplerName).hashCode & 0xffffff

  private def write(img: BufferedImage, sub: String): Path =
    val d = dir.resolve(sub)
    Files.createDirectories(d)
    n += 1
    val f = d.resolve(f"$n%05d.png")
    ImageIO.write(img, "png", f.toFile)
    f

  def solid(w: Int, h: Int, rgb: Int): BufferedImage =
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setColor(new Color(rgb))
    g.fillRect(0, 0, w, h)
    g.dispose()
    img

  private var interrupted = false
  var interruptedCalls = 0

  private def interruptNow(): Nothing =
    interrupted = true
    interruptedCalls += 1
    throw EngineError(Protocol.ErrorCodes.Interrupted, "interrupted")

  /** Transient generations (not user outputs): recorded separately, files go to `transient/`. */
  private val trans = ArrayBuffer[GenerationParams]()
  def transientCalls: Seq[GenerationParams] = trans.toSeq
  val transientPaths = ArrayBuffer[Path]()

  /** Generations of any kind (regular + transient); the interrupt counter uses this. */
  def attempted: Int = gen.size + trans.size

  def generate(p: GenerationParams): Seq[OutputImage] =
    if interrupted || attempted >= interruptAfter then interruptNow()
    gen += p
    produce(p, if p.isImg2Img then "img2img" else "txt2img", outputPaths)

  override def generateTransient(p: GenerationParams): Seq[OutputImage] =
    if interrupted || attempted >= interruptAfter then interruptNow()
    trans += p
    produce(p, "transient", transientPaths)

  private def produce(p: GenerationParams, sub: String, record: ArrayBuffer[Path]): Seq[OutputImage] =
    p.img2img.foreach(ii => inputs += ((ImageIO.read(new java.io.File(ii.initImage)), ii.mask.map(m => ImageIO.read(new java.io.File(m))))))
    val base = if p.seed < 0 then { nextRandom += 1; nextRandom } else p.seed
    val frames = p.hooks.get("animatediff").flatMap(_.obj.get("video_length")).map(_.num.toInt)
    (0 until frames.fold(p.imagesPerJob)(_ * p.nIter)).map { i =>
      val seed = base + i
      val img = p.img2img match
        case Some(ii) if echoInit =>
          val src = ImageIO.read(new java.io.File(ii.initImage))
          if src.getWidth == p.width && src.getHeight == p.height then src else ImageOps.resize(src, p.width, p.height)
        case _ => solid(p.width, p.height, colorOf(p, seed))
      val path = write(img, sub)
      record += path
      OutputImage(path, p, s"${p.prompt}\nSeed: $seed", seed)
    }

  def upscale(image: Path, upscaler: String, scale: Double): Path =
    if interrupted then interruptNow()
    upscales += ((image, upscaler, scale))
    val src = ImageIO.read(image.toFile)
    write(ImageOps.resize(src, math.round(src.getWidth * scale).toInt, math.round(src.getHeight * scale).toInt), "upscaled")

  def saveImage(img: BufferedImage, kind: String, params: GenerationParams, infotext: String): OutputImage =
    sv += ((kind, img, params, infotext))
    OutputImage(write(img, kind), params, infotext, params.seed)

  def tempFile(suffix: String): Path = Files.createTempFile(dir, "tmp", suffix)

  def choices(source: ChoiceSource): Seq[String] = source match
    case ChoiceSource.Samplers    => Seq("Euler a", "Euler", "DPM++ 2M", "DPM++ 2M SDE")
    case ChoiceSource.Schedulers  => Seq("Automatic", "Karras", "Exponential")
    case ChoiceSource.Checkpoints => Seq("modelA.safetensors", "modelB.safetensors")
    case ChoiceSource.Upscalers   => Seq("None", "Lanczos", "R-ESRGAN 4x+")
    case _                        => Nil

  /** Recorded non-generation engine calls (kind, params) and fake but well-formed replies. */
  val engineCalls = ArrayBuffer[(String, ujson.Obj)]()
  /** The fake `cn_animation` request: number of decoded frames and the largest occlusion value reported by `flow`. */
  var cnFrames = 4
  var cnMaskMax = 0.5

  override def engineCall(kind: String, params: ujson.Obj): ujson.Value =
    if interrupted then interruptNow()
    engineCalls += ((kind, params))
    def src = ImageIO.read(new java.io.File(params("image_path").str))
    kind match
      case "restore_faces" | "novel_view" => ujson.Obj("path" -> write(src, "engine").toString)
      case "caption"   => ujson.Obj("caption" -> "a fake caption")
      case "run_code"  => ujson.Obj("stdout" -> "hello", "result" -> "42", "images" -> ujson.Arr(write(solid(8, 8, 0xff0000), "engine").toString))
      case "img2vid" =>
        val prev = write(src, "engine")
        val gif = prev.resolveSibling("clip.gif")
        Files.writeString(gif, "GIF89a")
        ujson.Obj("path" -> gif.toString, "frames" -> 4, "preview" -> prev.toString)
      case "animatediff_encode" =>
        val out = ujson.Obj()
        params("formats").arr.map(_.str).foreach { f =>
          val file = dir.resolve(s"video-${params("name").str}.${f.toLowerCase}")
          Files.writeString(file, s"$f frames=${params("images").arr.size} fps=${params("fps").num.toInt}")
          out(f) = file.toString
        }
        ujson.Obj("outputs" -> out, "frames" -> params("images").arr.size)
      case "cn_animation" =>
        def out(name: String) = Path.of(params("output_dir").str).resolve(name)
        params("operation").str match
          case "prepare" =>
            val frames = (0 until cnFrames).map { i =>
              val f = out(f"source-$i%05d.png")
              Files.createDirectories(f.getParent)
              ImageIO.write(solid(params("width").num.toInt, params("height").num.toInt, 0x101010 * (i + 1)), "png", f.toFile)
              ujson.Str(f.toString)
            }
            ujson.Obj("frames" -> ujson.Arr.from(frames), "fps" -> 8.0, "source_fps" -> 8.0, "source_frames" -> cnFrames)
          case "flow" =>
            val stem = params("name").str
            val cur = ImageIO.read(new java.io.File(params("cur_frame").str))
            val files = Seq("init", "mask", "warped").map { k =>
              val f = out(s"$stem-$k.png")
              ImageIO.write(cur, "png", f.toFile)
              k -> ujson.Str(f.toString)
            }
            val alpha = out(s"$stem-alpha.npy")
            Files.writeString(alpha, "npy")
            ujson.Obj.from(files ++ Seq("alpha" -> ujson.Str(alpha.toString), "mask_mean" -> ujson.Num(0.1), "mask_max" -> ujson.Num(cnMaskMax)))
          case "finish" =>
            val target = Path.of(params("output").str)
            Files.createDirectories(target.getParent)
            Files.copy(Path.of(params("image").str), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            ujson.Obj("path" -> target.toString)
          case "encode" =>
            val outs = ujson.Obj()
            params("formats").arr.map(_.str).foreach { f =>
              val file = out(s"video-${params("name").str}.${f.toLowerCase}")
              Files.createDirectories(file.getParent)
              Files.writeString(file, s"$f frames=${params("images").arr.size} fps=${params("fps").num.toInt}")
              outs(f) = file.toString
            }
            ujson.Obj("outputs" -> outs, "frames" -> params("images").arr.size)
          case "release" => ujson.Obj("released" -> true)
          case other => throw new UnsupportedOperationException(other)
      case other => throw new UnsupportedOperationException(other)

  val loadedCheckpoints = ArrayBuffer[(String, Option[String])]()
  override def loadCheckpoint(name: String, vae: Option[String]): Unit = loadedCheckpoints += ((name, vae))

  /** Catalog stand-in: modelA is SD1.5, modelB (the "loaded" model) is SDXL, anything else is unknown. */
  override def checkpointInfo(name: Option[String]): Option[CheckpointInfo] = name match
    case None | Some("modelB.safetensors") => Some(CheckpointInfo("modelB.safetensors", "sdxl"))
    case Some("modelA.safetensors")        => Some(CheckpointInfo("modelA.safetensors", "sd15"))
    case Some("modelC.safetensors")        => Some(CheckpointInfo("modelC.safetensors", "sd15"))
    case _                                 => None

  override def modelPath(source: ChoiceSource, name: String): Option[String] =
    Option.when(name.nonEmpty && name != "missing")(s"/models/${source.toString}/$name")

  def log(message: String): Unit = logs += message
  def status(text: String, done: Int, total: Int): Unit = ()
  def isInterrupted: Boolean = interrupted

  /** Writes a solid init image and returns its path. */
  def initImage(w: Int, h: Int, rgb: Int = 0x336699): String = write(solid(w, h, rgb), "init").toString

  def gradientInit(w: Int, h: Int): String =
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until h; x <- 0 until w do img.setRGB(x, y, ((x * 255 / w) << 16) | ((y * 255 / h) << 8) | 0x40)
    write(img, "init").toString
