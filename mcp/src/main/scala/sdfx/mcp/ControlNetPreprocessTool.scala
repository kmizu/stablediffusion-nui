package sdfx.mcp

import sdfx.core.model.PreprocessorControls
import sdfx.core.plugins.api.ChoiceSource
import sdfx.engine.AppServices

import java.nio.file.{Files, Path}

/** ControlNet Preprocessor as an Extras step: one image (or a folder) through a ControlNet preprocessor, saved with the
  * `ControlNet Preprocessor: "{...}"` parameters the Extras tab writes. */
final class ControlNetPreprocessTool(services: AppServices, policy: PathPolicy, base: Path,
                                     tempFile: String => Path, awaitReady: ToolContext => Unit) extends McpTool:
  import ControlNetPreprocessTool.*
  def name = "controlnet_preprocess"
  override def title = "ControlNet Preprocessor"
  def description = "Run a ControlNet preprocessor on an image without generating (the Extras-tab step of the ControlNet Preprocessor extension). " +
    "`image` is an allowed local path, base64 or data URI; or give `folder` (an allowed folder) to process every .png/.jpg/.jpeg/.webp/.bmp in it. " +
    "Results are written to `output_dir` (an existing allowed folder; default outputs/extras) as <name>-<preprocessor>.png and never overwrite. " +
    "`processor_res` (64..2048, default 512) or `pixel_perfect` (the short side of the image) applies to canny and the detector modules and is ignored by the others; " +
    "`args` holds the module's sliders (canny low_threshold/high_threshold, recolor gamma_correction, tile_resample down_sampling_rate, tile_colorfix variation, blur_gaussian sigma, marigold steps). " +
    "operation=list returns the available modules with their controls. Modules that need weights (depth, marigold) download them on first use."
  override def idempotent = true
  def inputSchema = Schema.obj(Seq(
    "operation" -> Schema.str("What to do", Seq("run", "list"), Some("run")),
    "preprocessor" -> Schema.str("Module, e.g. canny, invert, recolor_luminance, tile_resample, blur_gaussian, depth (see operation=list)"),
    "image" -> Schema.str("Image path inside an allowed folder, base64 or data URI"),
    "folder" -> Schema.str("Folder inside an allowed folder; every image in it is processed"),
    "output_dir" -> Schema.str("Existing allowed folder for the results (default outputs/extras)"),
    "processor_res" -> Schema.int("Processor resolution; omit for 512", Some(PreprocessorControls.MinResolution), Some(PreprocessorControls.MaxResolution)),
    "pixel_perfect" -> Schema.bool("Use the short side of the image as the resolution", Some(false)),
    "args" -> Schema.obj(Nil)
  ), Seq("operation"))

  def call(input: ujson.Obj, ctx: ToolContext): ToolResult =
    val args = Args(input, Seq("operation", "preprocessor", "image", "folder", "output_dir", "processor_res", "pixel_perfect", "args"))
    val operation = args.str("operation").getOrElse("run")
    if !Set("run", "list")(operation) then throw ToolError("operation must be run or list")
    if operation == "run" && services.imageScrambleSession.state.secret.nonEmpty then
      throw ToolError("ControlNet Preprocessor is unavailable during a protected image session")
    if ctx.isCancelled then throw ToolError("cancelled")
    awaitReady(ctx) // the module list comes from the engine
    val modules = PreprocessorControls.selectable(services.catalog.choices(ChoiceSource.Preprocessors).toSeq)
    if operation == "list" then ToolResult.json(listing(modules)) else run(args, modules, ctx)

  private def run(args: Args, modules: Seq[String], ctx: ToolContext): ToolResult =
    val chosen = args.reqStr("preprocessor")
    val module = modules.find(_.equalsIgnoreCase(chosen)).getOrElse(
      throw ToolError(s"unknown preprocessor: $chosen. Available: ${modules.mkString(", ")}"))
    if args.has("image") == args.has("folder") then throw ToolError("give exactly one of image and folder")
    val a = args.objOpt("args").map { o =>
      o.value.toSeq.map { (k, v) =>
        k -> (v match
          case ujson.Num(n) if !n.isNaN => n
          case _                        => throw ToolError(s"args.$k must be a number"))
      }.toMap
    }.getOrElse(Map.empty)
    PreprocessorControls.validate(module, a).left.foreach(m => throw ToolError(m))
    val res = args.int("processor_res", PreprocessorControls.MinResolution, PreprocessorControls.MaxResolution)
    val pixelPerfect = args.bool("pixel_perfect").getOrElse(false)
    if res.nonEmpty && pixelPerfect then throw ToolError("give processor_res or pixel_perfect, not both")
    val outDir = args.str("output_dir").map(policy.resolveDirectory(_, "output_dir", base)).getOrElse {
      val d = base.resolve("extras")
      Files.createDirectories(d)
      d
    }
    val notes =
      if !PreprocessorControls.of(module).resolution && (res.nonEmpty || pixelPerfect)
      then Seq(s"preprocessor '$module' has no resolution; processor_res and pixel_perfect were ignored") else Nil
    var temporary: Option[Path] = None
    try
      val inputs: Seq[Path] = args.str("image") match
        case Some(spec) =>
          val (path, temp) = Images.materialize(spec, "image", policy, base, tempFile)
          temporary = temp
          Seq(path)
        case None =>
          val dir = policy.resolveDirectory(args.reqStr("folder"), "folder", base)
          val found = listImages(dir)
          if found.isEmpty then throw ToolError("no images in folder")
          if found.size > MaxFolderImages then throw ToolError(s"folder has more than $MaxFolderImages images")
          if Files.isSameFile(dir, outDir) then throw ToolError("output_dir must differ from folder")
          found
      if ctx.isCancelled then throw ToolError("cancelled")
      val results = inputs.zipWithIndex.map { (in, i) =>
        if ctx.isCancelled then throw ToolError("cancelled")
        ctx.progress(i.toDouble, Some(inputs.size.toDouble), in.getFileName.toString)
        val out =
          try services.generator.preprocessExtras(in, module, a, res.getOrElse(-1), pixelPerfect, Some(outDir))
          catch
            case e: sdfx.core.protocol.EngineError => throw ToolError(s"${in.getFileName}: ${e.getMessage}")
            case e: IllegalArgumentException       => throw ToolError(e.getMessage)
        ujson.Obj("input" -> in.toString, "output" -> out.toString)
      }
      val structured = ujson.Obj("preprocessor" -> module, "output_dir" -> outDir.toString,
        "processor_res" -> res.map(n => ujson.Num(n.toDouble)).getOrElse(ujson.Null), "pixel_perfect" -> pixelPerfect,
        "args" -> ujson.Obj.from(a.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Num(v))),
        "results" -> ujson.Arr.from(results), "notes" -> ujson.Arr.from(notes.map(ujson.Str(_))))
      ToolResult.json(structured, Some(s"${results.size} image(s) written to $outDir"))
    finally temporary.foreach(Images.deleteQuietly)

object ControlNetPreprocessTool:
  val MaxFolderImages = 500

  def listImages(dir: Path): Seq[Path] =
    import scala.jdk.CollectionConverters.*
    val s = Files.list(dir)
    try s.iterator.asScala.filter(p => Files.isRegularFile(p) && PreprocessorControls.isImage(p.getFileName.toString))
      .toVector.sortBy(_.getFileName.toString.toLowerCase)
    finally s.close()

  def listing(modules: Seq[String]): ujson.Value =
    ujson.Obj("preprocessors" -> ujson.Arr.from(modules.map { m =>
      val c = PreprocessorControls.of(m)
      ujson.Obj("name" -> m, "resolution" -> c.resolution, "args" -> ujson.Arr.from(c.sliders.map(s =>
        ujson.Obj("key" -> s.key, "label" -> s.label, "min" -> s.min, "max" -> s.max, "default" -> s.default))))
    }))
