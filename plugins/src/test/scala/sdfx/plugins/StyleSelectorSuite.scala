package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import java.nio.file.Files

class StyleSelectorSuite extends munit.FunSuite:
  private val json =
    """[{"name":"base","prompt":"{prompt}","negative_prompt":""},
      | {"name":"Comic Book","prompt":"comic {prompt} . graphic illustration, vibrant","negative_prompt":"photograph, noisy"},
      | {"name":"Neon","prompt":"neon lights","negative_prompt":"dull"}]""".stripMargin

  private def file(name: String, text: String) =
    val d = Files.createTempDirectory("ss"); val f = d.resolve(name); Files.writeString(f, text); f

  test("JSON parses, CSV parses, duplicates and bad shapes are errors") {
    assertEquals(StyleSelector.load(file("a.json", json).toString).map(_.name), Vector("base", "Comic Book", "Neon"))
    val csv = "name,prompt,negative_prompt\r\nA,\"x {prompt}\",y\r\n"
    assertEquals(StyleSelector.load(file("a.csv", csv).toString).head.negativePrompt, "y")
    intercept[IllegalArgumentException](StyleSelector.load(file("d.json", """[{"name":"a"},{"name":"a"}]""").toString))
    intercept[IllegalArgumentException](StyleSelector.parseJson("""{"name":"a"}"""))
    intercept[IllegalArgumentException](StyleSelector.parseJson("""[{"prompt":"x"}]"""))
  }

  test("unknown style names fail instead of being skipped; case-insensitive match works") {
    val all = StyleSelector.parseJson(json)
    assertEquals(StyleSelector.pick(all, Seq("neon")).map(_.name), Seq("Neon"))
    intercept[IllegalArgumentException](StyleSelector.pick(all, Seq("missing")))
  }

  test("run applies templates in order, one generation per style in per-style mode") {
    val f = file("s.json", json)
    val p = new StyleSelectorPlugin
    val v = Values(Values.defaults(p.controls).map ++ Map("styles_file" -> f.toString, "styles" -> "Comic Book, Neon",
      "mode" -> "One image per style"))
    val ctx = new FakeContext()
    p.run(GenerationParams(prompt = "1girl", negativePrompt = "blurry"), v, ctx)
    assertEquals(ctx.generated.map(_.prompt), Seq("comic 1girl . graphic illustration, vibrant", "1girl, neon lights"))
    assertEquals(ctx.generated.map(_.negativePrompt), Seq("blurry, photograph, noisy", "blurry, dull"))
    val c2 = new FakeContext()
    p.run(GenerationParams(prompt = "1girl"), Values(v.map + ("mode" -> "Apply all selected styles to one image")), c2)
    assertEquals(c2.generated.map(_.prompt), Seq("comic 1girl . graphic illustration, vibrant, neon lights"))
  }
