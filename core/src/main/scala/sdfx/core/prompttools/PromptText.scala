package sdfx.core.prompttools

/** Pure prompt-text operations shared by the prompt tools: tag splitting, insertion, removal and weights.
  * Tags are separated by top-level commas; commas inside `()`, `[]`, `{}` or `<>` do not split.
  */
object PromptText:
  /** Split into trimmed, non-empty tags on commas outside brackets (backslash escapes the next character). */
  def splitTags(text: String): Vector[String] =
    val out = Vector.newBuilder[String]
    val cur = new StringBuilder
    var depth = 0
    var escaped = false
    text.foreach { c =>
      if escaped then { cur += c; escaped = false }
      else c match
        case '\\' => cur += c; escaped = true
        case '(' | '[' | '{' | '<' => depth += 1; cur += c
        case ')' | ']' | '}' | '>' => depth = math.max(0, depth - 1); cur += c
        case ',' | '、' | '，' if depth == 0 =>
          val t = cur.toString.trim
          if t.nonEmpty then out += t
          cur.clear()
        case _ => cur += c
    }
    val t = cur.toString.trim
    if t.nonEmpty then out += t
    out.result()

  def join(tags: Seq[String]): String = tags.mkString(", ")

  private def key(tag: String): String = tag.trim.toLowerCase(java.util.Locale.ROOT)

  /** Append `snippet` to `text` with a single comma separator. With `skipDuplicates` the tags already
    * present are not added again; the result equals `text` when everything was a duplicate.
    */
  def insert(text: String, snippet: String, skipDuplicates: Boolean = true): String =
    val add0 = splitTags(snippet)
    val have = splitTags(text).map(key).toSet
    val add = if skipDuplicates then add0.filterNot(t => have(key(t))) else add0
    if add.isEmpty then text
    else
      val body = text.stripTrailing()
      val joined = join(add)
      if body.isEmpty then joined
      else if body.endsWith(",") then body + " " + joined
      else body + ", " + joined

  /** True when every tag of `snippet` is present in `text`. */
  def contains(text: String, snippet: String): Boolean =
    val want = splitTags(snippet).map(key)
    val have = splitTags(text).map(key).toSet
    want.nonEmpty && want.forall(have)

  /** Remove the tags of `snippet` from `text`. None when none of them is present. */
  def remove(text: String, snippet: String): Option[String] =
    val drop = splitTags(snippet).map(key).toSet
    val tags = splitTags(text)
    val kept = tags.filterNot(t => drop(key(t)))
    if kept.size == tags.size then None else Some(join(kept))

  /** Drop repeated tags (case-insensitive), keeping the first. */
  def dedupe(tags: Seq[String]): Vector[String] =
    val seen = scala.collection.mutable.HashSet.empty[String]
    tags.filter(t => seen.add(key(t))).toVector

  /** Remove blacklisted tags. A word matches a tag whole (ignoring case and any `(...:w)` / `[...]` wrapping). */
  def applyBlacklist(text: String, words: Seq[String]): String =
    val bad = words.map(key).filter(_.nonEmpty).toSet
    join(splitTags(text).filterNot(t => bad(key(core(t)._1))))

  private val Weighted = """^\((.*):\s*(-?\d+(?:\.\d+)?)\)$""".r

  private def round4(d: Double): Double = BigDecimal(d).setScale(4, BigDecimal.RoundingMode.HALF_UP).toDouble

  /** The bare text and weight of a tag: `(x:1.2)` -> (x, 1.2), `(x)` -> (x, 1.1), `[x]` -> (x, 0.91). */
  def core(tag: String): (String, Double) =
    val t = tag.trim
    t match
      case Weighted(inner, w) if balanced(inner) => (inner.trim, w.toDouble)
      case _ if t.length > 1 && t.startsWith("(") && t.endsWith(")") && balanced(t.substring(1, t.length - 1)) =>
        val (c, w) = core(t.substring(1, t.length - 1)); (c, round4(w * 1.1))
      case _ if t.length > 1 && t.startsWith("[") && t.endsWith("]") && balanced(t.substring(1, t.length - 1)) =>
        val (c, w) = core(t.substring(1, t.length - 1)); (c, round4(w / 1.1))
      case _ => (t, 1.0)

  private def balanced(s: String): Boolean =
    var d = 0
    var esc = false
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if esc then esc = false
      else if c == '\\' then esc = true
      else if c == '(' || c == '[' then d += 1
      else if c == ')' || c == ']' then d -= 1
      if d < 0 then return false
      i += 1
    d == 0

  /** Change a tag's weight by `delta` (default one 0.1 step) in the explicit `(text:w)` form.
    * Network tags (`<lora:...>`) and BREAK are returned unchanged. Weight 1.0 yields the bare text.
    */
  def adjustWeight(tag: String, delta: Double = 0.1): String =
    val t = tag.trim
    if t.startsWith("<") || t == "BREAK" || t.isEmpty then t
    else
      val (text, w) = core(t)
      val nw = BigDecimal(w + delta).setScale(2, BigDecimal.RoundingMode.HALF_UP).max(BigDecimal(0)).min(BigDecimal(5))
      if nw == BigDecimal(1) then text else s"($text:${nw.bigDecimal.stripTrailingZeros.toPlainString})"

  /** Move the element at `from` so it ends at index `to`. */
  def moveTag[A](tags: Vector[A], from: Int, to: Int): Vector[A] =
    if from < 0 || from >= tags.size || to < 0 || to >= tags.size || from == to then tags
    else
      val item = tags(from)
      tags.patch(from, Nil, 1).patch(to, Seq(item), 0)
