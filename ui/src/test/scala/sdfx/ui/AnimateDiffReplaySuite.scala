package sdfx.ui

import sdfx.core.plugins.api.Values
import sdfx.plugins.AnimateDiffPlugin
import sdfx.ui.vm.{Mode, PluginsVM}

class AnimateDiffReplaySuite extends munit.FunSuite:
  private val fields = Map("AnimateDiff" ->
    "{'model': 'mm_sd15_v2.safetensors', 'video_length': 20, 'fps': 12, 'closed_loop': 'A', 'batch_size': 8, 'stride': 2, 'format': ['MP4', 'PNG']}")

  test("the AnimateDiff key selects the script and restores its form; other infotext keeps the choice"):
    val p = new AnimateDiffPlugin
    val vm = new PluginsVM(Mode.Txt2Img, Seq(p), Nil, _ => Values.defaults(p.controls))
    vm.restoreAnimateDiff(fields)
    assertEquals(vm.selectedScript.get, "animatediff")
    val v = vm.form("animatediff").get.values
    assertEquals(v.str("model"), "mm_sd15_v2.safetensors")
    assertEquals(v.str("video_length"), "20")
    assertEquals(v.str("fps"), "12")
    assertEquals(v.str("closed_loop"), "A")
    assertEquals(v.str("batch_size"), "8")
    assertEquals(v.str("stride"), "2")
    assertEquals((v.str("format_gif"), v.str("format_mp4"), v.str("format_png")), ("false", "true", "true"))
    vm.restoreAnimateDiff(Map("Steps" -> "20"))
    assertEquals(vm.selectedScript.get, "")
    vm.selectedScript.set("another_script")
    vm.restoreAnimateDiff(Map.empty)
    assertEquals(vm.selectedScript.get, "another_script")
