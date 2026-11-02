package sdfx.core.aesthetic

import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Resolves a metadata name only among explicitly supplied, authorized directories. */
object AestheticEmbeddingLocator:
  private val MaxEmbeddingBytes = 64L * 1024 * 1024
  private val MaxEntries = 4096
  private val Extensions = Set(".pt", ".safetensors")

  /** Full SHA-256 of a bounded, stable file. Never substitutes a hash of the path. */
  def fingerprint(path: Path): String =
    val before = Files.readAttributes(path, classOf[BasicFileAttributes])
    require(before.isRegularFile && before.size <= MaxEmbeddingBytes, "embedding is not a bounded regular file")
    val digest = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path)
    try
      val buf = new Array[Byte](8192)
      var n = in.read(buf)
      var total = 0L
      while n != -1 do
        total += n
        require(total <= MaxEmbeddingBytes, "embedding exceeds size limit")
        digest.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    val after = Files.readAttributes(path, classOf[BasicFileAttributes])
    require(before.size == after.size && before.lastModifiedTime == after.lastModifiedTime &&
      before.fileKey == after.fileKey, "embedding changed while hashing")
    digest.digest().map(b => f"${b & 0xff}%02x").mkString

  /** Legacy metadata lacks a fingerprint: refuse ambiguous names rather than selecting the wrong file. */
  def resolve(stem: String, hash: Option[String], directories: Seq[Path], authorized: Path => Boolean): Either[String, Path] =
    val name = Option(stem).getOrElse("").trim
    if name.isEmpty || name.startsWith(".") || name.exists(ch => "\\/:*?\"<>|".contains(ch)) then
      return Left("Invalid aesthetic embedding name")
    val expected = hash.map(_.trim.toLowerCase(Locale.ROOT))
    if expected.exists(h => !h.matches("[0-9a-f]{64}")) then return Left("Invalid aesthetic embedding fingerprint")
    try
      val candidates = directories.distinct.flatMap { directory =>
        if !authorized(directory.toAbsolutePath.normalize()) || !Files.isDirectory(directory) then Vector.empty
        else
          val realDir = directory.toRealPath()
          if !authorized(realDir) then Vector.empty
          else
            val stream = Files.list(realDir)
            try
              val entries = stream.iterator().asScala.take(MaxEntries + 1).toVector
              require(entries.size <= MaxEntries, "Too many files in an aesthetic embedding directory")
              entries.flatMap { p =>
                val filename = p.getFileName.toString
                val ext = Extensions.find(e => filename.toLowerCase(Locale.ROOT).endsWith(e))
                val requestedExtension = Extensions.exists(e => name.toLowerCase(Locale.ROOT).endsWith(e))
                if ext.exists(e => if requestedExtension then filename == name else filename.dropRight(e.length) == name) && Files.isRegularFile(p) then
                  val real = p.toRealPath()
                  Option.when(authorized(real))(real)
                else None
              }
            finally stream.close()
      }.distinct
      val matches = expected.fold(candidates)(h => candidates.filter(p => fingerprint(p) == h))
      matches match
        case Seq(path) => Right(path)
        case Seq() => Left("Aesthetic embedding was not found or its fingerprint does not match")
        case _ => Left("Aesthetic embedding is ambiguous; select the intended file")
    catch case NonFatal(e) => Left(s"Cannot resolve aesthetic embedding: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
