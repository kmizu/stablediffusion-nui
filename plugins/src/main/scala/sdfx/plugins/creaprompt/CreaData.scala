package sdfx.plugins.creaprompt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** The word lists CreaPrompt draws from: one category per `.csv` file (one entry per line), the `collection.txt`
  * of complete prompts and `.config` presets (category names, one per line). The lists shipped with the app are the
  * upstream ones (`creaprompt/` in the jar); files with the same name in `<app home>/creaprompt/csv/` replace them
  * and new `.csv` files there become new categories.
  */
object CreaData:
  /** `name` is the file name without the `x_x` order prefix and the extension, like upstream. */
  final case class Category(name: String, file: String, entries: Vector[String])
  final case class Library(categories: Vector[Category], presets: Vector[(String, Vector[String])], collection: Vector[String]):
    def category(name: String): Option[Category] = categories.find(_.name.equalsIgnoreCase(name.trim))
    def preset(name: String): Option[Vector[String]] = presets.find(_._1.equalsIgnoreCase(name.trim)).map(_._2)

  private val OrderPrefix = "^[0-9]_[0-9]".r
  val MaxFileBytes: Long = 8L * 1024 * 1024

  def categoryName(file: String): String =
    val stem = file.stripSuffix(".csv")
    if OrderPrefix.findPrefixOf(stem).isDefined then stem.drop(3) else stem

  /** Lines of a list file: BOM removed, trimmed, blank lines dropped. */
  def lines(text: String): Vector[String] =
    text.stripPrefix("﻿").split("\\r\\n|\\r|\\n").iterator.map(_.trim).filter(_.nonEmpty).toVector

  private def resourceText(name: String): String =
    val in = getClass.getResourceAsStream("/creaprompt/" + name)
    require(in != null, s"Bundled CreaPrompt file is missing: $name")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8) finally in.close()

  private lazy val bundledFiles: Vector[(String, String)] =
    lines(resourceText("index.txt")).map(n => n -> resourceText(n))

  /** Raw files of the user folder (plain file names only; oversize or unreadable files are skipped with a warning). */
  private def userFiles(dir: Path, warn: String => Unit): Vector[(String, String)] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      val stream = Files.list(dir)
      try
        stream.iterator.asScala.toVector.sortBy(_.getFileName.toString).flatMap { f =>
          val n = f.getFileName.toString
          val ok = Files.isRegularFile(f) && (n.endsWith(".csv") || n.endsWith(".config") || n == "collection.txt")
          if !ok then None
          else if Files.size(f) > MaxFileBytes then
            warn(s"CreaPrompt: skipped $n (larger than 8 MB)")
            None
          else
            try Some(n -> new String(Files.readAllBytes(f), StandardCharsets.UTF_8))
            catch case e: java.io.IOException =>
              warn(s"CreaPrompt: cannot read $n: ${e.getMessage}")
              None
        }
      finally stream.close()

  /** Bundled lists with the optional user folder merged on top. Categories are ordered by file name (upstream: "alphabetical
    * order"); a category without entries is dropped.
    */
  def load(userDir: Option[Path], warn: String => Unit = _ => ()): Library =
    val merged = scala.collection.mutable.LinkedHashMap[String, String]()
    bundledFiles.foreach(merged += _)
    userDir.foreach(d => userFiles(d, warn).foreach(merged += _))
    val files = merged.toVector.sortBy(_._1)(using Ordering.String)
    val cats = files.collect { case (n, t) if n.endsWith(".csv") => Category(categoryName(n), n, lines(t)) }.filter(_.entries.nonEmpty)
    val presets = files.collect { case (n, t) if n.endsWith(".config") => n.stripSuffix(".config") -> lines(t) }
    val collection = merged.get("collection.txt").map(lines).getOrElse(Vector.empty)
    Library(cats, presets, collection)

  def pick[T](xs: IndexedSeq[T], rnd: Random): T = xs(rnd.nextInt(xs.size))

  /** An entry can end in a comma left over from the CSV; two commas in a row would read as an empty tag. */
  def clean(entry: String): String = entry.trim.replaceAll("[,\\s]+$", "")
