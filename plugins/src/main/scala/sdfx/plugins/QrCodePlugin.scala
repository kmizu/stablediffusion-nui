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

/** Scannability check for generated QR art (the verification half of antfu's QR toolkit; decoded with ZXing). */
object QrVerify:
  import com.google.zxing.{BarcodeFormat, BinaryBitmap, DecodeHintType, MultiFormatReader}
  import com.google.zxing.client.j2se.BufferedImageLuminanceSource
  import com.google.zxing.common.HybridBinarizer

  /** Tries the image as is, then at half and quarter size: a phone camera sees QR art softened. Returns text and the size that worked. */
  def decode(img: BufferedImage, tryHarder: Boolean): Option[(String, String)] =
    val hints = new java.util.HashMap[DecodeHintType, Any]()
    hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(BarcodeFormat.QR_CODE))
    if tryHarder then hints.put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
    def attempt(i: BufferedImage): Option[String] =
      try Some(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(i))), hints).getText)
      catch case _: com.google.zxing.ReaderException => None
    def scaled(f: Double): BufferedImage =
      val w = math.max(32, (img.getWidth * f).toInt)
      val h = math.max(32, (img.getHeight * f).toInt)
      val o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
      val g = o.createGraphics()
      g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g.drawImage(img, 0, 0, w, h, null)
      g.dispose()
      o
    Seq[(String, () => BufferedImage)]("full size" -> (() => img), "half size" -> (() => scaled(0.5)), "quarter size" -> (() => scaled(0.25)))
      .iterator.flatMap((label, f) => attempt(f()).map(_ -> label)).nextOption()

class QrVerifyPlugin extends ExtrasPlugin:
  def id = "qr_verify"
  def name = "QR scan check"
  override def description = "Tries to read a QR code from the image (as is, then at half and quarter size, like a phone camera would). " +
    "Returns the image unchanged and records the decoded text, or that it could not be read."
  override def controls: Seq[Control] = Seq(
    Text("expected", "Expected text (optional)", ""),
    Check("try_harder", "Try harder (slower)", true),
    Check("fail_if_unreadable", "Fail the job when the code cannot be read")
  )
  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext) =
    val found = QrVerify.decode(img, v.bool("try_harder", true))
    val expected = v.str("expected").trim
    val status = found match
      case Some((t, how)) if expected.isEmpty || expected == t => s"readable ($how): $t"
      case Some((t, how)) => s"readable ($how) but different: $t"
      case None => "not readable"
    if v.bool("fail_if_unreadable") then require(found.exists((t, _) => expected.isEmpty || t == expected), s"QR check failed: $status")
    ctx.log(s"QR scan check: $status")
    Seq((img, Seq("QR scan check" -> status)))
