package sdfx.core.charasearch

import java.io.{ByteArrayOutputStream, DataInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Reader of the `embedding.npz` written by the engine (`numpy.savez_compressed(embedding=float32[N, D])`). */
object Npy:
  /** Rows of the 2-D little-endian float32 array `name` of an npz file. */
  def readNpz(file: Path, name: String = "embedding"): Array[Array[Float]] =
    val zip = new ZipFile(file.toFile)
    try
      val entry = Option(zip.getEntry(name + ".npy")).getOrElse(throw new java.io.IOException(s"$file has no array '$name'"))
      val in = zip.getInputStream(entry)
      try parse(in.readAllBytes())
      finally in.close()
    finally zip.close()

  def parse(bytes: Array[Byte]): Array[Array[Float]] =
    if bytes.length < 10 || bytes(0) != 0x93.toByte || new String(bytes, 1, 5, StandardCharsets.ISO_8859_1) != "NUMPY" then
      throw new java.io.IOException("not an .npy array")
    val major = bytes(6) & 0xff
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val (headerLen, headerStart) = if major == 1 then (buf.getShort(8) & 0xffff, 10) else (buf.getInt(8), 12)
    val header = new String(bytes, headerStart, headerLen, StandardCharsets.ISO_8859_1)
    if !header.contains("'<f4'") then throw new java.io.IOException(s"unsupported array type: $header")
    if header.contains("'fortran_order': True") then throw new java.io.IOException("Fortran ordered arrays are not supported")
    val shape = """'shape':\s*\((\d+),\s*(\d+)\s*,?\)""".r.findFirstMatchIn(header)
      .getOrElse(throw new java.io.IOException(s"expected a 2-D array: $header"))
    val rows = shape.group(1).toInt
    val cols = shape.group(2).toInt
    val dataStart = headerStart + headerLen
    if bytes.length - dataStart != rows.toLong * cols * 4 then throw new java.io.IOException("array size does not match its shape")
    val data = buf.position(dataStart).asInstanceOf[ByteBuffer].slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    Array.tabulate(rows) { _ =>
      val row = new Array[Float](cols)
      data.get(row)
      row
    }

/** One cut-out character: its file, size, tags and (L2-normalised) CLIP embedding. */
final case class CharaEntry(path: String, width: Int, height: Int, tags: Vector[String], vector: Array[Float]):
  /** Tags in comparison form. */
  lazy val tagSet: Set[String] = tags.map(CharaTags.normalize).toSet

/** A dataset folder: cut-out PNGs, `<name>.txt` tags and the `embedding.json` / `embedding.npz` index. */
final case class CharaDataset(name: String, dir: Path, entries: Vector[CharaEntry], problems: Vector[String]):
  def file(entry: CharaEntry): Path = dir.resolve(entry.path)
  def size: Int = entries.size

object CharaDataset:
  val IndexJson = "embedding.json"
  val IndexNpz = "embedding.npz"

  def isDataset(dir: Path): Boolean = Files.isRegularFile(dir.resolve(IndexJson)) && Files.isRegularFile(dir.resolve(IndexNpz))

  /** Names of the dataset folders directly below `root`, sorted. */
  def list(root: Path): Vector[String] =
    if !Files.isDirectory(root) then Vector.empty
    else
      val stream = Files.newDirectoryStream(root)
      try stream.asScala.filter(isDataset).map(_.getFileName.toString).toVector.sortBy(_.toLowerCase)
      finally stream.close()

  def normalized(v: Array[Float]): Array[Float] =
    var sum = 0.0
    var i = 0
    while i < v.length do { sum += v(i).toDouble * v(i); i += 1 }
    val norm = math.sqrt(sum)
    if norm == 0.0 then v.clone() else v.map(x => (x / norm).toFloat)

  /** Reads the index of `dir`. Files that no longer exist are listed in `problems` and skipped. */
  def load(dir: Path, name: String = ""): CharaDataset =
    val label = if name.nonEmpty then name else dir.getFileName.toString
    val json = ujson.read(Files.readString(dir.resolve(IndexJson), StandardCharsets.UTF_8))
    val files = json("files").arr
    val vectors = Npy.readNpz(dir.resolve(IndexNpz))
    if files.size != vectors.length then
      throw new java.io.IOException(s"$IndexJson lists ${files.size} files but $IndexNpz holds ${vectors.length} vectors")
    val problems = Vector.newBuilder[String]
    val entries = Vector.newBuilder[CharaEntry]
    files.zip(vectors).foreach { case (item, vector) =>
      val rel = item("path").str
      val file = dir.resolve(rel).normalize
      if !file.startsWith(dir.normalize) then problems += s"$rel: outside the dataset folder"
      else if !Files.isRegularFile(file) then problems += s"$rel: missing"
      else
        val txt = file.resolveSibling(file.getFileName.toString.replaceAll("\\.[^.]+$", "") + ".txt")
        val tags = if Files.isRegularFile(txt) then CharaTags.split(Files.readString(txt, StandardCharsets.UTF_8)) else Vector.empty
        entries += CharaEntry(rel, item.obj.get("width").map(_.num.toInt).getOrElse(0), item.obj.get("height").map(_.num.toInt).getOrElse(0),
          tags, normalized(vector))
    }
    CharaDataset(label, dir, entries.result(), problems.result())

object CharaTags:
  private val Escaped = """\\([\\()])""".r

  /** Comparison form of a tag: escapes removed, underscores as spaces, trimmed, lower case. */
  def normalize(tag: String): String = Escaped.replaceAllIn(tag, m => java.util.regex.Matcher.quoteReplacement(m.group(1))).replace('_', ' ').trim.toLowerCase

  /** A comma separated tag list as typed or stored (empty items dropped, text kept as written). */
  def split(text: String): Vector[String] = text.split(',').iterator.map(_.trim).filter(_.nonEmpty).toVector
