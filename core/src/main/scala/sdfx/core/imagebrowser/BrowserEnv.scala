package sdfx.core.imagebrowser

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Metadata cache keyed by (path, mtime, size); optionally persisted so the second scan of a big folder is fast. */
final class MetaCache(file: Option[Path]):
  private final case class Entry(modified: Long, size: Long, facts: FileFacts)
  private val map = new ConcurrentHashMap[String, Entry]()
  private def key(p: Path) = p.toAbsolutePath.normalize().toString

  file.foreach(load)

  private def load(f: Path): Unit =
    try
      if Files.isRegularFile(f) then
        ujson.read(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8)).arr.foreach { v =>
          val o = v.obj
          map.put(o("p").str, Entry(o("m").num.toLong, o("s").num.toLong,
            FileFacts(o.get("t").collect { case ujson.Str(s) => s }, o.get("r").collect { case ujson.Num(n) => n.toInt })))
        }
    catch case NonFatal(_) => map.clear() // a damaged cache is rebuilt on the next scan

  def size: Int = map.size()

  /** Cached facts when still valid, else reads the file once. */
  def facts(p: Path, modified: Long, size: Long): FileFacts =
    val k = key(p)
    val e = map.get(k)
    if e != null && e.modified == modified && e.size == size then e.facts
    else
      val f = FileMetadata.read(p)
      map.put(k, Entry(modified, size, f))
      f

  def clear(): Unit = map.clear()

  /** Drops entries whose file carries no generation text ("0-entries"). Returns how many. */
  def deleteEmpty(): Int =
    val dead = map.asScala.collect { case (k, e) if e.facts.parameters.isEmpty => k }.toVector
    dead.foreach(map.remove)
    dead.size

  def forget(p: Path): Unit = map.remove(key(p))

  /** Re-keys entries below `from` to `to` (directory renamed on disk). */
  def replacePrefix(from: String, to: String): Int =
    var n = 0
    map.asScala.toVector.foreach { (k, e) =>
      if k == from || k.startsWith(from + java.io.File.separator) then
        map.remove(k); map.put(to + k.substring(from.length), e); n += 1
    }
    n

  def persist(): Unit = file.foreach { f =>
    try
      val arr = ujson.Arr.from(map.asScala.toVector.map { (k, e) =>
        val o = ujson.Obj("p" -> k, "m" -> ujson.Num(e.modified.toDouble), "s" -> ujson.Num(e.size.toDouble))
        e.facts.parameters.foreach(t => o("t") = ujson.Str(t))
        e.facts.ranking.foreach(r => o("r") = ujson.Num(r))
        o
      })
      Files.createDirectories(f.toAbsolutePath.getParent)
      val tmp = Files.createTempFile(f.toAbsolutePath.getParent, ".image-browser-cache-", ".tmp")
      try
        Files.write(tmp, ujson.write(arr).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
      finally Files.deleteIfExists(tmp)
    catch case NonFatal(_) => () // the cache is an optimisation only
  }

/** Everything the browser needs from disk: scanning, rankings, favorites, copy/move, delete, maintenance. */
final class BrowserEnv(outputsOf: () => Path, val configDir: Path, val trash: Trash):
  def this(outputs: Path, configDir: Path, trash: Trash) = this(() => outputs, configDir, trash)
  /** The outputs folder is read each time, as the user can change it in the application settings. */
  def outputs: Path = outputsOf()
  val store = new BrowserStore(configDir.resolve("image-browser.json"))
  val cache = new MetaCache(Some(configDir.resolve("image-browser-cache.json")))

  def settings: BrowserSettings = store.settings
  def tabs: Vector[TabSpec] = BrowserTabs.resolveAll(settings, outputs)
  def favoritesDir: Path = BrowserTabs.favoritesDir(outputs, settings)

  def rankingOf(r: ImageRecord): Option[Int] = store.ranking(r.path).orElse(r.embeddedRanking)

  // ---- scanning -------------------------------------------------------------------------------------------
  /** Lists media below `dirs`. `maxDepth`: 0 = only the folder itself, negative = unlimited. */
  def scan(dirs: Seq[Path], maxDepth: Int): Vector[ImageRecord] =
    val limit = if maxDepth < 0 then Int.MaxValue else maxDepth
    val readMeta = settings.scanExif
    val seen = scala.collection.mutable.LinkedHashMap.empty[Path, ImageRecord]
    def visit(dir: Path, depth: Int): Unit =
      val stream = try Files.newDirectoryStream(dir) catch case NonFatal(_) => return
      try
        stream.iterator().asScala.foreach { e =>
          try
            val name = e.getFileName.toString
            if Files.isDirectory(e) then
              if depth < limit && !name.startsWith(".") && !Files.isSymbolicLink(e) then visit(e, depth + 1)
            else if FileMetadata.isMedia(e) && Files.isRegularFile(e) then
              val key = e.toAbsolutePath.normalize()
              if !seen.contains(key) then
                val modified = Files.getLastModifiedTime(e).toMillis
                val size = Files.size(e)
                val rec =
                  if readMeta && FileMetadata.isImage(e) then
                    val f = cache.facts(e, modified, size)
                    ImageRecord(key, modified, size, ImageMeta(f.parameters), f.ranking)
                  else ImageRecord(key, modified, size, ImageMeta(None))
                seen.put(key, rec)
          catch case NonFatal(_) => () // an unreadable entry must not hide the others
        }
      finally stream.close()
    dirs.foreach(d => if Files.isDirectory(d) then visit(d, 0))
    seen.values.toVector

  /** Standard/Favorites/Custom/All tabs; depth rules follow the documented option semantics. */
  def list(spec: TabSpec): Vector[ImageRecord] = spec.kind match
    case TabKind.All => scan(spec.dirs, -1)
    case TabKind.Maintenance | TabKind.Others => Vector.empty
    case _ => scan(spec.dirs, if settings.withSubdirs then -1 else 0)

  /** The "Others" tab: any folder, with an explicit sub directory depth (0 = only that folder, negative = unlimited). */
  def listOthers(dir: Path, depth: Int): Vector[ImageRecord] = scan(Seq(dir), depth)

  /** Immediate sub directories (the "Get sub directories" list). */
  def subdirectories(dir: Path): Vector[Path] =
    try
      val s = Files.newDirectoryStream(dir)
      try s.iterator().asScala.filter(p => Files.isDirectory(p) && !p.getFileName.toString.startsWith(".")).toVector.sortBy(_.getFileName.toString.toLowerCase)
      finally s.close()
    catch case NonFatal(_) => Vector.empty

  // ---- ranking --------------------------------------------------------------------------------------------
  /** Sets (or clears) the ranking; also written into the PNG when "Save ranking in image's pnginfo" is on. */
  def setRanking(p: Path, rank: Option[Int]): Either[String, Unit] =
    try
      store.setRanking(p, rank, if rank.isDefined && Files.isRegularFile(p) then BrowserFiles.sha256(p) else "")
      val isPng = FileMetadata.extension(p) == "png" && Files.isRegularFile(p)
      // Keep an embedded ranking consistent even when the option is off now, so a stale value cannot come back later.
      if isPng && (settings.rankingPnginfo || FileMetadata.read(p).ranking.isDefined) then
        PngText.set(p, FileMetadata.RankingKey, rank.map(_.toString))
        cache.forget(p)
      Right(())
    catch case NonFatal(e) => Left(s"Ranking could not be saved: ${Option(e.getMessage).getOrElse(e.toString)}")

  // ---- favorites / copy / move / delete -------------------------------------------------------------------
  /** Copies or moves into a folder according to the "Move buttons copy instead of move" option. */
  def sendToDirectory(p: Path, dir: Path): Either[String, Path] =
    val copy = settings.copyInsteadOfMove
    BrowserFiles.transfer(p, dir, copy, settings.txtFiles).map { target =>
      if copy then store.rankEntry(p).foreach(e => store.setRanking(target, Some(e.rank), e.hash)) else store.relocate(p, target)
      cache.forget(p)
      target
    }

  def addToFavorites(p: Path): Either[String, Path] = sendToDirectory(p, favoritesDir)

  def delete(paths: Seq[Path]): Vector[(Path, Either[String, String])] =
    val results = BrowserFiles.delete(paths, settings.txtFiles, settings.deleteRecycle, trash)
    results.foreach { (p, r) => if r.isRight then { store.setRanking(p, None); cache.forget(p) } }
    results

  // ---- maintenance ----------------------------------------------------------------------------------------
  /** Roots searched by maintenance operations: every listing tab folder plus saved directories. */
  def searchRoots: Vector[Path] =
    (tabs.flatMap(_.dirs) ++ store.savedDirs.map(d => Path.of(d.path))).distinct.toVector

  /** "Rebuild exif cache": forget everything and read every file again. Returns the number of files read. */
  def rebuildCache(): Int =
    cache.clear()
    val n = scan(searchRoots, -1).size
    cache.persist()
    n

  def deleteEmptyCacheEntries(): Int = { val n = cache.deleteEmpty(); cache.persist(); n }

  /** "Update directory names in database". */
  def updateDirectoryNames(from: String, to: String, pathRecorder: Boolean, exifData: Boolean, ranking: Boolean): Map[String, Int] =
    val f = Path.of(from).toAbsolutePath.normalize().toString
    val t = Path.of(to).toAbsolutePath.normalize().toString
    def re(s: String): String = if s == f || s.startsWith(f + java.io.File.separator) then t + s.substring(f.length) else s
    var rec = 0; var exif = 0; var rank = 0
    if pathRecorder then
      val updated = store.savedDirs.map { d => val n = re(d.path); if n != d.path then rec += 1; d.copy(path = n) }
      store.replaceSavedDirs(updated)
    if exifData then { exif = cache.replacePrefix(f, t); cache.persist() }
    if ranking then
      val updated = store.rankings.map { (k, v) => val n = re(k); if n != k then rank += 1; n -> v }
      store.replaceRankings(updated)
    Map("path_recorder" -> rec, "exif_data" -> exif, "ranking" -> rank)

  /** "Recreate hash for existing files": recomputes the content hash of every ranked file that still exists. */
  def recreateHashes(): (Int, Int) =
    var done = 0; var missing = 0
    val updated = store.rankings.map { (k, v) =>
      val p = Path.of(k)
      if Files.isRegularFile(p) then { done += 1; k -> v.copy(hash = BrowserFiles.sha256(p)) }
      else { missing += 1; k -> v }
    }
    store.replaceRankings(updated)
    (done, missing)

  /** "Reapply ranking after moving files": rankings whose file vanished follow a file with the same content hash. */
  def reapplyRanking(): (Int, Int) =
    val lost = store.rankings.filter((k, v) => !Files.exists(Path.of(k)) && v.hash.nonEmpty)
    if lost.isEmpty then (0, 0)
    else
      val byHash = lost.groupBy(_._2.hash)
      var moved = 0
      var current = store.rankings
      scan(searchRoots, -1).foreach { r =>
        if !current.contains(r.path.toString) then
          val h = BrowserFiles.sha256(r.path)
          byHash.get(h).flatMap(_.headOption).foreach { (oldKey, e) =>
            if current.contains(oldKey) then
              current = current - oldKey + (r.path.toString -> e)
              moved += 1
          }
      }
      store.replaceRankings(current)
      (moved, lost.size - moved)

  /** "Rebuild ranking from exif info": takes the ranking stored inside the files. */
  def rebuildRankingFromFiles(): Int =
    var n = 0
    scan(searchRoots, -1).foreach { r =>
      r.embeddedRanking.foreach { rank =>
        if store.ranking(r.path).isEmpty then
          store.setRanking(r.path, Some(rank), BrowserFiles.sha256(r.path)); n += 1
      }
    }
    n
