package sdfx.ui

import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, LinkedBlockingQueue, TimeUnit}
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.{CheckBox, ComboBox, TextField, TitledPane}
import javafx.stage.Stage
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.infotext.Infotext
import sdfx.core.png.PngMetadata
import sdfx.core.plugins.api.OutputImage
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

/** Real native UI acceptance. The caller owns the GPU lock. */
object RealFaceCheckpointCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task)
    task.get(45, TimeUnit.SECONDS)
  def main(args: Array[String]): Unit =
    require(args.length == 5, "output-dir python main-checkpoint face-checkpoint input-image")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val run = Files.createTempDirectory(output, "face-checkpoint-gui-")
    val home = AppHome(Files.createDirectories(run.resolve("home")))
    val python = Paths.get(args(1)).toAbsolutePath.normalize()
    val main = Paths.get(args(2)).toAbsolutePath.normalize()
    val face = Paths.get(args(3)).toAbsolutePath.normalize()
    val input = Paths.get(args(4)).toAbsolutePath.normalize()
    Seq(python, main, face, input).foreach(p => require(Files.isRegularFile(p), s"Missing $p"))
    val engineDir = Paths.get("python").toAbsolutePath.normalize()
    val settings = Settings(pythonExe = Some(python.toString), outputDir = home.root.resolve("outputs").toString,
      modelDirs = ModelDirs(checkpoints = Seq(main.getParent.toString, face.getParent.toString).distinct),
      vramMode = "model_offload", previewEvery = 0)
    val launch = () => EngineLaunch(Seq(python.toString, "-m", "sdfx"), engineDir,
      Map("PYTHONPATH" -> engineDir.toString, "OPENBLAS_NUM_THREADS" -> "1", "OMP_NUM_THREADS" -> "1", "USE_TF" -> "0"))
    val services = new AppServices(home, settings, dryRun = false, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(launch), persistSettings = false, timeouts = EngineTimeouts(load = 10.minutes), installShutdownHook = false)
    val done = new LinkedBlockingQueue[Either[String, OutputImage]]()
    services.queue.addListener {
      case e: JobEvent.JobFinished => done.offer(e.output.images.headOption.toRight("no image")); ()
      case e: JobEvent.JobFailed => done.offer(Left(e.message)); ()
      case _ => ()
    }
    val started = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); started.countDown() })
    require(started.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start()
      val loaded = services.loadCheckpoint(main.toString, remember = false)
      Await.result(services.catalog.refreshAsync(), 3.minutes)
      val faceName = services.catalog.checkpoints.find(m => Paths.get(m.path).toRealPath() == face.toRealPath()).get.name
      window = fx {
        val w = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        w.show(); w.select(Mode.Img2Img); w.stage.setWidth(1440); w.stage.setHeight(900)
        w.model.checkpoint.set(loaded.name)
        w.plugins(Mode.Img2Img).form("model_keyword").flatMap(_.prop("enabled")).foreach(_.set("false"))
        val vm = w.vms(Mode.Img2Img)
        vm.initImage.set(input.toString); vm.width.set(512); vm.height.set(512); vm.steps.set(12); vm.seed.set(2468)
        vm.prompt.set("anime portrait, looking at viewer")
        vm.denoise.set(0.05)
        w.root.applyCss(); w.root.layout()
        val panel = w.panels(Mode.Img2Img)
        val section = panel.node.lookup("#plugin-batch_face_swap-section").asInstanceOf[TitledPane]
        require(section != null, "Batch Face Swap section missing")
        section.setExpanded(true)
        w.root.applyCss(); w.root.layout()
        val checkbox = section.lookup("#plugin-batch_face_swap-enabled").asInstanceOf[CheckBox]
        require(checkbox != null, "Batch Face Swap enable control missing")
        if !checkbox.isSelected then checkbox.fire()
        val overrides = section.lookupAll(".titled-pane").asScala.collectFirst {
          case p: TitledPane if p.getText == "Overrides for the face pass" => p
        }.get
        overrides.setExpanded(true)
        w.root.applyCss(); w.root.layout()
        val select = section.lookup("#plugin-batch_face_swap-checkpoint").asInstanceOf[ComboBox[String]]
        require(select != null && select.getItems.asScala.contains(faceName), s"Face checkpoint choice missing: $faceName")
        select.getSelectionModel.select(faceName)
        val form = w.plugins(Mode.Img2Img).form("batch_face_swap").get
        require(form.values.str("checkpoint") == faceName && form.isActive,
          s"wrong form values: selected=${select.getValue} checkpoint=${form.values.str("checkpoint")} enabled=${form.isActive}")
        form.prop("resolution").get.set("512")
        form.prop("denoising_strength").get.set("0.65")
        val y = select.localToScene(select.getBoundsInLocal).getMinY - panel.node.localToScene(panel.node.getBoundsInLocal).getMinY
        val range = panel.content.getBoundsInLocal.getHeight - panel.node.getViewportBounds.getHeight
        if range > 0 then panel.node.setVvalue(math.max(0, math.min(1, (y - 150) / range)))
        w.root.applyCss(); w.root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(w.scene.snapshot(null), null), "png", run.resolve("checkpoint-controls.png").toFile))
        w.generate(false)
        w
      }
      val result = Option(done.poll(900, TimeUnit.SECONDS)).getOrElse(throw new IllegalStateException("generation timed out"))
        .fold(message => throw new IllegalStateException(message), identity)
      val fields = Infotext.parse(PngMetadata.readParameters(result.path).get).fields
      require(fields.get("Face checkpoint hash").exists(_.nonEmpty), s"face checkpoint hash missing: $fields")
      require(fields.get("Batch Face Swap").exists(_.contains(faceName)), s"face checkpoint name missing: $fields")
      val deadline = System.nanoTime() + 60.seconds.toNanos
      def imageShown = fx(window.preview.currentPath.contains(result.path) && window.preview.zoom.image != null)
      while !imageShown && System.nanoTime() < deadline do Thread.sleep(100)
      require(imageShown, "generated image did not finish loading in the preview")
      fx { window.root.applyCss(); window.root.layout(); ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", run.resolve("checkpoint-result.png").toFile) }
      fx {
        val section = window.panels(Mode.Img2Img).node.lookup("#plugin-batch_face_swap-section").asInstanceOf[TitledPane]
        val hash = section.lookup("#plugin-batch_face_swap-checkpoint_hash").asInstanceOf[TextField]
        val select = section.lookup("#plugin-batch_face_swap-checkpoint").asInstanceOf[ComboBox[String]]
        require(hash != null && select != null, "checkpoint hash controls missing")
        hash.setText(fields("Face checkpoint hash"))
        select.getSelectionModel.select(loaded.name)
        require(window.plugins(Mode.Img2Img).form("batch_face_swap").get.values.str("checkpoint_hash") == fields("Face checkpoint hash"))
        window.root.applyCss(); window.root.layout()
        ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", run.resolve("checkpoint-stale-hash.png").toFile)
        window.generate(false)
      }
      val stale = Option(done.poll(900, TimeUnit.SECONDS)).getOrElse(throw new IllegalStateException("stale hash test timed out"))
      require(stale.isLeft && stale.left.toOption.exists(_.contains("checkpoint hash")), s"stale hash was not rejected: $stale")
      println(s"FACE_CHECKPOINT_GUI_OK artifacts=$run output=${result.path}")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown()
      Platform.exit()
