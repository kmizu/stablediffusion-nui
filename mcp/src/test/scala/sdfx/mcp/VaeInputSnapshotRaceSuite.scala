package sdfx.mcp

import java.nio.file.Files

class VaeInputSnapshotRaceSuite extends munit.FunSuite:
  test("VAE rejects an allowed parent replaced after resolution") {
    assume(System.getProperty("os.name").toLowerCase.contains("win"))
    val root = Files.createTempDirectory("vae-race-root-")
    val inside = Files.createDirectory(root.resolve("inside"))
    val outside = Files.createTempDirectory("vae-race-outside-")
    val input = Files.write(inside.resolve("input.safetensors"), Array[Byte](1, 2, 3))
    Files.write(outside.resolve("input.safetensors"), Array[Byte](9, 8, 7))
    val policy = new PathPolicy(() => Seq(root))
    try
      val error = intercept[ToolError] {
        VaeInputSnapshot.capture(input.toString, "input_paths", false, policy, root,
          suffix => Files.createTempFile(root, "stage-", suffix), () => {
            Files.move(inside, root.resolve("moved"))
            val proc = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", inside.toString, outside.toString).redirectErrorStream(true).start()
            val message = new String(proc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            assertEquals(proc.waitFor(), 0, message)
          })
      }
      assert(error.getMessage.contains("verified"))
    finally
      if Files.isDirectory(root.resolve("moved")) then
        Files.deleteIfExists(inside)
        Files.deleteIfExists(root.resolve("moved").resolve("input.safetensors"))
        Files.deleteIfExists(root.resolve("moved"))
      else
        Files.deleteIfExists(input)
        Files.deleteIfExists(inside)
      Files.deleteIfExists(outside.resolve("input.safetensors"))
      Files.deleteIfExists(outside)
      Files.deleteIfExists(root)
  }
