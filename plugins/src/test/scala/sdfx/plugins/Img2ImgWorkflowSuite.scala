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
    intercept[IllegalArgumentException](plugin.run(GenerationParams(), values("phase" -> "None"), ctx))
    assertEquals(ctx.generated.size, 0)
  }
