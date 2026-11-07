package sdfx.core.panorama

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.Try

final case class PanoramaVideoInfo(width: Int, height: Int, duration: Double)

object PanoramaVideoInfo:
  val MaxJsonBytes = 262144

  def probe(ffprobe: Path, video: Path, timeoutMs: Long,
      start: Seq[String] => Process = PanoramaFrameDecoder.startProcess): PanoramaVideoInfo =
    require(Files.isRegularFile(ffprobe), "ffprobe executable not found")
    val runner = new PanoramaFrameDecoder(ffprobe, timeoutMs, start)
    try probeUsing(runner, ffprobe, video, runner.generation)
    finally runner.close()

  /** Reusable cancellable runner lets a superseding UI request stop metadata probing. */
  def probeUsing(runner: PanoramaFrameDecoder, ffprobe: Path, video: Path, generation: Long): PanoramaVideoInfo =
    require(Files.isRegularFile(ffprobe), "ffprobe executable not found")
    val input = video.toAbsolutePath.normalize()
    val name = input.toString
    require(!name.startsWith("\\\\") && !name.startsWith("//"), "Network video paths are not supported")
    require(Files.isRegularFile(input), "Video file not found")
    val command = Seq(ffprobe.toAbsolutePath.normalize().toString, "-v", "error",
      "-protocol_whitelist", "file,pipe", "-select_streams", "v:0", "-show_entries",
      "stream=width,height:format=duration", "-of", "json", "-i", name)
    parse(runner.runBounded(command, MaxJsonBytes, Some(generation)))

  /** Parse bounded ffprobe output. Video dimensions retain the still viewer's existing limit. */
  def parse(bytes: Array[Byte]): PanoramaVideoInfo =
    require(bytes.length <= MaxJsonBytes, "Video metadata is too large")
    val root = ujson.read(new String(bytes, UTF_8))
    val stream = root.obj.get("streams").flatMap(_.arrOpt).flatMap(_.headOption)
      .flatMap(_.objOpt).getOrElse(throw new IllegalArgumentException("Video stream is missing"))
    def dimension(key: String): Int =
      val d = stream.get(key).flatMap(_.numOpt).getOrElse(throw new IllegalArgumentException(s"Video $key is missing"))
      require(d.isFinite && d > 0 && d <= Int.MaxValue && d == math.floor(d), s"Invalid video $key")
      d.toInt
    val width = dimension("width")
    val height = dimension("height")
    PanoramaFiles.checkSize(width, height).left.foreach(m => throw new IllegalArgumentException(m))
    val duration = root.obj.get("format").flatMap(_.objOpt).flatMap(_.get("duration"))
      .flatMap(_.strOpt).flatMap(s => Try(s.toDouble).toOption)
      .getOrElse(throw new IllegalArgumentException("Video duration is missing"))
    require(duration.isFinite && duration > 0, "Invalid video duration")
    PanoramaVideoInfo(width, height, duration)
