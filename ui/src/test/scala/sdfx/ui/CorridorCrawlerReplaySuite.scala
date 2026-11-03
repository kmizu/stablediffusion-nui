package sdfx.ui

import sdfx.core.plugins.api.Values
import sdfx.plugins.CorridorCrawlerPlugin
import sdfx.ui.vm.{CorridorRestore, GenerationVM, Mode, PluginsVM}

class CorridorCrawlerReplaySuite extends munit.FunSuite:
  test("replay selects corridor settings but clears the previous custom prefab path"):
    val p = new CorridorCrawlerPlugin
    val vm = new PluginsVM(Mode.Img2Img, Seq(p), Nil, _ => Values.defaults(p.controls))
    vm.form("corridor_crawler").get.prop("prefab_image").get.set("C:/private/old.png")
    val configured = Values(Values.defaults(p.controls).map ++ Map("steps" -> "4", "prefab" -> CorridorCrawlerPlugin.CustomPrefab,
      "prefab_image" -> "C:/secret/original.png", "placement" -> CorridorCrawlerPlugin.PreserveAspect))
    val payload = CorridorCrawlerPlugin.replayValue(CorridorCrawlerPlugin.Settings.parse(configured))
    vm.restoreCorridorCrawler(Map(CorridorCrawlerPlugin.ReplayKey -> payload))
    assertEquals(vm.selectedScript.get, "corridor_crawler")
    val values = vm.form("corridor_crawler").get.values
    assertEquals(values.str("steps"), "4")
    assertEquals(values.str("placement"), CorridorCrawlerPlugin.PreserveAspect)
    assertEquals(values.str("prefab_image"), "")
    vm.restoreCorridorCrawler(Map(CorridorCrawlerPlugin.ReplayKey -> "invalid"))
    assertEquals(vm.selectedScript.get, "")

  test("restoring corridor targets Img2Img and preserves the Inpaint input"):
    val p = new CorridorCrawlerPlugin
    val vms = Map(Mode.Img2Img -> new GenerationVM(Mode.Img2Img), Mode.Inpaint -> new GenerationVM(Mode.Inpaint))
    vms(Mode.Img2Img).initImage.set("old-img2img.png")
    vms(Mode.Inpaint).initImage.set("keep-inpaint.png")
    val payload = CorridorCrawlerPlugin.replayValue(CorridorCrawlerPlugin.Settings.parse(Values.defaults(p.controls)))
    assertEquals(CorridorRestore.prepare(Map(CorridorCrawlerPlugin.ReplayKey -> payload), vms), Some(Mode.Img2Img))
    assertEquals(vms(Mode.Img2Img).initImage.get, "")
    assertEquals(vms(Mode.Inpaint).initImage.get, "keep-inpaint.png")
    assertEquals(CorridorRestore.prepare(Map.empty, vms), None)
