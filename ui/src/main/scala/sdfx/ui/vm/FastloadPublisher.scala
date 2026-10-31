package sdfx.ui.vm

import sdfx.core.png.ImageScrambleSession
import java.nio.file.{Files, Path}

/** Publish a readable legacy sidecar only while its submission session remains unprotected. */
object FastloadPublisher:
  def publish(target: Path, bytes: Array[Byte], session: ImageScrambleSession, revision: Long): Unit =
    require(target.getFileName.toString.toLowerCase(java.util.Locale.ROOT).endsWith(".cni"), "Output must be a .cni file")
    require(bytes != null && bytes.nonEmpty && bytes.length <= 128 * 1024 * 1024, "Invalid Fastload output")
    session.synchronized {
      val state = session.state
      require(state.revision == revision && state.secret.isEmpty, "Image session changed or is protected")
      val absolute = target.toAbsolutePath.normalize()
      val parent = absolute.getParent
      require(parent != null && Files.isDirectory(parent), "Output directory does not exist")
      val stage = Files.createTempFile(parent, ".fastload-stage-", ".tmp")
      try
        Files.write(stage, bytes)
        // createLink fails if the destination exists, including a symlink.
        Files.createLink(absolute, stage)
      finally Files.deleteIfExists(stage)
    }
