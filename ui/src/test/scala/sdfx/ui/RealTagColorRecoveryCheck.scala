package sdfx.ui

import java.nio.file.{Files, Paths}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.scene.control.{Button, ButtonType, DialogPane, Label, TextArea}
import javafx.stage.{Stage, Window}
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, Settings, SettingsRecovery}
import sdfx.engine.{AppServices, EngineLaunch}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

object RealTagColorRecoveryCheck:
  def main(args: Array[String]): Unit =
    import RealTagColorMapCheck.{eventually, fx}
    require(args.length == 2, "python output-directory")
    val out = Paths.get(args(1)).toAbsolutePath.normalize(); Files.createDirectories(out)
    val home = AppHome(Files.createTempDirectory(out, "recovery-"))
    Files.createDirectories(home.settingsFile.getParent)
    val original = "{invalid settings".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    Files.write(home.settingsFile, original)
    val initial = Settings()
    val worker = Paths.get("mcp/src/test/resources/fake_engine.py").toAbsolutePath.normalize()
    val services = new AppServices(home, initial, dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(args(0), worker.toString), home.root, Map.empty)),
      initialRecovery = Some(SettingsRecovery("fixture recovery", None)), installShutdownHook = false)
    val ready = new CountDownLatch(1); Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start()
      window = fx { val w = new MainWindow(new Stage(), services, UiState(), CliOptions()); w.show(); w.select(Mode.Txt2Img); w }
      val editor = window.panels(Mode.Txt2Img).prompts
      Platform.runLater(() => editor.toolbar.getChildren.asScala.collectFirst { case b: Button if b.getText == "Tags…" => b }.get.fire())
      def dialog: Option[Stage] = Window.getWindows.asScala.collectFirst { case s: Stage if s.isShowing && s.getTitle == "Tag autocomplete" => s }
      eventually("settings dialog")(fx(dialog.nonEmpty))
      fx {
        val root = dialog.get.getScene.getRoot.asInstanceOf[DialogPane]
        root.lookup("#tac-colormap").asInstanceOf[TextArea].setText("""{"tags":{"4":["red","blue"]}}""")
        root.getButtonTypes.asScala.find(_.getText == "Save").map(root.lookupButton).get.asInstanceOf[Button].fire()
        require(dialog.nonEmpty, "recovery hold must leave dialog open")
        require(services.settings == initial, "recovery hold must preserve in-memory settings")
        require(java.util.Arrays.equals(Files.readAllBytes(home.settingsFile), original), "recovery hold must preserve original bytes")
        require(root.lookupAll(".label").asScala.exists { case l: Label => l.getText.contains("settings recovery required"); case _ => false })
        root.lookupButton(ButtonType.CANCEL).asInstanceOf[Button].fire()
      }
      println("REAL_TAG_COLOR_RECOVERY_OK")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
