package sdfx.core.prompttools

import sdfx.core.config.AtomicFiles
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** Storage for the prompt tools under one folder (`config/prompt-tools`): the saved prompt library, history and
  * favourites, the blacklist, the tag-card folder and the tag-selector folder.
  */
final class PromptToolsService(val root: Path, clock: () => Long = () => System.currentTimeMillis):
  val libraryStore = new PromptLibraryStore(root.resolve("prompts.json"))
  val historyStore = new PromptHistoryStore(root.resolve("history.json"))
  val cards = new TagCardStore(root.resolve("tag-cards"))
  private val blacklistFile = root.resolve("blacklist.txt")
  private val tagFolderFile = root.resolve("tag-folder.txt")

  def now: Long = clock()

  // ---- library -----------------------------------------------------------------------------------------
  def library: PromptLibrary = synchronized(libraryStore.load())
  def updateLibrary(f: PromptLibrary => PromptLibrary): PromptLibrary = synchronized {
    val next = f(libraryStore.load())
    libraryStore.save(next)
    next
  }

  // ---- history / favourites ----------------------------------------------------------------------------
  def history: PromptHistory = synchronized(historyStore.load())
  def updateHistory(f: PromptHistory => PromptHistory): PromptHistory = synchronized {
    val next = f(historyStore.load())
    historyStore.save(next)
    next
  }
  /** Called for every submitted job. */
  def recordUsed(prompt: String, negative: String): Unit =
    updateHistory(_.record(prompt, negative, now))

  // ---- blacklist (prompt-all-in-one) -------------------------------------------------------------------
  def blacklist: Vector[String] = synchronized {
    if Files.isRegularFile(blacklistFile) && Files.size(blacklistFile) < 1024 * 1024 then
      new String(Files.readAllBytes(blacklistFile), UTF_8).linesIterator.map(_.trim).filter(_.nonEmpty).toVector.distinct
    else Vector.empty
  }
  def setBlacklist(words: Seq[String]): Unit = synchronized {
    AtomicFiles.writeString(blacklistFile, words.map(_.trim).filter(_.nonEmpty).distinct.mkString("\n"))
  }

  // ---- tag selector folder -----------------------------------------------------------------------------
  def defaultTagFolder: Path = root.resolve("tag-selector")
  /** The folder used for tag-selector files; any folder can be chosen (e.g. one synced with a cloud drive). */
  def tagFolder: Path = synchronized {
    if Files.isRegularFile(tagFolderFile) then
      val text = new String(Files.readAllBytes(tagFolderFile), UTF_8).trim
      if text.nonEmpty then Path.of(text) else defaultTagFolder
    else defaultTagFolder
  }
  def setTagFolder(dir: Option[Path]): Unit = synchronized {
    dir match
      case Some(d) => AtomicFiles.writeString(tagFolderFile, d.toAbsolutePath.normalize.toString)
      case None => Files.deleteIfExists(tagFolderFile)
  }
  def loadTags(): TagLibrary =
    val dir = tagFolder
    if dir == defaultTagFolder then TagSelector.ensureSample(dir)
    TagSelector.load(dir)
