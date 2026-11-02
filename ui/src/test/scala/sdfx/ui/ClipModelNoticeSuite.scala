package sdfx.ui

import munit.FunSuite
import sdfx.ui.view.ClipModelNotice

class ClipModelNoticeSuite extends FunSuite:
  test("dependency status explains missing, broken and incompatible installs") {
    assert(ClipModelNotice.dependency("missing").contains("install"))
    assert(ClipModelNotice.dependency("load-error").contains("could not load"))
    assert(ClipModelNotice.dependency("incompatible-version").contains("2.32.0"))
    assert(ClipModelNotice.dependency("available").contains("not all been tested"))
  }
  test("registry entries distinguish historical verification from untested candidates") {
    assert(ClipModelNotice.model("not-tested").contains("not been tested"))
    assert(ClipModelNotice.model("previous-gui-and-mcp").contains("earlier build"))
    assert(ClipModelNotice.model("previous-engine-local-weights").contains("local weights"))
    assert(ClipModelNotice.model("unknown").contains("not been tested"))
  }
