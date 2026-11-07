package sdfx.ui

import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.collections.FXCollections
import javafx.scene.Scene
import javafx.scene.control.TextArea
import javafx.scene.input.{InputMethodEvent, InputMethodHighlight, InputMethodTextRun, KeyCode, KeyEvent}
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.view.{CompletionSource, TagAutocomplete}
import sdfx.ui.vm.{CompletionData, CompletionEngine, CompletionResult, TagCompletion}

object NativeCompletionDelayCheck:
  def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(60, TimeUnit.SECONDS)
  def main(args: Array[String]): Unit =
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(10, TimeUnit.SECONDS))
    var stage: Stage = null
    try
      val area = fx(new TextArea())
      var calls = 0
      val source = new CompletionSource:
        def ensureLoaded(): Unit = ()
        def previews: Boolean = false
        override def completionDelayMs: Int = 300
        def complete(text: String, caret: Int, start: Int, end: Int): Option[CompletionResult] =
          calls += 1
          CompletionEngine.complete(CompletionData(tags = TagCompletion.parse(Iterator("blue_hair,0,20,", "red_hair,0,10,"))), TagCompleteSettings(), text, caret, start, end)
      val other = fx(new TextArea())
      val completion = fx {
        val c = new TagAutocomplete(area, source)
        stage = new Stage(); stage.setScene(new Scene(new javafx.scene.layout.VBox(area, other), 600, 300)); stage.show(); area.requestFocus(); c
      }
      fx { area.setText("bl"); area.positionCaret(2); require(calls == 0, s"completion must wait: $calls") }
      Thread.sleep(100)
      fx { area.setText("blue"); area.positionCaret(4); require(calls == 0) }
      Thread.sleep(500)
      fx { require(calls == 1 && completion.isShowing, s"only the last input should be queried: $calls") }
      fx { area.setText("red"); area.positionCaret(3); require(!completion.isShowing, "old suggestions must disappear immediately") }
      fx { Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)) }
      Thread.sleep(500)
      fx { require(calls == 1 && !completion.isShowing, s"Escape cancels the pending query: $calls") }
      fx { area.setText("bl"); area.positionCaret(2); other.requestFocus() }
      Thread.sleep(500)
      fx { require(calls == 1 && !completion.isShowing, "focus loss cancels pending search"); area.requestFocus() }
      fx {
        area.setText("blue"); area.positionCaret(4)
        val runs = FXCollections.observableArrayList(new InputMethodTextRun("あ", InputMethodHighlight.UNSELECTED_RAW))
        Event.fireEvent(area, new InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, runs, "", 1))
      }
      Thread.sleep(500)
      fx { require(calls == 1 && completion.isComposing && !completion.isShowing, "IME composition cancels pending search") }
      println("NativeCompletionDelayCheck passed")
    finally
      if stage != null then fx(stage.close())
      Platform.exit()
