package sdfx.ui

import sdfx.core.plugins.api.Values
import sdfx.plugins.CnAnimationPlugin
import sdfx.ui.vm.{Mode, PluginsVM}

class CnAnimationReplaySuite extends munit.FunSuite:
  private val fields = Map("SD-CN-Animation" ->
    "{'video': 'clip.mp4', 'processing_strength': 0.6, 'fix_frame_strength': 0.3, 'step_1_processing_mode': 'Inpaint occlusions', 'step_2_seed': 5, 'format': ['GIF']}")

  test("the SD-CN-Animation key selects the script and restores its form; other infotext keeps the choice"):
    val p = new CnAnimationPlugin
    val vm = new PluginsVM(Mode.Img2Img, Seq(p), Nil, _ => Values.defaults(p.controls))
    vm.restoreCnAnimation(fields)
    assertEquals(vm.selectedScript.get, "cn_animation")
    val v = vm.form("cn_animation").get.values
    assertEquals(v.str("video"), "clip.mp4")
    assertEquals(v.str("processing_strength"), "0.6")
    assertEquals(v.str("fix_frame_strength"), "0.3")
    assertEquals(v.str("step_1_processing_mode"), "Inpaint occlusions")
    assertEquals(v.str("step_2_seed"), "5")
    assertEquals((v.str("format_gif"), v.str("format_mp4")), ("true", "false"))
    vm.restoreCnAnimation(Map("Steps" -> "20"))
    assertEquals(vm.selectedScript.get, "")
    vm.selectedScript.set("another_script")
    vm.restoreCnAnimation(Map.empty)
    assertEquals(vm.selectedScript.get, "another_script")
