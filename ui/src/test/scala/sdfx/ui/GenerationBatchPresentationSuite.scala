package sdfx.ui

import sdfx.ui.view.GenerationBatchPresentation

class GenerationBatchPresentationSuite extends munit.FunSuite:
  test("batch text does not promise parallel execution"):
    assertEquals(GenerationBatchPresentation.summary(3,2),"3 images per run × 2 runs = 6 images")
    assertEquals(GenerationBatchPresentation.summary(1,1),"1 image per run × 1 run = 1 image")
    assert(GenerationBatchPresentation.sizeTip.contains("sequentially or in parallel"))
