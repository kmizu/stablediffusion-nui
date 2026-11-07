package sdfx.ui

import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.{Button, CheckBox, ComboBox, Label, TabPane, TextArea}
import javafx.stage.Stage
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, LocalizationPreferences, Settings}
import sdfx.engine.AppServices
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

/** Real MainWindow with OpenCLIP and BLIP, two already-generated SD source images. */
object RealOpenClipInterrogatorCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(60, TimeUnit.SECONDS)
  private def eventually(label: String, seconds: Int = 600)(body: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(50)
    require(body, s"Timed out: $label")
  def main(args: Array[String]): Unit =
    require(args.length == 5, "python output-directory clip-model sd15-image sdxl-image")
    val output = Paths.get(args(1)).toAbsolutePath.normalize(); Files.createDirectories(output)
    val home = AppHome(output.resolve("home")); Files.createDirectories(home.pythonDir)
    val services = new AppServices(home, Settings(pythonExe = Some(args(0)), hfOffline = true), dryRun = false,
      builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
    val started = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); started.countDown() })
    require(started.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start()
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        window.show()
        val tabs = window.scene.getRoot.lookupAll(".tab-pane").toArray.collect { case t: TabPane => t }
        tabs.find(_.getTabs.stream().anyMatch(_.getId == "clip-interrogator-tab")).foreach(t =>
          t.getTabs.stream().filter(_.getId == "clip-interrogator-tab").findFirst().ifPresent(t.getSelectionModel.select))
      }
      def node[A](id: String): A = fx(window.scene.lookup("#" + id).asInstanceOf[A])
      eventually("model registry", 120)(fx(node[ComboBox[String]]("clip-model").getItems.contains(args(2))))
      fx {
        node[ComboBox[String]]("clip-model").setValue(args(2))
        node[ComboBox[String]]("clip-caption-model").setValue("blip-base")
        node[ComboBox[String]]("clip-mode").setValue("fast")
        node[CheckBox]("clip-keep-loaded").setSelected(true)
        require(node[Label]("clip-dependency-notice").getText.contains("not all been tested"))
        require(node[Label]("clip-model-notice").getText.contains("earlier build"))
        require(node[Label]("clip-model-source").getText.contains("huggingface.co/laion/CLIP-convnext_base_w"))
        require(node[Label]("clip-card-license").getText.contains("2026-11-03") && node[Label]("clip-card-license").getText.contains("mit"))
      }
      val results = ujson.Arr()
      for (image, i) <- args.drop(3).zipWithIndex do
        fx {
          val tabs = window.scene.getRoot.lookupAll(".tab-pane").toArray.collect { case t: TabPane => t }
          tabs.find(_.getTabs.stream().anyMatch(_.getId == "clip-interrogator-tab")).foreach(t =>
            t.getTabs.stream().filter(_.getId == "clip-interrogator-tab").findFirst().ifPresent(t.getSelectionModel.select))
          window.clipInterrogator.promptImage.set(Paths.get(image).toAbsolutePath.toString)
          node[TextArea]("clip-prompt-output").clear()
          node[Button]("clip-generate").fire()
        }
        eventually(s"prompt $i")(fx(node[TextArea]("clip-prompt-output").getText.nonEmpty))
        eventually(s"idle $i")(fx(node[Button]("clip-cancel").isDisabled))
        fx {
          val prompt = node[TextArea]("clip-prompt-output").getText
          require(prompt.contains(","), prompt)
          if i == 1 then
            window.localization.apply(LocalizationPreferences(pack = "builtin/ja_JP"))
            window.localization.localizer.sweepNow()
            require(node[Label]("clip-model-notice").getText.contains("ライセンス"), "Japanese model notice missing")
            require(node[Label]("clip-card-license").getText.contains("ライセンス"), "Japanese card label missing")
          window.scene.getRoot.applyCss(); window.scene.getRoot.layout()
          require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(s"interrogator-$i.png").toFile))
          node[Button]("clip-to-txt2img").fire()
          require(window.vms(Mode.Txt2Img).prompt.get == prompt, "send-to-txt2img mismatch")
          window.scene.getRoot.applyCss(); window.scene.getRoot.layout()
          require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(s"gui-$i.png").toFile))
          results.value += ujson.Obj("image" -> image, "prompt" -> prompt)
        }
      Files.writeString(output.resolve("receipt.json"), ujson.write(results, indent = 2))
      println("REAL_OPENCLIP_GUI_OK")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
