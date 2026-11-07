package sdfx.core.panorama

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

class PanoramaVideoInfoSuite extends munit.FunSuite:
  private def bytes(s: String) = s.getBytes(UTF_8)

  test("reads video dimensions and duration from ffprobe JSON") {
    val info = PanoramaVideoInfo.parse(bytes("""{"streams":[{"width":1024,"height":512}],"format":{"duration":"12.25"}}"""))
    assertEquals(info.width, 1024)
    assertEquals(info.height, 512)
    assertEqualsDouble(info.duration, 12.25, 1e-9)
  }

  test("rejects missing, invalid and excessive metadata") {
    val cases = Seq(
      "{}", "not json", """{"streams":[],"format":{"duration":"1"}}""",
      """{"streams":[{"width":0,"height":512}],"format":{"duration":"1"}}""",
      """{"streams":[{"width":20000,"height":10000}],"format":{"duration":"1"}}""",
      """{"streams":[{"width":1024,"height":512}],"format":{"duration":"NaN"}}""",
      """{"streams":[{"width":1024,"height":512}],"format":{"duration":"0"}}"""
    )
    cases.foreach(raw => intercept[Exception] { PanoramaVideoInfo.parse(bytes(raw)) })
    intercept[Exception] { PanoramaVideoInfo.parse(Array.fill[Byte](262145)(32)) }
  }

  test("probe runs bounded metadata command and parses its output") {
    val javaExe = Path.of(System.getProperty("java.home"), "bin", if System.getProperty("os.name").startsWith("Windows") then "java.exe" else "java")
    val classes = Path.of(classOf[PanoramaVideoInfoSuite].getProtectionDomain.getCodeSource.getLocation.toURI).toString
    val scalaLib = Path.of(classOf[scala.Option[?]].getProtectionDomain.getCodeSource.getLocation.toURI).toString
    val cp = Seq(classes, scalaLib, System.getProperty("java.class.path")).mkString(java.io.File.pathSeparator)
    val video = Files.createTempFile("panorama-probe", ".mp4")
    try
      var actual = Seq.empty[String]
      val info = PanoramaVideoInfo.probe(javaExe, video, 5000, command => {
        actual = command
        new ProcessBuilder(javaExe.toString, "-cp", cp, "sdfx.core.panorama.PanoramaDecoderChild", "metadata", "unused")
          .redirectError(ProcessBuilder.Redirect.INHERIT).start()
      })
      assertEquals(info.width, 1024)
      assertEqualsDouble(info.duration, 12.25, 1e-9)
      assert(actual.contains("-protocol_whitelist"))
      assert(actual.contains(video.toAbsolutePath.normalize().toString))
    finally Files.deleteIfExists(video)
  }

  test("cancelled metadata request cannot start a delayed child") {
    val executable = Path.of(System.getProperty("java.home"), "bin", if System.getProperty("os.name").startsWith("Windows") then "java.exe" else "java")
    val video = Files.createTempFile("panorama-probe", ".mp4")
    var launched = false
    val runner = new PanoramaFrameDecoder(executable, 1000, _ => { launched = true; throw new RuntimeException("must not start") })
    try
      val stale = runner.generation
      runner.cancel()
      intercept[Exception] { PanoramaVideoInfo.probeUsing(runner, executable, video, stale) }
      assert(!launched)
    finally { runner.close(); Files.deleteIfExists(video) }
  }
