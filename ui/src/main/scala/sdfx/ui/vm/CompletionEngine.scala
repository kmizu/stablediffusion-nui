package sdfx.ui.vm

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Locale
import sdfx.core.config.TagCompleteSettings
import sdfx.core.model.LoraSyntax

/** What a suggestion completes. */
enum SuggestionKind(val title: String):
  case Tag extends SuggestionKind("tag")
  case Extra extends SuggestionKind("extra")
  case Wildcard extends SuggestionKind("wildcard")
  case WildcardValue extends SuggestionKind("wildcard value")
  case Lora extends SuggestionKind("LoRA")
  case Embedding extends SuggestionKind("embedding")
  case Chant extends SuggestionKind("chant")

/** One popup row. `insert` produces the replacement text lazily (trigger words are read from disk only on accept). */
final case class Suggestion(kind: SuggestionKind, label: String, detail: String, insert: () => String,
                            preview: () => Option[Path] = () => None, category: Option[Int] = None)

/** The popup rows for the text range [start, end). */
final case class CompletionResult(start: Int, end: Int, items: Vector[Suggestion], wildcardPrefix: Option[String] = None)

/** A model file offered by `<` completion (name is the file stem, path the real file). */
final case class NetFile(name: String, path: Path)

/** A chants JSON entry (`name`, `terms`, `content`, `color`). */
final case class Chant(name: String, terms: String, content: String, color: Int, colorSpecified: Boolean = true)

/** A tag from the "extra file": same CSV layout as tags, but the third column is the grey meta text. */
final case class ExtraTag(name: String, meta: String, aliases: Vector[String], category: Option[Int] = None)

/** Everything the engine can offer. */
final case class CompletionData(tags: Vector[CompletionTag] = Vector.empty, extra: Vector[ExtraTag] = Vector.empty,
                                translations: Map[String, String] = Map.empty, chants: Vector[Chant] = Vector.empty,
                                wildcards: Vector[String] = Vector.empty, loras: Vector[NetFile] = Vector.empty,
                                embeddings: Vector[NetFile] = Vector.empty)

/** Where `<`/`__` completion applies. `kinds` empty means the mixed `<` list. */
sealed trait Trigger
final case class WildcardTrigger(start: Int, end: Int, query: String) extends Trigger
final case class NetworkTrigger(start: Int, end: Int, head: String, kinds: Set[SuggestionKind], query: String,
                                args: String, hypernet: Boolean) extends Trigger

/** Pure completion logic for the native tag autocomplete (behaviour documented by a1111-sd-webui-tagcomplete: tags with
  * aliases and translations, an extra tag file, `__wildcard__` files, `<e:` `<l:` `<lora:` `<lyco:` `<h:` `<c:` extra
  * networks, chants, LoRA trigger words and preview images). Independent implementation. */
