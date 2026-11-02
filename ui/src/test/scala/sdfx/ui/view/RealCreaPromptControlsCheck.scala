package sdfx.ui.view

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.{Parent, Scene}
import javafx.scene.control.{Button, ComboBox, ComboBoxBase, Label, ScrollPane, TextField, TitledPane}
import javafx.event.Event
import javafx.stage.Stage
import javax.imageio.ImageIO
import sdfx.core.config.{AppHome, Settings}
import sdfx.engine.AppServices
import sdfx.plugins.CreaPromptPlugin
import sdfx.ui.vm.{CatalogVM, PluginValuesVM}
import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}

/** Checks the actual generic JavaFX controls and value bindings without a model or engine process. */
object RealCreaPromptControlsCheck:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "output directory")
    val output = Paths.get(args(0)).toAbsolutePath
    Files.createDirectories(output)
    val services = new AppServices(AppHome(Files.createTempDirectory(output, "home-")), Settings(),
      dryRun = true, builtins = Nil, persistSettings = false, installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    val task = new FutureTask[Unit](new Callable[Unit]:
      def call(): Unit =
        val stage = new Stage()
        try
          val presets = Files.createTempDirectory(output, "presets-")
          val plugin = new CreaPromptPlugin(() => Some(presets))
          val form = new PluginValuesVM(plugin)
          val root = PluginForm.render(new UiContext(services, new CatalogVM, stage), form).asInstanceOf[Parent]
          val scroll = new ScrollPane(root)
          scroll.setFitToWidth(true)
          stage.setScene(new Scene(scroll, 900, 1000))
          stage.show()
          root.applyCss(); root.layout()
          val manual = root.lookupAll(".titled-pane").toArray.collect { case t: TitledPane if t.getText == "Manual picks" => t }.head
          manual.setExpanded(true)
          root.applyCss(); root.layout()
          val mode = root.lookup("#plugin-creaprompt-mode").asInstanceOf[ComboBox[String]]
          val woman = root.lookup("#plugin-creaprompt-manual_cat_Woman").asInstanceOf[ComboBox[String]]
          val lighting = root.lookup("#plugin-creaprompt-manual_cat_lighting").asInstanceOf[ComboBox[String]]
          require(mode != null && woman != null && lighting != null)
          mode.setValue(CreaPromptPlugin.ModeManual)
          woman.setValue(woman.getItems.get(2))
          lighting.setValue("(random)")
          require(form.values.str("mode") == CreaPromptPlugin.ModeManual)
          require(form.values.str("manual_cat_Woman") == woman.getValue)
          require(form.values.str("manual_cat_lighting") == "(random)")
          require(root.lookup("#creaprompt-active").asInstanceOf[Label].getText == "CreaPrompt: Active")
          val preview = root.lookup("#creaprompt-preview").asInstanceOf[Label]
          require(preview.getText.contains(woman.getValue) && preview.getText.contains("Random: lighting"))
          lighting.setValue("(none)")
          require(!preview.getText.contains("Random: lighting"))
          root.lookup("#creaprompt-preset-name").asInstanceOf[TextField].setText("Saved preset")
          root.lookup("#creaprompt-save-preset").asInstanceOf[Button].fire()
          require(Files.isRegularFile(presets.resolve("Saved preset.config")))
          require(form.values.str("preset") == "Saved preset")
          require(root.lookup("#creaprompt-preset-status").asInstanceOf[Label].getText.contains("Saved preset"))
          Files.writeString(presets.resolve("Added.config"), "Woman\n")
          val presetBox = root.lookup("#plugin-creaprompt-preset").asInstanceOf[ComboBox[String]]
          presetBox.fireEvent(new Event(ComboBoxBase.ON_SHOWING))
          require(presetBox.getItems.contains("Added"), "new preset should be discovered when opening the menu")
          scroll.setVvalue(1.0)
          ImageIO.write(SwingFXUtils.fromFXImage(scroll.snapshot(null, null), null), "png", output.resolve("controls.png").toFile)
          println("CreaPrompt JavaFX manual choices and value bindings passed")
        finally stage.close()
    )
    try
      Platform.runLater(task)
      task.get(60, TimeUnit.SECONDS)
    finally
      Platform.exit()
      services.shutdown()
