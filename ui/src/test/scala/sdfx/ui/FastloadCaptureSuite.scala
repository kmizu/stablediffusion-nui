package sdfx.ui

import munit.FunSuite
import sdfx.core.model.ControlNetUnit
import sdfx.core.png.{ImageScrambleSession, ScrambledPng}
import sdfx.ui.vm.FastloadCapture
import java.awt.image.BufferedImage
import java.nio.file.Files

class FastloadCaptureSuite extends FunSuite:
  test("capture reads protected input and retains scalar arguments in memory") {
    val dir = Files.createTempDirectory("fastload-capture-")
    val input = dir.resolve("protected.png")
    val image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    image.setRGB(2, 3, 0x123456)
    ScrambledPng.write(image, input, Nil, Some("secret"))
    val session = new ImageScrambleSession; session.unlock("secret")
    val unit = ControlNetUnit(enabled = true, model = "m", image = Some(input.toString),
      preprocessor = "canny", preprocessorArgs = Map("low_threshold" -> 41.0))
    val wire = FastloadCapture.encode(Seq(unit), session.snapshot)
    assertEquals(wire("type").str, "list")
    assert(!ujson.write(wire).contains("secret"))
    val entries = Files.list(dir)
    try assertEquals(entries.count(), 1L) finally entries.close()
    Files.delete(input); Files.delete(dir)
  }

  test("capture rejects oversized image dimensions before decoding pixels") {
    val dir = Files.createTempDirectory("fastload-capture-")
    val input = dir.resolve("large.png")
    val bytes = new java.io.ByteArrayOutputStream
    val data = new java.io.DataOutputStream(bytes)
    data.write(Array[Byte](-119, 80, 78, 71, 13, 10, 26, 10))
    data.writeInt(13)
    val header = new java.io.ByteArrayOutputStream
    val h = new java.io.DataOutputStream(header)
    h.writeBytes("IHDR"); h.writeInt(8000); h.writeInt(8000); h.writeByte(8); h.writeByte(0); h.writeByte(0); h.writeByte(0); h.writeByte(0)
    val content = header.toByteArray
    val crc = new java.util.zip.CRC32; crc.update(content)
    data.write(content); data.writeInt(crc.getValue.toInt); data.close()
    Files.write(input, bytes.toByteArray)
    val error = intercept[IllegalArgumentException](FastloadCapture.encode(Seq(ControlNetUnit(image = Some(input.toString))), None))
    assert(error.getMessage.contains("too large"))
    Files.delete(input); Files.delete(dir)
  }
