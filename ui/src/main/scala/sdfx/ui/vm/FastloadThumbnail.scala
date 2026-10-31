package sdfx.ui.vm

import sdfx.core.png.{ImageScrambleSecret, PngMetadata, ScrambledPng}
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream

/** Decode a bounded thumbnail in memory without caching original pixels to disk. */
object FastloadThumbnail:
  def read(path: Path, secret: Option[ImageScrambleSecret]): BufferedImage =
    val input = new FileImageInputStream(path.toFile)
    val source = try
      val readers = ImageIO.getImageReaders(input)
      require(readers.hasNext, "Image is not readable")
      val reader = readers.next()
      try
        reader.setInput(input, true, true)
        val width = reader.getWidth(0); val height = reader.getHeight(0)
        require(width.toLong * height.toLong <= 32000000, "Image is too large")
        if PngMetadata.readTexts(path).contains("Encrypt") then
          ScrambledPng.read(path, secret.map(_.use(identity))).image
        else
          val param = reader.getDefaultReadParam
          val step = math.max(1, math.max(width / 288, height / 192))
          param.setSourceSubsampling(step, step, 0, 0)
          Option(reader.read(0, param)).getOrElse(throw new IllegalArgumentException("Image is not readable"))
      finally reader.dispose()
    finally input.close()
    val scale = math.min(1.0, math.min(144.0 / source.getWidth, 96.0 / source.getHeight))
    val width = math.max(1, (source.getWidth * scale).round.toInt)
    val height = math.max(1, (source.getHeight * scale).round.toInt)
    val result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = result.createGraphics()
    try
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      graphics.drawImage(source, 0, 0, width, height, null)
    finally graphics.dispose()
    result
