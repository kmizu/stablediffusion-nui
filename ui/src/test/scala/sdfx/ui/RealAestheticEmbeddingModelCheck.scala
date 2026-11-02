package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.*
import javafx.stage.Stage
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.infotext.Infotext
import sdfx.core.model.GenerationParams
import sdfx.core.png.PngMetadata
import sdfx.core.plugins.api.OutputImage
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, LinkedBlockingQueue, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Manual real-model GUI gate: python checkpoint reference-image output-directory side.
  * The image is inserted into the same ListView used by the chooser; the OS chooser itself is not automated.
  */
object RealAestheticEmbeddingModelCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then body
    else
      val task = new FutureTask[A](new Callable[A]:
        def call(): A = body)
      Platform.runLater(task)
      task.get(60, TimeUnit.SECONDS)

  private def eventually(label: String, seconds: Int = 600)(body: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(50)
    require(body, "Timed out: " + label)

  def main(args: Array[String]): Unit =
    require(args.length == 5, "python checkpoint reference-image output-directory side")
    val python = Paths.get(args(0)).toAbsolutePath.normalize()
    val checkpoint = Paths.get(args(1)).toAbsolutePath.normalize()
    val reference = Paths.get(args(2)).toAbsolutePath.normalize()
    val output = Paths.get(args(3)).toAbsolutePath.normalize()
    val side = args(4).toInt
    require(Files.isRegularFile(python) && Files.isRegularFile(checkpoint) && Files.isRegularFile(reference))
    Files.createDirectories(output)
    val home = AppHome(Files.createTempDirectory(output, "real-aesthetic-home-"))
    val engineDir = Paths.get("python").toAbsolutePath.normalize()
    val settings = Settings(pythonExe = Some(python.toString), outputDir = output.resolve("images").toString,
      modelDirs = ModelDirs(checkpoints = Seq(checkpoint.getParent.toString)), vramMode = "model_offload",
      dtype = "fp16", previewEvery = 0)
    val services = new AppServices(home, settings, dryRun = false, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(python.toString, "-m", "sdfx"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))), persistSettings = false,
      timeouts = EngineTimeouts(load = 10.minutes), installShutdownHook = false)
    val finished = new LinkedBlockingQueue[Either[String, OutputImage]]()
    services.queue.addListener {
      case event: JobEvent.JobFinished => finished.offer(event.output.images.headOption.toRight("no output image")); ()
      case event: JobEvent.JobFailed => finished.offer(Left(event.message)); ()
      case _ => ()
    }
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start()
      val loaded = services.loadCheckpoint(checkpoint.toString, remember = false)
      window = fx {
        val params = GenerationParams(prompt = "a blue ceramic teapot on a wooden table, soft daylight",
          negativePrompt = "blurry", seed = 173, steps = 12, width = side, height = side, samplerName = "Euler")
        val w = new MainWindow(new Stage(), services, UiState(params = Map("txt2img" -> params)), CliOptions())
        w.stage.setWidth(1280); w.stage.setHeight(950); w.show(); w.model.checkpoint.set(loaded.name)
        w.plugins(Mode.Txt2Img).form("model_keyword").flatMap(_.prop("enabled")).foreach(_.set("false"))
        w
      }
      def node[A](id: String): A = fx(window.scene.lookup("#" + id).asInstanceOf[A])
      def status: String = fx(node[Label]("aesthetic-status").getText)
      def fire(id: String): Unit = fx(node[Button](id).fire())
      def generate(): OutputImage =
        fx(window.statusBar.generateBtn.fire())
        val r = finished.poll(600, TimeUnit.SECONDS)
        require(r != null, "generation timed out")
        r.fold(error => throw new IllegalStateException(error), identity)
      val baseline = generate()
      fx {
        val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
        tabs.getTabs.asScala.find(_.getId == "aesthetic-embedding-tab").foreach(tabs.getSelectionModel.select)
      }
      val refs = node[ListView[Path]]("aesthetic-images")
      fx { refs.getItems.add(reference); node[TextField]("aesthetic-name").setText("gui-style") }
      fire("aesthetic-create")
      eventually("CLIP embedding request settled")(!status.startsWith("Creating embedding"))
      require(status.startsWith("Created "), "embedding creation failed: " + status)
      val selected = fx(node[ComboBox[String]]("aesthetic-embeddings").getValue)
      require(Files.isRegularFile(Paths.get(selected)), "embedding was not created")
      val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Paths.get(selected)))
        .map(b => f"${b & 255}%02x").mkString
      fire("aesthetic-use")
      val form = window.plugins(Mode.Txt2Img).form("aesthetic_gradients").get
      fx { form.prop("weight").get.set("0.3"); form.prop("steps").get.set("5") }
      require(fx(form.prop("embedding").get.get) == selected && fx(form.prop("enabled").get.get) == "true")
      val styled = generate()
      val text = PngMetadata.readParameters(styled.path).getOrElse(throw new IllegalStateException("PNG parameters missing"))
      val fields = Infotext.parse(text).fields
      require(fields.get("Aesthetic embedding SHA256").contains(digest), "recorded hash does not match embedding")
      val original = ImageIO.read(baseline.path.toFile)
      val changed = ImageIO.read(styled.path.toFile)
      val a = original.getRGB(0, 0, original.getWidth, original.getHeight, null, 0, original.getWidth)
      val b = changed.getRGB(0, 0, changed.getWidth, changed.getHeight, null, 0, changed.getWidth)
      require(!java.util.Arrays.equals(a, b), "aesthetic embedding did not change pixels")
      fx {
        window.root.applyCss(); window.root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve("gui-result.png").toFile))
      }
      val report = ujson.Obj("success" -> true, "checkpoint" -> checkpoint.toString, "reference" -> reference.toString,
        "embedding" -> selected, "embedding_sha256" -> digest, "baseline" -> baseline.path.toString,
        "styled" -> styled.path.toString, "side" -> side, "chooser_manually_exercised" -> false)
      Files.writeString(output.resolve("receipt.json"), report.render(indent = 2) + "\n")
      println("REAL AESTHETIC GUI CHECK PASSED")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown()
      Platform.exit()
