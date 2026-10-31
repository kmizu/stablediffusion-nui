package sdfx.ui

import munit.FunSuite
import sdfx.core.plugins.api.Values
import sdfx.ui.vm.{FastloadMetadataApply, GenerationVM, Mode, PluginsVM}

class FastloadMetadataApplySuite extends FunSuite:
  test("failed metadata application restores the previous form state") {
    val vm = new GenerationVM(Mode.Img2Img)
    val plugins = new PluginsVM(Mode.Img2Img, Nil, Nil, _ => Values.empty)
    vm.prompt.set("before"); vm.initImage.set("input.png"); vm.variationEnabled.set(false)
    val result = FastloadMetadataApply.run(vm, plugins) {
      vm.prompt.set("partial"); vm.variationEnabled.set(true)
      throw new IllegalStateException("synthetic failure")
    }
    assert(result.isLeft)
    assertEquals(vm.prompt.get, "before")
    assertEquals(vm.initImage.get, "input.png")
    assertEquals(vm.variationEnabled.get, false)
  }
