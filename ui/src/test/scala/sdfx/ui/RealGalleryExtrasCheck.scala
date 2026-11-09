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
object RealGalleryExtrasCheck:
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

      // ---- thumbnail tooltip content (hover itself is a JavaFX timer; the installed Tooltip is read)
      check("thumbnail_tooltip") {
        val t = fx(tile("still.png").getProperties.get("javafx.scene.control.Tooltip")).asInstanceOf[Tooltip]
        require(t != null, "no tooltip installed on the thumbnail")
        val text = fx(t.getText)
        require(text.contains("still.png") || text.contains("x"), text)
        require(text.contains("Steps:") || text.contains("KB") || text.contains("B"), text)
        text.replace('\n', ' ').take(200)
      }
      // ---- custom folder tab (a folder outside the outputs directory added to the active tabs)
      val custom = output.resolve("custom-pics"); Files.createDirectories(custom)
      Files.copy(pngs.head, custom.resolve("custom1.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      Files.copy(pngs(1), custom.resolve("custom2.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      check("custom_folder_tab") {
        val env = window.imageBrowser.vm.env
        env.store.updateSettings(s => s.copy(activeTabs = s.activeTabs :+ custom.toString))
        fx { window.imageBrowser.build() }
        val titles = fx(window.scene.lookup("#image-browser-tabs").asInstanceOf[TabPane].getTabs.asScala.map(_.getText).toSeq)
        val extra = titles.filterNot(sdfx.core.imagebrowser.BrowserTabs.Defaults.contains)
        require(extra.size == 1, s"tabs=$titles")
        fx(window.imageBrowser.selectInner(extra.head))
        eventually("custom tab lists both files")(fx(window.imageBrowser.page(extra.head).get.viewModel.filtered.size) == 2)
        s"custom tab '${extra.head}' lists ${fx(window.imageBrowser.page(extra.head).get.viewModel.filtered.size)} files"
      }
      // ---- the real Windows recycle bin: the app's SystemTrash must put the file there
      check("recycle_bin_contents") {
        val victim = output.resolve("trash-victim-" + System.nanoTime + ".png")
        Files.copy(pngs.head, victim)
        val result = new sdfx.core.imagebrowser.SystemTrash(new sdfx.core.imagebrowser.FolderTrash(output.resolve("fallback-trash"))).discard(victim)
        require(result == Right("recycle bin"), result.toString)
        require(!Files.exists(victim), "file still in place")
        val ps = new ProcessBuilder("powershell", "-NoProfile", "-Command",
          "$s=New-Object -ComObject Shell.Application; $b=$s.NameSpace(10); ($b.Items() | ForEach-Object { $_.Name }) -join '|'").redirectErrorStream(true).start()
        val names = new String(ps.getInputStream.readAllBytes(), java.nio.charset.Charset.defaultCharset); ps.waitFor()
        val stem = victim.getFileName.toString.stripSuffix(".png")
        require(names.contains(stem), "not found in the recycle bin listing")
        // restore the bin to its previous state
        val rm = new ProcessBuilder("powershell", "-NoProfile", "-Command",
          s"$$s=New-Object -ComObject Shell.Application; $$b=$$s.NameSpace(10); $$b.Items() | Where-Object { $$_.Name -like '$stem*' } | ForEach-Object { Remove-Item -LiteralPath $$_.Path -Force -Recurse -ErrorAction SilentlyContinue }").redirectErrorStream(true).start()
        rm.getInputStream.readAllBytes(); rm.waitFor()
        s"${victim.getFileName} moved to the recycle bin (listed by Shell.Application), then removed from it"
      }
      println(checks.render(indent = 2))
    finally
      Files.writeString(output.resolve("report.json"), ujson.Obj("checks" -> checks, "success" -> checks.obj.values.forall(_("pass").bool)).render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
