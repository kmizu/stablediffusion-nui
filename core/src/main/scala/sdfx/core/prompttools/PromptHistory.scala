package sdfx.core.prompttools

import sdfx.core.config.AtomicFiles
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** A prompt pair that was used (history) or bookmarked (favourite). */
final case class PromptRecord(prompt: String, negative: String, at: Long, name: String = "")

/** Automatic history of prompts that were generated with, plus favourites. */
final case class PromptHistory(history: Vector[PromptRecord] = Vector.empty, favorites: Vector[PromptRecord] = Vector.empty):
  /** Record a used prompt pair; newest first. An identical earlier pair moves to the front instead of repeating.
    * Pairs where both texts are blank are ignored.
    */
  def record(prompt: String, negative: String, at: Long, limit: Int = PromptHistory.DefaultLimit): PromptHistory =
    if prompt.isBlank && negative.isBlank then this
    else
      val rest = history.filterNot(r => r.prompt == prompt && r.negative == negative)
      copy(history = (PromptRecord(prompt, negative, at) +: rest).take(math.max(1, limit)))

  def clearHistory(): PromptHistory = copy(history = Vector.empty)
  def removeHistory(index: Int): PromptHistory =
    if index < 0 || index >= history.size then this else copy(history = history.patch(index, Nil, 1))

  def isFavorite(prompt: String, negative: String): Boolean =
    favorites.exists(r => r.prompt == prompt && r.negative == negative)

  /** Bookmark a pair (no duplicate for the same pair; the name is updated). */
  def addFavorite(prompt: String, negative: String, at: Long, name: String = ""): PromptHistory =
    require(!(prompt.isBlank && negative.isBlank), "Nothing to bookmark: both prompts are empty")
    require(favorites.size < PromptHistory.MaxFavorites || isFavorite(prompt, negative), "Too many favourites")
    require(name.length <= 200, "Name is too long")
    val kept = favorites.filterNot(r => r.prompt == prompt && r.negative == negative)
    copy(favorites = PromptRecord(prompt, negative, at, name.trim) +: kept)

  def removeFavorite(index: Int): PromptHistory =
    if index < 0 || index >= favorites.size then this else copy(favorites = favorites.patch(index, Nil, 1))

  def toJson: String =
    def arr(rs: Vector[PromptRecord]) = ujson.Arr.from(rs.map(r =>
      ujson.Obj("prompt" -> r.prompt, "negative" -> r.negative, "at" -> ujson.Num(r.at.toDouble), "name" -> r.name)))
    ujson.write(ujson.Obj("history" -> arr(history), "favorites" -> arr(favorites)), indent = 2, escapeUnicode = false)

object PromptHistory:
  val DefaultLimit = 500
  val MaxFavorites = 5000
  val MaxFileBytes = 50 * 1024 * 1024

  def parse(json: String): PromptHistory =
    val root = ujson.read(json).obj
    def recs(key: String): Vector[PromptRecord] = root.get(key).flatMap(_.arrOpt).map(_.value.toVector.flatMap { v =>
      v.objOpt.flatMap { o =>
        o.value.get("prompt").flatMap(_.strOpt).map { p =>
          PromptRecord(p, o.value.get("negative").flatMap(_.strOpt).getOrElse(""),
            o.value.get("at").flatMap(_.numOpt).map(_.toLong).getOrElse(0L), o.value.get("name").flatMap(_.strOpt).getOrElse(""))
        }
      }
    }).getOrElse(Vector.empty)
    PromptHistory(recs("history").take(DefaultLimit), recs("favorites").take(MaxFavorites))

final class PromptHistoryStore(val file: Path):
  def load(): PromptHistory =
    if !Files.isRegularFile(file) then PromptHistory()
    else
      require(Files.size(file) <= PromptHistory.MaxFileBytes, "history file is too large")
      PromptHistory.parse(new String(Files.readAllBytes(file), UTF_8))
  def save(h: PromptHistory): Unit = AtomicFiles.writeString(file, h.toJson)
