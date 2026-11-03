package sdfx.core.panorama

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable.ArrayBuffer

class LatestFrameSuite extends munit.FunSuite:
  test("slow rendering keeps the latest pending frame without starving the current frame") {
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val done = new CountDownLatch(2)
    val seen = ArrayBuffer.empty[Int]
    val frames = new LatestFrame[Int](frame => {
      if frame == 1 then
        started.countDown()
        assert(release.await(5, TimeUnit.SECONDS))
      seen.synchronized { seen += frame }
      done.countDown()
    })
    try
      frames.submit(1)
      assert(started.await(5, TimeUnit.SECONDS))
      (2 to 100).foreach(frames.submit)
      release.countDown()
      assert(done.await(5, TimeUnit.SECONDS))
      assertEquals(seen.synchronized(seen.toList), List(1, 100))
    finally
      release.countDown()
      frames.close()
  }

  test("closing discards pending frames and rejects subsequent submissions") {
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val exited = new CountDownLatch(1)
    val seen = ArrayBuffer.empty[Int]
    val frames = new LatestFrame[Int](frame => {
      started.countDown()
      try release.await(5, TimeUnit.SECONDS)
      catch case _: InterruptedException => ()
      seen.synchronized { seen += frame }
      exited.countDown()
    })
    frames.submit(1)
    assert(started.await(5, TimeUnit.SECONDS))
    frames.submit(2)
    frames.close()
    frames.submit(3)
    release.countDown()
    assert(exited.await(5, TimeUnit.SECONDS))
    assertEquals(seen.synchronized(seen.toList), List(1))
  }

  test("a failing error callback cannot permanently stall later frames") {
    val error = new CountDownLatch(1)
    val done = new CountDownLatch(1)
    val frames = new LatestFrame[Int](frame => {
      if frame == 1 then throw new IllegalStateException("render")
      done.countDown()
    }, _ => { error.countDown(); throw new IllegalStateException("callback") })
    try
      frames.submit(1)
      assert(error.await(5, TimeUnit.SECONDS))
      frames.submit(2)
      assert(done.await(5, TimeUnit.SECONDS))
    finally frames.close()
  }
