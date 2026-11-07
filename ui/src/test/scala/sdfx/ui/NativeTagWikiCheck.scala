package sdfx.ui

import java.net.URI
import java.nio.file.{Files, Paths}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.control.{Button, CheckBox, TextArea}
import javafx.scene.layout.StackPane
import javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent}
import javafx.stage.{PopupWindow, Stage, Window}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.view.{CompletionSource, TagAutocomplete, TagSettingsPane}
import sdfx.ui.vm.{CompletionData, CompletionEngine, CompletionResult, Suggestion, TagCompletion, TagWiki}

object NativeTagWikiCheck:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "output-directory")
    val output = Paths.get(args(0)).toAbsolutePath.normalize(); Files.createDirectories(output)
    val done = new CountDownLatch(1); val failure = new AtomicReference[Throwable]()
    Platform.startup(() => {
      Platform.setImplicitExit(false)
      Files.writeString(output.resolve("phase.txt"), "startup")
      val stage = new Stage()
      try
        val pane = new TagSettingsPane(None, TagCompleteSettings(), stage)
        val enabled = pane.node.lookup("#tac-wiki").asInstanceOf[CheckBox]
        require(!enabled.isSelected && !pane.read()._2.showWikiLinks, "wiki links must default off")
        enabled.setSelected(true)
        require(pane.read()._2.showWikiLinks, "enabling wiki links must persist")
        val area = new TextArea()
        val data = CompletionData(tags = TagCompletion.parse(Iterator("blue_hair,4,20,azure_hair")))
        var opened = Vector.empty[URI]
        val source = new CompletionSource:
          def ensureLoaded(): Unit = ()
          def previews: Boolean = false
          def complete(text: String, caret: Int, start: Int, end: Int): Option[CompletionResult] =
            CompletionEngine.complete(data, TagCompleteSettings(), text, caret, start, end)
          override def wikiUri(item: Suggestion): Option[URI] = TagWiki.uri(true, Some("danbooru.csv"), item.kind, item.wikiTag.getOrElse(""), item.category)
          override def openWiki(uri: URI): Unit = opened :+= uri
        val completion = new TagAutocomplete(area, source)
        stage.setScene(new Scene(new StackPane(area), 600, 300)); stage.show(); stage.requestFocus(); area.requestFocus()
        Platform.runLater(() => {
          try
            Files.writeString(output.resolve("phase.txt"), "first callback")
            area.requestFocus()
            Files.writeString(output.resolve("phase.txt"), "focus requested")
            area.setText("blue")
            Files.writeString(output.resolve("phase.txt"), "text set")
            area.positionCaret(4)
            Files.writeString(output.resolve("phase.txt"), "queued second callback")
            Platform.runLater(() => {
              try
                Files.writeString(output.resolve("phase.txt"), "second callback")
                require(completion.isShowing, "completion popup must be visible")
                val popup = Window.getWindows.asScala.collectFirst { case p: PopupWindow if p.isShowing => p }.get
                popup.getScene.getRoot.applyCss(); popup.getScene.getRoot.layout()
                val button = popup.getScene.getRoot.lookup("#tac-wiki-link").asInstanceOf[Button]
                require(button != null, "tag wiki button is visible")
                require(ImageIO.write(SwingFXUtils.fromFXImage(popup.getScene.snapshot(null), null), "png", output.resolve("tag-wiki-popup.png").toFile))
                button.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                  false, false, false, false, true, false, false, false, false, true, null))
                require(area.getText == "blue" && completion.isShowing, "mouse press on the link must not accept its row")
                button.fire()
                require(opened.map(_.toString) == Vector("https://danbooru.donmai.us/wiki_pages/blue_hair"))
                require(area.getText == "blue", "opening the link must not insert a tag")
                require(completion.isShowing, "opening the link must not accept the suggestion")
                area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F1, false, false, false, false))
                require(opened.size == 2 && area.getText == "blue", "F1 opens the selected tag without inserting it")
                println("NATIVE_TAG_WIKI_OK")
              catch case e: Throwable => { failure.set(e); Files.writeString(output.resolve("failure.txt"), e.toString) }
              finally { stage.close(); done.countDown() }
            })
          catch case e: Throwable => { failure.set(e); Files.writeString(output.resolve("failure.txt"), e.toString); stage.close(); done.countDown() }
        })
      catch case e: Throwable => { failure.set(e); Files.writeString(output.resolve("failure.txt"), e.toString); stage.close(); done.countDown() }
    })
    require(done.await(30, TimeUnit.SECONDS), "wiki UI check timed out")
    if failure.get() != null then
      failure.get().printStackTrace()
      System.exit(1)
    Platform.exit()
