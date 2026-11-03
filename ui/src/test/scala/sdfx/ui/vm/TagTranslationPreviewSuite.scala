package sdfx.ui.vm
import munit.FunSuite
class TagTranslationPreviewSuite extends FunSuite:
  test("longest translated phrase wins, matching underscores and case with original spans") {
    val text = "A BLUE_HAIR girl, blue hair"
    val hits = TagTranslationPreview.find(text, Map("blue" -> "blue-ja", "blue hair" -> "hair-ja", "girl" -> "girl-ja"))
    assertEquals(hits.map(_.translation), Vector("hair-ja", "girl-ja", "hair-ja"))
    assertEquals(hits.map(h => text.substring(h.start, h.end)), Vector("BLUE_HAIR", "girl", "blue hair"))
  }
  test("prefers up to three words, skips punctuation and does not match parts of words") {
    val text = "red cat ears, cat-ear, scat"
    val hits = TagTranslationPreview.find(text, Map("red cat ears" -> "three", "cat" -> "cat-ja", "cat ears" -> "ears"))
    assertEquals(hits.map(_.translation), Vector("three", "cat-ja"))
  }
  test("empty and missing translations are not clickable matches") {
    assertEquals(TagTranslationPreview.find("blue hair", Map("blue" -> "")), Vector.empty)
  }
  test("punctuation breaks phrases: only their matching word parts are shown") {
    val text = "artist-name, red_(dress), (blue hair:1.2)"
    val hits = TagTranslationPreview.find(text, Map("artist-name" -> "full", "artist" -> "part", "red (dress)" -> "full-red", "dress" -> "dress", "blue hair" -> "hair"))
    assertEquals(hits.map(_.translation), Vector("part", "dress", "hair"))
    assertEquals(hits.map(h => text.substring(h.start, h.end)), Vector("artist", "dress", "blue hair"))
  }
  test("empty huge input and result caps stay bounded") {
    val hits = TagTranslationPreview.find(List.fill(300)("blue").mkString(","), Map("blue" -> "blue"))
    assertEquals(hits.size, TagTranslationPreview.MaxHits)
    assertEquals(TagTranslationPreview.find("x" * TagTranslationPreview.MaxChars + " blue", Map("blue" -> "blue")), Vector.empty)
  }
  test("large dictionary lookup and long labels remain bounded") {
    val parseStart = System.nanoTime()
    val lines = (0 until 300000).iterator.map(i => s"term$i,translation$i") ++ Iterator("blue hair," + ("x" * 1000000))
    val dict = CompletionEngine.parseTranslations(lines, legacy = false)
    val parseElapsed = (System.nanoTime() - parseStart) / 1000000
    println(s"TAG_PREVIEW_300K_PARSE_MS=$parseElapsed")
    val start = System.nanoTime()
    val hit = TagTranslationPreview.find("blue hair", dict).head
    val elapsed = (System.nanoTime() - start) / 1000000
    println(s"TAG_PREVIEW_300K_LOOKUP_MS=$elapsed")
    val rendered = TagTranslationPreview.display(hit.translation)
    assertEquals(rendered.codePointCount(0, rendered.length), TagTranslationPreview.MaxLabelCodePoints + 1)
    val face = "\uD83D\uDE00"
    val unicode = TagTranslationPreview.display(face * 121)
    assertEquals(unicode.codePointCount(0, unicode.length), 121)
    assert(unicode.endsWith("…"))
  }
  test("old tag settings default preview off; enabled setting survives round trip") {
    val old = sdfx.core.json.SnakePickle.read[sdfx.core.config.TagCompleteSettings]("{}")
    assert(!old.showTranslationPreview)
    val enabled = old.copy(showTranslationPreview = true)
    assertEquals(sdfx.core.json.SnakePickle.read[sdfx.core.config.TagCompleteSettings](sdfx.core.json.SnakePickle.write(enabled)), enabled)
  }
