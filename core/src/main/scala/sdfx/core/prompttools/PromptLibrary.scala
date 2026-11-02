package sdfx.core.prompttools

import sdfx.core.config.AtomicFiles
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** One saved prompt. `negative` says which field it belongs to (Multi-Prompt Manager's `is_negative`). */
final case class SavedPrompt(name: String, prompt: String, negative: Boolean)

/** Named prompts in insertion order. Exchange format is Multi-Prompt Manager's prompts.json:
  * `{"name": {"prompt": "...", "is_negative": false}}`.
  */
final case class PromptLibrary(entries: Vector[SavedPrompt] = Vector.empty):
  def names: Vector[String] = entries.map(_.name)
  def get(name: String): Option[SavedPrompt] = entries.find(_.name == name)

  /** Save under `name` (replacing the entry of that name in place). A blank name becomes "prompt N", N the
    * lowest number not in use. Returns the library and the name actually used.
    */
  def save(name: String, prompt: String, negative: Boolean): (PromptLibrary, String) =
    val trimmed = name.trim
    PromptLibrary.validateName(trimmed)
    require(entries.size < PromptLibrary.MaxEntries || get(trimmed).nonEmpty, s"At most ${PromptLibrary.MaxEntries} prompts can be saved")
    require(prompt.length <= PromptLibrary.MaxPromptChars, "Prompt is too long")
    val used = if trimmed.nonEmpty then trimmed else
      Iterator.from(1).map(i => s"prompt $i").find(n => get(n).isEmpty).get
    val entry = SavedPrompt(used, prompt, negative)
    val updated = entries.indexWhere(_.name == used) match
      case -1 => entries :+ entry
      case i  => entries.updated(i, entry)
    (copy(entries = updated), used)

  def delete(name: String): PromptLibrary = copy(entries = entries.filterNot(_.name == name))

  def rename(from: String, to: String): PromptLibrary =
    val target = to.trim
    require(target.nonEmpty, "Enter a name")
    PromptLibrary.validateName(target)
    require(get(from).nonEmpty, s"No prompt named '$from'")
    require(from == target || get(target).isEmpty, s"A prompt named '$target' already exists")
    copy(entries = entries.map(e => if e.name == from then e.copy(name = target) else e))

  /** Merge `other` in; same-named entries are replaced when `overwrite`, otherwise skipped. Returns the count taken. */
  def merge(other: PromptLibrary, overwrite: Boolean): (PromptLibrary, Int) =
    var lib = this
    var n = 0
    other.entries.foreach { e =>
      if lib.get(e.name).isEmpty || overwrite then
        lib = lib.save(e.name, e.prompt, e.negative)._1
        n += 1
    }
    (lib, n)

  def toJson: String =
    val o = ujson.Obj()
    entries.foreach(e => o(e.name) = ujson.Obj("prompt" -> e.prompt, "is_negative" -> e.negative))
    ujson.write(o, indent = 4, escapeUnicode = false)

object PromptLibrary:
  val MaxEntries = 5000
  val MaxPromptChars = 100000
  val MaxNameChars = 200
  val MaxFileBytes = 20 * 1024 * 1024

  private[prompttools] def validateName(name: String): Unit =
    require(name.length <= MaxNameChars, "Name is too long")
    require(!name.exists(_.isControl), "Name contains control characters")

  def parse(json: String): PromptLibrary =
    val root = try ujson.read(json) catch case e: Exception => throw new IllegalArgumentException("Not valid JSON: " + e.getMessage)
    val obj = root.objOpt.getOrElse(throw new IllegalArgumentException("Expected a JSON object of named prompts"))
    require(obj.value.size <= MaxEntries, s"At most $MaxEntries prompts are supported")
    PromptLibrary(obj.value.toVector.map { (name, v) =>
      validateName(name)
      val o = v.objOpt.getOrElse(throw new IllegalArgumentException(s"Entry '$name' is not an object"))
      val prompt = o.value.get("prompt").flatMap(_.strOpt).getOrElse(throw new IllegalArgumentException(s"Entry '$name' has no prompt text"))
      val neg = o.value.get("is_negative").flatMap(_.boolOpt).getOrElse(false)
      SavedPrompt(name, prompt, neg)
    })

/** File-backed library (atomic writes). */
final class PromptLibraryStore(val file: Path):
  def load(): PromptLibrary =
    if !Files.isRegularFile(file) then PromptLibrary()
    else
      require(Files.size(file) <= PromptLibrary.MaxFileBytes, "prompts.json is too large")
      PromptLibrary.parse(new String(Files.readAllBytes(file), UTF_8))
  def save(lib: PromptLibrary): Unit = AtomicFiles.writeString(file, lib.toJson)
  def importFile(source: Path): PromptLibrary =
    require(Files.size(source) <= PromptLibrary.MaxFileBytes, "File is too large")
    PromptLibrary.parse(new String(Files.readAllBytes(source), UTF_8))
  def exportTo(lib: PromptLibrary, target: Path): Unit = AtomicFiles.writeString(target, lib.toJson)
