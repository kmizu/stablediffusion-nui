package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.{Node, Parent}
import javafx.scene.control.{ComboBox, ScrollPane, TitledPane}
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.engine.AppServices
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*

/** Inspect the real script form without showing a window or touching desktop input. */
object WorkflowQuickMaskOffscreenCheck:
  private def fx[A](f: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = f })
    Platform.runLater(task); task.get(30, TimeUnit.SECONDS)
  private def nodes(n: Node): Seq[Node] = Seq(n) ++ (n match
    case p: TitledPane => Option(p.getContent).toSeq.flatMap(nodes)
    case p: ScrollPane => Option(p.getContent).toSeq.flatMap(nodes)
    case p: Parent => p.getChildrenUnmodifiable.asScala.toSeq.flatMap(nodes)
    case _ => Nil)
  def main(args: Array[String]): Unit =
    require(args.length == 1, "output directory")
    val output = Path.of(args(0)).toAbsolutePath; Files.createDirectories(output)
    val home = AppHome(Files.createTempDirectory(output, "home-"))
    val services = new AppServices(home, Settings(), dryRun = true, builtins = StandardPlugins.withOwnAxes,
      persistSettings = false, installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions())
        require(!window.stage.isShowing)
        window.select(Mode.Img2Img)
        window.plugins(Mode.Img2Img).selectedScript.set("img2img_workflow")
        val panel = window.panels(Mode.Img2Img).node
        val quick = nodes(panel).collectFirst { case t: TitledPane if t.getText == "Quick Mask" => t }.get
        quick.setExpanded(true)
        val mask = nodes(quick).collectFirst {
          case c: ComboBox[?] if c.getItems.asScala.map(_.toString).contains("White") => c.asInstanceOf[ComboBox[String]]
        }.get
        mask.setValue("White")
        val form = window.plugins(Mode.Img2Img).form("img2img_workflow").get
        require(form.values.str("quick_mask") == "White")
        require(!nodes(quick).exists { case c: ComboBox[?] => c.getItems.asScala.map(_.toString).contains("Phase 1"); case _ => false },
          "phase selector must not be nested in Quick Mask")
        window.root.resize(1440, 900); window.root.applyCss(); window.root.layout()
        val snapshot = SwingFXUtils.fromFXImage(window.scene.snapshot(null), null)
        require(ImageIO.write(snapshot, "png", output.resolve("workflow-quickmask-offscreen.png").toFile))
        quick.applyCss(); quick.layout()
        val snapshotParams = new javafx.scene.SnapshotParameters()
        snapshotParams.setFill(javafx.scene.paint.Color.web("#19191b"))
        val quickImage = SwingFXUtils.fromFXImage(quick.snapshot(snapshotParams, null), null)
        require(quickImage.getWidth > 50 && quickImage.getHeight > 50)
        require(ImageIO.write(quickImage, "png", output.resolve("workflow-quickmask-controls.png").toFile))
        println("WORKFLOW_QUICKMASK_OFFSCREEN_OK")
      }
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
