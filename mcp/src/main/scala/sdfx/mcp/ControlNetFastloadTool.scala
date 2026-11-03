package sdfx.mcp

import sdfx.engine.AppServices
import java.nio.file.{Files, Path}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/** Read-only inspection; generation-time load/save is the `controlnet_fastload` AlwaysOn plugin (always_on). */
final class ControlNetFastloadTool(services: AppServices, policy: PathPolicy, base: Path,
                                  awaitReady: ToolContext => Unit,
                                  tempFile: String => Path = null,
                                  timeout: FiniteDuration = 5.minutes) extends McpTool:
  def name = "controlnet_fastload"
  override def title = "ControlNet Fastload"
  def description = "Inspect a Fastload image or .cni in an allowed folder, or report format versions. Inspection returns embedded settings and source image data to the caller, with each unit's settings NUI cannot apply under `unapplied`. To load or save units while generating, pass always_on={\"controlnet_fastload\": {\"enabled\": true, \"mode\": \"load_only|save_only|load_save\", \"priority\": \"current_first|loaded_first\", \"path\": ..., \"save_format\": \"embedded|sidecar|both\"}}. Protected image sessions are not exposed through this tool."
  override def readOnly = true
  def inputSchema = Schema.obj(Seq(
    "operation" -> Schema.str("version or inspect", Seq("version", "inspect")),
    "path" -> Schema.str("inspect: image or .cni inside an allowed folder")
  ), Seq("operation"))

  def call(input: ujson.Obj, ctx: ToolContext): ToolResult =
    val args = Args(input, Seq("operation", "path"))
    val operation = args.reqStr("operation")
    require(Set("version", "inspect")(operation), "Unknown Fastload operation")
    val session = services.imageScrambleSession.state
    if operation != "version" && session.secret.nonEmpty then
      throw ToolError("Fastload inspection is unavailable during a protected image session")
    val params = ujson.Obj("operation" -> operation)
    var temporary: Option[Path] = None
    var handedOff = false
    try
      if operation == "inspect" then
        val path = policy.resolve(args.reqStr("path"), "path", base)
        val data = Images.readVerifiedBytes(path, policy, "path")
        val pngSignature = Array[Byte](-119, 80, 78, 71, 13, 10, 26, 10)
        if data.length >= pngSignature.length && java.util.Arrays.equals(data.take(pngSignature.length), pngSignature) then
          Images.rejectProtectedPng(data, "path")
        val filename = path.getFileName.toString
        val dot = filename.lastIndexOf('.')
        val suffix = if dot >= 0 && filename.substring(dot).matches("\\.[A-Za-z0-9]{1,8}") then filename.substring(dot) else ".data"
        val staged = if tempFile == null then services.generator.tempFile(suffix) else tempFile(suffix)
        temporary = Some(staged)
        Files.write(staged, data)
        params("path") = staged.toString
      else require(args.str("path").isEmpty, "path is only valid for inspect")
      if ctx.isCancelled then throw ToolError("cancelled")
      awaitReady(ctx)
      val owner = java.util.UUID.randomUUID().toString
      val pending = services.client.controlNetFastloadOwned(params, owner)
      val completed = pending.transform { value =>
        temporary.foreach(Images.deleteQuietly)
        value
      }(ExecutionContext.parasitic)
      handedOff = true
      ctx.onCancel(() => { services.supervisor.requestInterrupt(Some(owner)); () })
      val value = Await.result(completed, timeout)
      if ctx.isCancelled then throw ToolError("cancelled")
      services.imageScrambleSession.synchronized {
        if operation != "version" && services.imageScrambleSession.state.revision != session.revision then
          throw ToolError("Image session changed; retry the inspection")
        ToolResult.json(value)
      }
    finally if !handedOff then temporary.foreach(Images.deleteQuietly)
