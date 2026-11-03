package sdfx.core.png

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import scala.util.Using
import sdfx.core.panorama.PanoramaFiles

/** Immutable encoded source used for both displayed pixels and generation parameters. */
object PanoramaSourceSnapshot:
  val MaxBytes = 64 * 1024 * 1024
  final class Unavailable(message: String) extends RuntimeException(message)
  final class Snapshot private[PanoramaSourceSnapshot] (private val bytes: Array[Byte]):
    lazy val parameters: Option[String] = PngMetadata.readParameters(bytes)
    def decode(password: Option[String]): ScrambledPng.Decoded = ScrambledPng.read(bytes, password)

  def read(path: Path, maxBytes: Int = MaxBytes): Snapshot =
    require(maxBytes > 0 && maxBytes < Int.MaxValue, "Invalid panorama snapshot limit")
    val bytes = Using.resource(Files.newInputStream(path))(_.readNBytes(maxBytes + 1))
    if bytes.length > maxBytes then
      val png = bytes.length >= 8 && bytes.take(8).sameElements(Array[Byte](0x89.toByte, 80, 78, 71, 13, 10, 26, 10))
      if png then throw new IllegalArgumentException("PNG source exceeds the 64 MiB verified metadata limit")
      else throw new Unavailable("source is larger than the metadata snapshot limit")
    BoundedPngMetadata.check(bytes)
    Using.resource(new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) { in =>
      val readers = ImageIO.getImageReaders(in)
      if !readers.hasNext then throw new Unavailable("source format has no metadata-safe decoder")
      val reader = readers.next()
      try
        reader.setInput(in, true, true)
        PanoramaFiles.checkSize(reader.getWidth(0), reader.getHeight(0)) match
          case Left(message) => throw new IllegalArgumentException(message)
          case Right(_) => ()
      finally reader.dispose()
    }
    new Snapshot(bytes)
