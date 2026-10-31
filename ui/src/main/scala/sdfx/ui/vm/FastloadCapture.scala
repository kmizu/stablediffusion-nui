package sdfx.ui.vm

import sdfx.core.model.ControlNetUnit
import sdfx.core.png.ImageScrambleSecret

/** The UI and generation path share the same bounded in-memory capture. */
object FastloadCapture:
  def encode(units: Seq[ControlNetUnit], secret: Option[ImageScrambleSecret]): ujson.Value =
    sdfx.engine.FastloadNativeCapture.encode(units, secret)
