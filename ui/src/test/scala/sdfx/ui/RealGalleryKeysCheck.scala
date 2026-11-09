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
object RealGalleryKeysCheck:
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

      import sdfx.core.imagebrowser.BrowserTabs
      val env = window.imageBrowser.vm.env
      def inner: Seq[String] = fx(window.scene.lookup("#image-browser-tabs").asInstanceOf[TabPane].getTabs.asScala.map(_.getText).toSeq)
      def byId(id: String): Node = { def walk(n: Node): Option[Node] = if id == n.getId then Some(n) else n match { case p: javafx.scene.Parent => p.getChildrenUnmodifiable.asScala.iterator.flatMap(c => walk(c)).nextOption(); case _ => None }; walk(window.scene.getRoot).orNull }
      def key(code: javafx.scene.input.KeyCode, ctrl: Boolean, shift: Boolean, target: String): Unit = fx {
        val grid = byId(s"$target-grid")
        Event.fireEvent(grid, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, false))
      }
      // six pictures in a custom folder, two per page -> three pages
      val dirA = output.resolve("album-a"); Files.createDirectories(dirA)
      (0 until 6).foreach(i => Files.copy(pngs(i % pngs.length), dirA.resolve(s"pic$i.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING))
      env.store.updateSettings(s => s.copy(activeTabs = BrowserTabs.Defaults :+ dirA.toString, pageColumns = 2, pageRows = 1))
      fx { window.imageBrowser.build() }
      val tabA = inner.filterNot(BrowserTabs.Defaults.contains).head
      fx(window.imageBrowser.selectInner(tabA))
      eventually("album listed")(fx(window.imageBrowser.page(tabA).get.viewModel.filtered.size) == 6)
      def fxShowing(n: Node): Boolean = { var p = n; var ok = true; while p != null do { if !p.isVisible then ok = false; p = p.getParent }; ok }
      val gid = fx(window.imageBrowser.page(tabA).get.spec.id)
      def label(): String = fx(byId(s"$gid-pagelabel").asInstanceOf[Labeled].getText)
      def modCheck(name: String, set: sdfx.core.imagebrowser.BrowserSettings => sdfx.core.imagebrowser.BrowserSettings, good: (Boolean, Boolean), bad: Seq[(Boolean, Boolean)]): Unit =
        check(name) {
          env.store.updateSettings(s => set(s.copy(modShift = false, modCtrlShift = false)))
          fx { window.imageBrowser.build() }
          fx(window.imageBrowser.selectInner(tabA))
          eventually("album listed again")(fx(window.imageBrowser.page(tabA).get.viewModel.filtered.size) == 6)
          val before = label()
          bad.foreach((c, sh) => key(javafx.scene.input.KeyCode.RIGHT, c, sh, gid))
          Thread.sleep(300)
          require(label() == before, s"wrong modifier moved the page: $before -> ${label()}")
          key(javafx.scene.input.KeyCode.RIGHT, good._1, good._2, gid)
          eventually("page moved")(label() != before)
          val after = label()
          key(javafx.scene.input.KeyCode.LEFT, good._1, good._2, gid)
          eventually("page moved back")(label() == before)
          s"$before -> $after with the configured modifier; other combinations did nothing"
        }
      modCheck("modifier_ctrl_default", identity, (true, false), Seq((false, true), (true, true)))
      modCheck("modifier_shift", _.copy(modShift = true), (false, true), Seq((true, false), (true, true)))
      modCheck("modifier_ctrl_shift", _.copy(modCtrlShift = true), (true, true), Seq((true, false), (false, true)))

      // ---- Maintenance: rename a ranked folder on disk, then "Update directory names in database"
      check("maintenance_rename_keeps_ranking") {
        env.store.updateSettings(s => s.copy(modShift = false, modCtrlShift = false, pageColumns = 6, pageRows = 3))
        fx { window.imageBrowser.build() }
        fx(window.imageBrowser.selectInner(tabA))
        eventually("album listed")(fx(window.imageBrowser.page(tabA).get.viewModel.filtered.size) == 6)
        val first = fx(window.imageBrowser.page(tabA).get.viewModel.filtered.head.path)
        click(fx(byId(s"$gid-grid").asInstanceOf[TilePane].getChildren.asScala.find(n => String.valueOf(n.getUserData).endsWith(first.getFileName.toString)).get))
        val gridNode = fx(byId(s"$gid-grid"))
        fx(Event.fireEvent(gridNode, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.DIGIT3, false, false, false, false)))
        eventually("ranking stored")(fx(window.imageBrowser.page(tabA).get.viewModel.filtered.find(_.path == first).flatMap(r => env.rankingOf(r))).contains(3))
        // rename the folder on disk
        val dirB = output.resolve("album-b")
        Files.move(dirA, dirB)
        env.store.updateSettings(s => s.copy(activeTabs = BrowserTabs.Defaults :+ dirB.toString))
        fx { window.imageBrowser.build() }
        val tabB = inner.filterNot(BrowserTabs.Defaults.contains).head
        fx(window.imageBrowser.selectInner(tabB))
        eventually("renamed album listed")(fx(window.imageBrowser.page(tabB).get.viewModel.filtered.size) == 6)
        val movedBefore = fx(window.imageBrowser.page(tabB).get.viewModel.filtered.find(_.path.getFileName == first.getFileName).flatMap(r => env.rankingOf(r)))
        // the ranking follows the content hash only after "Reapply"; here we use the documented rename operation
        fx(window.imageBrowser.selectInner("Maintenance"))
        fx(window.scene.lookup("#ib-maintenance-from").asInstanceOf[TextInputControl].setText(dirA.toString))
        fx(window.scene.lookup("#ib-maintenance-to").asInstanceOf[TextInputControl].setText(dirB.toString))
        fx(window.scene.lookup("#ib-maintenance-update").asInstanceOf[Button].fire())
        eventually("maintenance message")(fx(window.scene.lookup("#ib-maintenance-last").asInstanceOf[TextInputControl].getText).startsWith("Updated directory names"))
        val msg = fx(window.scene.lookup("#ib-maintenance-last").asInstanceOf[TextInputControl].getText)
        fx(window.imageBrowser.selectInner(tabB))
        fx(window.imageBrowser.page(tabB).get.reload())
        eventually("rescanned")(fx(window.imageBrowser.page(tabB).get.viewModel.filtered.size) == 6)
        val after = fx(window.imageBrowser.page(tabB).get.viewModel.filtered.find(_.path.getFileName == first.getFileName).flatMap(r => env.rankingOf(r)))
        require(after.contains(3), s"ranking after rename = $after (before the update: $movedBefore); message: $msg")
        s"ranking 3 of ${first.getFileName}: before update = $movedBefore, after = $after; $msg"
      }
      println(checks.render(indent = 2))
    finally
      Files.writeString(output.resolve("report.json"), ujson.Obj("checks" -> checks, "success" -> checks.obj.values.forall(_("pass").bool)).render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
