package sdfx.plugins

import sdfx.core.infotext.Infotext
import sdfx.core.model.{GenerationParams, HiresParams, LoraRef}
import sdfx.core.plugins.api.*

/** Old SD firstpasser: sizes, network choice, the pass sequence on the fake host, LoRA routing, hires, infotext and replay. */
class OldSdFirstpasserSuite extends munit.FunSuite:
  import OldSdFirstpasserPlugin.*

  private val plugin = new OldSdFirstpasserPlugin
  private def values(kv: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Seq("sd1_checkpoint" -> "modelA.safetensors", "sdxl_checkpoint" -> "modelC.safetensors") ++ kv)
  private val base = GenerationParams(prompt = "a castle <lora:detail:0.7>", negativePrompt = "blurry", seed = 100, width = 1024,
    height = 768, steps = 30, checkpoint = Some("modelB.safetensors"), vae = Some("sdxl_vae.safetensors"))

  test("firstpass size follows the family's native side, 8 aligned, capped at 2048") {
    assertEquals(firstpassSize(1024, 768, "SD1", 0), (680, 512))
    assertEquals(firstpassSize(1024, 1024, "SD1", 0), (512, 512))
    assertEquals(firstpassSize(768, 1024, "SDXL", 0), (1024, 1368)) // 1365.33 rounds to 1368
    assertEquals(firstpassSize(1024, 1024, "SD1", 640), (640, 640))
    assertEquals(firstpassSize(512, 4096, "SDXL", 0), (256, 2048)) // 2048 x 16384 capped to 2048 on the long side
    assertEquals(firstpassSize(64, 64, "SD1", 0), (512, 512))
  }

  test("upscale factor covers the target") {
    assertEqualsDouble(upscaleFactor(680, 512, 1024, 768), 1.5, 0.01)
    assert(upscaleFactor(1024, 1024, 512, 512) < 1)
  }

  test("Auto picks the other family; explicit types win; unknown kinds fail") {
    assertEquals(resolveNetwork("Auto", Some("sdxl")), "SD1")
    assertEquals(resolveNetwork("Auto", Some("sdxl-inpaint")), "SD1")
    assertEquals(resolveNetwork("Auto", Some("sd15")), "SDXL")
    assertEquals(resolveNetwork("SDXL", Some("sdxl")), "SDXL")
    intercept[IllegalArgumentException](resolveNetwork("Auto", None))
    intercept[IllegalArgumentException](resolveNetwork("Auto", Some("sd2")))
    intercept[IllegalArgumentException](resolveNetwork("Flux", Some("sd15")))
  }

  test("registered as a txt2img Script with the README controls and defaults") {
    val s = StandardPlugins.scripts(() => Nil).find(_.id == "old_sd_firstpasser").getOrElse(fail("not registered"))
    assertEquals(s.kind, PluginKind.Script)
    assertEquals(s.tabs, Set(Tab.Txt2Img))
    val d = Values.defaults(s.controls).map
    assertEquals(d("steps"), "20")
    assertEquals(d("denoising"), "0.55")
    assertEquals(d("upscaler"), "Lanczos")
    assertEquals(d("network_type"), "Auto")
    assertEquals(StandardPlugins.scripts(() => Nil).count(_.id == "old_sd_firstpasser"), 1)
    assertEquals(StandardPlugins.all(() => Nil).map(_.id).distinct.size, StandardPlugins.all(() => Nil).size)
  }

  test("settings reject out-of-range values instead of clamping") {
    def bad(kv: (String, String)*)(fragment: String) =
      val e = intercept[IllegalArgumentException](Settings.parse(values(kv*)))
      assert(e.getMessage.contains(fragment), e.getMessage)
    bad("steps" -> "0")("steps")
    bad("steps" -> "20.5")("whole")
    bad("denoising" -> "1.5")("denoising")
    bad("denoising" -> "NaN")("number")
    bad("resolution" -> "100")("resolution")
    bad("network_type" -> "Flux")("network type")
    bad("main_loras" -> "maybe")("main_loras")
  }

  test("passes: firstpass on the old checkpoint, upscale, main checkpoint img2img; LoRAs stay in the firstpass") {
    val ctx = FakeContext(echoInit = true)
    val p = base.copy(loras = Seq(LoraRef("manual", 0.5)))
    val out = plugin.run(p, values("steps" -> "12", "denoising" -> "0.4"), ctx).images
    assertEquals(out.size, 1)
    val Seq(first, mainPass) = ctx.transientCalls
    assertEquals(first.checkpoint, Some("modelA.safetensors"))
    assertEquals(first.vae, None)
    assertEquals((first.width, first.height, first.steps), (680, 512, 12))
    assertEquals(first.img2img, None)
    assertEquals(first.prompt, "a castle <lora:detail:0.7>")
    assertEquals(first.loras, Seq(LoraRef("manual", 0.5)))
    assertEquals(first.seed, 100L)
    assertEquals(mainPass.checkpoint, Some("modelB.safetensors"))
    assertEquals(mainPass.vae, Some("sdxl_vae.safetensors"))
    assertEquals((mainPass.width, mainPass.height, mainPass.steps), (1024, 768, 30))
    assertEquals(mainPass.prompt, "a castle")
    assertEquals(mainPass.loras, Nil)
    assertEquals(mainPass.img2img.map(_.denoisingStrength), Some(0.4))
    assertEquals(mainPass.seed, 100L)
    assertEquals(ctx.upscales.toSeq.map(u => (u._2, math.round(u._3 * 100))), Seq(("Lanczos", 151L)))
    assertEquals(ctx.generated, Nil) // nothing but the final save reaches the user outputs
    assertEquals(ctx.saved.size, 1)
  }

  test("main LoRAs ticked: prompt tags, manual LoRAs and ControlNet units stay in the main pass") {
    val ctx = FakeContext(echoInit = true)
    val cn = sdfx.core.model.ControlNetUnit(model = "canny")
    val p = base.copy(loras = Seq(LoraRef("manual", 0.5)), controlnet = Seq(cn))
    plugin.run(p, values("main_loras" -> "true"), ctx)
    val Seq(first, mainPass) = ctx.transientCalls
    assertEquals(first.controlnet, Seq(cn))
    assertEquals(mainPass.prompt, "a castle <lora:detail:0.7>")
    assertEquals(mainPass.loras, Seq(LoraRef("manual", 0.5)))
    assertEquals(mainPass.controlnet, Seq(cn))
  }

  test("ControlNet units act in the firstpass only by default") {
    val ctx = FakeContext(echoInit = true)
    val cn = sdfx.core.model.ControlNetUnit(model = "canny")
    plugin.run(base.copy(controlnet = Seq(cn)), values(), ctx)
    assertEquals(ctx.transientCalls.map(_.controlnet), Seq(Seq(cn), Nil))
  }

  test("batches run the whole batch on the firstpass checkpoint before switching, one saved image each") {
    val ctx = FakeContext(echoInit = true)
    val out = plugin.run(base.copy(batchSize = 2, nIter = 2), values(), ctx).images
    assertEquals(out.size, 4)
    assertEquals(ctx.transientCalls.map(_.checkpoint.get), "modelA.safetensors" +: Seq.fill(4)("modelB.safetensors"))
    assertEquals(ctx.transientCalls.head.imagesPerJob, 4)
    assertEquals(ctx.transientCalls.tail.map(_.seed), Seq(100L, 101L, 102L, 103L))
  }

  test("an SDXL pass on an SD1.5 main model and an explicit network type") {
    val ctx = FakeContext(echoInit = true)
    val p = base.copy(checkpoint = Some("modelA.safetensors"), width = 512, height = 512)
    plugin.run(p, values("sdxl_checkpoint" -> "modelB.safetensors"), ctx) // Auto: SD1.5 main -> SDXL pass
    val Seq(first, mainPass) = ctx.transientCalls
    assertEquals(first.checkpoint, Some("modelB.safetensors"))
    assertEquals((first.width, first.height), (1024, 1024))
    assertEquals(mainPass.checkpoint, Some("modelA.safetensors"))
    assertEquals(ctx.upscales.toSeq, Nil) // the firstpass is larger than the target: the engine downsizes the init image
  }

  test("mismatched firstpass checkpoint, missing checkpoints and wrong tab are reported") {
    def err(p: GenerationParams, v: Values) = intercept[IllegalArgumentException](plugin.run(p, v, FakeContext())).getMessage
    assert(err(base, values("sd1_checkpoint" -> "")).contains("choose the SD 1.x"))
    assert(err(base, values("sd1_checkpoint" -> "modelB.safetensors")).contains("not an SD 1.x"))
    assert(err(base, values("sd1_checkpoint" -> "ghost.safetensors")).contains("not found"))
    assert(err(base.copy(checkpoint = Some("ghost.safetensors")), values()).contains("main checkpoint not found"))
    assert(err(base.copy(checkpoint = Some("modelA.safetensors")), values("sdxl_checkpoint" -> "")).contains("choose the SDXL"))
    val ctx = FakeContext()
    val withInit = base.copy(img2img = Some(sdfx.core.model.Img2ImgParams(ctx.initImage(64, 64))))
    assert(intercept[IllegalArgumentException](plugin.run(withInit, values(), ctx)).getMessage.contains("txt2img"))
  }

  test("no upscaler needed when the firstpass is not smaller than the target; None skips it") {
    val ctx = FakeContext(echoInit = true)
    plugin.run(base.copy(width = 512, height = 512), values(), ctx)
    assertEquals(ctx.upscales.toSeq, Nil)
    val ctx2 = FakeContext(echoInit = true)
    plugin.run(base, values("upscaler" -> "None"), ctx2)
    assertEquals(ctx2.upscales.toSeq, Nil)
  }

  test("hires fix runs as one more img2img pass on the main checkpoint with the hires settings") {
    val ctx = FakeContext(echoInit = true)
    val hr = HiresParams(enabled = true, scale = 1.5, upscaler = "R-ESRGAN 4x+", secondPassSteps = 9, denoisingStrength = 0.3,
      prompt = "hires <lora:detail:0.7> prompt", cfgScale = Some(4.0), samplerName = Some("Euler"))
    plugin.run(base.copy(hires = hr), values(), ctx)
    val Seq(first, mainPass, hiresPass) = ctx.transientCalls
    assertEquals(first.hires.enabled, false)
    assertEquals(mainPass.hires.enabled, false)
    assertEquals(hiresPass.checkpoint, Some("modelB.safetensors"))
    assertEquals((hiresPass.width, hiresPass.height, hiresPass.steps), (1536, 1152, 9))
    assertEquals(hiresPass.img2img.map(_.denoisingStrength), Some(0.3))
    assertEquals(hiresPass.cfgScale, 4.0)
    assertEquals(hiresPass.samplerName, "Euler")
    assertEquals(hiresPass.prompt, "hires prompt")
    assertEquals(ctx.upscales.toSeq.map(u => (u._2, math.round(u._3 * 100))), Seq(("Lanczos", 151L), ("R-ESRGAN 4x+", 150L)))
    val (_, _, params, _) = ctx.saved.head
    assertEquals((params.width, params.height, params.steps), (1024, 768, 30))
    assertEquals(params.hires, hr)
  }

  test("a latent hires upscaler resizes in the engine instead of calling the upscaler") {
    val ctx = FakeContext(echoInit = true)
    plugin.run(base.copy(hires = HiresParams(enabled = true, scale = 2.0, upscaler = "Latent")), values(), ctx)
    assertEquals(ctx.upscales.size, 1) // only the firstpass upscale
    assertEquals(ctx.transientCalls.last.width, 2048)
  }

  test("the saved infotext is the request's own, with the script keys, and it replays into the form") {
    val ctx = FakeContext(echoInit = true)
    val hr = HiresParams(enabled = true, scale = 1.5, upscaler = "Lanczos", secondPassSteps = 9, denoisingStrength = 0.3)
    plugin.run(base.copy(hires = hr), values("steps" -> "14", "denoising" -> "0.45", "resolution" -> "640"), ctx)
    val (kind, _, _, text) = ctx.saved.head
    assertEquals(kind, "txt2img")
    val parsed = Infotext.parse(text)
    val f = parsed.fields
    assert(parsed.prompt.startsWith("a castle <lora:detail:0.7>"), parsed.prompt)
    assertEquals(f("Steps"), "30")
    assertEquals(f("Size"), "1024x768")
    assertEquals(f("Seed"), "100")
    assertEquals(f("Denoising strength"), "0.3")
    assertEquals(f("Hires upscale"), "1.5")
    assertEquals(f("Hires steps"), "9")
    assertEquals(f(Key), "SD1")
    assertEquals(f(StepsKey), "14")
    assertEquals(f(DenoisingKey), "0.45")
    assertEquals(f(CheckpointKey), "modelA.safetensors")
    assertEquals(f(SizeKey), "856x640")
    assertEquals(f(ResolutionKey), "640")
    val values2 = replay(f).getOrElse(fail("no replay"))
    assertEquals(values2.str("sd1_checkpoint"), "modelA.safetensors")
    assertEquals(values2.str("sdxl_checkpoint"), "")
    assertEquals(values2.str("network_type"), "SD1")
    assertEquals(values2.str("steps"), "14")
    assertEquals(values2.str("denoising"), "0.45")
    assertEquals(values2.str("resolution"), "640")
    // the replayed values give the same passes
    val again = FakeContext(echoInit = true)
    plugin.run(base.copy(hires = hr), Values(Values.defaults(plugin.controls).map ++ values2.map), again)
    assertEquals(again.transientCalls.map(c => (c.checkpoint, c.width, c.height, c.steps)),
      ctx.transientCalls.map(c => (c.checkpoint, c.width, c.height, c.steps)))
  }

  test("no replay without the script keys; malformed values are ignored") {
    assertEquals(replay(Map("Steps" -> "20")), None)
    assertEquals(replay(Map(Key -> "SD3", StepsKey -> "20", DenoisingKey -> "0.5")), None)
    assertEquals(replay(Map(Key -> "SD1", StepsKey -> "0", DenoisingKey -> "0.5")), None)
    assertEquals(replay(Map(Key -> "SDXL", StepsKey -> "20", DenoisingKey -> "2")), None)
  }

  test("interruption returns the finished images and cleans the temporary files") {
    val ctx = FakeContext(interruptAfter = 1, echoInit = true)
    val out = plugin.run(base, values(), ctx).images
    assertEquals(out, Nil)
    assert(ctx.transientPaths.forall(p => !java.nio.file.Files.exists(p)), "temporary files left behind")
  }

  test("the main checkpoint is reloaded after an interruption in the firstpass or the main pass, not after a normal run") {
    val a = FakeContext(interruptAfter = 0, echoInit = true)
    assertEquals(plugin.run(base, values(), a).images, Nil)
    assertEquals(a.loadedCheckpoints.toSeq, Seq(("modelB.safetensors", Some("sdxl_vae.safetensors"))))
    val b = FakeContext(interruptAfter = 1, echoInit = true)
    plugin.run(base, values(), b)
    assertEquals(b.loadedCheckpoints.toSeq, Seq(("modelB.safetensors", Some("sdxl_vae.safetensors"))))
    val c = FakeContext(echoInit = true)
    plugin.run(base, values(), c)
    assertEquals(c.loadedCheckpoints.toSeq, Nil)
  }

  test("temporary files of a finished run are deleted") {
    val ctx = FakeContext(echoInit = true)
    plugin.run(base, values(), ctx)
    assert(ctx.transientPaths.forall(p => !java.nio.file.Files.exists(p)))
  }
