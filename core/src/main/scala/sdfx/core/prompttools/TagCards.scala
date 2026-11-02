package sdfx.core.prompttools

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** A shortcut card: a prompt snippet stored as `<category>/<name>.tag` (Easy Tag Insert layout). */
final case class TagCard(category: String, name: String, text: String):
  def id: String = if category.isEmpty then name else category + "/" + name

/** Folder of `.tag` files. The folder tree is the category tree; the file stem is the card name.
  * Every name is validated so a card can never be written outside the folder.
  */
final class TagCardStore(val dir: Path):
  import TagCardStore.*

  def list(): Vector[TagCard] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      val root = dir.toAbsolutePath.normalize()
      val stream = Files.walk(root, 8)
      val files = try stream.iterator.asScala.filter(p => Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        && p.getFileName.toString.endsWith(Ext)).toVector finally stream.close()
      files.take(MaxCards).flatMap { p =>
        try
          if Files.size(p) > MaxCardBytes then None
          else
            val rel = root.relativize(p)
            val category = (0 until rel.getNameCount - 1).map(rel.getName(_).toString).mkString("/")
            val name = rel.getFileName.toString.stripSuffix(Ext)
            Some(TagCard(category, name, clean(new String(Files.readAllBytes(p), UTF_8))))
          catch case _: java.io.IOException => None
      }.sortBy(c => (c.category.toLowerCase(java.util.Locale.ROOT), c.name.toLowerCase(java.util.Locale.ROOT), c.category, c.name))

  def save(card: TagCard): TagCard =
    val normalized = card.copy(category = normalizeCategory(card.category), name = card.name.trim)
    requireName(normalized.name, "card name")
    require(normalized.text.length <= MaxCardBytes / 4, "Card text is too long")
    require(normalized.text.trim.nonEmpty, "Card text is empty")
    val target = fileOf(normalized.category, normalized.name)
    require(Files.exists(target) || list().size < MaxCards, s"At most $MaxCards cards are supported")
    sdfx.core.config.AtomicFiles.writeString(target, normalized.text)
    normalized

  def delete(card: TagCard): Boolean =
    val target = fileOf(normalizeCategory(card.category), card.name.trim)
    val removed = Files.deleteIfExists(target)
    // drop folders left empty, never the root
    var parent = target.getParent
    val root = dir.toAbsolutePath.normalize()
    while removed && parent != null && parent != root && parent.startsWith(root) && isEmptyDir(parent) do
      Files.delete(parent); parent = parent.getParent
    removed

  private def isEmptyDir(p: Path): Boolean = { val s = Files.list(p); try !s.findAny().isPresent finally s.close() }

  private def fileOf(category: String, name: String): Path =
    val root = dir.toAbsolutePath.normalize()
    val parts = if category.isEmpty then Nil else category.split('/').toList
    val resolved = parts.foldLeft(root)(_.resolve(_)).resolve(name + Ext).normalize()
    require(resolved.startsWith(root), "Card path leaves the cards folder")
    resolved

  /** Seed a few cards the first time the folder is used. */
  def ensureSamples(): Unit =
    if list().isEmpty && !Files.isDirectory(dir) then
      save(TagCard("Starting Prompts", "Positive", "(high quality, best quality)"))
      save(TagCard("Starting Prompts", "Negative", "(low quality, worst quality)"))

object TagCardStore:
  val Ext = ".tag"
  val MaxCards = 2000
  val MaxCardBytes = 200 * 1024

  private val Reserved = Set("con", "prn", "aux", "nul") ++ (1 to 9).flatMap(i => Seq(s"com$i", s"lpt$i"))

  private def clean(s: String): String = s.stripPrefix("﻿").stripTrailing()

  /** Accept `\` or `/` between segments; return `/`-joined segments with each one validated. */
  def normalizeCategory(raw: String): String =
    val segs = raw.split("[\\\\/]").map(_.trim).filter(_.nonEmpty).toList
    segs.foreach(requireName(_, "category"))
    require(segs.size <= 6, "Category is nested too deeply")
    segs.mkString("/")

  def requireName(name: String, what: String): Unit =
    require(name.nonEmpty, s"Enter a $what")
    require(name.length <= 100, s"The $what is too long")
    require(name != "." && name != ".." && !name.endsWith(".") && !name.endsWith(" "), s"Invalid $what '$name'")
    require(!name.exists(c => c.isControl || "<>:\"/\\|?*".indexOf(c) >= 0), s"The $what may not contain < > : \" / \\ | ? * or control characters")
    require(!Reserved(name.takeWhile(_ != '.').toLowerCase(java.util.Locale.ROOT)), s"'$name' is a reserved file name")
