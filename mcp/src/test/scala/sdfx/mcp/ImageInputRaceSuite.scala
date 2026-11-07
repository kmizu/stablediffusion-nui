package sdfx.mcp

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO

class ImageInputRaceSuite extends munit.FunSuite:
  test("get_image data and metadata come from the verified open file") {
    assume(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
    val root = Files.createTempDirectory("mcp-get-race-").toRealPath()
    val directory = root.resolve("images")
    val actual = Files.createDirectory(root.resolve("actual"))
    val outside = Files.createTempDirectory("mcp-get-outside-").toRealPath()
    val img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, 0x112233)
    sdfx.core.png.PngMetadata.write(img, actual.resolve("input.png"), Seq("parameters" -> "inside metadata"))
    img.setRGB(0, 0, 0xaabbcc)
    sdfx.core.png.PngMetadata.write(img, outside.resolve("input.png"), Seq("parameters" -> "outside metadata"))
    def junction(target: java.nio.file.Path): Unit =
      val proc = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", directory.toString, target.toString)
        .redirectErrorStream(true).start()
      val message = new String(proc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assertEquals(proc.waitFor(), 0, message)
    junction(actual)
    val original = Files.readAllBytes(actual.resolve("input.png")).toSeq
    val bytes = Images.readVerifiedBytes(directory.resolve("input.png"), new PathPolicy(() => Seq(root)), "image", () => {
      Files.delete(directory)
      junction(outside)
    })
    assertEquals(bytes.toSeq, original)
    assertEquals(Images.encodeBytes(bytes, directory.resolve("input.png"), 0).width, 8)
    assertEquals(sdfx.core.png.PngMetadata.readParameters(bytes), Some("inside metadata"))
  }

  test("path snapshots retain original image bytes, format and metadata") {
    assume(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
    val root = Files.createTempDirectory("mcp-exact-bytes-").toRealPath()
    val policy = new PathPolicy(() => Seq(root))
    val image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    for format <- Seq("png", "jpeg", "gif") do
      val original = root.resolve(s"input.$format")
      assert(ImageIO.write(image, format, original.toFile))
      Files.write(original, Array[Byte](1, 2, 3, 4), java.nio.file.StandardOpenOption.APPEND)
      val before = Files.readAllBytes(original).toSeq
      val (copy, owned) = Images.materialize(original.toString, "image", policy, root,
        suffix => Files.createTempFile(root, "stage-", suffix))
      assertEquals(Files.readAllBytes(copy).toSeq, before)
      assert(copy.toString.endsWith(s".$format"))
      assert(owned.contains(copy))
  }

  test("unsupported systems reject file paths and still accept base64") {
    val root = Files.createTempDirectory("mcp-unsupported-").toRealPath()
    val image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    val input = root.resolve("input.png")
    assert(ImageIO.write(image, "png", input.toFile))
    val policy = new PathPolicy(() => Seq(root))
    val temp: String => java.nio.file.Path = suffix => Files.createTempFile(root, "stage-", suffix)
    val error = intercept[ToolError](Images.materialize(input.toString, "init_image", policy, root, temp, "unsupported"))
    assert(error.getMessage.contains("base64"))
    val pathOnly = intercept[ToolError](Images.readVerifiedBytes(input, policy, "path", platform = "unsupported"))
    assert(!pathOnly.getMessage.contains("base64"))
    val encoded = java.util.Base64.getEncoder.encodeToString(Files.readAllBytes(input))
    val (result, owned) = Images.materialize(encoded, "init_image", policy, root, temp, "unsupported")
    assert(owned.contains(result) && Files.isRegularFile(result))
  }

  test("materialized local input is stable after its allowed parent is replaced") {
    assume(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
    val root = Files.createTempDirectory("mcp-race-root").toRealPath()
    val inside = Files.createDirectory(root.resolve("images"))
    val outside = Files.createTempDirectory("mcp-race-outside").toRealPath()
    def write(dir: java.nio.file.Path, color: Int): Unit =
      val image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
      image.setRGB(0, 0, color)
      assert(ImageIO.write(image, "png", dir.resolve("input.png").toFile))
    write(inside, 0x112233)
    write(outside, 0xaabbcc)
    val (path, _) = Images.materialize(inside.resolve("input.png").toString, "init_image",
      new PathPolicy(() => Seq(root)), root, suffix => Files.createTempFile(root, "staged-", suffix))
    Files.move(inside, root.resolve("moved-images"))
    val proc = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", inside.toString, outside.toString)
      .redirectErrorStream(true).start()
    val message = new String(proc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(proc.waitFor(), 0, message)
    assertEquals(ImageIO.read(path.toFile).getRGB(0, 0) & 0xffffff, 0x112233)
  }
