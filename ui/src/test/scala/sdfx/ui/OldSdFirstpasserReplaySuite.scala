package sdfx.ui

import sdfx.core.plugins.api.Values
import sdfx.plugins.OldSdFirstpasserPlugin
import sdfx.ui.vm.{Mode, PluginsVM}

class OldSdFirstpasserReplaySuite extends munit.FunSuite:
  private val fields = Map(
    "Old SD firstpasser" -> "SDXL", "Old SD firstpasser steps" -> "14", "Old SD firstpasser denoising" -> "0.45",
    "Old SD firstpasser upscaler" -> "R-ESRGAN 4x+", "Old SD firstpasser checkpoint" -> "pony.safetensors",
    "Old SD firstpasser resolution" -> "768", "Old SD firstpasser main LoRAs" -> "True")

  test("script keys in the infotext select the script and restore its form; other infotext keeps the choice"):
    val p = new OldSdFirstpasserPlugin
    val vm = new PluginsVM(Mode.Txt2Img, Seq(p), Nil, _ => Values.defaults(p.controls))
    vm.restoreOldSdFirstpasser(fields)
    assertEquals(vm.selectedScript.get, "old_sd_firstpasser")
    val v = vm.form("old_sd_firstpasser").get.values
    assertEquals(v.str("network_type"), "SDXL")
    assertEquals(v.str("sdxl_checkpoint"), "pony.safetensors")
    assertEquals(v.str("sd1_checkpoint"), "")
    assertEquals(v.str("steps"), "14")
    assertEquals(v.str("denoising"), "0.45")
    assertEquals(v.str("upscaler"), "R-ESRGAN 4x+")
    assertEquals(v.str("resolution"), "768")
    assertEquals(v.str("main_loras"), "true")
    vm.restoreOldSdFirstpasser(Map("Steps" -> "20"))
    assertEquals(vm.selectedScript.get, "")
    vm.selectedScript.set("another_script")
    vm.restoreOldSdFirstpasser(Map.empty)
    assertEquals(vm.selectedScript.get, "another_script")
