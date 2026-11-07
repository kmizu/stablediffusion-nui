package sdfx.ui

import sdfx.ui.view.AestheticEmbeddingTab
import java.nio.file.Files

class AestheticEmbeddingTabSuite extends munit.FunSuite:
  test("creation requires existing reference images and a plain output name") {
    val input = Files.createTempFile("aesthetic-ui-", ".png")
    try
      val request = AestheticEmbeddingTab.createParams("style", Seq(input), overwrite = false, protectedSession = false)
      assertEquals(request("op").str, "create")
      assertEquals(request("images").arr.map(_.str).toSeq, Seq(input.toAbsolutePath.normalize().toString))
      intercept[IllegalArgumentException] { AestheticEmbeddingTab.createParams("../style", Seq(input), false, false) }
      intercept[IllegalArgumentException] { AestheticEmbeddingTab.createParams("style", Seq.empty, false, false) }
      intercept[IllegalArgumentException] { AestheticEmbeddingTab.createParams("style", Seq(input), false, true) }
    finally Files.deleteIfExists(input)
  }
