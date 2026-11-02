package sdfx.core.imagebrowser

import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.control.NonFatal

/** Where deleted files go. Nothing in the browser removes a file without going through this. */
trait Trash:
  /** Moves `p` out of the way; the result names where it went. */
  def discard(p: Path): Either[String, String]

/** A recoverable folder (`<root>/<timestamp>/<name>`); used when the OS recycle bin is unavailable and by tests. */
final class FolderTrash(rootOf: () => Path) extends Trash:
  def this(root: Path) = this(() => root)
  def discard(p: Path): Either[String, String] =
    try
      val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
      val dir = rootOf().resolve(stamp)
      Files.createDirectories(dir)
      val target = BrowserFiles.uniqueTarget(dir, p.getFileName.toString)
      Files.move(p, target)
      Right(target.toString)
    catch case NonFatal(e) => Left(s"${p.getFileName}: ${Option(e.getMessage).getOrElse(e.toString)}")

/** The operating system recycle bin (java.awt.Desktop), falling back to the folder trash when it is unsupported. */
final class SystemTrash(fallback: FolderTrash) extends Trash:
  private def supported: Boolean =
    try java.awt.Desktop.isDesktopSupported && java.awt.Desktop.getDesktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)
    catch case NonFatal(_) => false
  def discard(p: Path): Either[String, String] =
    if supported then
      try
        if java.awt.Desktop.getDesktop.moveToTrash(p.toFile) then Right("recycle bin") else fallback.discard(p)
      catch case NonFatal(_) => fallback.discard(p)
    else fallback.discard(p)

object BrowserFiles:
  /** `name.ext` -> `name.ext` if free, else `name-1.ext`, `name-2.ext` ... (never overwrites). */
  def uniqueTarget(dir: Path, fileName: String): Path =
    var target = dir.resolve(fileName)
    if !Files.exists(target) then return target
    val i = fileName.lastIndexOf('.')
    val (stem, ext) = if i <= 0 then (fileName, "") else (fileName.substring(0, i), fileName.substring(i))
    var n = 1
    while Files.exists(target) do
      target = dir.resolve(s"$stem-$n$ext")
      n += 1
    target

  def sha256(p: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(p)
    try
      val buf = new Array[Byte](64 * 1024)
      var n = in.read(buf)
      while n >= 0 do { md.update(buf, 0, n); n = in.read(buf) }
    finally in.close()
    md.digest().map(b => f"${b & 0xff}%02x").mkString

  /** Copies or moves `src` (and its `.txt` side-car when requested) into `destDir`. Never overwrites; returns the new path. */
  def transfer(src: Path, destDir: Path, copy: Boolean, withTxt: Boolean): Either[String, Path] =
    try
      if !Files.isRegularFile(src) then return Left(s"${src.getFileName} no longer exists")
      Files.createDirectories(destDir)
      if !copy && src.toAbsolutePath.normalize().getParent == destDir.toAbsolutePath.normalize() then
        return Left(s"${src.getFileName} is already in that folder")
      val target = uniqueTarget(destDir, src.getFileName.toString)
      if copy then Files.copy(src, target) else Files.move(src, target)
      if withTxt then
        val side = FileMetadata.sidecar(src)
        if Files.isRegularFile(side) then
          val sideTarget = FileMetadata.sidecar(target)
          if !Files.exists(sideTarget) then
            if copy then Files.copy(side, sideTarget) else Files.move(side, sideTarget)
      Right(target)
    catch case NonFatal(e) => Left(s"${src.getFileName}: ${Option(e.getMessage).getOrElse(e.toString)}")

  /** Deletes images (and side-cars when requested) through the trash, or permanently when `recycle` is off. */
  def delete(paths: Seq[Path], withTxt: Boolean, recycle: Boolean, trash: Trash): Vector[(Path, Either[String, String])] =
    paths.toVector.map { p =>
      val result: Either[String, String] =
        try
          if !Files.exists(p) then Left(s"${p.getFileName} no longer exists")
          else
            val r = if recycle then trash.discard(p) else { Files.delete(p); Right("deleted permanently") }
            if r.isRight && withTxt then
              val side = FileMetadata.sidecar(p)
              if Files.isRegularFile(side) then if recycle then trash.discard(side) else Files.delete(side)
            r
        catch case NonFatal(e) => Left(s"${p.getFileName}: ${Option(e.getMessage).getOrElse(e.toString)}")
      p -> result
    }
