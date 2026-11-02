package sdfx.plugins

import sdfx.core.infotext.Infotext
import sdfx.core.model.{ControlNetUnit, GenerationParams, HiresParams, Img2ImgParams}
import sdfx.core.plugins.api.*

import java.nio.file.Files

/** SD-CN-Animation script: settings, infotext replay and the frame loop on the fake host. */
class CnAnimationSuite extends munit.FunSuite:
  import CnAnimationPlugin.*

  private val plugin = new CnAnimationPlugin
  private val video = Files.createTempFile("cn-animation", ".mp4")
  private def values(kv: (String, String)*) = Values(Values.defaults(plugin.controls).map ++ Seq("video" -> video.toString) ++ kv)
  private val base = GenerationParams(prompt = "a girl", seed = 7, width = 64, height = 64, steps = 5)
  private def ops(ctx: FakeContext): Seq[String] = ctx.engineCalls.filter(_._1 == "cn_animation").map(_._2("operation").str).toSeq

  test("registered once as a Script for both tabs, with the engine feature") {
    val all = StandardPlugins.scripts(() => Nil).filter(_.id == "cn_animation")
    assertEquals(all.size, 1)
    assertEquals(all.head.kind, PluginKind.Script)
    assertEquals(plugin.requiresEngineFeatures, Seq("cn_animation"))
    assertEquals(StandardPlugins.all(() => Nil).map(_.id).distinct.size, StandardPlugins.all(() => Nil).size)
  }

  test("defaults follow the extension") {
    val s = Settings.parse(values())
    assertEquals((s.processingStrength, s.fixFrameStrength, s.step1Mode, s.blendAlpha, s.step1Seed, s.step2Seed), (0.85, 0.15, 0, 1.0, -1L, 8888L))
    assertEquals((s.occlusionBlur, s.trailing, s.flowMultiplier, s.difoMultiplier, s.difsMultiplier), (3.0, true, 5.0, 2.0, 0.0))
    assertEquals((s.firstStrength, s.raftModel, s.raftIters, s.formats, s.saveFrames), (0.75, "large", 20, Seq("MP4"), false))
  }

  test("out-of-range and malformed settings are rejected, not clamped") {
    def bad(kv: (String, String)*)(fragment: String) =
      val e = intercept[IllegalArgumentException](Settings.parse(values(kv*)))
      assert(e.getMessage.contains(fragment), e.getMessage)
    bad("processing_strength" -> "1.5")("processing_strength")
    bad("occlusion_mask_blur" -> "11")("occlusion_mask_blur")
    bad("max_frames" -> "-1")("max_frames")
    bad("frame_skip" -> "1.5")("whole")
    bad("step_1_seed" -> "-2")("step_1_seed")
    bad("step_2_seed" -> "x")("step_2_seed")
    bad("step_1_processing_mode" -> "other")("processing mode")
    bad("raft_model" -> "tiny")("RAFT")
    bad("format_mp4" -> "false")("video format")
    bad("occlusion_mask_trailing" -> "maybe")("occlusion_mask_trailing")
  }

  test("the infotext value round-trips through parsing and replay; only the video name is recorded") {
    val s = Settings.parse(values("video" -> "C:\\Users\\me\\Videos\\clip one.mp4", "processing_strength" -> "0.6", "step_1_processing_mode" -> Step1Modes(1),
      "format_gif" -> "true", "step_1_seed" -> "42"))
    val text = infotextValue(s)
    assert(text.contains("'video': 'clip one.mp4'") && !text.contains("Users"), text)
    val line = Infotext.render("a girl", "", scala.collection.immutable.ListMap("Steps" -> "5", "Seed" -> "7", "Size" -> "64x64", Key -> text))
    val replay = CnAnimationPlugin.replay(Infotext.parse(line).fields.toMap).getOrElse(fail("no replay"))
    val back = Settings.parse(replay.updated("video", "x"))
    assertEquals((back.processingStrength, back.step1Mode, back.step1Seed, back.formats), (0.6, 1, 42L, Seq("MP4", "GIF")))
    assertEquals(replay.str("video"), "clip one.mp4")
    assertEquals(CnAnimationPlugin.replay(Map("Steps" -> "20")), None)
    assertEquals(CnAnimationPlugin.replay(Map(Key -> "{'processing_strength': 3}")), None)
  }

  test("a run: first frame, then flow + step 1 + step 2 per frame, one video next to the first frame") {
    val ctx = new FakeContext
    val unit = ControlNetUnit(model = "canny", image = Some("/stale.png"), preprocessor = "canny")
    val off = ControlNetUnit(enabled = false, model = "depth")
    val out = plugin.run(base.copy(controlnet = Seq(unit, off)), values(), ctx)
    // 4 frames: 1 first frame + 3 x (step 1 + step 2)
    assertEquals(ctx.transientCalls.size, 7)
    assertEquals(ops(ctx), Seq("prepare", "finish") ++ Seq.fill(3)(Seq("flow", "finish", "finish")).flatten ++ Seq("encode", "release"))
    assertEquals(out.images.size, 1)
    assertEquals(ctx.saved.size, 1)
    val calls = ctx.transientCalls
    assertEquals(calls.head.seed, 7L)
    assertEquals(calls.head.img2img.map(_.denoisingStrength), Some(0.75))
    assertEquals(calls(1).img2img.map(_.denoisingStrength), Some(0.85))
    assertEquals(calls(2).img2img.map(_.denoisingStrength), Some(0.15))
    assertEquals(calls(2).seed, 8888L)
    assertEquals(calls(1).seed, 7L + 1) // step 1 seed -1: job seed + frame number
    assertEquals(calls(3).seed, 7L + 2)
    assert(calls(1).img2img.get.mask.isEmpty, "full-image mode has no mask")
    // each ControlNet unit that is on gets the current source frame; a unit that is off is untouched
    calls.foreach { c =>
      assertEquals(c.controlnet.size, 2)
      assert(c.controlnet.head.image.exists(_.contains("source-")), c.controlnet.head.image)
      assertEquals(c.controlnet(1), off)
    }
    assert(calls(1).controlnet.head.image.exists(_.endsWith("source-00001.png")))
    assert(calls(5).controlnet.head.image.exists(_.endsWith("source-00003.png")))
    assert(calls.forall(_.extra.contains(Key)))
    val preview = out.images.head.path
    val stem = preview.getFileName.toString.replaceAll("\\.png$", "")
    assertEquals(Files.readString(preview.resolveSibling(s"$stem.mp4")), "MP4 frames=4 fps=8")
    val encode = ctx.engineCalls.filter(c => c._1 == "cn_animation" && c._2("operation").str == "encode").head._2
    assertEquals(encode("images").arr.size, 4)
    assert(ctx.transientPaths.forall(p => !Files.exists(p)), "transient frames must be deleted")
  }

  test("the occlusion state feeds the next frame, and the work directory is removed") {
    val ctx = new FakeContext
    plugin.run(base, values(), ctx)
    val flows = ctx.engineCalls.filter(c => c._1 == "cn_animation" && c._2("operation").str == "flow").map(_._2)
    assertEquals(flows.size, 3)
    assertEquals(flows.head("prev_alpha"), ujson.Null)
    assert(flows(1)("prev_alpha").str.endsWith("f00001-alpha.npy"), flows(1)("prev_alpha"))
    assert(flows(2)("prev_alpha").str.endsWith("f00002-alpha.npy"))
    assertEquals(flows.head("blend_alpha").num, 1.0)
    assertEquals(flows.head("occlusion_blur").num, 3.0)
    // frame n is warped from the styled frame n-1, never from a source frame
    assert(flows(1)("prev_frame").str.endsWith("source-00001.png") && flows(1)("cur_frame").str.endsWith("source-00002.png"))
    val work = java.nio.file.Path.of(ctx.engineCalls.head._2("output_dir").str)
    assert(!Files.exists(work), "work directory must be deleted")
  }

  test("fix frame strength 0 skips step 2; inpaint mode sends the occlusion mask and skips an empty one") {
    val ctx = new FakeContext
    plugin.run(base, values("fix_frame_strength" -> "0", "step_1_processing_mode" -> Step1Modes(1), "step_1_seed" -> "5"), ctx)
    assertEquals(ctx.transientCalls.size, 4) // first frame + 3 x step 1
    assert(ctx.transientCalls.tail.forall(c => c.img2img.exists(_.mask.isDefined) && c.seed == 5L))
    assert(ctx.transientCalls.tail.forall(c => c.img2img.exists(i => i.inpaintingFill == 1 && i.inpaintFullRes && i.maskBlur == 0)))
    val empty = new FakeContext
    empty.cnMaskMax = 0.0
    plugin.run(base, values("fix_frame_strength" -> "0", "step_1_processing_mode" -> Step1Modes(1)), empty)
    assertEquals(empty.transientCalls.size, 1) // only the first frame: nothing to inpaint
  }

  test("save frames puts every frame in the gallery; frame skip and fps override reach the engine") {
    val ctx = new FakeContext
    val out = plugin.run(base, values("save_frames" -> "true", "frame_skip" -> "2", "fps" -> "24", "max_frames" -> "9", "format_gif" -> "true"), ctx)
    assertEquals(out.images.size, 4)
    val prepare = ctx.engineCalls.head._2
    assertEquals((prepare("frame_skip").num, prepare("max_frames").num, prepare("width").num, prepare("height").num), (2.0, 9.0, 64.0, 64.0))
    val encode = ctx.engineCalls.filter(c => c._2("operation").str == "encode").head._2
    assertEquals(encode("fps").num, 24.0)
    assertEquals(encode("formats").arr.map(_.str).toSeq, Seq("MP4", "GIF"))
  }

  test("invalid combinations fail before any engine call") {
    def fails(p: GenerationParams, kv: (String, String)*)(fragment: String) =
      val ctx = new FakeContext
      val e = intercept[IllegalArgumentException](plugin.run(p, values(kv*), ctx))
      assert(e.getMessage.contains(fragment), e.getMessage)
      assertEquals(ctx.attempted + ctx.engineCalls.size, 0)
    fails(base.copy(batchSize = 2))("batch size")
    fails(base.copy(nIter = 2))("batch count")
    fails(base.copy(hires = HiresParams(enabled = true)))("Hires")
    fails(base, "video" -> "")("input video")
    fails(base, "video" -> "/definitely/missing.mp4")("not found")
    fails(base.copy(img2img = Some(Img2ImgParams("/x.png", mask = Some("/m.png")))))("inpainting")
  }

  test("a video with one frame is rejected, and the model is released") {
    val ctx = new FakeContext
    ctx.cnFrames = 1
    val e = intercept[IllegalArgumentException](plugin.run(base, values(), ctx))
    assert(e.getMessage.contains("two frames"), e.getMessage)
    assertEquals(ops(ctx).last, "release")
  }

  test("an interruption keeps the saved frames, writes no video") {
    val ctx = new FakeContext(interruptAfter = 3)
    val out = plugin.run(base, values("save_frames" -> "true"), ctx)
    assertEquals(out.images.size, 2) // first frame and the first processed frame
    assert(!ops(ctx).contains("encode"))
    val preview = out.images.head.path
    val stem = preview.getFileName.toString.replaceAll("\\.png$", "")
    assert(!Files.exists(preview.resolveSibling(s"$stem.mp4")))
  }

  test("plaintext video policy is enforced") {
    val ctx = new FakeContext:
      override def plaintextVideoAllowed: Boolean = false
    intercept[IllegalStateException](plugin.run(base, values(), ctx))
    assertEquals(ctx.attempted + ctx.engineCalls.size, 0)
  }
