package sdfx.core.imagebrowser

import sdfx.core.png.PngMetadata

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.CRC32
import scala.util.control.NonFatal

/** Embedded or side-car facts about one file. */
final case class FileFacts(parameters: Option[String], ranking: Option[Int])

object FileMetadata:
  val ImageExtensions: Set[String] = Set("png", "jpg", "jpeg", "webp", "gif")
  val VideoExtensions: Set[String] = Set("mp4", "mov", "avi", "wmv", "flv", "mkv", "webm", "mpeg", "mpg", "3gp", "ogv", "m4v")
  val RankingKey = "ranking"

  def extension(p: Path): String =
    val n = p.getFileName.toString
    val i = n.lastIndexOf('.')
    if i < 0 then "" else n.substring(i + 1).toLowerCase(java.util.Locale.ROOT)
  def isImage(p: Path): Boolean = ImageExtensions.contains(extension(p))
  def isVideo(p: Path): Boolean = VideoExtensions.contains(extension(p))
  def isMedia(p: Path): Boolean = isImage(p) || isVideo(p)

  /** `name.txt` next to `name.ext`. */
  def sidecar(p: Path): Path =
    val n = p.getFileName.toString
    val i = n.lastIndexOf('.')
    p.resolveSibling((if i < 0 then n else n.substring(0, i)) + ".txt")

  /** PNG text chunks, JPEG / WebP EXIF user comment, otherwise the matching `.txt` file. Never throws. */
  def read(p: Path): FileFacts =
    val ext = extension(p)
    val embedded: (Option[String], Option[Int]) =
      try ext match
        case "png" =>
          val texts = PngMetadata.readTexts(p)
          (texts.get(PngMetadata.ParametersKey).filter(_.nonEmpty), texts.get(RankingKey).flatMap(parseRanking))
        case "jpg" | "jpeg" | "webp" => (Exif.fromFile(p), None)
        case _ => (None, None)
      catch case NonFatal(_) => (None, None)
    val params = embedded._1.orElse(readSidecar(p))
    FileFacts(params, embedded._2)

  def parseRanking(s: String): Option[Int] = s.trim.toIntOption.filter(r => r >= 1 && r <= 5)

  private def readSidecar(p: Path): Option[String] =
    val t = sidecar(p)
    try
      if Files.isRegularFile(t) && Files.size(t) <= 1024 * 1024 then
        Some(new String(Files.readAllBytes(t), StandardCharsets.UTF_8)).map(_.trim).filter(_.nonEmpty)
      else None
    catch case NonFatal(_) => None

/** Minimal EXIF `UserComment` reader for JPEG (APP1) and WebP (`EXIF` chunk); A1111 stores the generation text there. */
object Exif:
  def fromFile(p: Path): Option[String] =
    val size = Files.size(p)
    if size > 64L * 1024 * 1024 then None else fromBytes(Files.readAllBytes(p))

  def fromBytes(b: Array[Byte]): Option[String] =
    if b.length > 12 && b(0) == 0xff.toByte && b(1) == 0xd8.toByte then jpeg(b)
    else if b.length > 12 && new String(b, 0, 4, StandardCharsets.ISO_8859_1) == "RIFF" && new String(b, 8, 4, StandardCharsets.ISO_8859_1) == "WEBP" then webp(b)
    else None

  private def u16be(b: Array[Byte], o: Int): Int = ((b(o) & 0xff) << 8) | (b(o + 1) & 0xff)

  private def jpeg(b: Array[Byte]): Option[String] =
    var o = 2
    while o + 4 <= b.length && (b(o) & 0xff) == 0xff do
      val marker = b(o + 1) & 0xff
      if marker == 0xda || marker == 0xd9 then return None
      val len = u16be(b, o + 2)
      if marker == 0xe1 && len >= 8 && o + 2 + len <= b.length && new String(b, o + 4, 4, StandardCharsets.ISO_8859_1) == "Exif" then
        return tiff(b, o + 10, o + 2 + len)
      o += 2 + len
    None

  private def webp(b: Array[Byte]): Option[String] =
    var o = 12
    while o + 8 <= b.length do
      val tag = new String(b, o, 4, StandardCharsets.ISO_8859_1)
      val len = (b(o + 4) & 0xff) | ((b(o + 5) & 0xff) << 8) | ((b(o + 6) & 0xff) << 16) | ((b(o + 7) & 0xff) << 24)
      if len < 0 || o + 8 + len > b.length then return None
      if tag == "EXIF" then
        val start = o + 8 + (if len >= 6 && new String(b, o + 8, 4, StandardCharsets.ISO_8859_1) == "Exif" then 6 else 0)
        return tiff(b, start, o + 8 + len)
      o += 8 + len + (len & 1)
    None

  /** Parses the TIFF block: IFD0 -> ExifIFD (tag 0x8769) -> UserComment (0x9286). */
  def tiff(b: Array[Byte], base: Int, end: Int): Option[String] =
    if end - base < 8 || end > b.length then return None
    val little = b(base) == 'I'.toByte
    def u16(o: Int): Int = if little then (b(o) & 0xff) | ((b(o + 1) & 0xff) << 8) else u16be(b, o)
    def u32(o: Int): Long =
      if little then (b(o) & 0xffL) | ((b(o + 1) & 0xffL) << 8) | ((b(o + 2) & 0xffL) << 16) | ((b(o + 3) & 0xffL) << 24)
      else ((b(o) & 0xffL) << 24) | ((b(o + 1) & 0xffL) << 16) | ((b(o + 2) & 0xffL) << 8) | (b(o + 3) & 0xffL)
    def ok(o: Long, n: Long): Boolean = o >= 0 && o + n <= end - base
    def entries(ifd: Long): Seq[(Int, Int, Long, Int)] = // tag, type, count, absolute value position
      if !ok(ifd, 2) then Nil
      else
        val n = u16(base + ifd.toInt)
        (0 until n).flatMap { i =>
          val e = base + ifd.toInt + 2 + 12 * i
          if e + 12 > end then None else Some((u16(e), u16(e + 2), u32(e + 4), e + 8))
        }
    val ifd0 = entries(u32(base + 4))
    ifd0.find(_._1 == 0x8769).flatMap { e =>
      entries(u32(e._4)).find(_._1 == 0x9286).flatMap { c =>
        val count = c._3
        val pos = if count <= 4 then c._4.toLong - base else u32(c._4)
        if count < 8 || !ok(pos, count) then None else decode(b.slice(base + pos.toInt, base + pos.toInt + count.toInt))
      }
    }

  private def decode(raw: Array[Byte]): Option[String] =
    val code = new String(raw, 0, 8, StandardCharsets.ISO_8859_1)
    val body = raw.drop(8)
    val text = code.takeWhile(_ != 0) match
      case "UNICODE" =>
        // piexif / A1111 write UTF-16BE; honour an explicit BOM, and fall back to LE when the high bytes say so.
        if body.length >= 2 && body(0) == 0xff.toByte && body(1) == 0xfe.toByte then new String(body.drop(2), StandardCharsets.UTF_16LE)
        else if body.length >= 2 && body(0) == 0xfe.toByte && body(1) == 0xff.toByte then new String(body.drop(2), StandardCharsets.UTF_16BE)
        else if body.length >= 2 && body(0) != 0 && body(1) == 0 then new String(body, StandardCharsets.UTF_16LE)
        else new String(body, StandardCharsets.UTF_16BE)
      case "ASCII" | "" => new String(body, StandardCharsets.UTF_8)
      case _ => new String(body, StandardCharsets.UTF_8)
    Some(text.replace("\u0000", "").trim).filter(_.nonEmpty)

  /** Builds a minimal big-endian TIFF block holding only a Unicode UserComment (used by tests and the README fixtures). */
  def encodeUserComment(text: String): Array[Byte] =
    val body = "UNICODE\u0000".getBytes(StandardCharsets.ISO_8859_1) ++ text.getBytes(StandardCharsets.UTF_16BE)
    val out = new java.io.ByteArrayOutputStream()
    def w16(v: Int): Unit = { out.write(v >> 8); out.write(v) }
    def w32(v: Int): Unit = { w16(v >>> 16); w16(v & 0xffff) }
    out.write("MM".getBytes); w16(42); w32(8)
    w16(1); w16(0x8769); w16(4); w32(1); w32(26) // IFD0 at 8: one entry -> ExifIFD at 26
    w32(0)
    w16(1); w16(0x9286); w16(7); w32(body.length); w32(44) // ExifIFD at 26 -> data at 44
    w32(0)
    out.write(body)
    out.toByteArray

