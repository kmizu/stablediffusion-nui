package sdfx.core

import sdfx.core.model.PreprocessorControls

class PreprocessorControlsSuite extends munit.FunSuite:
  test("canny has the resolution and both thresholds with the engine defaults") {
    val c = PreprocessorControls.of("canny")
    assert(c.resolution)
    assertEquals(c.sliders.map(_.key), Seq("low_threshold", "high_threshold"))
    assertEquals(PreprocessorControls.defaults("canny"), Map("low_threshold" -> 100.0, "high_threshold" -> 200.0))
  }

  test("modules without a resolution or sliders are plain") {
    for m <- Seq("invert", "depth", "none", "unknown") do
      assertEquals(PreprocessorControls.of(m).resolution, false, m)
      assertEquals(PreprocessorControls.of(m).sliders, Nil, m)
    assert(PreprocessorControls.of("openpose").resolution)
    assertEquals(PreprocessorControls.of("openpose").sliders, Nil)
  }

  test("every slider default is inside its range and keys map to the engine argument names") {
    for m <- Seq("canny", "recolor_luminance", "recolor_intensity", "tile_resample", "tile_colorfix", "blur_gaussian", "depth_marigold", "normal_marigold"); s <- PreprocessorControls.of(m).sliders do
      assert(s.default >= s.min && s.default <= s.max, s"$m ${s.key}")
    assertEquals(PreprocessorControls.of("blur_gaussian").sliders.map(_.key), Seq("sigma"))
    assertEquals(PreprocessorControls.of("tile_resample").sliders.map(_.key), Seq("down_sampling_rate"))
  }

  test("validate rejects unknown keys and out of range values and accepts the defaults") {
    assertEquals(PreprocessorControls.validate("canny", Map("low_threshold" -> 50.0)), Right(Map("low_threshold" -> 50.0)))
    assert(PreprocessorControls.validate("canny", Map("sigma" -> 1.0)).isLeft)
    assert(PreprocessorControls.validate("canny", Map("low_threshold" -> 0.0)).isLeft)
    assert(PreprocessorControls.validate("canny", Map("high_threshold" -> 256.0)).isLeft)
    assert(PreprocessorControls.validate("canny", Map("low_threshold" -> Double.NaN)).isLeft)
    assert(PreprocessorControls.validate("invert", Map("x" -> 1.0)).isLeft)
    assertEquals(PreprocessorControls.validate("invert", Map.empty), Right(Map.empty))
    for m <- Seq("canny", "blur_gaussian", "tile_colorfix") do
      assert(PreprocessorControls.validate(m, PreprocessorControls.defaults(m)).isRight, m)
  }

  test("selectable drops none and inpaint, isImage knows the batch extensions") {
    assertEquals(PreprocessorControls.selectable(Seq("none", "invert", "inpaint", "canny", "canny", "")), Seq("invert", "canny"))
    assert(Seq("a.PNG", "b.jpg", "c.jpeg", "d.webp", "e.bmp").forall(PreprocessorControls.isImage))
    assert(!Seq("a.txt", "png", ".png", "b.mp4", "c").exists(PreprocessorControls.isImage))
  }
