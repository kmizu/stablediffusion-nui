package sdfx.ui.vm

import sdfx.core.prompttools.PromptText
import java.util.Locale

/** Optional, ordinary text candidates; none is selected or applied automatically. */
object NegativeSuggestions:
  val items: Vector[String] = Vector(
    "low quality", "worst quality", "blurry", "jpeg artifacts",
    "bad anatomy", "bad proportions", "extra fingers", "extra limbs",
    "watermark", "signature", "text")
  val presets: Vector[(String, Vector[String])] = Vector(
    "Quality" -> Vector("low quality", "worst quality", "blurry", "jpeg artifacts"),
    "Anatomy" -> Vector("bad anatomy", "bad proportions", "extra fingers", "extra limbs"),
    "Text and marks" -> Vector("watermark", "signature", "text"))
  val descriptions: Map[String, String] = Map(
    "low quality" -> "Low-quality image", "worst quality" -> "Very low-quality image",
    "blurry" -> "Blurred image", "jpeg artifacts" -> "JPEG compression artifacts",
    "bad anatomy" -> "Anatomical errors", "bad proportions" -> "Incorrect proportions",
    "extra fingers" -> "Extra fingers", "extra limbs" -> "Extra limbs",
    "watermark" -> "Watermark", "signature" -> "Signature", "text" -> "Unwanted text")

  /** Match PromptText.splitTags: backslash escapes the next character and all brackets count toward depth. */
  private def tail(text: String): (Int, Boolean, Boolean) =
    var depth = 0
    var escaped = false
    var trailingSeparator = false
    text.foreach { c =>
      if escaped then
        escaped = false
        trailingSeparator = false
      else c match
        case '\\' => escaped = true; trailingSeparator = false
        case '(' | '[' | '{' | '<' => depth += 1; trailingSeparator = false
        case ')' | ']' | '}' | '>' => depth = math.max(0, depth - 1); trailingSeparator = false
        case ',' | '、' | '，' => trailingSeparator = depth == 0
        case x if x.isWhitespace => ()
        case _ => trailingSeparator = false
    }
    (depth, escaped, trailingSeparator)

  /** Return only the text to insert, preserving the caller's existing text exactly. */
  def suffix(existing: String, selected: Seq[String]): String =
    def key(s: String): String = s.trim.toLowerCase(Locale.ROOT)
    val seen = scala.collection.mutable.Set.from(PromptText.splitTags(existing).map(key))
    val add = selected.iterator.map(_.trim).filter(_.nonEmpty).filter(s => seen.add(key(s))).toVector
    if add.isEmpty then ""
    else
      val (depth, escaped, hasSeparator) = tail(existing)
      require(depth == 0 && !escaped, "Close the unfinished bracket or escape before adding suggestions")
      val separator =
        if existing.trim.isEmpty then ""
        else if hasSeparator then
          if existing.last.isWhitespace then "" else " "
        else ", "
      separator + add.mkString(", ")
