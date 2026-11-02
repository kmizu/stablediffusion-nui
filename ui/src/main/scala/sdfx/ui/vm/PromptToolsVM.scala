package sdfx.ui.vm

import javafx.beans.property.StringProperty
import sdfx.core.prompttools.*

import scala.util.Random
import scala.util.control.NonFatal

/** Glue between the prompt tools and the generation tab's prompt fields. No JavaFX controls: the fields are plain
  * string properties supplied by `target` (positive, negative), so everything here is testable without a toolkit.
  */
final class PromptToolsVM(val service: PromptToolsService, target: () => (StringProperty, StringProperty),
                          rnd: Random = new Random()):
  private def field(negative: Boolean): StringProperty = if negative then target()._2 else target()._1
  def text(negative: Boolean): String = Option(field(negative).get).getOrElse("")

  private def attempt[A](f: => A): Either[String, A] =
    try Right(f) catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))

  // ---- tag buttons / cards -----------------------------------------------------------------------------
  /** Insert a snippet. True when the field changed (false when everything was already present). */
  def insert(snippet: String, negative: Boolean, skipDuplicates: Boolean = true): Boolean =
    val before = text(negative)
    val after = PromptText.insert(before, snippet, skipDuplicates)
    if after == before then false else { field(negative).set(after); true }

  /** Remove the snippet's tags. True when something was removed. */
  def remove(snippet: String, negative: Boolean): Boolean =
    PromptText.remove(text(negative), snippet) match
      case Some(after) => field(negative).set(after); true
      case None => false

  /** Insert a random leaf of the group; returns its text. */
  def insertRandom(group: TagNode.Group, negative: Boolean): Option[String] =
    TagSelector.randomLeaf(group, rnd).map { leaf => insert(leaf.text, negative); leaf.text }

  // ---- saved prompts (Multi-Prompt Manager) ------------------------------------------------------------
  def saveCurrent(name: String, negative: Boolean): Either[String, String] =
    attempt {
      val body = text(negative)
      require(body.trim.nonEmpty, "The " + (if negative then "negative " else "") + "prompt is empty")
      var used = ""
      service.updateLibrary { lib => val (next, n) = lib.save(name, body, negative); used = n; next }
      used
    }

  /** Save arbitrary text (the editor box) under `name`; a blank name becomes "prompt N". Returns the name used. */
  def saveText(name: String, body: String, negative: Boolean): Either[String, String] =
    attempt {
      require(body.trim.nonEmpty, "The prompt text is empty")
      var used = ""
      service.updateLibrary { lib => val (next, n) = lib.save(name, body, negative); used = n; next }
      used
    }

  /** Replace the positive or negative field with `body`. */
  def useText(body: String, negative: Boolean): Unit = field(negative).set(body)

  /** Replace the matching field with the saved text. Returns which field was written. */
  def use(name: String): Either[String, Boolean] =
    attempt {
      val entry = service.library.get(name).getOrElse(throw new NoSuchElementException(s"No saved prompt named '$name'"))
      field(entry.negative).set(entry.prompt)
      entry.negative
    }

  def delete(name: String): Either[String, Unit] = attempt { service.updateLibrary(_.delete(name)); () }
  def rename(from: String, to: String): Either[String, Unit] = attempt { service.updateLibrary(_.rename(from, to)); () }

  // ---- history / favourites (prompt-all-in-one) --------------------------------------------------------
  def favoriteCurrent(name: String): Either[String, Unit] =
    attempt { service.updateHistory(_.addFavorite(text(false), text(true), service.now, name)); () }

  /** Put a history/favourite record back into both fields. */
  def restore(record: PromptRecord): Unit =
    target()._1.set(record.prompt)
    target()._2.set(record.negative)

  def recordUsed(prompt: String, negative: String): Unit =
    try service.recordUsed(prompt, negative) catch case NonFatal(_) => ()

  // ---- tag editor (prompt-all-in-one chips) ------------------------------------------------------------
  def tags(negative: Boolean): Vector[String] = PromptText.splitTags(text(negative))
  private def setTags(negative: Boolean, ts: Seq[String]): Unit = field(negative).set(PromptText.join(ts))
  def removeTag(negative: Boolean, index: Int): Unit =
    val ts = tags(negative)
    if index >= 0 && index < ts.size then setTags(negative, ts.patch(index, Nil, 1))
  def moveTag(negative: Boolean, from: Int, to: Int): Unit =
    val ts = tags(negative)
    val moved = PromptText.moveTag(ts, from, to)
    if moved != ts then setTags(negative, moved)
  def weightTag(negative: Boolean, index: Int, delta: Double): Unit =
    val ts = tags(negative)
    if index >= 0 && index < ts.size then
      val changed = PromptText.adjustWeight(ts(index), delta)
      if changed != ts(index) then setTags(negative, ts.updated(index, changed))
  def dedupe(negative: Boolean): Int =
    val ts = tags(negative)
    val kept = PromptText.dedupe(ts)
    if kept.size != ts.size then setTags(negative, kept)
    ts.size - kept.size
  /** Remove blacklisted tags from the field; returns how many tags were removed. */
  def applyBlacklist(negative: Boolean): Int =
    val before = tags(negative)
    val after = PromptText.splitTags(PromptText.applyBlacklist(text(negative), service.blacklist))
    if after.size != before.size then setTags(negative, after)
    before.size - after.size
