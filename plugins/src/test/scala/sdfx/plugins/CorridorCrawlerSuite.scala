package sdfx.plugins

import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.*

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/** Corridor Crawler Outpainting: window geometry, prefab, mask, chaining, outputs, animation, GIF and rejections. */
class CorridorCrawlerSuite extends munit.FunSuite:
  import CorridorCrawlerPlugin.*

  private val plugin = new CorridorCrawlerPlugin
  private def values(kv: (String, String)*) = Values(Values.defaults(plugin.controls).map ++ kv)
  private val base = GenerationParams(prompt = "a hallway", seed = 7, width = 512, height = 512)
  private def img2img(ctx: FakeContext, p: GenerationParams = base, i: Img2ImgParams => Img2ImgParams = identity, rgb: Int = 0x2060c0) =
    p.copy(img2img = Some(i(Img2ImgParams(ctx.initImage(p.width, p.height, rgb), denoisingStrength = 0.9))))

  private def px(img: BufferedImage, x: Int, y: Int): Int = img.getRGB(x, y) & 0xffffff

  test("default window is the source thumbnail box on 512x512") {
    assertEquals(Settings.parse(values()).window(512, 512), Rect(127, 81, 254, 254))
  }

  test("versioned corridor settings round-trip without embedding image paths") {
    val v = values("steps" -> "5", "prefab" -> CustomPrefab, "prefab_image" -> "C:/private/prefab.png",
      "window_width" -> "40.1", "window_height" -> "30.2", "window_x" -> "17.3", "window_y" -> "13.4",
      "placement" -> PreserveAspect, "mask_shrink" -> "4", "make_grid" -> "false", "animation" -> "true",
      "frames_per_step" -> "8", "frame_ms" -> "70", "animation_size" -> "320")
    val serialized = replayValue(Settings.parse(v))
    assert(!serialized.contains("private"))
    val result = replay(Map(ReplayKey -> serialized)).get
    assertEquals(result.str("steps"), "5")
    assertEquals(result.str("prefab"), CustomPrefab)
    assertEquals(result.str("prefab_image"), "")
    assertEquals(result.str("window_width"), "40.1")
    assertEquals(result.str("placement"), PreserveAspect)
    assertEquals(result.str("animation"), "true")
    assertEquals(result.str("frame_ms"), "70")
    assertEquals(result.str("animation_size"), "320")
  }

  test("replay rejects missing, malformed, unknown and unsafe corridor configuration") {
    assertEquals(replay(Map.empty), None)
    assertEquals(replay(Map(ReplayKey -> "{")), None)
    assertEquals(replay(Map(ReplayKey -> ("x" * 4097))), None)
    val serialized = replayValue(Settings.parse(values()))
    val bad = ujson.read(serialized).obj
    bad("placement") = ujson.Str("invalid")
    assertEquals(replay(Map(ReplayKey -> ujson.Obj.from(bad).render())), None)
    val unknown = ujson.read(serialized).obj
    unknown("prefab_image") = ujson.Str("C:/private/injected.png")
    assertEquals(replay(Map(ReplayKey -> ujson.Obj.from(unknown).render())), None)
    val invalidVersion = ujson.read(serialized).obj
    invalidVersion("version") = ujson.Num(2)
    assertEquals(replay(Map(ReplayKey -> ujson.Obj.from(invalidVersion).render())), None)
  }

  test("the window scales with the canvas and must stay inside it") {
    assertEquals(Settings.parse(values()).window(1024, 1024), Rect(254, 162, 508, 508))
    intercept[IllegalArgumentException](Settings.parse(values("window_x" -> "60", "window_width" -> "50")).window(512, 512))
    intercept[IllegalArgumentException](Settings.parse(values("window_width" -> "10")).window(64, 64))
  }

  test("mask is white around a black window, and shrinks inward") {
    val win = Rect(127, 81, 254, 254)
    val m = mask(512, 512, win)
    assertEquals(px(m, 0, 0), 0xffffff)
    assertEquals(px(m, 126, 200), 0xffffff)
    assertEquals(px(m, 127, 81), 0)
    assertEquals(px(m, 380, 334), 0)
    assertEquals(px(m, 381, 200), 0xffffff)
    val s = Settings.parse(values("mask_shrink" -> "10"))
    assertEquals(s.protectedRect(win), Rect(137, 91, 234, 234))
    intercept[IllegalArgumentException](Settings.parse(values("mask_shrink" -> "64", "window_width" -> "10", "window_height" -> "10")).protectedRect(Rect(0, 0, 51, 51)))
  }

  test("composite pastes the previous picture into the window only") {
    val prefab = fit(ctxSolid(0x00ff00), 512, 512)
    val win = Rect(100, 50, 200, 200)
    val out = composite(prefab, ctxSolid(0xff0000), win)
    assertEquals(px(out, 99, 100), 0x00ff00)
    assertEquals(px(out, 100, 50), 0xff0000)
    assertEquals(px(out, 299, 249), 0xff0000)
    assertEquals(px(out, 300, 100), 0x00ff00)
  }

  private def ctxSolid(rgb: Int): BufferedImage =
    val img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until 512; x <- 0 until 512 do img.setRGB(x, y, rgb)
    img

  test("aspect-preserving placement leaves and repaints unused window space") {
    val ctx = new FakeContext(echoInit = true)
    val green = ctx.dir.resolve("prefab-green.png")
    ImageIO.write(ctxSolid(0x00ff00), "png", green.toFile)
    plugin.run(img2img(ctx), values("steps" -> "1", "prefab" -> CustomPrefab, "prefab_image" -> green.toString,
      "window_width" -> "40", "window_height" -> "20", "placement" -> "Preserve aspect"), ctx)
    val (init, maybeMask) = ctx.inputs.head
    val m = maybeMask.get
    assertEquals(px(init, 140, 90), 0x2060c0)
    assertEquals(px(init, 260, 90), 0x00ff00)
    assertEquals(px(m, 140, 90), 0)
    assertEquals(px(m, 260, 90), 0xffffff)
  }

  test("aspect placement never enlarges, records actual bounds, and updates the mask for the next image") {
    val ctx = new FakeContext(echoInit = true)
    val input = ctx.dir.resolve("small.png")
    val small = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB)
    val g = small.createGraphics(); g.setColor(java.awt.Color.RED); g.fillRect(0, 0, 80, 40); g.dispose()
    ImageIO.write(small, "png", input.toFile)
    val p = base.copy(img2img = Some(Img2ImgParams(input.toString, denoisingStrength = 0.9)))
    plugin.run(p, values("steps" -> "2", "placement" -> "Preserve aspect", "make_grid" -> "false"), ctx)
    assertEquals(ctx.generated.head.extra("Corridor Crawler image window"), "127,81,80,40")
    assertEquals(ctx.generated(1).extra("Corridor Crawler image window"), "127,81,254,254")
    assertEquals(px(ctx.inputs.head._2.get, 205, 100), 0)
    assertEquals(px(ctx.inputs.head._2.get, 210, 100), 0xffffff)
    assertEquals(px(ctx.inputs(1)._2.get, 210, 100), 0)
    assertEquals(ctx.generated.head.extra("Corridor Crawler placement"), "Preserve aspect")
  }

  test("aspect mode rejects a shrink that removes the first image before generation") {
    val ctx = new FakeContext(echoInit = true)
    val input = ctx.dir.resolve("small-for-mask.png")
    ImageIO.write(new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "png", input.toFile)
    val p = base.copy(img2img = Some(Img2ImgParams(input.toString, denoisingStrength = 0.9)))
    intercept[IllegalArgumentException] {
      plugin.run(p, values("steps" -> "2", "placement" -> "Preserve aspect", "mask_shrink" -> "20"), ctx)
    }
    assertEquals(ctx.generated.size, 0)
  }

  test("aspect zoom uses fixed frames without stretching a wide older image") {
    val newer = ctxSolid(0x00ff00)
    val older = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB)
    val g = older.createGraphics(); g.setColor(java.awt.Color.RED); g.fillRect(0, 0, 80, 40); g.dispose()
    val frames = aspectWalk(Seq(older, newer), Seq(Rect(100, 100, 80, 40)), 3, 128)
    assertEquals(frames.size, 4)
    assert(frames.forall(f => f.getWidth == 128 && f.getHeight == 128))
    assertEquals(px(frames.last, 64, 10), 0)
    assertEquals(px(frames.last, 64, 64), 0xff0000)
    assertEquals(px(frames.last, 64, 120), 0)
  }

  test("built-in prefab is a corridor: distinct surfaces around the window") {
    val win = Rect(127, 81, 254, 254)
    val c = brickCorridor(512, 512, win)
    def mean(x: Int, y: Int): Int = // average of a small patch, so a mortar line cannot decide the surface colour
      val ps = for dy <- 0 until 6; dx <- 0 until 6 yield px(c, x + dx, y + dy)
      ps.map(p => (p >> 16 & 0xff) * 3 + (p >> 8 & 0xff) * 2 + (p & 0xff)).sum / ps.size
    val surfaces = Seq(mean(240, 10), mean(240, 480), mean(8, 240), mean(498, 240))
    assert(surfaces.distinct.size >= 3, surfaces.toString) // ceiling, floor and the walls differ
    assertEquals((c.getWidth, c.getHeight), (512, 512))
  }

  test("a custom prefab is stretched to the canvas and flattened") {
    val small = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
    val fitted = fit(small, 200, 100)
    assertEquals((fitted.getWidth, fitted.getHeight), (200, 100))
    assertEquals(px(fitted, 5, 5), 0x808080) // transparent pixels become mid grey
  }

  test("steps chain: each step inpaints a composite whose window holds the previous output") {
    val ctx = new FakeContext(echoInit = true)
    val out = plugin.run(img2img(ctx), values("steps" -> "3", "make_grid" -> "false"), ctx)
    assertEquals(out.images.size, 3)
    assertEquals(ctx.generated.map(_.seed).distinct, Seq(7L))
    val win = Rect(127, 81, 254, 254)
    ctx.inputs.zipWithIndex.foreach { case ((init, mk), i) =>
      assertEquals((init.getWidth, init.getHeight), (512, 512))
      assertEquals(px(mk.get, 0, 0), 0xffffff)
      assertEquals(px(mk.get, 200, 200), 0)
      if i == 0 then assertEqualsDouble(px(init, 250, 200).toDouble, 0x2060c0.toDouble, 1.0, "step 1 shows the start image")
    }
    assertEquals(ctx.generated.map(_.img2img.get.resizeMode).distinct, Seq(0))
    assertEquals(ctx.generated.map(_.extra("Corridor Crawler step")), Seq("1/3", "2/3", "3/3"))
    assertEquals(ctx.generated.head.extra("Corridor Crawler window"), "127,81,254,254")
    assertEquals(ctx.generated.head.extra("Corridor Crawler prefab"), "Brick corridor")
  }

  test("the second composite carries the first output shrunk into its window") {
    val ctx = new FakeContext(echoInit = true)
    val green = ctx.dir.resolve("green.png")
    ImageIO.write(ctxSolid(0x00ff00), "png", green.toFile)
    plugin.run(img2img(ctx), values("steps" -> "2", "prefab" -> CustomPrefab, "prefab_image" -> green.toString), ctx)
    val second = ctx.inputs(1)._1
    def near(a: Int, b: Int) = (0 to 2).forall(k => math.abs(((a >> (8 * k)) & 0xff) - ((b >> (8 * k)) & 0xff)) <= 4)
    assert(near(px(second, 10, 10), 0x00ff00), "ring is the prefab")
    assert(near(px(second, 127 + 3, 81 + 3), 0x00ff00), "window corner holds the first output's prefab ring")
    assert(near(px(second, 254, 208), 0x2060c0), "window centre holds the start image seen through the first window")
  }

  test("the user's denoising strength, fill and blur are kept; the temp inputs are removed") {
    val ctx = new FakeContext
    plugin.run(img2img(ctx, i = _.copy(denoisingStrength = 0.77, inpaintingFill = 2, maskBlur = 9)), values("steps" -> "2"), ctx)
    val used = ctx.generated.map(_.img2img.get)
    assertEquals(used.map(_.denoisingStrength).distinct, Seq(0.77))
    assertEquals(used.map(_.inpaintingFill).distinct, Seq(2))
    assertEquals(used.map(_.maskBlur).distinct, Seq(9))
    used.foreach { u =>
      assert(!Files.exists(java.nio.file.Paths.get(u.initImage)), "composite deleted")
      assert(!Files.exists(java.nio.file.Paths.get(u.mask.get)), "mask deleted")
    }
  }

  test("a random seed is fixed once for the whole walk") {
    val ctx = new FakeContext
    plugin.run(img2img(ctx, base.copy(seed = -1)), values("steps" -> "3"), ctx)
    assertEquals(ctx.generated.map(_.seed).distinct.size, 1)
    assert(ctx.generated.head.seed >= 0)
  }

  test("grid holds the steps; none for a single step or when switched off") {
    val ctx = new FakeContext
    val three = plugin.run(img2img(ctx), values("steps" -> "3"), ctx)
    assertEquals(three.grids.size, 1)
    assertEquals(plugin.run(img2img(ctx), values("steps" -> "1"), ctx).grids.size, 0)
    assertEquals(plugin.run(img2img(ctx), values("steps" -> "3", "make_grid" -> "false"), ctx).grids.size, 0)
  }

  test("the shrunk kept area reaches the mask and the infotext") {
    val ctx = new FakeContext
    plugin.run(img2img(ctx), values("steps" -> "1", "mask_shrink" -> "12"), ctx)
    val mk = ctx.inputs.head._2.get
    assertEquals(px(mk, 127 + 11, 200), 0xffffff)
    assertEquals(px(mk, 127 + 12, 200), 0)
    assertEquals(ctx.generated.head.extra("Corridor Crawler mask shrink"), "12")
  }

  test("a custom prefab image is used and named; a missing one is rejected") {
    val ctx = new FakeContext
    val file = ctx.dir.resolve("my prefab.png")
    ImageIO.write(ctxSolid(0x00ff00), "png", file.toFile)
    plugin.run(img2img(ctx), values("steps" -> "1", "prefab" -> CustomPrefab, "prefab_image" -> file.toString), ctx)
    assertEquals(px(ctx.inputs.head._1, 10, 10), 0x00ff00)
    assertEquals(ctx.generated.head.extra("Corridor Crawler prefab"), "my prefab.png")
    intercept[IllegalArgumentException](plugin.run(img2img(ctx), values("prefab" -> CustomPrefab), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx), values("prefab" -> CustomPrefab, "prefab_image" -> "/no/such.png"), ctx))
  }

  test("rejections: txt2img, batches, an own mask, only-masked, inversion, bad values") {
    val ctx = new FakeContext
    intercept[IllegalArgumentException](plugin.run(base, values(), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx, base.copy(batchSize = 2)), values(), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx, base.copy(nIter = 2)), values(), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx, i = _.copy(mask = Some("/x.png"))), values(), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx, i = _.copy(inpaintFullRes = true)), values(), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx, i = _.copy(inpaintingMaskInvert = true)), values(), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx), values("steps" -> "0"), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx), values("steps" -> "31"), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx), values("steps" -> "2.5"), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx), values("prefab" -> "Mystery"), ctx))
    intercept[IllegalArgumentException](plugin.run(img2img(ctx), values("window_x" -> "abc"), ctx))
    assertEquals(ctx.generated.size, 0)
  }

  test("interrupting keeps the finished steps and writes no animation") {
    val ctx = new FakeContext(interruptAfter = 2)
    val out = plugin.run(img2img(ctx), values("steps" -> "4", "animation" -> "true", "frames_per_step" -> "2"), ctx)
    assertEquals(out.images.size, 2)
    assert(ctx.logs.exists(_.contains("interrupted after 2 of 4")))
    assert(!Files.list(ctx.dir.resolve("img2img")).toArray.exists(_.toString.endsWith(".gif")))
  }

  test("scrambled sessions refuse the animation before any generation") {
    val ctx = new FakeContext:
      override def plaintextVideoAllowed = false
    intercept[IllegalStateException](plugin.run(img2img(ctx), values("animation" -> "true"), ctx))
    assertEquals(ctx.generated.size, 0)
  }

  // ----------------------------------------------------------------------------------- animation

  test("zoom frames start at the newer picture and approach the older one") {
    val newer = ctxSolid(0xff0000)
    val older = ctxSolid(0x0000ff)
    val win = Rect(128, 128, 256, 256)
    val frames = zoomFrames(newer, older, win, 4, 256)
    assertEquals(frames.size, 4)
    assertEquals(frames.map(f => (f.getWidth, f.getHeight)).distinct, Seq((256, 256)))
    assertEquals(px(frames.head, 5, 5), 0xff0000) // whole canvas: the newer picture around the edge
    assertEquals(px(frames.head, 128, 128), 0x0000ff) // centre is the older picture
    // later frames contain more of the older picture
    def blue(f: BufferedImage) = (for y <- 0 until 256 by 4; x <- 0 until 256 by 4 yield if (f.getRGB(x, y) & 0xff) > 128 then 1 else 0).sum
    assert(blue(frames(3)) > blue(frames(1)))
    assert(blue(frames(1)) > blue(frames(0)))
    assertEquals(zoomFrames(newer, older, win, 0, 256), Nil)
  }

  test("the walk has frames x transitions + 1 frames, newest first, ending on the start image") {
    val steps = Seq(0x0000ff, 0x00ff00, 0xff0000).map(ctxSolid) // oldest first
    val win = Rect(128, 128, 256, 256)
    val frames = walk(steps, win, 5, 128)
    assertEquals(frames.size, 2 * 5 + 1)
    assertEquals(px(frames.head, 2, 2), 0xff0000)
    assertEquals(px(frames.last, 64, 64), 0x0000ff)
    val slide = walk(steps, win, 0, 128)
    assertEquals(slide.map(px(_, 64, 64)), Seq(0xff0000, 0x00ff00, 0x0000ff))
  }

  test("animation size keeps the aspect ratio and never enlarges") {
    assertEquals(fitSize(512, 512, 256), (256, 256))
    assertEquals(fitSize(832, 1216, 256), (175, 256))
    assertEquals(fitSize(100, 50, 256), (100, 50))
  }

  test("the GIF is a looping multi-frame file with the requested delay") {
    val dir = Files.createTempDirectory("cc-gif")
    val frames = Seq(0xff0000, 0x00ff00, 0x0000ff).map(c => scaled(ctxSolid(c), 64))
    val gif = dir.resolve("a.gif")
    writeGif(frames, 70, gif)
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    val in = ImageIO.createImageInputStream(gif.toFile)
    reader.setInput(in)
    assertEquals(reader.getNumImages(true), 3)
    val meta = reader.getImageMetadata(1)
    val root = meta.getAsTree(meta.getNativeMetadataFormatName).asInstanceOf[javax.imageio.metadata.IIOMetadataNode]
    assertEquals(root.getElementsByTagName("GraphicControlExtension").item(0).getAttributes.getNamedItem("delayTime").getNodeValue, "7")
    val first = reader.read(0)
    assertEquals((first.getWidth, first.getHeight), (64, 64))
    assert((first.getRGB(32, 32) >> 16 & 0xff) > 200, "first frame is red")
    assert((reader.read(2).getRGB(32, 32) & 0xff) > 200, "last frame is blue")
    val bytes = Files.readAllBytes(gif)
    assert(new String(bytes, "ISO-8859-1").contains("NETSCAPE2.0"), "loop extension")
    in.close(); reader.dispose()
  }

  test("GIF refuses existing files and removes its own incomplete file after an error") {
    val dir = Files.createTempDirectory("corridor-gif-safe-")
    val existing = dir.resolve("existing.gif")
    val original = Array[Byte](1, 2, 3, 4)
    Files.write(existing, original)
    intercept[java.nio.file.FileAlreadyExistsException](writeGif(Seq(ctxSolid(0xff0000)), 50, existing))
    assert(java.util.Arrays.equals(Files.readAllBytes(existing), original))
    val partial = dir.resolve("partial.gif")
    intercept[Exception](writeGif(Seq(ctxSolid(0xff0000), null), 50, partial))
    assert(!Files.exists(partial))
    val entries = Files.list(dir)
    try assert(!entries.anyMatch(p => p.getFileName.toString.startsWith(".corridor-gif-"))) finally entries.close()
  }

  test("GIF publication falls back to exclusive copy when hard links are unavailable") {
    val dir = Files.createTempDirectory("corridor-gif-fallback-")
    val temp = dir.resolve("finished.tmp")
    val target = dir.resolve("result.gif")
    val data = Array[Byte](1, 2, 3, 4)
    Files.write(temp, data)
    val noLink: (java.nio.file.Path, java.nio.file.Path) => Unit = (_, _) => throw new UnsupportedOperationException("no hard links")
    publishGif(temp, target, noLink)
    assert(java.util.Arrays.equals(Files.readAllBytes(target), data))
    intercept[java.nio.file.FileAlreadyExistsException](publishGif(temp, target, noLink))
    assert(java.util.Arrays.equals(Files.readAllBytes(target), data))
    val partial = dir.resolve("partial.gif")
    val missingSource: (java.nio.file.Path, java.nio.file.Path) => Unit = (_, source) => {
      Files.delete(source)
      throw new UnsupportedOperationException("no hard links")
    }
    val error = intercept[java.io.IOException](publishGif(temp, partial, missingSource))
    assert(error.getMessage.contains("incomplete file may remain"))
    assert(Files.exists(partial))
  }

  test("GIF fallback preserves a read failure when closing the output also fails") {
    val dir = Files.createTempDirectory("corridor-gif-errors-")
    val missing = dir.resolve("missing.tmp")
    val target = dir.resolve("partial.gif")
    val noLink: (java.nio.file.Path, java.nio.file.Path) => Unit = (_, _) => throw new UnsupportedOperationException("no hard links")
    val failingClose: java.nio.file.Path => java.io.OutputStream = p =>
      val underlying = Files.newOutputStream(p, java.nio.file.StandardOpenOption.CREATE_NEW)
      new java.io.FilterOutputStream(underlying):
        override def close(): Unit =
          super.close()
          throw new java.io.IOException("close failed")
    val error = intercept[java.io.IOException](publishGif(missing, target, noLink, failingClose))
    assert(error.getMessage.contains("incomplete file may remain"))
    assert(error.getCause.isInstanceOf[java.nio.file.NoSuchFileException])
    assert(error.getCause.getSuppressed.exists(_.getMessage.contains("close failed")))
  }

  test("GIF collision leaves generated images and removes intermediate inputs") {
    val ctx = new FakeContext(echoInit = true):
      override def generate(p: GenerationParams): Seq[OutputImage] =
        val results = super.generate(p)
        val path = results.last.path
        Files.write(path.resolveSibling(path.getFileName.toString.stripSuffix(".png") + "-corridor.gif"), Array[Byte](1, 2, 3))
        results
    val p = img2img(ctx)
    intercept[java.nio.file.FileAlreadyExistsException] {
      plugin.run(p, values("steps" -> "1", "animation" -> "true"), ctx)
    }
    assertEquals(ctx.outputPaths.size, 1)
    assert(Files.exists(ctx.outputPaths.head))
    val image = ctx.generated.head.img2img.get
    assert(!Files.exists(java.nio.file.Path.of(image.initImage)))
    assert(!Files.exists(java.nio.file.Path.of(image.mask.get)))
    val gif = ctx.outputPaths.head.resolveSibling(ctx.outputPaths.head.getFileName.toString.stripSuffix(".png") + "-corridor.gif")
    assert(java.util.Arrays.equals(Files.readAllBytes(gif), Array[Byte](1, 2, 3)))
  }

  test("a full run with animation writes the GIF beside the last step") {
    val ctx = new FakeContext(echoInit = true)
    val out = plugin.run(img2img(ctx), values("steps" -> "2", "animation" -> "true", "frames_per_step" -> "3",
      "frame_ms" -> "40", "animation_size" -> "128"), ctx)
    val last = out.images.last.path
    val gif = last.resolveSibling(last.getFileName.toString.stripSuffix(".png") + "-corridor.gif")
    assert(Files.isRegularFile(gif))
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    val in = ImageIO.createImageInputStream(gif.toFile)
    reader.setInput(in)
    assertEquals(reader.getNumImages(true), 2 * 3 + 1)
    assertEquals(reader.read(0).getWidth, 128)
    in.close(); reader.dispose()
  }

  test("registered as an img2img script with the ledger id") {
    assertEquals(plugin.id, "corridor_crawler")
    assertEquals(plugin.kind, PluginKind.Script)
    assertEquals(plugin.tabs, Set(Tab.Img2Img))
    assert(StandardPlugins.scripts(() => Nil).exists(_.id == "corridor_crawler"))
  }
