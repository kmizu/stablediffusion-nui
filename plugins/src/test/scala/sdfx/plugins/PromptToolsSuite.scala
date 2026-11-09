package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import java.nio.file.Files
import scala.util.Random

class PromptToolsSuite extends munit.FunSuite:
  private def vals(p: Plugin, m: Map[String, String]) = Values(Values.defaults(p.controls).map ++ m)

  private val lib =
    """Quality:
      |  best: "masterpiece, best quality"
      |  bad:
      |    value: "ugly"
      |    negative: "lowres, blurry"
      |hair: "silver hair"
      |""".stripMargin

  test("library: tokens expand by full path or unique leaf, unknown is rejected") {
    val items = PromptLibrary.parse(lib)
    assertEquals(items.map(_.name), Vector("Quality/best", "Quality/bad", "hair"))
    assertEquals(PromptLibrary.expand(items, "1girl, [[hair]], [[Quality/best]]")._1, "1girl, silver hair, masterpiece, best quality")
    intercept[IllegalArgumentException](PromptLibrary.expand(items, "[[nope]]"))
    assertEquals(PromptLibrary.search(items, "SILVER").map(_.name), Seq("hair"))
  }

  test("library run: negative of used entries is merged; save never overwrites") {
    val f = Files.createTempDirectory("pl").resolve("lib.yaml"); Files.writeString(f, lib)
    val ctx = new FakeContext(); val p = new PromptLibraryPlugin
    p.run(GenerationParams(prompt = "x [[bad]]", negativePrompt = "n"), vals(p, Map("file" -> f.toString)), ctx)
    assertEquals((ctx.generated.head.prompt, ctx.generated.head.negativePrompt), ("x ugly", "n, lowres, blurry"))
    val save = vals(p, Map("file" -> f.toString, "action" -> "Save current prompt", "query" -> "mine"))
    p.run(GenerationParams(prompt = "a \"b\", c"), save, new FakeContext())
    assertEquals(PromptLibrary.parse(Files.readString(f)).last.value, "a \"b\", c")
    intercept[IllegalArgumentException](p.run(GenerationParams(prompt = "z"), save, new FakeContext()))
  }

  test("scenario: variables, base merge and per-scene params") {
    val y =
      """base: "masterpiece, ${hero}"
        |negative: "lowres"
        |variables:
        |  hero: "silver hair"
        |  place: "beach"
        |scenes:
        |  - name: dawn
        |    prompt: "sunrise at ${place}"
        |    steps: 31
        |  - name: night
        |    prompt: "stars"
        |    hero: "red hair"
        |""".stripMargin
    val s = Scenario.parse(y)
    assertEquals(s.map(_.prompt), Vector("masterpiece, silver hair, sunrise at beach", "masterpiece, red hair, stars"))
    assertEquals(Scenario(GenerationParams(steps = 20), s(0)).steps, 31)
    assertEquals(Scenario(GenerationParams(steps = 20), s(1)).steps, 20)
    intercept[IllegalArgumentException](Scenario.parse("scenes:\n  - prompt: \"${zzz}\"\n"))
  }

  test("scenario run generates one image per scene") {
    val f = Files.createTempDirectory("sc").resolve("s.yaml")
    Files.writeString(f, "scenes:\n  - name: a\n    prompt: \"one\"\n  - name: b\n    prompt: \"two\"\n")
    val ctx = new FakeContext(); val p = new ScenarioLoaderPlugin
    p.run(GenerationParams(), vals(p, Map("file" -> f.toString, "only" -> "b")), ctx)
    assertEquals(ctx.generated.map(_.prompt), Seq("two"))
  }

  test("generator: seeded chain extends without repeats and stops at dead ends") {
    val chain = PromptGenerator.train(Seq("1girl, smile, outdoors, sky", "1girl, hat, outdoors"))
    val a = PromptGenerator.extend(chain, Vector("1girl"), 5, new Random(1))
    assertEquals(a, PromptGenerator.extend(chain, Vector("1girl"), 5, new Random(1)))
    assertEquals(a.distinct, a)
    assertEquals(PromptGenerator.extend(chain, Vector("unknown"), 5, new Random(1)), Vector("unknown"))
    assert(a.head == "1girl" && a.size > 1)
  }

  test("generator run: one image per variant, base tags kept") {
    val f = Files.createTempDirectory("pg2").resolve("c.txt"); Files.writeString(f, "1girl, smile, sky\n1girl, hat\n")
    val ctx = new FakeContext(); val p = new PromptGeneratorPlugin
    p.run(GenerationParams(prompt = "1girl"), vals(p, Map("method" -> "Markov (corpus file)", "corpus" -> f.toString, "count" -> "3", "seed" -> "5")), ctx)
    assertEquals(ctx.generated.size, 3)
    assert(ctx.generated.forall(_.prompt.startsWith("1girl")))
  }
