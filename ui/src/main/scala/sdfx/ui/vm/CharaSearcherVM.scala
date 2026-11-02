package sdfx.ui.vm

import sdfx.core.charasearch.*

import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** What a click on a result image does ("Click on the Image" of the extension). */
enum CharaClick:
  case Exclude, Threshold

/** State of the Character Searcher tab: the datasets under `root`, the selected ones, the query and the visible result.
  * No JavaFX. Reading datasets is blocking; the tab loads them off the UI thread and installs them with `install`.
  */
final class CharaSearcherVM(val root: Path):
  private var loaded = Map.empty[String, CharaDataset]
  private var selectedNames = Vector.empty[String]
  private var hits = Vector.empty[CharaHit]

  var queryVector: Option[Array[Float]] = None
  var queryImage: Option[Path] = None
  var threshold: Double = 0.8
  var tags: String = ""
  var negativeTags: String = ""
  var click: CharaClick = CharaClick.Exclude
  private var excluded = Set.empty[String]

  def datasetNames: Vector[String] = CharaDataset.list(root)
  def selected: Vector[String] = selectedNames
  def datasets: Vector[CharaDataset] = selectedNames.flatMap(loaded.get)
  def results: Vector[CharaHit] = hits
  def excludedCount: Int = excluded.size
  def total: Int = datasets.map(_.size).sum
  def datasetDir(name: String): Path = root.resolve(name)

  /** A folder name that is safe on every file system. */
  def validName(name: String): Option[String] =
    Option(name).map(_.trim).filter(n => n.nonEmpty && n.length <= 64 && n.matches("""[\p{L}\p{N}_\-. ]+""") && !n.startsWith(".") && !n.endsWith("."))

  /** Blocking: reads one dataset from disk. */
  def read(name: String): CharaDataset = CharaDataset.load(datasetDir(name), name)

  /** Installs freshly read datasets (replacing a stale copy) and re-runs the search. */
  def install(datasets: Seq[CharaDataset]): Unit =
    loaded = loaded ++ datasets.map(d => d.name -> d)
    search()

  def select(names: Seq[String]): Unit =
    selectedNames = names.toVector.distinct
    search()

  def forget(name: String): Unit =
    loaded -= name
    selectedNames = selectedNames.filterNot(_ == name)
    search()

  def query: CharaQuery = CharaQuery(queryVector, threshold, CharaTags.split(tags), CharaTags.split(negativeTags), excluded)

  def search(): Vector[CharaHit] =
    hits = CharaSearch.run(datasets, query)
    hits

  /** The effect of clicking a result. Returns a short status text. */
  def clicked(hit: CharaHit): String = click match
    case CharaClick.Exclude =>
      excluded += hit.key
      search()
      s"Excluded ${hit.entry.path}"
    case CharaClick.Threshold =>
      hit.similarity match
        case Some(s) =>
          threshold = math.floor(s * 1000) / 1000
          search()
          f"Threshold set to $threshold%.3f"
        case None => "Choose a search image first; the threshold needs a similarity"

  def resetExcluded(): Unit =
    excluded = Set.empty
    search()

  def setImage(path: Option[Path], vector: Option[Array[Float]]): Unit =
    queryImage = path
    queryVector = vector
    search()

  /** Blocking export of the displayed images. */
  def exportHits(dir: Path, additional: String, excludeTags: String): Either[String, CharaExport.Result] =
    if hits.isEmpty then Left("There is nothing to export")
    else
      try Right(CharaExport.run(hits, dir, CharaTags.split(additional), CharaTags.split(excludeTags)))
      catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
