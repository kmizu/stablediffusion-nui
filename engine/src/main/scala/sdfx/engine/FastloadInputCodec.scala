package sdfx.engine

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Decode imported pixels in memory with a bound checked before allocation. */
object FastloadInputCodec:
  def decode(png: Array[Byte]): BufferedImage =
    require(png != null && png.nonEmpty && png.length <= 128 * 1024 * 1024, "Invalid Fastload image")
    val input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))
    try
      val readers = ImageIO.getImageReaders(input)
      if !readers.hasNext then throw new IllegalArgumentException("Invalid Fastload image")
      val reader = readers.next()
      try
        reader.setInput(input, true, true)
        require(reader.getWidth(0).toLong * reader.getHeight(0).toLong <= 32000000, "Fastload image is too large")
        Option(reader.read(0)).getOrElse(throw new IllegalArgumentException("Invalid Fastload image"))
      finally reader.dispose()
    finally input.close()
