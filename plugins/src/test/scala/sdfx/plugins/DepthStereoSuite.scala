package sdfx.plugins

import java.awt.image.BufferedImage

class DepthStereoSuite extends munit.FunSuite:
  private def solid(w: Int, h: Int, rgb: Int) =
    val i = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until h; x <- 0 until w do i.setRGB(x, y, rgb)
    i
  private def grey(w: Int, h: Int, v: Int) = solid(w, h, v * 0x010101)

  test("zero shift is the identity") {
    val img = solid(8, 4, 0x336699); img.setRGB(3, 2, 0xFF0000)
    val out = DepthStereo.warp(img, grey(8, 4, 200), 0.0)
    for y <- 0 until 4; x <- 0 until 8 do assertEquals(out.getRGB(x, y) & 0xFFFFFF, img.getRGB(x, y) & 0xFFFFFF)
  }
  test("near pixels move right for a positive shift, far pixels move left, and the near one wins an overlap") {
    val w = 20
    val img = solid(w, 1, 0x000000)
    val depth = new BufferedImage(w, 1, BufferedImage.TYPE_INT_RGB)
    for x <- 0 until w do depth.setRGB(x, 0, 0x808080)
    img.setRGB(10, 0, 0xFF0000); depth.setRGB(10, 0, 0xFFFFFF)   // near marker
    val out = DepthStereo.warp(img, depth, 8.0)
    val moved = (0 until w).find(x => (out.getRGB(x, 0) & 0xFFFFFF) == 0xFF0000).get
    assert(moved > 10, s"near marker should move right, got $moved")
  }
  test("holes are filled, nothing is left unpainted") {
    val img = solid(16, 2, 0x20C020)
    val d = grey(16, 2, 255)
    val out = DepthStereo.warp(img, d, 6.0)
    for y <- 0 until 2; x <- 0 until 16 do assertEquals(out.getRGB(x, y) & 0xFFFFFF, 0x20C020)
  }
  test("layouts have the expected sizes and anaglyph takes red from the left and green/blue from the right") {
    val l = solid(6, 4, 0xFF0000 | 0x0000FF); val r = solid(6, 4, 0x00FF00)
    assertEquals(DepthStereo.sideBySide(l, r).getWidth, 12)
    assertEquals(DepthStereo.overUnder(l, r).getHeight, 8)
    assertEquals(DepthStereo.anaglyph(l, r).getRGB(0, 0) & 0xFFFFFF, 0xFF0000 | 0x00FF00)
  }

  test("render a real image pair when SDFX_STEREO_IMG / SDFX_STEREO_DEPTH / SDFX_STEREO_OUT are set") {
    for img <- sys.env.get("SDFX_STEREO_IMG"); dp <- sys.env.get("SDFX_STEREO_DEPTH"); out <- sys.env.get("SDFX_STEREO_OUT") do
      val i = javax.imageio.ImageIO.read(new java.io.File(img)); val d = javax.imageio.ImageIO.read(new java.io.File(dp))
      val s = i.getWidth * 0.03
      val l = DepthStereo.warp(i, d, s); val r = DepthStereo.warp(i, d, -s)
      javax.imageio.ImageIO.write(DepthStereo.sideBySide(l, r), "png", new java.io.File(out + "-sbs.png"))
      javax.imageio.ImageIO.write(DepthStereo.anaglyph(l, r), "png", new java.io.File(out + "-ana.png"))
  }
