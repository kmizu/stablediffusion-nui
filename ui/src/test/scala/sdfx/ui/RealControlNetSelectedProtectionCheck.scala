package sdfx.ui

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.scene.control.{Button, Label, ListView, TabPane, TextField}
import javafx.stage.Stage
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, LocalizationPreferences, Settings}
import sdfx.engine.{AppServices, EngineLaunch}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.UiState

/** Real MainWindow with a controlled fake worker; file chooser is not automated. */
object RealControlNetSelectedProtectionCheck:
  def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(60, TimeUnit.SECONDS)
  def eventually(label: String)(body: => Boolean): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while !body && System.nanoTime() < deadline do Thread.sleep(25)
    require(body, s"Timed out: $label")
  def main(args: Array[String]): Unit =
    require(args.length == 2, "python output-directory")
    val out = Paths.get(args(1)).toAbsolutePath.normalize(); Files.createDirectories(out)
    val root = Files.createTempDirectory(out, "selected-protection-")
    val home = AppHome(root.resolve("home")); val output = Files.createDirectories(root.resolve("out"))
    val input = root.resolve("input.png")
    require(ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", input.toFile))
    val gate = root.resolve("gate").toString
    val worker = Paths.get("mcp/src/test/resources/fake_engine.py").toAbsolutePath
    val services = new AppServices(home, Settings(), dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(args(0), worker.toString), root, Map("SDFX_TEST_PREPROCESS_GATE" -> gate))),
      persistSettings = false, installShutdownHook = false)
    val ready = new CountDownLatch(1); Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start()
      window = fx {
        val w = new MainWindow(new Stage(), services, UiState(), CliOptions()); w.show()
        val tabs = w.scene.getRoot.lookupAll(".tab-pane").asScala.collect { case t: TabPane => t }
          .find(_.getTabs.asScala.exists(_.getText == "Extras")).get
        tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getText == "Extras").get)
        w
      }
      eventually("preprocessor ready")(fx(window.scene.lookup("#extras-cn-module") != null))
      fx {
        val list = window.scene.lookup("#extras-cn-selected-list").asInstanceOf[ListView[Path]]
        list.getItems.addAll(input, root.resolve("unwanted.png"))
        list.getSelectionModel.select(1)
        window.scene.lookup("#extras-cn-remove-selected").asInstanceOf[Button].fire()
        require(list.getItems.asScala.toVector == Vector(input), "remove selected must keep other images")
        window.scene.lookup("#extras-cn-output-dir").asInstanceOf[TextField].setText(output.toString)
        require(window.localization.apply(LocalizationPreferences(pack = "builtin/ja_JP")).isEmpty)
        window.localization.localizer.sweepNow()
        require(window.scene.lookup("#extras-cn-run-selected").asInstanceOf[Button].getText.contains("選択した画像"))
        val tabs = window.scene.getRoot.lookupAll(".tab-pane").asScala.collect { case t: TabPane => t }
          .find(_.getTabs.asScala.exists(_.getText == "Extras")).get
        tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getText == "Extras").get)
        var p: javafx.scene.Node = window.scene.lookup("#extras-cn-run-selected")
        while p != null && !p.isInstanceOf[javafx.scene.control.ScrollPane] do p = p.getParent
        p match
          case sp: javafx.scene.control.ScrollPane =>
            sp.layout()
            val target = window.scene.lookup("#extras-cn-run-selected")
            val y = target.localToScene(0, 0).getY - sp.getContent.localToScene(0, 0).getY
            val total = sp.getContent.getLayoutBounds.getHeight - sp.getViewportBounds.getHeight
            if total > 0 then sp.setVvalue((y / total).max(0).min(1))
          case _ => ()
      }
      Thread.sleep(500)
      fx {
        window.scene.getRoot.applyCss(); window.scene.getRoot.layout()
        ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", root.resolve("selected-ja.png").toFile)
        window.scene.lookup("#extras-cn-run-selected").asInstanceOf[Button].fire()
      }
      eventually("worker started")(Files.exists(Paths.get(gate + ".started")))
      services.imageScrambleSession.unlock("changed while processing")
      Files.writeString(Paths.get(gate + ".continue"), "continue")
      eventually("batch stops")(fx(window.scene.lookup("#extras-cn-cancel").asInstanceOf[Button].isDisabled))
      val status = fx(window.scene.lookup("#extras-cn-status").asInstanceOf[Label].getText)
      require(status.contains("session changed") && status.contains("cancelled"), status)
      val count = scala.util.Using.resource(Files.list(output))(_.count())
      require(count == 0, s"stale session must not publish: $count")
      val protectedInput = root.resolve("protected-input.png")
      sdfx.core.png.ScrambledPng.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), protectedInput, Nil, Some("changed while processing"))
      fx {
        window.scene.lookup("#extras-cn-selected-list").asInstanceOf[ListView[Path]].getItems.setAll(protectedInput)
        window.scene.lookup("#extras-cn-run-selected").asInstanceOf[Button].fire()
      }
      val protectedPath = output.resolve("protected-input-canny.png")
      eventually("protected batch output")(Files.exists(protectedPath))
      eventually("protected batch idle")(fx(window.scene.lookup("#extras-cn-cancel").asInstanceOf[Button].isDisabled))
      require(sdfx.core.png.ScrambledPng.read(protectedPath, Some("changed while processing")).scrambled,
        "protected batch output must be scrambled")
      require(scala.util.Try(sdfx.core.png.ScrambledPng.read(protectedPath, None)).isFailure,
        "protected batch must not be readable without a password")
      println("REAL_CN_SELECTED_PROTECTION_OK")
    finally
      Files.writeString(Paths.get(gate + ".continue"), "continue")
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
