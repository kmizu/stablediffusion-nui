package sdfx.core.png

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import scala.util.Using
import scala.util.control.NonFatal

/** Inspect PNG chunks before invoking ImageIO metadata readers. */
private[png] object BoundedPngMetadata:
  val MaxMetadataBytes = 1024 * 1024
  private val signature = Array[Byte](0x89.toByte, 80, 78, 71, 13, 10, 26, 10)

  def check(bytes: Array[Byte]): Unit =
    if bytes.length < signature.length || !signature.indices.forall(i => bytes(i) == signature(i)) then return
    var at = 8
    var used = 0L
    var ended = false
    def add(n: Long): Unit =
      used += n
      if used > MaxMetadataBytes then throw new IllegalArgumentException("PNG metadata is too large")
    def nullAfter(start: Int, end: Int): Int =
      var i = start
      while i < end && bytes(i) != 0 do i += 1
      if i == end then throw new IllegalArgumentException("Malformed PNG metadata")
      i + 1
    def inflate(start: Int, end: Int): Unit =
      try
        Using.resource(new InflaterInputStream(new ByteArrayInputStream(bytes, start, end - start))) { in =>
          val buffer = new Array[Byte](8192)
          var n = in.read(buffer)
          while n != -1 do
            add(n)
            n = in.read(buffer)
        }
      catch
        case e: IllegalArgumentException => throw e
        case NonFatal(e) => throw new IllegalArgumentException("Malformed compressed PNG metadata", e)
    while at < bytes.length && !ended do
      if bytes.length - at < 12 then throw new IllegalArgumentException("Malformed PNG chunk")
      val len = ((bytes(at).toLong & 255) << 24) | ((bytes(at + 1).toLong & 255) << 16) |
        ((bytes(at + 2).toLong & 255) << 8) | (bytes(at + 3).toLong & 255)
      if len > bytes.length.toLong - at - 12 then throw new IllegalArgumentException("Malformed PNG chunk length")
      val kind = new String(bytes, at + 4, 4, StandardCharsets.US_ASCII)
      val start = at + 8
      val end = start + len.toInt
      if kind != "IDAT" && kind != "IHDR" && kind != "IEND" then add(len)
      if kind == "zTXt" || kind == "iCCP" then
        val method = nullAfter(start, end)
        if method >= end || bytes(method) != 0 then throw new IllegalArgumentException("Malformed PNG metadata compression")
        inflate(method + 1, end)
      else if kind == "iTXt" then
        val flag = nullAfter(start, end)
        if flag + 1 >= end || (bytes(flag) != 0 && bytes(flag) != 1) || bytes(flag + 1) != 0 then
          throw new IllegalArgumentException("Malformed PNG metadata compression")
        val language = nullAfter(flag + 2, end)
        val text = nullAfter(language, end)
        if bytes(flag) == 1 then inflate(text, end)
      at = end + 4
      ended = kind == "IEND"
    if !ended then throw new IllegalArgumentException("Malformed PNG: missing IEND")
