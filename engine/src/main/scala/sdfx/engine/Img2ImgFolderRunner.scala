package sdfx.engine

import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.JobOutput

/** Runs one generation per snapshotted input. The caller owns the temporary-file factory. */
private[engine] object Img2ImgFolderRunner:
  def checkAllowedFolder(folder: Path, roots: Seq[Path]): Unit =
    val expected = folder.toAbsolutePath.normalize()
    val actual = expected.toRealPath()
    require(actual == expected && roots.exists { root =>
      val normalized = root.toAbsolutePath.normalize()
      normalized.toRealPath() == normalized && actual.startsWith(normalized)
    }, "Input folder no longer resolves inside an allowed directory")

  def recordedParams(params: GenerationParams, provenance: Option[(String, String)]): GenerationParams =
    provenance match
      case Some((staged, original)) if original.nonEmpty && params.img2img.exists(_.initImage == staged) =>
        params.copy(img2img = params.img2img.map(_.copy(initImage = original)),
          extra = params.extra.updated("NUI img2img source", original))
      case _ => params

  def run(
      directory: Path,
      params: GenerationParams,
      tempFile: String => Path,
      generate: (GenerationParams, Path) => JobOutput,
      progress: (Path, Int, Int) => Unit,
      check: () => Unit = () => (),
      allowedRoots: Seq[Path] = Nil
  ): JobOutput =
    val img2img = params.img2img.getOrElse(throw new IllegalArgumentException("img2img parameters are required"))
    val items = Img2ImgFolderManifest.scan(directory, check, allowedRoots)
    val images = Vector.newBuilder[sdfx.core.plugins.api.OutputImage]
    val grids = Vector.newBuilder[sdfx.core.plugins.api.OutputImage]
    items.zipWithIndex.foreach { (item, index) =>
      check()
      val name = item.path.getFileName.toString
      val dot = name.lastIndexOf('.')
      val suffix = if dot >= 0 then name.substring(dot) else ".img"
      val staged = tempFile(suffix)
      try
        val out = Files.newOutputStream(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
        try Img2ImgFolderManifest.copyVerified(item, out, check, allowedRoots) finally out.close()
        check()
        val result = generate(params.copy(img2img = Some(img2img.copy(initImage = staged.toString))), item.path)
        images ++= result.images
        grids ++= result.grids
        progress(item.path, index + 1, items.size)
      finally Files.deleteIfExists(staged)
    }
    JobOutput(images.result(), grids.result())
