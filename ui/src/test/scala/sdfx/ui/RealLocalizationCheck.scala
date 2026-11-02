package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Node
import javafx.scene.control.*
import javafx.stage.{Stage, Window}
import sdfx.core.config.{AppHome, LocalizationPreferences, ModelDirs, Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.png.PngMetadata
import sdfx.core.infotext.Infotext
import sdfx.engine.JobEvent
import java.util.concurrent.LinkedBlockingQueue
import sdfx.core.i18n.*
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts}
import sdfx.plugins.StandardPlugins
import sdfx.ui.i18n.Localizer
import sdfx.ui.vm.{Mode, UiState}

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** MANUAL root-only gate. `python output-directory collect|verify`.
  *
  * Mounts the real `MainWindow` (dry-run engine, no GPU) and
  *  - `collect`: switches on a placeholder language, visits every tab and the Settings dialog and writes the English texts
  *    that were seen to `strings.json` (the work list for the built-in pack);
  *  - `verify`: drives the language features on the real window: built-in pack, bilingual layouts, restore, the Settings >
  *    Language tab, the store dialog (real downloads), fallback pack, all pinned upstream packs, with screenshots.
  */
object RealLocalizationCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body
    )
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)

  private def eventually(label: String, seconds: Int = 60)(body: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(40)
    require(body, "Timed out: " + label)

  private val Japanese = "[぀-ヿ一-鿿]".r
  private def isJapanese(text: String): Boolean = Japanese.findFirstIn(text).isDefined

  def main(args: Array[String]): Unit =
    require(args.length >= 3, "python output-directory collect|verify")
    val mode = args(2)
    val output = Paths.get(args(1)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val work = Files.createTempDirectory(output, "loc-run-")
    val home = if mode == "real" then AppHome.detect() else AppHome(Files.createTempDirectory(work, "app-home-"))
    val engineDir = AppHome.detect().pythonDir
    val realRun = mode == "real"
    val checkpoint = if realRun then Some(Paths.get(args(3)).toAbsolutePath) else None
    val settings =
      if realRun then Settings(outputDir = work.resolve("outputs").toString, pythonExe = Some(Paths.get(args(0)).toAbsolutePath.toString),
        modelDirs = ModelDirs(checkpoints = Seq(checkpoint.get.getParent.toString)), previewEvery = 0)
      else Settings(outputDir = work.resolve("outputs").toString, previewEvery = 0)
    val services =
      if realRun then new AppServices(home, settings, dryRun = false, builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false)
      else new AppServices(home, settings, dryRun = true, builtins = StandardPlugins.withOwnAxes,
        launchOverride = Some(() => EngineLaunch(Seq(Paths.get(args(0)).toAbsolutePath.toString, "-m", "sdfx", "--dry-run"), engineDir,
          Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))),
        persistSettings = false, timeouts = EngineTimeouts(load = 2.minutes), installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    def snapshot(name: String): Unit = fx {
      window.root.applyCss(); window.root.layout()
      require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(name).toFile))
    }
    try
      if realRun then
        Real.run(args, services, output, snapshot = name => snapshot(name), setWindow = w => window = w)
        Platform.exit()
        System.exit(0)
      window = fx { val w = new MainWindow(new Stage(), services, UiState(), CliOptions()); w.stage.setWidth(1700); w.stage.setHeight(1000); w.show(); w }
      services.start()
      mode match
        case "collect" => Collect.run(window, output)
        case "verify" => Verify.run(window, services, home, work, output, snapshot)
        case other => sys.error("unknown mode " + other)
      Platform.exit()
      System.exit(0)
    catch case e: Throwable =>
      e.printStackTrace()
      try snapshot("localization-FAILED.png") catch case _: Throwable => ()
      System.exit(1)

  private def mainTabs(window: MainWindow): Vector[Tab] =
    fx(window.scene.lookup("#main-tabs").asInstanceOf[TabPane].getTabs.asScala.toVector)

  private def dialogRoot(): Option[DialogPane] = fx {
    Window.getWindows.asScala.toVector.filter(w => w.isShowing && w.getScene != null).map(_.getScene.getRoot).collectFirst { case d: DialogPane => d }
  }

  private def closeDialogs(): Unit =
    fx {
      Window.getWindows.asScala.toVector.filter(w => w.isShowing && w.getScene != null && w.getScene.getRoot.isInstanceOf[DialogPane]).foreach { w =>
        val root = w.getScene.getRoot.asInstanceOf[DialogPane]
        Option(root.lookupButton(ButtonType.CANCEL)).orElse(Option(root.lookupButton(ButtonType.CLOSE))).foreach(_.asInstanceOf[Button].fire())
      }
    }
    eventually("dialogs closed", 20)(dialogRoot().isEmpty)

  private object Collect:
    private def probe: Translations =
      new LayeredTranslations(Seq(LocalizationPack.parse("probe", """{"__never__": "x"}""").toOption.get))

    /** The English texts of the whole window (every tab, every mode) as seen by a localizer that translates nothing. */
    def strings(window: MainWindow): Vector[String] =
      val localizer = window.localization.localizer
      fx(localizer.configure(probe, Display.Default, true, true))
      for tab <- mainTabs(window) do
        fx { window.scene.lookup("#main-tabs").asInstanceOf[TabPane].getSelectionModel.select(tab) }
        Thread.sleep(250)
        fx(localizer.sweepNow())
      fx(localizer.untranslated)

    def run(window: MainWindow, output: Path): Unit =
      val localizer = window.localization.localizer
      strings(window)
      // the settings dialog (modal): open from the FX thread without waiting, sweep, close
      for index <- 0 to 6 do
        Platform.runLater(() => window.openSettings(index))
        eventually("settings dialog", 20)(dialogRoot().isDefined)
        Thread.sleep(500)
        fx(localizer.sweepNow())
        closeDialogs()
      val collected = fx(localizer.untranslated)
      val obj = ujson.Obj.from(collected.map(s => s -> ujson.Str("")))
      Files.writeString(output.resolve("strings.json"), ujson.write(obj, indent = 1), java.nio.charset.StandardCharsets.UTF_8)
      println(s"COLLECTED ${collected.size} untranslated texts -> ${output.resolve("strings.json")}")

  private object Verify:
    private var report = Vector.empty[String]
    private def checked(message: String): Unit = { report :+= message; println("LOCALIZATION CHECK: " + message) }

    /** Every Labeled (text) and every Tab (title) below the root, in tree order, keyed by object identity. */
    private def englishTexts(window: MainWindow): Vector[(AnyRef, String)] = fx {
      val nodes = window.scene.getRoot.lookupAll("*").asScala.toVector
      val labeled = nodes.collect { case l: Labeled if l.getText != null => (l: AnyRef) -> l.getText }
      val tabs = nodes.collect { case t: TabPane => t.getTabs.asScala.toVector }.flatten.filter(_.getText != null).map(t => (t: AnyRef) -> t.getText)
      labeled ++ tabs
    }

    private def textNow(o: AnyRef): String = o match
      case l: Labeled => l.getText
      case t: Tab => t.getText
      case _ => ""

    private def snapshotScene(name: String, output: Path, scene: javafx.scene.Scene): Unit = fx {
      scene.getRoot.applyCss(); scene.getRoot.layout()
      require(ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png", output.resolve(name).toFile))
    }

    private def find[A <: Node](window: MainWindow, selector: String): A =
      val n = fx(window.scene.lookup(selector))
      require(n != null, "missing control " + selector)
      n.asInstanceOf[A]

    private def selectTab(window: MainWindow, title: String): Unit = fx {
      val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
      tabs.getSelectionModel.select(tabs.getTabs.asScala.find(t => Localizer.original(t) == title).getOrElse(sys.error("no tab " + title)))
    }

    def run(window: MainWindow, services: AppServices, home: AppHome, work: Path, output: Path, snapshot: String => Unit): Unit =
      val localizer = window.localization.localizer
      eventually("engine ready", 120)(services.supervisor.hello.isDefined)
      selectTab(window, "txt2img")
      Thread.sleep(1500)

      // ---- English baseline, and which texts change by themselves --------------------------------------------------
      val base = englishTexts(window)
      Thread.sleep(1500)
      val base2 = englishTexts(window).toMap
      val dynamic = base.filter((o, t) => base2.get(o).exists(_ != t)).map(_._1).toSet
      checked(s"English baseline: ${base.size} labeled texts and tab titles; ${dynamic.size} of them change by themselves (clocks, status) and are excluded from the restore comparison")
      val paramsBefore = fx(Mode.values.map(m => m -> window.vms(m).toParams).toMap)
      snapshot("loc-01-english.png")

      // ---- built-in Japanese pack ---------------------------------------------------------------------------------
      val ja = LocalizationPreferences(pack = "builtin/ja_JP")
      val problems = fx(window.localization.apply(ja))
      require(problems.isEmpty, "built-in pack problems: " + problems)
      fx(localizer.sweepNow())
      val generate = find[Button](window, ".generate-button")
      require(fx(generate.getText) == "▶  生成", "generate button: " + fx(generate.getText))
      val tabTitles = fx(window.scene.lookup("#main-tabs").asInstanceOf[TabPane].getTabs.asScala.map(t => Localizer.original(t) -> t.getText).toVector)
      println(tabTitles.map((o, t) => s"$o=$t").mkString(" | "))
      val tt = tabTitles.toMap
      require(tt("PNG Info") == "PNG 情報" && tt("Tokenizer") == "トークナイザー", tabTitles.toString)
      snapshot("loc-02-japanese-txt2img.png")
      for tab <- tabTitles.map(_._1) do { selectTab(window, tab); Thread.sleep(250); fx(localizer.sweepNow()) }
      selectTab(window, "img2img"); Thread.sleep(300); fx(localizer.sweepNow()); snapshot("loc-03-japanese-img2img.png")
      selectTab(window, "Inpaint"); Thread.sleep(300); fx(localizer.sweepNow()); snapshot("loc-04-japanese-inpaint.png")
      selectTab(window, "PNG Info"); Thread.sleep(300); fx(localizer.sweepNow()); snapshot("loc-05-japanese-pnginfo.png")
      selectTab(window, "Image Browser"); Thread.sleep(300); fx(localizer.sweepNow()); snapshot("loc-06-japanese-image-browser.png")
      selectTab(window, "txt2img"); Thread.sleep(300); fx(localizer.sweepNow())
      val (yes, no) = fx(localizer.coverage)
      val missing = fx(localizer.untranslated)
      checked(f"built-in ja_JP over the whole window (all tabs, all three modes): $yes texts translated, $no without translation (${100.0 * yes / (yes + no)}%.1f %% of the texts that contain letters); last sweep ${fx(localizer.lastSweepMillis)}%.1f ms")
      Files.writeString(output.resolve("untranslated-after-ja.json"), ujson.write(ujson.Obj.from(missing.map(m => m -> ujson.Str(""))), indent = 1), java.nio.charset.StandardCharsets.UTF_8)

      // tool tips and drop-down items
      val tooltips = fx(window.scene.getRoot.lookupAll("*").asScala.collect { case c: Control if c.getTooltip != null => c.getTooltip.getText }.toVector)
      val tipJa = tooltips.count(isJapanese)
      checked(s"tool tips on controls: $tipJa of ${tooltips.size} show Japanese")
      require(tipJa > 50, "expected many translated tool tips")
      val schedule = fx(window.scene.getRoot.lookupAll(".combo-box").asScala.collect { case c: ComboBox[?] => c.asInstanceOf[ComboBox[String]] }
        .find(c => c.getItems.contains("Karras") && c.getItems.contains("Automatic")).orNull)
      require(schedule != null, "schedule combo not found")
      val shown = fx(schedule.getButtonCell.getText)
      require(shown == "自動" && fx(schedule.getItems.contains("Automatic")) && fx(schedule.getValue) == "Automatic", s"drop-down shows '$shown'")
      checked(s"drop-down: the schedule box displays '自動' (its real button cell) while the item and the box value stay 'Automatic'")
      snapshot("loc-07-schedule-dropdown.png")

      // generation parameters are untouched by the translation
      val paramsJa = fx(Mode.values.map(m => m -> window.vms(m).toParams).toMap)
      require(paramsJa == paramsBefore, "generation parameters changed under localization")
      checked("GenerationParams of txt2img, img2img and Inpaint are identical with the English and the Japanese UI")

      // ---- bilingual layouts ---------------------------------------------------------------------------------------
      def withDisplay(style: DisplayStyle, order: DisplayOrder): Unit =
        fx(window.localization.apply(ja.copy(style = style.label, order = order.label))); Thread.sleep(300); fx(localizer.sweepNow())
      withDisplay(DisplayStyle.Stacked, DisplayOrder.TranslationFirst)
      require(fx(generate.getText) == "▶  生成\n▶  Generate", "stacked generate button: " + fx(generate.getText))
      snapshot("loc-08-bilingual-two-lines.png")
      withDisplay(DisplayStyle.Inline, DisplayOrder.OriginalFirst)
      require(fx(generate.getText) == "▶  Generate (▶  生成)", "inline original-first generate button: " + fx(generate.getText))
      snapshot("loc-09-bilingual-one-line-original-first.png")
      require(fx(schedule.getButtonCell.getText) == "Automatic (自動)", fx(schedule.getButtonCell.getText))
      checked("bilingual layouts on the real window: two lines (translation first), one line (original first); drop-down items follow (screenshots loc-08, loc-09)")

      // ---- back to English: every text restored exactly -----------------------------------------------------------
      fx(window.localization.apply(LocalizationPreferences()))
      Thread.sleep(500)
      val english2 = englishTexts(window)
      val stillJapanese = english2.filter((_, t) => isJapanese(t))
      require(stillJapanese.isEmpty, s"Japanese text left after switching off: ${stillJapanese.take(5).map(_._2)}")
      // texts the application itself changed while the Japanese UI was on (status lines, lazily built tabs) differ from the first baseline
      val appChanged = english2.filter((o, t) => base.exists((b, bt) => (b eq o) && bt != t) && !dynamic.contains(o))
      println("app-driven differences from the first baseline: " + appChanged.map(_._2.take(60)))
      // round trip: Japanese on and off again must give exactly the same English texts as the first time off
      fx(window.localization.apply(ja)); Thread.sleep(400); fx(localizer.sweepNow())
      fx(window.localization.apply(LocalizationPreferences())); Thread.sleep(500)
      val english3 = englishTexts(window).toMap
      val roundTrip = english2.filterNot((o, _) => dynamic.contains(o)).filter((o, t) => english3.get(o).exists(_ != t))
      require(roundTrip.isEmpty, s"round trip changed texts: ${roundTrip.take(5).map((o, t) => t + " -> " + english3(o))}")
      require(fx(schedule.getButtonCell == null && schedule.getCellFactory == null))
      require(fx(Mode.values.map(m => m -> window.vms(m).toParams).toMap) == paramsBefore)
      checked(s"switching the language off leaves no Japanese text (${english2.size} texts checked); a second on/off round trip reproduces all ${english2.size - dynamic.size} stable English texts exactly. ${appChanged.size} texts differ from the very first baseline only because the application updated them meanwhile (status lines of tabs that were built later): ${appChanged.map(_._2.take(50)).mkString("; ")}")
      snapshot("loc-10-english-again.png")

      // ---- Settings > Language, driven through the real dialog ------------------------------------------------------
      Platform.runLater(() => window.openSettings(6))
      eventually("settings dialog", 20)(dialogRoot().isDefined)
      Thread.sleep(500)
      val pack = fx(dialogRoot().get.lookup("#language-pack").asInstanceOf[ComboBox[Object]])
      val choices = fx(pack.getItems.asScala.map(c => pack.getConverter.toString(c)).toVector)
      require(choices.head == "None (English)" && choices.exists(_.contains("built in")), choices.toString)
      checked("Settings > Language lists: " + choices.mkString(" | "))
      fx { pack.getSelectionModel.select(choices.indexWhere(_.contains("built in"))) }
      val style = fx(dialogRoot().get.lookup("#language-style").asInstanceOf[ComboBox[String]])
      fx { style.setValue(DisplayStyle.Inline.label) }
      val status = fx(dialogRoot().get.lookup("#language-status").asInstanceOf[Label].getText)
      require(status.contains("translations"), status)
      checked("pack status line: " + status)
      snapshotScene("loc-11-settings-language-before-ok.png", output, fx(dialogRoot().get.getScene))
      fx { dialogRoot().get.lookupButton(ButtonType.OK).asInstanceOf[Button].fire() }
      eventually("settings dialog closed", 20)(dialogRoot().isEmpty)
      eventually("settings saved with the language", 20)(services.settings.localization.pack == "builtin/ja_JP")
      require(services.settings.localization.style == DisplayStyle.Inline.label)
      eventually("window translated after OK", 20)(fx(generate.getText) == "▶  生成 (▶  Generate)")
      checked("OK in the Settings dialog saved localization = " + services.settings.localization + " and translated the window at once")
      snapshot("loc-12-after-settings-ok.png")
      Platform.runLater(() => window.openSettings(6))
      eventually("settings dialog 2", 20)(dialogRoot().isDefined)
      Thread.sleep(300)
      val pack2 = fx(dialogRoot().get.lookup("#language-pack").asInstanceOf[ComboBox[Object]])
      fx { pack2.getSelectionModel.select(0) }
      closeDialogs()
      require(services.settings.localization.pack == "builtin/ja_JP" && fx(generate.getText) == "▶  生成 (▶  Generate)")
      checked("Cancel in the Settings dialog keeps the chosen language")

      // ---- upstream packs: the store dialog (real download), then every pinned pack through PackStore ----------------
      Platform.runLater(() => window.openSettings(6))
      eventually("settings dialog 3", 20)(dialogRoot().isDefined)
      Thread.sleep(300)
      fx { dialogRoot().get.lookup("#language-download").asInstanceOf[Button].fire() }
      def storeScene = Window.getWindows.asScala.find(w => w.isShowing && w.getScene != null && w.getScene.lookup("#language-store-list") != null).map(_.getScene)
      eventually("store dialog", 20)(fx(storeScene.isDefined))
      val scene = fx(storeScene.get)
      val list = fx(scene.lookup("#language-store-list").asInstanceOf[ListView[StorePack]])
      val idx = fx(list.getItems.asScala.indexWhere(_.id == "ja_JP (L4Ph)"))
      require(idx >= 0)
      fx { list.getSelectionModel.select(idx) }
      fx { scene.lookup("#language-store-download").asInstanceOf[Button].fire() }
      def resultText = fx(scene.lookup("#language-store-result").asInstanceOf[Label].getText)
      eventually("download finished", 90)(resultText.contains("ja_JP (L4Ph).json") || resultText.contains("失敗") || resultText.startsWith("Failed"))
      require(resultText.contains("ja_JP (L4Ph).json"), resultText) // the label itself is shown in the active (Japanese) UI language
      val loc = home.root.resolve("localizations")
      require(Files.isRegularFile(loc.resolve("ja_JP (L4Ph).json")) && Files.isRegularFile(loc.resolve("ja_JP (L4Ph).LICENSE.txt")) && Files.isRegularFile(loc.resolve("ja_JP (L4Ph).source.txt")))
      checked(s"store dialog downloaded the CC0 ja_JP pack ('$resultText'): pack + LICENSE.txt + source.txt in $loc")
      snapshotScene("loc-13-store-dialog.png", output, scene)
      closeDialogs() // store dialog
      closeDialogs() // settings

      // fallback pack behind the built-in one
      fx(window.localization.apply(ja.copy(fallbackPack = "ja_JP (L4Ph)")))
      for tab <- tabTitles.map(_._1) do { selectTab(window, tab); Thread.sleep(150); fx(localizer.sweepNow()) }
      val (yes2, no2) = fx(localizer.coverage)
      checked(f"built-in ja_JP plus the downloaded L4Ph ja_JP pack as fallback: $yes2 translated, $no2 without translation (built-in alone: $yes / $no)")
      require(yes2 >= yes)
      selectTab(window, "txt2img"); fx(localizer.sweepNow()); snapshot("loc-14-ja-with-fallback.png")

      val uiStrings = Collect.strings(window)
      checked(s"English texts of the whole window (all tabs): ${uiStrings.size}")
      val builtinJa = PackCatalog.loadBundled("ja_JP").fold(e => sys.error(e), identity)
      val builtinHits = uiStrings.count(s => builtinJa.translate(s).isDefined)
      val longStrings = uiStrings.filter(_.length > 100)
      val longHits = longStrings.count(s => builtinJa.translate(s).isDefined)
      checked(f"built-in ja_JP translates $builtinHits of ${uiStrings.size} distinct English texts (${100.0 * builtinHits / uiStrings.size}%.1f %%); of the ${longStrings.size} texts longer than 100 characters (long tool tips of extension controls) $longHits")
      val installDir = Files.createDirectories(work.resolve("all-packs"))
      val rows = Vector.newBuilder[String]
      for sp <- PackStore.catalog() do
        PackStore.install(sp, installDir) match
          case Left(e) => rows += s"| ${sp.id} | ${sp.license} | FAILED: $e |||||"
          case Right(path) =>
            val p = LocalizationPack.parse(sp.id, Files.readString(path)).fold(e => sys.error(e), identity)
            val hits = uiStrings.count(s => p.translate(s).isDefined)
            rows += f"| ${sp.id} | ${sp.license} | ${p.plainCount} | ${p.regexCount} | ${p.scopedCount} | ${p.warnings.size} | $hits (${100.0 * hits / uiStrings.size}%.1f %%) |"
      val table = rows.result()
      require(table.forall(!_.contains("FAILED")), table.filter(_.contains("FAILED")).toString)
      Files.writeString(output.resolve("upstream-packs.md"), f"Distinct English texts in the window: ${uiStrings.size}; built-in ja_JP translates $builtinHits.\n\n| Pack | Licence | Plain | Regex | Scoped | Warnings | UI texts translated |\n|---|---|---|---|---|---|---|\n" + table.mkString("\n") + "\n")
      checked(s"all ${table.size} pinned upstream packs downloaded, hash verified, parsed and applied to the window's texts (table in upstream-packs.md)")
      val hant = LocalizationPack.parse("h", Files.readString(installDir.resolve("zh_Hant (bluelovers).json"))).fold(e => sys.error(e), identity)
      require(hant.regexCount == 15 && hant.scopedCount == 6 && hant.warnings.isEmpty, s"regex ${hant.regexCount} scoped ${hant.scopedCount} warnings ${hant.warnings}")
      require(hant.translate("photomaker-v1 [abc12345]").contains("photomaker-v1 [abc12345] (PhotoMaker 個性化人像圖像)"), hant.translate("photomaker-v1 [abc12345]").toString)
      checked("the upstream zh_Hant pack's 15 regex rules and 6 scoped rules (A1111 syntax) compile without a warning and '$1' expands like the extension")
      fx(window.localization.apply(LocalizationPreferences(pack = "zh_CN (dtlnor)", extraDirs = Seq(installDir.toString))))
      selectTab(window, "txt2img"); Thread.sleep(400); fx(localizer.sweepNow())
      val (yzh, nzh) = fx(localizer.coverage)
      checked(s"dtlnor zh_CN pack as the only language (loaded from a user-listed folder): $yzh texts translated, $nzh not (A1111 wording differs from this app's labels)")
      snapshot("loc-15-zh_CN.png")
      fx(window.localization.apply(LocalizationPreferences()))
      Files.writeString(output.resolve("localization-report.txt"), report.mkString("\n"))
      println("LOCALIZATION CHECK PASSED (" + report.size + " groups)")

  /** `python output real checkpoint [side]`: the same seed generated with the English UI and with the Japanese UI on a real model. */
  private object Real:
    def run(args: Array[String], services: AppServices, output: Path, snapshot: String => Unit, setWindow: MainWindow => Unit): Unit =
      val checkpoint = Paths.get(args(3)).toAbsolutePath
      val sdxl = checkpoint.getFileName.toString.toLowerCase.contains("sdxl") || checkpoint.getFileName.toString.toLowerCase.contains("illustrious")
      val width = args.lift(4).map(_.toInt).getOrElse(if sdxl then 832 else 512)
      val height = args.lift(5).map(_.toInt).getOrElse(if sdxl then 1216 else 512)
      val finished = new LinkedBlockingQueue[Either[String, sdfx.core.plugins.api.OutputImage]]()
      services.queue.addListener {
        case event: JobEvent.JobFinished => finished.offer(event.output.images.headOption.toRight("no image")); ()
        case event: JobEvent.JobFailed => finished.offer(Left(event.message)); ()
        case _ => ()
      }
      var window: MainWindow = null
      try
        services.start()
        val loaded = services.loadCheckpoint(checkpoint.toString, remember = false)
        fx {
          window = new MainWindow(new Stage(), services, UiState(params = Map("txt2img" -> GenerationParams(prompt = "1girl, solo, silver hair, blue eyes, upper body, soft light, detailed",
            negativePrompt = "lowres, bad anatomy, blurry", seed = 20261102, steps = if sdxl then 14 else 12, width = width, height = height))), CliOptions())
          window.stage.setWidth(1700); window.stage.setHeight(1000)
          window.show()
          window.model.checkpoint.set(checkpoint.toString)
          setWindow(window)
        }
        val localizer = window.localization.localizer
        def generate(label: String): (Path, Array[Int], String) =
          fx { window.statusBar.generateBtn.fire() }
          val result = finished.poll(600, TimeUnit.SECONDS)
          require(result != null, label + ": generation timed out")
          val image = result.fold(m => throw new IllegalStateException(label + ": " + m), identity)
          val img = ImageIO.read(image.path.toFile)
          val rgb = img.getRGB(0, 0, img.getWidth, img.getHeight, null, 0, img.getWidth)
          val text = PngMetadata.readTexts(image.path)(PngMetadata.ParametersKey)
          println(s"REAL $label: ${image.path} ${img.getWidth}x${img.getHeight}")
          (image.path, rgb, text)
        val english = generate("english UI")
        snapshot("real-01-english.png")
        val problems = fx(window.localization.apply(LocalizationPreferences(pack = "builtin/ja_JP", style = DisplayStyle.Stacked.label)))
        require(problems.isEmpty)
        Thread.sleep(500); fx(localizer.sweepNow())
        val button = fx(window.statusBar.generateBtn.getText)
        require(button == "▶  生成\n▶  Generate", "button text " + button)
        val japanese = generate("japanese bilingual UI")
        snapshot("real-02-japanese.png")
        Files.copy(english._1, output.resolve("real-english.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.copy(japanese._1, output.resolve("real-japanese.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        require(java.util.Arrays.equals(english._2, japanese._2), "the language changed the generated pixels")
        require(english._3 == japanese._3, "the language changed the saved parameters:\n" + english._3 + "\n---\n" + japanese._3)
        require(!isJapanese(japanese._3))
        val info = Infotext.parse(japanese._3).fields
        val line = s"REAL CHECK PASSED: ${checkpoint.getFileName} ${width}x$height model hash ${loaded.hash.take(10)}; the same seed gave bit-identical pixels (${english._2.length} px) and identical PNG parameters with the English and the bilingual Japanese UI; Sampler ${info.getOrElse("Sampler", "?")}, Steps ${info.getOrElse("Steps", "?")}, Seed ${info.getOrElse("Seed", "?")}"
        println(line)
        Files.writeString(output.resolve("real-report.txt"), line + "\n")
      finally
        fx { if window != null then { window.dispose(); window.stage.close() } }
        services.shutdown()
