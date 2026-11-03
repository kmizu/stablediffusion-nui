package sdfx.core.panorama

/** Playback position independent of decoder timing. The supplied clock must be monotonic. */
final class PanoramaVideoClock(val duration: Double, nowNanos: () => Long):
  require(duration.isFinite && duration > 0, "Invalid video duration")
  private var anchor = nowNanos()
  private var offset = 0.0
  private var rate = 1.0
  private var running = false

  private def advance(now: Long): Unit =
    if running then
      val elapsed = math.max(0L, now - anchor).toDouble / 1e9
      offset = math.min(duration, offset + elapsed * rate)
      if offset >= duration then running = false
    anchor = now

  def position: Double = synchronized { advance(nowNanos()); offset }
  def playing: Boolean = synchronized { advance(nowNanos()); running }
  def speed: Double = synchronized { rate }

  def play(): Unit = synchronized {
    val now = nowNanos()
    advance(now)
    if offset >= duration then offset = 0.0
    running = true
    anchor = now
  }

  def pause(): Unit = synchronized {
    advance(nowNanos())
    running = false
  }

  def seek(seconds: Double): Unit = synchronized {
    require(seconds.isFinite && seconds >= 0 && seconds <= duration, "Invalid video position")
    val now = nowNanos()
    advance(now)
    offset = seconds
    if offset >= duration then running = false
    anchor = now
  }

  def setSpeed(value: Double): Unit = synchronized {
    require(value.isFinite && value >= 0.25 && value <= 4.0, "Video speed must be between 0.25 and 4")
    advance(nowNanos())
    rate = value
  }
