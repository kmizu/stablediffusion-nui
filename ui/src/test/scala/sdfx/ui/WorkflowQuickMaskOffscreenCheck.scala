package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.{Node, Parent}
import javafx.scene.control.{ComboBox, ScrollPane, TitledPane}
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.engine.{AppServices, EngineLaunch, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, CountDownLatch, FutureTask, TimeUnit}
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
    require(args.length == 2, "output directory and Python executable")
    val output = Path.of(args(0)).toAbsolutePath; Files.createDirectories(output)
    val home = AppHome(Files.createTempDirectory(output, "home-"))
    val python = Path.of(args(1)).toAbsolutePath
    val engineDir = Path.of("python").toAbsolutePath
    val services = new AppServices(home, Settings(), dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(python.toString, "-m", "sdfx", "--dry-run"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))), persistSettings = false, installShutdownHook = false)
    val events = new ConcurrentLinkedQueue[JobEvent]()
    services.queue.addListener(e => { events.add(e); () })
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start()
      val source = output.resolve("white-input.png")
      val image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
      val graphics = image.createGraphics()
      try { graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 64, 64) }
      finally graphics.dispose()
      require(ImageIO.write(image, "png", source.toFile))
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions())
        require(!window.stage.isShowing)
        window.select(Mode.Img2Img)
        window.plugins(Mode.Img2Img).selectedScript.set("img2img_workflow")
        window.plugins(Mode.Img2Img).form("model_keyword").flatMap(_.prop("enabled")).foreach(_.set("false"))
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
        val vm = window.vms(Mode.Img2Img)
        vm.initImage.set(source.toString); vm.width.set(64); vm.height.set(64); vm.steps.set(1); vm.seed.set(5)
        vm.prompt.set("offscreen quick mask test")
        window.generate(false)
      }
      val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
      while !events.asScala.exists(_.isInstanceOf[JobEvent.JobFinished]) &&
          !events.asScala.exists(_.isInstanceOf[JobEvent.JobFailed]) && System.nanoTime() < end do Thread.sleep(50)
      val queued = events.asScala.collectFirst { case e: JobEvent.JobQueued => e.job }.getOrElse(sys.error("job not queued"))
      require(queued.script.exists((plugin, values) => plugin.id == "img2img_workflow" && values.str("quick_mask") == "White"))
      val finished = events.asScala.collectFirst { case e: JobEvent.JobFinished => e.output }.getOrElse(sys.error("generation failed: " + events.asScala.mkString(", ")))
      require(finished.images.nonEmpty && finished.images.head.params.extra.contains("Img2Img Workflow mask"))
      println("WORKFLOW_QUICKMASK_OFFSCREEN_OK")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
