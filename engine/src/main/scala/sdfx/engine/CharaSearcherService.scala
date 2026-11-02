package sdfx.engine

import java.nio.file.{Files, Path}

/** Character Searcher engine requests: cut-out / tag / embed a set of sources into a dataset folder, and the embedding of
  * one search image. The search itself (similarity, tags, export) runs in `sdfx.core.charasearch` without the engine.
  */
final class CharaSearcherService(services: AppServices):
  /** The options the Import group edits; the engine validates them again. */
  final case class ImportOptions(maskThreshold: Double = 0.6, instanceThreshold: Double = 0.3, padding: Double = 0.1,
      tagThreshold: Double = 0.35, frameInterval: Double = 4.0, excludeTags: String = "")

  def importSources(datasetDir: Path, sources: Seq[Path], options: ImportOptions = ImportOptions(),
      listener: sdfx.core.protocol.EngineEvent => Unit = _ => ()): CharaSearcherHandle =
    require(sources.nonEmpty, "Choose at least one image, folder or video")
    require(sources.forall(Files.exists(_)), "Every source has to exist")
    Files.createDirectories(datasetDir)
    services.client.charaSearcherOwned(ujson.Obj(
      "action" -> "import", "dataset_dir" -> datasetDir.toAbsolutePath.toString,
      "paths" -> ujson.Arr.from(sources.map(_.toAbsolutePath.toString)),
      "options" -> ujson.Obj("mask_threshold" -> options.maskThreshold, "instance_threshold" -> options.instanceThreshold,
        "padding" -> options.padding, "tag_threshold" -> options.tagThreshold, "frame_interval" -> options.frameInterval,
        "exclude_tags" -> options.excludeTags)), listener)

  /** The CLIP embedding of one search image. */
  def embed(image: Path): CharaSearcherHandle =
    require(Files.isRegularFile(image), "The search image does not exist")
    services.client.charaSearcherOwned(ujson.Obj("action" -> "embed", "image_path" -> image.toAbsolutePath.toString))

object CharaSearcherService:
  def embeddingOf(result: ujson.Value): Array[Float] = result("embedding").arr.map(_.num.toFloat).toArray
