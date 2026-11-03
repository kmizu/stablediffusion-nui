package sdfx.engine

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.JobOutput

class Img2ImgFolderRunnerSuite extends munit.FunSuite:
  private class Cancel extends RuntimeException
  test("restricted folder must still resolve under an allowed root"):
    val root = Files.createTempDirectory("img2img-allowed-").toRealPath()
    val inside = Files.createDirectory(root.resolve("images")).toRealPath()
    Img2ImgFolderRunner.checkAllowedFolder(inside, Seq(root))
    val outside = Files.createTempDirectory("img2img-denied-").toRealPath()
    intercept[IllegalArgumentException](Img2ImgFolderRunner.checkAllowedFolder(outside, Seq(root)))
  test("recorded metadata uses the source only for the staged input"):
    val p = GenerationParams(img2img = Some(Img2ImgParams("temp.png")))
    val source = "C:\\画像,フォルダ\\source.png"
    val recorded = Img2ImgFolderRunner.recordedParams(p, Some("temp.png" -> source))
    assertEquals(recorded.img2img.get.initImage, source)
    val text = sdfx.core.infotext.Infotext.write(recorded, sdfx.core.infotext.ImageFacts(seed = 1))
    assertEquals(sdfx.core.infotext.Infotext.parse(text).fields("NUI img2img source"), source)
    assertEquals(Img2ImgFolderRunner.recordedParams(p, Some("other.png" -> "source.png")), p)
  test("each input is staged unchanged and submitted in one ordered batch"):
    val root = Files.createTempDirectory("img2img-run-input-")
    val staging = Files.createTempDirectory("img2img-run-stage-")
    Files.writeString(root.resolve("b.png"), "second")
    Files.writeString(root.resolve("a.png"), "first")
    val seen = ArrayBuffer.empty[(String, String)]
    val status = ArrayBuffer.empty[(Int, Int)]
    val params = GenerationParams(prompt = "same prompt", img2img = Some(Img2ImgParams("")))
    val output = Img2ImgFolderRunner.run(root, params, suffix => Files.createTempFile(staging, "image-", suffix),
      (current, original) =>
        assertEquals(current.prompt, "same prompt")
        seen += original.getFileName.toString -> Files.readString(Path.of(current.img2img.get.initImage))
        JobOutput(Nil),
      (_, done, total) => status += done -> total)
    assertEquals(output.images, Nil)
    assertEquals(seen.toVector, Vector("a.png" -> "first", "b.png" -> "second"))
    assertEquals(status.last, (2, 2))
    val stream = Files.list(staging)
    try assertEquals(stream.count(), 0L) finally stream.close()
    assertEquals(Files.readString(root.resolve("a.png")), "first")

  test("an input changed after selection stops the batch without submitting changed bytes"):
    val root = Files.createTempDirectory("img2img-run-changed-")
    val staging = Files.createTempDirectory("img2img-run-stage-")
    Files.writeString(root.resolve("a.png"), "first")
    val second = Files.writeString(root.resolve("b.png"), "second")
    var calls = 0
    intercept[IllegalArgumentException] {
      Img2ImgFolderRunner.run(root, GenerationParams(img2img = Some(Img2ImgParams(""))),
        suffix => Files.createTempFile(staging, "image-", suffix),
        (_, _) => { calls += 1; Files.writeString(second, "changed"); JobOutput(Nil) }, (_, _, _) => ())
    }
    assertEquals(calls, 1)
    val stream = Files.list(staging)
    try assertEquals(stream.count(), 0L) finally stream.close()

  test("restricted folder replacement between items never submits outside input"):
    assume(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"))
    val allowed = Files.createTempDirectory("img2img-allowed-").toRealPath()
    val root = Files.createDirectory(allowed.resolve("images"))
    Files.writeString(root.resolve("a.png"), "first")
    Files.writeString(root.resolve("b.png"), "inside")
    val outside = Files.createTempDirectory("img2img-outside-").toRealPath()
    Files.writeString(outside.resolve("b.png"), "secret")
    val stage = Files.createTempDirectory("img2img-stage-")
    var calls = 0
    val params = GenerationParams(img2img = Some(Img2ImgParams("")))
    intercept[Exception] {
      Img2ImgFolderRunner.run(root, params, suffix => Files.createTempFile(stage, "image-", suffix),
        (_, _) =>
          calls += 1
          Files.move(root, allowed.resolve("old-images"))
          val process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", root.toString, outside.toString)
            .redirectErrorStream(true).start()
          val message = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          assertEquals(process.waitFor(), 0, message)
          JobOutput(Nil),
        (_, _, _) => (), () => Img2ImgFolderRunner.checkAllowedFolder(root, Seq(allowed)), Seq(allowed))
    }
    assertEquals(calls, 1)
    val remaining = Files.list(stage)
    try assertEquals(remaining.count(), 0L) finally remaining.close()

  test("cancellation before generation cleans staged input"):
    val root = Files.createTempDirectory("img2img-run-cancel-")
    val staging = Files.createTempDirectory("img2img-run-stage-")
    Files.writeString(root.resolve("a.png"), "input")
    intercept[Cancel] {
      Img2ImgFolderRunner.run(root, GenerationParams(img2img = Some(Img2ImgParams(""))),
        suffix => Files.createTempFile(staging, "image-", suffix), (_, _) => throw new Cancel, (_, _, _) => ())
    }
    val stream = Files.list(staging)
    try assertEquals(stream.count(), 0L) finally stream.close()
