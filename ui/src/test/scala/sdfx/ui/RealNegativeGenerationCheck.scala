package sdfx.ui

import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, LinkedBlockingQueue, TimeUnit}
import javafx.application.Platform
import javafx.scene.control.{Button, ButtonBar, ComboBox, DialogPane}
import javafx.stage.{Stage, Window}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import javax.imageio.ImageIO
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.infotext.Infotext
import sdfx.core.plugins.api.OutputImage
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

/** Real picker -> native Generate button -> real model -> persisted PNG infotext. */
object RealNegativeGenerationCheck:
  private def fx[A](f: => A): A =
    if Platform.isFxApplicationThread then return f
    val task = new FutureTask[A](new Callable[A] { def call(): A = f })
    Platform.runLater(task)
    task.get(45, TimeUnit.SECONDS)

  private def until(label: String, seconds: Int = 45)(f: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !f && System.nanoTime() < end do Thread.sleep(50)
    require(f, s"Timed out: $label")

  private def pane: Option[DialogPane] = fx {
    Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene))
      .map(_.getRoot).collectFirst { case p: DialogPane => p }
  }

  def main(args: Array[String]): Unit =
    require(args.length == 4, "output-directory python checkpoint expected-family")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    val python = Paths.get(args(1)).toAbsolutePath.normalize()
    val checkpoint = Paths.get(args(2)).toAbsolutePath.normalize()
    val family = args(3)
    require(Set("sd15", "sdxl")(family))
    Seq(python, checkpoint).foreach(p => require(Files.isRegularFile(p), s"Missing file: $p"))
    Files.createDirectories(output)
    val run = Files.createTempDirectory(output, "negative-gui-")
    val home = AppHome(Files.createDirectories(run.resolve("home")))
    val engineDir = Paths.get("python").toAbsolutePath.normalize()
    val settings = Settings(pythonExe = Some(python.toString), outputDir = home.root.resolve("outputs").toString,
      modelDirs = ModelDirs(checkpoints = Seq(checkpoint.getParent.toString)), hfOffline = true, previewEvery = 0)
    val launch = () => EngineLaunch(Seq(python.toString, "-m", "sdfx"), engineDir,
      Map("PYTHONPATH" -> engineDir.toString, "HF_HUB_OFFLINE" -> "1", "TRANSFORMERS_OFFLINE" -> "1",
        "OPENBLAS_NUM_THREADS" -> "1", "OMP_NUM_THREADS" -> "1", "USE_TF" -> "0"))
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
      require(loaded.kind == family, s"Expected $family, got ${loaded.kind}")
      window = fx {
        val w = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        w.show(); w.select(Mode.Txt2Img); w.stage.setWidth(1280); w.stage.setHeight(850)
        w.model.checkpoint.set(loaded.name)
        val vm = w.vms(Mode.Txt2Img)
        vm.prompt.set("a red fox resting in a snowy forest, soft daylight")
        vm.negativePrompt.set("existing negative")
        val size = if family == "sdxl" then 768 else 512
        vm.width.set(size); vm.height.set(size); vm.steps.set(20); vm.seed.set(20261103)
        vm.batchSize.set(1); vm.batchCount.set(1); vm.sampler.set("Euler")
        w
      }
      Platform.runLater(() => window.scene.lookup("#txt2img-negative-suggestions").asInstanceOf[Button].fire())
      until("negative picker")(pane.nonEmpty)
      fx {
        val d = pane.get
        d.lookup("#negative-suggestion-preset").asInstanceOf[ComboBox[String]].setValue("Quality")
        val add = d.getButtonTypes.asScala.find(_.getButtonData == ButtonBar.ButtonData.OK_DONE).get
        d.lookupButton(add).asInstanceOf[Button].fire()
      }
      until("picker closed")(pane.isEmpty)
      val expected = "existing negative, low quality, worst quality, blurry, jpeg artifacts"
      fx {
        require(window.vms(Mode.Txt2Img).negativePrompt.get == expected)
        require(!window.statusBar.generateBtn.isDisabled, "Generate button disabled")
        window.statusBar.generateBtn.fire()
      }
      val result = finished.poll(900, TimeUnit.SECONDS)
      require(result != null, "native generation timed out")
      val image = result.fold(message => throw new IllegalStateException(message), identity)
      require(Files.isRegularFile(image.path))
      val pixels = ImageIO.read(image.path.toFile)
      require(pixels != null && pixels.getWidth > 0 && pixels.getHeight > 0)
      val infotext = PngMetadata.readParameters(image.path).getOrElse(sys.error("PNG lacks parameters"))
      require(Infotext.parse(infotext).negativePrompt == expected, infotext)
      val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image.path)).map(b => f"${b & 255}%02x").mkString
      Files.writeString(run.resolve("receipt.json"), ujson.write(ujson.Obj("status" -> "passed", "family" -> family,
        "checkpoint" -> checkpoint.toString, "negative" -> expected, "image" -> image.path.toString,
        "image_sha256" -> hash, "png_infotext" -> infotext), indent = 2))
      println(s"NEGATIVE_GUI_GENERATION_OK artifacts=$run family=$family")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown()
      Platform.exit()