/** Adds or removes one PNG tEXt keyword without touching the pixel data (chunk-level rewrite with CRC). */
object PngText:
  private val Signature = Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  def set(path: Path, key: String, value: Option[String]): Unit =
    val data = Files.readAllBytes(path)
    require(data.length > 8 && java.util.Arrays.equals(data.take(8), Signature), "Not a PNG file")
    val out = new java.io.ByteArrayOutputStream(data.length + 64)
    out.write(Signature)
    var o = 8
    var inserted = false
    def insert(): Unit =
      if !inserted then
        value.foreach { v =>
          require(StandardCharsets.ISO_8859_1.newEncoder().canEncode(key + v), "ranking text must be Latin-1")
          chunk(out, "tEXt", key.getBytes(StandardCharsets.ISO_8859_1) ++ Array(0.toByte) ++ v.getBytes(StandardCharsets.ISO_8859_1))
        }
        inserted = true
    while o + 12 <= data.length do
      val len = ((data(o) & 0xff) << 24) | ((data(o + 1) & 0xff) << 16) | ((data(o + 2) & 0xff) << 8) | (data(o + 3) & 0xff)
      require(len >= 0 && o + 12 + len <= data.length, "Truncated PNG chunk")
      val tpe = new String(data, o + 4, 4, StandardCharsets.ISO_8859_1)
      val whole = data.slice(o, o + 12 + len)
      val keyword = if tpe == "tEXt" || tpe == "iTXt" || tpe == "zTXt" then new String(data, o + 8, len, StandardCharsets.ISO_8859_1).takeWhile(_ != '\u0000') else ""
      if tpe == "IDAT" || tpe == "IEND" then insert()
      if !((tpe == "tEXt" || tpe == "iTXt" || tpe == "zTXt") && keyword == key) then out.write(whole)
      o += 12 + len
    val bytes = out.toByteArray
    val tmp = Files.createTempFile(path.toAbsolutePath.getParent, ".sdfx-ib-", ".png.tmp")
    try
      Files.write(tmp, bytes)
      Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(tmp)

  private def chunk(out: java.io.ByteArrayOutputStream, tpe: String, body: Array[Byte]): Unit =
    val t = tpe.getBytes(StandardCharsets.ISO_8859_1)
    def w32(v: Long): Unit = { out.write((v >> 24).toInt); out.write((v >> 16).toInt); out.write((v >> 8).toInt); out.write(v.toInt) }
    w32(body.length)
    out.write(t); out.write(body)
    val crc = new CRC32
    crc.update(t); crc.update(body)
    w32(crc.getValue)
