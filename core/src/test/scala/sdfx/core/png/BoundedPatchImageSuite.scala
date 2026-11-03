package sdfx.core.png

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO

class BoundedPatchImageSuite extends munit.FunSuite:
  private def png(color: Int): java.nio.file.Path =
    val p = Files.createTempFile("patch-test-", ".png")
    val image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, color)
    ImageIO.write(image, "png", p.toFile)
    p

  test("snapshot is decoded from the inspected bytes even if the file changes") {
    val p = png(0xffff0000)
    val snap = BoundedPatchImage.snapshot(p)
    val replacement = png(0xff0000ff)
    Files.copy(replacement, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    assertEquals(snap.decode(None).getRGB(0, 0), 0xffff0000)
  }

  test("oversized dimensions are rejected from the header before image decode") {
    val bytes = Files.readAllBytes(png(0xff00ff00))
    val b = ByteBuffer.wrap(bytes)
    b.putInt(16, 16385)
    val crc = new CRC32()
    crc.update(bytes, 12, 17)
    b.putInt(29, crc.getValue.toInt)
    val p = Files.createTempFile("oversize-header-", ".png")
    Files.write(p, bytes)
    val e = intercept[IllegalArgumentException](BoundedPatchImage.snapshot(p))
    assert(e.getMessage.contains("large"))
  }

  test("pixel and dimension limits are checked at their boundaries") {
    def header(w: Int, h: Int): java.nio.file.Path =
      val bytes = Files.readAllBytes(png(0xff00ff00))
      val b = ByteBuffer.wrap(bytes)
      b.putInt(16, w); b.putInt(20, h)
      val crc = new CRC32()
      crc.update(bytes, 12, 17)
      b.putInt(29, crc.getValue.toInt)
      val p = Files.createTempFile("patch-header-", ".png")
      Files.write(p, bytes)
      p
    val max = BoundedPatchImage.snapshot(header(4096, 4096))
    assertEquals((max.width, max.height), (4096, 4096))
    intercept[IllegalArgumentException](BoundedPatchImage.snapshot(header(4097, 4096)))
    intercept[IllegalArgumentException](BoundedPatchImage.snapshot(header(2, 16385)))
    intercept[IllegalArgumentException](BoundedPatchImage.snapshot(header(1, 2)))
  }

  test("protected images use the same snapshot and require the right password") {
    val p = png(0xffaabbcc)
    val original = ImageIO.read(p.toFile)
    ScrambledPng.write(original, p, Nil, Some("secret"))
    val snap = BoundedPatchImage.snapshot(p)
    intercept[IllegalArgumentException](snap.decode(None))
    intercept[IllegalArgumentException](snap.decode(Some("wrong")))
    assertEquals(snap.decode(Some("secret")).getRGB(0, 0), 0xffaabbcc)
  }

  test("invalid and encoded-size-limit inputs are rejected") {
    val p = Files.createTempFile("invalid-patch-", ".png")
    Files.write(p, Array[Byte](1, 2, 3))
    intercept[IllegalArgumentException](BoundedPatchImage.snapshot(p))
    intercept[IllegalArgumentException](BoundedPatchImage.snapshot(p, maxEncodedBytes = 2))
  }

  test("compressed PNG text and profiles are limited before ImageIO parses metadata") {
    def fixture(kind: String, expanded: Int): java.nio.file.Path =
      val zipBytes = new java.io.ByteArrayOutputStream()
      val zip = new java.util.zip.DeflaterOutputStream(zipBytes)
      try zip.write(Array.fill[Byte](expanded)(65)) finally zip.close()
      val prefix = kind match
        case "zTXt" => Array[Byte](107, 0, 0)
        case "iCCP" => Array[Byte](112, 0, 0)
        case "iTXt" => Array[Byte](107, 0, 1, 0, 0, 0)
      val payload = prefix ++ zipBytes.toByteArray
      val name = kind.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      val chunk = new java.io.ByteArrayOutputStream()
      val out = new java.io.DataOutputStream(chunk)
      out.writeInt(payload.length); out.write(name); out.write(payload)
      val crc = new CRC32(); crc.update(name); crc.update(payload); out.writeInt(crc.getValue.toInt); out.close()
      val original = Files.readAllBytes(png(0xff123456))
      val path = Files.createTempFile("metadata-bounded-", ".png")
      Files.write(path, original.take(33) ++ chunk.toByteArray ++ original.drop(33))
      path
    for kind <- Seq("zTXt", "iTXt", "iCCP") do
      val failure = intercept[IllegalArgumentException](BoundedPatchImage.snapshot(fixture(kind, 2 * 1024 * 1024)))
      assert(failure.getMessage.contains("metadata"), s"$kind: ${failure.getMessage}")
    val plain = BoundedPatchImage.snapshot(fixture("zTXt", 100))
    assertEquals(plain.decode(None).getRGB(0, 0), 0xff123456)
  }
