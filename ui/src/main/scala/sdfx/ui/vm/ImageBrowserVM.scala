package sdfx.ui.vm

import sdfx.core.imagebrowser.*

import java.nio.file.{Files, Path}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

/** Keyboard shortcuts of the browser. `Ctrl` is the default modifier for page turning and can be changed to Shift or Ctrl+Shift. */
enum BrowserKeyAction:
  case Rank(value: Option[Int])
  case Favorite, Refresh, Delete, PrevPage, NextPage

object BrowserKeys:
  /** `key` is the key name ("0".."5", "F", "R", "DELETE", "LEFT", "RIGHT"); modifier state is passed explicitly. */
  def resolve(settings: BrowserSettings, key: String, ctrl: Boolean, shift: Boolean, alt: Boolean = false): Option[BrowserKeyAction] =
    val plain = !ctrl && !shift && !alt
    def modifierHeld: Boolean =
      if alt then false
      else if settings.modCtrlShift then ctrl && shift
      else if settings.modShift then shift && !ctrl
      else ctrl && !shift
    key.toUpperCase match
      case d if d.length == 1 && d.head >= '0' && d.head <= '5' && plain => Some(BrowserKeyAction.Rank(Option(d.head - '0').filter(_ > 0)))
      case "F" if plain => Some(BrowserKeyAction.Favorite)
      case "R" if plain => Some(BrowserKeyAction.Refresh)
      case "DELETE" if plain => Some(BrowserKeyAction.Delete)
      case "LEFT" if modifierHeld => Some(BrowserKeyAction.PrevPage)
      case "RIGHT" if modifierHeld => Some(BrowserKeyAction.NextPage)
      case _ => None

/** Outcome of an operation that changes files; `message` is meant for the status line / console log. */
final case class BrowserOutcome(ok: Boolean, message: String)

/** State of one listing tab: scan result, query, paging and selection. No JavaFX; all file work is blocking, so callers
  * run `scan`/mutations off the UI thread and then `accept` the result on it.
  */
