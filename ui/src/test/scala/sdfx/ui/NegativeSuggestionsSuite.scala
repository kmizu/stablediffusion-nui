package sdfx.ui

import sdfx.ui.vm.NegativeSuggestions

class NegativeSuggestionsSuite extends munit.FunSuite:
  test("suggestions are opt-in, distinct short text rather than installed embeddings") {
    assert(NegativeSuggestions.items.size >= 8)
    assertEquals(NegativeSuggestions.items.distinct.size, NegativeSuggestions.items.size)
    assert(NegativeSuggestions.items.contains("blurry"))
    assert(NegativeSuggestions.items.forall(t => !t.contains("<") && !t.contains(",")))
  }

  test("named sets are nonempty and use only known editable candidates") {
    assert(NegativeSuggestions.presets.exists(_._1 == "Quality"))
    assert(NegativeSuggestions.presets.size >= 3)
    assert(NegativeSuggestions.presets.forall { case (_, tags) => tags.nonEmpty && tags.forall(NegativeSuggestions.items.contains) })
  }

  test("append preserves every existing byte and skips only exact top-level duplicates") {
    val input = "(bad anatomy:1.2), blurry,\n  "
    val suffix = NegativeSuggestions.suffix(input, Seq("BLURRY", "watermark", "watermark", "text"))
    assertEquals(input + suffix, input + "watermark, text")
    assertEquals(NegativeSuggestions.suffix(input, Seq("blurry")), "")
    assertEquals(NegativeSuggestions.suffix("(blurry:1.2)", Seq("blurry")), ", blurry")
    assertEquals(NegativeSuggestions.suffix("", Seq("bad anatomy", "text")), "bad anatomy, text")
    assertEquals(NegativeSuggestions.suffix("  ", Seq("blurry")), "blurry")
    assertEquals(NegativeSuggestions.suffix("line\n", Nil), "")
  }

  test("commas within weighted expressions are not mistaken for top-level tags") {
    assertEquals(NegativeSuggestions.suffix("(a, blurry:1.2)", Seq("blurry")), ", blurry")
  }

  test("only an unescaped top-level trailing comma is a separator") {
    assertEquals(NegativeSuggestions.suffix("foo\\,", Seq("blurry")), ", blurry")
    assertEquals(NegativeSuggestions.suffix("foo\\、", Seq("blurry")), ", blurry")
    assertEquals(NegativeSuggestions.suffix("foo\\\\,", Seq("blurry")), " blurry")
    assertEquals(NegativeSuggestions.suffix("(foo, bar),  ", Seq("blurry")), "blurry")
  }

  test("unfinished brackets or escape block insertion instead of trapping terms inside them") {
    intercept[IllegalArgumentException](NegativeSuggestions.suffix("(foo,", Seq("blurry")))
    intercept[IllegalArgumentException](NegativeSuggestions.suffix("foo\\", Seq("blurry")))
    assertEquals(NegativeSuggestions.suffix("(foo,", Nil), "")
  }
