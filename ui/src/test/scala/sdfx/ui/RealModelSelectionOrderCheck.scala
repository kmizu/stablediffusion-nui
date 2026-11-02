package sdfx.ui

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.stage.Stage
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.model.{ExtendedStyle, GenerationParams}
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

/** Actual MainWindow + fake worker gate: no GPU and no external model downloads. */
object RealModelSelectionOrderCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(60, TimeUnit.SECONDS)
  private def waitFor(label: String, seconds: Int = 60)(test: => Boolean): Unit =
    val deadline = System.nanoTime() + seconds.seconds.toNanos
    while !test && System.nanoTime() < deadline do Thread.sleep(20)
    require(test, "Timed out: " + label)

  def main(args: Array[String]): Unit =
    require(args.length == 2 || (args.length == 3 && args(2) == "--lost-model"), "python output-directory [--lost-model]")
    val lostModel = args.length == 3
    val python = Paths.get(args(0)).toAbsolutePath.normalize()
    val output = Paths.get(args(1)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val run = Files.createTempDirectory(output, "selection-")
    val models = Files.createDirectories(run.resolve("models"))
    val vaes = Files.createDirectories(run.resolve("vaes"))
    val gate = Files.createDirectories(run.resolve("gate"))
    Seq("A.safetensors", "B.safetensors").foreach(n => Files.write(models.resolve(n), Array[Byte](0)))
    Seq("first.safetensors", "second.safetensors").foreach(n => Files.write(vaes.resolve(n), Array[Byte](0)))
    val input = run.resolve("input.png")
    ImageIO.write(new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", input.toFile)
    val home = AppHome(Files.createDirectories(run.resolve("home")))
    val fake = Paths.get("engine/src/test/resources/fake_engine.py").toAbsolutePath.normalize()
    val settings = Settings(pythonExe = Some(python.toString), outputDir = run.resolve("images").toString,
      modelDirs = ModelDirs(checkpoints = Seq(models.toString), vae = Seq(vaes.toString)), previewEvery = 0)
    val services = new AppServices(home, settings, dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(python.toString, fake.toString), fake.getParent,
        Map("SDFX_FAKE_MODEL_GATE_DIR" -> gate.toString) ++
          (if lostModel then Map("SDFX_FAKE_LOSE_MODEL_ON_GATE" -> "1") else Map.empty))), persistSettings = false,
      timeouts = EngineTimeouts(load = 2.minutes), installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start(); services.catalog.refresh()
      val loaded = services.loadCheckpoint("A", remember = false)
      window = fx {
        val params = GenerationParams(prompt = "a blue ceramic teapot", steps = 2, width = 64, height = 64)
        val w = new MainWindow(new Stage(), services, UiState(params = Map("txt2img" -> params)), CliOptions(dryRun = true))
        w.stage.setWidth(1280); w.stage.setHeight(950); w.show(); w.model.checkpoint.set(loaded.name)
        w.select(Mode.Inpaint); w.vms(Mode.Inpaint).initImage.set(input.toString)
        w
      }
      waitFor("inpaint input and mask") { fx { window.panels(Mode.Inpaint).mask.get.paintDemo(); window.vms(Mode.Inpaint).hasMask.get } }
      def masks(): Set[Path] =
        val stream = Files.list(window.ctx.tmpDir)
        try stream.iterator().asScala.filter(_.getFileName.toString.startsWith("mask-")).toSet
        finally stream.close()
      val beforeMasks = masks()
      val maskObserved = new CountDownLatch(1)
      fx {
        window.vms(Mode.Inpaint).maskImage.addListener((_, _, value) => if value.nonEmpty then maskObserved.countDown())
        // The FX callback from asynchronous mask export cannot run until this turn ends.
        window.generate(false)
        window.loadCheckpoint("B")
        window.ctx.applyExtendedStyle(ExtendedStyle("fixture", "style", if lostModel then "A" else "B", "first", "blue pot", "", 64, 64))
        window.model.vae.set("second"); window.setVae("second")
      }
      waitFor("first B entered fake worker") { Files.exists(gate.resolve("ready")) }
      require(maskObserved.await(30, TimeUnit.SECONDS), "mask export did not complete")
      waitFor("rejected mask cleanup") { masks() == beforeMasks && fx(window.vms(Mode.Inpaint).maskImage.get.isEmpty) }
      require(services.queue.isIdle, "generation entered queue while model selection was pending")
      Files.writeString(gate.resolve("release"), "release")
      waitFor("style and later VAE complete") {
        !fx(window.ctx.extendedStyleApplying.get) &&
          services.supervisor.loadedModel.flatMap(_.vaePath).contains(vaes.resolve("second.safetensors").toString)
      }
      val worker = Await.result(services.client.raw("get_state"), 30.seconds)
      val expectedModel = if lostModel then "A" else "B"
      require(worker("loaded").str == models.resolve(expectedModel + ".safetensors").toString, worker.toString)
      require(worker("vae").str == vaes.resolve("second.safetensors").toString, worker.toString)
      require(worker("load_count").num.toInt == 2, worker.toString) // A and style retry B; failed first B not counted.
      require(fx(window.model.checkpoint.get) == expectedModel, "style target was not selected")
      require(fx(window.model.vae.get) == "second", "stale style callback overwrote later VAE")
      require(services.queue.isIdle, "unexpected queued generation")
      val before = worker("requests").arr.count(_.str == "set_vae")
      // Remove the already-loaded test VAE without refreshing the catalog. A prompt-only
      // style for the same path must keep the live VAE instead of trying to reload it.
      Files.delete(vaes.resolve("second.safetensors"))
      fx(window.ctx.applyExtendedStyle(ExtendedStyle("fixture-2", "same VAE", expectedModel, "second", "second style confirmed", "", 64, 64)))
      waitFor("same VAE style completes") { !fx(window.ctx.extendedStyleApplying.get) }
      require(fx(window.vms(Mode.Inpaint).prompt.get) == "second style confirmed", "same VAE style did not apply")
      val after = Await.result(services.client.raw("get_state"), 30.seconds)
      require(after("requests").arr.count(_.str == "set_vae") == before, "same VAE was reloaded")
      require(after("vae").str == vaes.resolve("second.safetensors").toString, "live VAE changed")
      println(s"MODEL_SELECTION_UI_SUCCESS failed-first-B style=$expectedModel lostModel=$lostModel later-VAE-wins delayed-mask-cleaned same-VAE-not-reloaded")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
