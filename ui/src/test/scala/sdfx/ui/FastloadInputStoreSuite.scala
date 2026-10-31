package sdfx.ui

import munit.FunSuite
import sdfx.core.png.{ImageScrambleSession, ScrambledPng}
import sdfx.ui.vm.{FastloadInputStore, TempTracker}
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

class FastloadInputStoreSuite extends FunSuite:
  test("protected input is written scrambled and can be recovered with the session password") {
    val dir = Files.createTempDirectory("fastload-input-test-")
    val tracker = new TempTracker(() => dir)
    val session = new ImageScrambleSession
    session.unlock("test-password")
    val pixels = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until 16; x <- 0 until 16 do pixels.setRGB(x, y, (x * 16 + y) * 0x010101)
    val bytes = new ByteArrayOutputStream
    ImageIO.write(pixels, "png", bytes)
    val path = FastloadInputStore.write(bytes.toByteArray, tracker, session.snapshot)
    assert(ScrambledPng.read(path, Some("test-password")).scrambled)
    assertEquals(ScrambledPng.read(path, Some("test-password")).image.getRGB(0, 0), pixels.getRGB(0, 0))
    val stored = ImageIO.read(path.toFile)
    assert((for y <- 0 until 16; x <- 0 until 16 yield stored.getRGB(x, y) != pixels.getRGB(x, y)).exists(identity))
    tracker.forgetInput(path)
    Files.delete(path)
    Files.delete(dir)
  }

  test("invalid image leaves no staged input") {
    val dir = Files.createTempDirectory("fastload-input-test-")
    val tracker = new TempTracker(() => dir)
    intercept[IllegalArgumentException](FastloadInputStore.write(Array[Byte](1, 2), tracker, None))
    val files = Files.list(dir)
    try assertEquals(files.count(), 0L) finally files.close()
    Files.delete(dir)
  }
