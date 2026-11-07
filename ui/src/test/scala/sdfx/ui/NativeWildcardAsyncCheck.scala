package sdfx.ui

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.TextArea
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.view.{CompletionSource, TagAutocomplete}
import sdfx.ui.vm.{CompletionData, CompletionEngine, CompletionResult}

object NativeWildcardAsyncCheck:
  def main(args: Array[String]): Unit =
    val done = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable]()
    Platform.startup(() => {
      val area = new TextArea()
      val data = CompletionData(wildcards = Vector("animals"))
      val source = new CompletionSource:
        def ensureLoaded(): Unit = ()
        def previews: Boolean = false
        def complete(text: String, caret: Int, start: Int, end: Int): Option[CompletionResult] =
          CompletionEngine.complete(data, TagCompleteSettings(), text, caret, start, end)
        override def wildcardLines(name: String)(callback: Either[Throwable, Vector[String]] => Unit): Unit =
          Platform.runLater(() => callback(Right(Vector("a fox"))))
      val completion = new TagAutocomplete(area, source)
      val stage = new Stage()
      stage.setScene(new Scene(new StackPane(area), 600, 300)); stage.show(); area.requestFocus()
      Platform.runLater(() => {
        area.setText("__ani"); area.positionCaret(area.getLength)
        Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
        Platform.runLater(() => {
          try require(completion.suggestions.map(_.label) == Vector("a fox"), "a deferred UI update must not discard the valid async result")
          catch case e: Throwable => failure.set(e)
          finally { stage.close(); done.countDown() }
        })
      })
    })
    require(done.await(30, TimeUnit.SECONDS), "Native async check timed out")
    Platform.exit()
    if failure.get() != null then throw failure.get()
