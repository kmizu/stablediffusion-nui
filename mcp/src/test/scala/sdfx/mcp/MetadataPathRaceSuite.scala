package sdfx.mcp

import java.nio.file.Files

class MetadataPathRaceSuite extends munit.FunSuite:
  test("metadata input refuses parent junction swapped after resolution") {
    assume(System.getProperty("os.name").toLowerCase.contains("win"))
    val root = Files.createTempDirectory("metadata-root-")
    val inside = Files.createDirectory(root.resolve("inside"))
    val outside = Files.createTempDirectory("metadata-outside-")
    val input = inside.resolve("input.png")
    Files.write(input, Array[Byte](1, 2, 3))
    Files.write(outside.resolve("input.png"), Array[Byte](9, 8, 7))
    val policy = new PathPolicy(() => Seq(root))
    try
      val ex = intercept[ToolError] {
        InspectGenerationMetadata.read(input.toString, policy, root, () => {
          Files.move(inside, root.resolve("moved"))
          val process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", inside.toString, outside.toString).redirectErrorStream(true).start()
          val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          assertEquals(process.waitFor(), 0, output)
        })
      }
      assert(ex.getMessage.contains("verified"))
    finally
      if Files.isDirectory(root.resolve("moved")) then
        Files.deleteIfExists(inside)
        Files.deleteIfExists(root.resolve("moved").resolve("input.png"))
        Files.deleteIfExists(root.resolve("moved"))
      else
        Files.deleteIfExists(input)
        Files.deleteIfExists(inside)
      Files.deleteIfExists(outside.resolve("input.png"))
      Files.deleteIfExists(outside)
      Files.deleteIfExists(root)
  }

  test("metadata path input retains the 16 MiB bound") {
    val root = Files.createTempDirectory("metadata-limit-")
    val input = root.resolve("large.png")
    try
      val file = new java.io.RandomAccessFile(input.toFile, "rw")
      try file.setLength(16L * 1024 * 1024 + 1) finally file.close()
      val error = intercept[ToolError](InspectGenerationMetadata.read(input.toString, new PathPolicy(() => Seq(root)), root))
      assert(error.getMessage.contains("16 MiB"))
    finally
      Files.deleteIfExists(input)
      Files.deleteIfExists(root)
  }
