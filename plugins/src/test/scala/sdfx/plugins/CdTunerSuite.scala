package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class CdTunerSuite extends munit.FunSuite:
  private val plugin = new CdTunerPlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)
  private def run(prompt: String, extra: (String, String)*) = plugin.process(GenerationParams(prompt = prompt), enabled(extra*))

  test("opt-in, inactive at source defaults, registered once"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    val p = run("cat")
    assertEquals(p.hooks(plugin.id)("d1").num, 0.0)
    assert(!p.extra.contains("CDT") && !p.extra.contains("CDTC"))
    // stop steps alone do nothing in the source either
    assert(!run("cat", "st1" -> "5").extra.contains("CDT"))
    assertEquals(plugin.requiresEngineFeatures, Seq("cd_tuner"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)

  test("slider values become hook fields and the source CDT text"):
    val p = run("cat", "d1" -> "2.5", "con1" -> "4", "st1" -> "5", "hrs" -> "true", "sat2" -> "-1.5")
    assertEquals(p.extra("CDT"), "2.5,0,4,0,0,0,0,0,0,0,1,5,-1,0,0,-1.5")
    assert(!p.extra.contains("CDTC"))
    val h = p.hooks(plugin.id)
    assertEquals(h("d1").num, 2.5)
    assertEquals(h("hrs").bool, true)
    assertEquals(h("st2").num, -1.0)
    assertEquals(h("colors").str, "")
    assertEquals(h("apply_once").bool, false)

  test("prompt tags override like the source, printed as Python floats"):
    val named = run("a <cdt:d1=2;col1=-3> girl", "d2" -> "1")
    assertEquals(named.extra("CDT"), "2.0,1,0,0,0,-3.0,0,0,0,0,0,-1,-1,0,0,0")
    assertEquals(named.hooks(plugin.id)("d1").num, 2.0)
    assertEquals(run("<cdt:1;0;4>").extra("CDT"), "1.0,0.0,4.0,0,0,0,0,0,0,0,0,-1,-1,0,0,0")
    val hrs = run("<cdt:d2=2;hrs=3>")
    assertEquals(hrs.extra("CDT"), "0,2.0,0,0,0,0,0,0,0,0,1,-1,-1,0,0,0")
    assertEquals(hrs.hooks(plugin.id)("hrs").bool, true)
    assertEquals(run("<cdt:0;0;0;-2.3;0;2;0;0;1>").extra("CDT"), "0.0,0.0,0.0,-2.3,0.0,2.0,0.0,0.0,1.0,0,0,-1,-1,0,0,0")
    // only the first tag counts and a zero override still overrides
    assertEquals(run("<cdt:d1=0> <cdt:d1=5>", "d1" -> "3", "d2" -> "1").hooks(plugin.id)("d1").num, 0.0)

  test("dis= disables detail and colour tags, the 14th positional value is the Contrast 2 step offset"):
    val off = run("<cdt:d1=4;dis=0> <cdtc:cols=5 0 0>", "d1" -> "1", "colors" -> "0 0 5", "split_ratio" -> "1")
    assert(!off.extra.contains("CDT"))
    assertEquals(off.extra("CDTC"), "1_Horizontal_0 0 5_2_1")
    assertEquals(off.hooks(plugin.id)("st1").num, 0.0)
    val offset = run("<cdt:0;0;0;3;0;0;0;0;0;0;0;-1;-1;2>")
    assertEquals(offset.hooks(plugin.id)("dis").num, 2.0)
    assertEquals(offset.extra("CDT"), "0.0,0.0,0.0,3.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,-1.0,-1.0,2.0,0,0")

  test("colour map settings, prompt syntax and presets"):
    val ui = run("cat", "colors" -> "5 0 0;0 0 5", "map_strength" -> "0.5")
    assertEquals(ui.extra("CDTC"), "1,1_Horizontal_5 0 0;0 0 5_2_0.5")
    assert(!ui.extra.contains("CDT"))
    val tag = run("<cdtc:1,1;H;5 0 0|-5 0 0;2;1>")
    assertEquals(tag.extra("CDTC"), "1,1_Horizontal_5 0 0|-5 0 0_2_1")
    assertEquals(tag.hooks(plugin.id)("map_stop_step").str, "2")
    val named = run("<cdtc:md=V;str=1.1;sp=2,1>", "colors" -> "5 0 0")
    assertEquals(named.extra("CDTC"), "2,1_Vertical_5 0 0_2_1.1")
    assertEquals(run("cat", "colors" -> "5 0 0", "add_preset" -> "Red").hooks(plugin.id)("colors").str, "5 0 0;5, -5, -5")
    assertEquals(run("cat", "add_preset" -> "Blue").extra("CDTC"), "1,1_Horizontal_0, 0, 5_2_1")
    // strength 0 with nothing else set is inactive; a typed strength is text in the source and stays active
    assert(!run("cat", "colors" -> "5 0 0", "map_strength" -> "0").extra.contains("CDTC"))
    assert(run("<cdtc:str=0>", "colors" -> "5 0 0").extra.contains("CDTC"))

  test("malformed prompt tags are rejected instead of ignored"):
    for prompt <- Seq("<cdt:d1=x>", "<cdt:foo=1>", "<cdt:d1=2;3>", "<cdt:d1=1=2>", "<cdt:" + Seq.fill(17)("1").mkString(";") + ">",
        "<cdtc:by=H;str=1.1>", "<cdtc:1;2;3;4;5;6>", "<cdt:2f>") do
      intercept[IllegalArgumentException](run(prompt))
    // empty parts are harmless
    assertEquals(run("<cdt:d1=2;>").hooks(plugin.id)("d1").num, 2.0)

  test("Python float repr"):
    assertEquals(Seq(2.0, -3.0, 0.1, -2.3, 1e-5, 1.5e-5, 1e16, 123456.0, 0.0001).map(CdTunerPlugin.pyRepr),
      Seq("2.0", "-3.0", "0.1", "-2.3", "1e-05", "1.5e-05", "1e+16", "123456.0", "0.0001"))

  test("PNG infotext round trip restores the generation, including commas and the colour map"):
    val values = enabled("d1" -> "2.5", "hd2" -> "-1", "con2" -> "3", "col3" -> "-4", "hrs" -> "true", "st1" -> "4",
      "st2" -> "2", "sat" -> "1.5", "sat2" -> "-2", "colors" -> "5, 0, 0;0 0 5", "split_ratio" -> "2,1,3;3,4,6",
      "split_by" -> "Vertical", "map_stop_step" -> "3", "map_strength" -> "0.7")
    val params = plugin.process(GenerationParams(prompt = "castle"), values)
    val fields = Infotext.parse(Infotext.write(params, ImageFacts(seed = 1))).fields
    assertEquals(fields("CDT"), params.extra("CDT"))
    assertEquals(fields("CDTC"), params.extra("CDTC"))
    val restored = CdTunerPlugin.replay(fields).get
    for key <- Seq("enabled", "hrs", "colors", "split_ratio", "split_by") do assertEquals(restored.str(key), values.str(key), key)
    for key <- CdTunerPlugin.Identifiers.filterNot(_ == "hrs") ++ Seq("map_stop_step", "map_strength") do
      assertEquals(restored.double(key), values.double(key), key)
    assertEquals(plugin.process(GenerationParams(prompt = "castle"), restored).extra, params.extra)

  test("replay reads older CDT layouts, fills missing parts and rejects malformed values"):
    assertEquals(CdTunerPlugin.replay(Map.empty).get.str("enabled"), "false")
    val old = CdTunerPlugin.replay(Map("CDT" -> "1,2,3,0,0,0,0,0,0,0,1,4,-1")).get
    assertEquals((old.str("enabled"), old.double("con1"), old.str("hrs"), old.double("st1"), old.double("sat")),
      ("true", 3.0, "true", 4.0, 0.0))
    assertEquals(old.str("colors"), "")
    val legacy = CdTunerPlugin.replay(Map("CDT" -> "1,2,3,4,5,6,7,8,1")).get
    assertEquals(Seq("d1", "d2", "con1", "col1", "col2", "col3", "hd1", "hd2").map(legacy.double(_)), (1 to 8).map(_.toDouble))
    assertEquals(legacy.double("con2"), 0.0)
    val mapOnly = CdTunerPlugin.replay(Map("CDTC" -> "1,1_Hor_5 0 0;0 0 5_2_1")).get
    assertEquals((mapOnly.str("split_by"), mapOnly.double("d1"), mapOnly.str("enabled")), ("Horizontal", 0.0, "true"))
    assertEquals(CdTunerPlugin.replay(Map("CDT" -> "1,2,3")), None)
    assertEquals(CdTunerPlugin.replay(Map("CDT" -> Seq.fill(16)("NaN").mkString(","))), None)
    assertEquals(CdTunerPlugin.replay(Map("CDTC" -> "1,1_Horizontal_5 0 0")), None)
