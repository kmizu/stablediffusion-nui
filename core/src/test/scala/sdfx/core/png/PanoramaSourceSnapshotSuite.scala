package sdfx.core.png

import java.awt.image.BufferedImage
import java.nio.file.Files

class PanoramaSourceSnapshotSuite extends munit.FunSuite:
  test("source pixels and parameters come from one snapshot after path replacement") {
    val path = Files.createTempFile("panorama-source-", ".png")
    val image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, 0xff123456)
    ScrambledPng.write(image, path, Seq("parameters" -> "original"), None)
    val snapshot = PanoramaSourceSnapshot.read(path)
    image.setRGB(0, 0, 0xffabcdef)
    ScrambledPng.write(image, path, Seq("parameters" -> "replacement"), None)
    val decoded = snapshot.decode(None)
    assertEquals(decoded.image.getRGB(0, 0), 0xff123456)
    assertEquals(snapshot.parameters, Some("original"))
  }

  test("protected source snapshot uses the same bytes and enforces password") {
    val path = Files.createTempFile("panorama-protected-", ".png")
    val image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, 0xff123456)
    ScrambledPng.write(image, path, Seq("parameters" -> "protected"), Some("secret"))
    val snapshot = PanoramaSourceSnapshot.read(path)
    ScrambledPng.write(image, path, Seq("parameters" -> "replacement"), None)
    intercept[IllegalArgumentException](snapshot.decode(None))
    assertEquals(snapshot.decode(Some("secret")).image.getRGB(0, 0), 0xff123456)
    assertEquals(snapshot.parameters, Some("protected"))
  }

  test("oversized encoded input is explicitly unavailable for verified metadata") {
    val path = Files.createTempFile("panorama-limit-", ".bin")
    Files.write(path, Array[Byte](1, 2, 3))
    intercept[PanoramaSourceSnapshot.Unavailable](PanoramaSourceSnapshot.read(path, maxBytes = 2))
  }

  test("oversized PNG cannot fall back to an unbounded metadata reader") {
    val path = Files.createTempFile("panorama-png-limit-", ".png")
    val image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
    ScrambledPng.write(image, path, Nil, None)
    intercept[IllegalArgumentException](PanoramaSourceSnapshot.read(path, maxBytes = 16))
  }
