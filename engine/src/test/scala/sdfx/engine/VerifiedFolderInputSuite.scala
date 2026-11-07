package sdfx.engine

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}
import java.util.Locale

class VerifiedFolderInputSuite extends munit.FunSuite:
  private def windows: Boolean = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
  private def junction(link: Path, target: Path): Unit =
    val process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString, target.toString)
      .redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(process.waitFor(), 0, output)

  test("a folder replaced by an outside junction before open is rejected"):
    assume(windows)
    val allowed = Files.createTempDirectory("verified-root-").toRealPath()
    val folder = Files.createDirectory(allowed.resolve("images"))
    Files.writeString(folder.resolve("input.png"), "inside")
    val outside = Files.createTempDirectory("verified-outside-").toRealPath()
    Files.writeString(outside.resolve("input.png"), "outside")
    Files.move(folder, allowed.resolve("old-images"))
    junction(folder, outside)
    val output = new ByteArrayOutputStream()
    intercept[Exception] { VerifiedFolderInput.copy(folder.resolve("input.png"), Seq(allowed), output) }
    assertEquals(output.size(), 0)

  test("replacing a parent after verified open cannot switch the file being read"):
    assume(windows)
    val allowed = Files.createTempDirectory("verified-root-").toRealPath()
    val actual = Files.createDirectory(allowed.resolve("actual"))
    Files.writeString(actual.resolve("input.png"), "inside")
    val folder = allowed.resolve("images")
    junction(folder, actual)
    val outside = Files.createTempDirectory("verified-outside-").toRealPath()
    Files.writeString(outside.resolve("input.png"), "outside")
    val output = new ByteArrayOutputStream()
    VerifiedFolderInput.copy(folder.resolve("input.png"), Seq(allowed), output, afterOpen = () => {
      Files.delete(folder)
      junction(folder, outside)
    })
    assertEquals(output.toString("UTF-8"), "inside")

  test("unsupported platforms explicitly reject restricted input"):
    val root = Files.createTempDirectory("verified-root-").toRealPath()
    val file = Files.writeString(root.resolve("input.png"), "inside")
    intercept[UnsupportedOperationException] {
      VerifiedFolderInput.copy(file, Seq(root), new ByteArrayOutputStream(), platform = "unsupported")
    }

  test("a differently cased allowed path is rejected rather than assuming case insensitive directories"):
    assume(windows)
    val parent = Files.createTempDirectory("verified-parent-").toRealPath()
    val root = Files.createDirectory(parent.resolve("MixedCaseRoot"))
    val file = Files.writeString(root.resolve("input.png"), "inside")
    val different = parent.resolve("mixedcaseroot")
    val output = new ByteArrayOutputStream()
    intercept[Exception] { VerifiedFolderInput.copy(file, Seq(different), output) }
    assertEquals(output.size(), 0)

  test("cancellation after verified open emits no bytes and a subsequent copy works"):
    assume(windows)
    class Cancel extends RuntimeException
    val root = Files.createTempDirectory("verified-cancel-").toRealPath()
    val file = Files.writeString(root.resolve("input.png"), "inside")
    val cancelled = new ByteArrayOutputStream()
    intercept[Cancel] { VerifiedFolderInput.copy(file, Seq(root), cancelled, afterOpen = () => throw new Cancel) }
    assertEquals(cancelled.size(), 0)
    val next = new ByteArrayOutputStream()
    VerifiedFolderInput.copy(file, Seq(root), next)
    assertEquals(next.toString("UTF-8"), "inside")

  test("network paths are rejected before the handle is opened"):
    assume(windows)
    val root = Files.createTempDirectory("verified-local-").toRealPath()
    val output = new ByteArrayOutputStream()
    intercept[Exception] { VerifiedFolderInput.copy(Path.of("\\\\example.invalid\\share\\input.png"), Seq(root), output) }
    assertEquals(output.size(), 0)

  test("append or truncate after verified open is rejected"):
    assume(windows)
    val root = Files.createTempDirectory("verified-changed-").toRealPath()
    for shrink <- Seq(true, false) do
      val file = Files.writeString(root.resolve(if shrink then "short.png" else "long.png"), "inside")
      val output = new ByteArrayOutputStream()
      intercept[Exception] {
        VerifiedFolderInput.copy(file, Seq(root), output, afterOpen = () =>
          if shrink then Files.writeString(file, "x")
          else Files.writeString(file, "extra", java.nio.file.StandardOpenOption.APPEND))
      }

  test("cancelling while the helper starts permits a later copy"):
    assume(windows)
    class Cancel extends RuntimeException
    val root = Files.createTempDirectory("verified-start-cancel-").toRealPath()
    val file = Files.writeString(root.resolve("input.png"), "inside")
    val calls = new java.util.concurrent.atomic.AtomicInteger()
    intercept[Cancel] {
      VerifiedFolderInput.copy(file, Seq(root), new ByteArrayOutputStream(),
        check = () => if calls.incrementAndGet() >= 2 then throw new Cancel)
    }
    val output = new ByteArrayOutputStream()
    VerifiedFolderInput.copy(file, Seq(root), output)
    assertEquals(output.toString("UTF-8"), "inside")
