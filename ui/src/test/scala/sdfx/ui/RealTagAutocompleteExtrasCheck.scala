package sdfx.ui

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.event.Event
import javafx.scene.control.{Button, ButtonType, CheckBox, DialogPane, TextArea, TextField}
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.stage.{PopupWindow, Stage, Window}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.infotext.Infotext
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.Mode

/** Real MainWindow, real engine: the new tag-autocomplete sources (translation, extra file, chants, wildcards,
  * LoRA names with trigger words and preview) are configured through the real Tags dialog, used in the real prompt
  * editor with key events, and the completed prompt is generated.
  * Args: output-directory python checkpoint [lora.safetensors]. No downloads.
  */
object RealTagAutocompleteExtrasCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)

  private def eventually(label: String, seconds: Int = 60)(condition: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !condition && System.nanoTime() < end do Thread.sleep(50)
    require(condition, s"Timed out: $label")

  private var checks = 0
  private def check(label: String)(ok: Boolean): Unit =
    checks += 1
    require(ok, s"CHECK FAILED: $label")
    println(s"  ok $checks: $label")

  def main(args: Array[String]): Unit =
    require(args.length >= 3, "output-directory python checkpoint [lora]")
    val output = Paths.get(args(0)).toAbsolutePath.normalize()
    val python = Paths.get(args(1)).toAbsolutePath.normalize()
    val checkpoint = Paths.get(args(2)).toAbsolutePath.normalize()
    val loraSource = args.lift(3).map(p => Paths.get(p).toAbsolutePath.normalize())
    Files.createDirectories(output)
    val run = Files.createTempDirectory(output, "tac-extras-")
    val home = AppHome(Files.createDirectories(run.resolve("home")))
    val wildcardDir = Files.createDirectories(home.root.resolve("wildcards"))
    Files.writeString(wildcardDir.resolve("animals.txt"), "a red fox\n")
    Files.writeString(wildcardDir.resolve("hair.txt"), "short hair\n")
    Files.createDirectories(wildcardDir.resolve("colors"))
    Files.writeString(wildcardDir.resolve("colors/warm.txt"), (Vector("orange") ++ (2 to 15).map(n => s"item $n")).mkString("\n"))
    Files.writeString(wildcardDir.resolve("colors/cold.txt"), "blue\n")
    val files = Files.createDirectories(run.resolve("data"))
    val tagCsv = files.resolve("tags.csv")
    Files.writeString(tagCsv, "blue_hair,4,20,azure_hair\nblue_eyes,0,10,\nred_(dress),0,5,\n")
    val translationCsv = files.resolve("translation.csv")
    Files.writeString(translationCsv, "blue_hair,青い髪\nblue_eyes,青い目\n", java.nio.charset.StandardCharsets.UTF_8)
    val extraCsv = files.resolve("extra.csv")
    Files.writeString(extraCsv, "masterpiece,0,Quality tag,\n")
    val chantsJson = files.resolve("chants.json")
    Files.writeString(chantsJson, """[{"name":"Basic-HighQuality","terms":"Best,High,Quality","content":"(best quality, highres)","color":1}]""")
    val loraDir = Files.createDirectories(run.resolve("lora"))
    val loraStem = loraSource.map(_.getFileName.toString.stripSuffix(".safetensors"))
    loraSource.foreach { src =>
      Files.copy(src, loraDir.resolve(src.getFileName), StandardCopyOption.REPLACE_EXISTING)
      Files.writeString(loraDir.resolve(loraStem.get + ".json"), """{"activation text": "sidecar trigger", "preferred weight": 0.8}""")
      val img = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB)
      for x <- 0 until 96; y <- 0 until 64 do img.setRGB(x, y, if x < 48 then 0xD02020 else 0x2060D0)
      ImageIO.write(img, "png", loraDir.resolve(loraStem.get + ".preview.png").toFile)
    }
    val engineDir = Paths.get("python").toAbsolutePath.normalize()
    val sdxl = checkpoint.getFileName.toString.toLowerCase.contains("sdxl")
    val settings = Settings(pythonExe = Some(python.toString), outputDir = home.root.resolve("outputs").toString,
      modelDirs = ModelDirs(checkpoints = Seq(checkpoint.getParent.toString), lora = Seq(loraDir.toString)),
      hfOffline = true, previewEvery = 1, previewMethod = "approx")
    val launch = () => EngineLaunch(Seq(python.toString, "-m", "sdfx"), engineDir,
      Map("PYTHONPATH" -> engineDir.toString, "HF_HUB_OFFLINE" -> "1", "TRANSFORMERS_OFFLINE" -> "1",
          "OPENBLAS_NUM_THREADS" -> "1", "OMP_NUM_THREADS" -> "1", "USE_TF" -> "0"))
    val services = new AppServices(home, settings, dryRun = false, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(launch), persistSettings = false, timeouts = EngineTimeouts(load = 10.minutes), installShutdownHook = false)
    val events = new ConcurrentLinkedQueue[JobEvent]()
    services.queue.addListener(e => events.add(e))
    val started = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); started.countDown() })
    require(started.await(30, TimeUnit.SECONDS), "JavaFX startup timed out")
    var window: MainWindow = null
    var failure: Throwable = null
    def shot(name: String): Unit =
      window.root.applyCss(); window.root.layout()
      require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", run.resolve(name).toFile))
    def popupShot(name: String): Unit =
      Window.getWindows.asScala.collect { case p: PopupWindow if p.isShowing && p.getScene != null => p }
        .zipWithIndex.foreach((p, i) => ImageIO.write(SwingFXUtils.fromFXImage(p.getScene.snapshot(null), null), "png", run.resolve(s"$name-$i.png").toFile))
    def key(area: TextArea, code: KeyCode): Unit =
      Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    try
      require(!services.start().dryRun)
      val loaded = services.loadCheckpoint(checkpoint.toString, remember = false)
      window = fx {
        val w = new MainWindow(new Stage(), services, sdfx.ui.vm.UiState(), CliOptions(), _ => ())
        w.show(); w.select(Mode.Txt2Img); w.stage.setWidth(1280); w.stage.setHeight(850)
        w.model.checkpoint.set(loaded.name)
        w
      }
      val editor = fx { window.panels(Mode.Txt2Img).prompts }
      val area = editor.promptArea
      val negative = editor.negativeArea
      fx { area.requestFocus() }
      loraStem.foreach(stem => eventually("LoRA catalog") { fx { window.catalogVM.snapshot.get.loras.exists(_.name.contains(stem)) } })

      // 1. Configure everything through the real dialog.
      Platform.runLater(() => editor.toolbar.getChildren.asScala.collectFirst { case b: Button if b.getText == "Tags…" => b }.get.fire())
      eventually("Tags dialog") { fx { Window.getWindows.asScala.exists { case s: Stage => s.getTitle == "Tag autocomplete"; case _ => false } } }
      fx {
        val dialog = Window.getWindows.asScala.collectFirst { case s: Stage if s.getTitle == "Tag autocomplete" => s }.get
        val root = dialog.getScene.getRoot.asInstanceOf[DialogPane]
        def field(id: String) = root.lookup("#" + id).asInstanceOf[TextField]
        field("tac-tags").setText(tagCsv.toString); field("tac-translation").setText(translationCsv.toString)
        field("tac-extra").setText(extraCsv.toString); field("tac-chants-file").setText(chantsJson.toString)
        root.lookup("#tac-colormap").asInstanceOf[TextArea].setText("""{"tags":{"4":["red","blue"],"1":["gold","purple"]},"extra":{"0":["lime","orange"]}}""")
        check("dialog exposes the new options")(Seq("tac-underscores", "tac-escape", "tac-comma", "tac-alias", "tac-wildcards",
          "tac-networks", "tac-chants", "tac-trigger", "tac-preview", "tac-legacy", "tac-extra-after").forall(id => root.lookup("#" + id).isInstanceOf[CheckBox]))
        ImageIO.write(SwingFXUtils.fromFXImage(dialog.getScene.snapshot(null), null), "png", run.resolve("01-dialog.png").toFile)
        root.getButtonTypes.asScala.find(_.getText == "Save").map(root.lookupButton).get.asInstanceOf[Button].fire()
      }
      eventually("data loaded") { fx { window.ctx.tagDictionary.data.translations.size == 2 && window.ctx.tagDictionary.data.chants.size == 1 &&
        window.ctx.tagDictionary.data.extra.size == 1 && window.ctx.tagDictionary.tags.size == 3 && window.ctx.tagDictionary.data.wildcards.size == 4 } }
      check("dialog Save persisted paths in settings")(services.settings.tagComplete.translationCsv.contains(translationCsv.toString) &&
        services.settings.tagAutocompleteCsv.contains(tagCsv.toString) && services.settings.tagComplete.chantsJson.contains(chantsJson.toString))
      fx { area.requestFocus() }
      eventually("prompt focus") { fx { area.isFocused } }

      // The real dictionary scans the real wildcard roots and reads the chosen file asynchronously.
      fx {
        area.setText("__col"); area.positionCaret(area.getLength)
        key(area, KeyCode.TAB)
        check("partial wildcard path")(area.getText == "__colors/")
        area.insertText(area.getCaretPosition, "w")
        key(area, KeyCode.ENTER)
        check("wildcard reference inserted")(area.getText == "__colors/warm__")
      }
      eventually("wildcard file lines") { fx { editor.completionItems.exists(_.label == "orange") } }
      def clickPopup(selector: String): Unit = fx {
        val node = Window.getWindows.asScala.collect { case p: PopupWindow if p.isShowing => p }.iterator
          .flatMap(w => Option(w.getScene).flatMap(s => Option(s.lookup(selector)))).next()
        val bounds = node.localToScreen(node.getBoundsInLocal)
        val robot = new javafx.scene.robot.Robot()
        robot.mouseMove((bounds.getMinX + bounds.getMaxX) / 2, (bounds.getMinY + bounds.getMaxY) / 2)
        robot.mouseClick(javafx.scene.input.MouseButton.PRIMARY)
      }
      clickPopup("#tac-lines-next")
      eventually("mouse next page") { fx { editor.completionItems.headOption.exists(_.label == "item 13") } }
      fx {
        check("mouse navigation keeps prompt focus")(area.isFocused)
        popupShot("pop-wildcard-lines")
        key(area, KeyCode.ENTER)
        check("Enter inserts line after real mouse navigation")(area.getText == "item 13")
        area.setText("__col"); area.positionCaret(area.getLength)
        key(area, KeyCode.TAB)
        area.insertText(area.getCaretPosition, "w")
        key(area, KeyCode.ENTER)
      }
      eventually("wildcard lines reopen") { fx { editor.completionItems.headOption.exists(_.label == "orange") } }
      fx {
        val cell = Window.getWindows.asScala.collect { case p: PopupWindow if p.isShowing => p }.iterator
          .flatMap(p => p.getScene.getRoot.lookupAll(".list-cell").asScala)
          .collectFirst { case c: javafx.scene.control.ListCell[?] if Option(c.getText).exists(_.contains("orange")) => c }.get
        val bounds = cell.localToScreen(cell.getBoundsInLocal)
        val robot = new javafx.scene.robot.Robot()
        robot.mouseMove((bounds.getMinX + bounds.getMaxX) / 2, (bounds.getMinY + bounds.getMaxY) / 2)
        robot.mouseClick(javafx.scene.input.MouseButton.PRIMARY)
      }
      eventually("mouse line inserted") { fx { area.getText == "orange" } }
      fx {
        check("mouse click inserts a line through the real dictionary")(area.getText == "orange")
        area.setText("")
      }

      // 2. Build the prompt with the real editor.
      def complete(a: TextArea, typed: String, label: String, down: Int = 0, expectPreview: Boolean = false): Unit =
        fx {
          val before = a.getText
          a.setText(before + typed); a.positionCaret(a.getText.length)
          check(s"popup for '$typed'")(editor.completionVisible && editor.completionItems.nonEmpty)
          check(s"first row of '$typed' is '$label'")(editor.completionItems(down).label == label)
          if label == "blue_hair" then check("translated tag retains category 4")(editor.completionItems(down).category.contains(4))
          if label == "Basic-HighQuality" then check("chant retains category 1")(editor.completionItems(down).category.contains(1))
          if Set("blue_hair", "Basic-HighQuality", "masterpiece")(label) then
            val popup = Window.getWindows.asScala.collectFirst { case p: PopupWindow if p.isShowing => p }.get
            popup.getScene.getRoot.applyCss(); popup.getScene.getRoot.layout()
            val cell = popup.getScene.getRoot.lookupAll(".list-cell").asScala.collectFirst {
              case c: javafx.scene.control.ListCell[?] if Option(c.getItem).exists(_.isInstanceOf[sdfx.ui.vm.Suggestion]) &&
                  c.getItem.asInstanceOf[sdfx.ui.vm.Suggestion].label == label => c
            }.get
            val expected = if label == "blue_hair" then javafx.scene.paint.Color.RED
              else if label == "Basic-HighQuality" then javafx.scene.paint.Color.GOLD else javafx.scene.paint.Color.LIME
            check(s"$label uses the correct source colour")(cell.getGraphic.asInstanceOf[javafx.scene.shape.Circle].getFill == expected)
          if expectPreview then check(s"preview window shown for '$typed'")(editor.previewVisible)
        }
        if expectPreview then
          // The picture loads in the background: wait until it is really decoded, and check its pixels (left half red).
          eventually("preview image decoded") { fx { editor.previewImage.exists(i => !i.isError && i.getProgress >= 1) } }
          fx {
            val img = editor.previewImage.get
            val left = img.getPixelReader.getColor(10, img.getHeight.toInt / 2)
            val right = img.getPixelReader.getColor(img.getWidth.toInt - 10, img.getHeight.toInt / 2)
            check("preview shows the picture next to the model (red left, blue right)")(left.getRed > 0.6 && left.getBlue < 0.4 && right.getBlue > 0.6 && right.getRed < 0.4)
          }
          Thread.sleep(300)
        fx {
          (0 until down).foreach(_ => key(a, KeyCode.DOWN))
          popupShot(s"pop-${typed.replaceAll("[^A-Za-z0-9]", "_")}")
          key(a, KeyCode.ENTER)
          check(s"popup closed after accept of '$typed'")(!editor.completionVisible && !editor.previewVisible)
        }
      fx { area.setText("") }
      complete(area, "mast", "masterpiece")
      fx { check("extra file tag inserted")(area.getText == "masterpiece") }
      complete(area, ", 青い髪", "blue_hair")
      fx { check("translation search inserts the English tag")(area.getText == "masterpiece, blue hair") }
      complete(area, ", <c:high", "Basic-HighQuality")
      fx { check("chant content inserted")(area.getText == "masterpiece, blue hair, (best quality, highres)") }
      complete(area, ", __ani", "animals")
      fx { check("wildcard inserted with closing __")(area.getText == "masterpiece, blue hair, (best quality, highres), __animals__") }
      val withoutLora = fx { area.getText }
      loraStem.foreach { stem =>
        complete(area, ", <l:" + stem.take(5), stem, expectPreview = true)
        fx {
          check("LoRA tag with sidecar trigger words")(area.getText == withoutLora + s", <lora:$stem:1.0>, sidecar trigger")
          shot("02-completed-prompt.png")
        }
      }
      if loraStem.isEmpty then fx { shot("02-completed-prompt.png") }
      // Negative prompt uses the same sources (alias display, wildcard and options).
      fx { negative.requestFocus() }
      eventually("negative focus") { fx { negative.isFocused } }
      fx {
        negative.setText("azure"); negative.positionCaret(5)
        check("alias row shown as alias -> tag")(editor.completionItems.map(_.label) == Vector("azure_hair ➝ blue_hair"))
        key(negative, KeyCode.ENTER)
        check("alias inserts the real tag")(negative.getText == "blue hair")
        negative.setText("__hai"); negative.positionCaret(5)
        key(negative, KeyCode.TAB)
        check("negative prompt wildcard")(negative.getText == "__hair__")
        negative.setText("<h:x"); negative.positionCaret(4)
        check("hypernetwork completion offers nothing (no loader)")(!editor.completionVisible)
        negative.setText("")
        area.requestFocus()
      }

      // 3. Generate the completed prompt through the ordinary Generate path.
      def generate(prompt: String, label: String): (Path, sdfx.core.infotext.ParsedInfotext) =
        events.clear()
        fx {
          val vm = window.vms(Mode.Txt2Img)
          vm.prompt.set(prompt); vm.negativePrompt.set("blurry")
          val size = if sdxl then (832, 1216) else (512, 512)
          vm.width.set(size._1); vm.height.set(size._2); vm.steps.set(8); vm.seed.set(4242)
          vm.batchSize.set(1); vm.batchCount.set(1); vm.cfgScale.set(6.0); vm.sampler.set("Euler a")
          window.plugins(Mode.Txt2Img).selectedScript.set("dynamic_prompts")
          window.plugins(Mode.Txt2Img).form("dynamic_prompts").get.props("wildcard_dirs").set(wildcardDir.toString)
          window.generate(false)
        }
        eventually(s"$label generation", 900) { events.asScala.exists(e => e.isInstanceOf[JobEvent.JobFinished] || e.isInstanceOf[JobEvent.JobFailed]) }
        val done = events.asScala.collectFirst { case e: JobEvent.JobFinished => e.output }
          .getOrElse(sys.error(s"$label failed: ${events.asScala.mkString("\n")}"))
        val image = done.images.head
        val text = PngMetadata.readParameters(image.path).getOrElse(sys.error("no parameters"))
        (image.path, Infotext.parse(text))
      val completed = fx { area.getText }
      val (pathA, infoA) = generate(completed, "completed prompt")
      Files.copy(pathA, run.resolve("generated-completed.png"), StandardCopyOption.REPLACE_EXISTING)
      check("PNG prompt keeps the completed text (wildcard resolved by Dynamic Prompts)")(
        infoA.prompt.contains("masterpiece, blue hair, (best quality, highres)") && infoA.prompt.contains("a red fox") && !infoA.prompt.contains("__animals__"))
      loraStem.foreach { stem =>
        // The first version of this check looked for a "Lora hashes" field; this app's infotext does not write one
        // (the tag itself is kept in the prompt), so the assertion was changed to the tag and the trigger words.
        check("PNG prompt carries the completed LoRA tag and the trigger words")(
          infoA.prompt.contains(s"<lora:$stem:1.0>") && infoA.prompt.contains("sidecar trigger"))
        val (pathB, _) = generate(withoutLora, "same prompt without the LoRA")
        Files.copy(pathB, run.resolve("generated-without-lora.png"), StandardCopyOption.REPLACE_EXISTING)
        val a = ImageIO.read(pathA.toFile); val b = ImageIO.read(pathB.toFile)
        var diff = 0L
        for y <- 0 until a.getHeight; x <- 0 until a.getWidth do
          val p = a.getRGB(x, y); val q = b.getRGB(x, y)
          diff += math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + math.abs((p & 255) - (q & 255))
        val mean = diff.toDouble / (a.getWidth.toLong * a.getHeight * 3)
        println(f"  mean abs pixel difference with/without the completed LoRA tag: $mean%.3f / 255")
        check("completed LoRA tag changes the image")(mean > 0.5)
        Files.writeString(run.resolve("lora-diff.txt"), f"$mean%.4f\n")
      }
      fx { shot("03-after-generation.png") }
      Files.writeString(run.resolve("report.json"), ujson.write(ujson.Obj("status" -> "passed", "checks" -> checks, "checkpoint" -> checkpoint.getFileName.toString,
        "family" -> loaded.kind, "lora" -> loraStem.fold("")(identity), "prompt" -> completed, "png_prompt" -> infoA.prompt), indent = 2))
      println(s"TAC_EXTRAS_OK checks=$checks family=${loaded.kind} artifacts=$run")
    catch case error: Throwable => failure = error
    finally
      NativeAcceptanceSafety.cleanup(failure)(
        () => if window != null then fx { window.dispose(); window.stage.close() },
        () => services.shutdown(), () => Platform.exit())
    if failure != null then throw failure
