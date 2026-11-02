package sdfx.ui

import sdfx.ui.view.ControlNetExtrasCard

import java.nio.file.Files

class ControlNetExtrasCardSuite extends munit.FunSuite:
  test("a folder lists only images, sorted by name, without sub folders") {
    val dir = Files.createTempDirectory("cn-extras-")
    try
      Seq("b.png", "A.jpg", "c.txt", "d.webp", "e.mp4").foreach(n => Files.writeString(dir.resolve(n), "x"))
      Files.createDirectory(dir.resolve("sub.png"))
      assertEquals(ControlNetExtrasCard.listImages(dir).map(_.getFileName.toString), Seq("A.jpg", "b.png", "d.webp"))
    finally
      scala.util.Using(Files.walk(dir))(_.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p)))
  }

  test("the output folder is the same folder when it resolves to the input") {
    val dir = Files.createTempDirectory("cn-extras-")
    try
      assert(ControlNetExtrasCard.sameFolder(dir, dir.resolve(".")))
      assert(!ControlNetExtrasCard.sameFolder(dir, dir.resolve("other")))
      assert(!ControlNetExtrasCard.sameFolder(dir, Files.createTempDirectory("cn-extras-other-")))
    finally Files.deleteIfExists(dir)
  }
