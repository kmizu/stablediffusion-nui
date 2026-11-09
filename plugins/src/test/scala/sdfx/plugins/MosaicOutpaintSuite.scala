package sdfx.plugins

import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

class MosaicOutpaintSuite extends munit.FunSuite:
  test("each pass grows the canvas, masks the new strip plus the overlap, and only the last result is returned") {
    val src = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until 48; x <- 0 until 64 do src.setRGB(x, y, (x * 4) << 8)
    val f = Files.createTempFile("mo", ".png"); ImageIO.write(src, "png", f.toFile)
    val ctx = new FakeContext(); val p = new MosaicOutpaintPlugin
    val v = Values(Values.defaults(p.controls).map ++ Map("right" -> "32", "left" -> "0", "passes" -> "2", "overlap" -> "8", "mask_blur" -> "3"))
    val out = p.run(GenerationParams(prompt = "x", seed = 1, img2img = Some(Img2ImgParams(initImage = f.toString))), v, ctx)
    assertEquals(ctx.generated.size, 2)
    assertEquals(out.images.size, 1)
    assert(ctx.generated.forall(g => g.img2img.exists(i => i.mask.nonEmpty && i.maskBlur == 3 && i.inpaintingFill == 1)))
    assertEquals(ctx.generated.map(g => (g.width, g.height)), Seq((80, 48), (96, 48)))
  }

  test("needs an init image and a non-empty extension") {
    val p = new MosaicOutpaintPlugin
    intercept[IllegalArgumentException](p.run(GenerationParams(), Values(Values.defaults(p.controls).map), new FakeContext()))
    val f = Files.createTempFile("mo", ".png"); ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", f.toFile)
    intercept[IllegalArgumentException](p.run(GenerationParams(img2img = Some(Img2ImgParams(initImage = f.toString))),
      Values(Values.defaults(p.controls).map ++ Map("right" -> "0")), new FakeContext()))
  }
