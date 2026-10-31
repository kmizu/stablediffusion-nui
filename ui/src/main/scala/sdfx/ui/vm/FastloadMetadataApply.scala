package sdfx.ui.vm

import sdfx.core.plugins.api.Values
import scala.util.control.NonFatal

/** Restore existing native form and plugin values if metadata application fails. */
object FastloadMetadataApply:
  def run(vm: GenerationVM, plugins: PluginsVM)(apply: => Unit): Either[String, Unit] =
    val before = vm.snapshot
    val variation = vm.variationEnabled.get
    val values = plugins.snapshot
    val script = plugins.selectedScript.get
    try
      apply
      Right(())
    catch
      case NonFatal(error) =>
        try
          vm.load(before, keepImages = true, variationEnabledState = variation)
          values.foreach((id, value) => plugins.form(id).foreach(_.load(Values(value))))
          plugins.selectedScript.set(script)
          Left(s"Could not apply parameters: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
        catch
          case NonFatal(restore) => Left(s"Could not restore previous parameters: ${Option(restore.getMessage).getOrElse(restore.getClass.getSimpleName)}")
