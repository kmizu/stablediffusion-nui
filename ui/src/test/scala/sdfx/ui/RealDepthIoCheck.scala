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
object RealDepthIoCheck:
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

      val lib = output.resolve("library"); val exp = output.resolve("export")
      // library: save -> list -> load
      val saved = run("lib-save", "depth_library", Map("folder" -> lib.toString, "operation" -> "Save", "name" -> "meadow"))
      check("library_save") {
        require(saved.size == 1 && Files.isRegularFile(lib.resolve("meadow.png")), "no saved depth map")
        depthChecks("saved depth preview", saved.head)
      }
      val loaded = run("lib-load", "depth_library", Map("folder" -> lib.toString, "operation" -> "Load", "name" -> "meadow"))
      check("library_load") {
        require(size(loaded.head) == (sw, sh), s"size ${size(loaded.head)}")
        val a = ImageIO.read(saved.head.toFile); val b = ImageIO.read(loaded.head.toFile)
        val diff = (0 until a.getHeight by 16).flatMap(y => (0 until a.getWidth by 16).map(x => math.abs(gray(a, x, y) - gray(b, x, y)))).max
        require(diff <= 2, s"loaded map differs from the saved one (max diff $diff)"); s"max diff $diff"
      }
      // export 16-bit, then import it back (inverted)
      val ex = run("io-export", "depth_image_io", Map("operation" -> "Export 16-bit depth PNG", "folder" -> exp.toString, "name" -> "d16"))
      check("export_16bit") {
        val f = exp.resolve("d16.png"); require(Files.isRegularFile(f), "no export")
        val im = ImageIO.read(f.toFile)
        require(im.getColorModel.getPixelSize == 16, s"pixel size ${im.getColorModel.getPixelSize}")
        s"16-bit ${im.getWidth}x${im.getHeight}"
      }
      val im1 = run("io-import", "depth_image_io", Map("operation" -> "Import depth file", "file" -> exp.resolve("d16.png").toString, "invert" -> "false"))
      val im2 = run("io-import-inv", "depth_image_io", Map("operation" -> "Import depth file", "file" -> exp.resolve("d16.png").toString, "invert" -> "true"))
      check("import_roundtrip") {
        require(size(im1.head) == (sw, sh), s"size ${size(im1.head)}")
        val a = ImageIO.read(im1.head.toFile); val b = ImageIO.read(im2.head.toFile)
        val diff = (0 until a.getHeight by 16).flatMap(y => (0 until a.getWidth by 16).map(x => math.abs(gray(a, x, y) + gray(b, x, y) - 255))).max
        require(diff <= 3, s"invert is not the complement ($diff)"); s"import ok, invert complement diff $diff"
      }
      report("checks") = checks
      report("success") = checks.obj.values.forall(_("pass").bool)
      println(report.render(indent = 2))
    finally
      report("checks") = checks
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
