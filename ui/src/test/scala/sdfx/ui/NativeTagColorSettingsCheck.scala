package sdfx.ui

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.scene.control.{ComboBox, Spinner, TextArea}
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
        val delay = pane.node.lookup("#tac-delay").asInstanceOf[Spinner[Integer]]
        require(pane.read()._2.completionDelayMs == 0, "old settings default to immediate completion")
        delay.getEditor.setText("350")
        require(pane.read()._2.completionDelayMs == 350, "edited delay is retained")
        val modes = pane.node.lookup("#tac-wildcard-path-mode").asInstanceOf[ComboBox[String]]
        require(modes.getSelectionModel.getSelectedIndex == 0, "old settings default to first difference")
        modes.getSelectionModel.select(1)
        require(pane.read()._2.wildcardPathMode == "next-folder", "selected mode must be saved")
        modes.getSelectionModel.select(2)
        require(pane.read()._2.wildcardPathMode == "always-full", "full-completion mode must be saved")
        val future = new TagSettingsPane(None, TagCompleteSettings(wildcardPathMode = "future-mode"), stage)
        val futureModes = future.node.lookup("#tac-wildcard-path-mode").asInstanceOf[ComboBox[String]]
        require(futureModes.getValue.contains("future-mode"), "unknown saved mode must be visible")
        require(future.read()._2.wildcardPathMode == "future-mode", "unmodified unknown mode must be retained")
        futureModes.getSelectionModel.select(0)
        require(future.read()._2.wildcardPathMode == "first-difference", "explicit change replaces the unknown mode")
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
