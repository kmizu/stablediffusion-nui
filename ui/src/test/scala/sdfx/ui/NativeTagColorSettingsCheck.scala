package sdfx.ui

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.scene.control.TextArea
import javafx.stage.Stage
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.view.TagSettingsPane

object NativeTagColorSettingsCheck:
  def main(args: Array[String]): Unit =
    val done = new CountDownLatch(1); val failure = new AtomicReference[Throwable]()
    Platform.startup(() => {
      val stage = new Stage()
      try
        val pane = new TagSettingsPane(None, TagCompleteSettings(), stage)
        val field = Option(pane.node.lookup("#tac-colormap")).collect { case t: TextArea => t }
          .getOrElse(throw new IllegalStateException("custom colour settings are unavailable"))
        val valid = """{"dataset":{"4":["red","blue"]}}"""
        field.setText(valid)
        require(sdfx.core.json.SnakePickle.write(pane.read()._2).contains("dataset"), "valid settings must be retained")
        field.setText("{broken")
        require(scala.util.Try(pane.read()).isFailure, "invalid JSON must not be accepted")
        println("NATIVE_TAG_COLOUR_SETTINGS_OK")
      catch case e: Throwable => failure.set(e)
      finally { stage.close(); done.countDown() }
    })
    require(done.await(30, TimeUnit.SECONDS), "colour settings check timed out")
    Platform.exit()
    if failure.get() != null then throw failure.get()
