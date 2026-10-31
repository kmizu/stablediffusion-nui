package sdfx.ui.vm

/** Shared validation for form transfers and generation. */
object FastloadForm:
  def decode(value: ujson.Value): sdfx.engine.FastloadNativeForm.Decoded =
    sdfx.engine.FastloadNativeForm.decode(value)