final class ImageBrowserTabVM(val spec: TabSpec, env: BrowserEnv, seed: () => Long = () => System.nanoTime()):
  private var scanned: Vector[ImageRecord] = Vector.empty
  private var result = QueryResult(Vector.empty, Vector.empty)
  private var pageNo = 1
  private var chosen: Option[Path] = None

  var query: BrowserQuery = BrowserQuery()
  /** "Others" tab only. */
  var othersDir: String = ""
  var othersDepth: Int = 0
  var subdir: Option[Path] = None

  def settings: BrowserSettings = env.settings
  def filtered: Vector[ImageRecord] = result.records
  def warnings: Vector[String] = result.warnings
  def totalScanned: Int = scanned.size
  def pager: Pager = Pager(filtered.size, settings.perPage)
  def page: Int = pager.clamp(pageNo)
  def pageLabel: String = pager.label(page)
  def pageItems: Vector[ImageRecord] = pager.slice(filtered, page)
  def selected: Option[Path] = chosen
  def selectedRecord: Option[ImageRecord] = chosen.flatMap(p => filtered.find(_.path == p))

  /** Directories this tab lists right now (Others: the chosen folder or its chosen sub folder). */
  def effectiveDirs: Seq[Path] = spec.kind match
    case TabKind.Others =>
      Option(othersDir.trim).filter(_.nonEmpty).map(d => subdir.getOrElse(Path.of(d))).toSeq
    case _ => spec.dirs

  /** Blocking scan according to the tab kind. */
  def scan(): Vector[ImageRecord] = spec.kind match
    case TabKind.Others => effectiveDirs.headOption.fold(Vector.empty[ImageRecord])(d => env.listOthers(d, othersDepth))
    case _ => env.list(spec)

  /** Installs a scan result (keeping page and selection where possible) and re-applies the query. */
  def accept(records: Vector[ImageRecord]): Unit =
    scanned = records
    reapply()

  def refresh(): Unit = accept(scan())

  /** Re-runs filter/sort over the last scan (cheap; used when only the query changed). */
  def reapply(): Unit =
    result = BrowserQueryEngine(scanned, query.copy(randomSeed = if query.sortBy == "random" then seed() else query.randomSeed), env.rankingOf)
    pageNo = pager.clamp(pageNo)
    if chosen.exists(p => !result.records.exists(_.path == p)) then chosen = None

  def setQuery(q: BrowserQuery, resetPage: Boolean = true): Unit =
    query = q
    if resetPage then pageNo = 1
    reapply()

  def flipSortOrder(): Unit = setQuery(query.copy(ascending = !query.ascending), resetPage = false)

  // ---- paging ----------------------------------------------------------------------------------------------
  def goTo(n: Int): Unit = pageNo = pager.clamp(n)
  def first(): Unit = goTo(1)
  def prev(): Unit = goTo(page - 1)
  def next(): Unit = goTo(page + 1)
  def end(): Unit = goTo(pager.pageCount)

  // ---- selection -------------------------------------------------------------------------------------------
  def select(p: Option[Path]): Unit = chosen = p.filter(path => filtered.exists(_.path == path))
  /** Moves the selection by `delta` within the filtered list, turning pages when needed. */
  def selectNeighbor(delta: Int): Unit =
    if filtered.nonEmpty then
      val i = chosen.flatMap(p => Option(filtered.indexWhere(_.path == p)).filter(_ >= 0)).getOrElse(if delta >= 0 then -1 else filtered.size)
      val j = (i + delta).max(0).min(filtered.size - 1)
      chosen = Some(filtered(j).path)
      pageNo = j / settings.perPage + 1

  // ---- actions on files -------------------------------------------------------------------------------------
  def setRanking(rank: Option[Int]): BrowserOutcome = chosen match
    case None => BrowserOutcome(false, "No image selected")
    case Some(p) =>
      env.setRanking(p, rank) match
        case Left(e) => BrowserOutcome(false, e)
        case Right(_) => reapply(); BrowserOutcome(true, s"${p.getFileName}: ranking ${rank.fold("None")(_.toString)}")

  /** Favorites button (copy or move per option). The tab list is updated by the caller's next refresh. */
  def favorite(): BrowserOutcome = chosen match
    case None => BrowserOutcome(false, "No image selected")
    case Some(p) =>
      val copy = settings.copyInsteadOfMove
      env.addToFavorites(p) match
        case Left(e) => BrowserOutcome(false, e)
        case Right(t) => dropIfMoved(p, copy); BrowserOutcome(true, s"${if copy then "Copied" else "Moved"} ${p.getFileName} to favorites")

  def toDirectory(dir: String): BrowserOutcome = chosen match
    case None => BrowserOutcome(false, "No image selected")
    case Some(_) if dir.trim.isEmpty => BrowserOutcome(false, "Choose a directory first")
    case Some(p) =>
      val copy = settings.copyInsteadOfMove
      env.sendToDirectory(p, Path.of(dir.trim)) match
        case Left(e) => BrowserOutcome(false, e)
        case Right(t) => dropIfMoved(p, copy); BrowserOutcome(true, s"${if copy then "Copied" else "Moved"} ${p.getFileName} to ${t.getParent}")

  private def dropIfMoved(p: Path, copied: Boolean): Unit =
    if !copied then removeFromLists(Set(p))

  private def removeFromLists(paths: Set[Path]): Unit =
    val index = filtered.indexWhere(r => chosen.contains(r.path))
    scanned = scanned.filterNot(r => paths(r.path))
    reapply()
    if chosen.isEmpty || chosen.exists(paths) then
      chosen = if filtered.isEmpty then None else Some(filtered(index.max(0).min(filtered.size - 1)).path)
    pageNo = pager.clamp(pageNo)

  /** Images "delete next N" would remove: from the selection onwards; without `offscreen` only those on the current page. */
  def deletePlan(count: Int, offscreen: Boolean): Vector[ImageRecord] =
    chosen.flatMap(p => Option(filtered.indexWhere(_.path == p)).filter(_ >= 0)) match
      case None => Vector.empty
      case Some(i) =>
        val pageEnd = math.min(filtered.size, page * settings.perPage)
        val pool = if offscreen then filtered.drop(i) else filtered.slice(i, pageEnd)
        pool.take(count.max(0))

  /** Deletes the planned images. Only call after the user confirmed; files go to the trash unless recycling is off. */
  def deleteConfirmed(plan: Vector[ImageRecord]): BrowserOutcome =
    if plan.isEmpty then BrowserOutcome(false, "Nothing to delete")
    else
      val results = env.delete(plan.map(_.path))
      val okPaths = results.collect { case (p, Right(_)) => p }.toSet
      val failed = results.collect { case (p, Left(e)) => e }
      removeFromLists(okPaths)
      val where = if settings.deleteRecycle then "to the recycle bin" else "permanently"
      val msg = s"Deleted ${okPaths.size} image${if okPaths.size == 1 then "" else "s"} $where" +
        (if failed.nonEmpty then s"; failed: ${failed.mkString("; ")}" else "")
      BrowserOutcome(failed.isEmpty, msg)

