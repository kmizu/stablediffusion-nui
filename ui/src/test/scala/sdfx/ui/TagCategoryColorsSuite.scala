package sdfx.ui

import javafx.scene.paint.Color
import sdfx.ui.vm.TagCategoryColors

class TagCategoryColorsSuite extends munit.FunSuite:
  test("source maps and dark/light colors remain distinct") {
    val p = TagCategoryColors.parse("""{"danbooru":{"1":["red","blue"]},"extra":{"1":["#00ff00","black"]}}""")
    assertEquals(TagCategoryColors.color(p, Some("danbooru"), 1, false), Some(Color.RED))
    assertEquals(TagCategoryColors.color(p, Some("danbooru"), 1, true), Some(Color.BLUE))
    assertEquals(TagCategoryColors.color(p, Some("extra"), 1, false), Some(Color.LIME))
    assertEquals(TagCategoryColors.color(p, Some("extra"), 1, true), Some(Color.BLACK))
    assertEquals(TagCategoryColors.color(p, Some("extra"), 3, false), None)
    assert(TagCategoryColors.color(p, Some("other"), 3, false).nonEmpty)
    assertEquals(TagCategoryColors.color(p, Some("other"), 99, false), None)
    assertEquals(TagCategoryColors.stem(Some("C:\\tags\\danbooru.csv")), Some("danbooru"))
    assertEquals(TagCategoryColors.stem(Some("/tags/my.set.csv")), Some("my.set"))
  }
  test("invalid mappings are rejected") {
    for json <- Seq("{bad", "[]", """{"x":{"1":["red"]}}""", """{"x":{"bad":["red","blue"]}}""",
      """{"x":{"1":["no-such-colour","blue"]}}""", """{"../secret":{"1":["red","blue"]}}""") do
      intercept[Exception](TagCategoryColors.parse(json))
    intercept[IllegalArgumentException](TagCategoryColors.parse(" " * 65537))
    assertEquals(TagCategoryColors.parse(""), Map.empty)
  }
