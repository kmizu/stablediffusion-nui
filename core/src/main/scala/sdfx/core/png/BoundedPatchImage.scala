package sdfx.core.png

import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, IOException}
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import scala.util.Using

/** One bounded byte snapshot is inspected and decoded; changing the original path cannot replace it. */
object BoundedPatchImage:
  val MaxEncodedBytes = 64 * 1024 * 1024
  val MaxDimension = 16384
  val MaxPixels = 16777216L

  final class Snapshot private[BoundedPatchImage] (private val bytes: Array[Byte], val width: Int, val height: Int):
    def decode(password: Option[String]): BufferedImage =
      val image = ScrambledPng.read(bytes, password).image
      if image.getWidth != width || image.getHeight != height then
        throw new IllegalArgumentException("Image dimensions changed during decoding")
      image

  def snapshot(path: Path, maxEncodedBytes: Int = MaxEncodedBytes): Snapshot =
    require(maxEncodedBytes > 0 && maxEncodedBytes < Int.MaxValue, "Invalid encoded image limit")
    val bytes = Using.resource(Files.newInputStream(path))(_.readNBytes(maxEncodedBytes + 1))
    if bytes.length > maxEncodedBytes then throw new IllegalArgumentException("The view image file is too large")
    BoundedPngMetadata.check(bytes)
    val (width, height) = Using.resource(new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) { in =>
      val readers = ImageIO.getImageReaders(in)
      if !readers.hasNext then throw new IllegalArgumentException("Unsupported or unreadable view image format")
      val reader = readers.next()
      try
        reader.setInput(in, true, true)
        (reader.getWidth(0), reader.getHeight(0))
      catch
        case e: IOException => throw new IllegalArgumentException("Unreadable view image header", e)
      finally reader.dispose()
    }
    if width < 2 || height < 2 || width > MaxDimension || height > MaxDimension || width.toLong * height > MaxPixels then
      throw new IllegalArgumentException("The view image is too large or too small")
    new Snapshot(bytes, width, height)
