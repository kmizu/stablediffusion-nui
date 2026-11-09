package sdfx.plugins

import com.google.zxing.{BarcodeFormat, EncodeHintType}
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.awt.image.BufferedImage

/** QR code image for ControlNet (qr_code / QR Code Generator equivalent; encoded with ZXing, Apache-2.0). */
class QrCodePlugin extends ExtrasPlugin:
  def id = "qr_code"
  def name = "QR code"
  override def description = "Replaces the image with a QR code of the given text, sized like the input image, for use as a " +
    "ControlNet (QR monster) control image. Dark modules are black, the rest white."
  private val levels = Seq("L", "M", "Q", "H")
  override def controls: Seq[Control] = Seq(
    Text("text", "Text or URL", "https://example.com"),
    Choice("ecc", "Error correction", levels, "H"),
    IntField("border", "Quiet zone (modules)", 0, 16, 4),
    Check("invert", "Invert (white modules on black)")
  )
  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext) =
    val text = v.str("text", "")
    require(text.nonEmpty, "QR code text is empty")
    require(img.getWidth > 0 && img.getHeight > 0, "Input image is empty")
    val ecc = v.str("ecc", "H")
    require(levels.contains(ecc), "Invalid error correction level")
    val border = v.int("border", 4)
    val hints = new java.util.HashMap[EncodeHintType, Any]()
    hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.valueOf(ecc))
    hints.put(EncodeHintType.MARGIN, Int.box(border))
    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8")
    val size = math.min(img.getWidth, img.getHeight)
    val m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val inv = v.bool("invert")
    val out = new BufferedImage(img.getWidth, img.getHeight, BufferedImage.TYPE_INT_RGB)
    val bg = if inv then 0x000000 else 0xFFFFFF
    val ox = (img.getWidth - m.getWidth) / 2
    val oy = (img.getHeight - m.getHeight) / 2
    for y <- 0 until out.getHeight; x <- 0 until out.getWidth do
      val mx = x - ox; val my = y - oy
      val dark = mx >= 0 && my >= 0 && mx < m.getWidth && my < m.getHeight && m.get(mx, my)
      out.setRGB(x, y, if dark then (if inv then 0xFFFFFF else 0x000000) else bg)
    Seq((out, Seq("QR code" -> s"${text.length} chars, ECC $ecc")))
