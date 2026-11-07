package sdfx.plugins

import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.{Tab, Values}

class Img2ImgWorkflowSuite extends munit.FunSuite:
  private val plugin = new Img2ImgWorkflowPlugin
  private def values(kv: (String, String)*): Values = Values(Values.defaults(plugin.controls).map ++ kv.toMap)

  test("None passes the existing img2img request unchanged") {
    val ctx = new FakeContext
    val input = ctx.initImage(80, 64)
    val p = GenerationParams(prompt = "test", img2img = Some(Img2ImgParams(input)), batchSize = 2)
    assertEquals(plugin.tabs, Set(Tab.Img2Img))
    plugin.run(p, values("phase" -> "None"), ctx)
    assertEquals(ctx.generated, Seq(p))
  }

  test("phase applies its size, count and denoise while keeping input, mask and hooks") {
    val ctx = new FakeContext
    val input = ctx.initImage(64, 96)
    val ii = Img2ImgParams(input, mask = Some(input), denoisingStrength = 0.9)
    val p = GenerationParams(prompt = "test", seed = 42, img2img = Some(ii), hooks = Map("x" -> ujson.Obj("a" -> 1)))
    val out = plugin.run(p, values("phase" -> "Phase 1", "phase1_width" -> "64", "phase1_height" -> "96"), ctx)
    val sent = ctx.generated.head
    assertEquals((sent.width, sent.height, sent.batchSize, sent.nIter), (64, 96, 1, 4))
    assertEqualsDouble(sent.img2img.get.denoisingStrength, 0.5, 1e-9)
    assertEquals((sent.img2img.get.initImage, sent.img2img.get.mask, sent.prompt, sent.seed, sent.hooks),
      (input, Some(input), "test", 42L, p.hooks))
    assertEquals(sent.copy(width = p.width, height = p.height, batchSize = p.batchSize, nIter = p.nIter,
      img2img = p.img2img, extra = p.extra), p)
    assertEquals(out.images.size, 4)
  }

  test("orientation, 2:1 and explicit denoise are applied predictably") {
    val ctx = new FakeContext
    val input = ctx.initImage(96, 64)
    val p = GenerationParams(img2img = Some(Img2ImgParams(input)))
    plugin.run(p, values("phase" -> "Phase 2", "phase2_width" -> "96", "phase2_height" -> "128",
      "ratio" -> "2:1", "override_denoise" -> "true", "denoise" -> "0.3"), ctx)
    val sent = ctx.generated.head
    assertEquals((sent.width, sent.height), (128, 64))
    assertEqualsDouble(sent.img2img.get.denoisingStrength, 0.3, 1e-9)
  }

  test("phase 3 square and custom count retain the phase denoise") {
    val ctx = new FakeContext
    val input = ctx.initImage(64, 96)
    val p = GenerationParams(img2img = Some(Img2ImgParams(input)))
    plugin.run(p, values("phase" -> "Phase 3", "orientation" -> "Square", "phase3_width" -> "64",
      "phase3_height" -> "80", "phase3_count" -> "2"), ctx)
    val sent = ctx.generated.head
    assertEquals((sent.width, sent.height, sent.nIter), (80, 80, 2))
    assertEqualsDouble(sent.img2img.get.denoisingStrength, 0.2, 1e-9)
  }

  test("invalid dimensions, parameters and txt2img are rejected before generation") {
    val ctx = new FakeContext
    val input = ctx.initImage(64, 64)
    val p = GenerationParams(img2img = Some(Img2ImgParams(input)))
    intercept[IllegalArgumentException](plugin.run(p, values("phase" -> "Phase 1", "phase1_width" -> "65"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("phase" -> "Phase 1", "ratio" -> "bad"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("phase" -> "Phase 3", "phase3_count" -> "0"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("phase" -> "Phase 1", "phase1_width" -> "4104"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("phase" -> "Phase 1", "phase1_height" -> "72", "ratio" -> "2:1"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("phase" -> "Phase 1", "phase1_denoise" -> "NaN"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("phase" -> "Phase 1", "override_denoise" -> "true", "denoise" -> "1.01"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("phase" -> "Unknown"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("phase" -> "Phase 1", "orientation" -> "Unknown"), ctx))
    intercept[IllegalArgumentException](plugin.run(GenerationParams(), values("phase" -> "None"), ctx))
    assertEquals(ctx.generated.size, 0)
  }

  test("quick mask selects exact or nearby RGB and can invert without selecting transparent pixels") {
    val image = new java.awt.image.BufferedImage(4, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, 0xff102030); image.setRGB(1, 0, 0xff112030)
    image.setRGB(2, 0, 0xff132030); image.setRGB(3, 0, 0x00102030)
    val exact = Img2ImgWorkflowPlugin.colorMask(image, 0x102030, 1, false)
    assertEquals((0 until 4).map(i => exact.getRGB(i, 0) & 0xff), Seq(255, 0, 0, 0))
    val near = Img2ImgWorkflowPlugin.colorMask(image, 0x102030, 2, false)
    assertEquals((0 until 4).map(i => near.getRGB(i, 0) & 0xff), Seq(255, 255, 0, 0))
    val inverse = Img2ImgWorkflowPlugin.colorMask(image, 0x102030, 2, true)
    assertEquals((0 until 4).map(i => inverse.getRGB(i, 0) & 0xff), Seq(0, 0, 255, 0))
  }

  test("quick mask passes a temporary inpaint mask and removes it after generation") {
    var observed: Option[java.awt.image.BufferedImage] = None
    val ctx = new FakeContext:
      override def generate(p: GenerationParams): Seq[sdfx.core.plugins.api.OutputImage] =
        observed = Some(javax.imageio.ImageIO.read(java.nio.file.Path.of(p.img2img.get.mask.get).toFile))
        super.generate(p)
    val input = ctx.initImage(64, 64)
    val p = GenerationParams(img2img = Some(Img2ImgParams(input)))
    plugin.run(p, values("phase" -> "None", "quick_mask" -> "Black", "mask_threshold" -> "1"), ctx)
    assert(observed.nonEmpty)
    assertEquals((observed.get.getWidth, observed.get.getHeight), (64, 64))
    assert(!java.nio.file.Files.exists(java.nio.file.Path.of(ctx.generated.head.img2img.get.mask.get)))
  }

  test("quick mask validates inputs and refuses protected-session plaintext") {
    val ctx = new FakeContext
    val input = ctx.initImage(64, 64)
    val p = GenerationParams(img2img = Some(Img2ImgParams(input)))
    intercept[IllegalArgumentException](plugin.run(p, values("quick_mask" -> "Custom", "mask_color" -> "red"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("quick_mask" -> "White", "mask_threshold" -> "0"), ctx))
    intercept[IllegalArgumentException](plugin.run(p, values("quick_mask" -> "White", "mask_invert" -> "perhaps"), ctx))
    val protectedCtx = new FakeContext:
      override def imageScramblingEnabled = true
    intercept[IllegalArgumentException](plugin.run(p, values("quick_mask" -> "Black"), protectedCtx))
    assertEquals(ctx.generated.size, 0)
    assertEquals(protectedCtx.generated.size, 0)
  }

  test("quick mask temporary file is removed if generation fails") {
    var temp: Option[java.nio.file.Path] = None
    val ctx = new FakeContext:
      override def generate(p: GenerationParams): Seq[sdfx.core.plugins.api.OutputImage] =
        temp = Some(java.nio.file.Path.of(p.img2img.get.mask.get))
        assert(java.nio.file.Files.exists(temp.get))
        throw new RuntimeException("generation failure")
    val input = ctx.initImage(64, 64)
    val p = GenerationParams(img2img = Some(Img2ImgParams(input)))
    intercept[RuntimeException](plugin.run(p, values("quick_mask" -> "Black"), ctx))
    assert(temp.nonEmpty && !java.nio.file.Files.exists(temp.get))
  }

  test("default quick mask inversion toggles only an existing inpaint mask") {
    val ctx = new FakeContext
    val input = ctx.initImage(64, 64)
    val p = GenerationParams(img2img = Some(Img2ImgParams(input, mask = Some(input), inpaintingMaskInvert = true)))
    plugin.run(p, values("quick_mask" -> "Default", "mask_invert" -> "true"), ctx)
    assertEquals(ctx.generated.head.img2img.get.mask, Some(input))
    assertEquals(ctx.generated.head.img2img.get.inpaintingMaskInvert, false)
    val ctx2 = new FakeContext
    val noMask = p.copy(img2img = Some(Img2ImgParams(input)))
    plugin.run(noMask, values("quick_mask" -> "Default", "mask_invert" -> "true"), ctx2)
    assertEquals(ctx2.generated.head, noMask)
  }

  test("protected session writes the generated colour mask through protected temp image access") {
    var observed: Option[java.nio.file.Path] = None
    val ctx = new FakeContext:
      override def imageScramblingEnabled = true
      override def writeTempImage(image: java.awt.image.BufferedImage, path: java.nio.file.Path): Unit =
        sdfx.core.png.ScrambledPng.write(image, path, Nil, Some("secret"))
      override def generate(p: GenerationParams): Seq[sdfx.core.plugins.api.OutputImage] =
        val path = java.nio.file.Path.of(p.img2img.get.mask.get)
        assert(sdfx.core.png.PngMetadata.readTexts(path).contains("Encrypt"))
        assertEquals(sdfx.core.png.ScrambledPng.read(path, Some("secret")).image.getWidth, 64)
        observed = Some(path)
        super.generate(p)
    val input = ctx.initImage(64, 64)
    val p = GenerationParams(img2img = Some(Img2ImgParams(input)))
    plugin.run(p, values("quick_mask" -> "White"), ctx)
    assert(observed.nonEmpty && !java.nio.file.Files.exists(observed.get))
  }

  test("quick mask and generated init image use the same snapshot when source is replaced") {
    var source: java.nio.file.Path = null
    var replaced = false
    var observedPixel = 0
    var observedMaskPixel = 0
    val ctx = new FakeContext:
      override def writeTempImage(image: java.awt.image.BufferedImage, path: java.nio.file.Path): Unit =
        super.writeTempImage(image, path)
        if !replaced then
          replaced = true
          val replacement = solid(64, 64, 0x224488)
          javax.imageio.ImageIO.write(replacement, "png", source.toFile)
      override def generate(p: GenerationParams): Seq[sdfx.core.plugins.api.OutputImage] =
        observedPixel = javax.imageio.ImageIO.read(java.nio.file.Path.of(p.img2img.get.initImage).toFile).getRGB(0, 0) & 0xffffff
        observedMaskPixel = javax.imageio.ImageIO.read(java.nio.file.Path.of(p.img2img.get.mask.get).toFile).getRGB(0, 0) & 0xffffff
        super.generate(p)
    source = java.nio.file.Path.of(ctx.initImage(64, 64))
    javax.imageio.ImageIO.write(ctx.solid(64, 64, 0xffffff), "png", source.toFile)
    val p = GenerationParams(img2img = Some(Img2ImgParams(source.toString)))
    plugin.run(p, values("quick_mask" -> "White"), ctx)
    assert(replaced)
    assertEquals(observedPixel, 0xffffff)
    assertEquals(observedMaskPixel, 0xffffff)
  }

  test("protected source conversion preserves grayscale pixels and rejects high bit depth") {
    val gray = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
    gray.getRaster.setSample(0, 0, 0, 20); gray.getRaster.setSample(1, 0, 0, 200)
    val converted = Img2ImgWorkflowPlugin.protectedSource(gray)
    assertEquals((0 until 2).map(x => converted.getRGB(x, 0)), (0 until 2).map(x => gray.getRGB(x, 0)))
    val high = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_USHORT_GRAY)
    intercept[IllegalArgumentException](Img2ImgWorkflowPlugin.protectedSource(high))
  }

  test("quick mask rejects formats with unverified worker colour or orientation semantics") {
    val input = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val rgb = new java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(input, "png", rgb)
    Img2ImgWorkflowPlugin.requireSafePng(rgb.toByteArray)
    val alpha = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val rgba = new java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(alpha, "png", rgba)
    intercept[IllegalArgumentException](Img2ImgWorkflowPlugin.requireSafePng(rgba.toByteArray))
    val jpg = new java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(input, "jpeg", jpg)
    intercept[IllegalArgumentException](Img2ImgWorkflowPlugin.requireSafePng(jpg.toByteArray))
    val bytes = rgb.toByteArray
    val exif = Array[Byte](0, 0, 0, 1, 101, 88, 73, 102, 1, 0, 0, 0, 0)
    val withExif = bytes.take(33) ++ exif ++ bytes.drop(33)
    intercept[IllegalArgumentException](Img2ImgWorkflowPlugin.requireSafePng(withExif))
    val duplicateHeader = bytes.take(33) ++ bytes.slice(8, 33) ++ bytes.drop(33)
    intercept[IllegalArgumentException](Img2ImgWorkflowPlugin.requireSafePng(duplicateHeader))
    val badCrc = bytes.clone()
    badCrc(badCrc.length - 1) = (badCrc.last ^ 1).toByte
    intercept[IllegalArgumentException](Img2ImgWorkflowPlugin.requireSafePng(badCrc))
  }

  test("return mask emits a labelled image after the generated image") {
    val ctx = new FakeContext
    val source = java.nio.file.Path.of(ctx.initImage(64, 64))
    val image = ctx.solid(64, 64, 0xffffff)
    image.setRGB(0, 0, 0x223344)
    javax.imageio.ImageIO.write(image, "png", source.toFile)
    val p = GenerationParams(img2img = Some(Img2ImgParams(source.toString)))
    val result = plugin.run(p, values("quick_mask" -> "White", "return_mask" -> "true"), ctx)
    assertEquals(result.images.size, 2)
    assertEquals(ctx.saved.size, 1)
    assertEquals(ctx.saved.head._2.getRGB(0, 0) & 0xffffff, 0)
    assertEquals(ctx.saved.head._2.getRGB(1, 0) & 0xffffff, 0xffffff)
    assertEquals(ctx.saved.head._3.extra.get("Img2Img Workflow output"), Some("mask"))
    assert(ctx.saved.head._4.contains("Img2Img Workflow output"))
  }

  test("post-generation interruption keeps partial output but suppresses the returned mask and cleans staging") {
    var cancelled = false
    var staged = Seq.empty[java.nio.file.Path]
    val ctx = new FakeContext:
      override def isInterrupted: Boolean = cancelled || super.isInterrupted
      override def generateWithInputSnapshot(p: GenerationParams, original: String): Seq[sdfx.core.plugins.api.OutputImage] =
        staged = Seq(p.img2img.get.initImage, p.img2img.get.mask.get).map(java.nio.file.Path.of(_))
        val outputs = super.generateWithInputSnapshot(p, original)
        cancelled = true
        outputs
    val source = java.nio.file.Path.of(ctx.initImage(64, 64))
    val p = GenerationParams(img2img = Some(Img2ImgParams(source.toString)))
    val result = plugin.run(p, values("quick_mask" -> "White", "return_mask" -> "true"), ctx)
    assertEquals(result.images.size, 1)
    assertEquals(ctx.saved.size, 0)
    assertEquals(staged.size, 2)
    staged.foreach(path => assert(!java.nio.file.Files.exists(path), path.toString))
  }

  test("returning an existing mask is rejected before generation until it is supported") {
    val ctx = new FakeContext
    val source = ctx.initImage(64, 64)
    val p = GenerationParams(img2img = Some(Img2ImgParams(source, mask = Some(source))))
    intercept[IllegalArgumentException](plugin.run(p, values("quick_mask" -> "Default", "return_mask" -> "true"), ctx))
    assertEquals(ctx.generated.size, 0)
  }
