package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.collection.mutable.ArrayBuffer

/** Settings, engine hook values and the `AnimateDiff` infotext key of the AnimateDiff script. The infotext value is the
  * Python-dict spelling the extension writes (`AnimateDiff: "{'model': 'mm_sd15_v3.safetensors', 'video_length': 16, ...}"`),
  * so a frame of this app restores in the extension's form and the reverse.
  */
object AnimateDiffPlugin:
  val Id = "animatediff"
  val Key = "AnimateDiff"
  val InfotextKeys: Seq[String] = Seq(Key)
  val ClosedLoops: Seq[String] = Seq("N", "R-P", "R+P", "A")
  val Formats: Seq[(String, String)] = Seq(("format_gif", "GIF"), ("format_mp4", "MP4"), ("format_webp", "WEBP"),
    ("format_webm", "WEBM"), ("format_png", "PNG"), ("format_txt", "TXT"))
  val VideoFormats: Set[String] = Set("GIF", "MP4", "WEBP", "WEBM")
  val FreeInitFilters: Seq[String] = Seq("butterworth", "gaussian", "box", "ideal")
  val MaxFrames = 512
  val MaxContext = 32

  final case class Settings(model: String, videoLength: Int, fps: Int, loopNumber: Int, closedLoop: String, batchSize: Int,
      stride: Int, overlap: Int, latentPower: Double, latentScale: Double, lastFrame: String, latentPowerLast: Double,
      latentScaleLast: Double, freeInit: Boolean, freeInitFilter: String, freeInitDs: Double, freeInitDt: Double,
      freeInitN: Int, freeInitIterations: Int, linearBetas: Boolean, formats: Seq[String]):
    /** The number of frames in one video (0 = one context batch, as the extension). */
    def frames: Int = if videoLength == 0 then batchSize else videoLength
    def videoFormats: Seq[String] = formats.filter(VideoFormats.contains)

  object Settings:
    private def fail(msg: String): Nothing = throw new IllegalArgumentException(s"AnimateDiff: $msg")
    private def number(v: Values, key: String, default: Double, min: Double, max: Double, whole: Boolean): Double =
      v.map.get(key).map(_.trim) match
        case None | Some("") => default
        case Some(s) =>
          val d = s.toDoubleOption.filter(x => !x.isNaN && !x.isInfinite).getOrElse(fail(s"$key must be a number (got '$s')"))
          if d < min - 1e-9 || d > max + 1e-9 then fail(s"$key must be in ${Values.fmt(min)}..${Values.fmt(max)} (got $s)")
          if whole && d != math.rint(d) then fail(s"$key must be a whole number (got $s)")
          d
    private def flag(v: Values, key: String, default: Boolean): Boolean = v.map.get(key).map(_.trim.toLowerCase) match
      case None | Some("") => default
      case Some("true") | Some("1") | Some("yes") => true
      case Some("false") | Some("0") | Some("no") => false
      case Some(s) => fail(s"$key must be true or false (got '$s')")

    def parse(v: Values): Settings =
      val closed = v.str("closed_loop", "R+P")
      if !ClosedLoops.contains(closed) then fail(s"closed loop must be one of ${ClosedLoops.mkString(", ")} (got '$closed')")
      val filter = v.str("free_init_filter", "butterworth").toLowerCase
      if !FreeInitFilters.contains(filter) then fail(s"FreeInit filter must be one of ${FreeInitFilters.mkString(", ")} (got '$filter')")
      val formats = Formats.collect { case (k, name) if flag(v, k, k == "format_gif") => name }
      if formats.isEmpty then fail("choose at least one save format")
      Settings(
        model = v.str("model").trim,
        videoLength = number(v, "video_length", 16, 0, MaxFrames, true).toInt,
        fps = number(v, "fps", 8, 1, 120, true).toInt,
        loopNumber = number(v, "loop_number", 0, 0, 1000, true).toInt,
        closedLoop = closed,
        batchSize = number(v, "batch_size", 16, 1, MaxContext, true).toInt,
        stride = number(v, "stride", 1, 1, 16, true).toInt,
        overlap = number(v, "overlap", -1, -1, MaxContext - 1, true).toInt,
        latentPower = number(v, "latent_power", 1, 0, 100, false),
        latentScale = number(v, "latent_scale", 32, 1e-3, 1e6, false),
        lastFrame = v.str("last_frame").trim,
        latentPowerLast = number(v, "latent_power_last", 1, 0, 100, false),
        latentScaleLast = number(v, "latent_scale_last", 32, 1e-3, 1e6, false),
        freeInit = flag(v, "free_init", false),
        freeInitFilter = filter,
        freeInitDs = number(v, "free_init_d_s", 0.25, 0.01, 1, false),
        freeInitDt = number(v, "free_init_d_t", 0.25, 0.01, 1, false),
        freeInitN = number(v, "free_init_n", 4, 1, 16, true).toInt,
        freeInitIterations = number(v, "free_init_iterations", 3, 1, 10, true).toInt,
        linearBetas = flag(v, "linear_betas", true),
        formats = formats)

  /** The engine hook (`params.hooks.animatediff`). */
  def hookFields(s: Settings): ujson.Obj =
    val o = ujson.Obj("model" -> s.model, "video_length" -> s.frames, "fps" -> s.fps, "loop_number" -> s.loopNumber,
      "closed_loop" -> s.closedLoop, "batch_size" -> s.batchSize, "stride" -> s.stride, "overlap" -> s.overlap,
      "interp" -> "Off", "interp_x" -> 10, "latent_power" -> s.latentPower, "latent_scale" -> s.latentScale,
      "latent_power_last" -> s.latentPowerLast, "latent_scale_last" -> s.latentScaleLast, "linear_betas" -> s.linearBetas)
    if s.lastFrame.nonEmpty then o("last_frame") = s.lastFrame
    if s.freeInit then
      o("free_init") = true
      o("free_init_filter") = s.freeInitFilter
      o("free_init_d_s") = s.freeInitDs
      o("free_init_d_t") = s.freeInitDt
      o("free_init_n") = s.freeInitN
      o("free_init_iterations") = s.freeInitIterations
    o

  /** The dict the infotext carries: the hook values plus the save formats. */
  def infotextDict(s: Settings): ujson.Obj =
    val o = hookFields(s)
    o("format") = ujson.Arr.from(s.formats.map(ujson.Str(_)))
    o

  private def pyString(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

  /** Python repr of a JSON value (`{'a': 1, 'b': True}`). */
  def pyRepr(v: ujson.Value): String = v match
    case ujson.Str(s)  => pyString(s)
    case ujson.Bool(b) => if b then "True" else "False"
    case ujson.Num(d)  => Values.fmt(d)
    case ujson.Null    => "None"
    case a: ujson.Arr  => a.value.map(pyRepr).mkString("[", ", ", "]")
    case o: ujson.Obj  => o.value.map((k, x) => s"${pyString(k)}: ${pyRepr(x)}").mkString("{", ", ", "}")

  /** Parses the Python-dict spelling back into JSON; None for anything that is not a flat dict of simple values. */
  def parsePyDict(text: String): Option[ujson.Obj] =
    val t = text.trim
    if !(t.startsWith("{") && t.endsWith("}")) then None
    else
      val out = new StringBuilder
      var i = 0
      var ok = true
      while ok && i < t.length do
        val c = t.charAt(i)
        if c == '\'' || c == '"' then
          val quote = c
          val sb = new StringBuilder
          i += 1
          var closed = false
          while !closed && i < t.length do
            val d = t.charAt(i)
            if d == '\\' && i + 1 < t.length then
              sb.append(t.charAt(i + 1)); i += 2
            else if d == quote then
              closed = true; i += 1
            else
              sb.append(d); i += 1
          if !closed then ok = false else out.append(ujson.write(ujson.Str(sb.toString)))
        else if c.isLetter then
          val start = i
          while i < t.length && t.charAt(i).isLetter do i += 1
          t.substring(start, i) match
            case "True"  => out.append("true")
            case "False" => out.append("false")
            case "None"  => out.append("null")
            case _       => ok = false
        else
          out.append(c); i += 1
      if !ok then None
      else
        try ujson.read(out.toString) match
          case o: ujson.Obj => Some(o)
          case _            => None
        catch case _: Exception => None

  def infotextValue(s: Settings): String = pyRepr(infotextDict(s))

  /** `Values` for the form from a frame's infotext (this app's or the extension's). */
  def replay(fields: Map[String, String]): Option[Values] =
    for
      text <- fields.get(Key)
      dict <- parsePyDict(text)
      values = {
        val defaults = Values.defaults(new AnimateDiffPlugin().controls).map
        val scalar = dict.value.collect {
          case (k, ujson.Num(d)) => k -> Values.fmt(d)
          case (k, ujson.Str(s)) => k -> s
          case (k, ujson.Bool(b)) => k -> b.toString
        }
        val formats = dict.value.get("format").collect { case a: ujson.Arr => a.value.collect { case ujson.Str(s) => s.toUpperCase } }
        val keys = Set("model", "video_length", "fps", "loop_number", "closed_loop", "batch_size", "stride", "overlap",
          "latent_power", "latent_scale", "last_frame", "latent_power_last", "latent_scale_last", "free_init", "free_init_filter",
          "free_init_d_s", "free_init_d_t", "free_init_n", "free_init_iterations", "linear_betas")
        val base = defaults ++ scalar.filter((k, _) => keys.contains(k))
        Values(formats.fold(base)(f => base ++ Formats.map((k, name) => k -> f.contains(name).toString)))
      }
      _ <- scala.util.Try(Settings.parse(values)).toOption
    yield values

/** AnimateDiff (continue-revolution/sd-webui-animatediff, CC BY-NC-SA 4.0), re-implemented from its documentation. A
  * Script: it generates the frames through the engine's `animatediff` hook (motion modules inserted into the loaded
  * checkpoint's UNet), saves the first frame in the gallery with the full infotext, and writes the selected videos next
  * to it. The frames come from one transient generation, so AlwaysOn plugins, LoRAs and embeddings apply as usual.
  */
class AnimateDiffPlugin extends Plugin:
  import AnimateDiffPlugin.*

  def id = Id
  def name = "AnimateDiff"
  def kind = PluginKind.Script
  override def requiresEngineFeatures: Seq[String] = Seq("animatediff")
  override def description = "Generates a video (GIF / MP4 / WEBP / WEBM and frames) with an AnimateDiff motion module " +
    "(SD 1.x or SDXL). The module is inserted into the loaded checkpoint at run time, so the checkpoint is never reloaded."

  override def controls: Seq[Control] = Seq(
    Text("model", "Motion module", "", placeholder = "mm_sd15_v3.safetensors, a diffusers folder, or e.g. guoyww/animatediff-motion-adapter-v1-5-2"),
    IntField("video_length", "Number of frames (0 = context batch size)", 0, MaxFrames, 16),
    IntField("fps", "FPS", 1, 120, 8),
    IntField("loop_number", "Display loop number (0 = forever)", 0, 1000, 0),
    IntField("batch_size", "Context batch size", 1, MaxContext, 16),
    Choice("closed_loop", "Closed loop", ClosedLoops, "R+P"),
    IntField("stride", "Stride", 1, 16, 1),
    IntField("overlap", "Overlap (-1 = context batch size / 4)", -1, MaxContext - 1, -1),
    Check("format_gif", "GIF", defaultValue = true), Check("format_mp4", "MP4"), Check("format_webp", "WEBP"),
    Check("format_webm", "WEBM"), Check("format_png", "PNG (save every frame to the gallery)"),
    Check("format_txt", "TXT (infotext file next to the video)"),
    Check("linear_betas", "Linear beta schedule (what the motion modules were trained with; off = the checkpoint's own, which looks grey)", defaultValue = true),
    Section("img2vid", "Img2Vid (img2img tab)", expanded = false),
    Slider("latent_power", "Latent power", 0, 10, 0.1, 1),
    Slider("latent_scale", "Latent scale", 1, 256, 1, 32),
    ImageFile("last_frame", "Optional last frame"),
    Slider("latent_power_last", "Optional latent power for last frame", 0, 10, 0.1, 1),
    Slider("latent_scale_last", "Optional latent scale for last frame", 1, 256, 1, 32),
    Section("freeinit", "FreeInit (txt2img)", expanded = false),
    Check("free_init", "Enable FreeInit"),
    Choice("free_init_filter", "Filter", FreeInitFilters, "butterworth"),
    Slider("free_init_d_s", "Spatial cutoff d_s", 0.01, 1, 0.01, 0.25),
    Slider("free_init_d_t", "Temporal cutoff d_t", 0.01, 1, 0.01, 0.25),
    IntField("free_init_n", "Butterworth order n", 1, 16, 4),
    IntField("free_init_iterations", "Iterations", 1, 10, 3),
    Section("info", "Info - AnimateDiff", expanded = false),
    Note("help_prompt", "Prompt travel: optional head lines, then `frame: prompt` lines in ascending order (frame numbers below the number of frames), " +
      "then optional tail lines. Each keyframe prompt is head + keyframe + tail; the conditioning is interpolated between keyframes."),
    Note("help_context", "More frames than the context batch size run as overlapping windows through the motion module. Closed loop N: no wrap-around; " +
      "R-P / R+P: one wrap-around window (R+P also loops the prompt travel); A: wrap-around windows around the whole ring and, for a video that fits one context, " +
      "the reversed frames are appended to the video. Stride adds windows of frames 2^i apart."),
    Note("help_scope", "Not provided: frame interpolation (FILM), ControlNet V2V / SparseCtrl / IP-Adapter prompt travel, Motion LoRAs, Hires. fix with a video, batch size above 1."))

  private def fail(msg: String): Nothing = throw new IllegalArgumentException(s"$name: $msg")

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val s = Settings.parse(v)
    if s.model.isEmpty then fail("choose a motion module (a file, a diffusers folder, or a Hugging Face repo id)")
    if p0.batchSize != 1 then fail("batch size must be 1; use the batch count for several videos")
    if p0.hires.enabled then fail("cannot be combined with Hires. fix")
    if p0.img2img.isEmpty && s.lastFrame.nonEmpty then fail("a last frame needs the img2img tab")
    if s.videoFormats.nonEmpty then ctx.requirePlaintextVideoAllowed()
    val seed = Support.fixSeed(p0.seed)
    val params = p0.copy(seed = seed, hooks = p0.hooks.updated("animatediff", hookFields(s)),
      extra = p0.extra.updated(Key, infotextValue(s)))
    ctx.status(name, 0, math.max(1, p0.nIter))
    Support.step(ctx)(ctx.generateTransient(params)) match
      case None => JobOutput(Nil)
      case Some(frames) =>
        val temps = ArrayBuffer[Path]()
        temps ++= frames.map(_.path)
        try
          val perVideo = s.frames
          if frames.size != perVideo * math.max(1, p0.nIter) then
            fail(s"the engine returned ${frames.size} frames, expected ${perVideo * math.max(1, p0.nIter)}")
          val saved = ArrayBuffer[OutputImage]()
          frames.grouped(perVideo).zipWithIndex.foreach { (group, index) =>
            ctx.status(name, index + 1, math.max(1, p0.nIter))
            saved ++= finish(group, s, p0.img2img.isDefined, ctx, temps)
          }
          JobOutput(saved.toSeq)
        finally Support.deleteQuietly(temps.toSeq*)

  /** The first frame goes to the gallery (with the infotext of the video), the other frames too when PNG is ticked;
    * the videos and the TXT file are written beside the first frame.
    */
  private def finish(group: Seq[OutputImage], s: Settings, img2img: Boolean, ctx: PluginContext, temps: ArrayBuffer[Path]): Seq[OutputImage] =
    val kindName = if img2img then "img2img" else "txt2img"
    val first = group.head
    val preview = ctx.saveImage(ctx.readImage(first.path), kindName, first.params, first.infotext)
    val stem = preview.path.getFileName.toString.replaceAll("\\.png$", "")
    val extraFrames =
      if s.formats.contains("PNG") then group.tail.map(f => ctx.saveImage(ctx.readImage(f.path), kindName, f.params, f.infotext)) else Nil
    if s.videoFormats.nonEmpty then
      val files = group.map { f =>
        val tmp = Support.writeTemp(ctx, ctx.readImage(f.path))
        temps += tmp
        tmp.toString
      }
      val reply = ctx.engineCall("animatediff_encode", ujson.Obj("images" -> ujson.Arr.from(files.map(ujson.Str(_))),
        "formats" -> ujson.Arr.from(s.videoFormats.map(ujson.Str(_))), "fps" -> s.fps, "loop_number" -> s.loopNumber,
        "closed_loop" -> s.closedLoop, "batch_size" -> s.batchSize, "name" -> stem))
      val outputs = reply match
        case o: ujson.Obj => o.value.get("outputs").collect { case m: ujson.Obj => m.value.collect { case (k, ujson.Str(p)) => k -> p }.toSeq }.getOrElse(Nil)
        case _            => Nil
      if outputs.size != s.videoFormats.size then fail(s"the engine wrote ${outputs.size} video(s), expected ${s.videoFormats.size}")
      outputs.foreach { (format, path) =>
        Files.copy(Path.of(path), preview.path.resolveSibling(s"$stem.${format.toLowerCase}"), StandardCopyOption.REPLACE_EXISTING)
      }
    if s.formats.contains("TXT") then
      Files.writeString(preview.path.resolveSibling(s"$stem.txt"), first.infotext, java.nio.charset.StandardCharsets.UTF_8)
    preview +: extraFrames