object BrowserInfo:
  private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
  def fileTime(r: ImageRecord): String = timeFormat.format(Instant.ofEpochMilli(r.modified))
  def sizeText(bytes: Long): String =
    if bytes < 1024 then s"$bytes B" else if bytes < 1024 * 1024 then f"${bytes / 1024.0}%.1f KB" else f"${bytes / 1048576.0}%.2f MB"

  /** Rows of the "Formatted display": Prompt, Negative prompt and the fields in the configured order. */
  def formattedRows(meta: ImageMeta, order: Seq[String]): Vector[(String, String)] =
    meta.parsed.fold(Vector.empty[(String, String)]) { p =>
      val all = Vector("Prompt" -> p.prompt, "Negative prompt" -> p.negativePrompt) ++ p.fields.toVector
      val byKey = all.filter(_._2.nonEmpty).toMap
      val ordered = order.flatMap(k => byKey.get(k).map(k -> _))
      ordered.toVector
    }

  /** Everything not in the configured order ("Additional Generation Info"). */
  def additionalRows(meta: ImageMeta, order: Seq[String]): Vector[(String, String)] =
    meta.parsed.fold(Vector.empty[(String, String)])(p => p.fields.toVector.filterNot((k, _) => order.contains(k)))

  def thumbnailTooltip(r: ImageRecord, dims: Option[(Int, Int)]): String =
    val size = dims.fold("")(d => s"${d._1} x ${d._2}  ")
    s"${r.fileName}\n$size${sizeText(r.size)}\n${fileTime(r)}"

/** Maintenance tab operations; each returns the "Last message". Blocking: run off the UI thread. */
final class BrowserMaintenance(env: BrowserEnv):
  def rebuildCache(): String = s"Exif cache rebuilt: ${env.rebuildCache()} files read"
  def deleteZeroEntries(): String = s"Deleted ${env.deleteEmptyCacheEntries()} 0-entries from the exif cache"
  def updateDirectoryNames(from: String, to: String, pathRecorder: Boolean, exifData: Boolean, ranking: Boolean): String =
    if from.trim.isEmpty || to.trim.isEmpty then "Both From and to need a full path"
    else if !pathRecorder && !exifData && !ranking then "Select at least one of path_recorder, exif_data, ranking"
    else
      val c = env.updateDirectoryNames(from.trim, to.trim, pathRecorder, exifData, ranking)
      s"Updated directory names: path_recorder ${c("path_recorder")}, exif_data ${c("exif_data")}, ranking ${c("ranking")}"
  def recreateHash(): String = { val (done, missing) = env.recreateHashes(); s"Hash recreated for $done files ($missing ranked files are missing)" }
  def reapplyRanking(): String = { val (moved, lost) = env.reapplyRanking(); s"Ranking reapplied for $moved moved files ($lost could not be found)" }
  def rebuildRanking(): String = s"Ranking rebuilt from file info: ${env.rebuildRankingFromFiles()} images"

/** The whole Image Browser: tab VMs for the active tabs plus maintenance. */
final class ImageBrowserVM(val env: BrowserEnv):
  private var vms: Map[String, ImageBrowserTabVM] = Map.empty
  def specs: Vector[TabSpec] = env.tabs
  def tab(spec: TabSpec): ImageBrowserTabVM =
    vms.getOrElse(spec.name, { val v = new ImageBrowserTabVM(spec, env); vms += spec.name -> v; v })
  /** Forgets cached tab VMs (after the active-tab or folder options changed). */
  def reset(): Unit = vms = Map.empty
  val maintenance = new BrowserMaintenance(env)
