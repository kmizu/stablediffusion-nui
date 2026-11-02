package sdfx.ui.presets

import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.{Executors, ThreadFactory}
import scala.util.control.NonFatal
import sdfx.core.presets.*
import sdfx.ui.vm.Mode

/** Categories a user can restore selectively, derived from the field-key segments. */
enum StateCategory(val id: String, val label: String):
  case Prompts extends StateCategory("prompts", "Prompts")
  case Sampling extends StateCategory("sampling", "Sampling, size, seed, hires")
  case Model extends StateCategory("model", "Checkpoint / VAE / clip skip")
  case ControlNet extends StateCategory("controlnet", "ControlNet")
  case Scripts extends StateCategory("scripts", "Script and extension controls")
  case Other extends StateCategory("other", "WD batch and layout")

object StateCategory:
  private val promptFields = Set("prompt", "negativePrompt", "hiresPrompt", "hiresNegative")
  def of(key: FieldKey): StateCategory = key.segments match
    case Vector("generation", f) if promptFields(f) => Prompts
    case Vector("generation", _*) => Sampling
    case Vector("model", _*) => Model
    case Vector("controlnet", _*) => ControlNet
    case Vector("script", _*) | Vector("plugin", _*) => Scripts
    case _ => Other
  def keys(record: PresetRecord, chosen: Set[StateCategory]): Set[FieldKey] =
    record.fields.keySet.filter(k => chosen(of(k)))

