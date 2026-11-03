package sdfx.mcp

import java.nio.file.{Files, Path}

/** Capture the exact verified bytes that a VAE worker will later consume. */
private[mcp] object VaeInputSnapshot:
  def capture(raw: String, field: String, image: Boolean, policy: PathPolicy, base: Path,
      tempFile: String => Path, afterResolve: () => Unit = () => ()): (Path, String) =
    val original = policy.resolve(raw, field, base)
    afterResolve()
    val data = Images.readVerifiedBytes(original, policy, field)
    val signature = Array[Byte](-119, 80, 78, 71, 13, 10, 26, 10)
    if image && data.take(8).sameElements(signature) then Images.rejectProtectedPng(data, field)
    val filename = original.getFileName.toString
    val dot = filename.lastIndexOf('.')
    val ext = if dot >= 0 then filename.substring(dot) else ""
    val suffix = if ext.matches("\\.[A-Za-z0-9]{1,16}") then ext else ".data"
    val staged = tempFile(suffix)
    Files.write(staged, data)
    (staged, original.toString)
