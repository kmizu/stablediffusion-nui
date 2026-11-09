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
  * Drives the real MainWindow txt2img tab with the prompt_gallery script: SD1.5 one image per set, SDXL combined.
  */
object RealPromptGalleryCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private val Dir = "D:/stable-diffusion-webui-reForge/models/Stable-diffusion/"
  private val Tags =
    """Hair:
      |  Red:
      |    red-twintails:
      |      value: "red hair, twintails"
      |      negative: "lowres, bad anatomy"
      |  Blue:
      |    blue-short:
      |      value: "blue hair, short hair"
      |      param: "Steps: 8, CFG scale: 5"
      |Face:
      |  smile:
      |    value: "smile, open mouth"
      |Style:
      |  Soft:
      |    watercolor:
      |      value: "watercolor, pastel colors"
      |""".stripMargin
  private val Avatars =
    """schoolGirl:
      |  value: "1girl, solo, school uniform, upper body"
      |  negative: "worst quality"
      |""".stripMargin

  def main(args: Array[String]): Unit =
    require(args.length >= 1, "output-directory [sd15 [sdxl]]")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val sd15 = Paths.get(if args.length > 1 then args(1) else Dir + "animekawa_v10.safetensors")
    val sdxl = Paths.get(if args.length > 2 then args(2) else Dir + "waiIllustriousSDXL_v170.safetensors")
    val tags = output.resolve("tags.yaml"); val avatars = output.resolve("avatars.yaml")
    Files.writeString(tags, Tags); Files.writeString(avatars, Avatars)
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
    def run(tag: String, model: Path, w: Int, h: Int, steps: Int, mode: String, sets: String): Seq[(Path, Map[String, String], String)] =
      waitIdle()
      fx {
        val vm = window.vms(Mode.Txt2Img)
        window.model.checkpoint.set(model.toString)
        vm.width.set(w); vm.height.set(h); vm.steps.set(steps); vm.cfgScale.set(7.0); vm.seed.set(1234L)
        vm.prompt.set("masterpiece, best quality"); vm.negativePrompt.set("blurry")
        val plugins = window.plugins(Mode.Txt2Img)
        plugins.selectedScript.set("prompt_gallery")
        val form = plugins.form("prompt_gallery").get
        Map("tags_file" -> tags.toString, "avatars_file" -> avatars.toString, "avatar" -> "schoolGirl", "sets" -> sets,
          "mode" -> mode, "use_param" -> "true").foreach((k, v) => form.props(k).set(v))
        window.statusBar.generateBtn.fire()
      }
      val r = finished.poll(1500, TimeUnit.SECONDS)
      require(r != null, s"$tag timed out")
      val images = r.fold(m => throw new IllegalStateException(m), identity)
      shot(tag)
      images.zipWithIndex.map { (o, i) =>
        val dest = output.resolve(s"$tag-$i.png"); Files.copy(o.path, dest, StandardCopyOption.REPLACE_EXISTING)
        val text = PngMetadata.readTexts(dest)(PngMetadata.ParametersKey)
        (dest, fields(dest), text)
      }
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show(); window.select(Mode.Txt2Img)
      }
      services.loadCheckpoint(sd15.toString, remember = false)
      val a = run("sd15", sd15, 512, 512, 12, "One image per prompt-set", "red-twintails, blue-short, watercolor")
      check("sd15_count") { require(a.size == 3, s"images=${a.size}"); "3 images" }
      val expect = Seq("Hair/Red/red-twintails" -> ("red hair, twintails", "12", "7"), "Hair/Blue/blue-short" -> ("blue hair, short hair", "8", "5"),
        "Style/Soft/watercolor" -> ("watercolor, pastel colors", "12", "7"))
      a.zip(expect).zipWithIndex.foreach { case (((p, f, text), (set, (value, steps, cfg))), i) =>
        check(s"sd15_$i") {
          require(f("Prompt gallery set") == set, "set label " + f.get("Prompt gallery set"))
          val prompt = text.linesIterator.next()
          val want = s"1girl, solo, school uniform, upper body, $value, masterpiece, best quality"
          require(prompt == want, s"prompt '$prompt' != '$want'")
          require(f("Steps") == steps && f("CFG scale").toDouble == cfg.toDouble, s"steps=${f("Steps")} cfg=${f("CFG scale")}")
          require(ImageIO.read(p.toFile).getWidth == 512, "size")
          s"$set steps=${f("Steps")} cfg=${f("CFG scale")} prompt='$prompt'"
        }
      }
      waitIdle()
      val t0 = System.nanoTime()
      services.loadCheckpoint(sdxl.toString, remember = false)
      report("sdxl_load_seconds") = (System.nanoTime() - t0) / 1e9
      val b = run("sdxl", sdxl, 768, 768, 4, "Combine selected into one image", "red-twintails, blue-short")
      check("sdxl_combined") {
        require(b.size == 1, s"images=${b.size}")
        val (p, f, text) = b.head
        require(f("Prompt gallery set") == "Hair/Red/red-twintails + Hair/Blue/blue-short", f.get("Prompt gallery set").toString)
        val prompt = text.linesIterator.next()
        require(prompt == "1girl, solo, school uniform, upper body, red hair, twintails, blue hair, short hair, masterpiece, best quality", prompt)
        require(f("Steps") == "8" && f("CFG scale").toDouble == 5.0, s"steps=${f("Steps")} cfg=${f("CFG scale")} (last set with param)")
        require(ImageIO.read(p.toFile).getWidth == 768, "size")
        s"steps=${f("Steps")} cfg=${f("CFG scale")} prompt='$prompt'"
      }
      report("checks") = checks
      report("success") = checks.obj.values.forall(_("pass").bool)
      println(report.render(indent = 2))
    finally
      report("checks") = checks
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
