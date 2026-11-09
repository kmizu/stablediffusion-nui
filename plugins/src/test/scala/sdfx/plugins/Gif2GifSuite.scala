package sdfx.plugins

import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.*
import java.awt.image.BufferedImage
import java.nio.file.Files

class Gif2GifSuite extends munit.FunSuite:
  private def solid(c: Int) =
    val i = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until 24; x <- 0 until 32 do i.setRGB(x, y, c)
    i

  private def makeGif(colors: Seq[Int], delay: Int) =
    val f = Files.createTempDirectory("g2g").resolve("in.gif")
    GifFrames.write(f, colors.map(c => GifFrames.Frame(solid(c), delay)))
    f

  test("GIF write/read round-trips frame count, size, delay and (approximate) colours") {
    val f = makeGif(Seq(0xFF0000, 0x00FF00, 0x0000FF), 7)
    val fr = GifFrames.read(f, 100)
    assertEquals(fr.size, 3)
    assertEquals((fr.head.image.getWidth, fr.head.image.getHeight, fr.head.delayCs), (32, 24, 7))
    def near(a: Int, b: Int) = (0 to 2).forall(k => math.abs(((a >> (8 * k)) & 255) - ((b >> (8 * k)) & 255)) < 24)
    assert(near(fr(0).image.getRGB(5, 5), 0xFF0000) && near(fr(1).image.getRGB(5, 5), 0x00FF00) && near(fr(2).image.getRGB(5, 5), 0x0000FF))
    assertEquals(GifFrames.read(f, 2).size, 2)
  }

  test("plugin runs img2img per frame with the frame as init image and writes a GIF without overwriting") {
    val f = makeGif(Seq(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00), 5)
    val out = Files.createTempDirectory("g2gout")
    val ctx = new FakeContext(); val p = new Gif2GifPlugin
    val v = Values(Values.defaults(p.controls).map ++ Map("gif" -> f.toString, "folder" -> out.toString, "every" -> "2"))
    val params = GenerationParams(prompt = "x", seed = 3, img2img = Some(Img2ImgParams(initImage = "")))
    p.run(params, v, ctx)
    assertEquals(ctx.generated.size, 2)
    assert(ctx.generated.forall(g => g.img2img.exists(_.initImage.nonEmpty) && g.seed == 3L && g.width == 32 && g.height == 24))
    assert(Files.exists(out.resolve("gif2gif.gif")))
    assertEquals(GifFrames.read(out.resolve("gif2gif.gif"), 10).size, 2)
    p.run(params, v, new FakeContext())
    assert(Files.exists(out.resolve("gif2gif-1.gif")))
  }
