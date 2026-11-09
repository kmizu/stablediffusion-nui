package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.stage.Stage
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.infotext.Infotext
import sdfx.core.plugins.api.OutputImage
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, LinkedBlockingQueue, TimeUnit}
import javax.imageio.ImageIO

/** MANUAL gate (real scala-torch engine, GPU). Args: output-directory [sd15-checkpoint [sdxl-checkpoint]].
  * Drives the real MainWindow txt2img tab with the prompt_library / scenario_loader / prompt_generator scripts on SD1.5 and SDXL.
  */
object RealInfinityGridCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private val Dir = "D:/stable-diffusion-webui-reForge/models/Stable-diffusion/"
  def main(args: Array[String]): Unit =
    require(args.length >= 1, "output-directory [sd15 [sdxl]]")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val sd15 = Paths.get(if args.length > 1 then args(1) else Dir + "animekawa_v10.safetensors")
    val sdxl = Paths.get(if args.length > 2 then args(2) else Dir + "waiIllustriousSDXL_v170.safetensors")
    val gridYaml = output.resolve("grid.yaml")
    Files.writeString(gridYaml, "axes:\n  prompt:\n    values: \"red hair | blue hair\"\n  steps:\n    values: \"6, 14\"\n")
    val home = AppHome(output.resolve("app-home"))
    val services = new AppServices(home, Settings(engineBackend = "scala-torch", scalaTorchModel = Some(sd15.toString),
      scalaTorchUnetDevice = "cuda", scalaTorchVaeDevice = "cuda", modelDirs = ModelDirs(checkpoints = Seq(sd15.getParent.toString))),
      dryRun = false, builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
    val finished = new LinkedBlockingQueue[Either[String, Seq[OutputImage]]]()
    services.queue.addListener {
      case e: JobEvent.JobFinished => finished.offer(Right(e.output.images)); ()
      case e: JobEvent.JobFailed => println(s"FAILURE: ${e.message}"); finished.offer(Left(e.message)); ()
      case _ => ()
    }
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    val report = ujson.Obj(); val checks = ujson.Obj()
    def shot(name: String): Unit = fx {
      window.scene.getRoot.applyCss(); window.scene.getRoot.layout()
      ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(name + ".png").toFile)
    }
    def check(name: String)(body: => String): Unit =
      try checks(name) = ujson.Obj("pass" -> true, "detail" -> body)
      catch case t: Throwable =>
        checks(name) = ujson.Obj("pass" -> false, "detail" -> String.valueOf(t))
        println(s"CHECK FAILED $name: $t")
    def waitIdle(): Unit =
      val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
      while (!services.queue.isIdle || fx(window.progress.get.running)) && System.nanoTime() < end do Thread.sleep(20)
    def fields(p: Path) = Infotext.parse(PngMetadata.readTexts(p)(PngMetadata.ParametersKey)).fields
    def run(tag: String, model: Path, w: Int, h: Int, steps: Int, script: String, prompt: String, form0: Map[String, String]): Seq[(Path, Map[String, String], String)] =
      waitIdle()
      fx {
        val vm = window.vms(Mode.Txt2Img)
        window.model.checkpoint.set(model.toString)
        vm.width.set(w); vm.height.set(h); vm.steps.set(steps); vm.cfgScale.set(7.0); vm.seed.set(1234L)
        vm.prompt.set(prompt); vm.negativePrompt.set("blurry")
        val plugins = window.plugins(Mode.Txt2Img)
        plugins.selectedScript.set(script)
        val form = plugins.form(script).get
        form0.foreach((k, v) => form.props(k).set(v))
        window.statusBar.generateBtn.fire()
      }
      val r = finished.poll(1500, TimeUnit.SECONDS)
      require(r != null, s"$tag timed out")
      val images = r.fold(m => throw new IllegalStateException(m), identity)
      shot(tag)
      images.zipWithIndex.map { (o, i) =>
        val dest = output.resolve(s"$tag-$i.png"); Files.copy(o.path, dest, StandardCopyOption.REPLACE_EXISTING)
        (dest, fields(dest), PngMetadata.readTexts(dest)(PngMetadata.ParametersKey))
      }
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show(); window.select(Mode.Txt2Img)
      }
      services.loadCheckpoint(sd15.toString, remember = false)
      val a = run("sd15_grid", sd15, 512, 512, 10, "infinity_grid", "1girl, upper body", Map("file" -> gridYaml.toString))
      check("grid_sd15") {
        require(a.size == 4, s"images=${a.size} (4 cells; the grid is saved under outputs/grids)")
        val gf = Files.walk(output.resolve("app-home/outputs/grids")).filter(_.toString.endsWith("-grid.png")).sorted().findFirst().get
        val g = ImageIO.read(gf.toFile)
        require(g.getWidth >= 1024 && g.getHeight >= 1024, s"grid ${g.getWidth}x${g.getHeight}")
        val prompts = a.map(_._3.linesIterator.next())
        require(prompts.toSet == Set("1girl, upper body, red hair", "1girl, upper body, blue hair"), prompts.toString)
        require(a.map(_._2("Seed")).distinct.size == 1, "one seed")
        require(a.map(_._2("Steps")).toSet == Set("6", "14"))
        s"grid ${g.getWidth}x${g.getHeight}; cells ${a.map(_._2("Infinity grid cell")).mkString(" | ")}"
      }
      waitIdle()
      services.loadCheckpoint(sdxl.toString, remember = false)
      val b = run("sdxl_grid", sdxl, 768, 768, 12, "infinity_grid", "1girl, upper body, masterpiece", Map("file" -> gridYaml.toString))
      check("grid_sdxl") { require(b.size == 4, s"images=${b.size}"); "4 cells" }
      report("checks") = checks
      report("success") = checks.obj.values.forall(_("pass").bool)
      println(report.render(indent = 2))
    finally
      report("checks") = checks
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
