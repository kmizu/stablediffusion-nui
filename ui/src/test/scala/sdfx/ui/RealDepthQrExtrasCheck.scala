package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.stage.Stage
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.OutputImage
import sdfx.engine.{AppServices, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, LinkedBlockingQueue, TimeUnit}
import javax.imageio.ImageIO

/** MANUAL gate (real scala-torch engine, GPU). Args: output-directory source-image [sd15-checkpoint [sdxl-checkpoint]].
  * Env: SDFX_SCALATORCH_ANNOTATOR_DIR, SDFX_SCALATORCH_UNET_DEVICE/VAE_DEVICE=cuda, SDFX_SCALATORCH_TOKENIZER.
  * The three plugins are img2img Script plugins (ExtrasPlugin), so this drives the real MainWindow img2img tab with the
  * script selected and the Generate button, then checks the saved images and writes screenshots plus report.json.
  */
object RealDepthQrExtrasCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)

  private val Dir = "D:/stable-diffusion-webui-reForge/models/Stable-diffusion/"

  def main(args: Array[String]): Unit =
    require(args.length >= 2, "output-directory source-image [sd15-checkpoint [sdxl-checkpoint]]")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val source = Paths.get(args(1)).toAbsolutePath
    val sd15 = Paths.get(if args.length > 2 then args(2) else Dir + "animekawa_v10.safetensors")
    val sdxl = Paths.get(if args.length > 3 then args(3) else Dir + "waiIllustriousSDXL_v170.safetensors")
    val src = ImageIO.read(source.toFile)
    val (sw, sh) = (src.getWidth, src.getHeight)
    val home = AppHome(output.resolve("app-home"))
    val services = new AppServices(home, Settings(engineBackend = "scala-torch",
      scalaTorchModel = Some(sd15.toString), scalaTorchUnetDevice = "cuda", scalaTorchVaeDevice = "cuda",
      modelDirs = ModelDirs(checkpoints = Seq(sd15.getParent.toString))), dryRun = false,
      builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
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
    val report = ujson.Obj()
    val checks = ujson.Obj()
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
    /** Selects the script, sets the props, presses Generate and returns the copied outputs. */
    def run(tag: String, script: String, props: Map[String, String]): Seq[Path] =
      waitIdle()
      fx {
        val plugins = window.plugins(Mode.Img2Img)
        plugins.selectedScript.set(script)
        val form = plugins.form(script).get
        props.foreach((k, v) => form.props(k).set(v))
        window.statusBar.generateBtn.fire()
      }
      val r = finished.poll(900, TimeUnit.SECONDS)
      require(r != null, s"$tag timed out")
      val images = r.fold(m => throw new IllegalStateException(m), identity)
      shot(tag)
      images.zipWithIndex.map { (o, i) =>
        val dest = output.resolve(s"$tag-$i.png")
        Files.copy(o.path, dest, StandardCopyOption.REPLACE_EXISTING)
        dest
      }
    def size(p: Path): (Int, Int) = { val i = ImageIO.read(p.toFile); (i.getWidth, i.getHeight) }
    def gray(i: BufferedImage, x: Int, y: Int): Int = { val c = i.getRGB(x, y); ((c >> 16 & 255) + (c >> 8 & 255) + (c & 255)) / 3 }
    def depthChecks(label: String, p: Path): String =
      val d = ImageIO.read(p.toFile)
      require((d.getWidth, d.getHeight) == (sw, sh), s"depth size ${d.getWidth}x${d.getHeight}")
      val vs = for y <- 0 until d.getHeight by 8; x <- 0 until d.getWidth by 8 yield gray(d, x, y)
      val (mn, mx) = (vs.min, vs.max)
      val mean = vs.sum.toDouble / vs.size
      require(mx - mn > 100, s"depth has no dynamic range: $mn..$mx")
      s"$label ${d.getWidth}x${d.getHeight} min=$mn max=$mx mean=${"%.1f".format(mean)}"
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(params = Map("img2img" -> GenerationParams(
          seed = 7, width = 512, height = 512, img2img = Some(Img2ImgParams(source.toString))))), CliOptions(), _ => ())
        window.show(); window.model.checkpoint.set(sd15.toString); window.select(Mode.Img2Img)
        window.vms(Mode.Img2Img).initImage.set(source.toString)
      }
      services.loadCheckpoint(sd15.toString, remember = false)
      report("source") = s"${sw}x$sh"
      shot("0-initial")

      // ---- Depth Anything V2 (SD1.5 loaded)
      val depth = run("depth-sd15", "depth_anything", Map("invert" -> "false"))
      check("depth_anything_sd15") { require(depth.size == 1, s"outputs=${depth.size}"); depthChecks("sd15", depth.head) }
      val depthInv = run("depth-sd15-inv", "depth_anything", Map("invert" -> "true"))
      check("depth_anything_inverted") {
        val a = ImageIO.read(depth.head.toFile); val b = ImageIO.read(depthInv.head.toFile)
        val diff = (0 until a.getHeight by 16).flatMap(y => (0 until a.getWidth by 16).map(x => math.abs(gray(a, x, y) + gray(b, x, y) - 255))).max
        require(diff <= 3, s"inverted is not the complement (max diff $diff)"); s"complement max diff $diff"
      }

      // ---- Stereo: three modes, depth_too on the first
      val modes = Seq("Side by side" -> (sw * 2, sh), "Over / under" -> (sw, sh * 2), "Red-cyan anaglyph" -> (sw, sh))
      val stereoOut = modes.zipWithIndex.map { case ((mode, expected), i) =>
        val outs = run(s"stereo-$i", "depth_stereo", Map("mode" -> mode, "divergence" -> "2.0", "swap" -> "false",
          "depth_too" -> (if i == 0 then "true" else "false")))
        check(s"stereo_${mode.replace(' ', '_').replace('/', '_')}") {
          require(outs.size == (if i == 0 then 2 else 1), s"outputs=${outs.size}")
          require(size(outs.head) == expected, s"size ${size(outs.head)} expected $expected")
          if i == 0 then require(size(outs(1)) == (sw, sh), "depth_too size " + size(outs(1)))
          s"$mode ${size(outs.head)} outputs=${outs.size}"
        }
        outs.head
      }
      check("stereo_parallax") {
        val sbs = ImageIO.read(stereoOut.head.toFile)
        val l = sbs.getSubimage(0, 0, sw, sh); val r = sbs.getSubimage(sw, 0, sw, sh)
        var diff = 0L
        for y <- 0 until sh by 4; x <- 0 until sw by 4 do diff += math.abs(gray(l, x, y) - gray(r, x, y))
        val meanDiff = diff.toDouble / ((sh / 4) * (sw / 4))
        require(meanDiff > 1.0, s"left and right views are identical (mean abs diff $meanDiff)")
        // best horizontal shift between eyes should be non-zero for near objects: report mean diff only
        s"mean abs luminance difference left vs right = ${"%.2f".format(meanDiff)}"
      }
      val swapped = run("stereo-swap", "depth_stereo", Map("mode" -> "Side by side", "swap" -> "true", "depth_too" -> "false"))
      check("stereo_swap_differs") {
        val a = ImageIO.read(stereoOut.head.toFile); val b = ImageIO.read(swapped.head.toFile)
        require(a.getWidth == b.getWidth)
        val d = (0 until a.getHeight by 8).flatMap(y => (0 until a.getWidth by 8).map(x => math.abs(gray(a, x, y) - gray(b, x, y)))).sum
        require(d > 0, "swap changed nothing"); s"sum abs diff $d"
      }

      // ---- QR code
      val qr = run("qr", "qr_code", Map("text" -> "https://kmizu.dev", "ecc" -> "H", "border" -> "4", "invert" -> "false"))
      check("qr_decode") {
        require(qr.size == 1 && size(qr.head) == (sw, sh), s"qr size ${qr.headOption.map(size)}")
        val img = ImageIO.read(qr.head.toFile)
        val bmp = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
          new com.google.zxing.RGBLuminanceSource(img.getWidth, img.getHeight, img.getRGB(0, 0, img.getWidth, img.getHeight, null, 0, img.getWidth))))
        val text = new com.google.zxing.qrcode.QRCodeReader().decode(bmp).getText
        require(text == "https://kmizu.dev", s"decoded '$text'"); s"decoded '$text' ${size(qr.head)}"
      }

      // ---- Depth with SDXL loaded
      if Files.isRegularFile(sdxl) then
        waitIdle()
        val t0 = System.nanoTime()
        services.loadCheckpoint(sdxl.toString, remember = false)
        report("sdxl_load_seconds") = (System.nanoTime() - t0) / 1e9
        fx { window.model.checkpoint.set(sdxl.toString) }
        val d2 = run("depth-sdxl", "depth_anything", Map("invert" -> "false"))
        check("depth_anything_sdxl") { depthChecks("sdxl", d2.head) }
        val st2 = run("stereo-sdxl", "depth_stereo", Map("mode" -> "Side by side", "depth_too" -> "false"))
        check("stereo_sdxl") { require(size(st2.head) == (sw * 2, sh), size(st2.head).toString); size(st2.head).toString }
      else checks("sdxl") = ujson.Obj("pass" -> false, "detail" -> ("missing " + sdxl))
      report("checks") = checks
      report("success") = checks.obj.values.forall(_("pass").bool)
      println(report.render(indent = 2))
    finally
      report("checks") = checks
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
