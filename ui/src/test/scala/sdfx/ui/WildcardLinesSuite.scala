package sdfx.ui

import java.nio.file.Files
import munit.FunSuite
import sdfx.ui.vm.WildcardLines

class WildcardLinesSuite extends FunSuite:
  test("nested files use first root and ignore empty lines and comments") {
    val first = Files.createTempDirectory("wildcards-first")
    val second = Files.createTempDirectory("wildcards-second")
    Files.createDirectories(first.resolve("colors"))
    Files.createDirectories(second.resolve("colors"))
    Files.writeString(first.resolve("colors/warm.txt"), "# comment\n 赤い花 \n\nblue fox\n")
    Files.writeString(second.resolve("colors/warm.txt"), "other\n")
    assertEquals(WildcardLines.read(Seq(first, second), "colors/warm"), Vector("赤い花", "blue fox"))
    assertEquals(WildcardLines.read(Seq(first), "missing"), Vector.empty)
  }
  test("traversal and oversized files are rejected") {
    val root = Files.createTempDirectory("wildcards-safe")
    intercept[IllegalArgumentException](WildcardLines.read(Seq(root), "../secret"))
    intercept[IllegalArgumentException](WildcardLines.read(Seq(root), "a/*"))
    Files.write(root.resolve("large.txt"), new Array[Byte](1024 * 1024 + 1))
    intercept[IllegalArgumentException](WildcardLines.read(Seq(root), "large"))
  }
  test("symbolic links cannot leave the wildcard root") {
    val root = Files.createTempDirectory("wildcards-link-root")
    val outside = Files.createTempFile("wildcards-link-outside", ".txt")
    Files.writeString(outside, "outside")
    val link = root.resolve("link.txt")
    val created = scala.util.Try(Files.createSymbolicLink(link, outside)).isSuccess
    assume(created, "symbolic link creation is not available on this system")
    intercept[IllegalArgumentException](WildcardLines.read(Seq(root), "link"))
  }
  test("line reader returns one extra entry to indicate truncation") {
    val root = Files.createTempDirectory("wildcards-many-lines")
    Files.writeString(root.resolve("many.txt"), Vector.fill(5002)("value").mkString("\n"))
    assertEquals(WildcardLines.read(Seq(root), "many").size, 5001)
  }
