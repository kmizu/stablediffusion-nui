package sdfx.ui.vm

import sdfx.core.png.{ImageScrambleSecret, ScrambledPng}
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** Stage an imported input without writing readable pixels in a protected session. */
object FastloadInputStore:
  def write(png: Array[Byte], tracker: TempTracker, secret: Option[ImageScrambleSecret]): Path =
    val image = sdfx.engine.FastloadInputCodec.decode(png)
    val path = tracker.createInputTemp("paste-", ".png")
    try
      ScrambledPng.write(image, path, Nil, secret.map(_.use(identity)))
      if !tracker.completeInputWrite(path) then
        throw new java.util.concurrent.CancellationException("Image input closed")
      path
    catch
      case NonFatal(error) =>
        tracker.forgetInput(path)
        try Files.deleteIfExists(path) catch case NonFatal(_) => ()
        throw error
