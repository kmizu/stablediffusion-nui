package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class CharacteristicGuidanceSuite extends munit.FunSuite:
  private val plugin = new CharacteristicGuidancePlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)
  private val DefaultChg = "{'RegS': 1, 'RegR': 1, 'MaxI': 50, 'NBasis': 0, 'Reuse': 1, 'Tol': -4, 'IteSS': 1, " +
    "'ASpeed': 0.4, 'AStrength': 0.5, 'AADim': 2, 'CMode': 'More ControlNet', 'StartStep': 0, 'StopStep': 1}"

  test("opt-in controls follow source defaults and write the source CHG infotext"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    val params = plugin.process(GenerationParams(), enabled())
    assertEquals(params.hooks(plugin.id), ujson.Obj("reg_ini" -> 1.0, "reg_range" -> 1.0, "ite" -> 50, "noise_base" -> 0,
      "chara_decay" -> 1.0, "res" -> -4.0, "lr" -> 1.0, "reg_size" -> 0.4, "reg_w" -> 0.5, "aa_dim" -> 2,
      "start_step" -> 0.0, "stop_step" -> 1.0, "controlnet_mode" -> "More ControlNet"))
    assertEquals(params.extra("CHG"), DefaultChg)
    // A1111 quotes the value because it holds commas and colons; the swapped quotes need no escaping
    assert(Infotext.write(params, ImageFacts(seed = 1)).contains("CHG: \"" + DefaultChg + "\""))
    assertEquals(plugin.requiresEngineFeatures, Seq(plugin.id))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)
    assert(plugin.xyzAxes.isEmpty) // the source registers no X/Y/Z axes

  test("PNG infotext round trip restores every control"):
    val values = enabled("reg_ini" -> "7.5", "reg_range" -> "0.35", "ite" -> "20", "noise_base" -> "3", "chara_decay" -> "0.5",
      "res" -> "-3.2", "lr" -> "0.8", "reg_size" -> "0.6", "reg_w" -> "1.25", "aa_dim" -> "4", "start_step" -> "0.1",
      "stop_step" -> "0.45", "controlnet_mode" -> "More Prompt")
    val params = plugin.process(GenerationParams(prompt = "castle"), values)
    assertEquals(params.extra("CHG"), "{'RegS': 7.5, 'RegR': 0.35, 'MaxI': 20, 'NBasis': 3, 'Reuse': 0.5, 'Tol': -3.2, " +
      "'IteSS': 0.8, 'ASpeed': 0.6, 'AStrength': 1.25, 'AADim': 4, 'CMode': 'More Prompt', 'StartStep': 0.1, 'StopStep': 0.45}")
    val fields = Infotext.parse(Infotext.write(params, ImageFacts(seed = 1))).fields
    val restored = CharacteristicGuidancePlugin.replay(fields).get
    for key <- Seq("controlnet_mode", "enabled") do assertEquals(restored.str(key), values.str(key), key)
    for key <- Seq("reg_ini", "reg_range", "ite", "noise_base", "chara_decay", "res", "lr", "reg_size", "reg_w", "aa_dim",
                   "start_step", "stop_step") do
      assertEquals(restored.double(key), values.double(key), key)
    assertEquals(plugin.process(GenerationParams(prompt = "castle"), restored).extra("CHG"), params.extra("CHG"))

  test("replay reads source PNGs, including Python float spellings and missing keys"):
    val source = "{'RegS': 1.0, 'RegR': 1.0, 'MaxI': 50, 'NBasis': 0, 'Reuse': 1.0, 'Tol': -4.0, 'IteSS': 1.0, " +
      "'ASpeed': 0.4, 'AStrength': 0.5, 'AADim': 2, 'CMode': 'More ControlNet'}"  // before Start/End Step existed
    val restored = CharacteristicGuidancePlugin.replay(Map("CHG" -> source)).get
    assertEquals(restored.str("enabled"), "true")
    assertEquals((restored.str("reg_ini"), restored.str("start_step"), restored.str("stop_step")), ("1", "0", "1"))
    assertEquals(plugin.process(GenerationParams(), restored).extra("CHG"), DefaultChg)

  test("replay disables the plugin for other PNGs and rejects malformed values"):
    assertEquals(CharacteristicGuidancePlugin.replay(Map.empty).get.str("enabled"), "false")
    for bad <- Seq("not json", "[1, 2]", "{'RegS': 'x'}", "{'MaxI': 2.5}", "{'MaxI': 0}", "{'AStrength': 0}",
                   "{'RegR': -1}", "{'NBasis': -1}", "{'CMode': 'Other'}", "{'Tol': 1e400}") do
      assertEquals(CharacteristicGuidancePlugin.replay(Map("CHG" -> bad)), None, bad)
