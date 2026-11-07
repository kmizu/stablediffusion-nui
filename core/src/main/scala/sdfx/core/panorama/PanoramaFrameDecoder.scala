package sdfx.core.panorama

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

final class PanoramaNoVideoFrame(message: String) extends IOException(message)
final class PanoramaVideoCancelled extends IOException("Video frame request was cancelled")

/** One bounded ffmpeg frame request. This class does not download or bundle ffmpeg. */
final class PanoramaFrameDecoder(
    executable: Path,
    timeoutMs: Long,
    start: Seq[String] => Process = PanoramaFrameDecoder.startProcess
) extends PanoramaFrameReader:
  require(Files.isRegularFile(executable), "ffmpeg executable not found")
  require(timeoutMs > 0 && timeoutMs <= 120000, "Invalid decoder timeout")
  private val ffmpeg = executable.toAbsolutePath.normalize()
  private val watchdog = Executors.newSingleThreadScheduledExecutor(new ThreadFactory:
    def newThread(r: Runnable): Thread =
      val t = new Thread(r, "panorama-ffmpeg-watchdog")
      t.setDaemon(true)
      t
  )
  private var active: Option[Process] = None
  private var closed = false
  private var revision = 0L

  def generation: Long = synchronized { revision }

  def decode(video: Path, seconds: Double, width: Int, height: Int): Array[Byte] =
    decode(video, seconds, width, height, generation)

  def decode(video: Path, seconds: Double, width: Int, height: Int, requestedGeneration: Long): Array[Byte] =
    val input = video.toAbsolutePath.normalize()
    val name = input.toString
    require(!name.startsWith("\\\\") && !name.startsWith("//"), "Network video paths are not supported")
    require(Files.isRegularFile(input), "Video file not found")
    require(seconds.isFinite && seconds >= 0, "Invalid video position")
    require(width > 0 && height > 0 && width <= 1024 && height <= 1024 && width.toLong * height <= 1024L * 1024L,
      "Invalid frame dimensions")
    val expected = Math.multiplyExact(Math.multiplyExact(width, height), 3)
    val command = Seq(ffmpeg.toString, "-nostdin", "-v", "error", "-ss", java.math.BigDecimal.valueOf(seconds).toPlainString,
      "-protocol_whitelist", "file,pipe", "-i", name, "-an", "-vf", s"scale=$width:$height",
      "-frames:v", "1", "-pix_fmt", "rgb24", "-threads", "1", "-f", "rawvideo", "pipe:1")
    val bytes = runBounded(command, expected, Some(requestedGeneration))
    if bytes.isEmpty then throw new PanoramaNoVideoFrame("No video frame at the requested position")
    if bytes.length != expected then throw new IOException("Video frame decoder returned an invalid frame size")
    bytes

  /** Shared process boundary for bounded ffprobe JSON and RGB frame bytes. */
  private[panorama] def runBounded(command: Seq[String], maxBytes: Int, requestedGeneration: Option[Long] = None): Array[Byte] =
    require(maxBytes > 0 && maxBytes <= 4 * 1024 * 1024, "Invalid process output limit")
    val timedOut = new AtomicBoolean(false)
    val (process, deadline) = synchronized {
      if closed then throw new IOException("Video decoder is closed")
      if requestedGeneration.exists(_ != revision) then throw new PanoramaVideoCancelled
      if active.nonEmpty then throw new IOException("Video decoder is already running")
      val p = start(command)
      active = Some(p)
      val future = watchdog.schedule(new Runnable:
        def run(): Unit = { timedOut.set(true); p.destroyForcibly(); () }, timeoutMs, TimeUnit.MILLISECONDS)
      (p, future)
    }
    val diagnostic = new java.io.ByteArrayOutputStream()
    val stderr = new Thread(() =>
      try
        val stream = process.getErrorStream
        val buffer = new Array[Byte](4096)
        var n = stream.read(buffer)
        while n != -1 do
          val keep = math.min(n, 8192 - diagnostic.size())
          if keep > 0 then diagnostic.write(buffer, 0, keep)
          n = stream.read(buffer)
      catch case _: IOException => ()
    , "panorama-ffmpeg-stderr")
    stderr.setDaemon(true)
    stderr.start()
    try
      process.getOutputStream.close()
      val bytes = process.getInputStream.readNBytes(maxBytes + 1)
      if bytes.length > maxBytes then throw new IOException("Video process output is too large")
      val exit = process.waitFor()
      stderr.join(1000)
      if timedOut.get then throw new IOException("Video frame decode timed out")
      if synchronized { closed || requestedGeneration.exists(_ != revision) } then throw new PanoramaVideoCancelled
      if exit != 0 then
        val detail = diagnostic.toString(java.nio.charset.StandardCharsets.UTF_8).trim
        throw new IOException(s"Video frame decoder failed (exit $exit)" + (if detail.isEmpty then "" else s": $detail"))
      bytes
    catch
      case _: IOException if !timedOut.get && synchronized { closed || requestedGeneration.exists(_ != revision) } =>
        throw new PanoramaVideoCancelled
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        throw new PanoramaVideoCancelled
    finally
      deadline.cancel(false)
      if process.isAlive then process.destroyForcibly()
      val interrupted = Thread.interrupted()
      try
        if !process.waitFor(5, TimeUnit.SECONDS) then throw new IOException("Video decoder did not exit")
        process.getInputStream.close()
        process.getErrorStream.close()
        stderr.join(1000)
        synchronized { if active.contains(process) then active = None }
      finally if interrupted then Thread.currentThread().interrupt()

  /** Interrupt the current request without closing the decoder for later requests. */
  def cancel(): Unit = synchronized {
    revision += 1
    active.foreach(_.destroyForcibly())
  }

  override def close(): Unit =
    val process = synchronized {
      closed = true
      revision += 1
      val p = active
      p.foreach(_.destroyForcibly())
      p
    }
    try process.foreach { p =>
      if !p.waitFor(5, TimeUnit.SECONDS) then throw new IOException("Video decoder did not exit")
    }
    finally watchdog.shutdownNow()

object PanoramaFrameDecoder:
  private[panorama] def startProcess(command: Seq[String]): Process =
    new ProcessBuilder(command*).start()
