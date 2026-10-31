package sdfx.ui

import munit.FunSuite
import sdfx.core.model.ControlNetUnit
import sdfx.ui.vm.ControlNetUnitVM

class ControlNetUnitVMSuite extends FunSuite:
  test("loading and editing a unit preserves its preprocessor arguments") {
    val vm = new ControlNetUnitVM
    val source = ControlNetUnit(enabled = true, model = "model", preprocessor = "canny",
      preprocessorArgs = Map("low_threshold" -> 37.0, "high_threshold" -> 180.0), resizeMode = 2, sourceCompatibleResize = true)
    vm.load(Some(source))
    vm.weight.set(0.8)
    assertEquals(vm.toUnit.preprocessorArgs, source.preprocessorArgs)
    assertEquals(vm.toUnit.resizeMode, 2)
    assertEquals(vm.toUnit.sourceCompatibleResize, true)
    vm.preprocessor.set("none")
    assertEquals(vm.toUnit.preprocessorArgs, Map.empty[String, Double])
    vm.load(Some(source))
    vm.load(None)
    assertEquals(vm.toUnit.preprocessorArgs, Map.empty[String, Double])
  }
