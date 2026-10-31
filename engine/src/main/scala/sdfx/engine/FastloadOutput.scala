package sdfx.engine

import sdfx.core.gallery.OutputNaming
import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}

/** Publish only newly created outputs. The caller owns and cleans the staged PNG. */
private[engine] object FastloadOutput:
  def publish(stage: Path, dir: Path, seed: Long, payload: Array[Byte], format: String, check: () => Unit): Path = synchronized {
    require(Set("embedded", "sidecar", "both")(format), "Invalid Fastload output format")
    require(payload != null && payload.nonEmpty && payload.length <= 128 * 1024 * 1024, "Invalid Fastload payload")
    require(Files.isRegularFile(stage) && !Files.isSymbolicLink(stage), "Invalid staged image")
    check()
    if format != "sidecar" then Files.write(stage, payload, StandardOpenOption.APPEND)
    val sideStage = if format != "embedded" then
      val path = Files.createTempFile(dir, ".fastload-sidecar-", ".tmp")
      try Files.write(path, payload)
      catch
        case e: Throwable => Files.deleteIfExists(path); throw e
      Some(path)
    else None
    try
      check()
      var index = OutputNaming.nextIndex(dir)
      var published: Option[Path] = None
      while published.isEmpty do
        val base = String.format(java.util.Locale.ROOT, "%05d", Int.box(index)) + s"-$seed"
        val image = dir.resolve(base + ".png")
        val side = dir.resolve(base + ".cni")
        if sideStage.nonEmpty && Files.exists(side, java.nio.file.LinkOption.NOFOLLOW_LINKS) then index += 1
        else
          try
            Files.createLink(image, stage)
            published = Some(image)
            // Once the image is visible, complete the associated sidecar publication; a failure
            // leaves the new image intact and is surfaced to the caller instead of overwriting.
            sideStage.foreach(s => Files.createLink(side, s))
          catch
            case _: FileAlreadyExistsException if published.isEmpty => index += 1
      published.get
    finally sideStage.foreach(Files.deleteIfExists(_))
  }
