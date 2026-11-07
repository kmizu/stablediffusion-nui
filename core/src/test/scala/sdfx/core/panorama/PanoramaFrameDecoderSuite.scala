package sdfx.core.panorama

import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

/** Process fixture for decoder lifetime checks. */
object PanoramaDecoderChild:
  def main(args: Array[String]): Unit = args(0) match
    case "frame" => System.out.write(Array.tabulate[Byte](12)(_.toByte)); System.out.flush()
    case "oversize" => System.out.write(Array.fill[Byte](13)(1)); System.out.flush()
    case "bad-exit" => System.out.write(Array.fill[Byte](12)(1)); System.out.flush(); System.exit(4)
    case "empty" => ()
    case "diagnostic" =>
      System.err.print("diagnostic-marker:" + "x" * 200000); System.err.flush(); System.exit(22)
    case "metadata" =>
      System.out.write("""{"streams":[{"width":1024,"height":512}],"format":{"duration":"12.25"}}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      System.out.flush()
    case "blocked" =>
      System.out.write(42); System.out.flush()
      Files.writeString(Path.of(args(1)), "blocked")
      Thread.sleep(60000)

class PanoramaFrameDecoderSuite extends munit.FunSuite:
  private val executable = Path.of(System.getProperty("java.home"), "bin", if System.getProperty("os.name").startsWith("Windows") then "java.exe" else "java")
  private val fixturePath = Path.of(classOf[PanoramaFrameDecoderSuite].getProtectionDomain.getCodeSource.getLocation.toURI).toString
  private val scalaPath = Path.of(classOf[scala.Option[?]].getProtectionDomain.getCodeSource.getLocation.toURI).toString
  private val classpath = Seq(fixturePath, scalaPath, System.getProperty("java.class.path")).mkString(java.io.File.pathSeparator)
  private def child(mode: String, started: CountDownLatch, seen: java.util.concurrent.atomic.AtomicReference[Process], signal: Path): Seq[String] => Process = _ =>
    val p = new ProcessBuilder(executable.toString, "-cp", classpath,
      "sdfx.core.panorama.PanoramaDecoderChild", mode, signal.toString).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    seen.set(p); started.countDown(); p

  test("reads one exact frame and rejects excessive or failed output") {
    val video = Files.createTempFile("panorama-decoder", ".mp4")
    val signal = Files.createTempDirectory("panorama-child").resolve("ready")
    try
      for mode <- Seq("frame", "oversize", "bad-exit", "empty") do
        val started = new CountDownLatch(1)
        val seen = new java.util.concurrent.atomic.AtomicReference[Process]()
        val decoder = new PanoramaFrameDecoder(executable, 5000, child(mode, started, seen, signal))
        try
          if mode == "frame" then assertEquals(decoder.decode(video, 0.0, 2, 2).toSeq, (0 until 12).map(_.toByte))
          else if mode == "empty" then intercept[PanoramaNoVideoFrame] { decoder.decode(video, 0.0, 2, 2) }
          else intercept[Exception] { decoder.decode(video, 0.0, 2, 2) }
          assert(!seen.get().isAlive)
        finally decoder.close()
    finally { Files.deleteIfExists(video); Files.deleteIfExists(signal); Files.deleteIfExists(signal.getParent) }
  }

  test("drains bounded stderr concurrently and reports the original process failure") {
    val video = Files.createTempFile("panorama-decoder", ".mp4")
    val launch: Seq[String] => Process = _ => new ProcessBuilder(executable.toString, "-cp", classpath,
      "sdfx.core.panorama.PanoramaDecoderChild", "diagnostic").start()
    val decoder = new PanoramaFrameDecoder(executable, 5000, launch)
    try
      val error = intercept[java.io.IOException] { decoder.decode(video, 0.0, 2, 2) }
      assert(error.getMessage.contains("exit 22"), error.getMessage)
      assert(error.getMessage.contains("diagnostic-marker"), error.getMessage)
      assert(error.getMessage.length < 20000, "stderr must be bounded")
    finally { decoder.close(); Files.deleteIfExists(video) }
  }

  test("small timestamps use a decimal ffmpeg duration") {
    val video = Files.createTempFile("panorama-decoder", ".mp4")
    val started = new CountDownLatch(1)
    val seen = new java.util.concurrent.atomic.AtomicReference[Process]()
    val signal = Files.createTempDirectory("panorama-child").resolve("ready")
    var actual = ""
    val launch: Seq[String] => Process = command =>
      actual = command(command.indexOf("-ss") + 1)
      child("frame", started, seen, signal)(command)
    val decoder = new PanoramaFrameDecoder(executable, 5000, launch)
    try
      decoder.decode(video, 0.0000016, 2, 2)
      assertEquals(actual, "0.0000016")
    finally { decoder.close(); Files.deleteIfExists(video); Files.deleteIfExists(signal); Files.deleteIfExists(signal.getParent) }
  }

  test("deadline terminates a decoder blocked on output") {
    val video = Files.createTempFile("panorama-decoder", ".mp4")
    val signal = Files.createTempDirectory("panorama-child").resolve("ready")
    val started = new CountDownLatch(1)
    val seen = new java.util.concurrent.atomic.AtomicReference[Process]()
    val decoder = new PanoramaFrameDecoder(executable, 2000, child("blocked", started, seen, signal))
    try
      intercept[Exception] { decoder.decode(video, 0.0, 2, 2) }
      assert(started.await(5, TimeUnit.SECONDS))
      assert(Files.exists(signal), "child must have entered its blocked state")
      assert(!seen.get().isAlive)
    finally { decoder.close(); Files.deleteIfExists(video); Files.deleteIfExists(signal); Files.deleteIfExists(signal.getParent) }
  }

  test("closing terminates an in-flight decoder") {
    val video = Files.createTempFile("panorama-decoder", ".mp4")
    val signal = Files.createTempDirectory("panorama-child").resolve("ready")
    val started = new CountDownLatch(1)
    val seen = new java.util.concurrent.atomic.AtomicReference[Process]()
    val decoder = new PanoramaFrameDecoder(executable, 10000, child("blocked", started, seen, signal))
    val executor = Executors.newSingleThreadExecutor()
    try
      val result = executor.submit(new java.util.concurrent.Callable[Boolean]:
        def call(): Boolean =
          try { decoder.decode(video, 0.0, 2, 2); false }
          catch case _: Exception => true
      )
      assert(started.await(5, TimeUnit.SECONDS))
      val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while !Files.exists(signal) && System.nanoTime() < limit do Thread.sleep(10)
      assert(Files.exists(signal), "child must have entered its blocked state")
      decoder.close()
      assert(result.get(5, TimeUnit.SECONDS))
      assert(!seen.get().isAlive)
    finally { decoder.close(); executor.shutdownNow(); Files.deleteIfExists(video); Files.deleteIfExists(signal); Files.deleteIfExists(signal.getParent) }
  }

  test("cancel stops the current decode and permits a later frame") {
    val video = Files.createTempFile("panorama-decoder", ".mp4")
    val signal = Files.createTempDirectory("panorama-child").resolve("ready")
    val started = new CountDownLatch(1)
    val seen = new java.util.concurrent.atomic.AtomicReference[Process]()
    var count = 0
    val launch: Seq[String] => Process = command =>
      count += 1
      child(if count == 1 then "blocked" else "frame", started, seen, signal)(command)
    val decoder = new PanoramaFrameDecoder(executable, 10000, launch)
    val executor = Executors.newSingleThreadExecutor()
    try
      val result = executor.submit(new java.util.concurrent.Callable[String]:
        def call(): String =
          try { decoder.decode(video, 0.0, 2, 2); "success" }
          catch case e: Exception => e.getClass.getSimpleName
      )
      assert(started.await(5, TimeUnit.SECONDS))
      val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while !Files.exists(signal) && System.nanoTime() < limit do Thread.sleep(10)
      assert(Files.exists(signal))
      decoder.cancel()
      assertEquals(result.get(5, TimeUnit.SECONDS), "PanoramaVideoCancelled")
      assertEquals(decoder.decode(video, 1.0, 2, 2).length, 12)
    finally { decoder.close(); executor.shutdownNow(); Files.deleteIfExists(video); Files.deleteIfExists(signal); Files.deleteIfExists(signal.getParent) }
  }

  test("cancel prevents an already queued old request from starting a child") {
    val video = Files.createTempFile("panorama-decoder", ".mp4")
    val started = new CountDownLatch(1)
    val seen = new java.util.concurrent.atomic.AtomicReference[Process]()
    val signal = Files.createTempDirectory("panorama-child").resolve("ready")
    val decoder = new PanoramaFrameDecoder(executable, 5000, child("frame", started, seen, signal))
    try
      val stale = decoder.generation
      decoder.cancel()
      intercept[Exception] { decoder.decode(video, 0.0, 2, 2, stale) }
      assertEquals(seen.get(), null)
      assertEquals(decoder.decode(video, 0.0, 2, 2, decoder.generation).length, 12)
    finally { decoder.close(); Files.deleteIfExists(video); Files.deleteIfExists(signal); Files.deleteIfExists(signal.getParent) }
  }

  test("missing executable and invalid image dimensions are rejected") {
    val video = Files.createTempFile("panorama-decoder", ".mp4")
    try
      intercept[Exception] { new PanoramaFrameDecoder(video.resolveSibling("missing-ffmpeg.exe"), 1000) }
      val decoder = new PanoramaFrameDecoder(executable, 1000)
      try
        intercept[Exception] { decoder.decode(video, -1.0, 2, 2) }
        intercept[Exception] { decoder.decode(video, 0.0, 0, 2) }
        intercept[Exception] { decoder.decode(video, 0.0, 100000, 2) }
      finally decoder.close()
    finally Files.deleteIfExists(video)
  }
