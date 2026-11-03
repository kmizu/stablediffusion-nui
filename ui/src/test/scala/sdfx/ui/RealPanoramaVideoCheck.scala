package sdfx.ui

import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.{Button, ComboBox, Labeled, TabPane}
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.core.panorama.{Panorama, Projection, Raster, View}
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Manual GUI gate: a 60-second video with a red first second and blue remainder. */
object RealPanoramaVideoCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body
    )
    Platform.runLater(task)
    task.get(30, TimeUnit.SECONDS)

  private def eventually(name: String, seconds: Int = 30)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + seconds.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(40)
    require(condition, s"Timed out: $name")

  def main(args: Array[String]): Unit =
    require(args.length == 7, "python video.mp4 output-directory still.png sd15-video.mp4 sdxl-video.mp4 ffmpeg.exe")
    val video = Paths.get(args(1)).toAbsolutePath.normalize()
    val out = Paths.get(args(2)).toAbsolutePath.normalize()
    val still = Paths.get(args(3)).toAbsolutePath.normalize()
    val panoramaVideos = Seq(args(4), args(5)).map(p => Paths.get(p).toAbsolutePath.normalize())
    Files.createDirectories(out)
    val home = AppHome(Files.createTempDirectory(out, "app-home-"))
    val engineDir = AppHome.detect().pythonDir
    val services = new AppServices(home, Settings(outputDir = out.toString, panoramaFfmpegExe = Some(args(6))), dryRun = true,
      builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(Paths.get(args(0)).toAbsolutePath.toString, "-m", "sdfx", "--dry-run"),
        engineDir, Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))),
      persistSettings = false, timeouts = EngineTimeouts(load = 2.minutes), installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions())
        window.stage.setWidth(1700); window.stage.setHeight(1000)
        window.show()
        val tabs = window.root.lookupAll(".tab-pane").asScala.collectFirst {
          case pane: TabPane if pane.getTabs.asScala.exists(_.getId == "panorama-tab-page") => pane
        }.get
        tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "panorama-tab-page").get)
        window.panorama.open(still)
      }
      services.start()
      eventually("still image opened") { fx(Option(window.panorama.model.path.get).contains(still)) }
      services.updateSettingsChecked(_.copy(panoramaFfmpegExe = None)).fold(error => throw new IllegalStateException(error), _ => ())
      fx(window.panorama.openVideo(video))
      assert(fx(window.panorama.node.lookup("#panorama-status").asInstanceOf[Labeled].getText.contains("Choose an ffmpeg")))
      assert(fx(!window.panorama.videoActive))
      services.updateSettingsChecked(_.copy(panoramaFfmpegExe = Some(args(6)))).fold(error => throw new IllegalStateException(error), _ => ())
      fx(window.panorama.openVideo(video))
      eventually("video frame") { fx(window.panorama.model.raster.get != null) }
      eventually("video replaces still input") { fx(window.panorama.videoActive && window.panorama.model.video.get && window.panorama.model.path.get == null) }
      eventually("rendered frame") { window.panorama.viewport.renders > 0 }
      eventually("fullscreen enabled") { fx {
        val button = window.panorama.node.lookup("#panorama-fullscreen").asInstanceOf[Button]
        button != null && !button.isDisabled
      } }
      def centre: Int = fx {
        val raster = window.panorama.model.raster.get
        if raster == null then 0 else raster.argb((raster.height / 2) * raster.width + raster.width / 2)
      }
      fx {
        val pause = window.panorama.node.lookup("#panorama-video-play").asInstanceOf[Button]
        require(pause != null && !pause.isDisabled)
        println(s"VIDEO_UI_BEFORE_PAUSE seconds=${window.panorama.videoSeconds} label=${pause.getText} active=${window.panorama.videoActive}")
        pause.fire()
      }
      eventually("paused") { fx(window.panorama.videoPaused) }
      fx {
        window.panorama.model.setView(View(30, 20, 70))
        val up = window.panorama.node.lookup("#panorama-direction-up").asInstanceOf[Button]
        require(up != null && !up.isDisabled)
        up.fire()
        require(window.panorama.model.view == View(0, 90, 70))
      }
      fx {
        val speed = window.panorama.node.lookup("#panorama-video-speed").asInstanceOf[ComboBox[java.lang.Double]]
        speed.setValue(java.lang.Double.valueOf(2.0))
        assertEqualsSpeed(window.panorama.videoRate, 2.0)
        speed.setValue(java.lang.Double.valueOf(0.5))
        assertEqualsSpeed(window.panorama.videoRate, 0.5)
        speed.setValue(java.lang.Double.valueOf(1.0))
      }
      fx(window.panorama.seekVideo(1.4))
      eventually("blue video frame") { (centre & 255) > 170 && ((centre >> 16) & 255) < 80 }
      fx {
        window.panorama.node.applyCss()
        window.panorama.node.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", out.resolve("video.png").toFile))
      }
      val previousImage = fx(window.vms(Mode.Img2Img).initImage.get)
      fx(window.panorama.node.lookup("#panorama-view-to-img2img").asInstanceOf[Button].fire())
      eventually("paused video perspective sent") { fx(window.vms(Mode.Img2Img).initImage.get) != previousImage }
      val sent = Paths.get(fx(window.vms(Mode.Img2Img).initImage.get))
      val sentImage = ImageIO.read(sent.toFile)
      val pixel = sentImage.getRGB(sentImage.getWidth / 2, sentImage.getHeight / 2)
      require((pixel & 255) > 170 && ((pixel >> 16) & 255) < 80, "sent view must capture paused blue frame")
      fx {
        val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
        tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "panorama-tab-page").get)
      }
      fx(window.panorama.seekVideo(0.4))
      eventually("seeked") { fx(math.abs(window.panorama.videoSeconds - 0.4) < 0.25) }
      eventually("red frame after paused seek") { ((centre >> 16) & 255) > 170 && (centre & 255) < 80 }
      fx {
        window.panorama.seekVideo(59.5)
        window.panorama.node.lookup("#panorama-video-play").asInstanceOf[Button].fire()
      }
      eventually("playing near end") { fx(!window.panorama.videoPaused && window.panorama.videoSeconds >= 59.4) }
      eventually("play label at end") { fx {
        window.panorama.videoSeconds >= 59.9 &&
          window.panorama.node.lookup("#panorama-video-play").asInstanceOf[Button].getText == "Play"
      } }
      fx {
        window.panorama.seekVideo(5.0)
        window.panorama.node.lookup("#panorama-video-play").asInstanceOf[Button].fire()
      }
      eventually("play from chosen position after end", 3) { fx(!window.panorama.videoPaused && window.panorama.videoSeconds >= 5.0 && window.panorama.videoSeconds < 10.0) }
      fx {
        window.panorama.seekVideo(59.5)
      }
      try eventually("play label at second end") { fx {
          window.panorama.videoSeconds >= 59.9 &&
            window.panorama.node.lookup("#panorama-video-play").asInstanceOf[Button].getText == "Play"
        } }
      catch case e: Throwable =>
        println(fx {
          val status = window.panorama.node.lookup("#panorama-status").asInstanceOf[Labeled].getText
          val label = window.panorama.node.lookup("#panorama-video-play").asInstanceOf[Button].getText
          s"VIDEO_SECOND_END_DIAG active=${window.panorama.videoActive} paused=${window.panorama.videoPaused} seconds=${window.panorama.videoSeconds} label=$label status=$status"
        })
        throw e
      fx(window.panorama.node.lookup("#panorama-video-play").asInstanceOf[Button].fire())
      eventually("replay from start") { fx(!window.panorama.videoPaused && window.panorama.videoSeconds < 2.0) }
      fx(window.panorama.stopVideo())
      eventually("stopped") { fx(!window.panorama.videoActive) }
      fx(window.panorama.open(still))
      eventually("still image restored") { fx(!window.panorama.videoActive && !window.panorama.model.video.get && Option(window.panorama.model.path.get).contains(still)) }
      panoramaVideos.zipWithIndex.foreach { (path, index) =>
        val previous = fx { val old = window.panorama.model.raster.get; window.panorama.openVideo(path); old }
        try eventually(s"panorama video frame $index") { fx {
            val r = window.panorama.model.raster.get
            window.panorama.model.video.get && r != null && (r ne previous) && r.width == 512 && r.height == 256
          } }
        catch case e: Throwable =>
          println(fx {
            val r = window.panorama.model.raster.get
            val size = if r == null then "null" else s"${r.width}x${r.height}"
            val status = window.panorama.node.lookup("#panorama-status").asInstanceOf[Labeled].getText
            s"VIDEO_FRAME_DIAG active=${window.panorama.videoActive} mode=${window.panorama.model.video.get} seconds=${window.panorama.videoSeconds} size=$size status=$status"
          })
          throw e
        fx(window.panorama.model.setView(View(90, 10, 75)))
        eventually(s"panorama video projection $index") {
          val (source, view, actual) = fx((window.panorama.model.raster.get, window.panorama.model.view, window.panorama.viewport.lastPixels))
          if source == null || source.width != 512 || (source eq previous) || actual == null then false
          else
            val expected = Panorama.render(source, Projection.Equirectangular, view, actual.width, actual.height)
            meanDiff(expected, actual) < 2.0
        }
        fx {
          require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", out.resolve(s"real-video-$index.png").toFile))
          window.panorama.stopVideo()
        }
      }
      println("REAL_PANORAMA_VIDEO_OK")
    finally
      if window != null then fx { window.panorama.dispose(); window.stage.close() }
      services.shutdown()
      Platform.exit()

  private def meanDiff(a: Raster, b: Raster): Double =
    var total = 0L
    var i = 0
    while i < a.argb.length do
      val x = a.argb(i); val y = b.argb(i)
      total += math.abs(((x >> 16) & 255) - ((y >> 16) & 255))
      total += math.abs(((x >> 8) & 255) - ((y >> 8) & 255))
      total += math.abs((x & 255) - (y & 255))
      i += 1
    total.toDouble / (a.argb.length * 3)

  private def assertEqualsSpeed(actual: Double, expected: Double): Unit =
    require(math.abs(actual - expected) < 1e-9, s"speed $actual != $expected")
