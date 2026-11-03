package sdfx.core.panorama

import sdfx.core.config.Settings
import java.nio.file.Files

class PanoramaFfmpegSettingsSuite extends munit.FunSuite:
  test("old settings have no configured ffmpeg and a chosen executable survives a round trip") {
    val dir = Files.createTempDirectory("panorama-settings")
    val file = dir.resolve("settings.json")
    try
      Files.writeString(file, "{}")
      val old = Settings.load(file)
      assertEquals(old.panoramaFfmpegExe, None)
      val selected = old.copy(panoramaFfmpegExe = Some("C:/tools/ffmpeg.exe"))
      Settings.save(file, selected)
      assertEquals(Settings.load(file).panoramaFfmpegExe, selected.panoramaFfmpegExe)
    finally { Files.deleteIfExists(file); Files.deleteIfExists(dir) }
  }
