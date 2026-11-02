package sdfx.ui

import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, LinkedBlockingQueue, TimeUnit}
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.{CheckBox, ComboBox}
import javafx.stage.Stage
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.plugins.api.OutputImage
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

/** Native controls -> Generate -> MediaPipe and real model, with test-only isolated dependency paths. */
object RealMediaPipeFaceCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)

  def main(args: Array[String]): Unit =
    require(args.length == 6, "output python checkpoint family mediapipe-deps model-dir")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    val python = Paths.get(args(1)).toAbsolutePath.normalize()
    val checkpoint = Paths.get(args(2)).toAbsolutePath.normalize()
    val family = args(3)
    val deps = Paths.get(args(4)).toAbsolutePath.normalize()
    val modelDir = Paths.get(args(5)).toAbsolutePath.normalize()
    require(Set("sd15", "sdxl")(family))
    Files.createDirectories(output)
    val run = Files.createTempDirectory(output, "mediapipe-gui-")
    val home = AppHome(Files.createDirectories(run.resolve("home")))
    val source = Paths.get("python").toAbsolutePath.normalize()
    val settings = Settings(pythonExe = Some(python.toString), outputDir = home.root.resolve("outputs").toString,
      modelDirs = ModelDirs(checkpoints = Seq(checkpoint.getParent.toString)), hfOffline = true,
      vramMode = if family == "sdxl" then "sequential_offload" else "model_offload", previewEvery = 0)
    val launch = () => EngineLaunch(Seq(python.toString, "-m", "sdfx"), source,
      Map("PYTHONPATH" -> s"$source${java.io.File.pathSeparator}$deps", "SDFX_MEDIAPIPE_DIR" -> modelDir.toString,
        "HF_HUB_OFFLINE" -> "1", "TRANSFORMERS_OFFLINE" -> "1", "OMP_NUM_THREADS" -> "1", "USE_TF" -> "0"))
    val services = new AppServices(home, settings, dryRun = false, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(launch), persistSettings = false, timeouts = EngineTimeouts(load = 10.minutes),
      installShutdownHook = false)
    val finished = new LinkedBlockingQueue[Either[String, OutputImage]]()
    services.queue.addListener {
      case event: JobEvent.JobFinished => finished.offer(event.output.images.headOption.toRight("no image")); ()
      case event: JobEvent.JobFailed => finished.offer(Left(event.message)); ()
      case _ => ()
    }
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      require(!services.start().dryRun)
      val loaded = services.loadCheckpoint(checkpoint.toString, remember = false)
      require(loaded.kind == family)
      window = fx {
        val w = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        w.show(); w.select(Mode.Txt2Img); w.stage.setWidth(1280); w.stage.setHeight(850)
        w.model.checkpoint.set(loaded.name)
        val vm = w.vms(Mode.Txt2Img)
        vm.prompt.set("close-up portrait of a smiling young woman, looking at viewer, detailed face")
        vm.negativePrompt.set("blurry, text")
        vm.width.set(if family == "sdxl" then 832 else 512)
        vm.height.set(if family == "sdxl" then 1216 else 512)
        vm.steps.set(20); vm.seed.set(2468); vm.sampler.set("Euler")
        val section = w.panels(Mode.Txt2Img).sections("txt2img.plugin.batch_face_swap")
        section.setExpanded(true)
        val enabled = section.lookup("#plugin-batch_face_swap-enabled").asInstanceOf[CheckBox]
        val detector = section.lookup("#plugin-batch_face_swap-detector").asInstanceOf[ComboBox[String]]
        require(enabled != null && detector != null && detector.getItems.contains("mediapipe_face"))
        enabled.setSelected(true); detector.setValue("mediapipe_face")
        section.applyCss(); section.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(section.snapshot(null, null), null), "png", run.resolve("controls.png").toFile))
        w
      }
      fx { require(!window.statusBar.generateBtn.isDisabled); window.statusBar.generateBtn.fire() }
      val result = finished.poll(900, TimeUnit.SECONDS)
      require(result != null, "native generation timed out")
      val image = result.fold(message => throw new IllegalStateException(message), identity)
      val infotext = PngMetadata.readParameters(image.path).getOrElse(sys.error("PNG lacks parameters"))
      require(infotext.contains("mediapipe_face"), infotext)
      Files.writeString(run.resolve("receipt.json"), ujson.write(ujson.Obj("status" -> "passed", "family" -> family,
        "image" -> image.path.toString, "png_infotext" -> infotext), indent = 2))
      println(s"MEDIAPIPE_GUI_OK artifacts=$run family=$family")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
