package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.{ComboBox, ScrollPane, TitledPane}
import javafx.stage.Stage
import javax.imageio.ImageIO
import sdfx.core.config.{AppHome, Settings}
import sdfx.engine.AppServices
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}
import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import scala.jdk.CollectionConverters.*

/** Actual MainWindow script form and binding check, without launching a model. */
object RealCreaPromptWindowCheck:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "output directory")
    val output = Paths.get(args(0)).toAbsolutePath
    Files.createDirectories(output)
    val services = new AppServices(AppHome(Files.createTempDirectory(output, "home-")), Settings(),
      dryRun = true, builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    val task = new FutureTask[Unit](new Callable[Unit]:
      def call(): Unit =
        val window = new MainWindow(new Stage(), services, UiState(), CliOptions())
        try
          window.stage.setWidth(1500); window.stage.setHeight(1000); window.show()
          window.plugins(Mode.Txt2Img).selectedScript.set("creaprompt")
          window.root.applyCss(); window.root.layout()
          val manual = window.scene.getRoot.lookupAll(".titled-pane").asScala.collectFirst {
            case t: TitledPane if t.getText == "Manual picks" => t
          }.getOrElse(sys.error("CreaPrompt manual section missing"))
          manual.setExpanded(true)
          window.root.applyCss(); window.root.layout()
          val mode = window.scene.lookup("#plugin-creaprompt-mode").asInstanceOf[ComboBox[String]]
          val woman = window.scene.lookup("#plugin-creaprompt-manual_cat_Woman").asInstanceOf[ComboBox[String]]
          require(mode != null && woman != null)
          mode.setValue("Manual (choose per category)")
          woman.setValue(woman.getItems.get(2))
          val form = window.plugins(Mode.Txt2Img).scriptForms.find(_.plugin.id == "creaprompt").get
          require(form.values.str("manual_cat_Woman") == woman.getValue)
          val scroll = Iterator.iterate(woman.getParent)(_.getParent).takeWhile(_ != null).collectFirst {
            case s: ScrollPane => s
          }.getOrElse(sys.error("generation scroll pane missing"))
          val y = scroll.getContent.sceneToLocal(woman.localToScene(woman.getBoundsInLocal)).getMinY
          val distance = scroll.getContent.getBoundsInLocal.getHeight - scroll.getViewportBounds.getHeight
          if distance > 0 then scroll.setVvalue(math.max(0, math.min(1, (y - 250) / distance)))
          window.root.applyCss(); window.root.layout()
          require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve("main-window.png").toFile))
          println("CreaPrompt MainWindow script and manual choice binding passed")
        finally window.stage.close()
    )
    try
      Platform.runLater(task)
      task.get(60, TimeUnit.SECONDS)
    finally
      Platform.exit()
      services.shutdown()
