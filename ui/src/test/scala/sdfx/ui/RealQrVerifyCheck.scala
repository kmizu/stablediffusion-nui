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

/** MANUAL gate (real scala-torch engine, GPU). Args: output-directory source-image [sd15 [sdxl]].
  * Real MainWindow img2img: qr_code makes a QR, qr_verify reads it, the QR is the init image of real SD1.5 (and SDXL)
  * generations at two denoising strengths whose results are verified again; a photo-like image must not be readable and
  * fail_if_unreadable must fail the job. Readability of the generated art is recorded as it is, not asserted.
  */
object RealQrVerifyCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private val Dir = "D:/stable-diffusion-webui-reForge/models/Stable-diffusion/"
  private val Url = "https://kmizu.dev"

  def main(args: Array[String]): Unit =
    require(args.length >= 2, "output-directory source-image [sd15 [sdxl]]")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val source = Paths.get(args(1)).toAbsolutePath
    val sd15 = Paths.get(if args.length > 2 then args(2) else Dir + "animekawa_v10.safetensors")
    val sdxl = Paths.get(if args.length > 3 then args(3) else Dir + "waiIllustriousSDXL_v170.safetensors")
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
    val report = ujson.Obj(); val checks = ujson.Obj(); val recorded = ujson.Obj()
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
    /** Runs one script over an init image in the img2img tab and returns the raw outcome. */
    def go(model: Path, init: Path, w: Int, h: Int, steps: Int, denoise: Double, prompt: String, script: String,
        props: Map[String, String]): Either[String, Seq[OutputImage]] =
      waitIdle()
      fx {
        val vm = window.vms(Mode.Img2Img)
        window.model.checkpoint.set(model.toString)
        vm.initImage.set(init.toString)
        vm.width.set(w); vm.height.set(h); vm.steps.set(steps); vm.cfgScale.set(7.0); vm.seed.set(2024L)
        vm.denoise.set(denoise); vm.prompt.set(prompt); vm.negativePrompt.set("lowres, worst quality")
        val plugins = window.plugins(Mode.Img2Img)
        plugins.selectedScript.set(script)
        if script.nonEmpty then
          val form = plugins.form(script).get
          props.foreach((k, v) => form.props(k).set(v))
        window.statusBar.generateBtn.fire()
      }
      val r = finished.poll(1500, TimeUnit.SECONDS)
      require(r != null, s"$script timed out")
      r
    def ok(r: Either[String, Seq[OutputImage]], dest: Path): Path =
      val images = r.fold(m => throw new IllegalStateException(m), identity)
      require(images.size == 1, s"images=${images.size}")
      Files.copy(images.head.path, dest, StandardCopyOption.REPLACE_EXISTING); dest
    def qrStatus(p: Path): String =
      Infotext.parse(PngMetadata.readTexts(p)(PngMetadata.ParametersKey)).fields.getOrElse("QR scan check", "<missing>")
    def verify(tag: String, model: Path, p: Path, size: Int, props: Map[String, String] = Map("expected" -> Url, "fail_if_unreadable" -> "false")): String =
      val out = ok(go(model, p, size, size, 1, 0.0, "x", "qr_verify", props), output.resolve(tag + "-verified.png"))
      qrStatus(out)
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show(); window.select(Mode.Img2Img)
      }
      services.loadCheckpoint(sd15.toString, remember = false)

      // (1) QR from the 768x768 source
      val qr = output.resolve("qr.png")
      check("qr_made") {
        ok(go(sd15, source, 768, 768, 1, 0.0, "x", "qr_code", Map("text" -> Url, "ecc" -> "H", "border" -> "4", "invert" -> "false")), qr)
        val i = ImageIO.read(qr.toFile); s"${i.getWidth}x${i.getHeight}"
      }
      shot("qr-made")
      // (2) verify the plain QR
      check("verify_plain_qr") {
        val s = verify("qr", sd15, qr, 768); require(s.startsWith("readable") && s.endsWith(Url), s); s
      }
      // (4) a photo-like image is not readable; fail_if_unreadable fails clearly
      check("verify_photo_not_readable") {
        val s = verify("photo", sd15, source, 768, Map("expected" -> "", "fail_if_unreadable" -> "false"))
        require(s == "not readable", s); s
      }
      check("fail_if_unreadable") {
        val r = go(sd15, source, 768, 768, 1, 0.0, "x", "qr_verify", Map("expected" -> Url, "fail_if_unreadable" -> "true"))
        require(r.isLeft, "job must fail, got images")
        require(r.left.toOption.get.contains("QR check failed: not readable"), "message: " + r.left.toOption.get)
        r.left.toOption.get
      }
      shot("fail-unreadable")

      // (3) QR as init image of real generations, then verify (readable or not is recorded)
      val village = "a medieval village seen from above, rivers, fields, detailed"
      def art(tag: String, model: Path, size: Int, steps: Int, denoise: Double): Unit =
        check(tag) {
          val img = ok(go(model, qr, size, size, steps, denoise, village, "", Map.empty), output.resolve(tag + ".png"))
          val s = verify(tag, model, img, size)
          recorded(tag) = ujson.Obj("denoise" -> denoise, "size" -> size, "steps" -> steps, "qr_scan_check" -> s)
          s"denoise $denoise: $s"
        }
      art("sd15-d035", sd15, 512, 20, 0.35)
      art("sd15-d060", sd15, 512, 20, 0.6)
      waitIdle()
      val t0 = System.nanoTime()
      services.loadCheckpoint(sdxl.toString, remember = false)
      report("sdxl_load_seconds") = (System.nanoTime() - t0) / 1e9
      art("sdxl-d040", sdxl, 768, 20, 0.4)
      report("recorded") = recorded
      report("checks") = checks
      report("success") = checks.obj.values.forall(_("pass").bool)
      println(report.render(indent = 2))
    finally
      report("checks") = checks
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
