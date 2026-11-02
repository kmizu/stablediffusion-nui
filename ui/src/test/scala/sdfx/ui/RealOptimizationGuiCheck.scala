package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.control.{Button, ButtonType, CheckBox, DialogPane}
import javafx.scene.layout.StackPane
import javafx.stage.{Stage, Window}
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.OutputImage
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.view.SettingsDialog
import sdfx.ui.vm.{Mode, UiState}
import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Manual real-model acceptance using actual JavaFX controls, with programmatic fire(), not desktop mouse input. */
object RealOptimizationGuiCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)

  def main(args: Array[String]): Unit =
    require(args.length == 5 || (args.length == 6 && Set("--settings-only", "--reject-check").contains(args(5))), "python checkpoint output width height [--settings-only|--reject-check]")
    val rejectCheck = args.length == 6 && args(5) == "--reject-check"
    val python = Paths.get(args(0)).toAbsolutePath.normalize()
    val checkpoint = Paths.get(args(1)).toAbsolutePath.normalize()
    val output = Paths.get(args(2)).toAbsolutePath.normalize()
    val width = args(3).toInt
    val height = args(4).toInt
    require(Files.isRegularFile(python) && Files.isRegularFile(checkpoint))
    Files.createDirectories(output)
    val home = AppHome(Files.createTempDirectory(output, "gui-opt-home-"))
    val engineDir = Paths.get("python").toAbsolutePath.normalize()
    val init = Settings(pythonExe = Some(python.toString), outputDir = output.resolve("images").toString,
      modelDirs = ModelDirs(checkpoints = Seq(checkpoint.getParent.toString)), dtype = "fp16", vramMode = "gpu",
      fp8Storage = false, vaeAuto = true, vaeSlicing = false, previewEvery = 0, hfOffline = true)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    val dialogError = new AtomicReference[Throwable]()
    val edited = fx {
      val owner = new Stage()
      owner.setScene(new Scene(new StackPane(), 600, 400)); owner.show()
      Platform.runLater(() => {
        try
          val pane = Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene)).map(_.getRoot)
            .collectFirst { case p: DialogPane => p }.getOrElse(throw new IllegalStateException("Settings dialog missing"))
          val boxes = pane.lookupAll(".check-box").asScala.collect { case c: CheckBox => c }.toSeq
          def choose(prefix: String, wanted: Boolean): Unit =
            val c = boxes.find(_.getText.startsWith(prefix)).getOrElse(throw new IllegalStateException("Missing checkbox: " + prefix))
            if c.isSelected != wanted then c.fire()
            require(c.isSelected == wanted, "Could not set " + prefix)
          choose("fp8 U-Net weights", true)
          choose("VAE slicing", true)
          choose("Automatic VAE slicing", true)
          pane.applyCss(); pane.layout()
          require(ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png", output.resolve("settings.png").toFile))
          pane.lookupButton(ButtonType.OK).asInstanceOf[Button].fire()
        catch
          case t: Throwable =>
            dialogError.set(t)
            Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene)).map(_.getRoot)
              .collectFirst { case p: DialogPane => p }.foreach(_.lookupButton(ButtonType.CANCEL).asInstanceOf[Button].fire())
      })
      val result = new SettingsDialog(owner, init, 2).showAndWait()
      owner.close()
      if dialogError.get() != null then throw dialogError.get()
      result.getOrElse(throw new IllegalStateException("Settings cancelled"))
    }
    require(edited.fp8Storage && edited.vaeSlicing && edited.vaeAuto && edited.vramMode == "gpu")
    if args.length == 6 && args(5) == "--settings-only" then
      println("GUI_OPT_SETTINGS_SUCCESS fp8=true slicing=true auto=true mode=gpu")
      Platform.exit()
      return
    val services = new AppServices(home, edited, dryRun = false, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(python.toString, "-m", "sdfx"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "HF_HUB_OFFLINE" -> "1", "TRANSFORMERS_OFFLINE" -> "1", "USE_TF" -> "0") ++
          (if rejectCheck then Map("SDFX_VRAM_BUDGET_MB" -> "1") else Map.empty))),
      persistSettings = false, timeouts = EngineTimeouts(load = 10.minutes), installShutdownHook = false)
    val finished = new LinkedBlockingQueue[Either[String, OutputImage]]()
    services.queue.addListener {
      case event: JobEvent.JobFinished => finished.offer(event.output.images.headOption.toRight("no output image")); ()
      case event: JobEvent.JobFailed => finished.offer(Left(event.message)); ()
      case _ => ()
    }
    var window: MainWindow = null
    try
      services.start()
      val loaded = services.loadCheckpoint(checkpoint.toString, remember = false)
      window = fx {
        val params = GenerationParams(prompt = "a blue ceramic teapot on a wooden table, soft daylight", negativePrompt = "blurry",
          seed = 173, steps = 20, width = width, height = height, samplerName = "Euler")
        val w = new MainWindow(new Stage(), services, UiState(params = Map("txt2img" -> params)), CliOptions())
        w.stage.setWidth(1280); w.stage.setHeight(950); w.show(); w.model.checkpoint.set(loaded.name)
        w.plugins(Mode.Txt2Img).form("model_keyword").flatMap(_.prop("enabled")).foreach(_.set("false"))
        w
      }
      def generate(): OutputImage =
        fx(window.statusBar.generateBtn.fire())
        val result = finished.poll(600, TimeUnit.SECONDS)
        require(result != null, "generation timed out")
        result.fold(error => throw new IllegalStateException(error), identity)
      val baseline = generate()
      fx {
        val enabled = window.panels(Mode.Txt2Img).sections("txt2img.plugin.deepcache").getGraphic.asInstanceOf[CheckBox]
        require(enabled != null, "DeepCache checkbox missing")
        if !enabled.isSelected then enabled.fire()
        require(window.plugins(Mode.Txt2Img).form("deepcache").flatMap(_.prop("enabled")).exists(_.get == "true"), "txt2img DeepCache form did not update")
      }
      val cached = generate()
      require(cached.infotext.contains("DeepCache interval"), "DeepCache metadata missing")
      fx {
        val enabled = window.panels(Mode.Txt2Img).sections("txt2img.plugin.deepcache").getGraphic.asInstanceOf[CheckBox]
        enabled.fire()
        require(!enabled.isSelected)
      }
      val restored = generate()
      val a = ImageIO.read(baseline.path.toFile)
      val b = ImageIO.read(restored.path.toFile)
      require(a.getWidth == b.getWidth && a.getHeight == b.getHeight)
      val aa = a.getRGB(0, 0, a.getWidth, a.getHeight, null, 0, a.getWidth)
      val bb = b.getRGB(0, 0, b.getWidth, b.getHeight, null, 0, b.getWidth)
      require(java.util.Arrays.equals(aa, bb), "baseline not restored after disabling DeepCache")
      if rejectCheck then
        val current = services.supervisor.loadedModel.getOrElse(throw new IllegalStateException("loaded model missing"))
        services.updateSettings(_.copy(vramMode = "auto"))
        val error = try
          services.loadCheckpoint(checkpoint.toString, remember = false)
          throw new IllegalStateException("auto + fp8 unexpectedly succeeded under the test planner budget")
        catch
          case e: sdfx.core.protocol.EngineError => e
        require(error.message.contains("fp8_storage needs the 'gpu' VRAM mode"), "unexpected rejection: " + error.message)
        require(services.supervisor.loadedModel.contains(current), "supervisor lost existing model after refusal")
        services.updateSettings(_.copy(vramMode = "gpu"))
        val recovered = generate()
        val image = ImageIO.read(recovered.path.toFile)
        val pixels = image.getRGB(0, 0, image.getWidth, image.getHeight, null, 0, image.getWidth)
        require(java.util.Arrays.equals(aa, pixels), "existing model did not recover after rejected fp8 auto load")
        println(s"GUI_OPT_REJECTION_SUCCESS simulated_planner_budget_mb=1 recovered=${recovered.path}")
      println(s"GUI_OPT_SUCCESS fp8=${edited.fp8Storage} slicing=${edited.vaeSlicing} auto=${edited.vaeAuto} baseline=${baseline.path} cached=${cached.path} restored=${restored.path}")
    finally
      if window != null then fx(window.stage.close())
      services.shutdown()
      Platform.exit()
