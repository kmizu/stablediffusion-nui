package sdfx.ui.vm

/** Summaries intentionally exclude embedded image and mask bytes. */
object ControlNetFastloadSummary:
  def render(data: ujson.Value): String =
    data.obj.get("summary") match
      case Some(items: ujson.Arr) if items.value.nonEmpty =>
        items.value.zipWithIndex.map { (item, index) =>
          val obj = item.obj
          obj.get("error") match
            case Some(ujson.Str(message)) => s"Unit ${index + 1}: ${message.take(200)}"
            case _ =>
              val model = obj.get("model").collect { case ujson.Str(s) => s.take(160) }.getOrElse("")
              val module = obj.get("preprocessor").collect { case ujson.Str(s) => s.take(100) }.getOrElse("")
              val enabled = obj.get("enabled").contains(ujson.Bool(true))
              val unapplied = obj.get("unapplied").collect { case a: ujson.Arr =>
                a.value.collect { case ujson.Str(s) => s.take(100) }.mkString(", ")
              }.getOrElse("")
              val warning = obj.get("warning").collect { case ujson.Str(s) => s.take(300) }.getOrElse("")
              s"Unit ${index + 1}: ${if enabled then "enabled" else "disabled"}; $model; $module" +
                (if unapplied.nonEmpty then s"\nNot applied by the current form: $unapplied" else "") +
                (if warning.nonEmpty then s"\n$warning" else "")
        }.mkString("\n\n")
      case Some(_: ujson.Arr) => "No ControlNet units."
      case _ => throw new IllegalArgumentException("Invalid Fastload inspection result")
