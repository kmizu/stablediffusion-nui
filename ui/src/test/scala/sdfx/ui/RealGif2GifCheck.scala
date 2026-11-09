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
object RealGif2GifCheck:
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
    def run(tag: String, model: Path, script: String, props: Map[String, String], init: Path): Seq[(Path, Map[String, String])] =
      waitIdle()
      fx {
        val vm = window.vms(Mode.Img2Img)
        window.model.checkpoint.set(model.toString)
        vm.initImage.set(init.toString)
        vm.width.set(512); vm.height.set(512); vm.steps.set(12); vm.cfgScale.set(7.0); vm.seed.set(77L)
        vm.denoise.set(0.5)
        vm.prompt.set("1girl, anime, watercolor"); vm.negativePrompt.set("blurry")
        val plugins = window.plugins(Mode.Img2Img)
        plugins.selectedScript.set(script)
        val form = plugins.form(script).get
        props.foreach((k, v) => form.props(k).set(v))
        window.statusBar.generateBtn.fire()
      }
      val r = finished.poll(1500, TimeUnit.SECONDS)
      require(r != null, s"$tag timed out")
      val images = r.fold(m => throw new IllegalStateException(m), identity)
      shot(tag)
      images.zipWithIndex.map { (o, i) =>
        val dest = output.resolve(s"$tag-$i.png"); Files.copy(o.path, dest, StandardCopyOption.REPLACE_EXISTING)
        (dest, fields(dest))
      }
    try
      val srcs = args.drop(3).map(Paths.get(_))
      require(srcs.length >= 2, "pass at least two source PNGs after output sd15 sdxl")
      val gifIn = output.resolve("in.gif")
      def scaled(p: Path) = { val i = ImageIO.read(p.toFile); val o = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB); o.getGraphics.drawImage(i, 0, 0, 256, 256, null); o }
      sdfx.plugins.GifFrames.write(gifIn, srcs.toSeq.map(p => sdfx.plugins.GifFrames.Frame(scaled(p), 12)))
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show(); window.select(Mode.Img2Img)
      }
      services.loadCheckpoint(sd15.toString, remember = false)
      val outDir = output.resolve("gifout")
      val a = run("sd15_gif", sd15, "gif2gif", Map("gif" -> gifIn.toString, "folder" -> outDir.toString), srcs.head)
      check("gif2gif_sd15") {
        require(a.size == srcs.length, s"images=${a.size}")
        require(a.forall(_._2("Script") == "gif2gif"))
        val g = outDir.resolve("gif2gif.gif")
        require(Files.exists(g), "no output gif")
        val fr = sdfx.plugins.GifFrames.read(g, 100)
        require(fr.size == srcs.length && fr.head.delayCs == 12, s"frames=${fr.size} delay=${fr.head.delayCs}")
        require(a.map(_._2("Seed")).distinct.size == 1, "seed must be fixed")
        s"${fr.size} frames, ${fr.head.image.getWidth}x${fr.head.image.getHeight}, delay ${fr.head.delayCs}"
      }
      report("checks") = checks
      report("success") = checks.obj.values.forall(_("pass").bool)
      println(report.render(indent = 2))
    finally
      report("checks") = checks
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
