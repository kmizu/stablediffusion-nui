package sdfx.ui

import munit.FunSuite
import sdfx.ui.vm.FastloadForm
import sdfx.ui.vm.{GenerationVM, Mode}

class FastloadFormSuite extends FunSuite:
  private def response = ujson.Obj(
    "params" -> ujson.Obj("enabled" -> true, "model" -> "m", "preprocessor" -> "canny",
      "weight" -> 0.8, "guidance_start" -> 0.1, "guidance_end" -> 0.9,
      "preprocessor_args" -> ujson.Obj("low_threshold" -> 40), "resize_mode" -> 2,
      "source_compatible_resize" -> true),
    "image" -> ujson.Null, "mask" -> ujson.Null, "unapplied" -> ujson.Arr())

  test("decode representable parameters without dropping preprocessing arguments") {
    val result = FastloadForm.decode(response)
    assertEquals(result.unit.preprocessorArgs, Map("low_threshold" -> 40.0))
    assertEquals(result.unit.model, "m")
    assertEquals(result.unit.resizeMode, 2)
    assertEquals(result.unit.sourceCompatibleResize, true)
    assertEquals(result.image, None)
  }

  test("decode source control mode, processor resolution and pixel perfect") {
    val input = response
    input("params")("control_mode") = "My prompt is more important"
    input("params")("processor_res") = 640
    input("params")("pixel_perfect") = true
    val unit = FastloadForm.decode(input).unit
    assertEquals((unit.controlMode, unit.processorRes, unit.pixelPerfect), ("My prompt is more important", 640, true))
    assertEquals(FastloadForm.decode(response).unit.controlMode, "Balanced")
    input("params")("control_mode") = "Prompt"
    intercept[IllegalArgumentException](FastloadForm.decode(input))
    input("params")("control_mode") = "Balanced"
    input("params")("processor_res") = 32
    intercept[IllegalArgumentException](FastloadForm.decode(input))
  }

  test("reject fields which the native form cannot apply") {
    val input = response
    input("unapplied") = ujson.Arr("image.mask", "pixel_perfect")
    val error = intercept[IllegalArgumentException](FastloadForm.decode(input))
    assert(error.getMessage.contains("image.mask"))
  }

  test("reject inpaint preprocessor until its mask semantics are implemented") {
    val input = response
    input("params")("preprocessor") = "inpaint"
    val error = intercept[IllegalArgumentException](FastloadForm.decode(input))
    assert(error.getMessage.contains("inpaint"))
    input("params")("preprocessor") = "inpaint_only"
    intercept[IllegalArgumentException](FastloadForm.decode(input))
  }

  test("metadata-only restoration keeps the existing input image") {
    val vm = new GenerationVM(Mode.Img2Img)
    vm.initImage.set("existing-input.png")
    vm.restoreInfotext("new prompt\nSteps: 20, Sampler: Euler, CFG scale: 7, Seed: 1, Size: 512x512")
    assertEquals(vm.initImage.get, "existing-input.png")
    assertEquals(vm.prompt.get, "new prompt")
  }
