package sdfx.plugins

import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.*

import java.nio.file.{Files, Paths}
import javax.imageio.ImageIO
import scala.collection.mutable.ArrayBuffer

/** Loopback Scaler (Elldreth/loopback_scaler@184a53e) schedule, batches, outputs, interruption and rejections. */
class LoopbackScalerSuite extends munit.FunSuite:
  import LoopbackScalerPlugin.*

  private val plugin = new LoopbackScalerPlugin
  private def values(kv: (String, String)*) = Values(Values.defaults(plugin.controls).map ++ kv)
  private val base = GenerationParams(prompt = "a castle", negativePrompt = "blurry", seed = 100, width = 512, height = 768)
  private def img2img(ctx: FakeContext, p: GenerationParams = base, denoise: Double = 0.35) =
    p.copy(img2img = Some(Img2ImgParams(ctx.initImage(p.width, p.height), denoisingStrength = denoise, resizeMode = 1)))

  // ---------------------------------------------------------------------------------------- literal restatement
  /** The source arithmetic written out again, statement by statement (`literalWidthBranch` keeps the source's
    * `get_height_from_ratio(calc_height, ...)`; otherwise the width-driven branch uses `calc_width`).
    */
  private def restated(w0: Int, h0: Int, loops: Int, maxW: Int, maxH: Int, useScale: Boolean, scale: Double, opt: String,
      literalWidthBranch: Boolean = false): Seq[(Int, Int)] =
    def rnm(width: Int, height: Int): (Int, Int) =
      val ar = width.toDouble / height
      if width < height then
        val nw = math.ceil(width / 8.0).toInt * 8
        val nh = math.ceil(math.rint(nw / ar) / 8.0).toInt * 8
        (nw, nh)
      else
        val nh = math.ceil(height / 8.0).toInt * 8
        val nw = math.ceil(math.rint(nh * ar) / 8.0).toInt * 8
        (nw, nh)
    val baseRatio = h0.toDouble / w0
    val finalH = if useScale then math.floor(h0 * scale).toInt else maxH
    val finalW = if useScale then math.floor(w0 * scale).toInt else maxW
    val useHeight = baseRatio >= finalH.toDouble / finalW
    for i <- 0 until loops yield
      val e = ease(opt, i.toDouble / loops)
      val last = i == loops - 1
      val calcH = if last then finalH else ((finalH - h0) * e + h0).toInt
      val calcW = if last then finalW else ((finalW - w0) * e + w0).toInt
      if useHeight then rnm(math.floor(calcH / baseRatio).toInt, calcH)
      else rnm(calcW, math.floor((if literalWidthBranch then calcH else calcW) * baseRatio).toInt)

  test("every easing matches the source formulas and starts at zero") {
    val p = 0.3
    val expected = Map(
      "Linear" -> p, "Ease In: Sine" -> (1 - math.cos(p * math.Pi / 2)), "Ease In: Cubic" -> p * p * p,
      "Ease In: Quint" -> p * p * p * p, "Ease In: Circ" -> (1 - math.sqrt(1 - p * p)),
      "Ease Out: Sine" -> math.sin(p * math.Pi / 2), "Ease Out: Cubic" -> (1 - math.pow(1 - p, 3)),
      "Ease Out: Quint" -> (1 - math.pow(1 - p, 5)), "Ease Out: Circ" -> math.sqrt(1 - math.pow(p - 1, 2)),
      "Ease InOut: Sine" -> (-(math.cos(math.Pi * p) - 1) / 2), "Ease InOut: Cubic" -> 4 * p * p * p,
      "Ease InOut: Quint" -> 16 * math.pow(p, 5), "Ease InOut: Circ" -> (1 - math.sqrt(1 - math.pow(2 * p, 2))) / 2)
    assertEquals(expected.keySet, Easings.toSet)
    expected.foreach((o, x) => assertEqualsDouble(ease(o, p), x, 1e-12, o))
    Easings.foreach(o => assertEqualsDouble(ease(o, 0), 0.0, 1e-12, o))
    assertEqualsDouble(ease("Ease InOut: Cubic", 0.75), 1 - math.pow(0.5, 3) / 2, 1e-12)
    assertEqualsDouble(ease("Ease InOut: Quint", 0.75), 1 - math.pow(0.5, 5) / 2, 1e-12)
    assertEqualsDouble(ease("Ease InOut: Circ", 0.75), (math.sqrt(1 - 0.25) + 1) / 2, 1e-12)
    intercept[IllegalArgumentException](ease("Bounce", 0.5))
  }

  test("nearest multiple of 8 follows the source rounding (Python half-to-even)") {
    assertEquals(nearestMultiple(682, 1024), (688, 1040))
    assertEquals(nearestMultiple(1024, 682), (1040, 688))
    assertEquals(nearestMultiple(512, 512), (512, 512))
    assertEquals(nearestMultiple(341, 512), (344, 520))
    assertEquals(nearestMultiple(4, 6), (8, 16))
    intercept[IllegalArgumentException](nearestMultiple(0, 8))
  }

  // Tables produced by a separate Python restatement of the source (docs/loopback-scaler-verification.md).
  test("height-driven schedules equal the source exactly") {
    val cases = Seq(
      ((512, 768, 4, 1024, 1024, false, 1.0, "Linear"), Seq((512, 768), (560, 848), (600, 904), (688, 1040))),
      ((512, 768, 5, 1024, 1024, true, 1.5, "Ease In: Sine"), Seq((512, 768), (528, 792), (560, 848), (624, 944), (768, 1152))),
      ((512, 512, 8, 1024, 1024, false, 1.0, "Ease InOut: Circ"),
        Seq((512, 512), (520, 520), (552, 552), (600, 600), (768, 768), (944, 944), (992, 992), (1024, 1024))),
      ((512, 512, 1, 1024, 1024, false, 1.0, "Linear"), Seq((1024, 1024))),
      ((512, 768, 4, 1024, 1024, true, 0.5, "Linear"), Seq((512, 768), (448, 672), (384, 576), (256, 384)))
    )
    cases.foreach { case ((w, h, l, mw, mh, us, sc, o), expected) =>
      assertEquals(sizes(w, h, l, mw, mh, us, sc, o), expected, o)
      assertEquals(restated(w, h, l, mw, mh, us, sc, o, literalWidthBranch = true), expected, o)
    }
    val easings = Map(
      "Linear" -> Seq(616, 720, 824), "Ease In: Sine" -> Seq(544, 616, 728), "Ease In: Cubic" -> Seq(520, 544, 624),
      "Ease In: Quint" -> Seq(512, 528, 584), "Ease In: Circ" -> Seq(528, 560, 616), "Ease Out: Sine" -> Seq(672, 816, 928),
      "Ease Out: Cubic" -> Seq(768, 920, 992), "Ease Out: Quint" -> Seq(856, 984, 1024), "Ease Out: Circ" -> Seq(824, 928, 984),
      "Ease InOut: Sine" -> Seq(560, 688, 848), "Ease InOut: Cubic" -> Seq(528, 648, 896),
      "Ease InOut: Quint" -> Seq(520, 600, 944), "Ease InOut: Circ" -> Seq(536, 616, 928))
    easings.foreach((o, mid) => assertEquals(sizes(512, 512, 5, 1024, 1024, false, 1.0, o), ((512 +: mid) :+ 1024).map(s => (s, s)), o))
  }

  test("width-driven schedules use the width schedule (deliberate fix of the source's calc_height slip)") {
    val cases = Seq(
      ((768, 512, 4, 1024, 1024, false, 1.0, "Linear"), Seq((768, 512), (848, 560), (904, 600), (1040, 688)),
        Seq((776, 344), (848, 432), (896, 512), (1040, 688))),
      ((512, 512, 4, 1024, 2048, false, 1.0, "Linear"), Seq((512, 512), (640, 640), (768, 768), (1024, 1024)),
        Seq((512, 512), (640, 896), (768, 1280), (1024, 2048))),
      ((640, 384, 6, 2048, 1536, false, 1.0, "Ease Out: Cubic"),
        Seq((640, 384), (1248, 744), (1640, 984), (1880, 1128), (2000, 1200), (2056, 1232)), Nil)
    )
    cases.foreach { case ((w, h, l, mw, mh, us, sc, o), fixed, literal) =>
      assertEquals(sizes(w, h, l, mw, mh, us, sc, o), fixed)
      assertEquals(restated(w, h, l, mw, mh, us, sc, o), fixed)
      if literal.nonEmpty then assertEquals(restated(w, h, l, mw, mh, us, sc, o, literalWidthBranch = true), literal)
    }
    // landscape mirrors portrait
    assertEquals(sizes(768, 512, 4, 1024, 1024, false, 1.0, "Linear").map(_.swap), sizes(512, 768, 4, 1024, 1024, false, 1.0, "Linear"))
    assertEquals(sizes(768, 512, 3, 1024, 1024, true, 2.0, "Ease InOut: Sine"), Seq((768, 512), (960, 640), (1536, 1024)))
  }

  test("denoising multiplies by the change factor and clamps to 0.1..1 after every loop") {
    def run(d0: Double, f: Double, n: Int) = Iterator.iterate(d0)(nextDenoising(_, f)).take(n).toSeq
    val up = run(0.35, 1.1, 8)
    Seq(0.35, 0.385, 0.42350000000000004, 0.4658500000000001, 0.5124350000000002, 0.5636785000000003,
      0.6200463500000004, 0.6820509850000005).zip(up).foreach((e, g) => assertEqualsDouble(g, e, 1e-15))
    assertEqualsDouble(run(0.35, 0.9, 12).last, 0.10983370863150003, 1e-15)
    assertEquals(run(0.35, 0.9, 30).min, 0.1)
    assertEquals(run(0.05, 1.05, 3).map(x => math.rint(x * 1000) / 1000), Seq(0.05, 0.1, 0.105)) // first loop keeps the user value
    assertEquals(run(0.95, 1.1, 3).last, 1.0)
  }

  // ---------------------------------------------------------------------------------------- run
  test("one chain: per-loop size, denoising, seed, prompts, init chaining, infotext; only the final image returned") {
    val ctx = FakeContext()
    val p = img2img(ctx)
    val out = plugin.run(p, values("denoising_strength_change_factor" -> "1.1"), ctx)
    val g = ctx.generated
    assertEquals(g.map(x => (x.width, x.height)), Seq((512, 768), (560, 848), (600, 904), (688, 1040)))
    assertEquals(g.map(_.seed), Seq(100L, 101L, 102L, 103L))
    Seq(0.35, 0.385, 0.4235, 0.46585).zip(g.map(_.img2img.get.denoisingStrength)).foreach((e, x) => assertEqualsDouble(x, e, 1e-12))
    assert(g.forall(x => x.prompt == "a castle" && x.negativePrompt == "blurry" && x.batchSize == 1 && x.nIter == 1))
    assert(g.forall(_.img2img.get.resizeMode == 1))
    assertEquals(g.head.img2img.get.initImage, p.img2img.get.initImage)
    assertEquals(g.drop(1).map(_.img2img.get.initImage), ctx.outputPaths.take(3).map(_.toString).toSeq)
    assertEquals(g.head.extra("Denoising strength change factor"), "1.1")
    assertEquals(g.head.extra("Dimension increment factor"), "Linear")
    assertEquals(Seq("Add Detail", "Add Blur", "Smoothing", "Contour", "Sharpness", "Brightness", "Color Strength", "Contrast")
      .map(g.last.extra), Seq("None", "None", "None", "False", "1", "1", "1", "1"))
    // neutral filters: no engine call, the last loop output (already saved) is the result
    assertEquals(ctx.engineCalls.size, 0)
    assertEquals(ctx.saved.size, 0)
    assertEquals(out.images.map(_.path), Seq(ctx.outputPaths.last))
    assertEquals(out.grids, Nil)
  }

  test("batch count repeats the chain from the original image; seeds and denoising carry on like the source") {
    val ctx = FakeContext()
    val p = img2img(ctx).copy(nIter = 2)
    val out = plugin.run(p, values("loops" -> "3", "denoising_strength_change_factor" -> "0.9"), ctx)
    val g = ctx.generated
    assertEquals(g.size, 6)
    assertEquals(g.map(_.seed), Seq(100L, 101L, 102L, 103L, 104L, 105L))
    val d = Iterator.iterate(0.35)(x => math.min(math.max(x * 0.9, 0.1), 1)).take(6).toSeq
    d.zip(g.map(_.img2img.get.denoisingStrength)).foreach((e, x) => assertEqualsDouble(x, e, 1e-12))
    assertEquals(g(3).img2img.get.initImage, p.img2img.get.initImage)
    assertEquals(g(4).img2img.get.initImage, ctx.outputPaths(3).toString)
    assertEquals(g.map(x => (x.width, x.height)).take(3), g.map(x => (x.width, x.height)).drop(3))
    assertEquals(out.images.map(_.path), Seq(ctx.outputPaths(2), ctx.outputPaths(5)))
  }

  test("random seed is fixed once; subseed is fixed when variation strength is used") {
    val ctx = FakeContext()
    val p = img2img(ctx).copy(seed = -1, subseed = -1, subseedStrength = 0.3)
    plugin.run(p, values("loops" -> "3"), ctx)
    val seeds = ctx.generated.map(_.seed)
    assert(seeds.head >= 0)
    assertEquals(seeds, Seq(seeds.head, seeds.head + 1, seeds.head + 2))
    assertEquals(ctx.generated.map(_.subseed).distinct.size, 1)
    assert(ctx.generated.head.subseed >= 0)
  }

  test("use scale targets the scaled img2img size") {
    val ctx = FakeContext()
    plugin.run(img2img(ctx), values("use_scale" -> "true", "scale" -> "1.5", "loops" -> "5", "dimension_increment_factor" -> "Ease In: Sine",
      "max_width" -> "512", "max_height" -> "512"), ctx)
    assertEquals(ctx.generated.map(x => (x.width, x.height)), Seq((512, 768), (528, 792), (560, 848), (624, 944), (768, 1152)))
    assertEquals(plugin.enabledBy, Map("scale" -> "use_scale"))
  }

  private class FilterContext(interruptOnTool: Boolean = false) extends FakeContext:
    val toolCalls = ArrayBuffer[ujson.Obj]()
    override def engineCall(kind: String, params: ujson.Obj): ujson.Value =
      if kind != "image_tool" then super.engineCall(kind, params)
      else
        if interruptOnTool then throw sdfx.core.protocol.EngineError(sdfx.core.protocol.Protocol.ErrorCodes.Interrupted, "interrupted")
        toolCalls += params
        val src = ImageIO.read(Paths.get(params("image_path").str).toFile)
        val outPath = Paths.get(initImage(src.getWidth, src.getHeight, 0xffffff))
        ujson.Obj("path" -> outPath.toString, "outputs" -> ujson.Obj(), "info" -> ujson.Obj())

  test("final filters run once per batch on the last loop and are saved with that loop's infotext") {
    val ctx = FilterContext()
    val p = img2img(ctx).copy(nIter = 2)
    val v = values("loops" -> "2", "sharpness_strength" -> "1.4", "brightness_strength" -> "0.9", "color_strength" -> "1.2",
      "contrast_strength" -> "1.1", "detail_strength" -> "Low", "blur_strength" -> "High", "smooth_strength" -> "Medium",
      "contour_bool" -> "true")
    val out = plugin.run(p, v, ctx)
    assertEquals(ctx.generated.size, 4)
    assertEquals(ctx.toolCalls.size, 2)
    val call = ctx.toolCalls.head
    assertEquals(call("tool").str, "loopback_scaler")
    assertEquals(Seq("sharpness", "brightness", "color", "contrast").map(k => call(k).num), Seq(1.4, 0.9, 1.2, 1.1))
    assertEquals(Seq("detail", "smooth", "blur").map(k => call(k).str), Seq("Low", "Medium", "High"))
    assertEquals(call("contour").bool, true)
    assertEquals(ctx.saved.map(_._1), Seq("img2img", "img2img"))
    val lastLoops = Seq(ctx.generated(1), ctx.generated(3))
    assertEquals(ctx.saved.map(_._3), lastLoops)
    assertEquals(ctx.saved.map(_._4), Seq(s"a castle\nSeed: 101", s"a castle\nSeed: 103"))
    assertEquals(ctx.saved.map(s => (s._2.getWidth, s._2.getHeight, s._2.getRGB(0, 0) & 0xffffff)), Seq.fill(2)((688, 1040, 0xffffff)))
    assertEquals(out.images.size, 2)
    assert(out.images.forall(o => !ctx.outputPaths.contains(o.path)), "returns the filtered copies")
    assertEquals(lastLoops.head.extra("Contour"), "True")
    assertEquals(lastLoops.head.extra("Smoothing"), "Medium")
    assertEquals(lastLoops.head.extra("Color Strength"), "1.2")
    // temp input and engine output are cleaned up
    assert(ctx.toolCalls.forall(c => !Files.exists(Paths.get(c("image_path").str))))
  }

  test("interrupting between loops stops the chain and returns only finished batches") {
    val early = FakeContext(interruptAfter = 2)
    val none = plugin.run(img2img(early), values(), early)
    assertEquals(early.generated.size, 2)
    assertEquals(early.interruptedCalls, 1)
    assertEquals(none.images, Nil)

    val late = FakeContext(interruptAfter = 5)
    val one = plugin.run(img2img(late).copy(nIter = 3), values(), late)
    assertEquals(late.generated.size, 5)
    assertEquals(one.images.map(_.path), Seq(late.outputPaths(3)))
    assert(late.logs.exists(_.contains("interrupted after 5 of 12")))
  }

  test("interrupting the final filters stops without saving a filtered copy") {
    val ctx = FilterContext(interruptOnTool = true)
    val out = plugin.run(img2img(ctx).copy(nIter = 2), values("loops" -> "2", "contour_bool" -> "true"), ctx)
    assertEquals(ctx.generated.size, 2)
    assertEquals(ctx.saved, Nil)
    assertEquals(out.images, Nil)
  }

  test("unsupported inputs are rejected before any generation") {
    def rejects(p: GenerationParams, kv: (String, String)*)(fragment: String) =
      val ctx = FakeContext()
      val e = intercept[IllegalArgumentException](plugin.run(p, values(kv*), ctx))
      assert(e.getMessage.contains(fragment), e.getMessage)
      assertEquals(ctx.generated, Nil)
    val ok = img2img(FakeContext())
    rejects(base)("img2img")
    rejects(ok.copy(batchSize = 2))("batch size")
    rejects(ok, "loops" -> "0")("loops")
    rejects(ok, "loops" -> "33")("loops")
    rejects(ok, "loops" -> "2.5")("whole number")
    rejects(ok, "denoising_strength_change_factor" -> "1.2")("denoising_strength_change_factor")
    rejects(ok, "denoising_strength_change_factor" -> "fast")("must be a number")
    rejects(ok, "max_width" -> "256")("max_width")
    rejects(ok, "max_height" -> "5000")("max_height")
    rejects(ok, "scale" -> "4.5")("scale")
    rejects(ok, "dimension_increment_factor" -> "Bounce")("dimension_increment_factor")
    rejects(ok, "detail_strength" -> "Extreme")("detail_strength")
    rejects(ok, "blur_strength" -> "")("blur_strength")
    rejects(ok, "sharpness_strength" -> "0")("sharpness_strength")
    rejects(ok, "contrast_strength" -> "NaN")("contrast_strength")
    rejects(ok, "contour_bool" -> "maybe")("contour_bool")
    // 1024x1536 x4 = 4096x6144: beyond the engine's 4096 limit
    rejects(img2img(FakeContext(), base.copy(width = 1024, height = 1536)), "use_scale" -> "true", "scale" -> "4")("outside the engine")
    // rounding up to multiples of 8 can overshoot the 4096 maximum too (floor(4096 / 1.5) = 2730 -> 2736x4112)
    rejects(img2img(FakeContext(), base.copy(width = 512, height = 768)), "max_width" -> "4096", "max_height" -> "4096")("2736x4112")
  }

  test("registered as an img2img Script with the source controls and defaults") {
    val s = StandardPlugins.scripts(() => Nil).find(_.id == "loopback_scaler").getOrElse(fail("not registered"))
    assertEquals(s.kind, PluginKind.Script)
    assertEquals(s.tabs, Set(Tab.Img2Img))
    assertEquals(s.requiresEngineFeatures, Seq("loopback_scaler"))
    val d = Values.defaults(s.controls).map
    assertEquals(
      Seq("dimension_increment_factor", "loops", "denoising_strength_change_factor", "max_width", "max_height", "use_scale", "scale",
        "detail_strength", "blur_strength", "smooth_strength", "contour_bool", "sharpness_strength", "brightness_strength",
        "color_strength", "contrast_strength").map(d),
      Seq("Linear", "4", "1", "1024", "1024", "false", "1", "None", "None", "None", "false", "1", "1", "1", "1"))
    val st = Settings.parse(Values(d))
    assert(st.identityFinish)
    assertEquals(st.infotext.keys.toSeq, Seq("Denoising strength change factor", "Dimension increment factor", "Add Detail",
      "Add Blur", "Smoothing", "Contour", "Sharpness", "Brightness", "Color Strength", "Contrast"))
  }
