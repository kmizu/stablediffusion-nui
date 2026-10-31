package sdfx.ui

import munit.FunSuite
import sdfx.core.png.{ImageScrambleSession, ScrambledPng}
import sdfx.ui.vm.FastloadThumbnail
import java.awt.image.BufferedImage
import java.nio.file.Files

class FastloadThumbnailSuite extends FunSuite:
  test("protected thumbnail is decoded in memory and bounded") {
    val dir = Files.createTempDirectory("fastload-thumb-")
    val path = dir.resolve("image.png")
    val source = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
    val g = source.createGraphics()
    try { g.setColor(new java.awt.Color(20, 40, 60)); g.fillRect(0, 0, 400, 200) } finally g.dispose()
    ScrambledPng.write(source, path, Nil, Some("secret"))
    val session = new ImageScrambleSession; session.unlock("secret")
    val image = FastloadThumbnail.read(path, session.snapshot)
    assert(image.getWidth <= 144 && image.getHeight <= 96)
    assertEquals(image.getRGB(0, 0) & 0xffffff, 0x14283c)
    intercept[IllegalArgumentException](FastloadThumbnail.read(path, None))
    val contents = Files.list(dir)
    try assertEquals(contents.count(), 1L) finally contents.close()
    Files.delete(path); Files.delete(dir)
  }
