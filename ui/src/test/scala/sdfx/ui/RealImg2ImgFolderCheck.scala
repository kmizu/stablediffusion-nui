package sdfx.ui

import java.nio.file.{Files, Paths, StandardCopyOption}
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.{CheckBox, TextField}
import javafx.stage.Stage
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

/** Native MainWindow acceptance using an actual checkpoint and two folder inputs. */
object RealImg2ImgFolderCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task)
    task.get(45, TimeUnit.SECONDS)
  private def waitFor(label: String, seconds: Int)(test: => Boolean): Unit =
    val until = System.nanoTime() + seconds.seconds.toNanos
    while !test && System.nanoTime() < until do Thread.sleep(100)
    require(test, s"Timeout: $label")
  def main(args: Array[String]): Unit =
    require(args.length == 4, "output-dir python checkpoint-file input-image")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val run = Files.createTempDirectory(output, "img2img-native-")
    val home = AppHome(Files.createDirectories(run.resolve("home")))
    val python = Paths.get(args(1)).toAbsolutePath.normalize()
    val checkpoint = Paths.get(args(2)).toAbsolutePath.normalize()
    val input = Paths.get(args(3)).toAbsolutePath.normalize()
    Seq(python, checkpoint, input).foreach(p => require(Files.isRegularFile(p), s"Missing file $p"))
    val folder = Files.createDirectories(run.resolve("input"))
    Seq("a.png", "b.png").foreach(n => Files.copy(input, folder.resolve(n), StandardCopyOption.COPY_ATTRIBUTES))
    val engineDir = Paths.get("python").toAbsolutePath.normalize()
    val settings = Settings(pythonExe = Some(python.toString), outputDir = home.root.resolve("outputs").toString,
      modelDirs = ModelDirs(checkpoints = Seq(checkpoint.getParent.toString)), hfOffline = true, previewEvery = 0)
    val launch = () => EngineLaunch(Seq(python.toString, "-m", "sdfx"), engineDir,
      Map("PYTHONPATH" -> engineDir.toString, "HF_HUB_OFFLINE" -> "1", "TRANSFORMERS_OFFLINE" -> "1",
        "OPENBLAS_NUM_THREADS" -> "1", "OMP_NUM_THREADS" -> "1", "USE_TF" -> "0"))
    val services = new AppServices(home, settings, dryRun = false, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(launch), persistSettings = false, timeouts = EngineTimeouts(load = 10.minutes),
      installShutdownHook = false)
    val events = new ConcurrentLinkedQueue[JobEvent]()
    services.queue.addListener(e => events.add(e))
    val started = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); started.countDown() })
    require(started.await(30, TimeUnit.SECONDS), "JavaFX startup timed out")
    var window: MainWindow = null
    try
      services.start()
      val loaded = services.loadCheckpoint(checkpoint.toString, remember = false)
      window = fx {
        val w = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        w.show(); w.select(Mode.Img2Img); w.stage.setWidth(1440); w.stage.setHeight(900)
        w.model.checkpoint.set(loaded.name)
        val vm = w.vms(Mode.Img2Img)
        vm.width.set(512); vm.height.set(512); vm.steps.set(4); vm.seed.set(2468)
        vm.prompt.set("anime portrait, smile")
        w.root.applyCss(); w.root.layout()
        val checkbox = w.scene.lookup("#img2img-folder-enabled").asInstanceOf[CheckBox]
        val field = w.scene.lookup("#img2img-folder-input").asInstanceOf[TextField]
        require(checkbox != null && field != null, "folder controls missing")
        checkbox.fire(); field.setText(folder.toString)
        require(w.panels(Mode.Img2Img).imgFolderEnabled.get && w.panels(Mode.Img2Img).imgFolderInput.get == folder.toString)
        val panel = w.panels(Mode.Img2Img)
        val y = checkbox.localToScene(checkbox.getBoundsInLocal).getMinY - panel.node.localToScene(panel.node.getBoundsInLocal).getMinY
        val range = panel.content.getBoundsInLocal.getHeight - panel.node.getViewportBounds.getHeight
        if range > 0 then panel.node.setVvalue(math.max(0, math.min(1, (y - 100) / range)))
        w.root.applyCss(); w.root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(w.scene.snapshot(null), null), "png", run.resolve("folder-controls.png").toFile))
        w.generate(false)
        w
      }
      waitFor("folder queued", 60) { events.asScala.exists(_.isInstanceOf[JobEvent.JobQueued]) }
      val job = events.asScala.collectFirst { case e: JobEvent.JobQueued => e.job }.get
      require(job.img2imgFolder.exists(_.path == folder.toString))
      waitFor("folder completed", 900) { events.asScala.exists(e => e.jobId == job.id &&
        (e.isInstanceOf[JobEvent.JobFinished] || e.isInstanceOf[JobEvent.JobFailed] || e.isInstanceOf[JobEvent.JobCancelled])) }
      val result = events.asScala.collectFirst { case e: JobEvent.JobFinished if e.jobId == job.id => e.output }
        .getOrElse(throw new IllegalStateException(s"Folder failed: ${events.asScala.filter(_.jobId == job.id).mkString("\n")}"))
      require(result.images.size == 2 && result.images.forall(i => Files.isRegularFile(i.path)))
      require(result.images.map(_.params.img2img.get.initImage).toSet == Seq("a.png", "b.png").map(n => folder.resolve(n).toRealPath().toString).toSet)
      waitFor("gallery updated", 60) { fx { result.images.forall(i => window.galleryVM.items.asScala.exists(_.path == i.path)) } }
      fx { window.root.applyCss(); window.root.layout(); require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", run.resolve("folder-results.png").toFile)) }
      println(s"IMG2IMG_FOLDER_GUI_OK artifacts=$run outputs=${result.images.map(_.path).mkString(",")}")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown()
      Platform.exit()
