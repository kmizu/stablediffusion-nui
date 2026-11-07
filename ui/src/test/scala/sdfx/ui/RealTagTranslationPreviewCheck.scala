package sdfx.ui

import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.{Button, Label}
import javafx.scene.layout.FlowPane
import javafx.stage.Stage
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, Settings, TagCompleteSettings}
import sdfx.engine.{AppServices, EngineLaunch}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

object RealTagTranslationPreviewCheck:
  def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(45, TimeUnit.SECONDS)
  def eventually(label: String)(condition: => Boolean): Unit =
    val end = System.nanoTime() + 45.seconds.toNanos
    while !condition && System.nanoTime() < end do Thread.sleep(50)
    require(condition, s"Timed out: $label")
  def main(args: Array[String]): Unit =
    require(args.length == 2, "python output")
    val out = Paths.get(args(1)).toAbsolutePath.normalize(); Files.createDirectories(out)
    val home = AppHome(Files.createTempDirectory(out, "home-"))
    val translation = out.resolve("translations.csv")
    Files.writeString(translation, "blue hair,BLUE-TRANSLATION\ngirl,GIRL-TRANSLATION\n")
    val cfg = TagCompleteSettings(translationCsv = Some(translation.toString), showTranslationPreview = true)
    val settings = Settings(tagComplete = cfg)
    val worker = Paths.get("mcp/src/test/resources/fake_engine.py").toAbsolutePath.normalize()
    val services = new AppServices(home, settings, dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(args(0), worker.toString), home.root, Map.empty)),
      persistSettings = false, installShutdownHook = false)
    val ready = new CountDownLatch(1); Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start()
      window = fx { val w = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ()); w.show(); w.select(Mode.Txt2Img); w }
      val editor = window.panels(Mode.Txt2Img).prompts
      def pane = window.stage.getScene.lookup("#txt2img-prompt-translation").asInstanceOf[FlowPane]
      fx { editor.promptArea.setText("BLUE_HAIR, girl") }
      eventually("loaded translation") { fx(pane.getChildren.asScala.collect { case b: Button => b.getText }.toVector == Vector("BLUE-TRANSLATION (BLUE_HAIR)", "GIRL-TRANSLATION (girl)")) }
      fx {
        pane.getChildren.get(0).asInstanceOf[Button].fire()
        require(editor.promptArea.getSelectedText == "BLUE_HAIR")
        editor.promptArea.setText("girl")
        require(pane.getChildren.size == 1 && pane.getChildren.get(0).asInstanceOf[Button].getText.startsWith("GIRL-TRANSLATION"))
        editor.negativeArea.setText("blue hair")
      }
      eventually("negative translation") { fx(window.stage.getScene.lookup("#txt2img-negative-translation").asInstanceOf[FlowPane].getChildren.size == 1) }
      fx {
        window.stage.getScene.getRoot.applyCss(); window.stage.getScene.getRoot.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(window.stage.getScene.snapshot(null), null), "png", out.resolve("translation-preview.png").toFile))
        require(window.ctx.tagDictionary.apply(None, cfg.copy(showTranslationPreview = false)).isRight)
        require(!pane.isVisible && !pane.isManaged)
        require(window.ctx.tagDictionary.apply(None, cfg.copy(translationCsv = Some(out.resolve("missing.csv").toString))).isRight)
        require(pane.isVisible && pane.getChildren.isEmpty, "old translations must clear when switching files")
      }
      eventually("missing dictionary is reported") { fx(window.ctx.toaster.layer.getChildren.size() > 0) }
      fx { require(pane.getChildren.isEmpty, "failed load must not restore stale translations") }
      fx { require(window.ctx.tagDictionary.apply(None, cfg).isRight) }
      eventually("dictionary recovers") { fx(pane.getChildren.size == 1 && pane.getChildren.get(0).asInstanceOf[Button].getText.startsWith("GIRL-TRANSLATION")) }
      val longFile = out.resolve("long-translation.csv")
      Files.writeString(longFile, "girl," + ("x" * 1000000) + "\n")
      fx { require(window.ctx.tagDictionary.apply(None, cfg.copy(translationCsv = Some(longFile.toString))).isRight) }
      eventually("long translation bounded") { fx {
        pane.getChildren.size == 1 && pane.getChildren.get(0).asInstanceOf[Button].getText.startsWith("x" * 120)
      }}
      fx {
        val label = pane.getChildren.get(0).asInstanceOf[Button].getText
        require(label.length < 150 && label.contains("…"), s"long label length: ${label.length}")
        pane.getChildren.get(0).asInstanceOf[Button].fire()
        require(editor.promptArea.getSelectedText == "girl")
      }
      println("REAL_TAG_TRANSLATION_PREVIEW_OK")
    finally NativeAcceptanceSafety.cleanup(null)(() => if window != null then fx { window.dispose(); window.stage.close() }, () => services.shutdown(), () => Platform.exit())
