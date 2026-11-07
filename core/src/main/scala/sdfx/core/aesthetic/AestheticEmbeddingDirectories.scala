package sdfx.core.aesthetic

import sdfx.core.config.AtomicFiles
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** Local index of explicitly used directories. This index never grants filesystem access. */
object AestheticEmbeddingDirectories:
  private val MaxBytes = 1024 * 1024
  private val MaxDirectories = 256
  private def network(s: String): Boolean =
    val p = s.trim.toLowerCase(java.util.Locale.ROOT)
    p.startsWith("\\\\") || p.startsWith("//") || p.startsWith("\\/") || p.startsWith("/\\") ||
      p.startsWith("file:") || p.startsWith("smb:")

  private def raw(file: Path): Vector[String] =
    if !Files.exists(file) then Vector.empty
    else
      require(Files.size(file) <= MaxBytes, "aesthetic directory index is too large")
      val obj = ujson.read(Files.readString(file))
      require(obj("version").num == 1, "unsupported aesthetic directory index")
      val dirs = obj("directories").arr.toVector.map(_.str)
      require(dirs.size <= MaxDirectories && dirs.forall(s => s.nonEmpty && s.length <= 4096), "invalid aesthetic directory index")
      dirs

  private def approved(s: String, authorized: Path => Boolean): Option[Path] =
    if network(s) then None
    else
      try
        val p = Paths.get(s)
        if !p.isAbsolute || !authorized(p.normalize()) || !Files.isDirectory(p) then None
        else
          val real = p.toRealPath()
          Option.when(!network(real.toString) && authorized(real))(real)
      catch case NonFatal(_) => None

  /** Reads existing entries but rechecks authorization for every entry. */
  def read(file: Path, authorized: Path => Boolean): Either[String, Vector[Path]] = synchronized {
    try Right(raw(file).flatMap(s => approved(s, authorized)).distinct)
    catch case NonFatal(e) => Left(s"Cannot read aesthetic directory index: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
  }

  /** Only explicitly authorized existing directories may be added; corrupt indexes are not overwritten. */
  def register(file: Path, directory: Path, authorized: Path => Boolean): Either[String, Unit] = synchronized {
    try
      val real = approved(directory.toAbsolutePath.normalize().toString, authorized)
        .getOrElse(throw new IllegalArgumentException("aesthetic directory is not authorized"))
      val index = file.toAbsolutePath.normalize()
      Files.createDirectories(index.getParent)
      val lockPath = index.resolveSibling(index.getFileName.toString + ".lock")
      val channel = java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
      try
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        var lock: java.nio.channels.FileLock = null
        while lock == null && System.nanoTime() < deadline do
          try lock = channel.tryLock()
          catch case _: java.nio.channels.OverlappingFileLockException => ()
          if lock == null then Thread.sleep(25)
        require(lock != null, "aesthetic directory index is busy; retry")
        try
          val entries = (raw(index) :+ real.toString).distinct
          require(entries.size <= MaxDirectories, "too many aesthetic directories")
          AtomicFiles.writeString(index, ujson.write(ujson.Obj("version" -> 1, "directories" -> ujson.Arr.from(entries)), indent = 2) + "\n")
        finally lock.release()
      finally channel.close()
      Right(())
    catch case NonFatal(e) => Left(s"Cannot register aesthetic directory: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
  }
