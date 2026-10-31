package sdfx.engine

import sdfx.core.plugins.api.Values

/** Job-local configuration, separate from generation hooks and payload diagnostics. */
private[engine] final case class FastloadJobPlan(mode: String, priority: String, path: String, saveFormat: String)

private[engine] object FastloadJobPlan:
  def fromValues(values: Values): Option[FastloadJobPlan] =
    if !values.bool("enabled") then None
    else
      val mode = values.str("mode", "load_only")
      val priority = values.str("priority", "current_first")
      val format = values.str("save_format", "embedded")
      require(Set("load_only", "save_only", "load_save")(mode), "Invalid Fastload mode")
      require(Set("current_first", "loaded_first")(priority), "Invalid Fastload priority")
      require(Set("embedded", "sidecar", "both")(format), "Invalid Fastload save format")
      Some(FastloadJobPlan(mode, priority, values.str("path").trim, format))
