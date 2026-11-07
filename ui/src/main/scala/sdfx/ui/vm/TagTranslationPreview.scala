package sdfx.ui.vm

import java.util.Locale
import scala.util.matching.Regex

/** Bounded read-only hints. Spans use JavaFX/Java UTF-16 indices. */
object TagTranslationPreview:
  final case class Hit(start: Int, end: Int, translation: String)
  private val word: Regex = "[\\p{L}\\p{N}]+".r
  val MaxChars = 16384
  val MaxHits = 100
  val MaxLabelCodePoints = 120
  def display(text: String): String =
    val count = text.codePointCount(0, text.length)
    if count <= MaxLabelCodePoints then text
    else text.substring(0, text.offsetByCodePoints(0, MaxLabelCodePoints)) + "…"
  def find(text: String, translations: Map[String, String]): Vector[Hit] =
    val input = Option(text).getOrElse("").take(MaxChars)
    val words = word.findAllMatchIn(input).toVector
    val out = Vector.newBuilder[Hit]
    var i = 0
    var count = 0
    while i < words.size && count < MaxHits do
      var n = math.min(3, words.size - i)
      var hit: Option[Hit] = None
      while n > 0 && hit.isEmpty do
        val start = words(i).start
        val end = words(i+n-1).end
        val phrase = input.substring(start, end)
        if phrase.forall(c => Character.isLetterOrDigit(c) || c == '_' || c == ' ' || c == '\t') then
          val key = phrase.toLowerCase(Locale.ROOT).replace('_', ' ').trim.replaceAll("[ \\t]+", " ")
          hit = translations.get(key).filter(_.nonEmpty).map(Hit(start, end, _))
        if hit.isEmpty then n -= 1
      hit match
        case Some(h) => out += h; count += 1; i += n
        case None => i += 1
    out.result()
