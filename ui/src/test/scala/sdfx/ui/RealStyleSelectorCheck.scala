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
  * Real MainWindow txt2img with the style_selector script: SD1.5 one image per style (own JSON), SDXL combined (own CSV),
  * and an unknown style name that must fail clearly and generate nothing. No upstream style data is used.
  */
object RealStyleSelectorCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private val Dir = "D:/stable-diffusion-webui-reForge/models/Stable-diffusion/"
  private val Json =
    """[
      |  {"name": "Watercolor", "prompt": "watercolor painting of {prompt}, soft washes, paper texture", "negative_prompt": "photo, harsh lines"},
      |  {"name": "Neon noir", "prompt": "{prompt}, neon noir, night city, rain, magenta and cyan lighting", "negative_prompt": "daylight, flat"},
      |  {"name": "Pencil sketch", "prompt": "pencil sketch of {prompt}, graphite, monochrome, sketchbook", "negative_prompt": "color"},
      |  {"name": "Plain", "prompt": "{prompt}", "negative_prompt": ""}
      |]""".stripMargin
  private val Csv =
    "name,prompt,negative_prompt\r\n" +
      "Golden hour,\"{prompt}, golden hour, warm backlight\",\"overcast\"\r\n" +
      "Pixel art,\"pixel art, 16-bit, {prompt}\",\"smooth, blurry\"\r\n"
  private val BasePrompt = "1girl, smiling, upper body"
  private val BaseNeg = "lowres"

  def main(args: Array[String]): Unit =
    require(args.length >= 1, "output-directory [sd15 [sdxl]]")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val sd15 = Paths.get(if args.length > 1 then args(1) else Dir + "animekawa_v10.safetensors")
    val sdxl = Paths.get(if args.length > 2 then args(2) else Dir + "waiIllustriousSDXL_v170.safetensors")
    val json = output.resolve("my_styles.json"); val csv = output.resolve("my_styles.csv")
    Files.writeString(json, Json); Files.writeString(csv, Csv)
    val home = AppHome(output.resolve("app-home"))
    val services = new AppServices(home, Settings(engineBackend = "scala-torch", scalaTorchModel = Some(sd15.toString),
      scalaTorchUnetDevice = "cuda", scalaTorchVaeDevice = "cuda", modelDirs = ModelDirs(checkpoints = Seq(sd15.getParent.toString))),
      dryRun = false, builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
    val finished = new LinkedBlockingQueue[Either[String, Seq[OutputImage]]]()
    services.queue.addListener {
      case e: JobEvent.JobFinished => finished.offer(Right(e.output.images)); ()
      case e: JobEvent.JobFailed => println(s"JOB FAILED: ${e.message}"); finished.offer(Left(e.message)); ()
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
    def start(model: Path, w: Int, h: Int, steps: Int, file: Path, styles: String, mode: String): Unit =
      waitIdle()
      fx {
        val vm = window.vms(Mode.Txt2Img)
        window.model.checkpoint.set(model.toString)
        vm.width.set(w); vm.height.set(h); vm.steps.set(steps); vm.cfgScale.set(7.0); vm.seed.set(99L)
        vm.prompt.set(BasePrompt); vm.negativePrompt.set(BaseNeg)
        val plugins = window.plugins(Mode.Txt2Img)
        plugins.selectedScript.set("style_selector")
        val form = plugins.form("style_selector").get
        Map("styles_file" -> file.toString, "styles" -> styles, "mode" -> mode, "list_only" -> "false").foreach((k, v) => form.props(k).set(v))
        window.statusBar.generateBtn.fire()
      }
    def run(tag: String, model: Path, w: Int, h: Int, steps: Int, file: Path, styles: String, mode: String): Seq[(Path, Map[String, String], String)] =
      start(model, w, h, steps, file, styles, mode)
      val r = finished.poll(1500, TimeUnit.SECONDS)
      require(r != null, s"$tag timed out")
      val images = r.fold(m => throw new IllegalStateException(m), identity)
      shot(tag)
      images.zipWithIndex.map { (o, i) =>
        val dest = output.resolve(s"$tag-$i.png"); Files.copy(o.path, dest, StandardCopyOption.REPLACE_EXISTING)
        val text = PngMetadata.readTexts(dest)(PngMetadata.ParametersKey)
        (dest, Infotext.parse(text).fields, text)
      }
    def negOf(text: String): String = text.linesIterator.find(_.startsWith("Negative prompt: ")).map(_.stripPrefix("Negative prompt: ")).getOrElse("")
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show(); window.select(Mode.Txt2Img)
      }
      services.loadCheckpoint(sd15.toString, remember = false)

      // (1) SD1.5, JSON, one image per style
      val a = run("sd15", sd15, 512, 512, 12, json, "Watercolor, Neon noir, Pencil sketch", "One image per style")
      val wantA = Seq(
        ("Watercolor", s"watercolor painting of $BasePrompt, soft washes, paper texture", "photo, harsh lines"),
        ("Neon noir", s"$BasePrompt, neon noir, night city, rain, magenta and cyan lighting", "daylight, flat"),
        ("Pencil sketch", s"pencil sketch of $BasePrompt, graphite, monochrome, sketchbook", "color"))
      check("sd15_count") { require(a.size == 3, s"images=${a.size}"); "3 images" }
      a.zip(wantA).zipWithIndex.foreach { case (((p, f, text), (label, prompt, neg)), i) =>
        check(s"sd15_$i") {
          require(f("Style selector") == label, "label " + f.get("Style selector"))
          require(text.linesIterator.next() == prompt, s"prompt '${text.linesIterator.next()}'")
          require(negOf(text) == s"$neg, $BaseNeg" || negOf(text).contains(neg), s"neg '${negOf(text)}'")
          s"$label | ${text.linesIterator.next()} | neg=${negOf(text)}"
        }
      }
      check("sd15_images_differ") {
        val imgs = a.map(x => ImageIO.read(x._1.toFile))
        def mean(i: java.awt.image.BufferedImage) = (for y <- 0 until i.getHeight by 8; x <- 0 until i.getWidth by 8 yield { val c = i.getRGB(x, y); (c >> 16 & 255) + (c >> 8 & 255) + (c & 255) }).sum / 3.0 / ((i.getHeight / 8) * (i.getWidth / 8))
        val m = imgs.map(mean)
        require(m.zip(m.tail).forall((x, y) => math.abs(x - y) > 0.5), s"means $m"); s"mean luminance ${m.map(d => f"$d%.1f").mkString(", ")}"
      }

      // (2) SDXL, CSV, combined
      waitIdle()
      val t0 = System.nanoTime()
      services.loadCheckpoint(sdxl.toString, remember = false)
      report("sdxl_load_seconds") = (System.nanoTime() - t0) / 1e9
      val b = run("sdxl", sdxl, 768, 768, 6, csv, "Golden hour, Pixel art", "Apply all selected styles to one image")
      check("sdxl_combined") {
        require(b.size == 1, s"images=${b.size}")
        val (_, f, text) = b.head
        require(f("Style selector") == "Golden hour + Pixel art", f.get("Style selector").toString)
        val want = s"pixel art, 16-bit, $BasePrompt, golden hour, warm backlight"
        require(text.linesIterator.next() == want, s"prompt '${text.linesIterator.next()}' != '$want'")
        require(negOf(text).contains("smooth, blurry") && negOf(text).contains("overcast"), "neg " + negOf(text))
        s"${text.linesIterator.next()} | neg=${negOf(text)}"
      }

      // (3) unknown style name fails clearly, nothing generated
      waitIdle()
      val before = output.resolve("app-home").toFile
      start(sdxl, 768, 768, 6, csv, "Golden hour, No such style", "One image per style")
      val bad = finished.poll(120, TimeUnit.SECONDS)
      check("unknown_style") {
        require(bad != null, "no completion event")
        require(bad.isLeft, "unknown style must fail the job, got images " + bad.toOption.map(_.size))
        val msg = bad.left.toOption.get
        require(msg.contains("Style 'No such style' not found"), "message: " + msg)
        waitIdle(); require(finished.isEmpty, "extra completion after failure")
        s"failed with: $msg"
      }
      shot("unknown-style")
      report("checks") = checks
      report("success") = checks.obj.values.forall(_("pass").bool)
      println(report.render(indent = 2))
    finally
      report("checks") = checks
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
