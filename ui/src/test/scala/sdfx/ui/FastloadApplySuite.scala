package sdfx.ui

import munit.FunSuite
import sdfx.core.model.ControlNetUnit
import sdfx.ui.vm.{FastloadApply, GenerationVM, Mode}

class FastloadApplySuite extends FunSuite:
  test("refuse a stale target without changing the user's newer edit") {
    val vm = new GenerationVM(Mode.Txt2Img)
    val target = vm.controlNet(0)
    target.model.set("before")
    val expected = target.toUnit
    target.model.set("newer")
    assert(FastloadApply.replace(vm, 0, expected, ControlNetUnit(model = "incoming")).isLeft)
    assertEquals(target.model.get, "newer")
  }

  test("replace only the chosen unchanged unit") {
    val vm = new GenerationVM(Mode.Txt2Img)
    vm.controlNet(1).model.set("keep")
    val incoming = ControlNetUnit(enabled = true, model = "incoming", preprocessorArgs = Map("x" -> 5.0))
    assertEquals(FastloadApply.replace(vm, 0, vm.controlNet(0).toUnit, incoming), Right(()))
    assertEquals(vm.controlNet(0).toUnit, incoming)
    assertEquals(vm.controlNet(1).model.get, "keep")
  }
