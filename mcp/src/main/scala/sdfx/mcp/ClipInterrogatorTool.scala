package sdfx.mcp

import sdfx.engine.AppServices
import java.nio.file.Path
import scala.concurrent.Await
import scala.concurrent.duration.*

/** CLIP Interrogator: image to prompt (best / fast / classic / negative / caption), Analyze and folder Batch. */
final class ClipInterrogatorTool(services: AppServices, policy: PathPolicy, base: Path,
                                 tempFile: String => Path, awaitReady: ToolContext => Unit) extends McpTool:
  import ClipInterrogatorTool.*
  def name = "clip_interrogate"
  override def title = "CLIP Interrogator"
  def description = "Work out a prompt for an existing image with CLIP (after pharmapsychotic's CLIP Interrogator). " +
    "operation=prompt returns a prompt for `image` (an allowed local path, base64 or data URI) in mode best (slowest, highest similarity), fast, classic, negative (a negative prompt) or caption; " +
    "analyze returns the top 5 medium / artist / movement / trending / flavor labels with scores; batch writes .txt files (or batch.txt / batch.csv) for every .png/.jpg/.jpeg in an allowed `folder` and never overwrites unless overwrite=true; " +
    "models lists choices, optional OpenCLIP dependency status and model-card source/verification metadata; model-card licence labels are dated snapshots, so check current terms. " +
    "unload frees the models (they are also freed after each call unless keep_loaded=true). " +
    "The first use downloads the CLIP and caption models from Hugging Face."
  override def idempotent = true
  def inputSchema = Schema.obj(Seq(
    "operation" -> Schema.str("What to do", Seq("prompt", "analyze", "batch", "models", "unload"), Some("prompt")),
    "image" -> Schema.str("prompt / analyze: image path inside an allowed folder, base64 or data URI"),
    "folder" -> Schema.str("batch: folder inside an allowed folder; output files are written there"),
    "mode" -> Schema.str("prompt / batch mode", Modes, Some("best")),
    "clip_model" -> Schema.str("CLIP model (see operation=models)", default = Some("ViT-L-14/openai")),
    "caption_model" -> Schema.str("Caption model; blip-large by default (blip-base on GPUs with 12 GB or less)",
      Seq("blip-base", "blip-large", "blip2-2.7b", "blip2-flan-t5-xl", "git-large-coco")),
    "output_mode" -> Schema.str("batch output", OutputModes, Some(OutputModes.head)),
    "overwrite" -> Schema.bool("batch: replace existing .txt / batch.txt / batch.csv", Some(false)),
    "keep_loaded" -> Schema.bool("Keep the models in memory after the call", Some(false)),
    "min_flavors" -> Schema.int("best: minimum flavors in the chain", Some(0), Some(64)),
    "max_flavors" -> Schema.int("best / fast / classic / negative: maximum flavors", Some(1), Some(64))
  ), Seq("operation"))

  def call(input: ujson.Obj, ctx: ToolContext): ToolResult =
    val args = Args(input, Seq("operation", "image", "folder", "mode", "clip_model", "caption_model", "output_mode", "overwrite",
      "keep_loaded", "min_flavors", "max_flavors"))
    val operation = args.str("operation").getOrElse("prompt")
    if !Set("prompt", "analyze", "batch", "models", "unload")(operation) then throw ToolError("operation must be prompt, analyze, batch, models or unload")
    if services.imageScrambleSession.state.secret.nonEmpty && operation != "models" && operation != "unload" then
      throw ToolError("CLIP Interrogator is unavailable during a protected image session")
    val mode = args.str("mode").getOrElse("best")
    if !Modes.contains(mode) then throw ToolError(s"mode must be one of ${Modes.mkString(", ")}")
    val params = ujson.Obj("operation" -> operation, "mode" -> mode,
      "keep_loaded" -> args.bool("keep_loaded").getOrElse(false),
      "cache_dir" -> services.home.configDir.resolve("clip-interrogator").toString)
    args.str("clip_model").foreach(m => params("clip_model") = m)
    args.str("caption_model").foreach(m => params("caption_model") = m)
    args.int("min_flavors", 0, 64).foreach(n => params("min_flavors") = n)
    args.int("max_flavors", 1, 64).foreach(n => params("max_flavors") = n)
    var temporary: Option[Path] = None
    try
      operation match
        case "prompt" | "analyze" =>
          val spec = args.reqStr("image")
          val (path, temp) = Images.materialize(spec, "image", policy, base, tempFile)
          temporary = temp
          params("image_path") = path.toString
        case "batch" =>
          params("folder") = policy.resolveDirectory(args.reqStr("folder"), "folder", base).toString
          val output = args.str("output_mode").getOrElse(OutputModes.head)
          if !OutputModes.contains(output) then throw ToolError(s"output_mode must be one of ${OutputModes.mkString("; ")}")
          params("output_mode") = output
          params("overwrite") = args.bool("overwrite").getOrElse(false)
        case _ => ()
      if ctx.isCancelled then throw ToolError("cancelled")
      awaitReady(ctx)
      val handle = services.client.clipInterrogatorOwned(params)
      ctx.onCancel(() => { handle.interrupt(); () })
      val value =
        try Await.result(handle.result, 60.minutes)
        catch case e: sdfx.core.protocol.EngineError => throw ToolError(e.getMessage)
      if ctx.isCancelled then throw ToolError("cancelled")
      ToolResult.json(value)
    finally temporary.foreach(Images.deleteQuietly)

object ClipInterrogatorTool:
  val Modes = Seq("best", "fast", "classic", "negative", "caption")
  val OutputModes = Seq("Text file for each image", "Single text file with all prompts",
    "csv file with columns for filenames and prompts")
