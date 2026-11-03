package sdfx.ui

import javafx.application.Platform
import javafx.scene.control.{Button, Label}
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.core.png.{PngMetadata, ScrambledPng}
import sdfx.engine.{AppServices, EngineLaunch}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.UiState
import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}

/** No Stage.show and no desktop input: focused validation of the actual Panorama UI controller. */
object PanoramaSafetyOffscreenCheck:
  def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(40, TimeUnit.SECONDS)
  def eventually(name: String)(body: => Boolean): Unit =
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
    while !body && System.nanoTime() < end do Thread.sleep(30)
    require(body, "Timed out: " + name)
  def main(args: Array[String]): Unit =
    require(args.length == 3, "python source-root output-directory")
    val root = Path.of(args(2)).toAbsolutePath; Files.createDirectories(root)
    val home = AppHome(Files.createTempDirectory(root, "home-"))
    val out = Files.createTempDirectory(root, "out-")
    val python = Path.of(args(0)).toAbsolutePath
    val engineDir = Path.of(args(1)).toAbsolutePath.resolve("python")
    val services = new AppServices(home, Settings(outputDir = out.toString, previewEvery = 0), dryRun = true,
      builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(python.toString, "-m", "sdfx", "--dry-run"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))), persistSettings = false, installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    var ok = false
    try
      fx { window = new MainWindow(new Stage(), services, UiState(), CliOptions()); require(!window.stage.isShowing) }
      def pane = window.panorama
      def current = fx(pane.model.path.get)
      def status = fx(pane.node.lookup("#panorama-status").asInstanceOf[Label].getText)
      def image(color: Int): BufferedImage =
        val i = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB)
        for y <- 0 until 64; x <- 0 until 128 do i.setRGB(x, y, color)
        i
      val source = root.resolve("source.png"); val patch = root.resolve("patch.png")
      ScrambledPng.write(image(0xff112233), source, Seq("parameters" -> "original"), None)
      ScrambledPng.write(image(0xffaa5544), patch, Nil, None)
      fx(pane.open(source)); eventually("plain source loaded")(current == source)
      ScrambledPng.write(image(0xff667788), source, Seq("parameters" -> "replacement"), None)
      fx(pane.applyPerspective(patch, 0))
      eventually("plain composite")(current != null && current != source && Files.isRegularFile(current))
      require(PngMetadata.readParameters(current).contains("original"), "plain composite metadata must match displayed source")
      require(PngMetadata.readImage(current).getRGB(0, 0) == 0xff112233, "plain composite must retain displayed source pixels")
      println("PANORAMA_OFFSCREEN_PLAIN_SNAPSHOT_OK")
      fx(pane.open(source)); eventually("source reopened for conversion")(current == source)
      val other = root.resolve("other.png")
      ScrambledPng.write(image(0xff334455), other, Seq("parameters" -> "other"), None)
      fx { services.imageScrambleSession.synchronized {
        val convert = pane.node.lookup("#panorama-to-cube").asInstanceOf[Button]
        require(!convert.isDisabled)
        convert.fire()
        pane.open(other)
      } }
      eventually("new source loaded")(current == other)
      eventually("stale conversion completed")(!fx(pane.perspectivePending))
      require(current == other, "stale conversion must not replace a newer source")
      require(Files.isRegularFile(out.resolve("panorama/source-cubemap.png")), "published stale output should be retained")
      println("PANORAMA_OFFSCREEN_STALE_CONVERSION_OK")
      val protectedSource = root.resolve("protected.png")
      ScrambledPng.write(image(0xff123456), protectedSource, Seq("parameters" -> "protected-original"), Some("secret"))
      fx { services.imageScrambleSession.unlock("secret"); window.ctx.imageAccessChanged(); pane.open(protectedSource) }
      eventually("protected source loaded")(current == protectedSource)
      ScrambledPng.write(image(0xffabcdef), protectedSource, Seq("parameters" -> "protected-replacement"), Some("secret"))
      fx(pane.applyPerspective(patch, 0))
      eventually("protected composite")(current != null && current != protectedSource && Files.isRegularFile(current))
      require(PngMetadata.readParameters(current).contains("protected-original"), "protected composite metadata must match displayed source")
      require(PngMetadata.readTexts(current).contains("Encrypt"), "protected composite must remain scrambled")
      require(ScrambledPng.read(current, Some("secret")).image.getRGB(0, 0) == 0xff123456, "protected composite must retain displayed source pixels")
      println("PANORAMA_OFFSCREEN_PROTECTED_SNAPSHOT_OK")
      fx { services.imageScrambleSession.lock(); window.ctx.imageAccessChanged(); pane.open(source) }
      eventually("source reopened")(current == source)
      fx { pane.viewport.resize(400, 200); window.root.applyCss(); window.root.layout() }
      val collision = fx { services.imageScrambleSession.synchronized {
        pane.viewport.resize(400, 200)
        val path = pane.saveView(128).getOrElse(sys.error("view dimensions unavailable"))
        Files.createDirectories(path.getParent)
        Files.writeString(path, "other-owner")
        path
      } }
      eventually("collision reported")(status.startsWith("Cannot save the view"))
      require(Files.readString(collision) == "other-owner", "view save replaced another owner's file")
      require(fx(!window.stage.isShowing), "offscreen stage must not be shown")
      println("PANORAMA_OFFSCREEN_COLLISION_OK")
      println("PANORAMA_SAFETY_OFFSCREEN_OK")
      ok = true
    catch case e: Throwable => e.printStackTrace()
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown()
      Platform.exit()
    if !ok then System.exit(1)
