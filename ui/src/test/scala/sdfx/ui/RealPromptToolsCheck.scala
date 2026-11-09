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
object RealPromptToolsCheck:
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
    val lib = output.resolve("lib.yaml"); val scen = output.resolve("scenario.yaml"); val corpus = output.resolve("corpus.txt")
    Files.writeString(lib, "Look:\n  hair: \"silver hair, long hair\"\n  bad:\n    value: \"ugly\"\n    negative: \"lowres, blurry\"\n")
    Files.writeString(scen, "base: \"masterpiece, $" + "{hero}\"\nvariables:\n  hero: \"1girl, solo\"\nscenes:\n  - name: dawn\n    prompt: \"sunrise over the sea\"\n    steps: 6\n  - name: night\n    prompt: \"starry sky\"\n    steps: 4\n    hero: \"1boy, solo\"\n")
    Files.writeString(corpus, "1girl, smile, outdoors, sky\n1girl, hat, outdoors, flowers\n1girl, smile, flowers, wind\n")
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
      val a = run("sd15_library", sd15, 512, 512, 10, "prompt_library", "1girl, [[hair]], [[Look/bad]]", Map("file" -> lib.toString))
      check("library") {
        require(a.size == 1)
        val (_, f, text) = a.head
        val lines = text.linesIterator.toSeq
        require(lines.head == "1girl, silver hair, long hair, ugly", lines.head)
        require(lines.exists(_.contains("Negative prompt: blurry, lowres, blurry")), lines.mkString("|"))
        require(f("Script") == "Prompt library", f.toString)
        lines.head
      }
      val g = run("sd15_generator", sd15, 512, 512, 10, "prompt_generator", "1girl", Map("corpus" -> corpus.toString, "count" -> "2", "new_tags" -> "3", "seed" -> "7"))
      check("generator") {
        require(g.size == 2, s"images=${g.size}")
        val ps = g.map(_._3.linesIterator.next())
        require(ps.forall(_.startsWith("1girl, ")) && ps.forall(_.split(",").length >= 2), ps.toString)
        ps.mkString(" || ")
      }
      waitIdle()
      val t0 = System.nanoTime()
      services.loadCheckpoint(sdxl.toString, remember = false)
      report("sdxl_load_seconds") = (System.nanoTime() - t0) / 1e9
      val b = run("sdxl_scenario", sdxl, 768, 768, 20, "scenario_loader", "", Map("file" -> scen.toString))
      check("scenario") {
        require(b.size == 2, s"images=${b.size}")
        val ps = b.map(_._3.linesIterator.next())
        require(ps == Seq("masterpiece, 1girl, solo, sunrise over the sea", "masterpiece, 1boy, solo, starry sky"), ps.toString)
        require(b.map(_._2("Steps")) == Seq("6", "4"), b.map(_._2("Steps")).toString)
        require(b.map(_._2("Scenario scene")) == Seq("dawn", "night"))
        ps.mkString(" || ")
      }
      report("checks") = checks
      report("success") = checks.obj.values.forall(_("pass").bool)
      println(report.render(indent = 2))
    finally
      report("checks") = checks
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
