package sdfx.ui

import sdfx.ui.view.ControlNetExtrasCard

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class ControlNetExtrasCardSuite extends munit.FunSuite:
  test("batch result keeps cleanup warning count visible after the toast disappears") {
    val result = ControlNetExtrasCard.BatchOutcome(2, 2, Vector.empty, false)
    val status = ControlNetExtrasCard.batchStatus(result, java.nio.file.Paths.get("output"), 1, Some("Could not remove temporary file C:/tmp/a.tmp: access denied"))
    assert(status.contains("2 of 2 written"))
    assert(status.contains("1 temporary file cleanup warning"))
    assert(status.contains("C:/tmp/a.tmp") && status.contains("access denied"))
  }

  test("cleanup failure after publication reports a warning without misreporting the published file") {
    val dir = Files.createTempDirectory("cn-cleanup-warning-")
    val session = new sdfx.core.png.ImageScrambleSession
    val stage = Files.writeString(dir.resolve("source.tmp"), "source")
    var warnings = Vector.empty[String]
    val result = ControlNetExtrasCard.publishStaged(stage, java.nio.file.Paths.get("a.png"), "canny", dir, session, session.state.revision,
      cleanup = _ => throw new java.io.IOException("injected cleanup failure"), warn = m => warnings :+= m)
    assertEquals(Files.readString(result), "source")
    assert(warnings.exists(_.contains("injected cleanup failure")))
    assert(Files.exists(stage))
  }

  test("cleanup failure does not mask the original transfer failure") {
    val dir = Files.createTempDirectory("cn-cleanup-error-")
    val session = new sdfx.core.png.ImageScrambleSession
    val stage = Files.writeString(dir.resolve("source.tmp"), "source")
    val error = intercept[java.io.IOException] {
      ControlNetExtrasCard.publishStaged(stage, java.nio.file.Paths.get("a.png"), "canny", dir, session, session.state.revision,
        transfer = (_, _) => throw new java.io.IOException("original transfer error"),
        cleanup = _ => throw new java.io.IOException("injected cleanup failure"))
    }
    assertEquals(error.getMessage, "original transfer error")
    assert(error.getSuppressed.nonEmpty)
    assert(!Files.exists(dir.resolve("a-canny.png")))
  }

  test("failed transfer never publishes or leaves partial output in the destination") {
    val dir = Files.createTempDirectory("cn-publish-transfer-")
    val session = new sdfx.core.png.ImageScrambleSession
    val stage = Files.writeString(dir.resolve("source.tmp"), "source")
    intercept[java.io.IOException] {
      ControlNetExtrasCard.publishStaged(stage, java.nio.file.Paths.get("a.png"), "canny", dir, session, session.state.revision,
        (src, tmp) => { Files.writeString(tmp, "partial"); throw new java.io.IOException("injected transfer failure") })
    }
    assertEquals(scala.util.Using.resource(Files.list(dir))(_.count()), 0L)
  }

  test("stale protected session never publishes the staged output or changes an existing output") {
    val dir = Files.createTempDirectory("cn-publish-")
    val session = new sdfx.core.png.ImageScrambleSession
    val revision = session.state.revision
    val stage = Files.writeString(dir.resolve("staged.tmp"), "staged")
    val target = Files.writeString(dir.resolve("a-canny.png"), "keep")
    val started = new java.util.concurrent.CountDownLatch(1)
    val result = new java.util.concurrent.atomic.AtomicReference[Throwable]()
    val thread = new Thread(() => {
      started.countDown()
      try ControlNetExtrasCard.publishStaged(stage, java.nio.file.Paths.get("a.png"), "canny", dir, session, revision)
      catch case e: Throwable => result.set(e)
      ()
    })
    session.synchronized {
      thread.start()
      assert(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
      session.unlock("secret")
    }
    thread.join(5000)
    assert(!thread.isAlive)
    assert(result.get().isInstanceOf[IllegalStateException])
    assertEquals(Files.readString(target), "keep")
    assert(!Files.exists(dir.resolve("a-canny-1.png")))
    assert(!Files.exists(stage))
  }

  test("publication and protection changes serialize and preserve an existing output") {
    val dir = Files.createTempDirectory("cn-publish-race-")
    val session = new sdfx.core.png.ImageScrambleSession
    val revision = session.state.revision
    val target = Files.writeString(dir.resolve("a-canny.png"), "keep")
    val stage = Files.writeString(dir.resolve("staged.tmp"), "new")
    val copying = new java.util.concurrent.CountDownLatch(1)
    val release = new java.util.concurrent.CountDownLatch(1)
    val changed = new java.util.concurrent.CountDownLatch(1)
    val result = new java.util.concurrent.atomic.AtomicReference[java.nio.file.Path]()
    val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]()
    val publisher = new Thread(() => {
      try result.set(ControlNetExtrasCard.publishStaged(stage, java.nio.file.Paths.get("a.png"), "canny", dir, session, revision,
        (src, tmp) => {
          require(tmp.getParent == dir, "staging must be on destination filesystem")
          Files.copy(src, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          copying.countDown()
          require(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }))
      catch case e: Throwable => failure.set(e)
    })
    publisher.start()
    assert(copying.await(5, java.util.concurrent.TimeUnit.SECONDS))
    val changer = new Thread(() => { session.unlock("secret"); changed.countDown() })
    changer.start()
    assert(!changed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS), "session change must wait during publication")
    release.countDown()
    publisher.join(5000); changer.join(5000)
    assertEquals(failure.get(), null)
    assert(changed.getCount == 0)
    assertEquals(result.get().getFileName.toString, "a-canny-1.png")
    assertEquals(Files.readString(result.get()), "new")
    assertEquals(Files.readString(target), "keep")
    assert(!Files.exists(stage))
  }

  test("selected batch continues after an individual failure and preserves input order") {
    val files = Seq("b.png", "a.png", "bad.png").map(java.nio.file.Paths.get(_))
    var seen = Vector.empty[String]
    var progress = Vector.empty[(Int, Int)]
    val outcome = ControlNetExtrasCard.runItems(files, new AtomicBoolean(false))({ p =>
      seen :+= p.toString
      if p.toString == "a.png" then throw new IllegalArgumentException("broken image")
    }, (done, failed) => progress :+= ((done, failed)))
    assertEquals(seen, Vector("b.png", "a.png", "bad.png"))
    assertEquals(progress, Vector((1, 0), (2, 1), (3, 1)))
    assertEquals(outcome.done, 3)
    assertEquals(outcome.failed.size, 1)
    assert(outcome.failed.head.contains("a.png"))
    assert(!outcome.cancelled)
  }

  test("cancelling a selected batch stops after the current image") {
    val cancel = new AtomicBoolean(false)
    var seen = Vector.empty[String]
    val files = Seq("one.png", "two.png").map(java.nio.file.Paths.get(_))
    val outcome = ControlNetExtrasCard.runItems(files, cancel)({ p => seen :+= p.toString; cancel.set(true) }, (_, _) => ())
    assertEquals(seen, Vector("one.png"))
    assertEquals(outcome.done, 1)
    assert(outcome.cancelled)
  }

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
