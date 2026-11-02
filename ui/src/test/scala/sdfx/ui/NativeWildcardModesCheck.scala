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

/** Native popup and key events; no engine or GPU. */
object NativeWildcardModesCheck:
  def main(args: Array[String]): Unit =
    val done = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable]()
    Platform.startup(() => {
      val area = new TextArea()
      val data = CompletionData(wildcards = Vector("colors/warm", "costumes/blue"))
      var mode = "next-folder"
      val source = new CompletionSource:
        def ensureLoaded(): Unit = ()
        def previews: Boolean = false
        override def wildcardPathMode: String = mode
        def complete(text: String, caret: Int, start: Int, end: Int): Option[CompletionResult] =
          CompletionEngine.complete(data, TagCompleteSettings(), text, caret, start, end)
      val completion = new TagAutocomplete(area, source)
      val stage = new Stage()
      stage.setScene(new Scene(new StackPane(area), 600, 300)); stage.show(); area.requestFocus()
      Platform.runLater(() => {
        def key(code: KeyCode): Unit = Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
        def input(value: String): Unit = { area.setText(value); area.positionCaret(area.getLength) }
        try
          input("before, __co, after"); area.positionCaret("before, __co".length)
          require(completion.isShowing); key(KeyCode.TAB)
          require(area.getText == "before, __colors/, after", s"next folder should follow first result: ${area.getText}")
          input("__co"); key(KeyCode.DOWN); key(KeyCode.TAB)
          require(area.getText == "__costumes/", s"next folder should follow selected result: ${area.getText}")
          input("__colors/warm__"); area.positionCaret("__co".length); key(KeyCode.TAB)
          require(area.getText == "__colors/warm__", s"existing suffix must remain intact: ${area.getText}")
          input("__COL"); key(KeyCode.TAB)
          require(area.getText == "__COLors/", s"next folder should match without changing case: ${area.getText}")
          input("__colors/wa"); key(KeyCode.TAB)
          require(area.getText == "__colors/warm__", s"last path component should complete fully: ${area.getText}")
          input("__co"); key(KeyCode.ENTER)
          require(area.getText == "__colors/warm__", s"Enter must complete the selected file: ${area.getText}")
          mode = "always-full"
          input("__co"); key(KeyCode.TAB)
          require(area.getText == "__colors/warm__", s"always-full should choose first result: ${area.getText}")
          input("__co"); key(KeyCode.DOWN); key(KeyCode.TAB)
          require(area.getText == "__costumes/blue__", s"always-full should choose the highlighted file: ${area.getText}")
          println("NativeWildcardModesCheck passed")
        catch case e: Throwable => failure.set(e)
        finally { stage.close(); done.countDown() }
      })
    })
    require(done.await(30, TimeUnit.SECONDS), "Native wildcard modes timed out")
    Platform.exit()
    if failure.get() != null then throw failure.get()