/** Automatic, capped per-mode generation history kept apart from the manual named-state library. */
object GenerationHistory:
  val DefaultLimit = 50
  /** Comma-separated terms all need to match one of the searchable saved fields or the name. */
  def matchesSearch(record: PresetRecord, query: String): Boolean =
    val terms = Option(query).getOrElse("").split(",").toVector.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty)
    val keys = Set("generation.prompt", "generation.negativePrompt", "generation.sampler", "model.checkpoint")
    val values = record.fields.toVector.collect { case (key, PresetAtom.Text(text)) if keys(key.render) => text }
    val searchable = (record.name +: values).map(_.toLowerCase(java.util.Locale.ROOT))
    terms.forall(term => searchable.exists(_.contains(term)))
  def collectionId(mode: Mode): String = s"history-${mode.key}"
  def favouritesId(mode: Mode): String = s"favourites-${mode.key}"
  private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  def collection(library: PresetLibrary, mode: Mode): PresetCollection =
    library.collections.find(_.id == collectionId(mode)).getOrElse(
      PresetCollection(collectionId(mode), Set.empty, Vector.empty, Vector.empty, Vector.empty))

  /** Stored oldest first; the UI shows newest first. */
  def entries(library: PresetLibrary, mode: Mode): Vector[PresetRecord] = collection(library, mode).presets
  def favourites(library: PresetLibrary, mode: Mode): Vector[PresetRecord] =
    library.collections.find(_.id == favouritesId(mode)).toVector.flatMap(_.presets)

  private def store(library: PresetLibrary, mode: Mode, records: Vector[PresetRecord]): PresetLibrary =
    val old = collection(library, mode)
    val next = old.copy(tracked = records.flatMap(_.fields.keys).toSet, presets = records)
    library.copy(collections = library.collections.filterNot(_.id == old.id) :+ next)

  private def storeFavourites(library: PresetLibrary, mode: Mode, records: Vector[PresetRecord]): PresetLibrary =
    val id = favouritesId(mode)
    val old = library.collections.find(_.id == id).getOrElse(PresetCollection(id, Set.empty, Vector.empty, Vector.empty, Vector.empty))
    val next = old.copy(tracked = records.flatMap(_.fields.keys).toSet, presets = records)
    library.copy(collections = library.collections.filterNot(_.id == id) :+ next)

  private def unreferenced(library: PresetLibrary, ids: Iterable[UUID]): Vector[UUID] =
    val live = library.collections.flatMap(_.presets.map(_.id)).toSet
    ids.iterator.filterNot(live).toVector.distinct

  /** Appends one entry; a state identical to the newest entry is skipped. Returns the added record and evicted ids. */
  def append(library: PresetLibrary, mode: Mode, values: Map[FieldKey, PresetAtom], at: LocalDateTime,
             limit: Int): (PresetLibrary, Option[PresetRecord], Vector[UUID]) =
    val current = entries(library, mode)
    if current.lastOption.exists(_.fields == values) then (library, None, Vector.empty)
    else
      val base = stamp.format(at)
      val taken = (current ++ favourites(library, mode)).map(_.name).toSet
      val name = Iterator.single(base).++(Iterator.from(2).map(n => s"$base ($n)")).find(!taken(_)).get
      val record = PresetRecord(UUID.randomUUID(), name, values, None, Set.empty, Vector.empty, Vector.empty)
      val keep = limit.max(1)
      val all = current :+ record
      val next = store(library, mode, all.takeRight(keep))
      (next, Some(record), unreferenced(next, all.dropRight(keep).map(_.id)))

  def remove(library: PresetLibrary, mode: Mode, ids: Set[UUID]): PresetLibrary =
    storeFavourites(store(library, mode, entries(library, mode).filterNot(r => ids(r.id))), mode,
      favourites(library, mode).filterNot(r => ids(r.id)))

  /** Mixed selections add all; if all selected are already favourites, remove all. */
  def toggleFavourites(library: PresetLibrary, mode: Mode, ids: Set[UUID]): (PresetLibrary, Vector[UUID]) =
    val saved = favourites(library, mode)
    val savedIds = saved.map(_.id).toSet
    val candidates = (entries(library, mode) ++ saved).filter(r => ids(r.id)).distinctBy(_.id)
    val next = if ids.nonEmpty && ids.subsetOf(savedIds) then saved.filterNot(r => ids(r.id))
      else saved ++ candidates.filterNot(r => savedIds(r.id))
    val result = storeFavourites(library, mode, next)
    (result, unreferenced(result, ids))

  def clearNonFavourites(library: PresetLibrary, mode: Mode): (PresetLibrary, Vector[UUID]) =
    val fav = favourites(library, mode).map(_.id).toSet
    val previous = entries(library, mode)
    val next = store(library, mode, previous.filter(r => fav(r.id)))
    (next, unreferenced(next, previous.map(_.id)))

  /** Merge source entries atomically; no existing history is evicted to make room. */
  def importV2(library: PresetLibrary, incoming: Vector[StateManagerV2Entry], limit: Int): Either[PresetError, PresetLibrary] =
    def problem(message: String) = PresetError(Vector(PresetIssue("state-manager-import", "import", message)))
    incoming.foldLeft[Either[PresetError, PresetLibrary]](Right(library)) { (result, entry) =>
      result.flatMap { current =>
        if entry.groups.isEmpty || !entry.groups.subsetOf(Set("history", "favourites")) then
          Left(problem(s"Entry ${entry.sourceId} has unsupported or empty groups"))
        else
          val mode = entry.mode match
            case PresetMode.Txt2Img => Mode.Txt2Img
            case PresetMode.Img2Img => Mode.Img2Img
            case PresetMode.Inpaint => Mode.Inpaint
          val digest = MessageDigest.getInstance("SHA-256").digest(
            (entry.mode.toString + "\u0000" + entry.sourceId + "\u0000" + entry.originalEntry.text + "\u0000" + entry.originalDefaults.text).getBytes(UTF_8))
          val buffer = ByteBuffer.wrap(digest)
          val id = new UUID(buffer.getLong(), buffer.getLong())
          val shortHash = digest.take(10).map(b => f"${b & 255}%02x").mkString
          val originalTitle = entry.sourceName.map(_.trim).filter(_.nonEmpty).getOrElse(s"State Manager ${entry.sourceId}")
          val cleanTitle = originalTitle.codePoints().toArray.filterNot(cp => Character.isISOControl(cp)).take(180)
          val readableTitle = if cleanTitle.isEmpty then "State Manager" else new String(cleanTitle, 0, cleanTitle.length)
          val title = s"$readableTitle [$shortHash]"
          val raw = s"{\"entry\":${entry.originalEntry.text},\"defaults\":${entry.originalDefaults.text}}".getBytes(UTF_8)
          PresetJson.readSource(raw, PresetLimits()).flatMap { provenance =>
            val record = PresetRecord(id, title, entry.mapped, Some(PresetSource("state-manager-v2", title, Some(entry.mode), provenance)),
              Set.empty, Vector.empty, Vector.empty)
            val history = entries(current, mode)
            val favourite = favourites(current, mode)
            val existing = (history ++ favourite).find(_.id == id)
            if existing.exists(_.source != record.source) then Left(problem(s"Entry ID collision: ${entry.sourceId}"))
            else
              val chosen = existing.getOrElse(record)
              val addHistory = entry.groups("history") && !history.exists(_.id == id)
              val addFavourite = entry.groups("favourites") && !favourite.exists(_.id == id)
              if addHistory && history.size >= limit then Left(problem(s"${mode.key} history is full; no entries were imported"))
              else
                val withHistory = if addHistory then store(current, mode, history :+ chosen) else current
                val withFavourite = if addFavourite then storeFavourites(withHistory, mode, favourite :+ chosen) else withHistory
                Right(withFavourite)
          }
      }
    }.map { merged =>
      // Native entries have no reliable timestamp to compare with source timestamps.
      // Treat newly imported history as older, preserving all existing order and entries.
      Vector(Mode.Txt2Img, Mode.Img2Img).foldLeft(merged) { (current, mode) =>
        val existingIds = entries(library, mode).map(_.id).toSet
        val all = entries(current, mode)
        if all.exists(r => !existingIds(r.id)) then
          store(current, mode, all.filterNot(r => existingIds(r.id)) ++ all.filter(r => existingIds(r.id)))
        else current
      }
    }

  def unreferencedAfterRemoval(library: PresetLibrary, ids: Iterable[UUID]): Vector[UUID] = unreferenced(library, ids)

