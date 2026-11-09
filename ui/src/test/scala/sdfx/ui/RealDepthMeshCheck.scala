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

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, LinkedBlockingQueue, TimeUnit}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*

/** MANUAL gate (real scala-torch engine, GPU). Args: output-directory source-image [sd15 [sdxl]].
  * Real MainWindow img2img with the depth_mesh script: two runs with the same name on SD1.5 (the second must become
  * `-1`), a run with an empty folder (must fail clearly) and a run on SDXL (next free name). The OBJ files are
  * then inspected independently by an external script; this check only validates the files exist and are non-empty.
  */
object RealDepthMeshCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private val Dir = "D:/stable-diffusion-webui-reForge/models/Stable-diffusion/"

  def main(args: Array[String]): Unit =
    require(args.length >= 2, "output-directory source-image [sd15 [sdxl]]")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val source = Paths.get(args(1)).toAbsolutePath
    val sd15 = Paths.get(if args.length > 2 then args(2) else Dir + "animekawa_v10.safetensors")
    val sdxl = Paths.get(if args.length > 3 then args(3) else Dir + "waiIllustriousSDXL_v170.safetensors")
    val meshDir = output.resolve("mesh")
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
    def check(name: String)(body: => String): Unit =
      try checks(name) = ujson.Obj("pass" -> true, "detail" -> body)
      catch case t: Throwable =>
        checks(name) = ujson.Obj("pass" -> false, "detail" -> String.valueOf(t))
        println(s"CHECK FAILED $name: $t")
    def waitIdle(): Unit =
      val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
      while (!services.queue.isIdle || fx(window.progress.get.running)) && System.nanoTime() < end do Thread.sleep(20)
    def go(model: Path, folder: String, name: String): Either[String, Seq[OutputImage]] =
      waitIdle()
      fx {
        val vm = window.vms(Mode.Img2Img)
        window.model.checkpoint.set(model.toString)
        vm.initImage.set(source.toString)
        vm.width.set(768); vm.height.set(768); vm.steps.set(1); vm.seed.set(1L)
        val plugins = window.plugins(Mode.Img2Img)
        plugins.selectedScript.set("depth_mesh")
        val form = plugins.form("depth_mesh").get
        Map("folder" -> folder, "name" -> name, "step" -> "4", "depth_scale" -> "0.4", "edge" -> "0.08").foreach((k, v) => form.props(k).set(v))
        window.statusBar.generateBtn.fire()
      }
      val r = finished.poll(900, TimeUnit.SECONDS)
      require(r != null, "depth_mesh timed out")
      r
    def meshInfo(r: Either[String, Seq[OutputImage]]): String =
      val images = r.fold(m => throw new IllegalStateException(m), identity)
      require(images.size == 1, s"images=${images.size}")
      Infotext.parse(PngMetadata.readTexts(images.head.path)(PngMetadata.ParametersKey)).fields("Depth mesh")
    def files(): Seq[String] =
      if Files.isDirectory(meshDir) then scala.util.Using.resource(Files.list(meshDir))(_.iterator.asScala.map(_.getFileName.toString).toSeq.sorted) else Nil
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show(); window.select(Mode.Img2Img)
      }
      services.loadCheckpoint(sd15.toString, remember = false)
      check("sd15_first") { val i = meshInfo(go(sd15, meshDir.toString, "relief")); require(i.startsWith("relief.obj"), i); i }
      val firstBytes = Files.readAllBytes(meshDir.resolve("relief.obj"))
      check("sd15_second_not_overwritten") {
        val i = meshInfo(go(sd15, meshDir.toString, "relief")); require(i.startsWith("relief-1.obj"), i)
        require(java.util.Arrays.equals(firstBytes, Files.readAllBytes(meshDir.resolve("relief.obj"))), "first obj changed")
        i
      }
      check("missing_folder_fails") {
        val before = files()
        val r = go(sd15, "", "relief")
        require(r.isLeft && r.left.toOption.get.contains("An output folder is required"), String.valueOf(r))
        require(files() == before, "files changed on failure")
        r.left.toOption.get
      }
      waitIdle()
      services.loadCheckpoint(sdxl.toString, remember = false)
      check("sdxl_next_free_name") { val i = meshInfo(go(sdxl, meshDir.toString, "relief")); require(i.startsWith("relief-2.obj"), i); i }
      check("files_present") {
        val f = files()
        require(f == Seq("relief-1.mtl", "relief-1.obj", "relief-1.png", "relief-2.mtl", "relief-2.obj", "relief-2.png", "relief.mtl", "relief.obj", "relief.png"), f.toString)
        require(f.forall(n => Files.size(meshDir.resolve(n)) > 0), "empty file")
        f.mkString(", ")
      }
      fx {
        window.scene.getRoot.applyCss(); window.scene.getRoot.layout()
        ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve("window.png").toFile)
      }
      report("checks") = checks
      report("success") = checks.obj.values.forall(_("pass").bool)
      println(report.render(indent = 2))
    finally
      report("checks") = checks
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
