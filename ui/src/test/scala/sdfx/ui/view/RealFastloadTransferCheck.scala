package sdfx.ui.view

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.{Button, Label, TextField}
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.core.png.ScrambledPng
import sdfx.engine.AppServices
import sdfx.ui.vm.{CatalogVM, FastloadApply, GenerationVM, Mode}
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}

/** Native worker/UI smoke using a locally generated, representable CNI fixture. */
object RealFastloadTransferCheck:
  def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(30, TimeUnit.SECONDS)

  def main(args: Array[String]): Unit =
    require(args.length == 3, "expected root, Python executable and synthetic CNI")
    val root = Path.of(args(0)).toAbsolutePath; Files.createDirectories(root)
    val home = AppHome(Files.createTempDirectory(root, "home-")); Files.createDirectories(home.pythonDir)
    val service = new AppServices(home, Settings(pythonExe = Some(Path.of(args(1)).toAbsolutePath.toString)),
      dryRun = true, builtins = Nil, persistSettings = false, installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS)); service.start()
    var stage: Stage = null
    var context: UiContext = null
    try
      val vm = new GenerationVM(Mode.Txt2Img)
      var rejectOnce = true
      val tab = fx {
        stage = new Stage()
        val ctx = new UiContext(service, new CatalogVM, stage); context = ctx
        ctx.fastloadTarget = (_, index) => vm.controlNet.lift(index).map(_.toUnit)
        ctx.applyFastloadTarget = (_, index, expected, incoming) =>
          if rejectOnce then { rejectOnce = false; vm.controlNet(index).model.set("newer-edit") }
          FastloadApply.replace(vm, index, expected, incoming)
        val value = new ControlNetFastloadTab(ctx)
        stage.setScene(new Scene(value.node, 1000, 800)); stage.show()
        service.imageScrambleSession.unlock("test-secret")
        value.node.lookup("#fastload-source").asInstanceOf[TextField].setText(Path.of(args(2)).toAbsolutePath.toString)
        value.node.lookup("#fastload-inspect").asInstanceOf[Button].fire()
        value
      }
      def status: String = fx(tab.node.lookup("#fastload-status").asInstanceOf[Label].getText)
      def waitFor(test: String => Boolean, seconds: Int = 30): Unit =
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while !test(status) && System.nanoTime() < deadline do Thread.sleep(50)
        require(test(status), s"Fastload status: $status")
      waitFor(_.startsWith("Data loaded"))
      // A programmatic session transition may occur without the UI revision
      // notification; an in-flight preview must still discard its old data.
      fx {
        tab.node.lookup("#fastload-preview").asInstanceOf[Button].fire()
        service.imageScrambleSession.lock()
      }
      waitFor(_.startsWith("Image session changed"), 5)
      require(fx(tab.node.lookup("#fastload-summary").asInstanceOf[javafx.scene.control.TextArea].getText).isEmpty,
        "old session summary remained visible")
      fx {
        service.imageScrambleSession.unlock("test-secret"); context.imageAccessChanged()
        tab.node.lookup("#fastload-inspect").asInstanceOf[Button].fire()
        service.imageScrambleSession.lock()
      }
      waitFor(_.startsWith("Image session changed"), 5)
      require(fx(tab.node.lookup("#fastload-summary").asInstanceOf[javafx.scene.control.TextArea].getText).isEmpty,
        "old session inspect result remained visible")
      fx {
        service.imageScrambleSession.unlock("test-secret"); context.imageAccessChanged()
        tab.node.lookup("#fastload-inspect").asInstanceOf[Button].fire()
      }
      waitFor(_.startsWith("Data loaded"))
      fx {
        tab.node.lookup("#fastload-send-txt2img").asInstanceOf[Button].fire()
        service.imageScrambleSession.lock()
      }
      waitFor(_.startsWith("Image session changed"), 5)
      require(fx(vm.controlNet(0).model.get).isEmpty && rejectOnce, "direct session change applied a stale unit")
      fx {
        service.imageScrambleSession.unlock("test-secret"); context.imageAccessChanged()
        tab.node.lookup("#fastload-inspect").asInstanceOf[Button].fire()
      }
      waitFor(_.startsWith("Data loaded"))
      // The completion callback cannot run until this FX event returns. Change
      // the session during that event and enqueue a fresh inspect afterwards.
      fx {
        tab.node.lookup("#fastload-send-txt2img").asInstanceOf[Button].fire()
        service.imageScrambleSession.lock(); context.imageAccessChanged()
        service.imageScrambleSession.unlock("test-secret"); context.imageAccessChanged()
        tab.node.lookup("#fastload-inspect").asInstanceOf[Button].fire()
      }
      waitFor(_.startsWith("Data loaded"))
      require(fx(vm.controlNet(0).model.get).isEmpty, "stale session changed destination")
      require(rejectOnce, "stale session reached the destination callback")
      val sessionTemps = Files.list(home.tmpDir(service.settings))
      try require(sessionTemps.noneMatch(p => p.getFileName.toString.startsWith("paste-")), "stale session left a staging file")
      finally sessionTemps.close()
      fx(tab.node.lookup("#fastload-send-txt2img").asInstanceOf[Button].fire())
      waitFor(_.contains("destination unit changed"))
      require(fx(vm.controlNet(0).model.get) == "newer-edit")
      val staged = Files.list(home.tmpDir(service.settings))
      try require(staged.noneMatch(p => p.getFileName.toString.startsWith("paste-")), "stale staging file remains")
      finally staged.close()
      fx(tab.node.lookup("#fastload-send-txt2img").asInstanceOf[Button].fire())
      waitFor(_.startsWith("Sent unit"))
      val path = Path.of(fx(vm.controlNet(0).image.get))
      val decoded = ScrambledPng.read(path, Some("test-secret"))
      require(decoded.scrambled)
      require((decoded.image.getRGB(0, 0) & 0xffffff) == 0x14283c, "protected input pixels differ")
      require(fx(vm.controlNet(0).model.get) == "fixture-model")
      fx {
        tab.node.lookup("#fastload-capture-txt2img").asInstanceOf[Button].fire()
        service.imageScrambleSession.lock()
      }
      waitFor(_.startsWith("Image session changed"), 5)
      fx { service.imageScrambleSession.unlock("test-secret"); context.imageAccessChanged() }
      fx(tab.node.lookup("#fastload-capture-txt2img").asInstanceOf[Button].fire())
      waitFor(_.startsWith("Captured txt2img"))
      val output = root.resolve("must-not-write.cni")
      fx {
        tab.node.lookup("#fastload-destination").asInstanceOf[TextField].setText(output.toString)
        tab.node.lookup("#fastload-save").asInstanceOf[Button].fire()
      }
      require(status.contains("cannot save readable"), s"Unexpected protected save status: $status")
      require(!Files.exists(output), "protected capture wrote a readable CNI")
      val afterCapture = Files.list(home.tmpDir(service.settings))
      try require(afterCapture.filter(p => p.getFileName.toString.startsWith("paste-")).count() == 1L,
        "capture created an unexpected input file")
      finally afterCapture.close()
      fx {
        service.imageScrambleSession.lock(); context.imageAccessChanged()
        tab.node.lookup("#fastload-source").asInstanceOf[TextField].setText(Path.of(args(2)).toAbsolutePath.toString)
        tab.node.lookup("#fastload-inspect").asInstanceOf[Button].fire()
      }
      waitFor(_.startsWith("Data loaded"))
      val directOutput = root.resolve("direct-stale.cni")
      fx {
        tab.node.lookup("#fastload-destination").asInstanceOf[TextField].setText(directOutput.toString)
        tab.node.lookup("#fastload-save").asInstanceOf[Button].fire()
        service.imageScrambleSession.unlock("another-secret")
      }
      waitFor(_.startsWith("Image session changed"), 5)
      require(!Files.exists(directOutput), "direct session change published readable output")
      fx {
        service.imageScrambleSession.lock(); context.imageAccessChanged()
        tab.node.lookup("#fastload-inspect").asInstanceOf[Button].fire()
      }
      waitFor(_.startsWith("Data loaded"))
      val staleOutput = root.resolve("stale.cni")
      fx {
        tab.node.lookup("#fastload-destination").asInstanceOf[TextField].setText(staleOutput.toString)
        tab.node.lookup("#fastload-save").asInstanceOf[Button].fire()
        service.imageScrambleSession.unlock("another-secret"); context.imageAccessChanged()
        service.imageScrambleSession.lock(); context.imageAccessChanged()
        tab.node.lookup("#fastload-inspect").asInstanceOf[Button].fire()
      }
      waitFor(_.startsWith("Data loaded"))
      require(!Files.exists(staleOutput), "session change published readable output")
      val staleStages = Files.list(root)
      try require(staleStages.noneMatch(p => p.getFileName.toString.startsWith(".fastload-stage-")), "session change left readable staging")
      finally staleStages.close()
      val output2 = root.resolve("saved.cni")
      fx {
        tab.node.lookup("#fastload-destination").asInstanceOf[TextField].setText(output2.toString)
        tab.node.lookup("#fastload-save").asInstanceOf[Button].fire()
      }
      waitFor(_.startsWith("Saved new"))
      require(Files.isRegularFile(output2))
      val original = Files.readAllBytes(output2).toVector
      fx {
        val shot = tab.node.snapshot(null, null)
        javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(shot, null), "png", root.resolve("fastload-ui.png").toFile)
      }
      fx(tab.node.lookup("#fastload-save").asInstanceOf[Button].fire())
      waitFor(s => !s.startsWith("Saving") && !s.startsWith("Saved new"))
      require(Files.readAllBytes(output2).toVector == original, "existing CNI was replaced")
      fx {
        tab.dispose()
        require(tab.node.lookup("#fastload-summary").asInstanceOf[javafx.scene.control.TextArea].getText.isEmpty,
          "disposed tab retained summary")
        require(tab.node.lookup("#fastload-unit").asInstanceOf[javafx.scene.control.ComboBox[?]].getItems.isEmpty,
          "disposed tab retained unit labels")
      }
      println("PASS native Fastload transfer/capture: protected staging, stale session/destination, safe publication")
    finally
      if stage != null then fx(stage.close())
      service.shutdown(); Platform.exit()
