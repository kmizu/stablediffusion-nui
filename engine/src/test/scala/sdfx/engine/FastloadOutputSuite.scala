package sdfx.engine

import java.nio.file.Files

class FastloadOutputSuite extends munit.FunSuite:
  test("embedded output is published with payload and preserves existing outputs") {
    val dir = Files.createTempDirectory("fastload-output-")
    val existing = dir.resolve("00000-7.png")
    Files.write(existing, Array[Byte](9))
    val stage = Files.createTempFile(dir, ".image-", ".tmp")
    Files.write(stage, Array[Byte](1, 2))
    val result = FastloadOutput.publish(stage, dir, 7, Array[Byte](3, 4), "embedded", () => ())
    assertEquals(Files.readAllBytes(existing).toSeq, Seq[Byte](9))
    assertEquals(Files.readAllBytes(result).toSeq, Seq[Byte](1, 2, 3, 4))
  }

  test("cancelled output does not create an image or sidecar") {
    val dir = Files.createTempDirectory("fastload-cancel-")
    val stage = Files.createTempFile(dir, ".image-", ".tmp")
    Files.write(stage, Array[Byte](1))
    intercept[java.util.concurrent.CancellationException] {
      FastloadOutput.publish(stage, dir, 9, Array[Byte](3), "both", () => throw new java.util.concurrent.CancellationException())
    }
    val entries = Files.list(dir)
    try assertEquals(entries.count(), 1L) finally entries.close()
  }

  test("both mode writes the same payload as a new sidecar") {
    val dir = Files.createTempDirectory("fastload-both-")
    val stage = Files.createTempFile(dir, ".image-", ".tmp")
    Files.write(stage, Array[Byte](1, 2))
    val result = FastloadOutput.publish(stage, dir, 12, Array[Byte](3, 4), "both", () => ())
    val sidecar = result.resolveSibling(result.getFileName.toString.stripSuffix(".png") + ".cni")
    assertEquals(Files.readAllBytes(result).toSeq, Seq[Byte](1, 2, 3, 4))
    assertEquals(Files.readAllBytes(sidecar).toSeq, Seq[Byte](3, 4))
  }
