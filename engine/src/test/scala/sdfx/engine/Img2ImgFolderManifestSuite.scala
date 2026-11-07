package sdfx.engine

import java.nio.file.Files
import java.io.ByteArrayOutputStream

class Img2ImgFolderManifestSuite extends munit.FunSuite:
  private class Cancel extends RuntimeException
  test("a folder snapshots supported files in stable order and stages exact bytes"):
    val root = Files.createTempDirectory("img2img-folder-")
    Files.writeString(root.resolve("z.PNG"), "z")
    Files.writeString(root.resolve("A.jpg"), "a")
    Files.writeString(root.resolve("ignore.txt"), "not an image")
    val items = Img2ImgFolderManifest.scan(root)
    assertEquals(items.map(_.path.getFileName.toString), Vector("A.jpg", "z.PNG"))
    val output = new ByteArrayOutputStream()
    Img2ImgFolderManifest.copyVerified(items.head, output)
    assertEquals(output.toString("UTF-8"), "a")

  test("changed input is rejected rather than silently processed"):
    val root = Files.createTempDirectory("img2img-folder-change-")
    val file = root.resolve("in.png")
    Files.writeString(file, "original")
    val saved = Img2ImgFolderManifest.scan(root).head
    Files.writeString(file, "modified")
    intercept[IllegalArgumentException](Img2ImgFolderManifest.copyVerified(saved, new ByteArrayOutputStream()))

  test("empty folders and non-regular supported files are rejected"):
    val root = Files.createTempDirectory("img2img-folder-empty-")
    intercept[IllegalArgumentException](Img2ImgFolderManifest.scan(root))
    Files.createDirectory(root.resolve("nested.png"))
    intercept[IllegalArgumentException](Img2ImgFolderManifest.scan(root))

  test("interruption is checked while scanning and staging"):
    val root = Files.createTempDirectory("img2img-folder-cancel-")
    Files.writeString(root.resolve("in.png"), "input")
    intercept[Cancel](Img2ImgFolderManifest.scan(root, () => throw new Cancel))
    val item = Img2ImgFolderManifest.scan(root).head
    intercept[Cancel](Img2ImgFolderManifest.copyVerified(item, new ByteArrayOutputStream(), () => throw new Cancel))

  test("replacing the input directory invalidates its captured images"):
    val parent = Files.createTempDirectory("img2img-folder-root-")
    val root = Files.createDirectory(parent.resolve("source"))
    Files.writeString(root.resolve("in.png"), "input")
    val item = Img2ImgFolderManifest.scan(root).head
    Files.move(root, parent.resolve("old-source"))
    Files.createDirectory(root)
    Files.writeString(root.resolve("in.png"), "input")
    intercept[IllegalArgumentException](Img2ImgFolderManifest.copyVerified(item, new ByteArrayOutputStream()))
