package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.*
import javafx.stage.Stage
import sdfx.core.config.{AppHome, LocalizationPreferences, Settings}
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Manual GUI gate: python output-dir. Uses the fake JSONL worker, not a real CLIP model. */
object RealAestheticEmbeddingCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then body
    else
      val task = new FutureTask[A](new Callable[A]:
        def call(): A = body)
      Platform.runLater(task)
      task.get(45, TimeUnit.SECONDS)

  private def eventually(label: String)(body: => Boolean): Unit =
    val end = System.nanoTime() + 60.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(40)
    require(body, "Timed out: " + label)

  def main(args: Array[String]): Unit =
    require(args.length == 2, "python output-directory")
    val python = Paths.get(args(0)).toAbsolutePath.normalize()
    val output = Paths.get(args(1)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val home = AppHome(Files.createTempDirectory(output, "aesthetic-home-"))
    val worker = Paths.get("mcp/src/test/resources/fake_engine.py").toAbsolutePath.normalize()
    val reference = output.resolve("reference.png")
    require(ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", reference.toFile))
    val services = new AppServices(home, Settings(outputDir = output.resolve("images").toString), dryRun = true,
      builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(python.toString, worker.toString), home.root, Map.empty)),
      persistSettings = false, timeouts = EngineTimeouts(load = 2.minutes), installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      services.start()
      window = fx {
        val w = new MainWindow(new Stage(), services, UiState(), CliOptions())
        w.stage.setWidth(1280); w.stage.setHeight(950); w.show()
        val tab = w.scene.lookup("#main-tabs").asInstanceOf[TabPane]
        tab.getTabs.asScala.find(_.getId == "aesthetic-embedding-tab").foreach(tab.getSelectionModel.select)
        w
      }
      def node[A](id: String): A = fx(window.scene.lookup("#" + id).asInstanceOf[A])
      def status: String = fx(node[Label]("aesthetic-status").getText)
      def fire(id: String): Unit = fx(node[Button](id).fire())
      val helpEnglish = "SDXL: strong weights can distort images. Try Aesthetic weight 0.3–0.5 first, then adjust for your result."
      val helpJapanese = "SDXL では重みが強いと画像が崩れることがあります。Aesthetic の重みを 0.3〜0.5 から試し、結果を見ながら調整してください。"
      def helpLabels: Vector[String] = fx(window.scene.getRoot.lookupAll(".label").asScala.collect {
        case label: Label => label.getText
      }.toVector)
      require(helpLabels.contains(helpEnglish), "creation tab must show the guidance")
      fx { window.select(Mode.Txt2Img); window.panels(Mode.Txt2Img).node.lookup("#plugin-aesthetic_gradients-section").asInstanceOf[TitledPane].setExpanded(true) }
      require(helpLabels.contains(helpEnglish), "generation form must show the guidance")
      fx { require(window.localization.apply(LocalizationPreferences(pack = "builtin/ja_JP")).isEmpty); window.localization.localizer.sweepNow() }
      eventually("Japanese generation guidance")(helpLabels.contains(helpJapanese))
      fx {
        val pane = window.panels(Mode.Txt2Img).node.lookup("#plugin-aesthetic_gradients-section").asInstanceOf[TitledPane]
        pane.setAnimated(false); pane.setExpanded(true)
        window.root.applyCss(); window.root.layout()
        val scroll = window.panels(Mode.Txt2Img).node
        val y = scroll.getContent.sceneToLocal(pane.localToScene(0, 0)).getY
        val range = (scroll.getContent.getLayoutBounds.getHeight - scroll.getViewportBounds.getHeight).max(1)
        scroll.setVvalue(((y - scroll.getViewportBounds.getHeight * 0.45) / range).max(0).min(1))
      }
      Thread.sleep(300)
      fx {
        val pane = window.panels(Mode.Txt2Img).node.lookup("#plugin-aesthetic_gradients-section").asInstanceOf[TitledPane]
        require(pane.isExpanded, "generation guidance section must be open")
        window.root.applyCss(); window.root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve("aesthetic-guidance-plugin-ja.png").toFile))
      }
      fx {
        val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
        tabs.getTabs.asScala.find(_.getId == "aesthetic-embedding-tab").foreach(tabs.getSelectionModel.select)
        window.localization.localizer.sweepNow()
      }
      eventually("Japanese creation guidance")(helpLabels.contains(helpJapanese))
      fx {
        window.root.applyCss(); window.root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve("aesthetic-guidance-creation-ja.png").toFile))
      }
      fx { require(window.localization.apply(LocalizationPreferences()).isEmpty); window.localization.localizer.sweepNow() }
      // Capture the unpopulated real JavaFX form without machine-specific paths.
      fx {
        window.root.applyCss(); window.root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve("aesthetic-ui-empty.png").toFile))
      }
      val refs = node[ListView[Path]]("aesthetic-images")
      fx { refs.getItems.add(reference); node[TextField]("aesthetic-name").setText("style") }
      fire("aesthetic-create")
      eventually("embedding created")(status.startsWith("Created "))
      val selected = fx(node[ComboBox[String]]("aesthetic-embeddings").getValue)
      require(Files.isRegularFile(Paths.get(selected)), "worker did not publish the selected file")
      require(Paths.get(selected).startsWith(output.resolve("images").resolve("aesthetic_embeddings")))
      fire("aesthetic-refresh")
      eventually("list refreshed")(status.startsWith("Found "))
      require(fx(node[ComboBox[String]]("aesthetic-embeddings").getItems.asScala.contains(selected)))
      fx(node[TextField]("aesthetic-name").setText("cancellable"))
      fire("aesthetic-create")
      eventually("cancel available")(!fx(node[Button]("aesthetic-cancel").isDisabled))
      eventually("worker is executing")(Files.exists(output.resolve("images/aesthetic_embeddings/.cancellable-started")))
      fire("aesthetic-cancel")
      eventually("cancelled request settled")(fx(node[Button]("aesthetic-cancel").isDisabled))
      require(status.toLowerCase.contains("interrupt") || status.toLowerCase.contains("cancel"), status)
      require(!Files.exists(output.resolve("images/aesthetic_embeddings/cancellable.pt")), "cancelled output was written")
      fx(node[TextField]("aesthetic-name").setText("recovered"))
      fire("aesthetic-create")
      eventually("creation after cancellation")(status.startsWith("Created "))
      require(Files.isRegularFile(output.resolve("images/aesthetic_embeddings/recovered.pt")))
      fx(node[ComboBox[String]]("aesthetic-embeddings").setValue(selected))
      val form = window.plugins(Mode.Txt2Img).form("aesthetic_gradients").get
      fx(form.prop("weight").get.set("0.37"))
      fire("aesthetic-use")
      require(fx(form.prop("embedding").get.get) == selected)
      require(fx(form.prop("enabled").get.get) == "true")
      require(fx(form.prop("weight").get.get) == "0.37", "Use must preserve the user's weight")
      val handDir = Files.createDirectories(output.resolve("handpicked"))
      val handFile = Files.writeString(handDir.resolve("handpicked.pt"), "hand-picked fixture")
      fx { form.prop("embedding").get.set(handFile.toString); form.prop("enabled").get.set("true") }
      val handParams = fx(services.generator.applyAlwaysOn(sdfx.core.model.GenerationParams(), Seq(form.plugin -> form.values)))
      services.generator.prepare(handParams)
      val handInfo = "test\nSteps: 1, Seed: 1, Size: 64x64, Aesthetic embedding: handpicked"
      fx(window.restore(handInfo))
      eventually("hand-entered embedding replay")(fx(form.prop("enabled").get.get == "true"))
      require(Paths.get(fx(form.prop("embedding").get.get)).toRealPath() == handFile.toRealPath())
      val info = "test\nSteps: 1, Sampler: Euler, CFG scale: 7, Seed: 1, Size: 64x64, Aesthetic embedding: style, Aesthetic weight: 0.9, Aesthetic steps: 20, Aesthetic LR: 0.0001"
      fx {
        window.restore(info)
        // A later edit must invalidate even an already completed background lookup.
        form.prop("weight").get.set("0.31")
      }
      Thread.sleep(500)
      require(fx(form.prop("weight").get.get) == "0.31")
      require(fx(form.prop("enabled").get.get) == "false", "stale restore re-enabled the plugin")
      fx(window.restore(info))
      eventually("unique aesthetic replay")(fx(form.prop("enabled").get.get == "true"))
      require(Paths.get(fx(form.prop("embedding").get.get)).toRealPath() == Paths.get(selected).toRealPath())
      fx(window.restore(info + ", Aesthetic embedding SHA256: ambiguous"))
      require(fx(form.prop("enabled").get.get) == "false", "ambiguous fingerprint must disable an earlier active plugin")
      fx(window.restore(info.replace("Aesthetic embedding: style", "Aesthetic embedding: missing-style")))
      eventually("missing aesthetic warning")(fx(window.ctx.toaster.layer.lookupAll(".label").asScala.exists {
        case label: Label => label.getText.contains("Aesthetic embedding not restored")
        case _ => false
      }))
      require(fx(form.prop("enabled").get.get) == "false")
      Thread.sleep(300) // let the toaster's fade-in complete before taking the screenshot
      fx {
        window.root.applyCss(); window.root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve("aesthetic-missing-toast.png").toFile))
      }
      fx {
        val tab = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
        tab.getTabs.asScala.find(_.getId == "aesthetic-embedding-tab").foreach(tab.getSelectionModel.select)
        window.services.imageScrambleSession.unlock("test-only")
        node[TextField]("aesthetic-name").setText("protected")
      }
      fire("aesthetic-create")
      require(status.contains("protected image session"), status)
      require(!Files.exists(output.resolve("images/aesthetic_embeddings/protected.pt")))
      fx {
        window.root.applyCss(); window.root.layout()
        require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve("aesthetic-ui.png").toFile))
      }
      Files.writeString(output.resolve("report.txt"), s"GUI fake-worker check passed; created and applied $selected; cancel/recovery, unique replay, stale edit protection, missing warning and protected refusal passed.\n")
      println("GUI FAKE-WORKER CHECK PASSED")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown()
      Platform.exit()
