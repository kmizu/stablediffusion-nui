package sdfx.plugins

import java.awt.image.BufferedImage

class CanvasToolsSuite extends munit.FunSuite:
  private def img(w: Int, h: Int)(f: (Int, Int) => Int) =
    val i = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until h; x <- 0 until w do i.setRGB(x, y, f(x, y))
    i

  test("roll wraps around and is reversible") {
    val a = img(8, 2)((x, _) => x * 0x010101 * 30)
    val r = PanoramaTools.roll(a, 3)
    assertEquals(r.getRGB(3, 0) & 255, a.getRGB(0, 0) & 255)
    assertEquals(r.getRGB(0, 0) & 255, a.getRGB(5, 0) & 255)
    assertEquals(PanoramaTools.roll(r, -3).getRGB(6, 1), a.getRGB(6, 1))
  }

  test("blend seam removes the step between the wrapped edges") {
    val a = img(64, 1)((x, _) => if x < 32 then 0 else 0xFFFFFF)
    val step = (a.getRGB(63, 0) & 255) - (a.getRGB(0, 0) & 255)
    val b = PanoramaTools.blendSeam(a, 8)
    assert(math.abs((b.getRGB(63, 0) & 255) - (b.getRGB(0, 0) & 255)) < math.abs(step) / 2)
    assertEquals(b.getRGB(20, 0) & 255, a.getRGB(20, 0) & 255)
  }

  test("view: the centre of the view is the equirect centre; yaw 90 reads a quarter turn away") {
    val w = 400; val a = img(w, 200)((x, _) => (x * 255 / w) * 0x010101)
    val v0 = PanoramaTools.view(a, 0, 0, 60, 64, 64)
    assert(math.abs((v0.getRGB(32, 32) & 255) - 127) <= 4)
    val v90 = PanoramaTools.view(a, 90, 0, 60, 64, 64)
    assert(math.abs((v90.getRGB(32, 32) & 255) - 191) <= 4)
  }

  test("outpaint extends, keeps the original and masks only the new area") {
    val a = img(4, 4)((x, y) => (x + y * 4) * 0x010101 * 10)
    val (o, m) = OutpaintCanvas.extend(a, 2, 0, 0, 3, "Edge")
    assertEquals((o.getWidth, o.getHeight), (6, 7))
    assertEquals(o.getRGB(2, 0) & 0xFFFFFF, a.getRGB(0, 0) & 0xFFFFFF)
    assertEquals(o.getRGB(0, 0) & 255, a.getRGB(0, 0) & 255)
    assertEquals((m.getRGB(0, 0) & 255, m.getRGB(3, 1) & 255, m.getRGB(3, 5) & 255), (255, 0, 255))
    assertEquals(OutpaintCanvas.extend(a, 1, 0, 0, 0, "Mirror")._1.getRGB(0, 2) & 255, a.getRGB(0, 2) & 255)
    intercept[IllegalArgumentException](OutpaintCanvas.extend(a, 0, 0, 0, 0, "Edge"))
  }

  test("dump outputs for a real image for manual checks when SDFX_CANVAS_DUMP=<in.png>;<outdir> is set") {
    sys.env.get("SDFX_CANVAS_DUMP").foreach { spec =>
      val Array(in, out) = spec.split(";")
      val src = javax.imageio.ImageIO.read(new java.io.File(in))
      def w(n: String, i: BufferedImage) = javax.imageio.ImageIO.write(i, "png", new java.io.File(out, n))
      w("roll.png", PanoramaTools.roll(src, src.getWidth / 2))
      w("blend.png", PanoramaTools.blendSeam(PanoramaTools.roll(src, src.getWidth / 2), 48))
      w("view.png", PanoramaTools.view(src, 40, 10, 90, 512, 384))
      val (o, m) = OutpaintCanvas.extend(src, 0, 0, 192, 0, "Mirror")
      w("outpaint.png", o); w("mask.png", m)
    }
  }
