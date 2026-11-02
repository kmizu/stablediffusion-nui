package sdfx.mcp

import sdfx.engine.AppServices
import java.nio.file.{Files, Path}
import scala.concurrent.{Await, ExecutionContext, TimeoutException}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Create the CLIP image embedding used by the Aesthetic Gradients hook. */
final class AestheticEmbeddingTool(services: AppServices, policy: PathPolicy, baseProvider: () => Path,
                                   tempFile: String => Path, awaitReady: ToolContext => Unit,
                                   timeout: FiniteDuration = 60.minutes) extends McpTool:
  def name = "aesthetic_embedding"
  override def title = "Aesthetic embeddings"
  def description = "List or create Aesthetic Gradients embeddings from reference images. " +
    "Create writes a .pt file; the first use downloads the CLIP ViT-L/14 model. " +
    "Image paths and optional output_dir must be in allowed folders. The default output folder is outputs/aesthetic_embeddings; " +
    "use the returned path, or a name from that folder, in Aesthetic Gradients."
  def inputSchema = Schema.obj(Seq(
    "operation" -> Schema.str("Operation", Seq("list", "create")),
    "name" -> Schema.str("Plain embedding file name for create"),
    "images" -> Schema.arr(Schema.str("Allowed image path, base64 PNG or data URI"), "Reference images", Some(32)),
    "output_dir" -> Schema.str("Existing allowed output directory; omit for outputs/aesthetic_embeddings"),
    "overwrite" -> Schema.bool("Replace an existing embedding", Some(false))
  ), Seq("operation"))

  def call(input: ujson.Obj, ctx: ToolContext): ToolResult =
    val args = Args(input, Seq("operation", "name", "images", "output_dir", "overwrite"))
    val op = args.reqStr("operation")
    if !Set("list", "create")(op) then throw ToolError("operation must be list or create")
    if op == "create" && services.imageScrambleSession.state.secret.nonEmpty then
      throw ToolError("Aesthetic embedding creation is unavailable during a protected image session")
    // Snapshot the current output root while settings updates are excluded; never create a folder
    // under the stale output root captured when the MCP server was constructed.
    val (base, outputDir) = services.synchronized {
      val current = baseProvider().toAbsolutePath.normalize()
      val dir = args.str("output_dir").map(policy.resolveDirectory(_, "output_dir", current)).getOrElse {
        val folder = current.resolve("aesthetic_embeddings")
        Files.createDirectories(folder)
        policy.resolveDirectory(folder.toString, "output_dir", current)
      }
      (current, dir)
    }
    if op == "list" then
      val stream = Files.list(outputDir)
      val entries = try stream.iterator.asScala.toVector.filter(p => {
        val name = p.getFileName.toString.toLowerCase
        name.endsWith(".pt") || name.endsWith(".safetensors")
      }).flatMap(p => scala.util.Try(policy.resolve(p.toString, "embedding", base)).toOption)
        .sortBy(_.getFileName.toString.toLowerCase)
      finally stream.close()
      return ToolResult.json(ujson.Obj("embeddings" -> ujson.Arr.from(entries.map(p =>
        ujson.Obj("name" -> p.getFileName.toString, "path" -> p.toString))), "output_dir" -> outputDir.toString))
    val params = ujson.Obj("op" -> op, "output_dir" -> outputDir.toString)
    var temporary = Vector.empty[Path]
    var cleanupDeferred = false
    try
      if op == "create" then
        val imageSpecs = args.strList("images").getOrElse(throw ToolError("images are required"))
        if imageSpecs.isEmpty || imageSpecs.size > 32 then throw ToolError("images must contain 1 to 32 references")
        val name = args.reqStr("name").trim
        if name.isEmpty || name.startsWith(".") || name.exists(ch => "\\/:*?\"<>|".contains(ch)) then
          throw ToolError("name must be a plain file name")
        params("name") = name
        val paths = imageSpecs.zipWithIndex.map { (spec, index) =>
          val (path, temp) = Images.materialize(spec, s"images[$index]", policy, base, tempFile)
          temporary ++= temp.toVector
          path.toString
        }
        params("images") = ujson.Arr.from(paths)
        params("overwrite") = args.bool("overwrite").getOrElse(false)
      if ctx.isCancelled then throw ToolError("cancelled")
      awaitReady(ctx)
      val handle = services.client.aestheticEmbeddingOwned(params)
      ctx.onCancel(() => { handle.interrupt(); () })
      def retainInputsAndInterrupt(): Unit =
        val inputs = temporary
        cleanupDeferred = true
        handle.result.onComplete(_ => inputs.foreach(Images.deleteQuietly))(using ExecutionContext.parasitic)
        handle.interrupt()
        ()
      val result = try Await.result(handle.result, timeout)
      catch
        case _: TimeoutException =>
          // The worker can outlive the caller (e.g. cancellation is blocked). Retain its inputs
          // until it has actually stopped and request scoped cancellation without interrupting other jobs.
          retainInputsAndInterrupt()
          throw ToolError("aesthetic embedding timed out; cancellation was requested and the worker may still be running")
        case e: InterruptedException =>
          // Transport shutdown can interrupt this caller while the owned worker is still using inputs.
          retainInputsAndInterrupt()
          Thread.currentThread().interrupt()
          throw e
        case e: sdfx.core.protocol.EngineError => throw ToolError(e.getMessage)
      val index = services.home.configDir.resolve("aesthetic-embedding-directories.json")
      def permitted(path: Path): Boolean = scala.util.Try(policy.resolveDirectory(path.toString, "output_dir", base)).isSuccess
      sdfx.core.aesthetic.AestheticEmbeddingDirectories.register(index, outputDir, permitted).left.foreach { warning =>
        result.obj("directory_registration_warning") = ujson.Str(warning)
      }
      ToolResult.json(result)
    finally if !cleanupDeferred then temporary.foreach(Images.deleteQuietly)
