package sdfx.mcp

import sdfx.core.presets.*
import sdfx.engine.AppServices

/** Read-only access to native history. Original imported payloads and images are not returned. */
final class GenerationHistoryTool(services: AppServices) extends McpTool:
  def name = "generation_history"
  override def title = "Generation history"
  override def readOnly = true
  override def idempotent = true
  def description = "List local generation history or favourites and read the mapped fields of one selected entry. Names and fields may contain private information. Original imported metadata and images are not returned. This tool does not restore settings or generate images."
  def inputSchema = Schema.obj(Seq(
    "action" -> Schema.str("list or get", Seq("list", "get")),
    "mode" -> Schema.str("Generation mode", Seq("txt2img", "img2img", "inpaint")),
    "collection" -> Schema.str("history or favourites", Seq("history", "favourites"), Some("history")),
    "id" -> Schema.str("Entry UUID; required for get")
  ), Seq("action", "mode"))

  def call(input: ujson.Obj, ctx: ToolContext): ToolResult =
    val args = Args(input, Seq("action", "mode", "collection", "id"))
    val action = args.reqStr("action")
    val mode = args.reqStr("mode")
    val collection = args.str("collection").getOrElse("history")
    require(Set("list", "get")(action), "Unknown history action")
    require(Set("txt2img", "img2img", "inpaint")(mode), "Unknown generation mode")
    require(Set("history", "favourites")(collection), "Unknown history collection")
    val id = if action == "get" then Some(args.reqStr("id")) else None
    require(action == "get" || !args.has("id"), "id is only valid for get")
    val store = new PresetStore(services.home.configDir.resolve("generation-history"), PresetLimits(maxCollections = 6))
    val library = store.load().fold(e => throw ToolError(e.issues.map(_.message).mkString("; ")), _.library)
    val records = library.collections.find(_.id == s"$collection-$mode").toVector.flatMap(_.presets).reverse
    val result = ujson.Obj("mode" -> mode, "collection" -> collection)
    if action == "list" then
      result("entries") = ujson.Arr.from(records.map(r => ujson.Obj("id" -> r.id.toString, "name" -> r.name)))
    else
      val record = records.find(_.id.toString == id.get).getOrElse(throw ToolError("History entry not found"))
      val fields = record.fields.toVector.sortBy(_._1.render).map { (key, value) =>
        val encoded = value match
          case PresetAtom.Text(v) => ujson.Obj("type" -> "text", "value" -> v)
          case PresetAtom.Integer(v) => ujson.Obj("type" -> "integer", "value" -> v.toString)
          case PresetAtom.Decimal(v) => ujson.Obj("type" -> "decimal", "value" -> v.toString)
          case PresetAtom.Flag(v) => ujson.Obj("type" -> "boolean", "value" -> v)
        key.render -> encoded
      }
      result("id") = record.id.toString
      result("name") = record.name
      result("fields") = ujson.Obj.from(fields)
    ToolResult.json(result)
