package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.event.Event
import javafx.scene.{Node, Parent}
import javafx.scene.control.*
import javafx.scene.input.{MouseButton, MouseEvent, PickResult}
import javafx.stage.Stage
import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.png.{PngMetadata, ScrambledPng}
import sdfx.core.plugins.api.OutputImage
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, JobEvent}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}
import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, LinkedBlockingQueue, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Drives the real MainWindow Prompt Tools tab and generates on a real checkpoint with the prompts it builds.
  * Usage: RealPromptToolsCheck <python> <checkpoint> <output-dir> [side]
  */
object RealPromptToolsCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private def eventually(label: String)(test: => Boolean): Unit =
    val end = System.nanoTime() + 120.seconds.toNanos
    while !test && System.nanoTime() < end do Thread.sleep(40)
    require(test, s"Timed out: $label")
  private def nodes(node: Node): Seq[Node] = Seq(node) ++ (node match
    case p: TitledPane => Option(p.getContent).toSeq.flatMap(nodes)
    case p: ScrollPane => Option(p.getContent).toSeq.flatMap(nodes)
    case p: TabPane => p.getTabs.asScala.toSeq.flatMap(t => Option(t.getContent).toSeq.flatMap(nodes))
    case p: Parent => p.getChildrenUnmodifiable.asScala.toSeq.flatMap(nodes)
    case _ => Nil)

  private def mouse(target: Node, tpe: javafx.event.EventType[MouseEvent], button: MouseButton, ctrl: Boolean): Unit =
    val e = new MouseEvent(tpe, 1, 1, 1, 1, button, 1, false, ctrl, false, false,
      button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY, true, false, true, new PickResult(target, 1, 1))
    Event.fireEvent(target, e)

  def main(args: Array[String]): Unit =
    require(args.length >= 3, "python checkpoint output [side]")
    val python = Paths.get(args(0)).toAbsolutePath.normalize()
    val checkpoint = Paths.get(args(1)).toAbsolutePath.normalize()
    val output = Paths.get(args(2)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val engineDir = AppHome.detect().pythonDir
    val home = AppHome(Files.createTempDirectory(output, "prompt-tools-home-"))
    val services = new AppServices(home, Settings(pythonExe = Some(python.toString), vramMode = "model_offload",
      modelDirs = ModelDirs(checkpoints = Seq(checkpoint.getParent.toString)),
      outputDir = output.resolve("images").toString, mcpHttpEnabled = false), dryRun = false,
      builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false,
      launchOverride = Some(() => EngineLaunch(Seq(python.toString, "-m", "sdfx"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))), timeouts = EngineTimeouts(load = 10.minutes))
    val finished = new LinkedBlockingQueue[Either[String, Seq[OutputImage]]]()
    services.queue.addListener {
      case e: JobEvent.JobFinished => finished.offer(Right(e.output.images)); ()
      case e: JobEvent.JobFailed => finished.offer(Left(e.message)); ()
      case _ => ()
    }
    // tag folder prepared before the window exists (the default folder is not seeded when it already exists)
    val tagDir = services.promptTools.defaultTagFolder
    Files.createDirectories(tagDir)
    Files.writeString(tagDir.resolve("scene.yml"),
      "subject:\n  - portrait photograph of a woman astronaut\n  - portrait photograph of an old fisherman\n" +
      "light:\n  warm: golden hour light\n  cold: cold blue moonlight\n" +
      "detail:\n  - detailed\n  - sharp focus\n  - natural light\n" +
      "bad:\n  - blurry\n  - cartoon\n")
    Files.writeString(tagDir.resolve("broken.yml"), "oops:\n\tbad: 1\n")
    Files.writeString(tagDir.resolve("__config__.yml"), "sort:\n  - scene\n")
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    var images = Vector.empty[String]
    val log = Vector.newBuilder[String]
    def pass(s: String): Unit = { println("PASS " + s); log += s }
    def idle(): Unit = eventually("queue idle")(services.queue.isIdle && fx(!window.progress.get.running))
    def generate(): OutputImage =
      idle(); fx(window.statusBar.generateBtn.fire())
      val result = finished.poll(300, TimeUnit.SECONDS)
      require(result != null, "generation timed out")
      val image = result.fold(m => throw new IllegalStateException(m), _.last)
      images :+= image.path.toString
      idle(); image
    def rgb(image: OutputImage): Array[Int] =
      val d = ScrambledPng.read(image.path, None).image
      d.getRGB(0, 0, d.getWidth, d.getHeight, null, 0, d.getWidth)
    def find[T <: Node](id: String): T = window.scene.lookup("#" + id).asInstanceOf[T]
    def button(id: String): Button = find[Button](id)
    def text(id: String): TextInputControl = find[TextInputControl](id)
    def select(id: String): Unit = fx {
      val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
      tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == id).get)
      window.root.applyCss(); window.root.layout()
    }
    def page(id: String): Unit = fx {
      val mainTabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
      mainTabs.getSelectionModel.select(mainTabs.getTabs.asScala.find(_.getId == "prompt-tools-tab").get)   // Generate switches to txt2img
      val pages = window.scene.lookup("#prompt-tools-pages").asInstanceOf[TabPane]
      pages.getSelectionModel.select(pages.getTabs.asScala.find(_.getId == id).get)
      window.root.applyCss(); window.root.layout()
    }
    def shot(name: String): Unit =
      Thread.sleep(300)
      fx {
        window.root.applyCss(); window.root.layout()
        ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(name + ".png").toFile)
      }
    def tagButton(label: String): Button = {
      nodes(window.scene.lookup("#prompt-tools-tag-files")).collectFirst {
        case b: Button if b.getText == label && b.getStyleClass.contains("tag-button") => b
      }.getOrElse(throw new IllegalStateException("no tag button " + label))
    }
    def groupButton(label: String): Button = {
      nodes(window.scene.lookup("#prompt-tools-tag-files")).collectFirst {
        case b: Button if b.getText == label && b.getStyleClass.contains("tag-group-button") => b
      }.getOrElse(throw new IllegalStateException("no group button " + label))
    }
    val vm0 = () => window.vms(Mode.Txt2Img)
    def prompt: String = vm0().prompt.get
    def negative: String = vm0().negativePrompt.get
    def infotext(image: OutputImage): String = PngMetadata.readParameters(image.path).get
    try
      val hello = services.start()
      require(!hello.dryRun)
      val loaded = services.loadCheckpoint(checkpoint.toString, remember = false)
      require(Set("sd15", "sdxl")(loaded.kind), loaded.kind)
      val side = args.lift(3).map(_.toInt).getOrElse(if loaded.kind == "sdxl" then 768 else 512)
      val base = GenerationParams(prompt = "", negativePrompt = "", seed = 4242, steps = 20, width = side, height = side, cfgScale = 6, samplerName = "Euler")
      window = fx {
        val w = new MainWindow(new Stage(), services, UiState(), CliOptions())
        w.show(); w.model.checkpoint.set(loaded.name); w.vms(Mode.Txt2Img).load(base); w
      }
      select("prompt-tools-tab")
      eventually("tag files shown")(fx(window.scene.lookup("#prompt-tools-tag-files") != null) && nodes(window.scene.lookup("#prompt-tools-tag-files")).exists {
        case b: Button => b.getText == "detailed"
        case _ => false
      })
      // 1. tag-file order and the broken file is reported, not hidden
      val fileNames = fx(window.scene.lookup("#prompt-tools-tag-files").asInstanceOf[TabPane].getTabs.asScala.map(_.getText).toVector)
      require(fileNames == Vector("scene", "broken (!)"), fileNames.toString)
      pass(s"tag files in __config__ order with the broken file flagged: $fileNames")
      // 2. saved prompt -> prompt field
      page("prompt-tools-saved-tab")
      fx {
        text("prompt-tools-saved-text").setText("portrait photograph of a woman astronaut")
        text("prompt-tools-saved-name").setText("astronaut")
        button("prompt-tools-saved-save").fire()
      }
      eventually("saved list")(fx(find[ListView[?]]("prompt-tools-saved-list").getItems.size == 1))
      fx { button("prompt-tools-saved-use").fire() }
      require(prompt == "portrait photograph of a woman astronaut", prompt)
      pass("saved prompt 'astronaut' saved and used: " + prompt)
      // 3. tag buttons: click, random group, ctrl-click negative, right-click remove
      page("prompt-tools-tags-tab")
      fx { tagButton("detailed").fire() }
      require(prompt == "portrait photograph of a woman astronaut, detailed", prompt)
      fx { tagButton("natural light").fire(); tagButton("natural light").fire() }
      require(prompt == "portrait photograph of a woman astronaut, detailed, natural light", "duplicate click must not repeat: " + prompt)
      fx { groupButton("light").fire() }
      require(prompt.endsWith("golden hour light") || prompt.endsWith("cold blue moonlight"), prompt)
      val randomPick = if prompt.endsWith("golden hour light") then "golden hour light" else "cold blue moonlight"
      fx { mouse(tagButton("blurry"), MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, ctrl = true) }
      require(negative == "blurry" && !prompt.contains("blurry"), s"ctrl-click negative: '$negative' / '$prompt'")
      fx { mouse(tagButton("cartoon"), MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, ctrl = true) }
      require(negative == "blurry, cartoon", negative)
      fx { mouse(tagButton("cartoon"), MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, ctrl = false) }   // remove from positive: absent
      require(negative == "blurry, cartoon", "right-click removes from the positive field only: " + negative)
      fx { find[CheckBox]("prompt-tools-negative-target").setSelected(true) }
      fx { mouse(tagButton("cartoon"), MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, ctrl = false) }
      require(negative == "blurry", "right-click with the negative target removes: " + negative)
      fx { tagButton("cartoon").fire() }
      require(negative == "blurry, cartoon", negative)
      fx { find[CheckBox]("prompt-tools-negative-target").setSelected(false) }
      pass(s"tag buttons: click, duplicate skipped, random group pick ('$randomPick'), ctrl-click negative, right-click remove")
      // search
      fx { text("prompt-tools-tag-search").setText("moon") }
      val hits = fx(nodes(window.scene.lookup("#prompt-tools-tag-hits")).collect { case b: Button if b.getStyleClass.contains("tag-button") => b.getText })
      require(hits == Seq("cold"), hits.toString)
      fx { text("prompt-tools-tag-search").setText("") }
      pass("tag search 'moon' found the button 'cold' by its text")
      shot("tag-buttons")
      // 4. generation A with the built prompt
      val promptA = prompt; val negA = negative
      val a = generate()
      val infoA = infotext(a)
      require(infoA.startsWith(promptA) && infoA.contains("Negative prompt: " + negA), infoA.take(300))
      pass(s"generation A prompt '$promptA' / negative '$negA' stored in the PNG")
      // 5. history holds it; restore after wiping the fields gives the identical image
      page("prompt-tools-history-tab")
      eventually("history entry")(fx(find[ListView[?]]("prompt-tools-history-list").getItems.size == 1))
      fx { vm0().prompt.set(""); vm0().negativePrompt.set("") }
      fx {
        find[ListView[?]]("prompt-tools-history-list").getSelectionModel.select(0)
        button("prompt-tools-history-restore").fire()
      }
      require(prompt == promptA && negative == negA)
      val b = generate()
      require(java.util.Arrays.equals(rgb(a), rgb(b)), "history restore must reproduce the image exactly")
      pass("history entry restored both fields; regenerated image is pixel-identical (fixed seed)")
      require(fx(find[ListView[?]]("prompt-tools-history-list").getItems.size) == 1, "same pair is not duplicated in history")
      fx { find[ListView[?]]("prompt-tools-history-list").getSelectionModel.select(0); button("prompt-tools-history-bookmark").fire() }
      eventually("favourite")(fx(find[ListView[?]]("prompt-tools-favorites-list").getItems.size == 1))
      pass("history entry bookmarked as a favourite")
      page("prompt-tools-history-tab")   // generating switched to txt2img
      shot("history")
      // 6. tag editor: weight on 'detailed' changes the image
      page("prompt-tools-editor-tab")
      val idx = fx(vm0().prompt.get.split(", ").indexOf("detailed"))
      fx { find[ListView[?]]("prompt-tools-tag-list").getSelectionModel.select(idx); button("prompt-tools-tag-plus").fire() }
      fx { button("prompt-tools-tag-plus").fire(); button("prompt-tools-tag-plus").fire() }
      require(prompt.contains("(detailed:1.3)"), prompt)
      fx { button("prompt-tools-tag-up").fire() }
      require(prompt.split(", ")(idx - 1) == "(detailed:1.3)", prompt)
      shot("tag-editor")
      val promptC = prompt
      val c = generate()
      require(infotext(c).startsWith(promptC))
      require(!java.util.Arrays.equals(rgb(a), rgb(c)), "weighted tag must change the image")
      pass(s"tag editor: weight x3 and move changed the prompt to '$promptC'; the image differs from A")
      // blacklist + dedupe
      fx {
        text("prompt-tools-blacklist").setText("natural light")
        button("prompt-tools-blacklist-apply").fire()
      }
      require(!prompt.contains("natural light"), prompt)
      fx { vm0().prompt.set(prompt + ", " + "sharp focus, Sharp Focus"); button("prompt-tools-tag-dedupe").fire() }
      require(prompt.split(", ").count(_.equalsIgnoreCase("sharp focus")) == 1, prompt)
      pass("blacklist removed 'natural light'; duplicate 'sharp focus' collapsed: " + prompt)
      // 7. tag cards
      page("prompt-tools-cards-tab")
      fx {
        text("prompt-tools-card-category").setText("Quality\\Extra")
        text("prompt-tools-card-name").setText("crisp")
        text("prompt-tools-card-text").setText("crisp edges, 8k")
        button("prompt-tools-card-save").fire()
      }
      eventually("card button")(fx(window.scene.lookup("#prompt-tools-card-Quality-Extra-crisp") != null))
      require(Files.isRegularFile(services.promptTools.cards.dir.resolve("Quality/Extra/crisp.tag")))
      fx { window.scene.lookup("#prompt-tools-card-Quality-Extra-crisp").asInstanceOf[Button].fire() }
      require(prompt.endsWith("crisp edges, 8k"), prompt)
      shot("tag-cards")
      pass("tag card saved to Quality/Extra/crisp.tag and inserted into the prompt")
      // 8. generation D from the final prompt, fixed seed; also confirms the history now has 3 entries
      val d = generate()
      require(infotext(d).startsWith(prompt))
      eventually("history grew")(fx(services.promptTools.history.history.size == 3))
      pass(s"final generation D matches the prompt field; history has ${services.promptTools.history.history.size} entries")
      page("prompt-tools-saved-tab"); shot("saved-prompts")
      Files.writeString(output.resolve("native-report.json"), ujson.Obj("status" -> "passed-pending-visual-inspection",
        "model" -> loaded.kind, "images" -> ujson.Arr.from(images), "checks" -> ujson.Arr.from(log.result())).render(2))
    catch case error: Throwable =>
      Files.writeString(output.resolve("native-report.json"), ujson.Obj("status" -> "failed", "failure" -> error.toString,
        "checks" -> ujson.Arr.from(log.result())).render(2))
      throw error
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
