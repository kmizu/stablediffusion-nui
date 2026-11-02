package sdfx.ui

import java.nio.file.{Files, Paths}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.stage.Stage
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome, Settings, TagCompleteSettings}
import sdfx.core.model.GenerationParams
import sdfx.engine.{AppServices, EngineLaunch, Job, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

/** Actual MainWindow event dispatch and a running fake-worker generation. */
object RealCompletionEscapeCheck:
  def main(args: Array[String]): Unit =
    import NativeCompletionDelayCheck.fx
    require(args.length == 2, "python output-directory")
    val out = Paths.get(args(1)).toAbsolutePath; Files.createDirectories(out)
    val home = AppHome(Files.createTempDirectory(out, "escape-home-"))
    val tags = Files.writeString(out.resolve("tags.csv"), "blue_hair,0,10,\n")
    val settings = Settings(tagAutocompleteCsv = Some(tags.toString), tagComplete = TagCompleteSettings(completionDelayMs = 1000), outputDir = home.root.resolve("outputs").toString)
    val worker = Paths.get("mcp/src/test/resources/fake_engine.py").toAbsolutePath
    val services = new AppServices(home, settings, dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(args(0), worker.toString), home.root, Map.empty)), installShutdownHook = false)
    val ready = new CountDownLatch(1); Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start()
      window = fx { val w = new MainWindow(new Stage(), services, UiState(), CliOptions()); w.show(); w.select(Mode.Txt2Img); w }
      val job = Job(GenerationParams(prompt = "cat", seed = 7, width = 64, height = 64, steps = 50,
        extra = Map("step_delay" -> "0.05", "allow_no_model" -> "true")))
      val started = new CountDownLatch(1)
      val remove = services.queue.addListener { case p: JobEvent.Progress if p.jobId == job.id => started.countDown(); case _ => () }
      val result = services.queue.submit(job)
      try
        require(started.await(30, TimeUnit.SECONDS), "fake generation did not start")
        val until = System.nanoTime() + 5.seconds.toNanos
        while !fx(window.progress.get.running) && System.nanoTime() < until do Thread.sleep(20)
        fx {
          require(window.progress.get.running, "MainWindow must show generation in progress")
          val editor = window.panels(Mode.Txt2Img).prompts
          val area = editor.promptArea
          area.requestFocus(); area.setText("blu"); area.positionCaret(3)
          require(!editor.completionVisible, "delayed completion should not yet be visible")
          require(editor.completionPending, "positive completion should be pending")
          val negative = editor.negativeArea
          negative.requestFocus()
          require(!editor.completionPending, "moving focus cancels the positive pending completion")
          negative.setText("blu"); negative.positionCaret(3)
          require(editor.completionPending, "negative completion should be pending")
          Event.fireEvent(negative, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
          require(!editor.completionPending, "Escape cancels the negative pending completion")
          area.requestFocus(); area.setText("blue"); area.positionCaret(4)
          require(editor.completionPending, "positive completion should be pending again")
          Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
          require(!editor.completionPending, "Escape cancels the positive pending completion")
        }
        Thread.sleep(1300)
        fx { require(!window.panels(Mode.Txt2Img).prompts.completionVisible, "Escape must cancel delayed completion") }
        val output = Await.result(result, 20.seconds)
        require(output.images.nonEmpty, "generation must finish after dismissing delayed completion")
        println("REAL_COMPLETION_ESCAPE_OK")
      finally remove()
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
