package sdfx.core.panorama

import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}
import java.nio.charset.StandardCharsets

class PanoramaNoOverwriteSuite extends munit.FunSuite:
  private val raster = new Raster(2, 2, Array.fill(4)(0xff112233))

  test("a competing process creating the proposed name is never overwritten") {
    val dir = Files.createTempDirectory("pano-noreplace-")
    val target = PanoramaFiles.uniqueTarget(dir, "source.png", "view")
    val py = sys.env.getOrElse("PANORAMA_TEST_PYTHON", "python")
    val process = new ProcessBuilder(py, "-c", "import pathlib,sys; pathlib.Path(sys.argv[1]).write_bytes(b'other-process-data')", target.toString).start()
    assertEquals(process.waitFor(), 0)
    intercept[FileAlreadyExistsException](PanoramaFiles.savePngNew(raster, target, None))
    assertEquals(Files.readString(target), "other-process-data")
    val entries = Files.list(dir)
    try assertEquals(entries.count(), 1L) finally entries.close()
  }

  test("exclusive-copy fallback never replaces an existing file") {
    val dir = Files.createTempDirectory("pano-fallback-")
    val stage = dir.resolve("staged.png"); val target = dir.resolve("result.png")
    Files.writeString(stage, "ours"); Files.writeString(target, "theirs")
    val noLinks: (Path, Path) => Unit = (_, _) => throw new UnsupportedOperationException("no links")
    intercept[FileAlreadyExistsException](PanoramaFiles.publishNew(stage, target, link = noLinks))
    assertEquals(Files.readString(target), "theirs")
  }

  test("exclusive-copy fallback creates the requested bytes when hard links are unavailable") {
    val dir = Files.createTempDirectory("pano-fallback-success-")
    val stage = dir.resolve("staged.png"); val target = dir.resolve("result.png")
    Files.writeString(stage, "ours")
    val noLinks: (Path, Path) => Unit = (_, _) => throw new UnsupportedOperationException("no links")
    PanoramaFiles.publishNew(stage, target, link = noLinks)
    assertEquals(Files.readString(target), "ours")
  }

  test("failed fallback copy reports a possible incomplete output without deleting it") {
    val dir = Files.createTempDirectory("pano-copy-fail-")
    val stage = dir.resolve("staged.png"); val target = dir.resolve("result.png")
    Files.writeString(stage, "ours")
    val noLinks: (Path, Path) => Unit = (_, _) => throw new UnsupportedOperationException("no links")
    val failing: Path => java.io.OutputStream = p =>
      val delegate = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      new java.io.FilterOutputStream(delegate):
        override def write(bytes: Array[Byte], off: Int, len: Int): Unit =
          out.write(bytes, off, 1)
          throw new java.io.IOException("injected copy failure")
    val e = intercept[java.io.IOException](PanoramaFiles.publishNew(stage, target, link = noLinks, open = failing))
    assert(e.getMessage.contains("incomplete"))
    assert(Files.exists(target))
  }

  test("fallback close failure reports uncertainty and does not delete the destination") {
    val dir = Files.createTempDirectory("pano-close-fail-")
    val stage = dir.resolve("staged.png"); val target = dir.resolve("result.png")
    Files.writeString(stage, "ours")
    val noLinks: (Path, Path) => Unit = (_, _) => throw new UnsupportedOperationException("no links")
    val failing: Path => java.io.OutputStream = p =>
      val delegate = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      new java.io.FilterOutputStream(delegate):
        override def close(): Unit = { super.close(); throw new java.io.IOException("injected close failure") }
    val e = intercept[java.io.IOException](PanoramaFiles.publishNew(stage, target, link = noLinks, open = failing))
    assert(e.getMessage.contains("incomplete"))
    assert(Files.exists(target))
  }

  test("new protected PNG publishes with metadata and correct pixels") {
    val dir = Files.createTempDirectory("pano-protected-")
    val target = dir.resolve("result.png")
    PanoramaFiles.savePngWithParametersNew(raster, target, Some("original parameters"), Some("secret"))
    assert(sdfx.core.png.PngMetadata.readTexts(target).contains("Encrypt"))
    assertEquals(sdfx.core.png.PngMetadata.readParameters(target), Some("original parameters"))
    assertEquals(sdfx.core.png.ScrambledPng.read(target, Some("secret")).image.getRGB(0, 0), 0xff112233)
  }
