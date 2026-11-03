package sdfx.ui

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Node
import javafx.scene.control.*
import javafx.stage.{Stage, Window}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, Settings}
import sdfx.core.presets.{FieldKey, PresetAtom, PresetError, PresetJson, PresetLimits, PresetRecord}
import sdfx.core.protocol.ModelEntry
import sdfx.core.plugins.api.ChoiceSource
import sdfx.engine.{AppServices, CatalogSnapshot}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}
import sdfx.ui.view.W
import sdfx.ui.presets.GenerationHistoryStore

/** Root-run offline acceptance of the real MainWindow and named-state buttons/dialogs.
  * No engine startup, inference, model download or clipboard access. Arg: output directory.
  */
object RealNamedGenerationStatesCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(30, TimeUnit.SECONDS)

  private def eventually(label: String)(condition: => Boolean): Unit =
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while !condition && System.nanoTime() < end do Thread.sleep(25)
    require(condition, s"Timed out: $label")

  def main(args: Array[String]): Unit =
    require(args.length == 1, "output-directory")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val home = AppHome(Files.createTempDirectory(output, "named-states-home-"))
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS), "JavaFX startup timed out")
    var services: AppServices = null
    var window: MainWindow = null
    var failure: Throwable = null
    var checks = Vector.empty[String]
    var screenshots = Vector.empty[String]
    val mode = Mode.Img2Img
    val library = home.configDir.resolve("generation-states/library-v1.json")
    val image = output.resolve("named-state-current-input.png")
    val bitmap = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
    ImageIO.write(bitmap, "png", image.toFile)

    def checked(label: String): Unit =
      checks :+= label
      println("NAMED STATES PASS: " + label)
    def stage: Stage = window.namedStatesStage.getOrElse(sys.error("Named-state stage unavailable"))
    def node[A <: Node](id: String): A =
      val found = stage.getScene.lookup("#" + id)
      require(found != null, s"Actual control missing: $id")
      found.asInstanceOf[A]
    def noWork(): Unit =
      require(services.supervisor.pid.isEmpty && services.supervisor.loadedModel.isEmpty,
        "Named-state operation started the engine or loaded a model")
      require(services.queue.isIdle && services.payloads.snapshots.isEmpty,
        "Named-state operation submitted generation work")
    def screenshot(name: String): Unit = fx {
      stage.getScene.getRoot.applyCss()
      stage.getScene.getRoot.layout()
      val path = output.resolve(name + ".png")
      require(ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene.snapshot(null), null), "png", path.toFile))
      screenshots :+= path.toString
    }
    def open(): Unit =
      services = new AppServices(home, Settings(), dryRun = true,
        builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
      window = fx {
        val created = new MainWindow(new Stage(), services, UiState(), CliOptions(), _ => ())
        created.show()
        created.catalogVM.snapshot.set(CatalogSnapshot(checkpoints = Seq(ModelEntry("fixture-model", "offline-model", "sd15"))))
        created.catalogVM.choices(ChoiceSource.Upscalers).setAll("R-ESRGAN 4x+", "Lanczos")
        created.select(mode)
        created.openGenerationStates(mode)
        created
      }
      eventually("library available") { fx { !node[Button]("generation-states-save").isDisabled } }
      noWork()
    def close(): Unit =
      if window != null then
        fx { window.dispose(); window.stage.close() }
        window = null
      if services != null then
        services.shutdown()
        services = null
    def select(name: String): Unit = fx {
      val list = node[ListView[String]]("generation-states-list")
      require(list.getItems.contains(name), s"Saved state is missing from visible list: $name")
      list.getSelectionModel.select(name)
    }
    def save(name: String): Unit =
      fx {
        node[TextField]("generation-states-name").setText(name)
        node[Button]("generation-states-save").fire()
      }
      eventually(s"saved $name") { fx {
        !node[Button]("generation-states-save").isDisabled &&
          node[ListView[String]]("generation-states-list").getItems.contains(name)
      }}
    def answerButton(id: String, answer: ButtonType): Unit =
      val task = new FutureTask[Unit](new Callable[Unit]:
        def call(): Unit = node[Button](id).fire())
      Platform.runLater(task)
      eventually(s"confirmation for $id") { fx {
        Window.getWindows.asScala.exists(w => w.isShowing && w.getScene != null &&
          w.getScene.getRoot.isInstanceOf[DialogPane]) || task.isDone
      }}
      fx {
        val pane = Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene))
          .map(_.getRoot).collectFirst { case dialog: DialogPane => dialog }
          .getOrElse(sys.error("Expected real confirmation dialog; status: " + node[TextArea]("generation-states-status").getText))
        pane.lookupButton(answer).asInstanceOf[Button].fire()
      }
      task.get(30, TimeUnit.SECONDS)
    def confirmButton(id: String): Unit = answerButton(id, ButtonType.OK)

    try
      open()
      fx {
        val vm = window.vms(mode)
        vm.prompt.set("named state native roundtrip 日本語")
        vm.width.set(640)
        vm.height.set(512)
        vm.seed.set(123456789L)
        vm.eta.set(Some(0.35))
        window.model.checkpoint.set("fixture-model")
        window.model.clipSkip.set(2)
        val freeu = window.plugins(mode).form("freeu").get
        freeu.prop("enabled").get.set("false")
        freeu.prop("b1").get.set("1.37")
      }
      save("native roundtrip")
      require(Files.isRegularFile(library), "Save did not persist the separate named-state library")
      require(!Files.exists(home.configDir.resolve("native-presets/library-v1.json")), "Named states touched native preset storage")
      val parsed = PresetJson.readNative(Files.readAllBytes(library), PresetLimits()).toOption.get
      require(parsed.collections.exists(_.id == "state-img2img"), "Saved mode was lost")
      checked("actual Save persists a mode-specific full snapshot in separate storage")
      select("native roundtrip")
      screenshot("named-states-saved")
      fx {
        val vm = window.vms(mode)
        vm.prompt.set("changed prompt")
        vm.width.set(768)
        vm.height.set(768)
        vm.eta.set(None)
        vm.initImage.set(image.toString)
        window.plugins(mode).form("freeu").get.prop("b1").get.set("1.8")
      }
      confirmButton("generation-states-restore")
      fx {
        val vm = window.vms(mode)
        require(vm.prompt.get == "named state native roundtrip 日本語")
        require(vm.width.get == 640 && vm.height.get == 512)
        require(vm.eta.get.contains(0.35))
        require(vm.initImage.get == image.toString, "Restoring settings changed current init image")
        require(window.plugins(mode).form("freeu").get.prop("enabled").get.get == "false")
        require(window.plugins(mode).form("freeu").get.prop("b1").get.get.toDouble == 1.37)
        require(window.model.clipSkip.get == 2)
      }
      noWork()
      checked("actual Restore confirmation recovers dimensions, Eta and disabled FreeU values while retaining init image")
      fx {
        window.catalogVM.snapshot.set(CatalogSnapshot())
        window.vms(mode).prompt.set("must remain untouched")
        window.vms(mode).width.set(896)
        node[Button]("generation-states-restore").fire()
        require(window.vms(mode).prompt.get == "must remain untouched" && window.vms(mode).width.get == 896)
        require(node[TextArea]("generation-states-status").getText.contains("model.checkpoint"))
      }
      screenshot("named-states-missing-model")
      checked("missing checkpoint visibly blocks Restore without partial form mutation")
      // A single field must remain usable even when another saved resource is missing.
      fx {
        val picker = node[ComboBox[String]]("generation-states-field")
        require(picker.getItems.contains("generation.prompt"))
        picker.setValue("generation.prompt")
        val scene = window.stage.getScene
        val height = scene.lookup("#generation-height-spinner-img2img").asInstanceOf[Spinner[Integer]]
        val steps = scene.lookup("#generation-steps-spinner-img2img").asInstanceOf[Spinner[Integer]]
        height.getEditor.setText("1024")
        steps.getEditor.setText("invalid draft")
      }
      fx {
        node[ComboBox[String]]("generation-states-field").setValue("model.checkpoint")
        node[Button]("generation-states-restore-field").fire()
        require(node[TextArea]("generation-states-status").getText.contains("model.checkpoint"))
        require(window.stage.getScene.lookup("#generation-height-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "1024")
        require(window.stage.getScene.lookup("#generation-steps-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "invalid draft")
        node[ComboBox[String]]("generation-states-field").setValue("generation.prompt")
      }
      screenshot("named-states-single-field")
      answerButton("generation-states-restore-field", ButtonType.CANCEL)
      fx {
        require(window.vms(mode).prompt.get == "must remain untouched")
        require(window.stage.getScene.lookup("#generation-height-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "1024")
        require(window.stage.getScene.lookup("#generation-steps-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "invalid draft")
      }
      confirmButton("generation-states-restore-field")
      fx {
        require(window.vms(mode).prompt.get == "named state native roundtrip 日本語")
        require(window.vms(mode).width.get == 896, "single-field restore changed another field")
        require(window.vms(mode).height.get == 512, "single-field restore committed an unrelated numeric draft")
        val scene = window.stage.getScene
        require(scene.lookup("#generation-height-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "1024")
        require(scene.lookup("#generation-steps-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "invalid draft")
      }
      checked("single-field Restore ignores unrelated missing checkpoint and preserves other settings")
      fx {
        window.vms(mode).prompt.set("category draft test")
        Seq("sampling", "model", "controlnet", "scripts", "other").foreach(c =>
          node[CheckBox]("generation-states-cat-" + c).setSelected(false))
      }
      confirmButton("generation-states-restore")
      fx {
        require(window.vms(mode).prompt.get == "named state native roundtrip 日本語")
        require(window.stage.getScene.lookup("#generation-height-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "1024")
        require(window.stage.getScene.lookup("#generation-steps-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "invalid draft")
        Seq("sampling", "model", "controlnet", "scripts", "other").foreach(c =>
          node[CheckBox]("generation-states-cat-" + c).setSelected(true))
      }
      checked("category Restore preserves drafts outside the selected categories")
      fx { node[ComboBox[String]]("generation-states-field").setValue("generation.height") }
      confirmButton("generation-states-restore-field")
      fx {
        val height = window.stage.getScene.lookup("#generation-height-spinner-img2img").asInstanceOf[Spinner[Integer]]
        require(window.vms(mode).height.get == 512 && height.getEditor.getText == "512",
          "restoring an unchanged numeric field must replace its selected draft")
        require(window.stage.getScene.lookup("#generation-steps-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "invalid draft")
      }
      checked("single-field Restore replaces the selected numeric draft without touching other drafts")
      fx {
        window.panels(mode).sections(s"${mode.key}.plugin.freeu").setExpanded(true)
        val row = window.panels(mode).content.lookup("#plugin-freeu-b1")
        require(row != null, "FreeU input missing")
        val spinner = row.lookup(".spinner").asInstanceOf[Spinner[java.lang.Double]]
        require(spinner != null, "FreeU numeric editor missing")
        spinner.getEditor.setText("1.8")
        node[ComboBox[String]]("generation-states-field").setValue("plugin.freeu.b1")
      }
      confirmButton("generation-states-restore-field")
      fx {
        val spinner = window.panels(mode).content.lookup("#plugin-freeu-b1").lookup(".spinner").asInstanceOf[Spinner[java.lang.Double]]
        require(spinner.getEditor.getText.toDouble == 1.37, s"selected plugin draft did not reset: text=${spinner.getEditor.getText}, value=${spinner.getValue}, owner=${W.numericOwner(spinner)}, prop=${window.plugins(mode).form("freeu").get.prop("b1").get.get}, status=${node[TextArea]("generation-states-status").getText}")
        require(window.plugins(mode).form("freeu").get.prop("b1").get.get.toDouble == 1.37)
        require(window.stage.getScene.lookup("#generation-steps-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "invalid draft")
        val clip = window.stage.getScene.getRoot.lookupAll(".spinner").asScala.collectFirst {
          case s: Spinner[?] if W.numericOwner(s).exists(_ eq window.model.clipSkip) => s
        }.getOrElse(sys.error("shared clip-skip spinner missing"))
        clip.getEditor.setText("3")
        node[ComboBox[String]]("generation-states-field").setValue("model.clipSkip")
      }
      confirmButton("generation-states-restore-field")
      fx {
        val clip = window.stage.getScene.getRoot.lookupAll(".spinner").asScala.collectFirst {
          case s: Spinner[?] if W.numericOwner(s).exists(_ eq window.model.clipSkip) => s
        }.get
        require(window.model.clipSkip.get == 2 && clip.getEditor.getText == "2", "shared clip skip draft did not reset")
      }
      checked("single-field Restore synchronizes selected plugin and shared numeric drafts")
      var detachedScriptSpinner: Spinner[Integer] = null
      fx {
        window.plugins(mode).selectedScript.set("scramble_prompts")
        val row = window.panels(mode).content.lookup("#plugin-scramble_prompts-count")
        require(row != null, "script numeric control missing")
        detachedScriptSpinner = row.lookup(".spinner").asInstanceOf[Spinner[Integer]]
        detachedScriptSpinner.getEditor.setText("9")
        window.plugins(mode).selectedScript.set("")
        node[ComboBox[String]]("generation-states-field").setValue("plugin.scramble_prompts.count")
      }
      confirmButton("generation-states-restore-field")
      fx { require(detachedScriptSpinner.getEditor.getText == "8", "detached script draft did not reset") }
      checked("single-field Restore synchronizes a detached script editor")
      fx {
        node[TabPane]("generation-states-tabs").getSelectionModel.select(1)
        require(node[ComboBox[String]]("generation-states-field").getValue == null,
          "field selection from the previous record remains on an empty history")
        require(node[Button]("generation-states-restore-field").isDisabled)
        node[TabPane]("generation-states-tabs").getSelectionModel.select(0)
      }
      close()
      open()
      select("native roundtrip")
      fx { window.vms(mode).initImage.set(image.toString) }
      confirmButton("generation-states-restore")
      fx {
        require(window.vms(mode).prompt.get == "named state native roundtrip 日本語")
        require(window.vms(mode).width.get == 640)
        require(window.vms(mode).initImage.get == image.toString)
        require(window.plugins(mode).form("freeu").get.prop("b1").get.get.toDouble == 1.37)
      }
      checked("new MainWindow and AppServices on the same home reload and restore disk snapshot")
      screenshot("named-states-reopened")
      confirmButton("generation-states-delete")
      eventually("delete persisted") { fx {
        !node[Button]("generation-states-save").isDisabled &&
          !node[ListView[String]]("generation-states-list").getItems.contains("native roundtrip")
      }}
      val deleted = PresetJson.readNative(Files.readAllBytes(library), PresetLimits()).toOption.get
      require(deleted.collections.flatMap(_.presets).isEmpty)
      noWork()
      checked("actual Delete confirmation removes the state from visible list and disk")
      val fixtureHistory = new GenerationHistoryStore(home.configDir.resolve("generation-history"), 50, thunk => thunk())
      def historyRecord(prompt: String): Unit =
        val latch = new CountDownLatch(1)
        var result: Either[PresetError, Option[PresetRecord]] = null
        fixtureHistory.record(mode, Map(FieldKey(Vector("generation", "prompt")) -> PresetAtom.Text(prompt)), r => {
          result = r; latch.countDown()
        })
        require(latch.await(20, TimeUnit.SECONDS) && result.isRight && result.toOption.get.nonEmpty)
      historyRecord("first favourite")
      historyRecord("second favourite")
      fixtureHistory.close()
      fx { window.openGenerationStates(mode); node[TabPane]("generation-states-tabs").getSelectionModel.select(1) }
      eventually("two history entries") { fx(node[ListView[PresetRecord]]("generation-history-list").getItems.size == 2) }
      fx {
        val list = node[ListView[PresetRecord]]("generation-history-list")
        list.getSelectionModel.clearAndSelect(0)
        window.openGenerationStates(Mode.Txt2Img)
        require(list.getItems.isEmpty && list.getSelectionModel.getSelectedItems.isEmpty, "old mode history remains actionable")
        require(node[Button]("generation-history-delete").isDisabled, "history actions active while loading")
        window.openGenerationStates(mode)
      }
      eventually("history reload after mode switch") { fx(node[ListView[PresetRecord]]("generation-history-list").getItems.size == 2) }
      checked("mode switch clears old history before asynchronous reload")
      fx {
        val list = node[ListView[PresetRecord]]("generation-history-list")
        list.getSelectionModel.clearAndSelect(0)
        node[TextField]("generation-history-search").setText("FIRST")
        require(list.getItems.size == 1 && list.getItems.get(0).fields.values.exists(_ == PresetAtom.Text("first favourite")))
        require(list.getSelectionModel.getSelectedItems.isEmpty, "filtered-out selection must not remain actionable")
      }
      screenshot("named-states-history-search")
      fx {
        val list = node[ListView[PresetRecord]]("generation-history-list")
        node[TextField]("generation-history-search").clear()
        require(list.getItems.size == 2)
      }
      checked("history search filters prompt and clears hidden selection")
      fx {
        val list = node[ListView[PresetRecord]]("generation-history-list")
        list.getSelectionModel.clearAndSelect(0); list.getSelectionModel.select(1)
        node[Button]("generation-history-favourite").fire()
      }
      eventually("favourites stored") { fx {
        node[TabPane]("generation-states-tabs").getSelectionModel.select(2)
        node[ListView[PresetRecord]]("generation-favourites-list").getItems.size == 2
      }}
      fx {
        val list = node[ListView[PresetRecord]]("generation-favourites-list")
        list.getSelectionModel.clearAndSelect(0)
        node[TextField]("generation-favourites-search").setText("FIRST, favourite")
        require(list.getItems.size == 1 && list.getSelectionModel.getSelectedItems.isEmpty)
        node[TextField]("generation-favourites-search").clear()
        require(list.getItems.size == 2)
      }
      checked("favourites search requires all terms and clears hidden selection")
      fx { node[ListView[PresetRecord]]("generation-favourites-list").getSelectionModel.clearAndSelect(0) }
      val renameTask = new FutureTask[Unit](new Callable[Unit]:
        def call(): Unit = node[Button]("generation-favourites-rename").fire())
      Platform.runLater(renameTask)
      eventually("rename dialog") { fx {
        Window.getWindows.asScala.exists(w => w.isShowing && w.getScene != null && w.getScene.getRoot.isInstanceOf[DialogPane]) || renameTask.isDone
      }}
      fx {
        val dialog = Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene))
          .map(_.getRoot).collectFirst { case d: DialogPane => d }.get
        dialog.lookup(".text-field").asInstanceOf[TextField].setText("favourite renamed")
        dialog.lookupButton(ButtonType.OK).asInstanceOf[Button].fire()
      }
      renameTask.get(30, TimeUnit.SECONDS)
      eventually("renamed in favourites and history") { fx {
        node[ListView[PresetRecord]]("generation-favourites-list").getItems.asScala.exists(_.name == "favourite renamed") &&
          node[ListView[PresetRecord]]("generation-history-list").getItems.asScala.exists(_.name == "favourite renamed")
      }}
      checked("history and favourite names change together")
      fx {
        node[TextField]("generation-favourites-search").setText("favourite renamed")
        val list = node[ListView[PresetRecord]]("generation-favourites-list")
        require(list.getItems.size == 1)
        list.getSelectionModel.clearAndSelect(0)
      }
      val filteredRename = new FutureTask[Unit](new Callable[Unit]:
        def call(): Unit = node[Button]("generation-favourites-rename").fire())
      Platform.runLater(filteredRename)
      eventually("filtered rename dialog") { fx {
        Window.getWindows.asScala.exists(w => w.isShowing && w.getScene != null && w.getScene.getRoot.isInstanceOf[DialogPane]) || filteredRename.isDone
      }}
      fx {
        val dialog = Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene))
          .map(_.getRoot).collectFirst { case d: DialogPane => d }.get
        dialog.lookup(".text-field").asInstanceOf[TextField].setText("new title")
        dialog.lookupButton(ButtonType.OK).asInstanceOf[Button].fire()
      }
      filteredRename.get(30, TimeUnit.SECONDS)
      eventually("renamed entry hidden by filter") { fx {
        val list = node[ListView[PresetRecord]]("generation-favourites-list")
        list.getItems.isEmpty && list.getSelectionModel.getSelectedItems.isEmpty &&
          node[TextArea]("generation-states-status").getText.contains("renamed")
      }}
      screenshot("named-states-rename-filtered")
      fx { node[TextField]("generation-favourites-search").clear() }
      eventually("renamed favourite visible again") { fx {
        node[ListView[PresetRecord]]("generation-favourites-list").getItems.asScala.exists(_.name == "new title")
      }}
      checked("renaming an entry out of the filter clears selection")
      fx {
        val list = node[ListView[PresetRecord]]("generation-favourites-list")
        list.getSelectionModel.clearAndSelect(0)
        node[ComboBox[String]]("generation-states-field").setValue("generation.prompt")
      }
      confirmButton("generation-states-restore-field")
      fx { require(window.vms(mode).prompt.get == "second favourite") }
      checked("multiple history entries become favourites and a favourite setting restores")
      screenshot("named-states-favourites")
      fx {
        val favourites = node[ListView[PresetRecord]]("generation-favourites-list")
        favourites.getSelectionModel.clearAndSelect(0); favourites.getSelectionModel.select(1)
        node[Button]("generation-favourites-toggle").fire()
      }
      eventually("both favourites removed") { fx(node[ListView[PresetRecord]]("generation-favourites-list").getItems.isEmpty) }
      fx {
        node[TabPane]("generation-states-tabs").getSelectionModel.select(1)
        val entries = node[ListView[PresetRecord]]("generation-history-list")
        require(entries.getItems.size == 2)
        entries.getSelectionModel.clearAndSelect(0)
        node[Button]("generation-history-favourite").fire()
      }
      eventually("one favourited") { fx {
        node[TabPane]("generation-states-tabs").getSelectionModel.select(2)
        node[ListView[PresetRecord]]("generation-favourites-list").getItems.size == 1
      }}
      fx { node[TabPane]("generation-states-tabs").getSelectionModel.select(1) }
      confirmButton("generation-history-clear")
      eventually("clear retains favourite") { fx(node[ListView[PresetRecord]]("generation-history-list").getItems.size == 1) }
      fx { node[ListView[PresetRecord]]("generation-history-list").getSelectionModel.clearAndSelect(0) }
      confirmButton("generation-history-delete")
      eventually("delete removes both groups") { fx {
        node[TabPane]("generation-states-tabs").getSelectionModel.select(2)
        node[ListView[PresetRecord]]("generation-favourites-list").getItems.isEmpty
      }}
      checked("multi-remove, clear and explicit delete respect favourite membership")
      val upstream = output.resolve("synthetic-state-manager-history.txt")
      val previewImage = new java.awt.image.BufferedImage(32, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
      val previewGraphics = previewImage.createGraphics()
      previewGraphics.setColor(java.awt.Color.RED); previewGraphics.fillRect(0, 0, 32, 16); previewGraphics.dispose()
      val previewBytes = new java.io.ByteArrayOutputStream()
      require(ImageIO.write(previewImage, "png", previewBytes))
      val previewUri = "data:image/png;base64," + java.util.Base64.getEncoder.encodeToString(previewBytes.toByteArray)
      val upstreamJson = s"""{"defaults":{"d":{"img2img/Prompt":"original default","img2img/Unrecognized setting":"keep"}},"entries":{"1700000000123":{"saveVersion":"2.0","type":"img2img","defaults":"d","groups":["history","favourites"],"quickSettings":{},"componentSettings":{"img2img/Prompt":"imported prompt"},"preview":"$previewUri"}}}"""
      val gzip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(upstream))
      gzip.write(upstreamJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)); gzip.close()
      def previewImport(answer: ButtonType): Unit =
        fx {
          window.ctx.chooseStateManagerImport = () => Some(upstream)
          node[TabPane]("generation-states-tabs").getSelectionModel.select(1)
          node[Button]("generation-history-import").fire()
        }
        eventually("import preview") { fx {
          Window.getWindows.asScala.exists(w => w.isShowing && w.getScene != null && w.getScene.getRoot.isInstanceOf[DialogPane])
        }}
        fx {
          val pane = Window.getWindows.asScala.filter(_.isShowing).flatMap(w => Option(w.getScene)).map(_.getRoot)
            .collectFirst { case p: DialogPane => p }.get
          require(pane.lookup("#state-manager-import-summary").asInstanceOf[TextArea].getText.contains("img2img/Unrecognized setting"), "preview did not warn about the unknown setting")
          require(ImageIO.write(SwingFXUtils.fromFXImage(pane.getScene.snapshot(null), null), "png", output.resolve("state-manager-import-preview.png").toFile))
          pane.lookupButton(answer).asInstanceOf[Button].fire()
        }
      previewImport(ButtonType.CANCEL)
      fx { require(node[ListView[PresetRecord]]("generation-history-list").getItems.isEmpty, "cancel imported history") }
      previewImport(ButtonType.OK)
      eventually("imported history") { fx(node[ListView[PresetRecord]]("generation-history-list").getItems.size == 1) }
      eventually("imported thumbnail") { fx {
        val item = node[ListView[PresetRecord]]("generation-history-list").getItems.get(0)
        Files.isRegularFile(home.configDir.resolve("generation-history/thumbs").resolve(item.id.toString + ".png")) &&
          node[TextArea]("generation-states-status").getText.contains("thumbnails saved: 1")
      }}
      screenshot("state-manager-import-thumbnail")
      fx {
        node[TabPane]("generation-states-tabs").getSelectionModel.select(2)
        val list = node[ListView[PresetRecord]]("generation-favourites-list")
        require(list.getItems.size == 1)
        require(list.getItems.get(0).source.get.originalFields.text.contains("Unrecognized setting"))
        list.getSelectionModel.clearAndSelect(0)
        node[ComboBox[String]]("generation-states-field").setValue("generation.prompt")
      }
      confirmButton("generation-states-restore-field")
      fx { require(window.vms(mode).prompt.get == "imported prompt") }
      fx {
        window.vms(mode).prompt.set("changed before imported restore")
        window.stage.getScene.lookup("#generation-steps-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.setText("unrelated bad draft")
      }
      confirmButton("generation-states-restore")
      fx {
        require(window.vms(mode).prompt.get == "imported prompt")
        require(window.stage.getScene.lookup("#generation-steps-spinner-img2img").asInstanceOf[Spinner[Integer]].getEditor.getText == "unrelated bad draft")
      }
      previewImport(ButtonType.OK)
      eventually("second import completed") { fx {
        !node[Button]("generation-history-import").isDisabled && node[TextArea]("generation-states-status").getText.contains("Imported")
      }}
      fx {
        require(node[ListView[PresetRecord]]("generation-history-list").getItems.size == 1, "repeat import duplicated history")
        node[TabPane]("generation-states-tabs").getSelectionModel.select(2)
        require(node[ListView[PresetRecord]]("generation-favourites-list").getItems.size == 1, "repeat import duplicated favourites")
      }
      checked("State Manager gzip import previews unknown settings, preserves provenance and restores a favourite")
      Files.writeString(output.resolve("named-states-native-report.json"), ujson.write(ujson.Obj(
        "status" -> "passed", "home" -> home.root.toString, "library" -> library.toString,
        "checks" -> ujson.Arr.from(checks), "screenshots" -> ujson.Arr.from(screenshots)), indent = 2))
    catch
      case error: Throwable =>
        failure = error
        if window != null then
          try screenshot("named-states-failure")
          catch case _: Throwable => ()
    finally
      NativeAcceptanceSafety.cleanup(failure)(() => close(), () => Platform.exit())
    if failure != null then throw failure
