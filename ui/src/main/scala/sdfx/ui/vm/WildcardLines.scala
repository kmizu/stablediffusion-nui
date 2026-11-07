package sdfx.ui.vm

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import scala.util.Using

/** Bounded wildcard preview, using the same ordered roots and line rules as Dynamic Prompts. */
object WildcardLines:
  private val MaxBytes = 1024 * 1024
  private val MaxLines = 5000

  def read(roots: Seq[Path], name: String): Vector[String] =
    val normalized = name.replace('\\', '/')
    require(normalized.nonEmpty && !normalized.startsWith("/") && !normalized.contains(':') &&
      !normalized.contains('*') && !normalized.split("/", -1).exists(p => p.isEmpty || p == ".." || p == "."),
      "Invalid wildcard name")
    val file = roots.iterator.filter(Files.isDirectory(_)).flatMap { dir =>
      val root = dir.toRealPath()
      val candidate = root.resolve(normalized + ".txt")
      if !Files.exists(candidate) then None
      else
        val real = candidate.toRealPath()
        require(real.startsWith(root) && real != root, "Wildcard file escapes its declared directory")
        Option.when(Files.isRegularFile(real))(real)
    }.nextOption()
    file.toVector.flatMap { path =>
      val bytes = Using.resource(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))(_.readNBytes(MaxBytes + 1))
      require(bytes.length <= MaxBytes, "Wildcard file exceeds 1 MiB")
      val text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString
      // One extra line lets the UI disclose that later entries were omitted.
      text.linesIterator.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#")).take(MaxLines + 1).toVector
    }
