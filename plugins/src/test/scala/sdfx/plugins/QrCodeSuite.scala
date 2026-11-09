package sdfx.plugins

import com.google.zxing.{BinaryBitmap, MultiFormatReader}
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import java.awt.image.BufferedImage

class QrCodeSuite extends munit.FunSuite:
  private def decode(i: BufferedImage): String =
    new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(i)))).getText

  private def run(v: Map[String, String], w: Int = 512, h: Int = 512): BufferedImage =
    val p = new QrCodePlugin
    val vals = Values(Values.defaults(p.controls).map ++ v)
    p.transform(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), GenerationParams(), vals, new FakeContext()).head._1

  test("QR round-trips through a real decoder, including non-ASCII text") {
    for t <- Seq("https://example.com/a?b=1", "こんにちは QR") do
      val img = run(Map("text" -> t))
      assertEquals((img.getWidth, img.getHeight), (512, 512))
      assertEquals(decode(img), t)
  }

  test("non-square input keeps its size and the code stays decodable") {
    val img = run(Map("text" -> "hello"), 640, 384)
    assertEquals((img.getWidth, img.getHeight), (640, 384))
    assertEquals(decode(img), "hello")
  }

  test("empty text is rejected") {
    intercept[IllegalArgumentException](run(Map("text" -> "")))
  }

  private def verify(img: BufferedImage, v: Map[String, String] = Map.empty): String =
    val p = new QrVerifyPlugin
    p.transform(img, GenerationParams(), Values(Values.defaults(p.controls).map ++ v), new FakeContext()).head._2.head._2

  test("QR scan check reads a generated code, also at reduced size, and reports mismatches") {
    val img = run(Map("text" -> "https://example.com/x"))
    assert(verify(img).startsWith("readable (full size): https://example.com/x"))
    assert(verify(img, Map("expected" -> "other")).contains("but different"))
    intercept[IllegalArgumentException](verify(img, Map("expected" -> "other", "fail_if_unreadable" -> "true")))
  }

  test("QR scan check says not readable for a blank image and can fail the job") {
    val blank = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
    assertEquals(verify(blank), "not readable")
    intercept[IllegalArgumentException](verify(blank, Map("fail_if_unreadable" -> "true")))
  }

  test("dump a control image for manual ControlNet checks when SDFX_QR_DUMP is set") {
    sys.env.get("SDFX_QR_DUMP").foreach { path =>
      javax.imageio.ImageIO.write(run(Map("text" -> "https://kmizu.dev", "border" -> "2")), "png", new java.io.File(path))
    }
  }
