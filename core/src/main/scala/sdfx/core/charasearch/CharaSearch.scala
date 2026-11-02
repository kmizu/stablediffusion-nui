package sdfx.core.charasearch

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.util.control.NonFatal

/** One search result: the entry, its dataset and the cosine similarity to the query image (None without a query image). */
final case class CharaHit(dataset: CharaDataset, entry: CharaEntry, similarity: Option[Double]):
  /** Identity used for the "Exclude" clicks. */
  def key: String = dataset.name + "/" + entry.path
  def file: Path = dataset.file(entry)

/** What the user typed in the Search group. */
final case class CharaQuery(
    image: Option[Array[Float]] = None, // the embedding of the search image (any length; normalised here)
    threshold: Double = 0.0,            // minimum cosine similarity; ignored without a search image
    tags: Vector[String] = Vector.empty,
    negativeTags: Vector[String] = Vector.empty,
    excluded: Set[String] = Set.empty   // CharaHit.key of images the user clicked away
)

object CharaSearch:
  /** Results of the query over the selected datasets.
    *
    * With a search image the hits are sorted by similarity (highest first) and hidden below the threshold; without
    * one they keep dataset and file order. Every search tag has to be among an image's tags; no negative tag may be.
    */
  def run(datasets: Seq[CharaDataset], query: CharaQuery): Vector[CharaHit] =
    val reference = query.image.map(CharaDataset.normalized)
    val include = query.tags.map(CharaTags.normalize).filter(_.nonEmpty)
    val exclude = query.negativeTags.map(CharaTags.normalize).filter(_.nonEmpty)
    val hits = Vector.newBuilder[CharaHit]
    for dataset <- datasets; entry <- dataset.entries do
      val hit = CharaHit(dataset, entry, reference.map(cosine(_, entry.vector)))
      val tags = entry.tagSet
      val similar = hit.similarity.forall(_ >= query.threshold)
      if similar && include.forall(tags.contains) && !exclude.exists(tags.contains) && !query.excluded.contains(hit.key) then hits += hit
    val all = hits.result()
    if reference.isDefined then all.sortBy(h => -h.similarity.getOrElse(0.0)) else all

  /** Dot product of two L2-normalised vectors; vectors of different length never match. */
  def cosine(a: Array[Float], b: Array[Float]): Double =
    if a.length != b.length then -1.0
    else
      var sum = 0.0
      var i = 0
      while i < a.length do { sum += a(i).toDouble * b(i); i += 1 }
      sum

/** The Export group: copies the displayed images and writes a caption next to each (for LoRA training and the like). */
object CharaExport:
  final case class Result(written: Vector[Path], skipped: Vector[String])

  /** The caption of one image: the additional tags first, then its own tags without the excluded ones, no duplicates. */
  def caption(entry: CharaEntry, additional: Seq[String], excluded: Seq[String]): String =
    val drop = excluded.map(CharaTags.normalize).filter(_.nonEmpty).toSet
    val seen = scala.collection.mutable.LinkedHashMap.empty[String, String]
    (additional.map(_.trim).filter(_.nonEmpty) ++ entry.tags).foreach { tag =>
      val key = CharaTags.normalize(tag)
      if !drop.contains(key) && !seen.contains(key) then seen(key) = tag
    }
    seen.values.mkString(", ")

  /** Exports `hits` into `dir` as `NNNNN_<source>.png` + `.txt`. Existing files are never overwritten. */
  def run(hits: Seq[CharaHit], dir: Path, additional: Seq[String], excluded: Seq[String]): Result =
    require(hits.nonEmpty, "There is nothing to export")
    Files.createDirectories(dir)
    val written = Vector.newBuilder[Path]
    val skipped = Vector.newBuilder[String]
    var counter = 0
    hits.foreach { hit =>
      val base = hit.entry.path.split('/').last.replaceAll("\\.[^.]+$", "").replaceAll("[^\\w.\\-]+", "_")
      var target = dir.resolve(f"$counter%05d_$base.png")
      while Files.exists(target) || Files.exists(txtOf(target)) do
        counter += 1
        target = dir.resolve(f"$counter%05d_$base.png")
      counter += 1
      try
        Files.copy(hit.file, target)
        Files.writeString(txtOf(target), caption(hit.entry, additional, excluded), StandardCharsets.UTF_8)
        written += target
      catch case NonFatal(e) =>
        Files.deleteIfExists(target)
        skipped += s"${hit.entry.path}: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
    }
    Result(written.result(), skipped.result())

  private def txtOf(png: Path): Path = png.resolveSibling(png.getFileName.toString.replaceAll("\\.png$", "") + ".txt")
