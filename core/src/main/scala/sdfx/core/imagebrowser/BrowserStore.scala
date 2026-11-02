package sdfx.core.imagebrowser

import sdfx.core.json.SnakePickle
import sdfx.core.json.SnakePickle.{ReadWriter => RW, *}

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.util.control.NonFatal

case class RankEntry(rank: Int, hash: String = "") derives RW
case class SavedDir(path: String, depth: Int = 0) derives RW
case class StoreData(
    settings: BrowserSettings = BrowserSettings(),
    rankings: Map[String, RankEntry] = Map.empty,
    savedDirs: Seq[SavedDir] = Nil,
    others: SavedDir = SavedDir("")
) derives RW

/** Persistent state of the Image Browser: options, rankings (with content hash), the saved "Others" folders.
  * One JSON file, written atomically; a damaged file is kept as `.corrupt` and never silently discarded.
  */
final class BrowserStore(file: Path):
  private var data: StoreData = StoreData()
  private var loadNotice: Option[String] = None

  load()

  private def load(): Unit =
    if Files.isRegularFile(file) then
      try data = SnakePickle.read[StoreData](new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8))
      catch case NonFatal(e) =>
        val kept = file.resolveSibling(file.getFileName.toString + ".corrupt")
        try Files.move(file, kept, StandardCopyOption.REPLACE_EXISTING) catch case NonFatal(_) => ()
        loadNotice = Some(s"Image browser data was unreadable (${e.getMessage}); kept as ${kept.getFileName}")
    data = data.copy(settings = data.settings.sanitized)

  def notice: Option[String] = loadNotice

  private def key(p: Path): String = p.toAbsolutePath.normalize().toString

  def save(): Unit = synchronized {
    Files.createDirectories(file.toAbsolutePath.getParent)
    val tmp = Files.createTempFile(file.toAbsolutePath.getParent, ".image-browser-", ".tmp")
    try
      Files.write(tmp, SnakePickle.write(data, indent = 2).getBytes(java.nio.charset.StandardCharsets.UTF_8))
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(tmp)
  }

  // ---- settings -------------------------------------------------------------------------------------------
  def settings: BrowserSettings = synchronized(data.settings)
  def updateSettings(f: BrowserSettings => BrowserSettings): BrowserSettings = synchronized {
    data = data.copy(settings = f(data.settings).sanitized); save(); data.settings
  }

  // ---- rankings -------------------------------------------------------------------------------------------
  def ranking(p: Path): Option[Int] = synchronized(data.rankings.get(key(p)).map(_.rank))
  def rankEntry(p: Path): Option[RankEntry] = synchronized(data.rankings.get(key(p)))
  def rankings: Map[String, RankEntry] = synchronized(data.rankings)
  def setRanking(p: Path, rank: Option[Int], hash: String = ""): Unit = synchronized {
    rank match
      case Some(r) =>
        require(r >= 1 && r <= 5, "ranking must be 1-5")
        data = data.copy(rankings = data.rankings.updated(key(p), RankEntry(r, hash)))
      case None => data = data.copy(rankings = data.rankings - key(p))
    save()
  }
  /** After a file moved: the ranking follows the new path. */
  def relocate(from: Path, to: Path): Unit = synchronized {
    data.rankings.get(key(from)).foreach { e => data = data.copy(rankings = data.rankings - key(from) + (key(to) -> e)); save() }
  }
  def replaceRankings(m: Map[String, RankEntry]): Unit = synchronized { data = data.copy(rankings = m); save() }

  // ---- saved directories ("Others") -----------------------------------------------------------------------
  def savedDirs: Seq[SavedDir] = synchronized(data.savedDirs)
  /** Adds, or replaces the depth of, a saved directory. */
  def addSavedDir(path: String, depth: Int): Unit = synchronized {
    val d = SavedDir(Path.of(path).toAbsolutePath.normalize().toString, depth)
    data = data.copy(savedDirs = data.savedDirs.filterNot(_.path == d.path) :+ d); save()
  }
  def removeSavedDir(path: String): Unit = synchronized {
    val n = Path.of(path).toAbsolutePath.normalize().toString
    data = data.copy(savedDirs = data.savedDirs.filterNot(_.path == n)); save()
  }
  def replaceSavedDirs(xs: Seq[SavedDir]): Unit = synchronized { data = data.copy(savedDirs = xs); save() }
  def othersState: SavedDir = synchronized(data.others)
  def setOthersState(path: String, depth: Int): Unit = synchronized { data = data.copy(others = SavedDir(path, depth)); save() }
