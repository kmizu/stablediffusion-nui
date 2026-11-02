package sdfx.ui.presets

import java.nio.file.{Files, Path, StandardCopyOption}
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
  def collectionId(mode: Mode): String = s"history-${mode.key}"
  private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  def collection(library: PresetLibrary, mode: Mode): PresetCollection =
    library.collections.find(_.id == collectionId(mode)).getOrElse(
      PresetCollection(collectionId(mode), Set.empty, Vector.empty, Vector.empty, Vector.empty))

  /** Stored oldest first; the UI shows newest first. */
  def entries(library: PresetLibrary, mode: Mode): Vector[PresetRecord] = collection(library, mode).presets

  private def store(library: PresetLibrary, mode: Mode, records: Vector[PresetRecord]): PresetLibrary =
    val old = collection(library, mode)
    val next = old.copy(tracked = records.flatMap(_.fields.keys).toSet, presets = records)
    library.copy(collections = library.collections.filterNot(_.id == old.id) :+ next)

  /** Appends one entry; a state identical to the newest entry is skipped. Returns the added record and evicted ids. */
  def append(library: PresetLibrary, mode: Mode, values: Map[FieldKey, PresetAtom], at: LocalDateTime,
             limit: Int): (PresetLibrary, Option[PresetRecord], Vector[UUID]) =
    val current = entries(library, mode)
    if current.lastOption.exists(_.fields == values) then (library, None, Vector.empty)
    else
      val base = stamp.format(at)
      val taken = current.map(_.name).toSet
      val name = Iterator.single(base).++(Iterator.from(2).map(n => s"$base ($n)")).find(!taken(_)).get
      val record = PresetRecord(UUID.randomUUID(), name, values, None, Set.empty, Vector.empty, Vector.empty)
      val keep = limit.max(1)
      val all = current :+ record
      (store(library, mode, all.takeRight(keep)), Some(record), all.dropRight(keep).map(_.id))

  def remove(library: PresetLibrary, mode: Mode, ids: Set[UUID]): PresetLibrary =
    store(library, mode, entries(library, mode).filterNot(r => ids(r.id)))

/** Disk side of the history: one JSON library plus 100px thumbnails, all on one I/O thread. */
final class GenerationHistoryStore(directory: Path, limit: Int, dispatch: (() => Unit) => Unit):
  private val root = directory.toAbsolutePath.normalize()
  private val store = new PresetStore(root, PresetLimits())
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

  private def run(task: => Unit): Unit =
    if !closed then
      try io.execute(() => try task catch case NonFatal(_) => ())
      catch case _: java.util.concurrent.RejectedExecutionException => ()

  private def mutate(change: PresetLibrary => (PresetLibrary, Option[PresetRecord], Vector[UUID]))
      : Either[PresetError, Option[PresetRecord]] =
    def attempt(n: Int): Either[PresetError, Option[PresetRecord]] =
      store.load().flatMap { loaded =>
        val (next, added, evicted) = change(loaded.library)
        if next eq loaded.library then Right(added)
        else store.save(loaded.revision, next).map { _ =>
          evicted.foreach(id => try Files.deleteIfExists(thumbnail(id)) catch case NonFatal(_) => ())
          added
        }
      } match
        case Left(e) if n < 2 && e.issues.exists(_.code == "conflict") => attempt(n + 1)
        case other => other
    attempt(0)

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

  def delete(mode: Mode, ids: Set[UUID])(done: Either[PresetError, Unit] => Unit): Unit =
    run {
      val result = mutate(lib => (GenerationHistory.remove(lib, mode, ids), None, ids.toVector)).map(_ => ())
      dispatch(() => { done(result); listener() })
    }

  def clear(mode: Mode)(done: Either[PresetError, Unit] => Unit): Unit =
    run {
      store.load().map(l => GenerationHistory.entries(l.library, mode).map(_.id).toSet) match
        case Left(e) => dispatch(() => done(Left(e)))
        case Right(ids) =>
          val result = mutate(lib => (GenerationHistory.remove(lib, mode, ids), None, ids.toVector)).map(_ => ())
          dispatch(() => { done(result); listener() })
    }

  /** Scales `source` to fit 100x100 and writes it as the entry thumbnail; failures only lose the thumbnail. */
  def writeThumbnail(id: UUID, source: Path): Unit =
    run {
      val image = javax.imageio.ImageIO.read(source.toFile)
      if image != null then
        val scale = math.min(100.0 / image.getWidth, 100.0 / image.getHeight).min(1.0)
        val w = math.max(1, (image.getWidth * scale).round.toInt)
        val h = math.max(1, (image.getHeight * scale).round.toInt)
        val out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, w, h, null)
        g.dispose()
        Files.createDirectories(thumbs)
        val temp = Files.createTempFile(thumbs, "t", ".tmp")
        try
          javax.imageio.ImageIO.write(out, "png", temp.toFile)
          Files.move(temp, thumbnail(id), StandardCopyOption.REPLACE_EXISTING)
        finally Files.deleteIfExists(temp)
        dispatch(() => listener())
    }

  def close(): Unit =
    closed = true
    io.shutdown()
