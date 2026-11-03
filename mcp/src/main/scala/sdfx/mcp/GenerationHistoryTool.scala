package sdfx.mcp

import sdfx.core.presets.*
import sdfx.engine.AppServices

/** Read-only access to native history. Original imported payloads and images are not returned. */
final class GenerationHistoryTool(services: AppServices) extends McpTool:
  def name = "generation_history"
  override def title = "Generation history"
  override def readOnly = true
  override def idempotent = true
  def description = "List or search local generation history or favourites, read saved fields, or prepare a partial generation request draft. Names, fields and search matches may reveal private information. Drafts omit settings and cannot guarantee replay. Original imported metadata and images are not returned. This tool does not restore settings or generate images."
  def inputSchema = Schema.obj(Seq(
    "action" -> Schema.str("list, get, or prepare a partial request draft", Seq("list", "get", "prepare")),
    "mode" -> Schema.str("Generation mode", Seq("txt2img", "img2img", "inpaint")),
    "collection" -> Schema.str("history or favourites", Seq("history", "favourites"), Some("history")),
    "id" -> Schema.str("Entry UUID; required for get or prepare"),
    "query" -> Schema.str("List only: comma-separated AND terms matching name, prompt, negative prompt, checkpoint or sampler, ignoring case"),
    "offset" -> Schema.int("List only: zero-based offset; concurrent history changes can shift pages", min = Some(0), max = Some(1000)),
    "limit" -> Schema.int("List only: maximum results (default 1000)", min = Some(1), max = Some(1000), default = Some(1000))
  ), Seq("action", "mode"))

  def call(input: ujson.Obj, ctx: ToolContext): ToolResult =
    val args = Args(input, Seq("action", "mode", "collection", "id", "query", "offset", "limit"))
    val action = args.reqStr("action")
    val mode = args.reqStr("mode")
    val collection = args.str("collection").getOrElse("history")
    require(Set("list", "get", "prepare")(action), "Unknown history action")
    require(Set("txt2img", "img2img", "inpaint")(mode), "Unknown generation mode")
    require(Set("history", "favourites")(collection), "Unknown history collection")
    val id = if action != "list" then Some(args.reqStr("id")) else None
    require(action != "list" || !args.has("id"), "id is only valid for get or prepare")
    require(action == "list" || !Seq("query", "offset", "limit").exists(args.has), "query, offset and limit are only valid for list")
    val query = args.str("query").getOrElse("")
    require(query.length <= 1024, "query is too long")
    val offset = args.int("offset", 0, 1000).getOrElse(0)
    val limit = args.int("limit", 1, 1000).getOrElse(1000)
    val store = new PresetStore(services.home.configDir.resolve("generation-history"), PresetLimits(maxCollections = 6))
    val library = store.load().fold(e => throw ToolError(e.issues.map(_.message).mkString("; ")), _.library)
    val records = library.collections.find(_.id == s"$collection-$mode").toVector.flatMap(_.presets).reverse
    val result = ujson.Obj("mode" -> mode, "collection" -> collection)
    if action == "list" then
      val matches = records.filter(HistorySearch.matches(_, query))
      val page = matches.slice(offset, offset + limit)
      result("entries") = ujson.Arr.from(page.map(r => ujson.Obj("id" -> r.id.toString, "name" -> r.name)))
      result("total") = matches.size
      result("offset") = offset
      result("next_offset") = if offset + page.size < matches.size then ujson.Num(offset + page.size) else ujson.Null
    else
      val record = records.find(_.id.toString == id.get).getOrElse(throw ToolError("History entry not found"))
      if action == "prepare" then
        val draft = HistoryGenerationDraft.build(record, mode)
        draft("id") = record.id.toString
        draft("mode") = mode
        draft("collection") = collection
        return ToolResult.json(draft)
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
