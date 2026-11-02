package sdfx.ui

import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.{Button, DialogPane, Label, ListCell, TextArea, TextField}
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.{PopupWindow, Stage, Window}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome, LocalizationPreferences, Settings}
import sdfx.engine.{AppServices, EngineLaunch}
import sdfx.plugins.StandardPlugins
import sdfx.ui.view.Theme
import sdfx.ui.vm.{Mode, Suggestion, UiState}

/** Real MainWindow and settings dialog with a fake CPU worker; no GPU. */
object RealTagColorMapCheck:
  def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(60, TimeUnit.SECONDS)
  def eventually(label: String)(condition: => Boolean): Unit =
    val until = System.nanoTime() + 60.seconds.toNanos
    while !condition && System.nanoTime() < until do Thread.sleep(50)
    require(condition, s"Timed out: $label")
  def main(args: Array[String]): Unit =
    require(args.length == 2, "python output-directory")
    val out = Paths.get(args(1)).toAbsolutePath.normalize(); Files.createDirectories(out)
    val home = AppHome(Files.createTempDirectory(out, "home-"))
    val tags = Files.writeString(out.resolve("tags.csv"), "blue_hair,4,20,\n")
    val extra = Files.writeString(out.resolve("extra.csv"), "blue_sky,1,custom,\n")
    val chants = Files.writeString(out.resolve("chants.json"), """[{"name":"bluechant","content":"blue sky","color":1}]""")
    val worker = Paths.get("mcp/src/test/resources/fake_engine.py").toAbsolutePath.normalize()
    val initial = Settings()
    Files.createDirectories(home.settingsFile.getParent); Settings.save(home.settingsFile, initial)
    val initialBytes = Files.readAllBytes(home.settingsFile)
    @volatile var failWrites = true
    val services = new AppServices(home, initial, dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(args(0), worker.toString), home.root, Map.empty)),
      persistSettings = true, installShutdownHook = false,
      settingsWriter = (path, value) => if failWrites then throw new java.io.IOException("injected write failure") else Settings.save(path, value))
    val ready = new CountDownLatch(1); Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    val uncaught = new AtomicReference[Throwable]()
    fx { Thread.currentThread().setUncaughtExceptionHandler((_, error) => uncaught.set(error)) }
    var window: MainWindow = null
    try
      services.start()
      window = fx { val w = new MainWindow(new Stage(), services, UiState(), CliOptions()); w.show(); w.select(Mode.Txt2Img); w }
      val notices = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      fx {
        window.ctx.toaster.layer.getChildren.addListener(new javafx.collections.ListChangeListener[javafx.scene.Node] {
          override def onChanged(change: javafx.collections.ListChangeListener.Change[? <: javafx.scene.Node]): Unit =
            while change.next() do
              change.getAddedSubList.asScala.foreach {
                case p: javafx.scene.Parent => p.getChildrenUnmodifiable.asScala.collect { case l: Label => l.getText }.foreach(notices.add)
                case _ => ()
              }
        })
      }
      val editor = window.panels(Mode.Txt2Img).prompts
      val tagsButton = fx(editor.toolbar.getChildren.asScala.collectFirst { case b: Button if b.getText == "Tags…" => b }.get)
      fx { require(window.localization.apply(LocalizationPreferences(pack = "builtin/ja_JP")).isEmpty) }
      Platform.runLater(() => tagsButton.fire())
      def dialog: Option[Stage] = Window.getWindows.asScala.collectFirst {
        case s: Stage if s.isShowing && s.getScene != null && s.getScene.getRoot.lookup("#tac-colormap") != null => s
      }
      eventually("settings dialog")(fx(dialog.nonEmpty))
      val before = services.settings
      fx {
        val root = dialog.get.getScene.getRoot.asInstanceOf[DialogPane]
        window.localization.localizer.sweepNow()
        require(root.lookupAll(".label").asScala.exists { case l: Label => l.getText.contains("指定したファイルでは"); case _ => false }, "Japanese palette explanation")
        require(root.lookupAll(".label").asScala.exists { case l: Label => l.getText.contains("ワイルドカードの Tab 補完"); case _ => false }, "Japanese wildcard mode label")
        root.applyCss(); root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(dialog.get.getScene.snapshot(null), null), "png", out.resolve("settings-ja.png").toFile))
        root.lookup("#tac-tags").asInstanceOf[TextField].setText(tags.toString)
        root.lookup("#tac-extra").asInstanceOf[TextField].setText(extra.toString)
        root.lookup("#tac-chants-file").asInstanceOf[TextField].setText(chants.toString)
        root.lookup("#tac-colormap").asInstanceOf[TextArea].setText("{bad")
        root.getButtonTypes.asScala.find(_.getText == "Save").map(root.lookupButton).get.asInstanceOf[Button].fire()
        require(dialog.nonEmpty, "invalid save must leave dialog open")
        require(services.settings == before, "invalid save must preserve all previous settings")
        window.localization.localizer.sweepNow()
        require(root.lookupAll(".label").asScala.exists { case l: Label => l.getText.contains("カテゴリ配色が正しくありません"); case _ => false }, "Japanese invalid-map message")
        root.applyCss(); root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(dialog.get.getScene.snapshot(null), null), "png", out.resolve("settings-invalid-ja.png").toFile))
        root.lookup("#tac-colormap").asInstanceOf[TextArea].setText(
          """{"tags":{"4":["red","blue"],"1":["gold","purple"]},"extra":{"1":["lime","orange"]}}""")
        root.getButtonTypes.asScala.find(_.getText == "Save").map(root.lookupButton).get.asInstanceOf[Button].fire()
        require(dialog.nonEmpty, "write failure must leave the dialog open")
        require(services.settings == before, "write failure must keep prior in-memory settings")
        require(java.util.Arrays.equals(Files.readAllBytes(home.settingsFile), initialBytes), "write failure must keep disk settings")
        failWrites = false
        root.getButtonTypes.asScala.find(_.getText == "Save").map(root.lookupButton).get.asInstanceOf[Button].fire()
      }
      eventually("data loaded")(fx(window.ctx.tagDictionary.data.tags.nonEmpty && window.ctx.tagDictionary.data.extra.nonEmpty && window.ctx.tagDictionary.data.chants.nonEmpty))
      require(services.settings.tagComplete.categoryColorMap.contains("tags"))
      require(Settings.load(home.settingsFile).tagComplete.categoryColorMap == services.settings.tagComplete.categoryColorMap, "saved map must survive settings reload")
      val area = editor.promptArea
      def input(text: String): Unit = fx { area.requestFocus(); area.setText(text); area.positionCaret(text.length) }
      def cells: Map[String, ListCell[?]] = fx {
        Window.getWindows.asScala.collectFirst { case p: PopupWindow if p.isShowing => p }.map { popup =>
          popup.getScene.getRoot.applyCss(); popup.getScene.getRoot.layout()
          popup.getScene.getRoot.lookupAll(".list-cell").asScala.collect {
            case c: ListCell[?] if c.getItem.isInstanceOf[Suggestion] => c.getItem.asInstanceOf[Suggestion].label -> c
          }.toMap
        }.getOrElse(Map.empty)
      }
      def color(c: ListCell[?]): Color = fx(c.getGraphic.asInstanceOf[Circle].getFill.asInstanceOf[Color])
      def shot(name: String): Unit = fx {
        val popup = Window.getWindows.asScala.collectFirst { case p: PopupWindow if p.isShowing => p }.get
        require(ImageIO.write(SwingFXUtils.fromFXImage(popup.getScene.snapshot(null), null), "png", out.resolve(name).toFile))
      }
      input("blue")
      eventually("tag and extra rows")(cells.keySet.contains("blue_hair") && cells.keySet.contains("blue_sky"))
      require(color(cells("blue_hair")) == Color.RED && color(cells("blue_sky")) == Color.LIME, "source-specific dark colors")
      shot("custom-dark.png")
      input("<c:blue")
      eventually("chant row")(cells.contains("bluechant"))
      require(color(cells("bluechant")) == Color.GOLD, "chant uses main tag CSV color")
      fx { Theme.apply(window.scene, "light") }
      input("blue")
      eventually("light tag rows")(cells.keySet.contains("blue_hair") && cells.keySet.contains("blue_sky"))
      require(color(cells("blue_hair")) == Color.BLUE && color(cells("blue_sky")) == Color.ORANGE, "source-specific light colors")
      shot("custom-light.png")
      input("<c:blue")
      eventually("light chant")(cells.contains("bluechant"))
      require(color(cells("bluechant")) == Color.PURPLE, "light chant uses main tag CSV color")
      services.updateSettings(_.copy(tagComplete = services.settings.tagComplete.copy(categoryColorMap = "{externally broken")))
      input("blue")
      fx { window.ctx.tagDictionary.categoryColor(Suggestion(sdfx.ui.vm.SuggestionKind.Tag, "blue_hair", "", () => "blue_hair", category = Some(4)), light = true) }
      eventually("invalid saved-map warning")(notices.asScala.exists(_.contains("Saved category colours are invalid")))
      require(services.settings.tagComplete.categoryColorMap == "{externally broken", "warning must not overwrite the invalid saved value")
      require(Settings.load(home.settingsFile).tagComplete.categoryColorMap == "{externally broken", "warning must not rewrite the file")
      val wildcards = Files.createDirectories(home.root.resolve("wildcards"))
      Files.writeString(wildcards.resolve("recovered.txt"), "recovered\n")
      fx(window.ctx.tagDictionary.reload())
      eventually("wildcard name before invalid path")(fx(window.ctx.tagDictionary.data.wildcards.contains("recovered")))
      Platform.runLater(() => tagsButton.fire())
      eventually("settings dialog after bad wildcard path")(fx(dialog.nonEmpty))
      val scanning = window.ctx.tagDictionary.getClass.getDeclaredFields.find(_.getName.endsWith("scanning")).get
      scanning.setAccessible(true)
      eventually("wildcard scan idle")(fx(!scanning.getBoolean(window.ctx.tagDictionary)))
      services.updateSettings(s => s.copy(pluginValues = s.pluginValues.updated("dynamic_prompts", Map("wildcard_dirs" -> ("x" + 0.toChar + "y")))))
      require(scala.util.Try(fx(window.ctx.tagDictionary.wildcardDirs)).isFailure, "injected wildcard path must fail resolution")
      val savedAfterFailure = """{"tags":{"4":["red","blue"]}}"""
      val newTags = Files.writeString(out.resolve("newtags.csv"), "fresh_tag,4,7,\n")
      fx {
        val root = dialog.get.getScene.getRoot.asInstanceOf[DialogPane]
        root.lookup("#tac-colormap").asInstanceOf[TextArea].setText(savedAfterFailure)
        root.lookup("#tac-tags").asInstanceOf[TextField].setText(newTags.toString)
        root.getButtonTypes.asScala.find(_.getText == "Save").map(root.lookupButton).get.asInstanceOf[Button].fire()
        require(dialog.isEmpty, "a successful save must not be reported as invalid when reload fails")
      }
      require(services.settings.tagComplete.categoryColorMap == savedAfterFailure &&
        Settings.load(home.settingsFile).tagComplete.categoryColorMap == savedAfterFailure, "the successful save must remain published")
      eventually("reload failure warning")(notices.asScala.exists(_.contains("Saved tag settings, but reload failed")))
      require(fx(!scanning.getBoolean(window.ctx.tagDictionary)), "failed path resolution must not leave scanning stuck")
      eventually("tag CSV still reloads with invalid wildcard path")(fx(window.ctx.tagDictionary.data.tags.exists(_.name == "fresh_tag")))
      input("__rec")
      eventually("cached wildcard offered")(fx(editor.completionItems.exists(_.label == "recovered")))
      fx { javafx.event.Event.fireEvent(area, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false)) }
      eventually("line load error is reported")(notices.asScala.exists(_.contains("Cannot read wildcard")))
      require(fx(area.getText) == "__recovered__", "failed line lookup preserves the wildcard reference")
      Platform.runLater(() => tagsButton.fire())
      eventually("reload dialog with invalid wildcard path")(fx(dialog.nonEmpty))
      fx {
        val root = dialog.get.getScene.getRoot.asInstanceOf[DialogPane]
        root.getButtonTypes.asScala.find(_.getText == "Reload").map(root.lookupButton).get.asInstanceOf[Button].fire()
      }
      eventually("manual reload error is reported")(notices.asScala.exists(_.contains("Cannot reload tag suggestions")))
      Files.writeString(wildcards.resolve("new-after-failure.txt"), "new entry\n")
      require(!fx(window.ctx.tagDictionary.data.wildcards.contains("new-after-failure")), "new wildcard must not be cached before recovery")
      services.updateSettings(s => s.copy(pluginValues = s.pluginValues - "dynamic_prompts"))
      fx(window.ctx.tagDictionary.reload())
      eventually("wildcard scan recovery")(fx(window.ctx.tagDictionary.data.wildcards.contains("new-after-failure")))
      require(uncaught.get() == null, s"unexpected JavaFX exception: ${uncaught.get()}")
      println("REAL_TAG_COLOR_MAP_OK")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
