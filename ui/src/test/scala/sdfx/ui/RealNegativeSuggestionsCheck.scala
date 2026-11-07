package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.{Button, ButtonBar, ButtonType, CheckBox, ComboBox, DialogPane, Label, Labeled, TextArea, TextField}
import javafx.stage.{Stage, Window}
import javax.imageio.ImageIO
import sdfx.core.config.{AppHome, LocalizationPreferences, Settings}
import sdfx.engine.AppServices
import sdfx.core.model.{PromptStyle, Styles}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}
import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import scala.jdk.CollectionConverters.*

/** Drive the actual MainWindow, modal picker and prompt bindings without loading a model. */
object RealNegativeSuggestionsCheck:
  private def fx[A](f: => A): A =
    if Platform.isFxApplicationThread then return f
    val t = new FutureTask[A](new Callable[A] { def call(): A = f })
    Platform.runLater(t); t.get(60, TimeUnit.SECONDS)
  private def until(f: => Boolean): Unit =
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while !f && System.nanoTime() < end do Thread.sleep(40)
    require(f, "timed out waiting for dialog")
  private def pane: Option[DialogPane] = fx {
    Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene)).map(_.getRoot)
      .collectFirst { case p: DialogPane => p }
  }

  def main(args: Array[String]): Unit =
    require(args.length == 1, "output directory")
    val output = Paths.get(args(0)).toAbsolutePath
    Files.createDirectories(output)
    val services = new AppServices(AppHome(Files.createTempDirectory(output, "home-")), Settings(),
      dryRun = true, builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
    val started = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); started.countDown() })
    require(started.await(30, TimeUnit.SECONDS))
    val window = fx {
      val w = new MainWindow(new Stage(), services, UiState(), CliOptions())
      w.stage.setWidth(1500); w.stage.setHeight(950); w.show(); w
    }
    try
      for mode <- Mode.values do
        fx {
          window.select(mode)
          window.vms(mode).prompt.set("positive remains")
          window.vms(mode).negativePrompt.set("existing, blurry,\n  ")
          window.root.applyCss(); window.root.layout()
        }
        Platform.runLater(() => window.scene.lookup(s"#${mode.key}-negative-suggestions").asInstanceOf[Button].fire())
        until(pane.nonEmpty)
        fx {
          val d = pane.get
          d.lookup("#negative-candidate-blurry").asInstanceOf[CheckBox].setSelected(true)
          d.lookup("#negative-candidate-watermark").asInstanceOf[CheckBox].setSelected(true)
          val add = d.getButtonTypes.asScala.find(_.getButtonData == ButtonBar.ButtonData.OK_DONE).get
          d.lookupButton(add).asInstanceOf[Button].fire()
        }
        until(pane.isEmpty)
        fx {
          require(window.vms(mode).negativePrompt.get == "existing, blurry,\n  watermark")
          require(window.vms(mode).prompt.get == "positive remains")
        }
      // Cancelling leaves the selected mode's text unchanged.
      Platform.runLater(() => window.scene.lookup("#inpaint-negative-suggestions").asInstanceOf[Button].fire())
      until(pane.nonEmpty)
      fx { val d = pane.get; d.lookup("#negative-candidate-text").asInstanceOf[CheckBox].setSelected(true); d.lookupButton(ButtonType.CANCEL).asInstanceOf[Button].fire() }
      until(pane.isEmpty)
      require(fx(window.vms(Mode.Inpaint).negativePrompt.get) == "existing, blurry,\n  watermark")
      fx(window.vms(Mode.Inpaint).negativePrompt.set("(unfinished,"))
      Platform.runLater(() => window.scene.lookup("#inpaint-negative-suggestions").asInstanceOf[Button].fire())
      until(pane.nonEmpty)
      fx {
        val d = pane.get
        d.lookup("#negative-candidate-blurry").asInstanceOf[CheckBox].setSelected(true)
        val add = d.getButtonTypes.asScala.find(_.getButtonData == ButtonBar.ButtonData.OK_DONE).get
        d.lookupButton(add).asInstanceOf[Button].fire()
      }
      until(pane.isEmpty)
      require(fx(window.vms(Mode.Inpaint).negativePrompt.get) == "(unfinished,")
      fx(window.select(Mode.Txt2Img))
      Platform.runLater(() => window.scene.lookup("#txt2img-negative-suggestions").asInstanceOf[Button].fire())
      until(pane.nonEmpty)
      fx {
        val d = pane.get
        d.lookup("#negative-suggestion-preset").asInstanceOf[ComboBox[String]].setValue("Quality")
        require(d.lookup("#negative-candidate-low-quality").asInstanceOf[CheckBox].isSelected)
        require(d.lookup("#negative-candidate-jpeg-artifacts").asInstanceOf[CheckBox].isSelected)
        require(d.lookup("#negative-selection-preview").asInstanceOf[Label].getText.contains("low quality"))
        val add = d.getButtonTypes.asScala.find(_.getButtonData == ButtonBar.ButtonData.OK_DONE).get
        d.lookupButton(add).asInstanceOf[Button].fire()
      }
      until(pane.isEmpty)
      require(fx(window.vms(Mode.Txt2Img).negativePrompt.get) == "existing, blurry,\n  watermark, low quality, worst quality, jpeg artifacts")
      fx {
        val field = window.scene.lookup("#txt2img-negative-prompt").asInstanceOf[TextArea]
        val beforeUndo = field.getText
        val couldUndo = field.isUndoable
        field.undo()
        require(field.getText == "existing, blurry,\n  watermark", s"undo: before=$beforeUndo after=${field.getText} undoable=$couldUndo")
        field.redo()
        require(field.getText == "existing, blurry,\n  watermark, low quality, worst quality, jpeg artifacts")
      }
      Platform.runLater(() => window.scene.lookup("#txt2img-save-style").asInstanceOf[Button].fire())
      until(pane.nonEmpty)
      fx {
        val d = pane.get
        d.lookup(".text-field").asInstanceOf[TextField].setText("Negative candidates test")
        d.lookupButton(ButtonType.OK).asInstanceOf[Button].fire()
      }
      until(pane.isEmpty)
      until(Files.isRegularFile(services.home.stylesFile) && Styles.load(services.home.stylesFile).exists(_.name == "Negative candidates test"))
      val saved = Styles.load(services.home.stylesFile).find(_.name == "Negative candidates test").get
      require(saved.negativePrompt == "existing, blurry,\n  watermark, low quality, worst quality, jpeg artifacts")
      fx { window.vms(Mode.Txt2Img).prompt.set(""); window.vms(Mode.Txt2Img).negativePrompt.set(""); window.ctx.loadStyles() }
      until(fx(window.ctx.styles.asScala.exists(_.name == "Negative candidates test")))
      fx {
        val choice = window.scene.lookup("#txt2img-style-choice").asInstanceOf[ComboBox[PromptStyle]]
        choice.setValue(window.ctx.styles.asScala.find(_.name == "Negative candidates test").get)
        window.scene.lookup("#txt2img-apply-style").asInstanceOf[Button].fire()
        require(window.vms(Mode.Txt2Img).negativePrompt.get == saved.negativePrompt)
        require(window.vms(Mode.Txt2Img).prompt.get == "positive remains")
      }
      for before <- Seq("文字e\u0301🦊", "") do
        fx(window.vms(Mode.Txt2Img).negativePrompt.set(before))
        Platform.runLater(() => window.scene.lookup("#txt2img-negative-suggestions").asInstanceOf[Button].fire())
        until(pane.nonEmpty)
        fx {
          val d = pane.get
          d.lookup("#negative-candidate-text").asInstanceOf[CheckBox].setSelected(true)
          val add = d.getButtonTypes.asScala.find(_.getButtonData == ButtonBar.ButtonData.OK_DONE).get
          d.lookupButton(add).asInstanceOf[Button].fire()
        }
        until(pane.isEmpty)
        fx {
          val field = window.scene.lookup("#txt2img-negative-prompt").asInstanceOf[TextArea]
          val expected = before + (if before.isEmpty then "text" else ", text")
          require(field.getText == expected, s"Unicode append changed text: ${field.getText}")
          field.undo(); require(field.getText == before, s"Unicode undo changed text: ${field.getText}")
          field.redo(); require(field.getText == expected, s"Unicode redo changed text: ${field.getText}")
        }
      fx { window.vms(Mode.Txt2Img).negativePrompt.set(""); window.localization.apply(LocalizationPreferences(pack = "builtin/ja_JP")); window.select(Mode.Txt2Img) }
      Thread.sleep(600)
      Platform.runLater(() => window.scene.lookup("#txt2img-negative-suggestions").asInstanceOf[Button].fire())
      until(pane.nonEmpty)
      Thread.sleep(600)
      fx {
        window.localization.localizer.sweepNow()
        val d = pane.get
        require(d.lookup("#negative-candidate-blurry").asInstanceOf[CheckBox].getTooltip.getText == "ぼやけた画像")
        val displayed = d.lookupAll("*").asScala.collect { case l: Labeled => l.getText }.toVector
        require(displayed.contains("追加する語句を選んでください"), displayed.mkString(" | "))
        d.applyCss(); d.layout()
        val add = d.getButtonTypes.asScala.find(_.getButtonData == ButtonBar.ButtonData.OK_DONE).get
        val addButton = d.lookupButton(add).asInstanceOf[Button]
        require(addButton.getText == "追加" && addButton.getWidth >= addButton.prefWidth(-1),
          s"button text or width: ${addButton.getText}, ${addButton.getWidth}, ${addButton.prefWidth(-1)}")
        d.lookup("#negative-suggestion-preset").asInstanceOf[ComboBox[String]].setValue("Quality")
        require(d.lookup("#negative-selection-preview").asInstanceOf[Label].getText.contains("low quality"))
        ImageIO.write(SwingFXUtils.fromFXImage(d.getScene.snapshot(null), null), "png", output.resolve("negative-suggestions-ja.png").toFile)
        d.lookup("#negative-candidate-text").asInstanceOf[CheckBox].setSelected(true)
        d.lookupButton(add).asInstanceOf[Button].fire()
      }
      until(pane.isEmpty)
      require(fx(window.vms(Mode.Txt2Img).negativePrompt.get) == "low quality, worst quality, blurry, jpeg artifacts, text")
      println("Negative suggestions: three modes, cancel, existing text and Japanese dialog passed")
    finally
      fx(window.stage.close())
      Platform.exit()
      services.shutdown()
