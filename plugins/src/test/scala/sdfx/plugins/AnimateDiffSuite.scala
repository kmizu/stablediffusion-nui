package sdfx.plugins

import sdfx.core.infotext.Infotext
import sdfx.core.model.{GenerationParams, HiresParams, Img2ImgParams}
import sdfx.core.plugins.api.*

import java.nio.file.Files

/** AnimateDiff script: settings, the engine hook, the Python-dict infotext value, replay, and the run on the fake host. */
class AnimateDiffSuite extends munit.FunSuite:
  import AnimateDiffPlugin.*

  private val plugin = new AnimateDiffPlugin
  private def values(kv: (String, String)*) = Values(Values.defaults(plugin.controls).map ++ Seq("model" -> "mm_sd15_v3.safetensors") ++ kv)
  private val base = GenerationParams(prompt = "a girl", seed = 7, width = 64, height = 64, steps = 5)

  test("registered once as a Script for both tabs, with the engine feature") {
    val all = StandardPlugins.scripts(() => Nil).filter(_.id == "animatediff")
    assertEquals(all.size, 1)
    assertEquals(all.head.kind, PluginKind.Script)
    assertEquals(all.head.tabs, Set(Tab.Txt2Img, Tab.Img2Img))
    assertEquals(plugin.requiresEngineFeatures, Seq("animatediff"))
    assertEquals(StandardPlugins.all(() => Nil).map(_.id).distinct.size, StandardPlugins.all(() => Nil).size)
  }

  test("defaults follow the extension: 16 frames, 8 fps, R+P, context 16, GIF") {
    val s = Settings.parse(Values(Values.defaults(plugin.controls).map))
    assertEquals((s.videoLength, s.fps, s.loopNumber, s.closedLoop, s.batchSize, s.stride, s.overlap), (16, 8, 0, "R+P", 16, 1, -1))
    assertEquals(s.formats, Seq("GIF"))
    assertEquals(s.frames, 16)
    assertEquals(Settings.parse(values("video_length" -> "0", "batch_size" -> "12")).frames, 12)
  }

  test("out-of-range and malformed settings are rejected, not clamped") {
    def bad(kv: (String, String)*)(fragment: String) =
      val e = intercept[IllegalArgumentException](Settings.parse(values(kv*)))
      assert(e.getMessage.contains(fragment), e.getMessage)
    bad("fps" -> "0")("fps")
    bad("video_length" -> "9999")("video_length")
    bad("batch_size" -> "33")("batch_size")
    bad("closed_loop" -> "X")("closed loop")
    bad("stride" -> "1.5")("whole")
    bad("free_init_filter" -> "sinc")("filter")
    bad("format_gif" -> "false")("save format")
    bad("overlap" -> "40")("overlap")
    bad("format_mp4" -> "maybe")("format_mp4")
  }

  test("the hook carries the engine values and only adds FreeInit / last frame when used") {
    val hook = hookFields(Settings.parse(values("closed_loop" -> "A", "stride" -> "2")))
    assertEquals(hook("model").str, "mm_sd15_v3.safetensors")
    assertEquals(hook("video_length").num, 16.0)
    assertEquals(hook("closed_loop").str, "A")
    assertEquals(hook("interp").str, "Off")
    assert(!hook.obj.contains("free_init") && !hook.obj.contains("last_frame") && !hook.obj.contains("format"))
    val full = hookFields(Settings.parse(values("free_init" -> "true", "free_init_iterations" -> "2", "last_frame" -> "/x/last.png")))
    assertEquals(full("free_init").bool, true)
    assertEquals(full("free_init_iterations").num, 2.0)
    assertEquals(full("last_frame").str, "/x/last.png")
  }

  test("the infotext value is a Python dict and round-trips through quoting, parsing and replay") {
    val s = Settings.parse(values("format_gif" -> "true", "format_mp4" -> "true", "fps" -> "12", "free_init" -> "true"))
    val text = infotextValue(s)
    assert(text.startsWith("{'model': 'mm_sd15_v3.safetensors', 'video_length': 16, 'fps': 12"), text)
    assert(text.contains("'format': ['GIF', 'MP4']") && text.contains("'free_init': True"), text)
    val line = Infotext.render("a girl", "", scala.collection.immutable.ListMap("Steps" -> "5", "Seed" -> "7", "Size" -> "64x64", Key -> text))
    assert(line.contains("AnimateDiff: \"{'model'"), line)
    val parsed = Infotext.parse(line).fields.toMap
    val replay = AnimateDiffPlugin.replay(parsed).getOrElse(fail("no replay"))
    assertEquals(Settings.parse(replay), s)
  }

  test("the extension's own infotext value restores the form") {
    val upstream = "{'model': 'mm_sd15_v2.safetensors', 'video_length': 24, 'fps': 10, 'loop_number': 0, 'closed_loop': 'R-P', " +
      "'batch_size': 16, 'stride': 2, 'overlap': 4, 'interp': 'Off', 'interp_x': 10, 'latent_power': 2, 'format': ['GIF', 'PNG', 'TXT']}"
    val v = AnimateDiffPlugin.replay(Map(Key -> upstream)).getOrElse(fail("no replay"))
    val s = Settings.parse(v)
    assertEquals((s.model, s.videoLength, s.fps, s.closedLoop, s.stride, s.overlap, s.latentPower), ("mm_sd15_v2.safetensors", 24, 10, "R-P", 2, 4, 2.0))
    assertEquals(s.formats, Seq("GIF", "PNG", "TXT"))
    assertEquals(AnimateDiffPlugin.replay(Map("Steps" -> "20")), None)
    assertEquals(AnimateDiffPlugin.replay(Map(Key -> "not a dict")), None)
    assertEquals(AnimateDiffPlugin.replay(Map(Key -> "{'fps': 0}")), None) // out of range -> no replay
    assertEquals(parsePyDict("{'a': [1, 'x y'], 'b': None}").map(_.render()), Some("""{"a":[1,"x y"],"b":null}"""))
    assertEquals(parsePyDict("{'a': 1 + 2}"), None)
  }

  test("a run generates the frames in one engine call, saves the first frame and writes the videos beside it") {
    val ctx = new FakeContext
    val out = plugin.run(base, values("video_length" -> "4", "batch_size" -> "4", "format_mp4" -> "true", "format_txt" -> "true"), ctx)
    assertEquals(ctx.transientCalls.size, 1)
    val call = ctx.transientCalls.head
    assertEquals(call.hooks("animatediff")("video_length").num, 4.0)
    assertEquals(call.seed, 7L)
    assert(call.extra(Key).contains("'format': ['GIF', 'MP4', 'TXT']"), call.extra)
    assertEquals(out.images.size, 1)
    assertEquals(ctx.saved.size, 1)
    val preview = out.images.head.path
    val stem = preview.getFileName.toString.replaceAll("\\.png$", "")
    assertEquals(Files.readString(preview.resolveSibling(s"$stem.gif")), "GIF frames=4 fps=8")
    assert(Files.exists(preview.resolveSibling(s"$stem.mp4")) && Files.exists(preview.resolveSibling(s"$stem.txt")))
    val encode = ctx.engineCalls.filter(_._1 == "animatediff_encode")
    assertEquals(encode.size, 1)
    assertEquals(encode.head._2("formats").arr.map(_.str).toSeq, Seq("GIF", "MP4"))
    assertEquals(encode.head._2("closed_loop").str, "R+P")
    assert(ctx.transientPaths.forall(p => !Files.exists(p)), "transient frames must be deleted")
  }

  test("PNG saves every frame; batch count gives one video per iteration; no video format skips the encoder") {
    val ctx = new FakeContext
    val out = plugin.run(base.copy(nIter = 2), values("video_length" -> "3", "batch_size" -> "3", "format_png" -> "true", "format_gif" -> "false"), ctx)
    assertEquals(out.images.size, 6)
    assert(ctx.engineCalls.isEmpty)
    val two = new FakeContext
    plugin.run(base.copy(nIter = 2), values("video_length" -> "3", "batch_size" -> "3"), two)
    assertEquals(two.engineCalls.size, 2)
    assertEquals(two.saved.size, 2)
  }

  test("img2img runs are saved as img2img and keep the init image") {
    val ctx = new FakeContext
    val init = ctx.initImage(64, 64)
    val p = base.copy(img2img = Some(Img2ImgParams(init, denoisingStrength = 0.6)))
    plugin.run(p, values("video_length" -> "2", "batch_size" -> "2"), ctx)
    assertEquals(ctx.saved.head._1, "img2img")
    assertEquals(ctx.transientCalls.head.img2img.map(_.initImage), Some(init))
  }

  test("invalid combinations fail before any generation") {
    def fails(p: GenerationParams, kv: (String, String)*)(fragment: String) =
      val ctx = new FakeContext
      val e = intercept[IllegalArgumentException](plugin.run(p, values(kv*), ctx))
      assert(e.getMessage.contains(fragment), e.getMessage)
      assertEquals(ctx.attempted, 0)
    fails(base.copy(batchSize = 2))("batch size")
    fails(base.copy(hires = HiresParams(enabled = true)))("Hires")
    fails(base, "model" -> "")("motion module")
    fails(base, "last_frame" -> "/x.png")("img2img")
  }

  test("an interrupted run returns nothing and leaves no files behind") {
    val ctx = new FakeContext(interruptAfter = 0)
    assertEquals(plugin.run(base, values(), ctx).images, Nil)
    assert(ctx.engineCalls.isEmpty)
  }

  test("plaintext video policy is enforced when a video format is chosen") {
    val ctx = new FakeContext:
      override def plaintextVideoAllowed: Boolean = false
    intercept[IllegalStateException](plugin.run(base, values(), ctx))
    assertEquals(ctx.attempted, 0)
    val ok = plugin.run(base.copy(seed = 3), values("format_gif" -> "false", "format_png" -> "true", "video_length" -> "2", "batch_size" -> "2"), ctx)
    assertEquals(ok.images.size, 2)
  }
