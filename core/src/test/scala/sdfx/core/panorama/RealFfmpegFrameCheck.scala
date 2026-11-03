package sdfx.core.panorama

import java.nio.file.Path

/** Manual CPU check against a user-supplied ffmpeg and red/blue local video. */
object RealFfmpegFrameCheck:
  def main(args: Array[String]): Unit =
    require(args.length == 3, "ffmpeg executable, ffprobe executable and local red/blue video")
    val decoder = new PanoramaFrameDecoder(Path.of(args(0)), 10000)
    try
      def centre(data: Array[Byte]): (Int, Int, Int) =
        val i = ((160 / 2) * 320 + 320 / 2) * 3
        (data(i) & 255, data(i + 1) & 255, data(i + 2) & 255)
      val info = PanoramaVideoInfo.probe(Path.of(args(1)), Path.of(args(2)), 10000)
      require(info.width == 320 && info.height == 160 && math.abs(info.duration - 60.0) < 0.1, s"unexpected video metadata $info")
      val red = centre(decoder.decode(Path.of(args(2)), 0.0, 320, 160))
      val blue = centre(decoder.decode(Path.of(args(2)), 1.4, 320, 160))
      require(red._1 > 170 && red._3 < 80, s"expected red, got $red")
      require(blue._3 > 170 && blue._1 < 80, s"expected blue, got $blue")
      println(s"REAL_FFMPEG_FRAME_OK info=$info red=$red blue=$blue")
    finally decoder.close()
