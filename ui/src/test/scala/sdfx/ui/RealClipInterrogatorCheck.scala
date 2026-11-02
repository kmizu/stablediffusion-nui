package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.*
import javafx.stage.Stage
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.engine.AppServices
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** MANUAL gate (real CLIP + BLIP, GPU). Args: python output-directory source-image [image-folder].
  * Drives the real MainWindow Interrogator tab: Prompt in several modes, Send to txt2img, Analyze, Batch, Unload,
  * Cancel, and writes screenshots plus a report into output-directory. A dry-run engine cannot interrogate, so this
  * uses the real engine without loading a checkpoint.
  */
object RealClipInterrogatorCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private def eventually(label: String, seconds: Int = 900)(body: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(50)
    require(body, "Timed out: " + label)

  def main(args: Array[String]): Unit =
    require(args.length >= 3, "python output-directory source-image [image-folder]")
    val output = Paths.get(args(1)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val source = Paths.get(args(2)).toAbsolutePath
    val home = AppHome(output.resolve("app-home"))
    Files.createDirectories(home.pythonDir)
    val services = new AppServices(home, Settings(pythonExe = Some(args(0))), dryRun = false,
      builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
    val started = new java.util.concurrent.CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); started.countDown() })
    require(started.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    def node[A](id: String): A = fx(window.scene.lookup("#" + id).asInstanceOf[A])
    def button(id: String): Button = node[Button](id)
    def text(id: String): String = fx(node[Control](id) match
      case t: TextArea => t.getText
      case l: Label => l.getText
      case other => other.toString)
    def shot(name: String): Unit = fx {
      window.scene.getRoot.applyCss(); window.scene.getRoot.layout()
      ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(name + ".png").toFile)
    }
    def select(page: String): Unit = fx {
      val pages = node[TabPane]("clip-pages")
      pages.getTabs.asScala.find(_.getId == "clip-page-" + page).foreach(pages.getSelectionModel.select)
    }
    def idle(): Unit = eventually("interrogator idle")(fx(button("clip-cancel").isDisabled))
    val report = ujson.Obj()
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show()
        val tabs = window.scene.getRoot.lookupAll(".tab-pane").toArray.collect { case t: TabPane => t }
        tabs.find(_.getTabs.stream().anyMatch(_.getId == "clip-interrogator-tab")).foreach(t =>
          t.getTabs.stream().filter(_.getId == "clip-interrogator-tab").findFirst().ifPresent(t.getSelectionModel.select))
      }
      // the model lists come from the engine the first time the tab is shown
      eventually("model list filled", 120)(fx(node[ComboBox[String]]("clip-model").getItems.size > 3))
      report("clip_models") = fx(node[ComboBox[String]]("clip-model").getItems.asScala.toSeq).mkString(", ")
      shot("clip-0-initial")

      // ---- Prompt: fast, then Send to txt2img
      fx {
        window.clipInterrogator.promptImage.set(source.toString)
        node[ComboBox[String]]("clip-mode").setValue("fast")
        node[CheckBox]("clip-keep-loaded").setSelected(true)
      }
      eventually("generate enabled")(fx(!button("clip-generate").isDisabled))
      require(fx(button("clip-to-txt2img").isDisabled), "Send must stay disabled until there is a prompt")
      fx(button("clip-generate").fire())
      eventually("prompt appears")(fx(text("clip-prompt-output").nonEmpty))
      idle()
      val fast = text("clip-prompt-output")
      require(fast.contains(","), fast)
      report("fast_prompt") = fast
      report("fast_status") = text("clip-status")
      require(text("clip-status").contains("similarity"), text("clip-status"))
      shot("clip-1-fast")
      fx(button("clip-to-txt2img").fire())
      require(fx(window.vms(Mode.Txt2Img).prompt.get) == fast, "prompt was not sent to txt2img")
      fx(window.vms(Mode.Txt2Img).prompt.set(""))

      // ---- best / negative: negative goes to the negative prompt field
      fx {
        window.clipInterrogator.promptImage.set(source.toString)
        node[ComboBox[String]]("clip-mode").setValue("negative")
        node[TextArea]("clip-prompt-output").clear()
        button("clip-generate").fire()
      }
      eventually("negative prompt")(fx(text("clip-prompt-output").nonEmpty))
      idle()
      val negative = text("clip-prompt-output")
      report("negative_prompt") = negative
      fx(button("clip-to-txt2img").fire())
      require(fx(window.vms(Mode.Txt2Img).negativePrompt.get) == negative, "negative result must go to the negative prompt")
      require(fx(window.vms(Mode.Txt2Img).prompt.get).isEmpty, "negative result must not touch the positive prompt")
      fx {
        node[ComboBox[String]]("clip-mode").setValue("best")
        node[TextArea]("clip-prompt-output").clear()
        button("clip-generate").fire()
      }
      eventually("best prompt")(fx(text("clip-prompt-output").nonEmpty))
      idle()
      report("best_prompt") = text("clip-prompt-output")
      shot("clip-2-best")

      // ---- Analyze
      select("analyze")
      fx {
        window.clipInterrogator.analyzeImage.set(source.toString)
        button("clip-analyze").fire()
      }
      eventually("analysis")(fx(node[TableView[(String, Double)]]("clip-analyze-flavor").getItems.size == 5))
      idle()
      val tables = Seq("medium", "artist", "movement", "trending", "flavor").map(k =>
        k -> fx(node[TableView[(String, Double)]]("clip-analyze-" + k).getItems.asScala.toSeq))
      tables.foreach((k, rows) => require(rows.size == 5 && rows.map(_._2) == rows.map(_._2).sorted.reverse, k))
      report("analysis") = ujson.Obj.from(tables.map((k, rows) => k -> (ujson.Arr.from(rows.map((l, s) => ujson.Str(f"$l ($s%.3f)"))): ujson.Value)))
      shot("clip-3-analyze")

      // ---- Batch (optional folder)
      args.lift(3).foreach { folderArg =>
        val folder = Paths.get(folderArg).toAbsolutePath
        select("batch")
        fx {
          node[TextField]("clip-batch-folder").setText(folder.toString)
          node[ComboBox[String]]("clip-batch-mode").setValue("fast")
          button("clip-batch-go").fire()
        }
        eventually("batch result")(fx(text("clip-batch-result").nonEmpty))
        idle()
        report("batch_result") = text("clip-batch-result")
        shot("clip-4-batch")
      }

      // ---- Unload, then Cancel in the middle of a best-mode run
      fx(button("clip-unload").fire())
      eventually("unload status")(fx(text("clip-status").startsWith("Models unloaded") || text("clip-status").startsWith("No models")))
      idle()
      report("unload_status") = text("clip-status")
      select("prompt")
      fx {
        node[CheckBox]("clip-keep-loaded").setSelected(false)
        node[ComboBox[String]]("clip-mode").setValue("best")
        node[TextArea]("clip-prompt-output").clear()
        window.clipInterrogator.promptImage.set(source.toString)
        button("clip-generate").fire()
      }
      eventually("running", 60)(fx(!button("clip-cancel").isDisabled))
      Thread.sleep(1500)
      fx(button("clip-cancel").fire())
      idle()
      report("cancel_status") = text("clip-status")
      require(text("clip-prompt-output").isEmpty, "a cancelled run must not produce a prompt")
      // the engine recovers: a normal run still works
      fx(node[ComboBox[String]]("clip-mode").setValue("classic"))
      fx(button("clip-generate").fire())
      eventually("recovers after cancel")(fx(text("clip-prompt-output").nonEmpty))
      idle()
      report("after_cancel_prompt") = text("clip-prompt-output")
      report("success") = true
      println(report.render(indent = 2))
    finally
      Files.writeString(output.resolve("report.json"), report.render(indent = 2))
      if window != null then fx(window.dispose())
      services.shutdown()
      Platform.exit()
