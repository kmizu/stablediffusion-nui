package sdfx.core.panorama

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

trait PanoramaFrameReader extends AutoCloseable:
  def generation: Long
  def cancel(): Unit
  def decode(video: Path, seconds: Double, width: Int, height: Int, request: Long): Array[Byte]

/** Playback state and a single bounded decoding lane; callbacks run on the decoder thread. */
final class PanoramaVideoPlayback(
    video: Path, duration: Double, width: Int, height: Int, reader: PanoramaFrameReader,
    nowNanos: () => Long, onFrame: (Long, Double, Array[Byte]) => Unit, onError: (Long, Throwable) => Unit
) extends AutoCloseable:
  private val clock = new PanoramaVideoClock(duration, nowNanos)
  private val epoch = new AtomicLong(0)
  private var closed = false
  private case class Request(seconds: Double, epoch: Long, generation: Long)
  private def decode(request: Request): Array[Byte] =
    val candidates =
      if request.seconds >= math.max(0.0, duration - 2.0) then
        (Seq(request.seconds) ++ Seq(0.1, 0.25, 0.5, 1.0, 2.0).map(delta => math.max(0.0, request.seconds - delta))).distinct
      else Seq(request.seconds)
    var missing: PanoramaNoVideoFrame = null
    val iterator = candidates.iterator
    while iterator.hasNext do
      if epoch.get != request.epoch then throw new java.io.IOException("Video frame request was cancelled")
      try return reader.decode(video, iterator.next(), width, height, request.generation)
      catch case e: PanoramaNoVideoFrame => missing = e
    throw missing
  private val frames = new LatestFrame[Request](request =>
    try
      val bytes = decode(request)
      if epoch.get == request.epoch then onFrame(request.epoch, request.seconds, bytes)
    catch
      case NonFatal(e) => if epoch.get == request.epoch then onError(request.epoch, e)
  )

  def position: Double = clock.position
  def playing: Boolean = clock.playing
  def speed: Double = clock.speed
  def currentEpoch: Long = epoch.get

  private def schedule(): Unit =
    if !closed then frames.submit(Request(clock.position, epoch.get, reader.generation))

  private def change(action: => Unit): Unit = synchronized {
    if closed then throw new IllegalStateException("Video playback is closed")
    epoch.incrementAndGet()
    reader.cancel()
    action
    schedule()
  }

  def play(): Unit = change(clock.play())
  def pause(): Unit = change(clock.pause())
  def seek(seconds: Double): Unit =
    require(seconds.isFinite && seconds >= 0 && seconds <= duration, "Invalid video position")
    change(clock.seek(seconds))
  def setSpeed(value: Double): Unit =
    require(value.isFinite && value >= 0.25 && value <= 4.0, "Video speed must be between 0.25 and 4")
    change(clock.setSpeed(value))
  def tick(): Unit = synchronized { if !closed && clock.playing then schedule() }

  override def close(): Unit = synchronized {
    if !closed then
      closed = true
      epoch.incrementAndGet()
      frames.close()
      reader.close()
  }
