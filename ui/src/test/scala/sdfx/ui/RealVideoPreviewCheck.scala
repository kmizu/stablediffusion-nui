package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.input.{MouseButton, MouseEvent}
import javafx.scene.layout.TilePane
import javafx.scene.media.MediaView
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts}
import sdfx.plugins.StandardPlugins

import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.FileTime
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** MANUAL gate. Args: python-exe output-directory source-png... (at least two real generated PNGs).
  * Builds a real H.264 mp4 with ffmpeg from the PNGs, mounts the real MainWindow (dry-run engine) and checks that selecting the video in the
  * Image Browser shows the in-app preview (first frame, play advances the clock) and that an undecodable "video" shows the fallback message. */
object RealVideoPreviewCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(40, TimeUnit.SECONDS)
  private def eventually(label: String, seconds: Int = 60)(body: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(40)
    require(body, "Timed out: " + label)

  def main(args: Array[String]): Unit =
    require(args.length >= 4, "python-exe output-directory png png...")
    val output = Paths.get(args(1)).toAbsolutePath.normalize(); Files.createDirectories(output)
    val pngs = args.drop(2).map(Paths.get(_))
    val outputs = output.resolve("outputs"); val day = outputs.resolve("txt2img/2026-11-02"); Files.createDirectories(day)
    // frames -> mp4 (H.264, yuv420p, even size)
    val frames = output.resolve("frames"); Files.createDirectories(frames)
    pngs.zipWithIndex.foreach((p, i) => Files.copy(p, frames.resolve(f"f$i%03d.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING))
    val good = day.resolve("good.mp4")
    val ff = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-framerate", "2", "-i", frames.resolve("f%03d.png").toString,
      "-vf", "scale=512:512,format=yuv420p", "-c:v", "libx264", "-pix_fmt", "yuv420p", good.toString).inheritIO().start()
    require(ff.waitFor() == 0 && Files.size(good) > 1000, "ffmpeg failed")
    val bad = day.resolve("bad.mp4"); Files.write(bad, Array.fill[Byte](2048)(0))
    Files.copy(pngs.head, day.resolve("still.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    Seq(good, bad).foreach(p => Files.setLastModifiedTime(p, FileTime.fromMillis(1_700_000_001_000L)))

    val home = AppHome(Files.createTempDirectory(output, "app-home-"))
    val engineDir = AppHome.detect().pythonDir
    val services = new AppServices(home, Settings(outputDir = outputs.toString, previewEvery = 0), dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(Paths.get(args(0)).toAbsolutePath.toString, "-m", "sdfx", "--dry-run"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))),
      persistSettings = false, timeouts = EngineTimeouts(load = 2.minutes), installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    val checks = ujson.Obj()
    def check(name: String)(body: => String): Unit =
      try checks(name) = ujson.Obj("pass" -> true, "detail" -> body)
      catch case t: Throwable => { checks(name) = ujson.Obj("pass" -> false, "detail" -> String.valueOf(t)); println(s"CHECK FAILED $name: $t") }
    def snapshot(name: String): Unit = fx {
      window.root.applyCss(); window.root.layout()
      ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(name).toFile)
    }
    def click(n: Node, count: Int = 1): Unit = fx {
      Event.fireEvent(n, new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5, MouseButton.PRIMARY, count, false, false, false, false, true, false, false, true, false, false, null))
    }
    def tile(suffix: String): Node = fx {
      window.scene.lookup("#ib-txt2img-grid").asInstanceOf[TilePane].getChildren.asScala.find(n => String.valueOf(n.getUserData).endsWith(suffix)).orNull
    }
    def pane: Node = fx(window.scene.lookup("#ib-txt2img-video-pane"))
    def note: String = fx(window.scene.lookup("#ib-txt2img-video-note").asInstanceOf[Label].getText)
    def visible(n: Node): Boolean = fx(n != null && n.isVisible && n.isManaged)
    try
      window = fx { val w = new MainWindow(new Stage(), services, sdfx.ui.vm.UiState(), CliOptions()); w.stage.setWidth(1700); w.stage.setHeight(1000); w.show(); w }
      services.start()
      fx { window.imageBrowser.build() }
      fx {
        val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
        tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "image-browser-tab").get)
      }
      eventually("tiles listed")(fx(window.imageBrowser.page("txt2img").get.viewModel.filtered.size) == 3)
      eventually("tile nodes")(tile("good.mp4") != null && tile("bad.mp4") != null && tile("still.png") != null)

      click(tile("good.mp4"))
      check("video_preview_shown") {
        eventually("video pane visible")(visible(pane))
        Thread.sleep(3000); println("DEBUG0 note=" + note); eventually("media ready", 30)(note.contains(" s, 512x512"))
        val mv = fx(window.scene.lookup("#ib-txt2img-video").asInstanceOf[MediaView])
        require(fx(mv.getMediaPlayer) != null, "no media player")
        require(!visible(fx(window.scene.lookup("#ib-txt2img-preview")).asInstanceOf[Node]), "image preview should be hidden")
        snapshot("1-video-first-frame.png"); note
      }
      check("video_play_advances") {
        val p = fx(window.scene.lookup("#ib-txt2img-video").asInstanceOf[MediaView].getMediaPlayer)
        val t0 = fx(p.getCurrentTime.toMillis)
        fx(window.scene.lookup("#ib-txt2img-video-play").asInstanceOf[Button].fire())
        Thread.sleep(1500)
        println("DEBUG anc=" + fx { var n: javafx.scene.Node = window.scene.lookup("#ib-txt2img-video-play"); val b = new StringBuilder; while n != null do { if n.disableProperty.get then b.append(n.getClass.getSimpleName + "#" + n.getId + " "); n = n.getParent }; b.toString }); println(s"DEBUG note=${note} status=${fx(p.getStatus)} time=${fx(p.getCurrentTime.toMillis)} t0=$t0 disabled=${fx(window.scene.lookup("#ib-txt2img-video-play").isDisabled)} btn=${fx(window.scene.lookup("#ib-txt2img-video-play").asInstanceOf[Button].getText)}")
        eventually("playing", 15)(fx(p.getCurrentTime.toMillis) > t0 + 300)
        snapshot("2-video-playing.png")
        fx(window.scene.lookup("#ib-txt2img-video-play").asInstanceOf[Button].fire())
        s"time moved from ${t0.toInt} ms to ${fx(p.getCurrentTime.toMillis).toInt} ms; button=" + fx(window.scene.lookup("#ib-txt2img-video-play").asInstanceOf[Button].getText)
      }
      click(tile("still.png"))
      check("image_hides_video_pane") {
        eventually("pane hidden")(!visible(pane))
        eventually("image preview visible")(visible(fx(window.scene.lookup("#ib-txt2img-preview"))))
        "still image selected: video pane hidden, image preview shown"
      }
      click(tile("bad.mp4"))
      check("undecodable_video_falls_back") {
        eventually("fallback note", 30)(note.contains("cannot be played in the app"))
        snapshot("3-video-fallback.png"); note
      }
      println(checks.render(indent = 2))
    finally
      Files.writeString(output.resolve("report.json"), ujson.Obj("checks" -> checks, "success" -> checks.obj.values.forall(_("pass").bool)).render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