object CompletionEngine:
  private def norm(s: String): String = s.toLowerCase(Locale.ROOT).replace('_', ' ').trim

  // ------------------------------------------------------------------------------------------------ triggers
  private def wildcardChar(c: Char): Boolean = Character.isLetterOrDigit(c) || "_-/.*".indexOf(c) >= 0

  def trigger(text: String, caret: Int, selStart: Int, selEnd: Int): Option[Trigger] =
    if caret < 0 || caret > text.length then None
    else networkTrigger(text, caret, selStart, selEnd).orElse(wildcardTrigger(text, caret, selStart, selEnd))

  def wildcardTrigger(text: String, caret: Int, selStart: Int, selEnd: Int): Option[WildcardTrigger] =
    var left = caret
    while left > 0 && wildcardChar(text.charAt(left - 1)) do left -= 1
    var right = caret
    while right < text.length && wildcardChar(text.charAt(right)) do right += 1
    val run = text.substring(left, right)
    // The run must contain the opening "__" before the caret and not start in the middle of a word.
    val open = run.indexOf("__")
    if open < 0 || left + open + 2 > caret then None
    else
      val start = left + open
      if start > 0 && Character.isLetterOrDigit(text.charAt(start - 1)) then None
      else
        val query = text.substring(start + 2, caret)
        if query.contains("__") then None
        else
          // Replace the rest of the name and its closing "__" when present.
          val after = text.substring(caret, right)
          val closing = after.indexOf("__")
          val end = if closing >= 0 then caret + closing + 2 else right
          if selStart < start || selEnd > end then None else Some(WildcardTrigger(start, end, query))

  private val Heads: Map[String, Set[SuggestionKind]] = Map(
    "e" -> Set(SuggestionKind.Embedding), "emb" -> Set(SuggestionKind.Embedding), "embedding" -> Set(SuggestionKind.Embedding),
    "l" -> Set(SuggestionKind.Lora), "lora" -> Set(SuggestionKind.Lora), "lyco" -> Set(SuggestionKind.Lora),
    "c" -> Set(SuggestionKind.Chant), "chant" -> Set(SuggestionKind.Chant))
  private val HypernetHeads = Set("h", "hypernet", "hypernetwork")

  def networkTrigger(text: String, caret: Int, selStart: Int, selEnd: Int): Option[NetworkTrigger] =
    val lt = if caret == 0 then -1 else text.lastIndexOf('<', caret - 1)
    if lt < 0 then None
    else
      val before = text.substring(lt + 1, caret)
      if before.exists(c => c == '>' || c == '\n' || c == '\r' || c == '<') then None
      else
        // End of the tag: the closing '>' when it follows on the same line, otherwise the end of the word.
        var scan = caret
        while scan < text.length && !">\n\r<, ".contains(text.charAt(scan)) do scan += 1
        val close = scan < text.length && text.charAt(scan) == '>'
        val end = if close then scan + 1 else scan
        val body = text.substring(lt + 1, scan)
        val colon = body.indexOf(':')
        if colon < 0 || colon >= before.length then
          // No colon before the caret: mixed list filtered by the typed name (or a partly typed head such as "lo").
          if colon >= 0 then None
          else if selStart < lt || selEnd > end then None
          else Some(NetworkTrigger(lt, end, "", Set.empty, before.trim, "", hypernet = false))
        else
          val head = body.substring(0, colon).toLowerCase(Locale.ROOT)
          val rest = body.substring(colon + 1)
          val nameEnd = rest.indexOf(':') match { case -1 => rest.length; case i => i }
          val caretInRest = before.length - colon - 1
          if caretInRest > nameEnd then None
          else if selStart < lt || selEnd > end then None
          else
            val query = rest.substring(0, caretInRest).trim
            val args = if nameEnd < rest.length then rest.substring(nameEnd) else ""
            if HypernetHeads(head) then Some(NetworkTrigger(lt, end, head, Set.empty, query, "", hypernet = true))
            else Heads.get(head).map(k => NetworkTrigger(lt, end, head, k, query, args, hypernet = false))

  // ------------------------------------------------------------------------------------------------ complete
  def complete(data: CompletionData, cfg: TagCompleteSettings, text: String, caret: Int, selStart: Int, selEnd: Int): Option[CompletionResult] =
    trigger(text, caret, selStart, selEnd) match
      case Some(w: WildcardTrigger) =>
        if !cfg.wildcards then None
        else
          val matches = ranked(data.wildcards, w.query)(identity)()
          val prefix = if matches.size < 2 then None else Some(matches.tail.foldLeft(matches.head) { (a, b) =>
            a.zip(b).takeWhile((x, y) => Character.toLowerCase(x) == Character.toLowerCase(y)).map(_._1).mkString
          })
          nonEmpty(w.start, w.end, wildcardItems(matches, cfg, text, w)).map(_.copy(wildcardPrefix = prefix))
      case Some(n: NetworkTrigger) =>
        if !cfg.extraNetworks && !(cfg.chants && n.kinds == Set(SuggestionKind.Chant)) then None
        else nonEmpty(n.start, n.end, networkItems(data, cfg, n))
      case None =>
        TagCompletion.token(text, caret, selStart, selEnd).flatMap { t =>
          nonEmpty(t.start, t.end, tagItems(data, cfg, text, t))
        }

  private def nonEmpty(start: Int, end: Int, items: Vector[Suggestion]): Option[CompletionResult] =
    Option.when(items.nonEmpty)(CompletionResult(start, end, items))

  private def comma(cfg: TagCompleteSettings, text: String, end: Int): String =
    if cfg.appendComma && !text.substring(end).trim.startsWith(",") then ", " else ""

  /** Insertion text of a booru tag under the user's options. */
  def formatTag(name: String, cfg: TagCompleteSettings): String =
    val spaced = if cfg.replaceUnderscores then name.replace('_', ' ') else name
    if cfg.escapeParens then spaced.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") else spaced

  private def tagItems(data: CompletionData, cfg: TagCompleteSettings, text: String, t: CompletionToken): Vector[Suggestion] =
    val q = norm(t.query)
    if q.isEmpty then return Vector.empty
    val limit = cfg.maxResults.max(1)
    val tail = comma(cfg, text, t.end)
    def translationOf(name: String, aliases: Vector[String]): Option[String] =
      data.translations.get(norm(name)).orElse(aliases.iterator.flatMap(a => data.translations.get(norm(a))).nextOption())
    // A match is by name, alias (when enabled) or translation of either; the label reflects what matched.
    def matchTag(name: String, aliases: Vector[String], detail: Option[String], kind: SuggestionKind, category: Option[Int]): Option[Suggestion] =
      val byName = norm(name).startsWith(q)
      lazy val byAlias = if cfg.searchByAlias then aliases.find(a => norm(a).startsWith(q)) else None
      lazy val translation = translationOf(name, aliases)
      lazy val byTranslation = data.translations.get(norm(name)).exists(v => norm(v).startsWith(q)) ||
        (if cfg.searchByAlias then aliases.exists(a => data.translations.get(norm(a)).exists(v => norm(v).startsWith(q))) else false)
      if !(byName || byAlias.nonEmpty || byTranslation) then None
      else
        val label =
          if byName then name
          else byAlias match
            case Some(alias) => if cfg.onlyShowAlias then alias else s"$alias ➝ $name"
            case None => name
        val categoryLabel = category.map(c => s"category $c")
        val extra = Seq(translation, detail, categoryLabel).flatten.mkString("  ")
        Some(Suggestion(kind, label, extra, () => formatTag(name, cfg) + tail, category = category))
    val main = data.tags.iterator.flatMap(tag => matchTag(tag.name, tag.aliases, Some(tag.count.toString), SuggestionKind.Tag, tag.category)).take(limit).toVector
    val extra = data.extra.iterator.flatMap(e => matchTag(e.name, e.aliases, Some(if e.meta.isEmpty then "Custom tag" else e.meta), SuggestionKind.Extra, e.category)).take(limit).toVector
    (if cfg.extraAfter then main ++ extra else extra ++ main).take(limit)

  private def ranked[A](items: Iterable[A], q: String)(name: A => String)(terms: A => String = (_: A) => ""): Vector[A] =
    val query = norm(q)
    val scored = items.iterator.flatMap { a =>
      val n = norm(name(a))
      val rank =
        if query.isEmpty then Some(0)
        else if n.startsWith(query) then Some(0)
        else if n.contains(query) then Some(1)
        else if norm(terms(a)).contains(query) then Some(2)
        else None
      rank.map(_ -> a)
    }.toVector
    scored.sortBy(_._1).map(_._2)  // stable: keeps the listed order inside a rank

  private def wildcardItems(matches: Vector[String], cfg: TagCompleteSettings, text: String, w: WildcardTrigger): Vector[Suggestion] =
    matches.take(cfg.maxResults.max(1)).map { name =>
      Suggestion(SuggestionKind.Wildcard, name, "wildcard", () => s"__${name}__" + comma(cfg, text, w.end))
    }

  /** Candidate preview images next to a model file, in the order the A1111 extra-network cards use. */
  def previewFor(model: Path): Option[Path] =
    val base = model.getFileName.toString
    val stem = base.lastIndexOf('.') match { case i if i > 0 => base.substring(0, i); case _ => base }
    Option(model.getParent).flatMap { d =>
      Seq("preview.png", "png", "preview.jpg", "jpg", "preview.jpeg", "jpeg", "preview.webp", "webp", "preview.gif")
        .map(ext => d.resolve(s"$stem.$ext")).find(p => Files.isRegularFile(p))
    }

  /** `activation text` of the A1111 extra-networks metadata sidecar `<model>.json` (webui 1.5+). */
  def triggerWords(model: Path): Option[String] =
    val base = model.getFileName.toString
    val stem = base.lastIndexOf('.') match { case i if i > 0 => base.substring(0, i); case _ => base }
    val side = model.resolveSibling(s"$stem.json")
    try
      if !Files.isRegularFile(side) || Files.size(side) > 1024 * 1024 then None
      else
        val json = ujson.read(new String(Files.readAllBytes(side), StandardCharsets.UTF_8))
        json.objOpt.flatMap(_.get("activation text")).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
    catch case _: Exception => None

  private def networkItems(data: CompletionData, cfg: TagCompleteSettings, n: NetworkTrigger): Vector[Suggestion] =
    if n.hypernet then return Vector.empty // This app has no hypernetwork loader: nothing to offer.
    val all = n.kinds.isEmpty
    val limit = cfg.maxResults.max(1)
    def has(k: SuggestionKind) = all || n.kinds(k)
    val names = if n.head == "lyco" then "lyco" else "lora"
    val loras =
      if has(SuggestionKind.Lora) then ranked(data.loras, n.query)(_.name)().map { f =>
        Suggestion(SuggestionKind.Lora, f.name, "LoRA", () => {
          val tag = LoraSyntax.tag(f.name, 1.0).replaceFirst("^<lora:", s"<$names:")
          val withArgs = if n.args.nonEmpty then s"<$names:${f.name}${n.args}>" else tag
          val words = if cfg.loraTriggerWords then triggerWords(f.path) else None
          withArgs + words.map(", " + _).getOrElse("")
        }, () => previewFor(f.path))
      } else Vector.empty
    val embeddings =
      if has(SuggestionKind.Embedding) then ranked(data.embeddings, n.query)(_.name)().map { f =>
        Suggestion(SuggestionKind.Embedding, f.name, "embedding", () => f.name, () => previewFor(f.path))
      } else Vector.empty
    val chants =
      if has(SuggestionKind.Chant) && cfg.chants then ranked(data.chants, n.query)(_.name)(_.terms).map { c =>
        val category = Option.when(c.colorSpecified)(c.color)
        val detail = category.fold("chant")(v => s"chant  category $v")
        Suggestion(SuggestionKind.Chant, c.name, detail, () => c.content, category = category)
      } else Vector.empty
    (loras ++ embeddings ++ chants).take(limit)

  // ------------------------------------------------------------------------------------------------ files
  /** `english,translation` (or the legacy 3-column `english,<skipped>,translation`); first column is also a tag alias key. */
  def parseTranslations(lines: Iterator[String], legacy: Boolean): Map[String, String] =
    val builder = Map.newBuilder[String, String]
    var count = 0
    lines.zipWithIndex.filter(_._1.trim.nonEmpty).foreach { (line, index) =>
      count += 1
      require(count <= 600000, "Translation CSV exceeds 600,000 rows")
      val f = TagCompletion.csvRow(line.stripPrefix("﻿"))
      val want = if legacy then 3 else 2
      require(f.size >= want, s"Translation CSV row ${index + 1}: expected ${if legacy then "english,ignored,translation" else "english,translation"}")
      val key = norm(f(0))
      val value = f(if legacy then 2 else 1).trim
      if key.nonEmpty && value.nonEmpty then builder += key -> value
    }
    val map = builder.result()
    require(map.nonEmpty, "Translation CSV is empty")
    map

  /** Extra file rows: `name,type,meta,"alias1,alias2"`; the meta text replaces the post count. */
  def parseExtra(lines: Iterator[String]): Vector[ExtraTag] =
    val tags = lines.zipWithIndex.filter(_._1.trim.nonEmpty).map { (line, index) =>
      require(index < 300000, "Extra tag CSV exceeds 300,000 rows")
      val f = TagCompletion.csvRow(line.stripPrefix("﻿"))
      require(f.nonEmpty && f(0).trim.nonEmpty, s"Extra tag CSV row ${index + 1}: missing tag")
      ExtraTag(f(0).trim, f.lift(2).map(_.trim).getOrElse(""), f.lift(3).toVector.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty), f.lift(1).flatMap(_.trim.toIntOption))
    }.toVector
    require(tags.nonEmpty, "Extra tag CSV is empty")
    tags

  def parseChants(json: String): Vector[Chant] =
    val arr = ujson.read(json).arr
    require(arr.size <= 20000, "Chants file has more than 20,000 entries")
    val chants = arr.toVector.zipWithIndex.map { (v, i) =>
      val o = v.obj
      def s(k: String) = o.get(k).map(_.str).getOrElse("")
      val color = o.get("color").flatMap(_.numOpt).map(_.toInt)
      val chant = Chant(s("name").trim, s("terms"), s("content"), color.getOrElse(0), color.nonEmpty)
      require(chant.name.nonEmpty && chant.content.nonEmpty, s"Chant ${i + 1} needs name and content")
      chant
    }
    require(chants.nonEmpty, "Chants file is empty")
    chants

  private def readText(path: Path, what: String): String =
    require(Files.size(path) <= 32L * 1024 * 1024, s"$what exceeds 32 MiB")
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  def loadTranslations(path: Path, legacy: Boolean): Map[String, String] =
    parseTranslations(readText(path, "Translation CSV").linesIterator, legacy)
  def loadExtra(path: Path): Vector[ExtraTag] = parseExtra(readText(path, "Extra tag CSV").linesIterator)
  def loadChants(path: Path): Vector[Chant] = parseChants(readText(path, "Chants file"))
