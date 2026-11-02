package sdfx.plugins

import sdfx.core.model.{ControlNetUnit, GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.Comparator
import scala.collection.mutable.ArrayBuffer

/** Settings, the `SD-CN-Animation` infotext key and replay of the SD-CN-Animation video-to-video script. The infotext value
  * is a Python-dict spelling like the AnimateDiff one; the video is recorded by file name only (the full path would leak
  * the user's folders into shared PNGs), so a restored form needs the video chosen again.
  */
object CnAnimationPlugin:
  val Id = "cn_animation"
  val Key = "SD-CN-Animation"
  val InfotextKeys: Seq[String] = Seq(Key)
  val Step1Modes: Seq[String] = Seq("Process full image then blend in occlusions", "Inpaint occlusions")
  val RaftModels: Seq[String] = Seq("large", "small")
  val Formats: Seq[(String, String)] = Seq(("format_mp4", "MP4"), ("format_gif", "GIF"), ("format_webm", "WEBM"), ("format_webp", "WEBP"))
  val MaxFrames = 5000

  final case class Settings(video: String, maxFrames: Int, frameSkip: Int, fps: Int, firstStrength: Double, processingStrength: Double,
      fixFrameStrength: Double, step1Mode: Int, blendAlpha: Double, step1Seed: Long, step2Seed: Long, occlusionBlur: Double,
      trailing: Boolean, flowMultiplier: Double, difoMultiplier: Double, difsMultiplier: Double, raftModel: String,
      raftIters: Int, formats: Seq[String], saveFrames: Boolean)

  object Settings:
    private def fail(msg: String): Nothing = throw new IllegalArgumentException(s"SD-CN-Animation: $msg")
    private def number(v: Values, key: String, default: Double, min: Double, max: Double, whole: Boolean): Double =
      v.map.get(key).map(_.trim) match
        case None | Some("") => default
        case Some(s) =>
          val d = s.toDoubleOption.filter(x => !x.isNaN && !x.isInfinite).getOrElse(fail(s"$key must be a number (got '$s')"))
          if d < min - 1e-9 || d > max + 1e-9 then fail(s"$key must be in ${Values.fmt(min)}..${Values.fmt(max)} (got $s)")
          if whole && d != math.rint(d) then fail(s"$key must be a whole number (got $s)")
          d
    private def seed(v: Values, key: String, default: Long): Long =
      v.map.get(key).map(_.trim) match
        case None | Some("") => default
        case Some(s) =>
          val n = s.toLongOption.getOrElse(fail(s"$key must be a whole number (got '$s')"))
          if n < -1 || n > 4294967294L then fail(s"$key must be -1 (derive from the job seed) or 0..4294967294 (got $s)")
          n
    private def flag(v: Values, key: String, default: Boolean): Boolean = v.map.get(key).map(_.trim.toLowerCase) match
      case None | Some("") => default
      case Some("true") | Some("1") | Some("yes") => true
      case Some("false") | Some("0") | Some("no") => false
      case Some(s) => fail(s"$key must be true or false (got '$s')")

    def parse(v: Values): Settings =
      val mode = v.str("step_1_processing_mode", Step1Modes.head)
      val modeIndex = Step1Modes.indexOf(mode)
      if modeIndex < 0 then fail(s"step 1 processing mode must be one of ${Step1Modes.mkString(", ")} (got '$mode')")
      val raft = v.str("raft_model", "large")
      if !RaftModels.contains(raft) then fail(s"RAFT model must be one of ${RaftModels.mkString(", ")} (got '$raft')")
      val formats = Formats.collect { case (k, name) if flag(v, k, k == "format_mp4") => name }
      if formats.isEmpty then fail("choose at least one video format")
      Settings(
        video = v.str("video").trim,
        maxFrames = number(v, "max_frames", 0, 0, MaxFrames, true).toInt,
        frameSkip = number(v, "frame_skip", 0, 0, 20, true).toInt,
        fps = number(v, "fps", 0, 0, 120, true).toInt,
        firstStrength = number(v, "first_frame_strength", 0.75, 0, 1, false),
        processingStrength = number(v, "processing_strength", 0.85, 0, 1, false),
        fixFrameStrength = number(v, "fix_frame_strength", 0.15, 0, 1, false),
        step1Mode = modeIndex,
        blendAlpha = number(v, "step_1_blend_alpha", 1, 0, 1, false),
        step1Seed = seed(v, "step_1_seed", -1),
        step2Seed = seed(v, "step_2_seed", 8888),
        occlusionBlur = number(v, "occlusion_mask_blur", 3, 0, 10, false),
        trailing = flag(v, "occlusion_mask_trailing", true),
        flowMultiplier = number(v, "occlusion_mask_flow_multiplier", 5, 0, 10, false),
        difoMultiplier = number(v, "occlusion_mask_difo_multiplier", 2, 0, 10, false),
        difsMultiplier = number(v, "occlusion_mask_difs_multiplier", 0, 0, 10, false),
        raftModel = raft,
        raftIters = number(v, "raft_iters", 20, 1, 64, true).toInt,
        formats = formats,
        saveFrames = flag(v, "save_frames", false))

  /** The dict the infotext carries (the video as a file name). */
  def infotextDict(s: Settings): ujson.Obj = ujson.Obj(
    "video" -> Path.of(s.video.replace('\\', '/')).getFileName.toString, "max_frames" -> s.maxFrames, "frame_skip" -> s.frameSkip,
    "fps" -> s.fps, "first_frame_strength" -> s.firstStrength, "processing_strength" -> s.processingStrength,
    "fix_frame_strength" -> s.fixFrameStrength, "step_1_processing_mode" -> Step1Modes(s.step1Mode),
    "step_1_blend_alpha" -> s.blendAlpha, "step_1_seed" -> s.step1Seed.toDouble, "step_2_seed" -> s.step2Seed.toDouble,
    "occlusion_mask_blur" -> s.occlusionBlur, "occlusion_mask_trailing" -> s.trailing,
    "occlusion_mask_flow_multiplier" -> s.flowMultiplier, "occlusion_mask_difo_multiplier" -> s.difoMultiplier,
    "occlusion_mask_difs_multiplier" -> s.difsMultiplier, "raft_model" -> s.raftModel, "raft_iters" -> s.raftIters,
    "format" -> ujson.Arr.from(s.formats.map(ujson.Str(_))))

  def infotextValue(s: Settings): String = AnimateDiffPlugin.pyRepr(infotextDict(s))

  private val ReplayKeys = Set("video", "max_frames", "frame_skip", "fps", "first_frame_strength", "processing_strength",
    "fix_frame_strength", "step_1_processing_mode", "step_1_blend_alpha", "step_1_seed", "step_2_seed", "occlusion_mask_blur",
    "occlusion_mask_trailing", "occlusion_mask_flow_multiplier", "occlusion_mask_difo_multiplier", "occlusion_mask_difs_multiplier",
    "raft_model", "raft_iters")

  /** `Values` for the form from a frame's infotext. */
  def replay(fields: Map[String, String]): Option[Values] =
    for
      text <- fields.get(Key)
      dict <- AnimateDiffPlugin.parsePyDict(text)
      values = {
        val defaults = Values.defaults(new CnAnimationPlugin().controls).map
        val scalar = dict.value.collect {
          case (k, ujson.Num(d)) => k -> Values.fmt(d)
          case (k, ujson.Str(s)) => k -> s
          case (k, ujson.Bool(b)) => k -> b.toString
        }
        val formats = dict.value.get("format").collect { case a: ujson.Arr => a.value.collect { case ujson.Str(s) => s.toUpperCase } }
        val base = defaults ++ scalar.filter((k, _) => ReplayKeys.contains(k))
        Values(formats.fold(base)(f => base ++ Formats.map((k, name) => k -> f.contains(name).toString)))
      }
      _ <- scala.util.Try(Settings.parse(values.copy(map = values.map.updated("video", "x")))).toOption
    yield values

/** SD-CN-Animation video-to-video (volotat/SD-CN-Animation, MIT, re-implemented). A Script: every frame is a normal img2img
  * job of the host (so ControlNet, LoRAs, embeddings and the AlwaysOn plugins apply), started from the previous styled frame
  * warped by RAFT optical flow onto the next source frame, with an occlusion mask where the warp cannot be trusted; the
  * engine request `cn_animation` does the video decoding, flow, warping, histogram matching and encoding.
  */
class CnAnimationPlugin extends Plugin:
  import CnAnimationPlugin.*

  def id = Id
  def name = "SD-CN-Animation"
  def kind = PluginKind.Script
  override def requiresEngineFeatures: Seq[String] = Seq("cn_animation")
  override def description = "Video to video: restyles a video frame by frame with the current checkpoint, prompt and ControlNet units, " +
    "keeping consecutive frames consistent with RAFT optical flow and an occlusion mask."

  override def controls: Seq[Control] = Seq(
    Text("video", "Input video (file path)", "", placeholder = "C:\\videos\\clip.mp4"),
    IntField("max_frames", "Number of frames to process (0 = the whole video)", 0, MaxFrames, 0),
    IntField("frame_skip", "Frames to skip between processed frames", 0, 20, 0),
    IntField("fps", "Output FPS (0 = the video's, divided by skip + 1)", 0, 120, 0),
    Slider("first_frame_strength", "Denoising strength of the first frame", 0, 1, 0.05, 0.75),
    Slider("processing_strength", "Processing strength (Step 1)", 0, 1, 0.05, 0.85),
    Slider("fix_frame_strength", "Fix frame strength (Step 2)", 0, 1, 0.05, 0.15),
    Section("extra", "Extra params", expanded = false),
    Slider("occlusion_mask_blur", "Occlusion blur strength", 0, 10, 0.1, 3),
    Check("occlusion_mask_trailing", "Occlusion trailing (reduces ghosting, adds flicker)", defaultValue = true),
    Slider("occlusion_mask_flow_multiplier", "Occlusion flow multiplier", 0, 10, 0.1, 5),
    Slider("occlusion_mask_difo_multiplier", "Occlusion diff origin multiplier", 0, 10, 0.1, 2),
    Slider("occlusion_mask_difs_multiplier", "Occlusion diff styled multiplier", 0, 10, 0.1, 0),
    Choice("step_1_processing_mode", "Step 1 processing mode", Step1Modes, Step1Modes.head),
    Slider("step_1_blend_alpha", "Warped previous frame vs current frame blend alpha", 0, 1, 0.1, 1),
    Text("step_1_seed", "Step 1 seed (-1 = job seed + frame number)", "-1"),
    Text("step_2_seed", "Step 2 seed", "8888"),
    Choice("raft_model", "RAFT optical flow model", RaftModels, "large"),
    IntField("raft_iters", "RAFT iterations", 1, 64, 20),
    Section("output", "Output", expanded = false),
    Check("format_mp4", "MP4", defaultValue = true), Check("format_gif", "GIF"), Check("format_webm", "WEBM"), Check("format_webp", "WEBP"),
    Check("save_frames", "Save every frame to the gallery"),
    Section("info", "Info - SD-CN-Animation", expanded = false),
    Note("help_use", "Set the prompt, size, sampler, steps and any ControlNet units as usual; the init image is ignored. In ControlNet units " +
      "leave the image empty: each unit receives the current video frame. A ControlNet unit (canny / depth / softedge) keeps the result " +
      "stable; without it the video gets choppy. The seed fixes the first frame and the colours of the following ones."),
    Note("help_scope", "Not provided: text-to-video (it needs the FloweR network, which is not available), ControlNet frame feeding for " +
      "reference preprocessors, per-frame prompts, audio, custom hooks that act inside the sampler are applied to every frame as in img2img."))

  private def fail(msg: String): Nothing = throw new IllegalArgumentException(s"$name: $msg")

  private def deleteTree(dir: Path): Unit =
    if Files.isDirectory(dir) then
      val walk = Files.walk(dir)
      try walk.sorted(Comparator.reverseOrder[Path]()).forEach(p => try Files.deleteIfExists(p) catch case _: Exception => ())
      finally walk.close()

  private def str(o: ujson.Value, key: String): String = o match
    case m: ujson.Obj => m.value.get(key).collect { case ujson.Str(s) => s }.getOrElse(fail(s"the engine reply has no '$key'"))
    case _            => fail("unexpected engine reply")


  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val s = Settings.parse(v)
    if s.video.isEmpty then fail("choose an input video")
    if !Files.isRegularFile(Path.of(s.video)) then fail(s"the input video was not found: ${s.video}")
    if p0.batchSize != 1 || p0.nIter != 1 then fail("batch size and batch count must be 1")
    if p0.hires.enabled then fail("cannot be combined with Hires. fix")
    if p0.isInpaint then fail("inpainting masks are not supported; use the plain txt2img or img2img tab")
    if ctx.imageScramblingEnabled then fail("image scrambling sessions are not supported")
    ctx.requirePlaintextVideoAllowed()
    val seed = Support.fixSeed(p0.seed)
    val base = p0.copy(seed = seed, img2img = None, extra = p0.extra.updated(Key, infotextValue(s)))
    val work = Files.createDirectories(ctx.tempFile(".dir").getParent).resolve(s"cn-animation-${java.util.UUID.randomUUID()}")
    Files.createDirectories(work)
    val saved = ArrayBuffer[OutputImage]()
    try
      // An interruption ends the run with the frames saved so far (no video).
      Support.step(ctx)(runFrames(base, s, work, ctx, saved))
      JobOutput(saved.toSeq)
    finally
      try ctx.engineCall("cn_animation", ujson.Obj("operation" -> "release")) catch case _: Throwable => ()
      deleteTree(work)

  /** Unit images are the current frame; disabled units are left alone. */
  private def withFrame(p: GenerationParams, frame: String): Seq[ControlNetUnit] =
    p.controlnet.map(u => if u.enabled then u.copy(image = Some(frame)) else u)

  private def frameSeed(jobSeed: Long, explicit: Long, index: Int): Long =
    if explicit >= 0 then explicit else (jobSeed + index) % 4294967294L

  private def runFrames(base: GenerationParams, s: Settings, work: Path, ctx: PluginContext, saved: ArrayBuffer[OutputImage]): Unit =
    def call(fields: (String, ujson.Value)*): ujson.Value =
      ctx.engineCall("cn_animation", ujson.Obj.from(fields))
    val prepared = call("operation" -> "prepare", "video" -> s.video, "width" -> base.width, "height" -> base.height,
      "max_frames" -> s.maxFrames, "frame_skip" -> s.frameSkip, "output_dir" -> work.toString)
    val frames = prepared("frames").arr.map(_.str).toVector
    val total = frames.size
    val fps = if s.fps > 0 then s.fps else math.max(1, math.round(prepared("fps").num).toInt)
    if total < 2 then fail("the video needs at least two frames")
    val styledDir = Files.createDirectories(work.resolve("styled"))
    val styled = ArrayBuffer[String]()

    def img2img(init: String, strength: Double, seed: Long, mask: Option[String], frame: String): GenerationParams =
      base.copy(seed = seed, controlnet = withFrame(base, frame),
        img2img = Some(Img2ImgParams(initImage = init, mask = mask, denoisingStrength = strength, resizeMode = 0, maskBlur = 0,
          inpaintingFill = 1, inpaintFullRes = true, inpaintFullResPadding = 0)))

    def generate(p: GenerationParams): OutputImage =
      ctx.generateTransient(p).headOption.getOrElse(fail("the engine returned no image"))

    def finish(image: Path, reference: String, out: Path, alpha: Option[String] = None, warped: Option[String] = None): String =
      val fields = Seq[(String, ujson.Value)]("operation" -> "finish", "image" -> image.toString, "reference" -> reference,
        "output" -> out.toString) ++ alpha.map(a => "alpha" -> (ujson.Str(a): ujson.Value)) ++ warped.map(w => "warped" -> (ujson.Str(w): ujson.Value))
      str(call(fields*), "path")

    def keep(path: String, index: Int): Unit =
      val target = styledDir.resolve(f"styled-$index%05d.png")
      Files.copy(Path.of(path), target, StandardCopyOption.REPLACE_EXISTING)
      styled += target.toString

    var preview: Option[OutputImage] = None
    def publish(index: Int, path: String, p: GenerationParams, infotext: String): Unit =
      if index == 0 then
        val first = ctx.saveImage(ctx.readImage(Path.of(path)), "img2img", p, infotext)
        preview = Some(first)
        saved += first
      else if s.saveFrames then saved += ctx.saveImage(ctx.readImage(Path.of(path)), "img2img", p, infotext)

    ctx.status(name, 0, total)
    // Frame 0: img2img of the first source frame.
    val p0f = img2img(frames.head, s.firstStrength, base.seed, None, frames.head)
    val out0 = generate(p0f)
    val styled0 = finish(out0.path, frames.head, work.resolve("first.png"))
    Support.deleteQuietly(out0.path)
    keep(styled0, 0)
    publish(0, styled0, p0f, out0.infotext)
    val firstInfo = out0.infotext

    var alpha: Option[String] = None
    var index = 1
    while index < total && !ctx.isInterrupted do
      ctx.status(name, index, total)
      val flow = call("operation" -> "flow", "prev_frame" -> frames(index - 1), "cur_frame" -> frames(index),
        "prev_styled" -> styled.last, "output_dir" -> work.toString, "name" -> f"f$index%05d",
        "occlusion_blur" -> s.occlusionBlur, "trailing" -> s.trailing, "flow_multiplier" -> s.flowMultiplier,
        "difo_multiplier" -> s.difoMultiplier, "difs_multiplier" -> s.difsMultiplier, "blend_alpha" -> s.blendAlpha,
        "raft_model" -> s.raftModel, "raft_iters" -> s.raftIters, "prev_alpha" -> alpha.map(a => ujson.Str(a): ujson.Value).getOrElse(ujson.Null))
      val init = str(flow, "init")
      val maskPath = str(flow, "mask")
      val warped = str(flow, "warped")
      val alphaPath = str(flow, "alpha")
      val inpaint = s.step1Mode == 1
      // Inpainting an empty mask has nothing to redraw: the blended init image is the result.
      val skipStep1 = inpaint && flow("mask_max").num < 1.0 / 255
      val p1 = img2img(init, s.processingStrength, frameSeed(base.seed, s.step1Seed, index), Option.when(inpaint)(maskPath), frames(index))
      val (step1Image, info1) = if skipStep1 then (Path.of(init), firstInfo) else { val o = generate(p1); (o.path, o.infotext) }
      val step1 = finish(step1Image, frames(index), work.resolve(f"step1-$index%05d.png"), Some(alphaPath), Some(warped))
      if !skipStep1 then Support.deleteQuietly(step1Image)
      val result =
        if s.fixFrameStrength > 0 then
          val p2 = img2img(step1, s.fixFrameStrength, frameSeed(base.seed, s.step2Seed, index), None, frames(index))
          val out2 = generate(p2)
          val fixed = finish(out2.path, frames(index), work.resolve(f"step2-$index%05d.png"))
          Support.deleteQuietly(out2.path)
          publish(index, fixed, p2, out2.infotext)
          fixed
        else
          publish(index, step1, p1, info1)
          step1
      keep(result, index)
      // Only the newest mask feeds the next frame's trailing.
      alpha.foreach(a => Support.deleteQuietly(Path.of(a)))
      alpha = Some(alphaPath)
      Support.deleteQuietly(Path.of(init), Path.of(maskPath), Path.of(warped), Path.of(step1), Path.of(result))
      if index > 1 then Support.deleteQuietly(Path.of(frames(index - 2)))
      index += 1

    // Video files next to the first frame (only for a complete run).
    if index >= total then
      val first0 = preview.getOrElse(fail("no first frame was saved"))
      val stem = first0.path.getFileName.toString.replaceAll("\\.png$", "")
      val videoDir = Files.createDirectories(work.resolve("video"))
      val reply = call("operation" -> "encode", "images" -> ujson.Arr.from(styled.map(ujson.Str(_))),
        "formats" -> ujson.Arr.from(s.formats.map(ujson.Str(_))), "fps" -> fps, "name" -> stem, "output_dir" -> videoDir.toString)
      val outputs = reply("outputs").obj.value.collect { case (k, ujson.Str(p)) => k -> p }.toSeq
      if outputs.size != s.formats.size then fail(s"the engine wrote ${outputs.size} video(s), expected ${s.formats.size}")
      outputs.foreach { (format, path) =>
        Files.copy(Path.of(path), first0.path.resolveSibling(s"$stem.${format.toLowerCase}"), StandardCopyOption.REPLACE_EXISTING)
      }
