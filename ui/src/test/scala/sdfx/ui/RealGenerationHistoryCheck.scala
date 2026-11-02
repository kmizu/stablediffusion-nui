package sdfx.ui

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Node
import javafx.scene.control.{Button, ButtonType, CheckBox, ComboBox, DialogPane, ListView, TabPane, TextArea, TextField}
import javafx.stage.{Stage, Window}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.presets.PresetRecord
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

/** Root-run real-model acceptance of the automatic generation history through the real MainWindow:
  * auto-record on Generate, dedupe, thumbnails, category-selective and full restore, exact regeneration,
  * autosave off, Keep as named state, delete. Args: output-directory python checkpoint side steps.
  */
object RealGenerationHistoryCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task)
    task.get(45, TimeUnit.SECONDS)

  private def eventually(label: String, seconds: Int = 45)(condition: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !condition && System.nanoTime() < end do Thread.sleep(50)
    require(condition, s"Timed out: $label")

  def main(args: Array[String]): Unit =
    require(args.length == 5, "output-directory python checkpoint side steps")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    val python = Paths.get(args(1)).toAbsolutePath.normalize()
    val checkpoint = Paths.get(args(2)).toAbsolutePath.normalize()
    val side = args(3).toInt
    val steps = args(4).toInt
    Seq(python, checkpoint).foreach(p => require(Files.isRegularFile(p), s"Missing file: $p"))
    Files.createDirectories(output)
    val run = Files.createTempDirectory(output, "history-real-")
    val home = AppHome(Files.createDirectories(run.resolve("home")))
    // Full restore refuses saved choices missing from the live catalog (existing, tested named-state rule), and
    // the img2img Hires Fix default upscaler "R-ESRGAN 4x+" is not installed on this machine. A placeholder file
    // only makes that name appear in the catalog; it is never loaded (Hires stays off).
    val upscalerDir = Files.createDirectories(run.resolve("upscalers"))
    Files.write(upscalerDir.resolve("R-ESRGAN 4x+.pth"), Array[Byte](0))
    val engineDir =Paths.get("python").toAbsolutePath.normalize()
    val settings = Settings(pythonExe = Some(python.toString), outputDir = home.root.resolve("outputs").toString,
      modelDirs = ModelDirs(checkpoints = Seq(checkpoint.getParent.toString), upscalers = Seq(upscalerDir.toString)),
      hfOffline = true)
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
    var failure: Throwable = null
    var notes = Vector.empty[String]
    def note(s: String): Unit = { notes :+= s; println("HISTORY PASS: " + s) }
    def control[A <: Node](id: String): A =
      val node = window.namedStatesStage.get.getScene.lookup("#" + id)
      require(node != null, s"Missing production control: $id")
      node.asInstanceOf[A]
    def historyItems: Vector[PresetRecord] = fx(control[ListView[PresetRecord]]("generation-history-list").getItems.asScala.toVector)
    def status: String = fx(control[TextArea]("generation-states-status").getText)
    def screenshot(name: String): Unit = fx {
      val scene = window.namedStatesStage.get.getScene
      scene.getRoot.applyCss(); scene.getRoot.layout()
      require(ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png", run.resolve(name).toFile))
    }
    def confirmFire(id: String): Unit =
      val task = new FutureTask[Unit](new Callable[Unit] { def call(): Unit = control[Button](id).fire() })
      Platform.runLater(task)
      eventually(s"confirmation for $id") { fx {
        Window.getWindows.asScala.exists(w => w.isShowing && w.getScene != null && w.getScene.getRoot.isInstanceOf[DialogPane]) || task.isDone
      } }
      fx {
        Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene)).map(_.getRoot)
          .collectFirst { case p: DialogPane => p }.foreach(_.lookupButton(ButtonType.OK).asInstanceOf[Button].fire())
      }
      task.get(30, TimeUnit.SECONDS)
    def selectHistory(indices: Int*): Unit = fx {
      val list = control[ListView[PresetRecord]]("generation-history-list")
      list.getSelectionModel.clearSelection()
      indices.foreach(list.getSelectionModel.select)
    }
    def setCategories(only: Set[String]): Unit = fx {
      Seq("prompts", "sampling", "model", "controlnet", "scripts", "other").foreach { c =>
        control[CheckBox]("generation-states-cat-" + c).setSelected(only(c))
      }
    }
    def generate(): Path =
      val before = events.asScala.count(_.isInstanceOf[JobEvent.JobQueued])
      fx { window.generate(false) }
      eventually("job queued") { events.asScala.count(_.isInstanceOf[JobEvent.JobQueued]) > before }
      val queued = events.asScala.collect { case e: JobEvent.JobQueued => e.job }.toVector.last
      eventually("job terminal", 900) { events.asScala.exists(e => e.jobId == queued.id &&
        (e.isInstanceOf[JobEvent.JobFinished] || e.isInstanceOf[JobEvent.JobFailed] || e.isInstanceOf[JobEvent.JobCancelled])) }
      val result = events.asScala.collectFirst { case e: JobEvent.JobFinished if e.jobId == queued.id => e.output }
        .getOrElse(sys.error(s"Generation failed: ${events.asScala.filter(_.jobId == queued.id).mkString("\n")}"))
      require(result.images.size == 1)
      result.images.head.path
    def pixels(p: Path): Vector[Int] =
      val img = ImageIO.read(p.toFile)
      (for y <- 0 until img.getHeight; x <- 0 until img.getWidth yield img.getRGB(x, y)).toVector
    def hash(p: Path): String =
      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).map(b => f"${b & 255}%02x").mkString
    try
      require(!services.start().dryRun)
      val loaded = services.loadCheckpoint(checkpoint.toString, remember = false)
      require(Set("sd15", "sdxl")(loaded.kind))
      window = fx {
        val w = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        w.show(); w.select(Mode.Txt2Img); w.stage.setWidth(1280); w.stage.setHeight(850)
        w.model.checkpoint.set(loaded.name)
        w.openGenerationStates(Mode.Txt2Img)
        w
      }
      fx { control[TabPane]("generation-states-tabs").getSelectionModel.select(1) }
      eventually("library available") { fx { !control[Button]("generation-states-save").isDisabled } }
      require(fx(control[CheckBox]("generation-history-auto").isSelected), "history recording must default to on")
      require(historyItems.isEmpty)
      def configure(prompt: String, seed: Long, st: Int, cfg: Double): Unit = fx {
        val vm = window.vms(Mode.Txt2Img)
        vm.prompt.set(prompt); vm.negativePrompt.set("blurry, low quality")
        vm.width.set(side); vm.height.set(side); vm.steps.set(st); vm.seed.set(seed)
        vm.batchSize.set(1); vm.batchCount.set(1); vm.cfgScale.set(cfg)
        vm.sampler.set("Euler"); vm.scheduler.set("Automatic")
      }
      // 1. Auto-record + thumbnail
      configure("1girl, red kimono, cherry blossoms", 7101, steps, 6.0)
      val paramsA = fx(window.vms(Mode.Txt2Img).toParams)
      val imageA = generate()
      eventually("first entry") { historyItems.size == 1 }
      val idA = historyItems.head.id
      val thumbA = home.configDir.resolve(s"generation-history/thumbs/$idA.png")
      eventually("first thumbnail") { Files.isRegularFile(thumbA) }
      val thumb = ImageIO.read(thumbA.toFile)
      require(thumb.getWidth <= 100 && thumb.getHeight <= 100 && math.max(thumb.getWidth, thumb.getHeight) == 100, s"thumbnail ${thumb.getWidth}x${thumb.getHeight}")
      val thumbColors = (for y <- 0 until thumb.getHeight; x <- 0 until thumb.getWidth yield thumb.getRGB(x, y)).distinct.size
      require(thumbColors > 50, s"thumbnail looks empty ($thumbColors colours)")
      note(s"Generate auto-recorded entry '${historyItems.head.name}' with a ${thumb.getWidth}x${thumb.getHeight} thumbnail ($thumbColors colours)")
      screenshot("01-one-entry.png")
      // 2. Different state -> second entry, newest first
      configure("1boy, blue armor, castle, night", 7102, steps + 1, 8.0)
      val paramsB = fx(window.vms(Mode.Txt2Img).toParams)
      val imageB = generate()
      eventually("second entry") { historyItems.size == 2 }
      require(historyItems.head.fields.exists((k, v) => k.render == "generation.seed" && v.toString.contains("7102")), "newest entry must be first")
      note("second Generate recorded a second entry, newest first")
      selectHistory(0, 1)
      fx { control[Button]("generation-history-favourite").fire() }
      eventually("two favourites") { fx {
        control[TabPane]("generation-states-tabs").getSelectionModel.select(2)
        control[ListView[PresetRecord]]("generation-favourites-list").getItems.size == 2
      }}
      fx {
        control[ListView[PresetRecord]]("generation-favourites-list").getSelectionModel.clearAndSelect(1)
        control[ComboBox[String]]("generation-states-field").setValue("generation.prompt")
      }
      confirmFire("generation-states-restore-field")
      fx {
        require(window.vms(Mode.Txt2Img).prompt.get == paramsA.prompt)
        require(window.vms(Mode.Txt2Img).seed.get == 7102L)
        control[CheckBox]("generation-history-auto").setSelected(false)
      }
      val imageFavourite = generate()
      val favouriteMetadata = sdfx.core.png.PngMetadata.readParameters(imageFavourite).getOrElse("")
      require(favouriteMetadata.contains(paramsA.prompt) && favouriteMetadata.contains("Seed: 7102"), favouriteMetadata)
      fx {
        window.vms(Mode.Txt2Img).prompt.set(paramsB.prompt)
        control[CheckBox]("generation-history-auto").setSelected(true)
        control[TabPane]("generation-states-tabs").getSelectionModel.select(1)
      }
      note(s"multiple favourites stored; restored a favourite prompt and generated image $imageFavourite")
      // 3. Identical state -> no duplicate
      generate()
      Thread.sleep(1500)
      require(historyItems.size == 2, s"identical state duplicated: ${historyItems.size}")
      note("regenerating the identical newest state did not add a duplicate")
      // 4. Category-selective restore of the OLDER entry: prompts only
      selectHistory(1)
      eventually("older entry selected") { fx(control[ListView[PresetRecord]]("generation-history-list").getSelectionModel.getSelectedItems.size == 1) }
      fx { control[ComboBox[String]]("generation-states-field").setValue("generation.prompt") }
      confirmFire("generation-states-restore-field")
      fx {
        val vm = window.vms(Mode.Txt2Img)
        require(vm.prompt.get == paramsA.prompt, "individual prompt not restored")
        require(vm.seed.get == 7102L && vm.steps.get == steps + 1 && vm.cfgScale.get == 8.0,
          "single-field restore changed other fields")
      }
      fx { control[CheckBox]("generation-history-auto").setSelected(false) }
      val imageSingle = generate()
      val singleMetadata = sdfx.core.png.PngMetadata.readParameters(imageSingle).getOrElse("")
      require(singleMetadata.contains(paramsA.prompt) && singleMetadata.contains("Seed: 7102"), singleMetadata)
      fx {
        window.vms(Mode.Txt2Img).prompt.set(paramsB.prompt)
        control[CheckBox]("generation-history-auto").setSelected(true)
      }
      note(s"single-field history restore changed only the selected prompt and generated image $imageSingle")
      setCategories(Set("prompts"))
      confirmFire("generation-states-restore")
      fx {
        val vm = window.vms(Mode.Txt2Img)
        require(vm.prompt.get == paramsA.prompt, s"prompt not restored: ${vm.prompt.get}")
        require(vm.seed.get == 7102L && vm.steps.get == steps + 1 && vm.cfgScale.get == 8.0, "non-selected categories changed")
      }
      require(status.contains("selected fields"), status)
      note("prompts-only restore changed the prompt and left seed/steps/CFG untouched")
      // 5. Full restore, then regenerate exactly
      setCategories(Set("prompts", "sampling", "model", "controlnet", "scripts", "other"))
      confirmFire("generation-states-restore")
      fx { val now = window.vms(Mode.Txt2Img).toParams
        require(now == paramsA, s"full restore did not reproduce the original GenerationParams\nstatus: ${control[TextArea]("generation-states-status").getText}\nnow:  $now\norig: $paramsA") }
      val imageA2 = generate()
      require(pixels(imageA) == pixels(imageA2), "regenerating the restored history entry differs from the original image")
      eventually("third entry") { historyItems.size == 3 }
      note(s"full restore reproduced GenerationParams; regenerated image pixels identical to the original (sha256 ${hash(imageA).take(12)} file, pixels equal)")
      // 6. Autosave off
      fx { control[CheckBox]("generation-history-auto").setSelected(false) }
      eventually("autosave flag") { Files.exists(home.configDir.resolve("generation-history/autosave.off")) }
      configure("landscape, mountains, sunrise", 7105, steps, 7.0)
      generate()
      Thread.sleep(1500)
      require(historyItems.size == 3, s"autosave-off generation was recorded: ${historyItems.size}")
      fx { control[CheckBox]("generation-history-auto").setSelected(true) }
      eventually("autosave flag removed") { !Files.exists(home.configDir.resolve("generation-history/autosave.off")) }
      note("with recording off, Generate added no entry; re-enabled afterwards")
      // 7. Keep as named state
      selectHistory(2)
      fx { control[TextField]("generation-states-name").setText("kept from history") }
      fx { control[Button]("generation-history-keep").fire() }
      eventually("kept named state") { fx(control[ListView[String]]("generation-states-list").getItems.contains("kept from history")) }
      note("Keep as named state copied the entry into the named list")
      // 8. Delete multiple, thumbnails removed
      val ids = historyItems.map(_.id)
      selectHistory(0, 1)
      confirmFire("generation-history-delete")
      eventually("two deleted") { historyItems.size == 1 }
      require(historyItems.head.id == ids(2))
      require(!Files.exists(home.configDir.resolve(s"generation-history/thumbs/${ids(0)}.png")) &&
              !Files.exists(home.configDir.resolve(s"generation-history/thumbs/${ids(1)}.png")), "deleted thumbnails remain")
      note("multi-select Delete removed two entries and their thumbnails")
      screenshot("02-after-delete.png")
      val upstream = run.resolve("synthetic-history.txt")
      val upstreamJson = s"""{"defaults":{"d":{"txt2img/Prompt":"source default","txt2img/Sampling method":"Euler","txt2img/Sampling steps":$steps,"txt2img/Width":$side,"txt2img/Height":$side,"customscript/seed.py/txt2img/Seed":7109,"txt2img/Unsupported":"preserved"}},"entries":{"1700000000123":{"saveVersion":"2.0","type":"txt2img","defaults":"d","groups":["history","favourites"],"quickSettings":{},"componentSettings":{"txt2img/Prompt":"1girl, red kimono, cherry blossoms"}}}}"""
      val gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(upstream))
      gz.write(upstreamJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)); gz.close()
      fx {
        window.ctx.chooseStateManagerImport = () => Some(upstream)
        control[Button]("generation-history-import").fire()
      }
      eventually("State Manager preview") { fx {
        Window.getWindows.asScala.exists(w => w.isShowing && w.getScene != null && w.getScene.getRoot.isInstanceOf[DialogPane])
      }}
      fx {
        val pane = Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene)).map(_.getRoot)
          .collectFirst { case p: DialogPane => p }.get
        require(pane.lookup("#state-manager-import-summary").asInstanceOf[TextArea].getText.contains("txt2img/Unsupported"))
        pane.lookupButton(ButtonType.OK).asInstanceOf[Button].fire()
      }
      eventually("imported State Manager favourite") { fx {
        control[TabPane]("generation-states-tabs").getSelectionModel.select(2)
        control[ListView[PresetRecord]]("generation-favourites-list").getItems.asScala.exists(_.source.exists(_.format == "state-manager-v2"))
      }}
      fx {
        val list = control[ListView[PresetRecord]]("generation-favourites-list")
        val imported = list.getItems.asScala.find(_.source.exists(_.format == "state-manager-v2")).get
        require(imported.source.get.originalFields.text.contains("preserved"))
        list.getSelectionModel.clearSelection(); list.getSelectionModel.select(imported)
        control[CheckBox]("generation-history-auto").setSelected(false)
      }
      confirmFire("generation-states-restore")
      fx {
        val p = window.vms(Mode.Txt2Img).toParams
        require(p.prompt == "1girl, red kimono, cherry blossoms" && p.seed == 7109 && p.steps == steps)
      }
      val importedImage = generate()
      val importedText = sdfx.core.png.PngMetadata.readParameters(importedImage).getOrElse("")
      require(importedText.contains("1girl, red kimono, cherry blossoms") && importedText.contains("Seed: 7109"), importedText)
      note(s"imported synthetic State Manager favourite, restored it and generated image $importedImage")
      require(Files.isRegularFile(home.configDir.resolve("generation-history/library-v1.json")))
      Files.writeString(run.resolve("report.json"), ujson.write(ujson.Obj("status" -> "passed", "family" -> loaded.kind,
        "side" -> side, "steps" -> steps, "checks" -> ujson.Arr(notes.map(ujson.Str(_))*),
        "imageA" -> imageA.toString, "imageA_regenerated_sha256" -> hash(imageA2), "imageB" -> imageB.toString), indent = 2))
      println(s"HISTORY_REAL_OK artifacts=$run family=${loaded.kind}")
    catch case error: Throwable => failure = error
    finally
      NativeAcceptanceSafety.cleanup(failure)(
        () => if window != null then fx { window.dispose(); window.stage.close() },
        () => services.shutdown(), () => Platform.exit())
    if failure != null then throw failure
