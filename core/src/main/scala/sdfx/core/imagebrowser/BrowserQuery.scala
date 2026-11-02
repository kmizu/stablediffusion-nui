package sdfx.core.imagebrowser

import sdfx.core.infotext.{Infotext, ParsedInfotext}

import java.nio.file.Path
import java.util.regex.{Pattern, PatternSyntaxException}

/** Generation text of one image with the fields the browser sorts and filters by. */
final case class ImageMeta(parameters: Option[String]):
  lazy val parsed: Option[ParsedInfotext] = parameters.map(Infotext.parse)
  private def field(k: String): Option[String] = parsed.flatMap(_.get(k)).map(_.trim).filter(_.nonEmpty)
  def prompt: String = parsed.map(_.prompt).getOrElse("")
  def negativePrompt: String = parsed.map(_.negativePrompt).getOrElse("")
  def steps: Option[Int] = field("Steps").flatMap(_.toIntOption)
  def cfgScale: Option[Double] = field("CFG scale").flatMap(_.toDoubleOption)
  def seed: Option[Long] = field("Seed").flatMap(_.toLongOption)
  def sampler: Option[String] = field("Sampler")
  def model: Option[String] = field("Model")
  def modelHash: Option[String] = field("Model hash")
  def aestheticScore: Option[Double] = field("aesthetic_score").flatMap(_.toDoubleOption)
  def size: Option[(Int, Int)] = field("Size").flatMap { s =>
    s.split('x') match
      case Array(w, h) => for a <- w.trim.toIntOption; b <- h.trim.toIntOption yield (a, b)
      case _ => None
  }
  /** Search text without the negative prompt (prompt line(s) + settings line). */
  def positiveText: String = parsed.fold("")(p => p.prompt + "\n" + p.fields.map((k, v) => s"$k: $v").mkString(", "))

/** One listed file. `ranking` is the effective ranking (store entry, else the value embedded in the PNG). */
final case class ImageRecord(path: Path, modified: Long, size: Long, meta: ImageMeta, embeddedRanking: Option[Int] = None):
  def fileName: String = path.getFileName.toString
  def isVideo: Boolean = FileMetadata.isVideo(path)

/** Sort keys, in the documented upstream order. */
object SortKeys:
  val All: Seq[String] = Seq("path name", "date", "aesthetic_score", "random", "cfg scale", "steps", "seed", "sampler", "size", "model", "model hash", "ranking")
object NegativeSearch:
  val All: Seq[String] = Seq("No", "Yes", "Only")
object RankingFilters:
  val All: Seq[String] = Seq("All", "1", "2", "3", "4", "5", "None", "Min-max")

final case class BrowserQuery(
    sortBy: String = "date",
    ascending: Boolean = false,
    filenameKeyword: String = "",
    exifKeyword: String = "",
    negativePromptSearch: String = "No",
    useRegex: Boolean = false,
    rankingFilter: String = "All",
    rankingMin: Int = 1,
    rankingMax: Int = 5,
    aestheticMin: String = "",
    aestheticMax: String = "",
    randomSeed: Long = 0L
)

/** Result of applying a query: the ordered records and any problem with the user's input (never a thrown error). */
final case class QueryResult(records: Vector[ImageRecord], warnings: Vector[String])

