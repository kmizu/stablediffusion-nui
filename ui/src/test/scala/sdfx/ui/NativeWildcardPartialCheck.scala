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
import scala.jdk.CollectionConverters.*

object NativeWildcardPartialCheck:
  def main(args: Array[String]): Unit =
    val done = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable]()
    Platform.startup(() => {
      val area = new TextArea()
      val data = CompletionData(wildcards = Vector("colors/warm", "colors/cold"))
      var linesEnabled = false
      var delayed: Option[Either[Throwable, Vector[String]] => Unit] = None
      var context = 0
      var errors = 0
      val source = new CompletionSource:
        def ensureLoaded(): Unit = ()
        def previews: Boolean = false
        override def wildcardLines(name: String)(done: Either[Throwable, Vector[String]] => Unit): Unit =
          if linesEnabled then delayed = Some(done) else done(Right(Vector.empty))
        override def wildcardContext: Any = context
        override def wildcardError(error: Throwable): Unit = errors += 1
        override def maxResults: Int = 2
        def complete(text: String, caret: Int, start: Int, end: Int): Option[CompletionResult] =
          CompletionEngine.complete(data, TagCompleteSettings(), text, caret, start, end)
      val completion = new TagAutocomplete(area, source)
      val stage = new Stage()
      stage.setScene(new Scene(new StackPane(area), 600, 300))
      stage.show(); area.requestFocus()
      Platform.runLater(() => {
        try
          area.setText("before, __col, after")
          area.positionCaret("before, __col".length)
          require(completion.isShowing, "wildcard popup must open")
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false))
          require(area.getText == "before, __colors/, after", s"partial Tab must retain both choices: ${area.getText}")
          require(completion.isShowing, "partial Tab must keep the popup open")
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false))
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false))
          require(area.getText == "before, __colors/cold__, after", s"explicit selection must complete: ${area.getText}")
          area.setText("__colors/warm__"); area.positionCaret("__col".length)
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false))
          require(area.getText == "__colors/warm__", s"Tab must preserve the existing suffix: ${area.getText}")
          area.setText("__COL"); area.positionCaret(area.getLength)
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false))
          require(area.getText == "__COLors/", s"case-insensitive matching must extend existing text: ${area.getText}")
          area.setText("__col")
          area.positionCaret(area.getLength)
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
          require(area.getText == "__colors/warm__", s"Enter preserves direct insertion: ${area.getText}")
          linesEnabled = true
          area.setText("__col"); area.positionCaret(area.getLength)
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
          require(area.getText == "__colors/warm__", "wildcard reference is inserted before loading lines")
          require(delayed.nonEmpty, "selected file must be loaded asynchronously")
          delayed.get(Right(Vector("red fox", "blue fox", "green fox"))); delayed = None
          require(completion.isShowing && completion.suggestions.map(_.label) == Vector("red fox", "blue fox"), "show file lines")
          val nextButton = javafx.stage.Window.getWindows.asScala.iterator.filter(_.isShowing)
            .flatMap(w => Option(w.getScene).flatMap(s => Option(s.lookup("#tac-lines-next"))))
            .collectFirst { case b: javafx.scene.control.Button => b }.getOrElse(throw new IllegalStateException("next-page button missing"))
          nextButton.fire()
          require(completion.suggestions.map(_.label) == Vector("green fox"), "next page respects max results")
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
          require(area.getText == "green fox", s"line selection must replace reference: ${area.getText}")
          area.setText("__col"); area.positionCaret(area.getLength)
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
          val stale = delayed.get; delayed = None
          area.setText("user changed prompt"); area.positionCaret(area.getLength)
          stale(Right(Vector("obsolete")))
          require(!completion.suggestions.exists(_.label == "obsolete"), "stale result must not replace newer edits")
          area.setText("__col"); area.positionCaret(area.getLength)
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
          val cancelled = delayed.get; delayed = None
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
          cancelled(Left(new RuntimeException("dismissed")))
          require(errors == 0, "stale errors must not be shown")
          require(!completion.suggestions.exists(_.label == "dismissed"), "Escape must dismiss a pending read")
          area.setText("__col"); area.positionCaret(area.getLength)
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
          val oldSettings = delayed.get; delayed = None
          context += 1
          oldSettings(Right(Vector("old settings")))
          require(!completion.suggestions.exists(_.label == "old settings"), "settings changes invalidate pending reads")
          area.setText("__col"); area.positionCaret(area.getLength)
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
          delayed.get(Right(Vector.fill(5001)("a line"))); delayed = None
          val status = javafx.stage.Window.getWindows.asScala.iterator.filter(_.isShowing)
            .flatMap(w => Option(w.getScene).flatMap(s => Option(s.lookup("#tac-lines-status"))))
            .collectFirst { case l: javafx.scene.control.Label => l }.getOrElse(throw new IllegalStateException("line status missing"))
          require(status.getText.contains("first 5,000 lines only"), s"truncated lines must be disclosed: ${status.getText}")
          println("NativeWildcardPartialCheck passed")
        catch case e: Throwable => failure.set(e)
        finally { stage.close(); done.countDown() }
      })
    })
    require(done.await(30, TimeUnit.SECONDS), "Native wildcard check timed out")
    Platform.exit()
    if failure.get() != null then throw failure.get()
