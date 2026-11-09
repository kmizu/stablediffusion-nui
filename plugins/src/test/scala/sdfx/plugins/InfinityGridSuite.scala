package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import java.nio.file.Files

class InfinityGridSuite extends munit.FunSuite:
  private val yaml =
    """axes:
      |  steps:
      |    values: "10, 20"
      |  prompt:
      |    values: "red hair | blue hair"
      |  cfg:
      |    values: "5, 8"
      |""".stripMargin

  test("axes parse with , or | separators; unsupported names and empty axes are rejected") {
    val a = InfinityGrid.parse(yaml)
    assertEquals(a.map(x => x.name -> x.values), Vector("steps" -> Vector("10", "20"), "prompt" -> Vector("red hair", "blue hair"), "cfg" -> Vector("5", "8")))
    intercept[IllegalArgumentException](InfinityGrid.parse("axes:\n  banana:\n    values: \"1\"\n"))
    intercept[IllegalArgumentException](InfinityGrid.parse("other: 1\n"))
  }

  test("combinations apply to the params; prompt values are appended to the base prompt") {
    val a = InfinityGrid.parse(yaml)
    val all = InfinityGrid.combos(a)
    assertEquals(all.size, 8)
    val q = InfinityGrid(GenerationParams(prompt = "1girl,"), all(3))
    assertEquals((q.steps, q.prompt, q.cfgScale), (10, "1girl, blue hair", 8.0))
  }

  test("run generates every cell once with one fixed seed and returns one grid page per value of axis 3") {
    val f = Files.createTempFile("ig", ".yaml"); Files.writeString(f, yaml)
    val ctx = new FakeContext(); val p = new InfinityGridPlugin
    val out = p.run(GenerationParams(prompt = "1girl", seed = 9), Values(Values.defaults(p.controls).map ++ Map("file" -> f.toString)), ctx)
    assertEquals(ctx.generated.size, 8)
    assertEquals(ctx.generated.map(_.seed).distinct, Seq(9L))
    assertEquals(out.grids.size, 2)
    assertEquals(out.images.size, 8)
  }

  test("the image limit stops oversized grids before any generation") {
    val f = Files.createTempFile("ig", ".yaml"); Files.writeString(f, yaml)
    val ctx = new FakeContext(); val p = new InfinityGridPlugin
    intercept[IllegalArgumentException](p.run(GenerationParams(), Values(Values.defaults(p.controls).map ++ Map("file" -> f.toString, "max_images" -> "4")), ctx))
    assertEquals(ctx.generated.size, 0)
  }
