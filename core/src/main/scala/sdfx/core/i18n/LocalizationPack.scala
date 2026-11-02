package sdfx.core.i18n

import java.util.regex.{Matcher, Pattern, PatternSyntaxException}
import scala.collection.mutable

/** Anything that can translate one English UI string. */
trait Translations:
  /** Translation of `source`, or None when there is none (or it equals the source). `scope` is the element's identity for scoped keys. */
  def translate(source: String, scope: Option[ScopeNode] = None): Option[String]
  def isEmpty: Boolean
  /** True when some rule is scoped, so callers know whether building scope information is worth it. */
  def hasScoped: Boolean

/** A language pack in the A1111 localization format: one flat JSON object, English text -> translation.
  *
  * Besides plain keys the format of the bilingual-localization extensions is understood:
  *  - `@@/regex/flags` (or `@@literal`): regular-expression rule; `$1`.. `$&` `$$` in the value expand like JavaScript's replace.
  *  - `##<id>##text` and `##@<selector>##text`: the translation only applies below an element with that id / selector.
  */
final class LocalizationPack private (
    val id: String,
    private val plain: Map[String, String],
    private val regexRules: Vector[LocalizationPack.RegexRule],
    private val scopedRules: Map[String, Vector[LocalizationPack.ScopedRule]],
    val skippedValues: Int,
    val warnings: Vector[String]
) extends Translations:
  import LocalizationPack.*

  def plainCount: Int = plain.size
  def regexCount: Int = regexRules.size
  def scopedCount: Int = scopedRules.valuesIterator.map(_.size).sum
  def size: Int = plainCount + regexCount + scopedCount
  def isEmpty: Boolean = size == 0
  def hasScoped: Boolean = scopedRules.nonEmpty
  def containsPlain(source: String): Boolean = plain.contains(source)

  def translate(source: String, scope: Option[ScopeNode] = None): Option[String] =
    if source == null || source.length > MaxSourceLength then return None
    val trimmed = source.trim
    if trimmed.isEmpty || Numeric.matcher(trimmed).matches() then return None
    val lead = source.substring(0, source.indexOf(trimmed.head))
    val tail = source.substring(source.lastIndexOf(trimmed.last) + 1)
    exact(trimmed, scope).orElse(fuzzy(trimmed, scope)).filter(t => t != trimmed).map(t => lead + t + tail)

  /** Exact key, then regex rules, then scoped overrides (the order of the upstream extension). */
  private def exact(s: String, scope: Option[ScopeNode]): Option[String] =
    var result = plain.get(s).filter(_.nonEmpty).orElse(regexTranslate(s))
    for sc <- scope; rules <- scopedRules.get(s) do
      rules.find(_.selector.closest(sc)).foreach(r => result = Some(r.translation).filter(_.nonEmpty).orElse(result))
    result

  /** Same text without decoration: leading icons / bullets and trailing `:` `…` `.`. Keeps the decoration around the translation. */
  private def fuzzy(s: String, scope: Option[ScopeNode]): Option[String] =
    var i = 0
    while i < s.length && { val cp = s.codePointAt(i); !Character.isLetterOrDigit(cp) } do i += Character.charCount(s.codePointAt(i))
    val start = i
    var end = s.length
    while end > start && "：:…. ".indexOf(s.charAt(end - 1)) >= 0 do end -= 1
    if (start == 0 && end == s.length) || start >= end then None
    else
      val core = s.substring(start, end)
      exact(core, scope).filter(_ != core).map(t => s.substring(0, start) + t + s.substring(end))

  private def regexTranslate(s: String): Option[String] =
    if regexRules.isEmpty || s.length > MaxRegexInput then return None
    regexRules.iterator.map(_.apply(s)).collectFirst { case Some(v) => v }

