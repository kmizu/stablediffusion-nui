package sdfx.plugins

import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

/** Values are interpreted by the job host, never serialized as generation hooks. */
final class ControlNetFastloadPlugin extends Plugin:
  def id = "controlnet_fastload"
  def name = "ControlNet Fastload"
  def kind = PluginKind.AlwaysOn
  override def description = "Load ControlNet units from a Fastload image or .cni before generating, and save the units used with each generated image (embedded, .cni sidecar, or both). Units that NUI cannot apply faithfully (inpaint/CLIP masks, batch inputs, other preprocessors' resolutions, hires-only or low-res-only units) are rejected with their setting names. Legacy files contain readable source images and masks; saving is unavailable in protected sessions."
  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Choice("mode", "Mode", Seq("load_only", "save_only", "load_save"), "load_only"),
    Choice("priority", "Priority", Seq("current_first", "loaded_first"), "current_first"),
    Text("path", "Input image or .cni path"),
    Choice("save_format", "Save format", Seq("embedded", "sidecar", "both"), "embedded")
  )
  override def requiresEngineFeatures: Seq[String] = Seq("controlnet_fastload_data")