private object HistoryOperationLocks:
  private val monitors = new java.util.HashMap[Path, Object]()
  def monitor(root: Path): Object = monitors.synchronized {
    Option(monitors.get(root)).getOrElse {
      val created = new Object()
      monitors.put(root, created)
      created
    }
  }

/** Disk side of the history: one JSON library plus 100px thumbnails, all on one I/O thread. */
final class GenerationHistoryStore(directory: Path, limit: Int, dispatch: (() => Unit) => Unit):
  private val root = directory.toAbsolutePath.normalize()
  private val store = new PresetStore(root, PresetLimits(maxCollections = 6))
  private val thumbs = root.resolve("thumbs")
  private val flag = root.resolve("autosave.off")
  private val io = Executors.newSingleThreadExecutor((task => {
    val t = new Thread(task, "sdfx-generation-history-io")
    t.setDaemon(true)
    t
  }): ThreadFactory)
  @volatile private var closed = false
  @volatile private var listener: () => Unit = () => ()

  def onChange(f: () => Unit): Unit = listener = f
  def path: Path = root
  def autosave: Boolean = !Files.exists(flag)
  def setAutosave(on: Boolean): Either[PresetError, Unit] =
    try
      if on then Files.deleteIfExists(flag)
      else
        Files.createDirectories(root)
        if !Files.exists(flag) then Files.write(flag, Array.emptyByteArray)
      Right(())
    catch case NonFatal(e) => Left(PresetError(Vector(PresetIssue("io", flag.toString, Option(e.getMessage).getOrElse("io error")))))
  def thumbnail(id: UUID): Path = thumbs.resolve(id.toString + ".png")

  private def isReparse(path: Path): Boolean =
    val attrs = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
    val dos = try Files.readAttributes(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS)
      .get("reparsePoint") == java.lang.Boolean.TRUE
    catch case NonFatal(_) => false
    attrs.isSymbolicLink || attrs.isOther || dos

  private def safeThumbDirectory(create: Boolean): Boolean =
    if !Files.exists(thumbs, LinkOption.NOFOLLOW_LINKS) then
      if !create then return false
      Files.createDirectory(thumbs)
    if isReparse(thumbs) || !Files.isDirectory(thumbs, LinkOption.NOFOLLOW_LINKS) then
      throw new java.io.IOException("History thumbnail directory is a link or is not a directory")
    true

  def thumbnailIfSafe(id: UUID): Option[Path] =
    try
      val file = thumbnail(id)
      Option.when(safeThumbDirectory(false) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !isReparse(file))(file)
    catch case NonFatal(_) => None

  private def run(task: => Unit): Unit =
    if !closed then
      try io.execute(() => try task catch case NonFatal(_) => ())
      catch case _: java.util.concurrent.RejectedExecutionException => ()

  /** Serialize library mutations and thumbnail lifecycle across cooperating NUI processes and stores. */
  private def exclusive[A](body: => A): Either[PresetError, A] =
    try
      Files.createDirectories(root)
      val physicalRoot = root.toRealPath()
      HistoryOperationLocks.monitor(physicalRoot).synchronized {
        store.load().flatMap { _ =>
          val lockPath = root.resolve(".history-operations.lock")
          val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
          try
            val lock = channel.lock()
            try Right(body)
            finally try lock.release() catch case NonFatal(_) => ()
          finally try channel.close() catch case NonFatal(_) => ()
        }
      }
    catch case NonFatal(error) => Left(PresetError(Vector(PresetIssue("io", root.toString,
      "History operation failed: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))))

  private def mutate(change: PresetLibrary => (PresetLibrary, Option[PresetRecord], Vector[UUID]),
      guard: PresetLibrary => Either[PresetError, Unit] = _ => Right(()))
      : Either[PresetError, Option[PresetRecord]] =
    def attempt(n: Int): Either[PresetError, Option[PresetRecord]] =
      store.load().flatMap { loaded =>
        guard(loaded.library).flatMap { _ =>
          val (next, added, evicted) = change(loaded.library)
          if next eq loaded.library then Right(added)
          else store.save(loaded.revision, next).map { _ =>
            evicted.foreach(id => try if safeThumbDirectory(false) then Files.deleteIfExists(thumbnail(id)) catch case NonFatal(_) => ())
            added
          }
        }
      } match
        case Left(e) if n < 2 && e.issues.exists(_.code == "conflict") => attempt(n + 1)
        case other => other
    exclusive(attempt(0)).flatten

  /** Records `values`; `done` receives the new record (None when identical to the newest entry). */
  def record(mode: Mode, values: Map[FieldKey, PresetAtom],
             done: Either[PresetError, Option[PresetRecord]] => Unit): Unit =
    run {
      val result = mutate(lib => GenerationHistory.append(lib, mode, values, LocalDateTime.now(ZoneId.systemDefault()), limit))
      dispatch(() => { done(result); if result.exists(_.nonEmpty) then listener() })
    }

  def list(mode: Mode)(done: Either[PresetError, Vector[PresetRecord]] => Unit): Unit =
    run {
      val result = store.load().map(l => GenerationHistory.entries(l.library, mode))
      dispatch(() => done(result))
    }

  def listFavourites(mode: Mode)(done: Either[PresetError, Vector[PresetRecord]] => Unit): Unit =
    run {
      val result = store.load().map(l => GenerationHistory.favourites(l.library, mode))
      dispatch(() => done(result))
    }

  def listGroups(mode: Mode)(done: Either[PresetError, (Vector[PresetRecord], Vector[PresetRecord])] => Unit): Unit =
    run {
      val result = store.load().map(l => (GenerationHistory.entries(l.library, mode), GenerationHistory.favourites(l.library, mode)))
      dispatch(() => done(result))
    }

  def toggleFavourites(mode: Mode, ids: Set[UUID])(done: Either[PresetError, Unit] => Unit): Unit =
    run {
      val result = mutate(lib => {
        val (next, removed) = GenerationHistory.toggleFavourites(lib, mode, ids)
        (next, None, removed)
      }, lib => {
        val available = (GenerationHistory.entries(lib, mode) ++ GenerationHistory.favourites(lib, mode)).map(_.id).toSet
        if ids.nonEmpty && ids.subsetOf(available) then Right(())
        else Left(PresetError(Vector(PresetIssue("stale-selection", mode.key, "Selected history entries are no longer available"))))
      }).map(_ => ())
      dispatch(() => { done(result); if result.isRight then listener() })
    }

  def importV2(incoming: Vector[StateManagerV2Entry])(done: Either[PresetError, Unit] => Unit): Unit =
    run {
      val result = mutate(lib => (GenerationHistory.importV2(lib, incoming, limit).toOption.get, None, Vector.empty),
        lib => GenerationHistory.importV2(lib, incoming, limit).map(_ => ())).map(_ => ())
      dispatch(() => { done(result); if result.isRight then listener() })
    }

  def delete(mode: Mode, ids: Set[UUID])(done: Either[PresetError, Unit] => Unit): Unit =
    run {
      val result = mutate(lib => {
        val next = GenerationHistory.remove(lib, mode, ids)
        (next, None, GenerationHistory.unreferencedAfterRemoval(next, ids))
      }).map(_ => ())
      dispatch(() => { done(result); listener() })
    }

  def clear(mode: Mode)(done: Either[PresetError, Unit] => Unit): Unit =
    run {
      val result = mutate(lib => {
        val (next, removed) = GenerationHistory.clearNonFavourites(lib, mode)
        (next, None, removed)
      }).map(_ => ())
      dispatch(() => { done(result); listener() })
    }

  /** Scales `source` to fit 100x100 and writes it as the entry thumbnail; failures only lose the thumbnail. */
  def writeThumbnail(id: UUID, source: Path): Unit =
    run {
      exclusive {
        val stillReferenced = store.load().exists(_.library.collections.exists(_.presets.exists(_.id == id)))
        val image = if stillReferenced then javax.imageio.ImageIO.read(source.toFile) else null
        if image != null then
          val scale = math.min(100.0 / image.getWidth, 100.0 / image.getHeight).min(1.0)
          val w = math.max(1, (image.getWidth * scale).round.toInt)
          val h = math.max(1, (image.getHeight * scale).round.toInt)
          val out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
          val g = out.createGraphics()
          g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
          g.drawImage(image, 0, 0, w, h, null)
          g.dispose()
          safeThumbDirectory(true)
          val temp = Files.createTempFile(thumbs, "t", ".tmp")
          try
            javax.imageio.ImageIO.write(out, "png", temp.toFile)
            Files.move(temp, thumbnail(id), StandardCopyOption.REPLACE_EXISTING)
          finally Files.deleteIfExists(temp)
          dispatch(() => listener())
      }
    }

  def close(): Unit =
    closed = true
    io.shutdown()
