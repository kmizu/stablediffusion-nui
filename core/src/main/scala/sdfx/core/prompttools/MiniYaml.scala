package sdfx.core.prompttools

/** Parsed YAML node (only the subset used by tag files: nested mappings, sequences and scalars). */
enum YNode:
  case YMap(entries: Vector[(String, YNode)])
  case YSeq(items: Vector[YNode])
  case YStr(value: String)

/** A small block-YAML reader: mappings, sequences, plain/quoted scalars, `[a, b]` flow sequences, `#` comments.
  * Anchors, tags, multi-line scalars and flow mappings are rejected with a line-numbered error rather than guessed.
  */
object MiniYaml:
  import YNode.*

  final class YamlError(message: String, val line: Int) extends IllegalArgumentException(s"line $line: $message")

  private final case class Line(no: Int, indent: Int, text: String)

  val MaxDepth = 24
  val MaxBytes = 4 * 1024 * 1024

  def parse(source: String): YNode =
    if source.length > MaxBytes then throw new YamlError("file is too large", 1)
    val lines = source.stripPrefix("﻿").split("\r?\n", -1).zipWithIndex.flatMap { (raw, i) =>
      val no = i + 1
      val stripped = stripComment(raw)
      if stripped.trim.isEmpty then None
      else
        val indent = stripped.takeWhile(_ == ' ').length
        if stripped.drop(indent).startsWith("\t") || stripped.take(indent + 1).contains('\t') then throw new YamlError("tabs cannot indent", no)
        Some(Line(no, indent, stripped.drop(indent).stripTrailing()))
    }.toVector
    if lines.isEmpty then YMap(Vector.empty)
    else
      val (node, next) = block(lines, 0, lines.head.indent, 0)
      if next < lines.size then throw new YamlError("unexpected indentation", lines(next).no)
      node

  private def stripComment(line: String): String =
    var quote = '\u0000'
    var i = 0
    while i < line.length do
      val c = line.charAt(i)
      if quote != '\u0000' then
        if c == '\\' && quote == '"' then i += 1
        else if c == quote then quote = '\u0000'
      else if (c == '"' || c == '\'') && (i == 0 || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '-' || line.charAt(i - 1) == ':' || line.charAt(i - 1) == '[' || line.charAt(i - 1) == ',') then quote = c
      else if c == '#' && (i == 0 || line.charAt(i - 1) == ' ') then return line.substring(0, i)
      i += 1
    line

  private def isItem(t: String): Boolean = t == "-" || t.startsWith("- ")

  private def block(lines: Vector[Line], from: Int, indent: Int, depth: Int): (YNode, Int) =
    if depth > MaxDepth then throw new YamlError("nesting is too deep", lines(from).no)
    if isItem(lines(from).text) then sequence(lines, from, indent, depth) else mapping(lines, from, indent, depth)

  private def sequence(lines: Vector[Line], from: Int, indent: Int, depth: Int): (YNode, Int) =
    val items = Vector.newBuilder[YNode]
    var i = from
    while i < lines.size && lines(i).indent == indent && isItem(lines(i).text) do
      val l = lines(i)
      val rest = l.text.drop(1).dropWhile(_ == ' ')
      if rest.isEmpty then
        if i + 1 < lines.size && lines(i + 1).indent > indent then
          val (n, next) = block(lines, i + 1, lines(i + 1).indent, depth + 1)
          items += n; i = next
        else { items += YStr(""); i += 1 }
      else if keyValue(rest).isDefined then
        // "- key: value": the mapping continues on following lines indented past the dash
        val innerIndent = indent + (l.text.length - rest.length)
        val patched = lines.updated(i, Line(l.no, innerIndent, rest))
        val (n, next) = mapping(patched, i, innerIndent, depth + 1)
        items += n; i = next
      else { items += scalarOrFlow(rest, l.no); i += 1 }
    (YSeq(items.result()), i)

  private def mapping(lines: Vector[Line], from: Int, indent: Int, depth: Int): (YNode, Int) =
    val entries = Vector.newBuilder[(String, YNode)]
    var i = from
    while i < lines.size && lines(i).indent == indent && !isItem(lines(i).text) do
      val l = lines(i)
      val (key, value) = keyValue(l.text).getOrElse(throw new YamlError("expected 'key: value'", l.no))
      if value.nonEmpty then
        if value.startsWith("|") || value.startsWith(">") then throw new YamlError("multi-line scalars are not supported", l.no)
        entries += (key -> scalarOrFlow(value, l.no)); i += 1
      else if i + 1 < lines.size && lines(i + 1).indent > indent then
        val (n, next) = block(lines, i + 1, lines(i + 1).indent, depth + 1)
        entries += (key -> n); i = next
      else if i + 1 < lines.size && lines(i + 1).indent == indent && isItem(lines(i + 1).text) then
        val (n, next) = sequence(lines, i + 1, indent, depth + 1)
        entries += (key -> n); i = next
      else { entries += (key -> YStr("")); i += 1 }
    if i < lines.size && lines(i).indent == indent then throw new YamlError("expected 'key: value'", lines(i).no)
    (YMap(entries.result()), i)

  /** Split "key: value" at the first colon that is outside quotes and followed by a space or the end. */
  private def keyValue(text: String): Option[(String, String)] =
    if text.startsWith("\"") || text.startsWith("'") then
      val q = text.charAt(0)
      val end = closing(text, 0)
      if end < 0 then None
      else
        val after = text.substring(end + 1)
        if after.startsWith(":") && (after.length == 1 || after.charAt(1) == ' ') then
          Some(unquote(text.substring(0, end + 1), 0) -> after.drop(1).trim)
        else None
    else
      var i = 0
      while i < text.length do
        if text.charAt(i) == ':' && (i + 1 == text.length || text.charAt(i + 1) == ' ') then
          return Some(text.substring(0, i).trim -> text.substring(i + 1).trim).filter(_._1.nonEmpty)
        i += 1
      None

  private def closing(s: String, start: Int): Int =
    val q = s.charAt(start)
    var i = start + 1
    while i < s.length do
      val c = s.charAt(i)
      if q == '"' && c == '\\' then i += 1
      else if c == q then
        if q == '\'' && i + 1 < s.length && s.charAt(i + 1) == '\'' then i += 1 else return i
      i += 1
    -1

  private def unquote(s: String, line: Int): String =
    val q = s.charAt(0)
    val body = s.substring(1, s.length - 1)
    if q == '\'' then body.replace("''", "'")
    else
      val sb = new StringBuilder
      var i = 0
      while i < body.length do
        val c = body.charAt(i)
        if c == '\\' && i + 1 < body.length then
          i += 1
          body.charAt(i) match
            case 'n' => sb += '\n'
            case 't' => sb += '\t'
            case '"' => sb += '"'
            case '\\' => sb += '\\'
            case '/' => sb += '/'
            case other => sb += '\\'; sb += other
        else sb += c
        i += 1
      sb.toString

  private def scalarOrFlow(text: String, line: Int): YNode =
    val t = text.trim
    if t.startsWith("[") then
      if !t.endsWith("]") then throw new YamlError("unterminated [ list", line)
      YSeq(flowItems(t.substring(1, t.length - 1), line).map(YStr(_)))
    else if t.startsWith("{") then throw new YamlError("flow mappings are not supported", line)
    else if t.startsWith("&") || t.startsWith("*") || t.startsWith("!") then throw new YamlError("anchors and tags are not supported", line)
    else YStr(scalar(t, line))

  private def scalar(t: String, line: Int): String =
    if t.startsWith("\"") || t.startsWith("'") then
      val end = closing(t, 0)
      if end != t.length - 1 then throw new YamlError("bad quoted text", line)
      unquote(t, line)
    else t

  private def flowItems(body: String, line: Int): Vector[String] =
    val out = Vector.newBuilder[String]
    val cur = new StringBuilder
    var quote = '\u0000'
    var i = 0
    while i < body.length do
      val c = body.charAt(i)
      if quote != '\u0000' then
        cur += c
        if c == '\\' && quote == '"' && i + 1 < body.length then { i += 1; cur += body.charAt(i) }
        else if c == quote then quote = '\u0000'
      else if (c == '"' || c == '\'') && cur.toString.trim.isEmpty then { quote = c; cur += c }
      else if c == ',' then { out += scalar(cur.toString.trim, line); cur.clear() }
      else cur += c
      i += 1
    if quote != '\u0000' then throw new YamlError("unterminated quote", line)
    val last = cur.toString.trim
    if last.nonEmpty then out += scalar(last, line)
    out.result()
