package sdfx.ui.vm

import sdfx.core.model.ControlNetUnit
import scala.util.control.NonFatal

/** Compare the destination with the submission snapshot before replacing one unit. */
object FastloadApply:
  def replace(vm: GenerationVM, index: Int, expected: ControlNetUnit, incoming: ControlNetUnit): Either[String, Unit] =
    if index < 0 || index >= vm.controlNet.size then Left("Invalid ControlNet unit")
    else
      val target = vm.controlNet(index)
      if target.toUnit != expected then Left("The destination unit changed; retry the transfer.")
      else
        try
          target.load(Some(incoming))
          Right(())
        catch
          case NonFatal(error) =>
            try target.load(Some(expected))
            catch case NonFatal(restore) => return Left(s"Could not restore the destination: ${restore.getMessage}")
            Left(s"Could not apply the destination: ${error.getMessage}")
