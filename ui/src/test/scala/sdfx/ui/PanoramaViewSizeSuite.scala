package sdfx.ui

import sdfx.ui.view.PanoramaViewSize

class PanoramaViewSizeSuite extends munit.FunSuite:
  test("perspective output dimensions are bounded and reject invalid viewport sizes") {
    assertEquals(PanoramaViewSize.forViewport(1600, 900), Right((1600, 900)))
    assertEquals(PanoramaViewSize.forViewport(1600, 1), Right((1600, 2)))
    assert(PanoramaViewSize.forViewport(0, 900).isLeft)
    assert(PanoramaViewSize.forViewport(1, 100000).isLeft)
    assert(PanoramaViewSize.forViewport(Double.NaN, 100).isLeft)
    assert(PanoramaViewSize.forViewport(100, Double.PositiveInfinity).isLeft)
    assert(PanoramaViewSize.forViewport(1600, 4096).isRight)
    assert(PanoramaViewSize.forViewport(1600, 4097).isLeft)
  }
