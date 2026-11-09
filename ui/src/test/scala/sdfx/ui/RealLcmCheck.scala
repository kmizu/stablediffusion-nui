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

/** MANUAL gate (real scala-torch engine, GPU). Args: output-directory [sdxl-checkpoint [lora-directory]].
  * Real MainWindow txt2img on SDXL: LCM sampler + LCM LoRA at 4 steps, a 20-step Euler a control and LCM without the LoRA.
  */
object RealLcmCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)

  def main(args: Array[String]): Unit =
    require(args.length >= 1, "output-directory [sdxl-checkpoint [lora-directory]]")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val sdxl = Paths.get(if args.length > 1 then args(1) else "D:/stable-diffusion-webui-reForge/models/Stable-diffusion/waiIllustriousSDXL_v170.safetensors")
    val loraDir = if args.length > 2 then args(2) else "D:/stable-diffusion-webui-reForge/models/Lora"
    val home = AppHome(output.resolve("app-home"))
    val services = new AppServices(home, Settings(engineBackend = "scala-torch", scalaTorchModel = Some(sdxl.toString),
      scalaTorchUnetDevice = "cuda", scalaTorchVaeDevice = "cuda",
      modelDirs = ModelDirs(checkpoints = Seq(sdxl.getParent.toString), lora = Seq(loraDir))),
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
    val report = ujson.Obj()
    def waitIdle(): Unit =
      val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
      while (!services.queue.isIdle || fx(window.progress.get.running)) && System.nanoTime() < end do Thread.sleep(20)
    def run(tag: String, prompt: String, sampler: String, scheduler: String, steps: Int, cfg: Double): Unit =
      waitIdle()
      fx {
        val vm = window.vms(Mode.Txt2Img)
        window.plugins(Mode.Txt2Img).selectedScript.set("")
        vm.width.set(1024); vm.height.set(1024); vm.seed.set(4242L)
        vm.prompt.set(prompt); vm.negativePrompt.set("lowres, worst quality")
        vm.sampler.set(sampler); vm.scheduler.set(scheduler); vm.steps.set(steps); vm.cfgScale.set(cfg)
        window.statusBar.generateBtn.fire()
      }
      val t0 = System.nanoTime()
      val r = finished.poll(1700, TimeUnit.SECONDS)
      val secs = (System.nanoTime() - t0) / 1e9
      val entry = ujson.Obj("seconds" -> secs)
      report(tag) = entry
      require(r != null, s"$tag timed out")
      val images = r.fold(m => throw new IllegalStateException(m), identity)
      require(images.size == 1, s"$tag images=${images.size}")
      val dest = output.resolve(tag + ".png"); Files.copy(images.head.path, dest, StandardCopyOption.REPLACE_EXISTING)
      val text = PngMetadata.readTexts(dest)(PngMetadata.ParametersKey)
      val f = Infotext.parse(text).fields
      entry("prompt") = text.linesIterator.next()
      entry("sampler") = f.getOrElse("Sampler", ""); entry("schedule") = f.getOrElse("Schedule type", "")
      entry("steps") = f.getOrElse("Steps", ""); entry("cfg") = f.getOrElse("CFG scale", "")
      entry("lora_fields") = f.filter((k, _) => k.toLowerCase.contains("lora")).map((k, v) => s"$k: $v").mkString(" | ")
      entry("size") = { val i = ImageIO.read(dest.toFile); s"${i.getWidth}x${i.getHeight}" }
      println(s"$tag: ${entry.render()}")
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show(); window.select(Mode.Txt2Img)
        window.model.checkpoint.set(sdxl.toString)
      }
      services.loadCheckpoint(sdxl.toString, remember = false)
      val base = "1girl, smiling, masterpiece"
      run("lcm-lora-4", base + " <lora:LCM_LoRA_Weights:1.0>", "LCM", "Simple", 4, 1.5)
      run("euler-a-20", base, "Euler a", "Automatic", 20, 7.0)
      run("lcm-nolora-4", base, "LCM", "Simple", 4, 1.5)
      fx {
        window.scene.getRoot.applyCss(); window.scene.getRoot.layout()
        ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve("window.png").toFile)
      }
      report("success") = true
      println(report.render(indent = 2))
    finally
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