object LocalizationPack:
  val MaxFileBytes: Long = 16L * 1024 * 1024
  val MaxEntries = 300000
  val MaxKeyLength = 4096
  val MaxValueLength = 16384
  val MaxSourceLength = 4096
  val MaxRegexInput = 1024
  val MaxRegexRules = 2000
  private val Numeric = Pattern.compile("^[.\\d]+$")
  private val ScopedKey = Pattern.compile("^##(.+)##(.+)$")

  /** A pack with nothing in it (language "None"). */
  val Empty: LocalizationPack = new LocalizationPack("none", Map.empty, Vector.empty, Map.empty, 0, Vector.empty)

  final class ScopedRule(val selector: Selector, val translation: String)

  final class RegexRule(val pattern: Pattern, val replacement: String, val global: Boolean):
    def apply(s: String): Option[String] =
      try
        val m = pattern.matcher(new BoundedChars(s))
        if !m.find() then None
        else
          val out = new java.lang.StringBuilder
          var last = 0
          var found = true
          while found do
            out.append(s, last, m.start())
            expand(out, m)
            last = m.end()
            found = global && m.end() > m.start() && m.find()
          out.append(s, last, s.length)
          Some(out.toString).filter(_.nonEmpty)
      catch case _: BudgetExceeded => None

    /** JavaScript replacement patterns: `$$`, `$&`, `$n`, `$nn`. */
    private def expand(out: java.lang.StringBuilder, m: Matcher): Unit =
      var i = 0
      val r = replacement
      while i < r.length do
        val c = r.charAt(i)
        if c == '$' && i + 1 < r.length then
          val d = r.charAt(i + 1)
          if d == '$' then { out.append('$'); i += 2 }
          else if d == '&' then { out.append(m.group()); i += 2 }
          else if d.isDigit then
            val two = if i + 2 < r.length && r.charAt(i + 2).isDigit then (d - '0') * 10 + (r.charAt(i + 2) - '0') else -1
            if two >= 1 && two <= m.groupCount then { Option(m.group(two)).foreach(out.append); i += 3 }
            else
              val one = d - '0'
              if one >= 1 && one <= m.groupCount then { Option(m.group(one)).foreach(out.append); i += 2 }
              else { out.append(c); i += 1 }
          else { out.append(c); i += 1 }
        else { out.append(c); i += 1 }

  /** Bounds the work a pattern may do on one input, so a hostile or sloppy pack cannot freeze the UI thread. */
  private final class BoundedChars(s: String) extends CharSequence:
    private var budget = 400000
    def length: Int = s.length
    def charAt(i: Int): Char =
      budget -= 1
      if budget < 0 then throw new BudgetExceeded
      s.charAt(i)
    def subSequence(a: Int, b: Int): CharSequence = s.subSequence(a, b)
    override def toString: String = s
  private final class BudgetExceeded extends RuntimeException(null, null, false, false)

  /** Parses `@@` keys: `/pattern/flags`, or any other text taken literally (unanchored), as the upstream extension does. */
  private[i18n] def parseRegexKey(raw: String): Either[String, (Pattern, Boolean)] =
    val key = raw.trim
    try
      val parts = key.split("/", -1)
      if key.isEmpty then Left("empty pattern")
      else if key.charAt(0) != '/' || parts.length < 3 then Right((Pattern.compile(Pattern.quote(key)), false))
      else
        val last = key.lastIndexOf('/')
        val flags = key.substring(last + 1)
        val body = key.substring(1, last)
        var f = 0
        var global = false
        flags.foreach {
          case 'i' => f |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
          case 'm' => f |= Pattern.MULTILINE
          case 's' => f |= Pattern.DOTALL
          case 'g' => global = true
          case 'u' | 'y' | 'd' => ()
          case other => throw new PatternSyntaxException(s"unknown flag '$other'", key, -1)
        }
        Right((Pattern.compile(body, f), global))
    catch case e: PatternSyntaxException => Left(e.getDescription)

  /** Parses the text of a pack file. `Left` for text that is not a JSON object or is over a limit. */
  def parse(id: String, text: String): Either[String, LocalizationPack] =
    val body = if text.startsWith("﻿") then text.substring(1) else text
    val json =
      try ujson.read(body)
      catch case e: Exception => return Left("not valid JSON: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(160))
    val obj = json.objOpt match
      case Some(o) => o
      case None => return Left("a language pack must be a JSON object (English text -> translation)")
    if obj.value.size > MaxEntries then return Left(s"too many entries (${obj.value.size} > $MaxEntries)")
    val plain = mutable.LinkedHashMap.empty[String, String]
    val regexes = Vector.newBuilder[RegexRule]
    val scoped = mutable.LinkedHashMap.empty[String, Vector[ScopedRule]]
    val warnings = Vector.newBuilder[String]
    var skipped = 0
    var regexCount = 0
    for (k, v) <- obj.value do
      v.strOpt match
        case None => skipped += 1
        case Some(value) if k.length > MaxKeyLength || value.length > MaxValueLength => skipped += 1
        case Some(value) =>
          if k.startsWith("@@") then
            parseRegexKey(k.drop(2)) match
              case Right((p, g)) if regexCount < MaxRegexRules => regexCount += 1; regexes += new RegexRule(p, value, g)
              case Right(_) => skipped += 1
              case Left(why) => warnings += s"regex rule ignored ($why): ${k.take(80)}"
          else
            val m = ScopedKey.matcher(k)
            if m.matches() then
              val scope = m.group(1)
              val text = m.group(2)
              val selectorText = if scope.startsWith("@") then scope.drop(1) else "#" + scope
              if selectorText.isEmpty then skipped += 1
              else
                Selector.parse(selectorText) match
                  case Right(sel) => scoped(text) = scoped.getOrElse(text, Vector.empty) :+ new ScopedRule(sel, value)
                  case Left(why) => warnings += s"scoped rule ignored ($why): ${k.take(80)}"
            else plain(k) = value
    Right(new LocalizationPack(id, plain.toMap, regexes.result(), scoped.toMap, skipped, warnings.result().take(50)))

/** Several packs in priority order: the first one that has a translation wins (selected pack, then fallback pack). */
final class LayeredTranslations(val layers: Seq[LocalizationPack]) extends Translations:
  def translate(source: String, scope: Option[ScopeNode] = None): Option[String] =
    layers.iterator.map(_.translate(source, scope)).collectFirst { case Some(t) => t }
  def isEmpty: Boolean = layers.forall(_.isEmpty)
  def hasScoped: Boolean = layers.exists(_.hasScoped)
