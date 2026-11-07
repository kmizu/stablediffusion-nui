package sdfx.core.panorama

import java.nio.file.Path
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable.ArrayBuffer

class PanoramaVideoPlaybackSuite extends munit.FunSuite:
  test("seek discards an in-flight old frame and shows the selected frame") {
    val firstStarted = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val delivered = new CountDownLatch(1)
    val results = ArrayBuffer.empty[(Long, Double)]
    val fake = new PanoramaFrameReader:
      private var version = 0L
      def generation: Long = synchronized(version)
      def cancel(): Unit = synchronized { version += 1 }
      def close(): Unit = cancel()
      def decode(path: Path, seconds: Double, width: Int, height: Int, request: Long): Array[Byte] =
        if seconds == 0.0 then
          firstStarted.countDown()
          require(release.await(5, TimeUnit.SECONDS))
        Array.fill[Byte](12)(seconds.toInt.toByte)
    val playback = new PanoramaVideoPlayback(Path.of("test.mp4"), 10.0, 2, 2, fake, () => 0L,
      (epoch, seconds, _) => { results.synchronized { results += epoch -> seconds }; delivered.countDown() }, (_, _) => ())
    try
      playback.play()
      assert(firstStarted.await(5, TimeUnit.SECONDS))
      playback.seek(3.0)
      release.countDown()
      assert(delivered.await(5, TimeUnit.SECONDS))
      assertEquals(results.synchronized(results.toList), List(playback.currentEpoch -> 3.0))
    finally { release.countDown(); playback.close() }
  }

  test("pause, seek and speed operate on the monotonic playback position") {
    var now = 0L
    val fake = new PanoramaFrameReader:
      def generation: Long = 0L
      def cancel(): Unit = ()
      def close(): Unit = ()
      def decode(path: Path, seconds: Double, width: Int, height: Int, request: Long): Array[Byte] = Array.fill[Byte](12)(0)
    val playback = new PanoramaVideoPlayback(Path.of("test.mp4"), 10.0, 2, 2, fake, () => now, (_, _, _) => (), (_, _) => ())
    try
      playback.play()
      now = 2_000_000_000L
      playback.pause()
      now = 4_000_000_000L
      assertEqualsDouble(playback.position, 2.0, 1e-9)
      playback.seek(4.0)
      playback.setSpeed(0.5)
      playback.play()
      now = 6_000_000_000L
      assertEqualsDouble(playback.position, 5.0, 1e-9)
    finally playback.close()
  }

  test("invalid seek or speed does not cancel the current playback") {
    val cancelCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val fake = new PanoramaFrameReader:
      def generation: Long = cancelCount.get().toLong
      def cancel(): Unit = { cancelCount.incrementAndGet(); () }
      def close(): Unit = ()
      def decode(path: Path, seconds: Double, width: Int, height: Int, request: Long): Array[Byte] = Array.fill[Byte](12)(0)
    val playback = new PanoramaVideoPlayback(Path.of("test.mp4"), 10.0, 2, 2, fake, () => 0L, (_, _, _) => (), (_, _) => ())
    try
      playback.play()
      val epoch = playback.currentEpoch
      val cancellations = cancelCount.get()
      intercept[IllegalArgumentException] { playback.seek(-1.0) }
      intercept[IllegalArgumentException] { playback.setSpeed(0.0) }
      assertEquals(playback.currentEpoch, epoch)
      assertEquals(cancelCount.get(), cancellations)
      assert(playback.playing)
    finally playback.close()
  }

  test("near the end, a successful decode with no frame retries a nearby earlier time") {
    val done = new CountDownLatch(1)
    val attempts = ArrayBuffer.empty[Double]
    val result = new java.util.concurrent.atomic.AtomicReference[(Double, Array[Byte])]()
    val fake = new PanoramaFrameReader:
      def generation: Long = 0L
      def cancel(): Unit = ()
      def close(): Unit = ()
      def decode(path: Path, seconds: Double, width: Int, height: Int, request: Long): Array[Byte] =
        attempts.synchronized { attempts += seconds }
        if seconds > 9.9 then throw new PanoramaNoVideoFrame("no frame")
        Array.fill[Byte](12)(4)
    val playback = new PanoramaVideoPlayback(Path.of("test.mp4"), 10.0, 2, 2, fake, () => 0L,
      (_, seconds, bytes) => { result.set(seconds -> bytes); done.countDown() }, (_, _) => done.countDown())
    try
      playback.seek(9.95)
      assert(done.await(5, TimeUnit.SECONDS))
      assert(result.get() != null, "near-end fallback should supply a frame")
      assertEqualsDouble(result.get()._1, 9.95, 1e-9)
      assert(attempts.synchronized(attempts.size) >= 2)
    finally playback.close()
  }

  test("missing frames away from the end report an error without retry") {
    val done = new CountDownLatch(1)
    val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
    val fake = new PanoramaFrameReader:
      def generation: Long = 0L
      def cancel(): Unit = ()
      def close(): Unit = ()
      def decode(path: Path, seconds: Double, width: Int, height: Int, request: Long): Array[Byte] =
        attempts.incrementAndGet()
        throw new PanoramaNoVideoFrame("no frame")
    val playback = new PanoramaVideoPlayback(Path.of("test.mp4"), 10.0, 2, 2, fake, () => 0L,
      (_, _, _) => (), (_, _) => done.countDown())
    try
      playback.seek(2.0)
      assert(done.await(5, TimeUnit.SECONDS))
      assertEquals(attempts.get(), 1)
    finally playback.close()
  }
