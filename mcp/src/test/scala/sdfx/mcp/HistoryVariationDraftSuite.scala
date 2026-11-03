package sdfx.mcp

import munit.FunSuite
import sdfx.core.presets.*

class HistoryVariationDraftSuite extends FunSuite:
  private def draft(values: (String, PresetAtom)*): ujson.Obj =
    val fields = values.map { case (name, value) => FieldKey(Vector("generation", name)) -> value }.toMap
    HistoryGenerationDraft.build(PresetRecord(java.util.UUID.randomUUID(), "test", fields, None, Set.empty, Vector.empty, Vector.empty), "txt2img")
  private def names(v: ujson.Value): Set[String] = v.arr.map(_.str).toSet

  test("enabled variation maps bounded seed and strength") {
    val d = draft("variationEnabled" -> PresetAtom.Flag(true), "subseed" -> PresetAtom.Integer(4294967295L), "subseedStrength" -> PresetAtom.Decimal(BigDecimal("0.65")))
    assertEquals(d("params")("subseed").num, 4294967295d)
    assertEquals(d("params")("subseed_strength").num, 0.65d)
    assertEquals(names(d("omitted_fields")), Set.empty[String])
  }
  test("disabled variation uses zero strength while preserving unused saved values as omitted") {
    val d = draft("variationEnabled" -> PresetAtom.Flag(false), "subseed" -> PresetAtom.Integer(15), "subseedStrength" -> PresetAtom.Decimal(BigDecimal("0.75")))
    assertEquals(d("params")("subseed_strength").num, 0d)
    assert(!d("params").obj.contains("subseed"))
    assertEquals(names(d("omitted_fields")), Set("generation.subseed", "generation.subseedStrength"))
  }
  test("unknown gate leaves variation values unconverted; invalid gate is reported") {
    val seed = "subseed" -> PresetAtom.Integer(15)
    val strength = "subseedStrength" -> PresetAtom.Decimal(BigDecimal("0.75"))
    for d <- Seq(draft(seed, strength), draft(seed, strength, "variationEnabled" -> PresetAtom.Text("bad"))) do
      assert(!d("params").obj.contains("subseed_strength"))
      assertEquals(names(d("omitted_fields")), Set("generation.subseed", "generation.subseedStrength"))
    assert(names(draft(seed, strength, "variationEnabled" -> PresetAtom.Text("bad"))("invalid_fields")).contains("generation.variationEnabled"))
  }
  test("enabled variation rejects out-of-range and wrong-type values") {
    val d = draft("variationEnabled" -> PresetAtom.Flag(true), "subseed" -> PresetAtom.Integer(4294967296L), "subseedStrength" -> PresetAtom.Text("bad"))
    assert(!d("params").obj.contains("subseed"))
    assert(!d("params").obj.contains("subseed_strength"))
    assertEquals(names(d("invalid_fields")), Set("generation.subseed", "generation.subseedStrength"))
  }
