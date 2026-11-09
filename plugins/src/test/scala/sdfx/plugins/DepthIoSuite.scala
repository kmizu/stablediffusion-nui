package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

class DepthIoSuite extends munit.FunSuite:
  private def ramp16(w: Int, h: Int, lo: Int, hi: Int) =
    val i = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY)
    for y <- 0 until h; x <- 0 until w do i.setRGB(x, y, 0); for y <- 0 until h; x <- 0 until w do i.getRaster.setSample(x, y, 0, lo + (hi - lo) * x / (w - 1))
    i

  test("16-bit values are read at full precision and written back losslessly") {
    val src = ramp16(8, 2, 1000, 61000)
    val (vs, w, h) = DepthIo.values(src)
    assertEqualsDouble(vs(0), 1000 / 65535.0, 1e-9)
    val f = Files.createTempFile("d16", ".png")
    ImageIO.write(DepthIo.to16(vs, w, h), "png", f.toFile)
    val (vs2, _, _) = DepthIo.values(ImageIO.read(f.toFile))
    vs.zip(vs2).foreach((a, b) => assertEqualsDouble(a, b, 1.0 / 65535))
  }

  test("normalize stretches to 0..1, invert flips, resize keeps the ramp monotonic") {
    val (vs, w, h) = DepthIo.values(ramp16(8, 2, 20000, 30000))
    val n = DepthIo.process(vs, w, h, normalize = true, invert = false, 16, 4)
    assertEqualsDouble(n.min, 0.0, 0.05); assertEqualsDouble(n.max, 1.0, 0.05)
    assert(n.take(16).sliding(2).forall(p => p(1) >= p(0) - 1e-9))
    val inv = DepthIo.process(vs, w, h, normalize = true, invert = true, 8, 2)
    assert(inv(0) > 0.9 && inv(7) < 0.1)
  }

  test("import plugin returns an 8-bit map sized like the input; library saves by hand-made file, lists and loads, rejects bad names") {
    val d = Files.createTempDirectory("lib")
    ImageIO.write(DepthIo.to16(Array.tabulate(64)(i => (i % 8) / 7.0), 8, 8), "png", d.resolve("ramp.png").toFile)
    val input = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB)
    val io = new DepthImageIoPlugin
    val r = io.transform(input, GenerationParams(), Values(Values.defaults(io.controls).map ++ Map("file" -> d.resolve("ramp.png").toString)), new FakeContext()).head._1
    assertEquals((r.getWidth, r.getHeight), (32, 16))
    assert((r.getRGB(0, 5) & 255) < 20 && (r.getRGB(31, 5) & 255) > 235)
    val lib = new DepthLibraryPlugin
    def v(m: Map[String, String]) = Values(Values.defaults(lib.controls).map ++ Map("folder" -> d.toString) ++ m)
    val loaded = lib.transform(input, GenerationParams(), v(Map("operation" -> "Load", "name" -> "ramp")), new FakeContext()).head._1
    assertEquals((loaded.getWidth, loaded.getHeight), (32, 16))
    val ctx = new FakeContext()
    lib.transform(input, GenerationParams(), v(Map("operation" -> "List")), ctx)
    intercept[IllegalArgumentException](lib.transform(input, GenerationParams(), v(Map("operation" -> "Load", "name" -> "../x")), new FakeContext()))
    intercept[IllegalArgumentException](lib.transform(input, GenerationParams(), v(Map("operation" -> "Load", "name" -> "missing")), new FakeContext()))
  }

  test("freeName never reuses an existing file") {
    val d = Files.createTempDirectory("fn")
    assertEquals(DepthIo.freeName(d, "a"), "a")
    Files.writeString(d.resolve("a.png"), "x")
    assertEquals(DepthIo.freeName(d, "a"), "a-1")
  }