object BrowserQueryEngine:
  /** Natural ordering: digit runs compare numerically, the rest case-insensitively. */
  def naturalCompare(a: String, b: String): Int =
    var i = 0
    var j = 0
    while i < a.length && j < b.length do
      if a(i).isDigit && b(j).isDigit then
        var ie = i; while ie < a.length && a(ie).isDigit do ie += 1
        var je = j; while je < b.length && b(je).isDigit do je += 1
        val x = BigInt(a.substring(i, ie)); val y = BigInt(b.substring(j, je))
        val c = x.compare(y)
        if c != 0 then return c
        i = ie; j = je
      else
        val c = a(i).toLower.compare(b(j).toLower)
        if c != 0 then return c
        i += 1; j += 1
    (a.length - i).compare(b.length - j)

  private def parseBound(label: String, text: String, warnings: scala.collection.mutable.Buffer[String]): Option[Double] =
    val t = text.trim
    if t.isEmpty then None
    else t.toDoubleOption match
      case some @ Some(_) => some
      case None => warnings += s"$label '$t' is not a number; ignored"; None

  def apply(records: Seq[ImageRecord], q: BrowserQuery, ranking: ImageRecord => Option[Int]): QueryResult =
    val warnings = scala.collection.mutable.Buffer.empty[String]
    var rs = records.toVector

    val fileKw = q.filenameKeyword.trim.toLowerCase
    if fileKw.nonEmpty then rs = rs.filter(_.path.toString.toLowerCase.contains(fileKw))

    val exifKw = q.exifKeyword.trim
    if exifKw.nonEmpty then
      def haystack(r: ImageRecord): String = q.negativePromptSearch match
        case "Only" => r.meta.negativePrompt
        case "Yes" => r.meta.parameters.getOrElse("")
        case _ => r.meta.positiveText
      if q.useRegex then
        try
          val p = Pattern.compile(exifKw, Pattern.DOTALL)
          rs = rs.filter(r => r.meta.parameters.isDefined && p.matcher(haystack(r)).find())
        catch case e: PatternSyntaxException => warnings += s"Regex error: ${e.getDescription}; EXIF search ignored"
      else
        val kw = exifKw.toLowerCase
        rs = rs.filter(r => r.meta.parameters.isDefined && haystack(r).toLowerCase.contains(kw))

    q.rankingFilter match
      case "All" => ()
      case "None" => rs = rs.filter(r => ranking(r).isEmpty)
      case "Min-max" =>
        val lo = q.rankingMin.min(q.rankingMax); val hi = q.rankingMin.max(q.rankingMax)
        rs = rs.filter(r => ranking(r).exists(v => v >= lo && v <= hi))
      case n => n.toIntOption.foreach(v => rs = rs.filter(r => ranking(r).contains(v)))

    val lo = parseBound("Minimum score", q.aestheticMin, warnings)
    val hi = parseBound("Maximum score", q.aestheticMax, warnings)
    if lo.isDefined || hi.isDefined then
      rs = rs.filter(r => r.meta.aestheticScore.exists(s => lo.forall(s >= _) && hi.forall(s <= _)))

    QueryResult(sort(rs, q, ranking), warnings.toVector)

  /** Images lacking the sort field go last in both directions (they are never dropped). */
  def sort(rs: Vector[ImageRecord], q: BrowserQuery, ranking: ImageRecord => Option[Int]): Vector[ImageRecord] =
    def withKey[K](key: ImageRecord => Option[K])(using ord: Ordering[K]): Vector[ImageRecord] =
      val (have, missing) = rs.partition(r => key(r).isDefined)
      val sorted = have.sortBy(r => key(r).get)(using if q.ascending then ord else ord.reverse)
      sorted ++ missing.sortBy(_.path.toString)(using Ordering.fromLessThan[String]((a, b) => naturalCompare(a, b) < 0))
    given Ordering[String] = Ordering.fromLessThan[String]((a, b) => naturalCompare(a, b) < 0)
    q.sortBy match
      case "path name" =>
        val s = rs.sortBy(_.path.toString)(using Ordering.String)
        if q.ascending then s else s.reverse
      case "random" => new scala.util.Random(q.randomSeed).shuffle(rs)
      case "date" => withKey(r => Some((r.modified, r.path.toString)))
      case "aesthetic_score" => withKey(_.meta.aestheticScore)
      case "cfg scale" => withKey(_.meta.cfgScale)
      case "steps" => withKey(_.meta.steps)
      case "seed" => withKey(_.meta.seed)
      case "sampler" => withKey(_.meta.sampler)
      case "model" => withKey(_.meta.model)
      case "model hash" => withKey(_.meta.modelHash)
      case "size" => withKey(_.meta.size.map((w, h) => (w.toLong * h, w)))
      case "ranking" => withKey(r => Some(ranking(r).getOrElse(0)))
      case _ => rs

/** Page arithmetic shared by every tab. Pages are 1-based; out-of-range requests clamp. */
final case class Pager(total: Int, perPage: Int):
  require(perPage >= 1, "perPage must be positive")
  def pageCount: Int = math.max(1, (total + perPage - 1) / perPage)
  def clamp(page: Int): Int = page.max(1).min(pageCount)
  def slice[A](items: Vector[A], page: Int): Vector[A] =
    val p = clamp(page)
    items.slice((p - 1) * perPage, math.min(items.size, p * perPage))
  def label(page: Int): String = s"Page Index (${clamp(page)}/$pageCount)"
