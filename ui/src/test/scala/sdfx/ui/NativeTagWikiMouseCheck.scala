package sdfx.ui

import java.net.URI
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.{Button, TextArea}
import javafx.scene.input.MouseButton
import javafx.scene.layout.StackPane
import javafx.scene.robot.Robot
import javafx.stage.{PopupWindow, Stage, Window}
import scala.jdk.CollectionConverters.*
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.view.{CompletionSource, TagAutocomplete}
import sdfx.ui.vm.{CompletionData, CompletionEngine, CompletionResult, Suggestion, TagCompletion, TagWiki}

/** Uses JavaFX Robot, but replaces browser opening with a recording callback. */
object NativeTagWikiMouseCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task)
    task.get(30, TimeUnit.SECONDS)

  def main(args: Array[String]): Unit =
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var opened = Vector.empty[URI]
    var stage: Stage = null
    var area: TextArea = null
    var completion: TagAutocomplete = null
    try
      fx {
        area = new TextArea()
        val data = CompletionData(tags = TagCompletion.parse(Iterator("blue_hair,4,20,azure_hair", "blue_eyes,0,10,")))
        val source = new CompletionSource:
          def ensureLoaded(): Unit = ()
          def previews: Boolean = false
          def complete(text: String, caret: Int, start: Int, end: Int): Option[CompletionResult] =
            CompletionEngine.complete(data, TagCompleteSettings(), text, caret, start, end)
          override def wikiUri(item: Suggestion): Option[URI] = TagWiki.uri(true, Some("danbooru.csv"), item.kind, item.wikiTag.getOrElse(""), item.category)
          override def openWiki(uri: URI): Unit = opened :+= uri
        completion = new TagAutocomplete(area, source)
        stage = new Stage(); stage.setScene(new Scene(new StackPane(area), 600, 300))
        stage.show(); area.requestFocus()
      }
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
      while !fx(area.isFocused) && System.nanoTime() < deadline do Thread.sleep(40)
      require(fx(area.isFocused), "prompt did not receive focus")
      fx { area.setText("blue"); area.positionCaret(4) }
      require(fx(completion.isShowing), "popup not visible")
      fx {
        val popup = Window.getWindows.asScala.collectFirst { case p: PopupWindow if p.isShowing => p }.get
        popup.getScene.getRoot.applyCss(); popup.getScene.getRoot.layout()
        val button = popup.getScene.lookup("#tac-wiki-link").asInstanceOf[Button]
        require(button != null, "wiki button absent")
        val b = button.localToScreen(button.getBoundsInLocal)
        require(b != null, "button screen bounds unavailable")
        val robot = new Robot()
        robot.mouseMove((b.getMinX + b.getMaxX) / 2, (b.getMinY + b.getMaxY) / 2)
        robot.mouseClick(MouseButton.PRIMARY)
      }
      val clickDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      while fx(opened.isEmpty) && System.nanoTime() < clickDeadline do Thread.sleep(40)
      fx {
        require(opened.map(_.toString) == Vector("https://danbooru.donmai.us/wiki_pages/blue_hair"), s"wrong opened target: $opened")
        require(area.getText == "blue", "wiki click inserted the tag")
        require(area.isFocused, "wiki click lost prompt focus")
        require(completion.isShowing, "wiki click dismissed popup")
      }
      println("NATIVE_TAG_WIKI_MOUSE_OK")
    finally
      if stage != null then fx(stage.close())
      Platform.exit()
