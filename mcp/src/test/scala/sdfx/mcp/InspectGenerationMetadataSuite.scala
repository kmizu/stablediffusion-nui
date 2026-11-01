package sdfx.mcp

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import sdfx.core.png.PngMetadata

class InspectGenerationMetadataSuite extends munit.FunSuite:
  test("original PNG metadata is restored identically from allowed path and base64") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("image.png")
    val text = "a cat\nNegative prompt: blur\nSteps: 25, Sampler: Euler a, CFG scale: 6, Seed: 123, Size: 512x768, Conditioning Highres Fix: high, Model keyword replay: true, Unknown Extension: unsafe"
    PngMetadata.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val policy = new PathPolicy(() => Seq(dir))
    val byPath = InspectGenerationMetadata.inspect(path.toString, policy, dir)
    val byBytes = InspectGenerationMetadata.inspect(Base64.getEncoder.encodeToString(Files.readAllBytes(path)), policy, dir)
    assertEquals(byPath, byBytes)
    assertEquals(byPath, InspectGenerationMetadata.inspect("data:image/png;base64," + Base64.getEncoder.encodeToString(Files.readAllBytes(path)), policy, dir))
    assertEquals(byPath("generation_args")("prompt").str, "a cat")
    assertEquals(byPath("generation_args")("steps").num, 25.0)
    assertEquals(byPath("generation_args")("seed").num, 123.0)
    assertEquals(byPath("always_on")("model_keyword")("enabled").bool, false)
    assertEquals(byPath("always_on")("conditioning_highres_fix")("enabled").bool, true)
    assertEquals(byPath("always_on")("conditioning_highres_fix")("mode").str, "high")
    assert(byPath("unapplied_fields").obj.contains("Unknown Extension"))
    assert(!byPath("unapplied_fields").obj.contains("Conditioning Highres Fix"))
    assert(!byPath("unapplied_fields").obj.contains("Model keyword replay"))
    assert(!byPath("generation_args").obj.contains("extra"))
  }

  test("all consumed AlwaysOn source fields are excluded from unapplied fields") {
    import sdfx.core.infotext.{ImageFacts, Img2ImgHiresMetadata, Infotext}
    import sdfx.core.model.GenerationParams
    import sdfx.core.plugins.api.Values
    import sdfx.plugins.*
    val configured = Seq(
      new ConditioningHighresFixPlugin -> Values(Map("enabled" -> "true", "mode" -> "high")),
      new Img2ImgHiresFixPlugin -> Values(Map("enabled" -> "true", "upscaler" -> "R-ESRGAN 4x+")),
      new TokenDownsamplingPlugin -> Values(Map("enabled" -> "true", "factor" -> "2", "factor2" -> "3", "disable_after" -> "0.5")),
      new ModelKeywordPlugin -> Values(Map("enabled" -> "true")),
      new ComposableLoraPlugin -> Values(Map("enabled" -> "true", "with_step" -> "true")),
      new GridNumbersPlugin -> Values(Map("enabled" -> "true", "image_numbers" -> "true", "xyz_info" -> "true"))
    ).foldLeft(GenerationParams(prompt = "cat")) { case (p, (plugin, values)) => plugin.process(p, values) }
    val p = configured.copy(extra = configured.extra + ("Hires seed" -> "{'Seed':123,'Subseed':0,'Strength':0.2}"))
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("image.png")
    PngMetadata.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> Infotext.write(p, ImageFacts(42L))))
    val result = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)
    val consumed = Set("Conditioning Highres Fix", Img2ImgHiresMetadata.key, "ToDo factor", "ToDo factor2", "ToDo disable after",
      "Model keyword settings", "Composable LoRA", "Hires seed", sdfx.core.image.GridNumbers.NumbersKey, sdfx.core.image.GridNumbers.XyzKey) ++
      Seq("with_step", "uc_text_model_encoder", "uc_diffusion_model", "plot_weights", "single_no_uc", "hires_global_step").map("Composable LoRA " + _)
    consumed.foreach(key => assert(!result("unapplied_fields").obj.contains(key), s"$key was returned as unapplied"))
  }

  test("CD Tuner CDT and CDTC restore the plugin and are consumed") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("cd-tuner.png")
    val text = "cat <cdt:d1=2>\nSteps: 20, Seed: 4, Size: 512x512, CDT: \"2.0,0,0,0,0,0,0,0,0,0,1,4,-1,0,0,0\", CDTC: \"1,1_Vertical_5 0 0;0 0 5_2_1\""
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val result = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)
    val on = result("always_on")("cd_tuner")
    assertEquals((on("enabled").bool, on("d1").num, on("hrs").bool, on("st1").num), (true, 2.0, true, 4.0))
    assertEquals((on("split_by").str, on("colors").str), ("Vertical", "5 0 0;0 0 5"))
    assert(!result("unapplied_fields").obj.contains("CDT"))
    assert(!result("unapplied_fields").obj.contains("CDTC"))
    val malformed = dir.resolve("cd-tuner-bad.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), malformed,
      Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512, CDT: \"1,2\""))
    val bad = InspectGenerationMetadata.inspect(malformed.toString, new PathPolicy(() => Seq(dir)), dir)
    assert(!bad("always_on").obj.contains("cd_tuner"))
    assert(bad("unapplied_fields").obj.contains("CDT"))
  }

  test("Characteristic Guidance CHG restores the plugin and is consumed") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("chg.png")
    val chg = "{'RegS': 5, 'RegR': 1.5, 'MaxI': 30, 'NBasis': 1, 'Reuse': 1, 'Tol': -4, 'IteSS': 1, 'ASpeed': 0.4, " +
      "'AStrength': 0.5, 'AADim': 2, 'CMode': 'More Prompt', 'StartStep': 0.1, 'StopStep': 0.4}"
    val text = "cat\nSteps: 20, Seed: 4, Size: 512x512, CHG: \"" + chg + "\""
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val result = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)
    val on = result("always_on")("characteristic_guidance")
    assertEquals((on("enabled").bool, on("reg_ini").num, on("ite").num, on("noise_base").num), (true, 5.0, 30.0, 1.0))
    assertEquals((on("controlnet_mode").str, on("stop_step").num), ("More Prompt", 0.4))
    assert(!result("unapplied_fields").obj.contains("CHG"))
    val malformed = dir.resolve("chg-bad.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), malformed,
      Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512, CHG: \"{'RegS': 'x'}\""))
    val bad = InspectGenerationMetadata.inspect(malformed.toString, new PathPolicy(() => Seq(dir)), dir)
    assert(!bad("always_on").obj.contains("characteristic_guidance"))
    assert(bad("unapplied_fields").obj.contains("CHG"))
  }

  test("replay rejects non-finite variation strength") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("non-finite.png")
    val policy = new PathPolicy(() => Seq(dir))
    for value <- Seq("NaN", "Infinity", "-Infinity") do
      PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path,
        Seq("parameters" -> s"cat\nSteps: 20, Seed: 4, Size: 512x512, Variation seed: 8, Variation seed strength: $value"))
      val error = intercept[ToolError](InspectGenerationMetadata.inspect(path.toString, policy, dir))
      assert(error.getMessage.contains("non-finite"), error.getMessage)
  }

  test("replay enforces per-batch and total MCP pixel limits") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("work-limit.png")
    val policy = new PathPolicy(() => Seq(dir))
    val cases = Seq(
      "cat\nSteps: 20, Seed: 4, Size: 4096x4096, Batch size: 2" -> "batch_size",
      "cat\nSteps: 20, Seed: 4, Size: 2048x2112, Batch size: 2, Batch count: 8" -> "in total"
    )
    for (text, message) <- cases do
      PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
      val error = intercept[ToolError](InspectGenerationMetadata.inspect(path.toString, policy, dir))
      assert(error.getMessage.contains(message), error.getMessage)
  }

  test("malformed values behind default-disabled candidates remain unapplied") {
    import sdfx.core.infotext.Img2ImgHiresMetadata
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("invalid-candidates.png")
    val text = s"cat\nSteps: 20, Seed: 4, Size: 512x512, Conditioning Highres Fix: invalid, ${Img2ImgHiresMetadata.key}: not-json, Grid image numbers: maybe, Grid XYZ info: true"
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val result = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)
    val unapplied = result("unapplied_fields").obj
    assert(unapplied.contains("Conditioning Highres Fix"))
    assert(unapplied.contains(Img2ImgHiresMetadata.key))
    assert(unapplied.contains(sdfx.core.image.GridNumbers.NumbersKey))
    assert(!unapplied.contains(sdfx.core.image.GridNumbers.XyzKey))
  }

  test("rejects outside paths, UNC, malformed inputs and missing infotext") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val outside = Files.createTempFile("mcp-outside-", ".png")
    val image = dir.resolve("empty.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), image, Nil)
    val policy = new PathPolicy(() => Seq(dir))
    intercept[ToolError](InspectGenerationMetadata.inspect(outside.toString, policy, dir))
    intercept[ToolError](InspectGenerationMetadata.inspect("\\\\example\\share\\x.png", policy, dir))
    intercept[ToolError](InspectGenerationMetadata.inspect("not_base64!", policy, dir))
    intercept[ToolError](InspectGenerationMetadata.inspect(image.toString, policy, dir))
  }

  test("untrusted model metadata stays descriptive and is never a checkpoint argument") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("image.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path,
      Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512, Model: ../../secret"))
    val result = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)
    assert(!result("generation_args").obj.contains("checkpoint"))
    assertEquals(result("unapplied_fields")("Model").str, "../../secret")
  }

  test("tool schema rejects unknown arguments and returns structured data") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("image.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path,
      Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512"))
    val tool = new InspectGenerationMetadataTool(new PathPolicy(() => Seq(dir)), dir)
    assertEquals(tool.name, "inspect_generation_metadata")
    assertEquals(tool.call(ujson.Obj("image" -> path.toString), ToolContext.none).structured.get("generation_args")("prompt").str, "cat")
    intercept[ToolError](tool.call(ujson.Obj("image" -> path.toString, "execute" -> true), ToolContext.none))
    intercept[ToolError](tool.call(ujson.Obj("image" -> 123), ToolContext.none))
  }

  test("generation parameters outside MCP limits are rejected") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("oversized.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path,
      Seq("parameters" -> "cat\nSteps: 999, Seed: 4, Size: 512x512"))
    val error = intercept[ToolError](InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir))
    assert(error.getMessage.contains("steps"))
  }

  test("protected PNG metadata is not exposed") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("protected.png")
    sdfx.core.png.ScrambledPng.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path,
      Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512"), Some("private"))
    val error = intercept[ToolError](InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir))
    assert(!error.getMessage.contains("private"))
  }

  test("compressed PNG parameters are read with a bounded expansion") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("image.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Nil)
    val original = Files.readAllBytes(path)
    def chunk(text: String): Array[Byte] =
      val compressed = new java.io.ByteArrayOutputStream()
      val deflater = new java.util.zip.DeflaterOutputStream(compressed)
      deflater.write(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)); deflater.close()
      val payload = "parameters".getBytes(java.nio.charset.StandardCharsets.US_ASCII) ++ Array[Byte](0, 0) ++ compressed.toByteArray
      val kind = "zTXt".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      val crc = new java.util.zip.CRC32(); crc.update(kind); crc.update(payload)
      java.nio.ByteBuffer.allocate(12 + payload.length).putInt(payload.length).put(kind).put(payload).putInt(crc.getValue.toInt).array()
    def image(text: String): Array[Byte] = original.dropRight(12) ++ chunk(text) ++ original.takeRight(12)
    val policy = new PathPolicy(() => Seq(dir))
    val encoded = Base64.getEncoder.encodeToString(image("cat\nSteps: 20, Seed: 4, Size: 512x512"))
    assertEquals(InspectGenerationMetadata.inspect(encoded, policy, dir)("generation_args")("prompt").str, "cat")
    val bomb = Base64.getEncoder.encodeToString(image("x" * (1024 * 1024 + 1)))
    assert(intercept[ToolError](InspectGenerationMetadata.inspect(bomb, policy, dir)).getMessage.contains("1 MiB"))
  }

  test("variation, manual adapter and independent Hires metadata become typed replay arguments") {
    import sdfx.core.model.{GenerationParams, HiresParams, LoraRef}
    import sdfx.core.infotext.{Infotext, ImageFacts}
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("replay.png")
    val p = GenerationParams(prompt = "cat", seed = 42, subseed = 77, subseedStrength = 0.4, eta = Some(0.3),
      loras = Seq(LoraRef("adapter", 0.7, Some(0.5), Some("ALL"), Some(0.2), Some(0.8), Some(4))),
      hires = HiresParams(enabled = true, prompt = "detail", negativePrompt = "blur", samplerName = Some("Euler a"), scheduler = Some("Karras"), cfgScale = Some(4.5)))
    val text = Infotext.write(p, ImageFacts(42L, subseed = 77L))
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val a = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)("generation_args")
    assertEquals(a("subseed").num, 77.0); assertEquals(a("subseed_strength").num, 0.4)
    assertEquals(a("eta").num, 0.3); assertEquals(a("manual_loras")(0)("dyn").num, 4.0)
    assertEquals(a("hires")("prompt").str, "detail"); assertEquals(a("hires")("negative_prompt").str, "blur")
    assertEquals(a("hires")("cfg_scale").num, 4.5)
  }

  test("FreeU, HyperTile and PAG candidates reproduce the native plugin output") {
    import sdfx.core.model.GenerationParams
    import sdfx.core.infotext.{Infotext, ImageFacts}
    import sdfx.core.plugins.api.Values
    val sources = Seq(
      new sdfx.plugins.FreeUPlugin -> Values(Map("enabled" -> "true", "b1" -> "1.15", "b2" -> "1.2", "s1" -> "0.8", "s2" -> "0.7")),
      new sdfx.plugins.HyperTilePlugin -> Values(Map("enabled" -> "true", "tile_size" -> "256", "swap_size" -> "2", "max_depth" -> "3", "scale_depth" -> "true")),
      new sdfx.plugins.PagPlugin -> Values(Map("enabled" -> "true", "scale" -> "2.5", "adaptive_scale" -> "0.3", "layers" -> "mid, up")))
    val params = sources.foldLeft(GenerationParams(prompt = "cat")) { case (p, (plugin, values)) => plugin.process(p, values) }
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("replay.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> Infotext.write(params, ImageFacts(42L))))
    val inspected = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)
    val result = inspected("always_on")
    assertEquals(result("freeu")("b1").num, 1.15); assertEquals(result("freeu")("s2").num, 0.7)
    assertEquals(result("hypertile")("max_depth").num, 3.0); assert(result("hypertile")("scale_depth").bool)
    assertEquals(result("pag")("layers").str, "mid, up"); assertEquals(result("pag")("scale").num, 2.5)
    assert(!inspected("unapplied_fields").obj.contains("FreeU Stages"))
    sources.foreach { (plugin, _) =>
      val values = Values(result(plugin.id).obj.toSeq.map { (k, value) =>
        k -> (value match
          case ujson.Str(v) => v
          case ujson.Bool(v) => v.toString
          case ujson.Num(v) => sdfx.core.plugins.api.Values.fmt(v)
          case _ => fail("invalid candidate type"))
      }.toMap)
      assertEquals(plugin.process(GenerationParams(), values).hooks(plugin.id), params.hooks(plugin.id))
    }
  }

  test("NudeNet NSFW Censor candidate reproduces the native hook and consumes its key") {
    import sdfx.core.model.GenerationParams
    import sdfx.core.infotext.{Infotext, ImageFacts}
    import sdfx.core.plugins.api.Values
    val plugin = new sdfx.plugins.NsfwCheckerPlugin
    val values = Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true", "gen_filter_type" -> "Fill color",
      "save_mask" -> "true", "threshold_Face_female" -> "0.3", "horizontal_Face_female" -> "1.4", "rectangle_round_radius" -> "-6"))
    val params = plugin.process(GenerationParams(prompt = "portrait"), values)
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("nudenet.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> Infotext.write(params, ImageFacts(42L))))
    val inspected = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)
    val candidate = inspected("always_on")("nsfw_checker")
    assertEquals(candidate("enabled").bool, true)
    assertEquals(candidate("gen_filter_type").str, "Fill color")
    assertEquals(candidate("threshold_Face_female").num, 0.3)
    assert(!inspected("unapplied_fields").obj.contains(sdfx.plugins.NsfwCheckerPlugin.InfotextKey))
    val restored = Values(candidate.obj.toSeq.map { (k, value) =>
      k -> (value match
        case ujson.Str(v) => v
        case ujson.Bool(v) => v.toString
        case ujson.Num(v) => Values.fmt(v)
        case _ => fail("invalid candidate type"))
    }.toMap)
    assertEquals(plugin.process(GenerationParams(), restored).hooks(plugin.id), params.hooks(plugin.id))
    val plain = dir.resolve("plain.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), plain, Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512"))
    assert(!InspectGenerationMetadata.inspect(plain.toString, new PathPolicy(() => Seq(dir)), dir)("always_on").obj.contains("nsfw_checker"))
  }

  test("SD safety checker and NSFWJS candidate reproduces the hook without NudeNet and consumes both keys") {
    import sdfx.core.model.GenerationParams
    import sdfx.core.infotext.{Infotext, ImageFacts}
    import sdfx.core.plugins.api.Values
    val plugin = new sdfx.plugins.NsfwCheckerPlugin
    val values = Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true", "nudenet" -> "false",
      "safety_checker" -> "true", "nsfwjs" -> "true", "nsfwjs_safety_level" -> "questionable"))
    val params = plugin.process(GenerationParams(prompt = "portrait"), values)
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("classifiers.png")
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> Infotext.write(params, ImageFacts(42L))))
    val inspected = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)
    val candidate = inspected("always_on")("nsfw_checker")
    assertEquals((candidate("nudenet").bool, candidate("safety_checker").bool, candidate("nsfwjs").bool), (false, true, true))
    assertEquals(candidate("nsfwjs_safety_level").str, "questionable")
    for key <- sdfx.plugins.NsfwCheckerPlugin.InfotextKeys do assert(!inspected("unapplied_fields").obj.contains(key), key)
    val restored = Values(candidate.obj.toSeq.map { (k, value) =>
      k -> (value match
        case ujson.Str(v) => v
        case ujson.Bool(v) => v.toString
        case ujson.Num(v) => Values.fmt(v)
        case _ => fail("invalid candidate type"))
    }.toMap)
    assertEquals(plugin.process(GenerationParams(), restored).hooks(plugin.id), params.hooks(plugin.id))
  }

  test("unknown FreeU version and malformed or out-of-range values stay unapplied") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("replay.png")
    for version <- Seq("3", "invalid") do
      val text = s"cat\nSteps: 20, Seed: 4, Size: 512x512, FreeU Version: $version, FreeU Stages: bad"
      PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
      val result = InspectGenerationMetadata.inspect(path.toString, new PathPolicy(() => Seq(dir)), dir)
      assert(!result("always_on").obj.contains("freeu"))
      assert(result("unapplied_fields").obj.contains("FreeU Stages"))
    assertEquals(PluginMetadataCandidates.freeu(Map("FreeU Version" -> "2", "FreeU Stages" ->
      "[{\"backbone_factor\":99,\"skip_factor\":1},{\"backbone_factor\":1,\"skip_factor\":1}]")), None)
    assertEquals(PluginMetadataCandidates.hypertile(Map("Hypertile tile size" -> "9999")), None)
    assertEquals(PluginMetadataCandidates.pag(Map("PAG scale" -> "nan", "PAG adaptive scale" -> "0", "PAG applied layers" -> "mid")), None)
  }

  test("Agent Attention source infotext becomes a typed candidate and its keys are consumed") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("agent.png")
    val policy = new PathPolicy(() => Seq(dir))
    val text = "cat\nSteps: 20, Seed: 4, Size: 512x512, AgAt Active: True, AgAt Use Second Pass: True, " +
      "AgAt Second Pass Step: 12, AgAt First Pass sx: 2, AgAt First Pass sy: 2, AgAt First Pass Ratio: 0.5, " +
      "AgAt First Pass Agent Ratio: 0.9, AgAt Second Pass sx: 2, AgAt Second Pass sy: 2, AgAt Second Pass Ratio: 0.4, " +
      "AgAt Second Pass Agent Ratio: 0.5, AgAt Use FP32 Precision: False, AgAt Max Downsample: 2, " +
      "AgAt Apply to Hires. Fix Only: False"
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val result = InspectGenerationMetadata.inspect(path.toString, policy, dir)
    val candidate = result("always_on")("agent_attention")
    assertEquals(candidate("enabled").bool, true)
    assertEquals(candidate("use_sp").bool, true)
    assertEquals(candidate("sp_step").num, 12.0)
    assertEquals(candidate("ratio").num, 0.5)
    assertEquals(candidate("max_downsample").str, "2")
    assert(!result("unapplied_fields").obj.keys.exists(_.startsWith("AgAt ")))
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512"))
    assert(!InspectGenerationMetadata.inspect(path.toString, policy, dir)("always_on").obj.contains("agent_attention"))
  }

  test("Sonar source infotext becomes a typed candidate and its keys are consumed") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("sonar.png")
    val policy = new PathPolicy(() => Seq(dir))
    val text = "lion\nSteps: 20, Seed: 4, Size: 512x512, Sonar sampler: Naive, Sonar momentum: 0.9, " +
      "Sonar momentum_hist: 0.5, Sonar momentum_sign: neg, Sonar ref_img: guide.png, Sonar ref_meth: euler, " +
      "Sonar ref_hgf: 0.3, Sonar ref_start_step: 1, Sonar ref_stop_step: 0.5"
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val result = InspectGenerationMetadata.inspect(path.toString, policy, dir)
    val candidate = result("always_on")("sonar")
    assertEquals(candidate("enabled").bool, true)
    assertEquals(candidate("sampler").str, "Naive")
    assertEquals(candidate("momentum").num, 0.9)
    assertEquals(candidate("momentum_sign").str, "neg")
    assertEquals(candidate("ref_meth").str, "euler")
    assertEquals(candidate("ref_img").str, "")
    val unapplied = result("unapplied_fields").obj.keySet
    assert(!unapplied.exists(k => sdfx.plugins.SonarPlugin.InfotextKeys.contains(k)), unapplied)
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512"))
    assert(!InspectGenerationMetadata.inspect(path.toString, policy, dir)("always_on").obj.contains("sonar"))
  }

  test("Incantations source infotext becomes a typed candidate and its keys are consumed") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("incant.png")
    val policy = new PathPolicy(() => Seq(dir))
    val text = "lion\nSteps: 20, Seed: 4, Size: 512x512, SEG Active: True, SEG Blur Sigma: 11, SEG Start Step: 0, " +
      "SEG End Step: 150, PAG Active: True, PAG SANF: False, PAG Scale: 3, PAG Start Step: 0, PAG End Step: 150, " +
      "CFG Interval Enable: True, CFG Interval Schedule: PCS (s=1.0), CFG Interval Low: 0.28, " +
      "CFG Interval High: 5.42, T2I-0 Active: True, T2I-0 Tokens: \"1, 4\", INCANT Active: True"
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val result = InspectGenerationMetadata.inspect(path.toString, policy, dir)
    val candidate = result("always_on")("incantations")
    assertEquals(candidate("enabled").bool, true)
    assertEquals(candidate("seg_active").bool, true)
    assertEquals(candidate("pag_sanf").bool, false)
    assertEquals(candidate("pag_scale").num, 3.0)
    assertEquals(candidate("cfg_interval_schedule").str, "PCS (s=1.0)")
    assertEquals(candidate("t2i0_tokens").str, "1, 4")
    assertEquals(candidate("scfg_active").bool, false)
    val unapplied = result("unapplied_fields").obj.keySet
    assert(!unapplied.exists(k => sdfx.plugins.IncantationsPlugin.InfotextKeys.contains(k)), unapplied)
    assert(unapplied.contains("INCANT Active"), unapplied) // Seek for Incantations is not provided
    assert(!result("always_on").obj.contains("pag")) // the native PAG plugin uses other keys
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512"))
    assert(!InspectGenerationMetadata.inspect(path.toString, policy, dir)("always_on").obj.contains("incantations"))
  }

  test("Old SD firstpasser infotext becomes a typed script candidate and its keys are consumed") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("firstpass.png")
    val policy = new PathPolicy(() => Seq(dir))
    val text = "lion\nSteps: 20, Seed: 4, Size: 1024x768, Old SD firstpasser: SD1, Old SD firstpasser steps: 14, " +
      "Old SD firstpasser denoising: 0.45, Old SD firstpasser upscaler: Lanczos, Old SD firstpasser checkpoint: old.safetensors, " +
      "Old SD firstpasser model hash: abcd1234, Old SD firstpasser size: 680x512, Old SD firstpasser resolution: 0, " +
      "Old SD firstpasser main LoRAs: False"
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val result = InspectGenerationMetadata.inspect(path.toString, policy, dir)
    val candidate = result("scripts")("old_sd_firstpasser")
    assertEquals(candidate("network_type").str, "SD1")
    assertEquals(candidate("steps").num, 14.0)
    assertEquals(candidate("denoising").num, 0.45)
    assertEquals(candidate("sd1_checkpoint").str, "old.safetensors")
    assertEquals(candidate("sdxl_checkpoint").str, "")
    assertEquals(candidate("main_loras").bool, false)
    val unapplied = result("unapplied_fields").obj.keySet
    assert(!unapplied.exists(_.startsWith("Old SD firstpasser")), unapplied)
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512"))
    assert(InspectGenerationMetadata.inspect(path.toString, policy, dir)("scripts").obj.isEmpty)
  }

  test("Aesthetic Gradients infotext becomes a typed candidate and its keys are consumed") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("aesthetic.png")
    val policy = new PathPolicy(() => Seq(dir))
    val text = "lion\nSteps: 20, Seed: 4, Size: 512x512, Aesthetic LR: 0.0001, Aesthetic weight: 0.7, Aesthetic steps: 12, " +
      "Aesthetic embedding: sunset, Aesthetic slerp: False"
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val result = InspectGenerationMetadata.inspect(path.toString, policy, dir)
    val candidate = result("always_on")("aesthetic_gradients")
    assertEquals(candidate("enabled").bool, true)
    assertEquals(candidate("embedding").str, "sunset")
    assertEquals(candidate("weight").num, 0.7)
    assertEquals(candidate("steps").num, 12.0)
    assert(!result("unapplied_fields").obj.keySet.exists(k => sdfx.plugins.AestheticGradientsPlugin.InfotextKeys.contains(k)))
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512"))
    assert(!InspectGenerationMetadata.inspect(path.toString, policy, dir)("always_on").obj.contains("aesthetic_gradients"))
  }

  test("LLuL source infotext becomes a typed candidate and its keys are consumed") {
    val dir = Files.createTempDirectory("mcp-inspect-")
    val path = dir.resolve("llul.png")
    val policy = new PathPolicy(() => Seq(dir))
    val text = "lion\nSteps: 20, Seed: 4, Size: 512x512, LLuL Enabled: True, LLuL Multiply: 4, LLuL Weight: 0.3, " +
      "LLuL Layers: \"['OUT']\", LLuL Apply to: \"['out']\", LLuL Start steps: 5, LLuL Max steps: 20, " +
      "LLuL Upscaler: bilinear, LLuL Downscaler: bilinear, LLuL Interpolation: lerp, LLuL x: 192, LLuL y: 192"
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> text))
    val result = InspectGenerationMetadata.inspect(path.toString, policy, dir)
    val candidate = result("always_on")("llul")
    assertEquals(candidate("enabled").bool, true)
    assertEquals(candidate("multiply").num, 2.0)
    assertEquals(candidate("weight").num, 0.3)
    assertEquals(candidate("x").str, "192")
    assert(!result("unapplied_fields").obj.keySet.exists(k => sdfx.plugins.LlulPlugin.InfotextKeys.contains(k)))
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), path, Seq("parameters" -> "cat\nSteps: 20, Seed: 4, Size: 512x512"))
    assert(!InspectGenerationMetadata.inspect(path.toString, policy, dir)("always_on").obj.contains("llul"))
  }
