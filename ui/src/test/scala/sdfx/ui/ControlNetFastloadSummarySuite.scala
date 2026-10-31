package sdfx.ui.vm

class ControlNetFastloadSummarySuite extends munit.FunSuite:
  test("summary displays settings and omissions without embedded image data") {
    val result = ujson.Obj("units" -> ujson.Str("private-image-canary"), "summary" -> ujson.Arr(
      ujson.Obj("enabled" -> true, "model" -> "canny", "preprocessor" -> "canny",
        "unapplied" -> ujson.Arr("image.mask", "resize_mode"))))
    val text = ControlNetFastloadSummary.render(result)
    assert(text.contains("canny") && text.contains("image.mask"))
    assert(!text.contains("private-image-canary"))
  }
