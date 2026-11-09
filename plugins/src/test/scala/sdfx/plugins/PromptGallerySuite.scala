package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import java.nio.file.Files

class PromptGallerySuite extends munit.FunSuite:
  // The two YAML examples are the ones in the upstream README (format reference).
  private val tags =
    """Figure:
      |  Hair:
      |    ponny-tail:
      |      value: "ponny-tail"
      |      negative: "lowres, bad anatomy, worst quality"
      |  Face:
      |    smile:
      |      value: "smile"
      |Style:
      |  General Effect:
      |    General_ice_high_res:
      |      value: "flowing ice, portrait, focus on face, best quality"
      |      negative: "lowres, bad anatomy, worst quality"
      |      param: "Steps: 30, Sampler: Euler, CFG scale: 11.5, Size: 512x768"
      |""".stripMargin
  private val avatars =
    """whiteHair:
      |  value: "1girl, white hair, red eyes, animal ears, looking at viewer"
      |  negative: ""
      |pinkGirl:
      |  value: "1girl, solo, pink hair, very long hair, outdoors, flower field"
      |""".stripMargin

  test("nested categories are walked and param is parsed") {
    val s = PromptGallery.parseSets(tags)
    assertEquals(s.map(_.name), Vector("Figure/Hair/ponny-tail", "Figure/Face/smile", "Style/General Effect/General_ice_high_res"))
    assertEquals(s(2).param, Map("steps" -> "30", "sampler" -> "Euler", "cfg scale" -> "11.5", "size" -> "512x768"))
    assertEquals(PromptGallery.parseSets(avatars).map(_.name), Vector("whiteHair", "pinkGirl"))
  }

  test("find accepts a full path or a unique leaf, rejects unknown names") {
    val s = PromptGallery.parseSets(tags)
    assertEquals(PromptGallery.find(s, "smile").name, "Figure/Face/smile")
    assertEquals(PromptGallery.find(s, "Figure/Hair/ponny-tail").value, "ponny-tail")
    intercept[IllegalArgumentException](PromptGallery.find(s, "nope"))
  }

  test("compose joins avatar, sets and the base prompt without doubled commas") {
    val s = PromptGallery.parseSets(tags); val a = PromptGallery.parseSets(avatars)
    val (pos, neg) = PromptGallery.compose(Some(a(0)), Seq(s(0), s(1)), "masterpiece,", "blurry")
    assertEquals(pos, "1girl, white hair, red eyes, animal ears, looking at viewer, ponny-tail, smile, masterpiece")
    assertEquals(neg, "lowres, bad anatomy, worst quality, blurry")
  }

  test("run: one generation per prompt-set, with Steps and CFG scale from param") {
    val dir = Files.createTempDirectory("pg"); val f = dir.resolve("tags.yaml"); Files.writeString(f, tags)
    val ctx = new FakeContext()
    val p = new PromptGalleryPlugin
    val v = Values(Values.defaults(p.controls).map ++ Map("tags_file" -> f.toString,
      "sets" -> "smile, General_ice_high_res", "mode" -> "One image per prompt-set"))
    p.run(GenerationParams(prompt = "base", steps = 20, cfgScale = 7.0), v, ctx)
    assertEquals(ctx.generated.map(_.prompt), Seq("smile, base", "flowing ice, portrait, focus on face, best quality, base"))
    assertEquals((ctx.generated(1).steps, ctx.generated(1).cfgScale), (30, 11.5))
    assertEquals((ctx.generated(0).steps, ctx.generated(0).cfgScale), (20, 7.0))
  }
