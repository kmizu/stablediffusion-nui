package sdfx.mcp

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption}
import scala.util.control.NonFatal
import sdfx.core.prompttools.PromptLibrary
import sdfx.engine.AppServices

/** Read-only access to the app home's saved prompt library. */
final class PromptLibraryTool(services: AppServices) extends McpTool:
  def name = "prompt_library"
  override def title = "Saved prompt library"
  override def readOnly = true
  override def idempotent = true
  def description = "List saved prompt names and whether each is negative, or read one named prompt. Names and prompt text may be private. This only reads the app home's prompt library; it does not change settings or generate images."
  def inputSchema = Schema.obj(Seq(
    "action" -> Schema.str("list names or get one prompt", Seq("list", "get")),
    "name" -> Schema.str("Exact saved prompt name; required for get"),
    "offset" -> Schema.int("List only: zero-based offset; concurrent edits can shift pages", min = Some(0), max = Some(PromptLibrary.MaxEntries)),
    "limit" -> Schema.int("List only: maximum results (default 100)", min = Some(1), max = Some(100), default = Some(100))
  ), Seq("action"))

  def call(input: ujson.Obj, ctx: ToolContext): ToolResult =
    val args = Args(input, Seq("action", "name", "offset", "limit"))
    val action = args.reqStr("action")
    if !Set("list", "get")(action) then throw ToolError("Unknown prompt library action")
    if action == "list" && args.has("name") then throw ToolError("name is only valid for get")
    if action == "get" && (args.has("offset") || args.has("limit")) then throw ToolError("offset and limit are only valid for list")
    val name = if action == "get" then Some(args.reqStr("name")) else None
    if name.exists(_.length > PromptLibrary.MaxNameChars) then throw ToolError("Prompt name is too long")
    val offset = args.int("offset", 0, PromptLibrary.MaxEntries).getOrElse(0)
    val limit = args.int("limit", 1, 100).getOrElse(100)
    val file = services.promptTools.libraryStore.file
    val library = try
      if Files.notExists(file, LinkOption.NOFOLLOW_LINKS) then PromptLibrary()
      else
        if !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) then throw ToolError("Prompt library is not a regular file")
        val stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)
        val bytes = try stream.readNBytes(PromptLibrary.MaxFileBytes + 1) finally stream.close()
        if bytes.length > PromptLibrary.MaxFileBytes then throw ToolError("Prompt library is too large")
        PromptLibrary.parse(new String(bytes, UTF_8))
    catch
      case e: ToolError => throw e
      case NonFatal(e) => throw ToolError(s"Cannot read prompt library: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
    if action == "list" then
      val page = library.entries.slice(offset, offset + limit)
      ToolResult.json(ujson.Obj("entries" -> ujson.Arr.from(page.map(e => ujson.Obj("name" -> e.name, "negative" -> e.negative))),
        "total" -> library.entries.size, "offset" -> offset,
        "next_offset" -> (if offset + page.size < library.entries.size then ujson.Num(offset + page.size) else ujson.Null)))
    else
      val entry = library.get(name.get).getOrElse(throw ToolError("Saved prompt not found"))
      if entry.prompt.length > PromptLibrary.MaxPromptChars then throw ToolError("Saved prompt is too long")
      ToolResult.json(ujson.Obj("name" -> entry.name, "prompt" -> entry.prompt, "negative" -> entry.negative))
