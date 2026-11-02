package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Node
import javafx.scene.control.*
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.engine.AppServices
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** MANUAL gate (real engine, no checkpoint needed). Args: python output-directory source-image second-image.
  * Drives the real MainWindow Extras tab: ControlNet Preprocessor on one image (canny pixel perfect and explicit
  * resolution, invert, recolor, chained on the result), the folder run (names, no overwrite, a corrupt file, cancel),
  * Send to ControlNet, and writes screenshots plus report.json into output-directory.
  */
object RealControlNetExtrasCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private def eventually(label: String, seconds: Int = 300)(body: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(50)
    require(body, "Timed out: " + label)

  private def png(path: Path): (Int, Int, java.util.Map[String, String]) =
    val image = ImageIO.read(path.toFile)
    val texts = new java.util.HashMap[String, String]()
    sdfx.core.png.PngMetadata.readTexts(path).foreach((k, v) => texts.put(k, v))
    (image.getWidth, image.getHeight, texts)

  def main(args: Array[String]): Unit =
    require(args.length >= 4, "python output-directory source-image second-image")
    val output = Paths.get(args(1)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val source = Paths.get(args(2)).toAbsolutePath
    val second = Paths.get(args(3)).toAbsolutePath
    val home = AppHome(output.resolve("app-home"))
    Files.createDirectories(home.pythonDir)
    val services = new AppServices(home, Settings(pythonExe = Some(args(0))), dryRun = false,
      builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
    val started = new java.util.concurrent.CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); started.countDown() })
    require(started.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    def node[A](id: String): A = fx(window.scene.lookup("#" + id).asInstanceOf[A])
    def has(id: String): Boolean = fx(window.scene.lookup("#" + id) != null)
    def visible(id: String): Boolean = fx { val n = window.scene.lookup("#" + id); n != null && n.isVisible && n.isManaged }
    def button(id: String): Button = node[Button](id)
    def label(id: String): String = fx(node[Label](id).getText)
    def shot(name: String): Unit = fx {
      val n = window.scene.lookup("#extras-cn-card")
      if n != null then n.getParent.layout()
      window.scene.getRoot.applyCss(); window.scene.getRoot.layout()
      ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(name + ".png").toFile)
    }
    def scrollTo(id: String): Unit = fx {
      val n = window.scene.lookup("#" + id)
      var p: Node = n
      while p != null && !p.isInstanceOf[ScrollPane] do p = p.getParent
      p match
        case sp: ScrollPane if n != null =>
          sp.layout()
          val content = sp.getContent
          val y = n.localToScene(0, 0).getY - content.localToScene(0, 0).getY
          val total = content.getLayoutBounds.getHeight - sp.getViewportBounds.getHeight
          if total > 0 then sp.setVvalue((y / total).max(0).min(1))
        case _ => ()
    }
    def busyDone(): Unit = eventually("card idle")(fx(button("extras-cn-cancel").isDisabled))
    def choose(name: String): Unit =
      fx(node[ComboBox[String]]("extras-cn-module").setValue(name))
      eventually("module " + name)(fx(node[ComboBox[String]]("extras-cn-module").getValue == name))
    def lastResult(): Path = { val t = label("extras-result-info"); Paths.get(t.substring(0, t.indexOf(".png") + 4)) }
    def runSingle(): Path =
      val before = label("extras-result-info")
      fx(button("extras-cn-run").fire())
      eventually("result changes")(fx(label("extras-result-info") != before && label("extras-result-info").endsWith(".png") ))
      busyDone()
      lastResult()
    val report = ujson.Obj()
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show()
        val pane = window.scene.getRoot.lookupAll(".tab-pane").toArray.collect { case t: TabPane => t }
          .find(_.getTabs.asScala.exists(_.getText == "Extras")).get
        pane.getSelectionModel.select(pane.getTabs.asScala.find(_.getText == "Extras").get)
        window.extras.image.set(source.toString)
      }
      eventually("modules listed", 120)(fx(node[ComboBox[String]]("extras-cn-module").getItems.size > 3))
      val modules = fx(node[ComboBox[String]]("extras-cn-module").getItems.asScala.toSeq)
      require(!modules.contains("none") && !modules.contains("inpaint") && modules.contains("canny") && modules.contains("invert"), modules.toString)
      report("modules") = modules.mkString(", ")
      scrollTo("extras-cn-card")
      shot("cn-0-initial")

      // ---- canny: sliders and resolution control visible; pixel perfect disables the resolution slider
      choose("canny")
      require(has("extras-cn-slider-low_threshold") && has("extras-cn-slider-high_threshold"), "canny sliders")
      require(visible("extras-cn-resolution") && visible("extras-cn-pixel-perfect"), "canny shows the resolution")
      fx(node[CheckBox]("extras-cn-pixel-perfect").setSelected(true))
      require(fx(node[Node]("extras-cn-resolution").lookup(".slider").isDisabled), "pixel perfect must disable the slider")
      val (w0, h0) = { val (w, h, _) = png(source); (w, h) }
      val canny = runSingle()
      val (cw, ch, ctext) = png(canny)
      require((cw, ch) == (w0, h0), s"pixel perfect keeps the size: $cw x $ch vs $w0 x $h0")
      val cannyParams = ctext.get("parameters")
      require(cannyParams != null && cannyParams.startsWith("ControlNet Preprocessor: \"{") && cannyParams.contains(s"'processor_res': ${math.min(w0, h0)}"), cannyParams)
      require(ctext.containsKey("Source parameters") == png(source)._3.containsKey("parameters"), "source parameters kept")
      require(canny.getParent.getFileName.toString == "extras" && canny.getFileName.toString.endsWith("-canny.png"), "single results are saved to outputs/extras: " + canny)
      report("canny_pixel_perfect") = ujson.Obj("path" -> canny.toString, "parameters" -> cannyParams)
      scrollTo("extras-cn-card")
      shot("cn-1-canny")

      // ---- explicit resolution, thresholds
      fx(node[CheckBox]("extras-cn-pixel-perfect").setSelected(false))
      val resSlider = fx(node[Node]("extras-cn-resolution").lookup(".slider").asInstanceOf[Slider])
      fx { resSlider.setValue(256); }
      val low = fx(node[Node]("extras-cn-slider-low_threshold").lookup(".slider").asInstanceOf[Slider])
      fx { low.setValue(40) }
      val c256 = runSingle()
      val (w256, h256, t256) = png(c256)
      require(math.min(w256, h256) == 256, s"resolution 256 -> $w256 x $h256")
      require(t256.get("parameters").contains("'processor_res': 256") && t256.get("parameters").contains("'threshold_a': 40"), t256.get("parameters"))
      report("canny_256_low40") = ujson.Obj("path" -> c256.toString, "size" -> s"${w256}x$h256", "parameters" -> t256.get("parameters"))

      // ---- a module without resolution hides the resolution and Pixel perfect, and its slider shows
      choose("recolor_luminance")
      require(!visible("extras-cn-resolution") && !visible("extras-cn-pixel-perfect"), "recolor has no resolution")
      require(has("extras-cn-slider-gamma_correction") && !has("extras-cn-slider-low_threshold"), "recolor slider")
      val gamma = fx(node[Node]("extras-cn-slider-gamma_correction").lookup(".slider").asInstanceOf[Slider])
      fx { gamma.setValue(2.0) }
      val recolor = runSingle()
      val (rw, rh, rt) = png(recolor)
      require((rw, rh) == (w0, h0), "recolor keeps the size")
      require(rt.get("parameters").contains("'module': 'recolor_luminance'") && rt.get("parameters").contains("'threshold_a': 2"), rt.get("parameters"))
      report("recolor_gamma2") = ujson.Obj("path" -> recolor.toString, "parameters" -> rt.get("parameters"))
      choose("invert")
      require(!has("extras-cn-slider-gamma_correction"), "invert has no sliders")
      val inv = runSingle()
      report("invert") = inv.toString

      // ---- chain: run on the current result instead of the source
      fx(node[CheckBox]("extras-cn-use-result").setSelected(true))
      choose("blur_gaussian")
      val blur = runSingle()
      val bt = png(blur)._3
      require(bt.get("Source parameters") != null && bt.get("Source parameters").contains("'module': 'invert'"),
        "the blur must have been run on the invert result: " + bt)
      report("chained_blur") = ujson.Obj("path" -> blur.toString, "source_parameters" -> bt.get("Source parameters"))
      fx(node[CheckBox]("extras-cn-use-result").setSelected(false))
      scrollTo("extras-cn-card")
      shot("cn-2-chain")

      // ---- Send to ControlNet: unit 0 of txt2img gets the image and the preprocessor becomes none
      val before = fx(window.vms(Mode.Txt2Img).controlNet(0).toUnit)
      fx(button("extras-cn-send-txt2img").fire())
      val unit = fx(window.vms(Mode.Txt2Img).controlNet(0).toUnit)
      require(unit.image.contains(blur.toString) && unit.preprocessor == "none", s"unit not updated: $unit")
      report("send_to_controlnet") = ujson.Obj("before_image" -> before.image.getOrElse("").toString, "image" -> unit.image.getOrElse(""), "preprocessor" -> unit.preprocessor)

      // ---- folder: three images, a text file and a corrupt image
      val folder = output.resolve("in-folder")
      val out = output.resolve("out-folder")
      Files.createDirectories(folder)
      Files.createDirectories(out)
      Files.copy(source, folder.resolve("a.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      Files.copy(second, folder.resolve("b.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      Files.copy(source, folder.resolve("c.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      Files.writeString(folder.resolve("notes.txt"), "keep")
      Files.writeString(folder.resolve("d.png"), "not an image")
      choose("canny")
      fx {
        node[CheckBox]("extras-cn-pixel-perfect").setSelected(true)
        node[TextField]("extras-cn-input-dir").setText(folder.toString)
        node[TextField]("extras-cn-output-dir").setText(out.toString)
        button("extras-cn-batch").fire()
      }
      eventually("batch finished")(fx(label("extras-cn-status").contains("written to")))
      val statusText = label("extras-cn-status")
      require(statusText.startsWith("3 of 4 written") && statusText.contains("1 failed") && statusText.contains("d.png"), statusText)
      val names = Files.list(out).iterator.asScala.map(_.getFileName.toString).toSeq.sorted
      require(names == Seq("a-canny.png", "b-canny.png", "c-canny.png"), names.toString)
      require(png(out.resolve("a-canny.png"))._3.get("parameters").contains("'processor_res': " + math.min(w0, h0)), "batch parameters")
      require(Files.readString(folder.resolve("notes.txt")) == "keep")
      report("batch") = ujson.Obj("status" -> statusText, "files" -> names.mkString(", "))
      // run again: nothing is overwritten
      fx(button("extras-cn-batch").fire())
      eventually("second batch idle")(fx(button("extras-cn-cancel").isDisabled))
      val names2 = Files.list(out).iterator.asScala.map(_.getFileName.toString).toSeq.sorted
      require(names2 == Seq("a-canny-1.png", "a-canny.png", "b-canny-1.png", "b-canny.png", "c-canny-1.png", "c-canny.png"), names2.toString)
      report("batch_rerun") = names2.mkString(", ")
      scrollTo("extras-cn-status")
      shot("cn-3-batch")

      // ---- same folder as output is refused
      fx {
        node[TextField]("extras-cn-output-dir").setText(folder.toString)
        button("extras-cn-batch").fire()
      }
      Thread.sleep(500)
      require(fx(button("extras-cn-cancel").isDisabled), "refused run must not start")
      require(Files.list(folder).count() == 5, "input folder must be untouched")

      // ---- cancel in the middle of a long folder, then the next single run still works
      val big = output.resolve("big-folder")
      val bigOut = output.resolve("big-out")
      Files.createDirectories(big)
      Files.createDirectories(bigOut)
      (0 until 60).foreach(i => Files.copy(source, big.resolve(f"img$i%02d.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING))
      fx {
        node[TextField]("extras-cn-input-dir").setText(big.toString)
        node[TextField]("extras-cn-output-dir").setText(bigOut.toString)
        button("extras-cn-batch").fire()
      }
      eventually("batch started")(fx(label("extras-cn-status").startsWith("1 / 60") || label("extras-cn-status").startsWith("2 / 60") || label("extras-cn-status").startsWith("3 / 60")))
      fx(button("extras-cn-cancel").fire())
      eventually("cancelled")(fx(label("extras-cn-status").contains("cancelled")))
      val written = Files.list(bigOut).count()
      require(written > 0 && written < 60, s"cancel must stop the run: $written of 60 written")
      report("cancel") = ujson.Obj("status" -> label("extras-cn-status"), "written" -> written)
      choose("invert")
      val again = runSingle()
      require(Files.isRegularFile(again), "single run after cancel")
      report("after_cancel") = again.toString
      report("success") = true
      println(report.render(indent = 2))
    finally
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx(window.dispose())
      services.shutdown()
      Platform.exit()
