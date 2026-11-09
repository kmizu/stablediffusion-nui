package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import sdfx.core.prompttools.MiniYaml
import sdfx.core.prompttools.YNode.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}

/** Personal prompt library: named snippets in a YAML file, expanded with `[[name]]` tokens (own design; no upstream code). */
object PromptLibrary:
  final case class Item(path: Vector[String], value: String, negative: String):
    def name: String = path.mkString("/")

  /** A string leaf is a snippet; a map with `value` is a snippet with a negative prompt; any other map is a category. */
  def parse(yaml: String): Vector[Item] =
    def walk(path: Vector[String], n: sdfx.core.prompttools.YNode): Vector[Item] = n match
      case YStr(v) if path.nonEmpty => Vector(Item(path, v, ""))
      case YMap(es) =>
        val f = es.collect { case (k, YStr(v)) => k -> v }.toMap
        if path.nonEmpty && f.contains("value") then Vector(Item(path, f("value"), f.getOrElse("negative", "")))
        else es.flatMap((k, c) => walk(path :+ k, c))
      case _ => Vector.empty
    walk(Vector.empty, MiniYaml.parse(yaml))

  def find(items: Seq[Item], key: String): Item =
    val k = key.trim
    items.find(_.name == k).orElse {
      val hits = items.filter(_.path.last == k)
      require(hits.size <= 1, s"Library entry '$k' is ambiguous: ${hits.map(_.name).mkString(", ")}")
      hits.headOption
    }.getOrElse(throw new IllegalArgumentException(s"Library entry '$k' not found"))

  private val Token = "\\[\\[([^\\[\\]]+)\\]\\]".r

  /** Replaces every `[[name]]` by the entry text (entries may not contain tokens themselves). Returns text and the used items. */
  def expand(items: Seq[Item], text: String): (String, Seq[Item]) =
    val used = scala.collection.mutable.ArrayBuffer[Item]()
    val out = Token.replaceAllIn(text, m =>
      val it = find(items, m.group(1))
      used += it
      java.util.regex.Matcher.quoteReplacement(it.value))
    (out, used.toSeq)

  /** Case-insensitive search over names and texts. */
  def search(items: Seq[Item], q: String): Seq[Item] =
    val s = q.trim.toLowerCase
    items.filter(i => s.isEmpty || i.name.toLowerCase.contains(s) || i.value.toLowerCase.contains(s))

  private def quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** One YAML line `name: "text"` for a new top-level entry. */
  def line(name: String, text: String): String =
    require(name.matches("[^:#\\[\\]\"'\\n]+") && name.trim == name && name.nonEmpty, "Entry name may not contain : # [ ] quotes or line breaks")
    require(text.trim.nonEmpty, "Nothing to save")
    s"$name: ${quote(text.replace('\n', ' ').trim)}\n"

class PromptLibraryPlugin extends Plugin:
  import PromptLibrary.*
  def id = "prompt_library"
  def name = "Prompt library"
  def kind = PluginKind.Script
  override def description = "Keeps named prompt snippets in a YAML file. Write [[name]] (or [[Category/name]]) in the prompt to insert one; " +
    "Search lists matches in the log; Save adds the current prompt to the file under a new name (never overwrites). No library text is bundled."
  override def controls: Seq[Control] = Seq(
    Text("file", "Library YAML file", ""),
    Choice("action", "Action", Seq("Generate with [[name]] expanded", "Search (log only)", "Save current prompt"), "Generate with [[name]] expanded"),
    Text("query", "Search text / name for Save", ""),
    Text("append", "Entries to append (comma separated, optional)", "")
  )

  private def read(path: String) =
    require(path.trim.nonEmpty, "A library file path is required")
    val f = Paths.get(path.trim)
    require(Files.isRegularFile(f) && Files.size(f) <= 4L * 1024 * 1024, s"Cannot read $path (missing or larger than 4 MiB)")
    new String(Files.readAllBytes(f), StandardCharsets.UTF_8)

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val action = v.str("action")
    if action.startsWith("Save") then
      val path = Paths.get(v.str("file").trim)
      require(v.str("file").trim.nonEmpty, "A library file path is required")
      val existing = if Files.isRegularFile(path) then parse(read(v.str("file"))) else Vector.empty
      val nm = v.str("query").trim
      require(!existing.exists(_.name == nm), s"'$nm' already exists in the library")
      val text = line(nm, p0.prompt)
      Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
      val needNl = Files.isRegularFile(path) && Files.size(path) > 0 && !new String(Files.readAllBytes(path), StandardCharsets.UTF_8).endsWith("\n")
      Files.writeString(path, (if needNl then "\n" else "") + text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      ctx.log(s"Prompt library: saved '$nm'")
      JobOutput(Nil)
    else
      val items = parse(read(v.str("file")))
      if action.startsWith("Search") then
        val hits = search(items, v.str("query"))
        ctx.log(s"Prompt library: ${hits.size} match(es)\n" + hits.take(50).map(i => s"  ${i.name}: ${i.value}").mkString("\n"))
        JobOutput(Nil)
      else
        val extra = v.str("append").split(",").map(_.trim).filter(_.nonEmpty).map(find(items, _))
        val (pos, used) = expand(items, p0.prompt)
        val (neg, usedN) = expand(items, p0.negativePrompt)
        val all = used ++ usedN ++ extra
        val posFull = (Seq(pos) ++ extra.map(_.value)).map(_.trim.stripSuffix(",").trim).filter(_.nonEmpty).mkString(", ")
        val negFull = (Seq(neg) ++ all.map(_.negative)).map(_.trim.stripSuffix(",").trim).filter(_.nonEmpty).mkString(", ")
        val q = p0.copy(prompt = posFull, negativePrompt = negFull,
          extra = p0.extra + ("Script" -> "Prompt library") + ("Prompt library entries" -> all.map(_.name).distinct.mkString(", ")))
        Support.step(ctx)(ctx.generate(q)).fold(JobOutput(Nil))(o => JobOutput(o))
