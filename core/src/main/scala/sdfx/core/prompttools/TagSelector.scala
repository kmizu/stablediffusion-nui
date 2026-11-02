package sdfx.core.prompttools

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Random
import scala.util.control.NonFatal

/** One node of a tag file: a button that inserts `text`, or a group of nodes. */
enum TagNode:
  case Leaf(label: String, text: String)
  case Group(name: String, children: Vector[TagNode])

  /** Every leaf below this node (a leaf yields itself). */
  def leaves: Vector[Leaf] = this match
    case l: Leaf => Vector(l)
    case g: Group => g.children.flatMap(_.leaves)

/** One YAML tag file, shown as one tab. `error` is set when the file could not be read. */
final case class TagFile(name: String, nodes: Vector[TagNode], error: Option[String] = None):
  def leaves: Vector[TagNode.Leaf] = nodes.flatMap(_.leaves)

final case class TagHit(file: String, path: Vector[String], leaf: TagNode.Leaf)

final case class TagLibrary(files: Vector[TagFile])

/** Tag-button files in the D2 Prompt Selector layout (one YAML file per tab, nested groups, `__config__.yml` with
  * a `sort:` list). Written from the documented behaviour only.
  */
object TagSelector:
  import YNode.*
  val ConfigFile = "__config__"
  val MaxFiles = 500
  val MaxFileBytes = 1024 * 1024

  /** Convert a parsed YAML tree. `group: [tag, ...]` and `group: {label: tag}` both work, nested to any depth;
    * a bare list item is both label and text.
    */
  def nodes(yaml: YNode): Vector[TagNode] = yaml match
    case YStr(v) => if v.isEmpty then Vector.empty else Vector(TagNode.Leaf(v, v))
    case YSeq(items) => items.flatMap(nodes)
    case YMap(entries) => entries.map {
      case (name, YStr(v)) => TagNode.Leaf(name, v)
      case (name, child) => TagNode.Group(name, nodes(child))
    }

  def parseFile(name: String, yaml: String): TagFile =
    try TagFile(name, nodes(MiniYaml.parse(yaml)))
    catch case e: MiniYaml.YamlError => TagFile(name, Vector.empty, Some(e.getMessage))

  private def sortOrder(yaml: String): Vector[String] =
    try MiniYaml.parse(yaml) match
      case YMap(entries) => entries.collectFirst { case ("sort", YSeq(items)) => items.collect { case YStr(s) => s } }.getOrElse(Vector.empty)
      case _ => Vector.empty
    catch case NonFatal(_) => Vector.empty

  /** Read every `*.yml` / `*.yaml` directly inside `dir`; broken files are kept with their error. */
  def load(dir: Path): TagLibrary =
    if !Files.isDirectory(dir) then TagLibrary(Vector.empty)
    else
      val stream = Files.list(dir)
      val paths = try stream.iterator.asScala.filter(Files.isRegularFile(_)).toVector finally stream.close()
      def stem(p: Path): String = p.getFileName.toString.replaceFirst("(?i)\\.ya?ml$", "")
      val tagPaths = paths.filter(p => p.getFileName.toString.toLowerCase(java.util.Locale.ROOT).matches(".*\\.ya?ml"))
      val (configs, tags) = tagPaths.partition(p => stem(p) == ConfigFile)
      val order = configs.headOption.map(c => sortOrder(read(c).getOrElse(""))).getOrElse(Vector.empty)
      val ranked = tags.sortBy(p => (order.indexOf(stem(p)) match { case -1 => Int.MaxValue; case i => i }, stem(p).toLowerCase(java.util.Locale.ROOT), stem(p)))
      TagLibrary(ranked.take(MaxFiles).map { p =>
        read(p) match
          case Right(text) => parseFile(stem(p), text)
          case Left(err) => TagFile(stem(p), Vector.empty, Some(err))
      })

  private def read(p: Path): Either[String, String] =
    try
      if Files.size(p) > MaxFileBytes then Left("file is larger than 1 MB")
      else
        val dec = UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        Right(dec.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))

  /** Leaves whose label or text contains every word of `query` (case-insensitive). */
  def search(library: TagLibrary, query: String): Vector[TagHit] =
    val words = query.toLowerCase(java.util.Locale.ROOT).split("\\s+").filter(_.nonEmpty)
    if words.isEmpty then Vector.empty
    else
      def walk(file: String, path: Vector[String], ns: Vector[TagNode]): Vector[TagHit] = ns.flatMap {
        case l: TagNode.Leaf =>
          val hay = (l.label + "\n" + l.text).toLowerCase(java.util.Locale.ROOT)
          if words.forall(hay.contains) then Vector(TagHit(file, path, l)) else Vector.empty
        case g: TagNode.Group => walk(file, path :+ g.name, g.children)
      }
      library.files.flatMap(f => walk(f.name, Vector.empty, f.nodes))

  /** A random leaf from a group (any depth). None for an empty group. */
  def randomLeaf(group: TagNode.Group, rnd: Random): Option[TagNode.Leaf] =
    val all = group.leaves
    if all.isEmpty then None else Some(all(rnd.nextInt(all.size)))

  /** Create a small example file when the folder does not exist yet, so the tab is not empty. */
  def ensureSample(dir: Path): Unit =
    if !Files.isDirectory(dir) then
      Files.createDirectories(dir)
      Files.writeString(dir.resolve("sample.yml"),
        """# One file per tab. Groups can be nested; a group button inserts a random tag from inside it.
          |hair color:
          |  single:
          |    - black hair
          |    - blonde hair
          |    - brown hair
          |  multi:
          |    multicolored: multicolored hair
          |    gradient: gradient hair
          |quality:
          |  - masterpiece
          |  - best quality
          |  - highly detailed
          |""".stripMargin)
