package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class AgentAttentionSuite extends munit.FunSuite:
  private val plugin = new AgentAttentionPlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("opt-in controls follow source defaults and write source infotext keys"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    val params = plugin.process(GenerationParams(), enabled())
    assertEquals(params.hooks(plugin.id), ujson.Obj("use_sp" -> false, "sp_step" -> 20, "sx" -> 4, "sy" -> 4,
      "ratio" -> 0.4, "agent_ratio" -> 0.95, "sp_sx" -> 2, "sp_sy" -> 2, "sp_ratio" -> 0.4, "sp_agent_ratio" -> 0.5,
      "use_fp32" -> false, "max_downsample" -> 1, "hires_fix_only" -> false))
    val expected = Seq("AgAt Active" -> "True", "AgAt Use Second Pass" -> "False", "AgAt Second Pass Step" -> "20",
      "AgAt First Pass sx" -> "4", "AgAt First Pass sy" -> "4", "AgAt First Pass Ratio" -> "0.4",
      "AgAt First Pass Agent Ratio" -> "0.95", "AgAt Second Pass sx" -> "2", "AgAt Second Pass sy" -> "2",
      "AgAt Second Pass Ratio" -> "0.4", "AgAt Second Pass Agent Ratio" -> "0.5", "AgAt Use FP32 Precision" -> "False",
      "AgAt Max Downsample" -> "1", "AgAt Apply to Hires. Fix Only" -> "False")
    expected.foreach((k, v) => assertEquals(params.extra(k), v, k))
    assertEquals(AgentAttentionPlugin.InfotextKeys.toSet, expected.map(_._1).toSet + "Fix Only")
    assertEquals(plugin.requiresEngineFeatures, Seq(plugin.id))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)

  test("PNG infotext round trip restores every control"):
    val values = enabled("use_sp" -> "true", "sp_step" -> "7", "sx" -> "3", "sy" -> "2", "ratio" -> "0.55",
      "agent_ratio" -> "0.9", "sp_sx" -> "1", "sp_sy" -> "5", "sp_ratio" -> "0.25", "sp_agent_ratio" -> "0.6",
      "use_fp32" -> "true", "max_downsample" -> "4", "hires_fix_only" -> "true")
    val params = plugin.process(GenerationParams(prompt = "castle"), values)
    val written = Infotext.write(params, ImageFacts(seed = 1))
    assert(written.contains("AgAt Apply to Hires. Fix Only: True"), written)
    val fields = Infotext.parse(written).fields
    // Like A1111, the parser reads the key with "." as "Fix Only".
    assertEquals(fields.get("Fix Only"), Some("True"))
    val restored = AgentAttentionPlugin.replay(fields).get
    for key <- Seq("enabled", "use_sp", "use_fp32", "hires_fix_only", "max_downsample") do
      assertEquals(restored.str(key), values.str(key), key)
    for key <- Seq("sp_step", "sx", "sy", "ratio", "agent_ratio", "sp_sx", "sp_sy", "sp_ratio", "sp_agent_ratio") do
      assertEquals(restored.double(key), values.double(key), key)
    assertEquals(plugin.process(GenerationParams(), restored).hooks(plugin.id), params.hooks(plugin.id))

  test("upstream PNG text restores: presence of AgAt Active enables, missing keys take defaults"):
    val text = "a cat\nSteps: 20, Seed: 1, Size: 512x512, AgAt Active: True, AgAt First Pass sx: 2, " +
      "AgAt First Pass Ratio: 0.3, AgAt Max Downsample: 2, AgAt Apply to Hires. Fix Only: False"
    val restored = AgentAttentionPlugin.replay(Infotext.parse(text).fields).get
    assertEquals(restored.str("enabled"), "true")
    assertEquals(restored.int("sx"), 2)
    assertEquals(restored.double("ratio"), 0.3)
    assertEquals(restored.str("max_downsample"), "2")
    assertEquals(restored.int("sy"), 4)
    assertEquals(restored.double("sp_agent_ratio"), 0.5)
    assertEquals(restored.str("hires_fix_only"), "false")
    assertEquals(AgentAttentionPlugin.replay(Map("AgAt Active" -> "True", "AgAt Apply to Hires. Fix Only" -> "True")).get
      .str("hires_fix_only"), "true")

  test("replay disables the plugin for other PNGs and rejects malformed values"):
    assertEquals(AgentAttentionPlugin.replay(Map.empty).get.str("enabled"), "false")
    assertEquals(AgentAttentionPlugin.replay(Map("AgAt Active" -> "True", "AgAt First Pass Ratio" -> "2")), None)
    assertEquals(AgentAttentionPlugin.replay(Map("AgAt Active" -> "True", "AgAt First Pass sx" -> "2.5")), None)
    assertEquals(AgentAttentionPlugin.replay(Map("AgAt Active" -> "True", "AgAt Max Downsample" -> "0")), None)
    assertEquals(AgentAttentionPlugin.replay(Map("AgAt Active" -> "True", "AgAt Use Second Pass" -> "maybe")), None)

  test("source XYZ axes override fields and the Active axis switches the plugin off"):
    val names = plugin.xyzAxes.map(_.name)
    assertEquals(names, Seq("[AgentAttention] Active", "[AgentAttention] Use Second Pass",
      "[AgentAttention] Second Pass Step", "[AgentAttention] First Pass sx", "[AgentAttention] First Pass sy",
      "[AgentAttention] First Pass Ratio", "[AgentAttention] First Pass Agent Ratio", "[AgentAttention] Second Pass sx",
      "[AgentAttention] Second Pass sy", "[AgentAttention] Second Pass Ratio", "[AgentAttention] Second Pass Agent Ratio",
      "[AgentAttention] Use FP32", "[AgentAttention] Max Downsample"))
    def axis(name: String) = plugin.xyzAxes.find(_.name == name).get
    val off = plugin.process(axis("[AgentAttention] Active")(GenerationParams(), "False"), enabled())
    assert(!off.hooks.contains(plugin.id) && !off.hooks.contains(plugin.id + "_xyz") && !off.extra.contains("AgAt Active"))
    val on = plugin.process(axis("[AgentAttention] Active")(GenerationParams(), "True"), enabled())
    assert(!on.hooks(plugin.id).obj.contains("active"))
    val sp = plugin.process(axis("[AgentAttention] Use Second Pass")(GenerationParams(), "TRUE"), enabled())
    assertEquals(sp.hooks(plugin.id)("use_sp").bool, true)
    val ds = plugin.process(axis("[AgentAttention] Max Downsample")(GenerationParams(), "8"), enabled())
    assertEquals(ds.hooks(plugin.id)("max_downsample").num, 8.0)
    assertEquals(ds.extra("AgAt Max Downsample"), "8")
