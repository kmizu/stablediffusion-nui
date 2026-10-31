package sdfx.ui

import munit.FunSuite
import sdfx.core.png.ImageScrambleSession
import sdfx.ui.vm.FastloadPublisher
import java.nio.file.Files

class FastloadPublisherSuite extends FunSuite:
  test("session change rejects publication before any file is staged") {
    val dir = Files.createTempDirectory("fastload-publish-")
    val session = new ImageScrambleSession
    val state = session.state
    session.unlock("secret")
    intercept[IllegalArgumentException](FastloadPublisher.publish(dir.resolve("a.cni"), Array[Byte](1), session, state.revision))
    intercept[IllegalArgumentException](FastloadPublisher.publish(dir.resolve("b.cni"), Array[Byte](1), session, session.state.revision))
    val files = Files.list(dir)
    try assertEquals(files.count(), 0L) finally files.close()
    Files.delete(dir)
  }

  test("publish creates a new file without replacing an existing one") {
    val dir = Files.createTempDirectory("fastload-publish-")
    val session = new ImageScrambleSession
    val target = dir.resolve("a.cni")
    FastloadPublisher.publish(target, Array[Byte](1, 2), session, session.state.revision)
    intercept[Exception](FastloadPublisher.publish(target, Array[Byte](3), session, session.state.revision))
    assertEquals(Files.readAllBytes(target).toSeq, Seq[Byte](1, 2))
    Files.delete(target); Files.delete(dir)
  }
