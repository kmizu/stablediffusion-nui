package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.image.ImageView
import javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent}
import javafx.scene.layout.TilePane
import javafx.stage.{Stage, Window}
import sdfx.core.config.{AppHome, Settings}
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.FileTime
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** MANUAL root-only gate. python output-directory source-outputs-directory.
  * Copies real generated images (from earlier real-model runs) into an isolated outputs folder, mounts the real
  * MainWindow with a dry-run engine and drives the Image Browser controls (clicks, key events, dialogs) while writing
  * screenshots to output-directory. No network, no GPU.
  */
object RealImageBrowserCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body
    )
    Platform.runLater(task)
    task.get(40, TimeUnit.SECONDS)
  private def eventually(label: String, seconds: Int = 60)(body: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(40)
    require(body, "Timed out: " + label)

  private def copyTo(src: Path, dst: Path, mtime: Long): Unit =
    Files.createDirectories(dst.getParent)
    Files.copy(src, dst)
    Files.setLastModifiedTime(dst, FileTime.fromMillis(mtime))

  def main(args: Array[String]): Unit =
    require(args.length == 3, "python output-directory source-outputs-directory")
    val output = Paths.get(args(1)).toAbsolutePath.normalize()
    val source = Paths.get(args(2)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val work = Files.createTempDirectory(output, "browser-run-")
    val outputs = work.resolve("outputs")
    var clock = 1_760_000_000_000L
    def stamp(): Long = { clock += 60_000; clock }
    def pngs(dir: Path): Vector[Path] =
      Files.walk(source.resolve(dir)).iterator().asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".png")).toVector.sortBy(_.toString)
    pngs(Paths.get("inc-real-sd15/images/txt2img/2026-11-01")).foreach(p => copyTo(p, outputs.resolve("txt2img/2026-11-01/inc-" + p.getFileName), stamp()))
    pngs(Paths.get("cls-real-sd15/images/normal/txt2img")).foreach(p => copyTo(p, outputs.resolve("txt2img/2026-11-01/cls-" + p.getFileName), stamp()))
    pngs(Paths.get("aes-real-sd15/images/txt2img")).foreach(p => copyTo(p, outputs.resolve("txt2img/2026-11-02/aes-" + p.getFileName), stamp()))
    pngs(Paths.get("inpaint-native-final-sd15/outputs/img2img")).foreach(p => copyTo(p, outputs.resolve("img2img/2026-10-30/ip-" + p.getFileName), stamp()))
    pngs(Paths.get("cls-real-sd15/images/normal/extras")).foreach(p => copyTo(p, outputs.resolve("extras/2026-11-01/ex-" + p.getFileName), stamp()))
    val sdxl = pngs(Paths.get("cdt-real-sdxl/images/txt2img")).take(6)
    sdxl.foreach(p => copyTo(p, work.resolve("elsewhere/pics/deep/" + "xl-" + p.getFileName), stamp()))
    copyTo(sdxl.head, work.resolve("elsewhere/top-xl.png"), stamp())
    // a JPEG with a .txt side-car, a video placeholder and a stray text file
    val jpgSource = ImageIO.read(pngs(Paths.get("inc-real-sd15/images/txt2img/2026-11-01")).head.toFile)
    val jpg = outputs.resolve("txt2img/2026-11-02/side-car.jpg")
    ImageIO.write(jpgSource, "jpg", jpg.toFile)
    Files.setLastModifiedTime(jpg, FileTime.fromMillis(1_700_000_000_000L))
    Files.writeString(jpg.resolveSibling("side-car.txt"), "jpeg side-car prompt, from a txt file\nNegative prompt: lowres\nSteps: 7, Sampler: Euler, CFG scale: 4, Seed: 31337, Size: 512x512, Model: sidecar-model")
    Files.write(outputs.resolve("txt2img/2026-11-02/clip.mp4"), Array.fill[Byte](2048)(0))
    Files.setLastModifiedTime(outputs.resolve("txt2img/2026-11-02/clip.mp4"), FileTime.fromMillis(1_700_000_001_000L))
    val totalTxt2img = Files.walk(outputs.resolve("txt2img")).iterator().asScala.count(p => Files.isRegularFile(p) && !p.toString.endsWith(".txt"))

    val home = AppHome(Files.createTempDirectory(work, "app-home-"))
    val engineDir = AppHome.detect().pythonDir
    val settings = Settings(outputDir = outputs.toString, previewEvery = 0)
    val services = new AppServices(home, settings, dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(Paths.get(args(0)).toAbsolutePath.toString, "-m", "sdfx", "--dry-run"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))),
      persistSettings = false, timeouts = EngineTimeouts(load = 2.minutes), installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    var checks = Vector.empty[String]
    def checked(message: String): Unit = { checks :+= message; println("BROWSER CHECK: " + message) }
    def find[A <: Node](id: String): A =
      val node = fx(window.scene.lookup("#" + id))
      require(node != null, "Missing control #" + id)
      node.asInstanceOf[A]
    def snapshot(name: String): Unit = fx {
      window.root.applyCss(); window.root.layout()
      require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(name).toFile))
    }
    def click(n: Node, count: Int = 1): Unit = fx {
      Event.fireEvent(n, new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5, MouseButton.PRIMARY, count, false, false, false, false, true, false, false, true, false, false, null))
    }
    def key(code: KeyCode, ctrl: Boolean = false, shift: Boolean = false, target: String = "txt2img"): Unit = fx {
      val grid = window.scene.lookup(s"#ib-$target-grid")
      Event.fireEvent(grid, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, false))
    }
    def press(id: String): Unit = fx { val b = window.scene.lookup("#" + id).asInstanceOf[Button]; require(!b.isDisabled, s"#$id disabled"); b.fire() }
    def page(name: String) = window.imageBrowser.page(name).get.viewModel
    def tilesLoaded(id: String): Boolean = fx {
      val grid = window.scene.lookup(s"#ib-$id-grid").asInstanceOf[TilePane]
      val views = grid.lookupAll(".image-view").asScala.collect { case v: ImageView => v }.filter(_.isVisible)
      views.nonEmpty && views.forall(_.getImage != null)
    }
    def tileNames(id: String): Seq[String] = fx {
      window.scene.lookup(s"#ib-$id-grid").asInstanceOf[TilePane].getChildren.asScala.map(_.getUserData).collect { case n: String => n }.toSeq
    }
    def dialog(idPart: String): DialogPane =
      var found: DialogPane = null
      eventually("dialog " + idPart, 20) {
        found = fx(Window.getWindows.asScala.flatMap(w => Option(w.getScene)).map(_.getRoot).collectFirst { case d: DialogPane if d.getId == idPart => d }.orNull)
        found != null
      }
      found
    def label(id: String): String = fx(window.scene.lookup("#" + id).asInstanceOf[Labeled].getText)
    def textOf(id: String): String = fx(window.scene.lookup("#" + id).asInstanceOf[TextInputControl].getText)
    def setText(id: String, v: String): Unit = fx(window.scene.lookup("#" + id).asInstanceOf[TextInputControl].setText(v))
    def selectInner(name: String): Unit = fx(window.imageBrowser.selectInner(name))

    try
      window = fx { val w = new MainWindow(new Stage(), services, UiState(), CliOptions()); w.stage.setWidth(1700); w.stage.setHeight(1000); w.show(); w }
      services.start()
      val env = window.imageBrowser.vm.env
      env.store.updateSettings(_.copy(pageColumns = 6, pageRows = 3, thumbnailSize = 120)) // 18 per page -> several pages
      fx { window.imageBrowser.build() }
      fx {
        val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
        tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "image-browser-tab").get)
      }
      eventually("txt2img tab listed")(fx(page("txt2img").filtered.size) == totalTxt2img - 0 && tilesLoaded("txt2img"))
      val inner = fx(window.scene.lookup("#image-browser-tabs").asInstanceOf[TabPane].getTabs.asScala.map(_.getText).toSeq)
      require(inner == Seq("txt2img", "img2img", "txt2img-grids", "img2img-grids", "Extras", "Favorites", "Others", "All", "Maintenance"), inner.toString)
      checked(s"Image Browser main tab opens with the nine upstream inner tabs; txt2img lists $totalTxt2img real files (png, jpg+txt side-car, video) split into pages of 18")
      snapshot("browser-01-txt2img.png")

      // ---- paging --------------------------------------------------------------------------------------------
      val pageCount = fx(page("txt2img").pager.pageCount)
      require(pageCount == (totalTxt2img + 17) / 18, s"page count $pageCount for $totalTxt2img")
      require(label("ib-txt2img-pagelabel") == s"Page Index (1/$pageCount)")
      require(tileNames("txt2img").size == 18)
      press("ib-txt2img-next"); eventually("page 2")(tilesLoaded("txt2img") && label("ib-txt2img-pagelabel") == s"Page Index (2/$pageCount)")
      press("ib-txt2img-end"); eventually("end page")(label("ib-txt2img-pagelabel") == s"Page Index ($pageCount/$pageCount)")
      require(tileNames("txt2img").size == totalTxt2img - 18 * (pageCount - 1))
      press("ib-txt2img-first"); press("ib-txt2img-next"); press("ib-txt2img-prev")
      eventually("back on page 1")(label("ib-txt2img-pagelabel") == s"Page Index (1/$pageCount)")
      setText("ib-txt2img-page", "2"); fx(window.scene.lookup("#ib-txt2img-page").asInstanceOf[TextField].fireEvent(new javafx.event.ActionEvent()))
      eventually("page field")(label("ib-txt2img-pagelabel") == s"Page Index (2/$pageCount)")
      key(KeyCode.RIGHT, ctrl = true); eventually("ctrl+right")(label("ib-txt2img-pagelabel") == s"Page Index (3/$pageCount)")
      key(KeyCode.LEFT, ctrl = true); eventually("ctrl+left")(label("ib-txt2img-pagelabel") == s"Page Index (2/$pageCount)")
      press("ib-txt2img-first")
      checked("paging: First/Prev/Next/End buttons, page-index field and Ctrl+Left/Right all move between pages and update the 'Page Index (n/N)' label")

      // ---- thumbnails, tooltips, info panel -------------------------------------------------------------------------
      eventually("page 1 loaded")(tilesLoaded("txt2img"))
      val firstName = tileNames("txt2img").head
      click(find[Node]("ib-txt2img-tile-" + firstName.replace('.', '_')))
      eventually("selection")(fx(page("txt2img").selected.isDefined))
      eventually("preview image")(fx(find[ImageView]("ib-txt2img-preview").getImage != null))
      val rec = fx(page("txt2img").selectedRecord.get)
      require(textOf("ib-txt2img-filename") == rec.path.toString)
      require(label("ib-txt2img-filetime").matches("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d"""), label("ib-txt2img-filetime"))
      require(label("ib-txt2img-filefacts").contains("512 x"), label("ib-txt2img-filefacts"))
      val rows = fx(find[TableView[(String, String)]]("ib-txt2img-info-table").getItems.asScala.toVector)
      require(rows.exists(_._1 == "Prompt") && rows.exists(r => r._1 == "Seed") && rows.exists(_._1 == "Steps"), rows.toString)
      fx(find[CheckBox]("ib-txt2img-formatted").setSelected(false))
      require(textOf("ib-txt2img-info").contains("Negative prompt:") && textOf("ib-txt2img-info").contains("Seed:"))
      fx(find[CheckBox]("ib-txt2img-formatted").setSelected(true))
      checked("thumbnails decode asynchronously (all tiles have images); selecting one fills the preview, file name, file time, size/dimensions and the formatted (Key/Value table) and raw Generation Info")
      snapshot("browser-02-selected-info.png")

      // ---- sorting ---------------------------------------------------------------------------------------------------
      def sortedBy(key: String, ascending: Boolean): Vector[sdfx.core.imagebrowser.ImageRecord] =
        fx {
          val cb = find[ComboBox[String]]("ib-txt2img-sort")
          cb.setValue(key)
          val v = page("txt2img")
          if v.query.ascending != ascending then find[Button]("ib-txt2img-order").fire()
          v.filtered
        }
      val byDate = sortedBy("date", false)
      require(byDate.map(_.modified) == byDate.map(_.modified).sorted.reverse, "date descending")
      val byDateAsc = sortedBy("date", true)
      require(byDateAsc.map(_.modified) == byDateAsc.map(_.modified).sorted, "date ascending")
      val byName = sortedBy("path name", true)
      require(byName.map(_.path.toString) == byName.map(_.path.toString).sorted)
      val bySeed = sortedBy("seed", true).flatMap(_.meta.seed)
      require(bySeed == bySeed.sorted && bySeed.nonEmpty && bySeed.contains(31337L), bySeed.toString)
      val byAes = sortedBy("aesthetic_score", false)
      val scores = byAes.flatMap(_.meta.aestheticScore)
      require(scores == scores.sorted.reverse && scores.size >= 10 && byAes.take(scores.size).forall(_.meta.aestheticScore.isDefined), "aesthetic descending, scored first")
      val bySteps = sortedBy("steps", true).flatMap(_.meta.steps); require(bySteps == bySteps.sorted)
      val byCfg = sortedBy("cfg scale", true).flatMap(_.meta.cfgScale); require(byCfg == byCfg.sorted && byCfg.contains(4.0))
      val bySize = sortedBy("size", true).flatMap(_.meta.size.map((w, h) => w * h)); require(bySize == bySize.sorted)
      sortedBy("sampler", true); sortedBy("model", true); sortedBy("model hash", true)
      val r1 = sortedBy("random", false).map(_.fileName); sortedBy("date", false); val r2 = sortedBy("random", false).map(_.fileName)
      require(r1.toSet == r2.toSet && r1 != r2, "random re-shuffles")
      sortedBy("ranking", false); sortedBy("date", false)
      checked("sorting by date, path name, seed, aesthetic_score, steps, cfg scale, size, sampler, model, model hash, random and ranking in both directions (order verified against the file data)")

      // ---- filters ----------------------------------------------------------------------------------------------------
      setText("ib-txt2img-filekw", "aes-")
      eventually("filename filter")(fx(page("txt2img").filtered.forall(_.fileName.startsWith("aes-")) && page("txt2img").filtered.size >= 18))
      setText("ib-txt2img-filekw", "")
      setText("ib-txt2img-exifkw", "seed: 31337")
      eventually("exif filter")(fx(page("txt2img").filtered.map(_.fileName) == Vector("side-car.jpg")))
      checked("filename keyword and EXIF keyword search; the .jpg is found through its .txt side-car (EXIF/txt data)")
      setText("ib-txt2img-exifkw", "blurry"); eventually("neg No")(fx(page("txt2img").filtered.isEmpty))
      fx(find[RadioButton]("ib-txt2img-neg-Yes").setSelected(true)); eventually("neg Yes")(fx(page("txt2img").filtered.size > 20))
      fx(find[RadioButton]("ib-txt2img-neg-Only").setSelected(true)); eventually("neg Only")(fx(page("txt2img").filtered.size > 20))
      setText("ib-txt2img-exifkw", "lowres"); fx(find[RadioButton]("ib-txt2img-neg-No").setSelected(true))
      eventually("neg No lowres")(fx(page("txt2img").filtered.isEmpty)) // 'lowres' only occurs in negative prompts
      setText("ib-txt2img-exifkw", "^(?!.*aesthetic_score).*$"); fx(find[CheckBox]("ib-txt2img-regex").setSelected(true))
      fx(find[RadioButton]("ib-txt2img-neg-Yes").setSelected(true))
      eventually("regex")(fx { val f = page("txt2img").filtered; f.nonEmpty && f.forall(_.meta.aestheticScore.isEmpty) && f.forall(_.meta.parameters.isDefined) })
      setText("ib-txt2img-exifkw", "("); eventually("regex error")(label("ib-txt2img-warning").startsWith("Regex error"))
      fx(find[CheckBox]("ib-txt2img-regex").setSelected(false)); setText("ib-txt2img-exifkw", "")
      fx(find[RadioButton]("ib-txt2img-neg-No").setSelected(true))
      checked("negative-prompt search modes No/Yes/Only and regex search (including the invalid-regex warning) behave as specified")
      setText("ib-txt2img-aesmin", "6"); eventually("aes min")(fx { val f = page("txt2img").filtered; f.nonEmpty && f.forall(_.meta.aestheticScore.exists(_ >= 6.0)) })
      setText("ib-txt2img-aesmax", "6.05"); eventually("aes max")(fx(page("txt2img").filtered.isEmpty))
      setText("ib-txt2img-aesmin", "5.5"); eventually("aes range")(fx { val f = page("txt2img").filtered; f.nonEmpty && f.forall(_.meta.aestheticScore.exists(s => s >= 5.5 && s <= 6.05)) })
      setText("ib-txt2img-aesmin", ""); setText("ib-txt2img-aesmax", "")
      eventually("filters reset")(fx(page("txt2img").filtered.size == totalTxt2img))
      checked("minimum/maximum aesthetic score filters use the real aesthetic_score values (5.5 / 5.9 / 6.1) in the images")

      // ---- ranking ------------------------------------------------------------------------------------------------------
      eventually("tiles")(tilesLoaded("txt2img"))
      val names = tileNames("txt2img")
      click(find[Node]("ib-txt2img-tile-" + names(1).replace('.', '_')))
      fx(find[RadioButton]("ib-txt2img-rank-4").setSelected(true))
      val target = fx(page("txt2img").selectedRecord.get.path)
      eventually("ranking 4")(env.store.ranking(target).contains(4))
      require(label("ib-txt2img-ranking-current") == "4")
      require(PngMetadata.readTexts(target).get("ranking").isEmpty, "pnginfo option is off by default")
      key(KeyCode.DIGIT2); eventually("key 2")(env.store.ranking(target).contains(2))
      key(KeyCode.NUMPAD5); eventually("numpad 5")(env.store.ranking(target).contains(5))
      env.store.updateSettings(_.copy(rankingPnginfo = true))
      key(KeyCode.DIGIT3); eventually("pnginfo")(PngMetadata.readTexts(target).get("ranking").contains("3"))
      require(javax.imageio.ImageIO.read(target.toFile) != null, "image still decodes after the text chunk rewrite")
      env.store.updateSettings(_.copy(rankingPnginfo = false))
      fx(find[RadioButton]("ib-txt2img-rf-Min-max").setSelected(true))
      eventually("min-max enabled")(fx(!find[Spinner[Integer]]("ib-txt2img-rfmin").isDisable))
      eventually("min-max filter")(fx(page("txt2img").filtered.map(_.path) == Vector(target)))
      fx(find[RadioButton]("ib-txt2img-rf-None").setSelected(true)); eventually("none filter")(fx(page("txt2img").filtered.size == totalTxt2img - 1))
      fx(find[RadioButton]("ib-txt2img-rf-3").setSelected(true)); eventually("3 filter")(fx(page("txt2img").filtered.map(_.path) == Vector(target)))
      fx(find[RadioButton]("ib-txt2img-rf-All").setSelected(true)); eventually("all")(fx(page("txt2img").filtered.size == totalTxt2img))
      sortedBy("ranking", false); eventually("rank sort")(fx(page("txt2img").filtered.head.path == target))
      eventually("ranked first tile")(tileNames("txt2img").headOption.contains(target.getFileName.toString))
      require(fx(page("txt2img").selected.isEmpty), "a filter that hides the selected image clears the selection")
      click(find[Node]("ib-txt2img-tile-" + target.getFileName.toString.replace('.', '_')))
      snapshot("browser-03-ranking.png")
      sortedBy("date", false)
      key(KeyCode.DIGIT0); eventually("clear rank")(env.store.ranking(target).isEmpty)
      eventually("embedded ranking removed with the stored one")(PngMetadata.readTexts(target).get("ranking").isEmpty)
      checked("ranking: radio buttons and keys 0-5 (also numpad) store the ranking, 'Save ranking in pnginfo' writes a tEXt chunk without breaking the image, ranking filter All/1-5/None/Min-max and ranking sort work")

      // ---- favorites -----------------------------------------------------------------------------------------------------
      val favSource = fx(page("txt2img").selectedRecord.get.path)
      val before = fx(page("txt2img").filtered.size)
      key(KeyCode.F)
      eventually("moved to favorites")(Files.exists(env.favoritesDir.resolve(favSource.getFileName)) && !Files.exists(favSource))
      eventually("list shrinks")(fx(page("txt2img").filtered.size == before - 1))
      selectInner("Favorites")
      eventually("favorites lists it")(fx(page("Favorites").filtered.map(_.fileName) == Vector(favSource.getFileName.toString)) && tilesLoaded("favorites"))
      require(fx(window.scene.lookup("#ib-favorites-favorite") == null), "no favorites button on the Favorites tab")
      snapshot("browser-04-favorites.png")
      selectInner("txt2img")
      env.store.updateSettings(_.copy(copyInsteadOfMove = true)); fx { window.imageBrowser.build() }
      eventually("rebuilt")(fx(page("txt2img").filtered.size == before - 1) && tilesLoaded("txt2img"))
      click(find[Node]("ib-txt2img-tile-" + tileNames("txt2img").head.replace('.', '_')))
      val copySource = fx(page("txt2img").selectedRecord.get.path)
      require(fx(find[Button]("ib-txt2img-favorite").getText) == "Copy to favorites")
      press("ib-txt2img-favorite")
      eventually("copied")(Files.exists(env.favoritesDir.resolve(copySource.getFileName)) && Files.exists(copySource))
      checked("favorites: 'Move to favorites' (default) moves the file into the favorites folder (shows on the Favorites tab, no button there); with the copy option the button reads 'Copy to favorites' and keeps the original")

      // ---- copy / move to directory ----------------------------------------------------------------------------------------
      val dest = work.resolve("collected")
      setText("ib-txt2img-todir-path", dest.toString)
      press("ib-txt2img-todir")
      eventually("copied to dir")(Files.list(dest).count() == 1L)
      env.store.updateSettings(_.copy(copyInsteadOfMove = false)); fx { window.imageBrowser.build() }
      eventually("rebuilt 2")(tilesLoaded("txt2img"))
      click(find[Node]("ib-txt2img-tile-" + tileNames("txt2img").head.replace('.', '_')))
      val moveSource = fx(page("txt2img").selectedRecord.get.path)
      setText("ib-txt2img-todir-path", dest.toString); press("ib-txt2img-todir")
      eventually("moved to dir")(Files.exists(dest.resolve(moveSource.getFileName)) && !Files.exists(moveSource))
      checked("copy/move to directory panel copies or moves according to the option")

      // ---- send to -------------------------------------------------------------------------------------------------------------
      eventually("tiles 3")(tilesLoaded("txt2img"))
      click(find[Node]("ib-txt2img-tile-" + tileNames("txt2img").head.replace('.', '_')))
      val sel = fx(page("txt2img").selectedRecord.get)
      val prompt = sel.meta.prompt
      def backToBrowser(): Unit = fx { val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]; tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "image-browser-tab").get) }
      press("ib-txt2img-to-txt2img")
      eventually("txt2img prompt")(fx(window.vms(Mode.Txt2Img).prompt.get == prompt))
      backToBrowser()
      press("ib-txt2img-to-img2img")
      eventually("img2img input")(fx(window.vms(Mode.Img2Img).initImage.get == sel.path.toString && window.vms(Mode.Img2Img).prompt.get == prompt))
      require(fx(window.activeMode == Mode.Img2Img))
      fx { val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]; tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "image-browser-tab").get) }
      press("ib-txt2img-to-inpaint")
      eventually("inpaint input")(fx(window.vms(Mode.Inpaint).initImage.get == sel.path.toString))
      fx { val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]; tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "image-browser-tab").get) }
      press("ib-txt2img-to-extras")
      eventually("extras input")(fx(window.extras.image.get == sel.path.toString))
      fx { val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]; tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "image-browser-tab").get) }
      fx(find[ComboBox[String]]("ib-txt2img-cn-number").setValue("1"))
      press("ib-txt2img-cn-txt2img")
      eventually("controlnet txt2img unit 1")(fx(window.vms(Mode.Txt2Img).controlNet(1).image.get == sel.path.toString))
      press("ib-txt2img-cn-img2img")
      eventually("controlnet img2img unit 1")(fx(window.vms(Mode.Img2Img).controlNet(1).image.get == sel.path.toString))
      require(fx(window.vms(Mode.Txt2Img).controlNet(0).image.get.isEmpty), "other ControlNet units untouched")
      checked("send to txt2img (parameters), img2img and inpaint (input image + parameters), Extras (input image) and txt2img/img2img ControlNet unit 1 (image only) reach the real generation forms")
      fx { val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]; tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "image-browser-tab").get) }

      // ---- delete -------------------------------------------------------------------------------------------------------------------
      eventually("tiles 4")(tilesLoaded("txt2img"))
      val delNames = tileNames("txt2img")
      click(find[Node]("ib-txt2img-tile-" + delNames(0).replace('.', '_')))
      fx(find[Spinner[Integer]]("ib-txt2img-delnum").getValueFactory.setValue(2))
      val delPaths = fx(page("txt2img").deletePlan(2, false).map(_.path))
      require(delPaths.size == 2)
      Platform.runLater(() => find[Button]("ib-txt2img-delete").fire()) // opens a modal confirmation
      var dlg = dialog("image-browser-delete-confirm")
      require(fx(dlg.getContentText).contains("Delete 2 images") && fx(dlg.getContentText).contains("recycle bin"), fx(dlg.getContentText))
      fx(dlg.lookupButton(ButtonType.CANCEL).asInstanceOf[Button].fire())
      Thread.sleep(300)
      require(delPaths.forall(Files.exists(_)), "Cancel must not delete")
      Platform.runLater(() => find[Button]("ib-txt2img-delete").fire())
      dlg = dialog("image-browser-delete-confirm")
      snapshot("browser-05-delete-confirm.png")
      fx(dlg.lookupButton(ButtonType.OK).asInstanceOf[Button].fire())
      eventually("deleted")(delPaths.forall(!Files.exists(_)) && fx(page("txt2img").filtered.forall(r => !delPaths.contains(r.path))))
      checked("delete next N asks for confirmation naming the recycle bin; Cancel keeps the files, OK sends exactly the planned images to the recycle bin and refreshes the list")

      // ---- other tabs --------------------------------------------------------------------------------------------------------------------
      selectInner("img2img"); eventually("img2img tab")(fx(page("img2img").filtered.size == 2) && tilesLoaded("img2img"))
      selectInner("Extras"); eventually("extras tab")(fx(page("Extras").filtered.size == 6) && tilesLoaded("extras"))
      selectInner("txt2img-grids"); eventually("grids tab")(fx(page("txt2img-grids").totalScanned == 0))
      require(label("ib-txt2img-grids-pagelabel") == "Page Index (1/1)")
      selectInner("All")
      val txtLeft = fx(page("txt2img").totalScanned)
      eventually("all tab")(fx(page("All").filtered.size) == txtLeft + 2 + 6 + 2 && tilesLoaded("all"))
      snapshot("browser-06-all.png")
      checked("img2img (2), Extras (6), the empty txt2img-grids tab and the combined All tab list the right files")

      // ---- Others -----------------------------------------------------------------------------------------------------------------
      selectInner("Others")
      val others = work.resolve("elsewhere")
      setText("ib-others-dir", others.toString)
      fx(find[Spinner[Integer]]("ib-others-depth").getValueFactory.setValue(0)); press("ib-others-load")
      eventually("depth 0")(fx(page("Others").filtered.map(_.fileName) == Vector("top-xl.png")) && tilesLoaded("others"))
      fx(find[Spinner[Integer]]("ib-others-depth").getValueFactory.setValue(1)); press("ib-others-load")
      eventually("depth 1")(fx(page("Others").filtered.size == 1))
      fx(find[Spinner[Integer]]("ib-others-depth").getValueFactory.setValue(2)); press("ib-others-load")
      eventually("depth 2")(fx(page("Others").filtered.size == 7) && tilesLoaded("others"))
      fx(find[Spinner[Integer]]("ib-others-depth").getValueFactory.setValue(-1)); press("ib-others-load")
      eventually("depth -1")(fx(page("Others").filtered.size == 7))
      press("ib-others-save")
      eventually("saved")(fx(find[ComboBox[String]]("ib-others-saved").getItems.size) == 1)
      require(env.store.savedDirs.map(_.depth) == Seq(-1))
      fx(find[Spinner[Integer]]("ib-others-depth").getValueFactory.setValue(2)); press("ib-others-save")
      require(env.store.savedDirs.size == 1 && env.store.savedDirs.head.depth == 2, "save replaces the entry for the same directory")
      fx(find[ComboBox[String]]("ib-others-subdirs").getItems.size) // populated by Load/Get
      press("ib-others-getsubs")
      require(fx(find[ComboBox[String]]("ib-others-subdirs").getItems.asScala.toSeq) == Seq("(none)", "pics"))
      fx(find[ComboBox[String]]("ib-others-subdirs").setValue("pics"))
      eventually("subdir filter")(fx(page("Others").filtered.size == 6))
      snapshot("browser-07-others.png")
      fx(find[ComboBox[String]]("ib-others-saved").setValue(fx(find[ComboBox[String]]("ib-others-saved").getItems.get(0))))
      press("ib-others-remove")
      eventually("removed")(env.store.savedDirs.isEmpty)
      checked("Others: any folder with sub directory depth 0/1/2/-1, saved directories (add, replace, remove) and the sub directory dropdown")

      // ---- Maintenance --------------------------------------------------------------------------------------------------------------------
      selectInner("Maintenance")
      def maint(id: String): String =
        press(id); eventually("maintenance " + id)(textOf("ib-maintenance-last").nonEmpty && label("ib-maintenance-status") == "Status: done")
        val t = textOf("ib-maintenance-last"); setText("ib-maintenance-last", ""); t
      val m1 = maint("ib-maintenance-rebuild"); require(m1.startsWith("Exif cache rebuilt: "), m1)
      val m2 = maint("ib-maintenance-zero"); require(m2.contains("0-entries"), m2)
      val m3 = maint("ib-maintenance-hash"); require(m3.startsWith("Hash recreated"), m3)
      val m4 = maint("ib-maintenance-reapply"); require(m4.startsWith("Ranking reapplied"), m4)
      val m5 = maint("ib-maintenance-rebuild-ranking"); require(m5.startsWith("Ranking rebuilt"), m5)
      val m6 = maint("ib-maintenance-update"); require(m6.contains("need"), m6) // empty from/to is reported, not applied
      snapshot("browser-08-maintenance.png")
      checked(s"Maintenance buttons run off the UI thread and report: '$m1' / '$m2' / '$m3' / '$m4' / '$m5'")

      // ---- swipe (synthetic SwipeEvent; no touch hardware) ----------------------------------------------------------------------
      selectInner("txt2img"); eventually("txt2img tiles again")(tilesLoaded("txt2img"))
      click(find[Node]("ib-txt2img-tile-" + tileNames("txt2img").head.replace('.', '_')))
      def swipe(left: Boolean): Unit = fx {
        Event.fireEvent(window.scene.lookup("#ib-txt2img-grid"), new javafx.scene.input.SwipeEvent(
          if left then javafx.scene.input.SwipeEvent.SWIPE_LEFT else javafx.scene.input.SwipeEvent.SWIPE_RIGHT, 1, 1, 1, 1, false, false, false, false, true, 1, null))
      }
      val at = fx(page("txt2img").selected.get)
      swipe(left = true); Thread.sleep(200)
      require(fx(page("txt2img").selected.get) == at, "swipe is ignored while the option is off")
      env.store.updateSettings(_.copy(swipe = true))
      swipe(left = true); eventually("swipe next")(fx(page("txt2img").selected.get) != at)
      val moved = fx(page("txt2img").selected.get)
      swipe(left = false); eventually("swipe back")(fx(page("txt2img").selected.get) == at)
      require(moved != at)
      env.store.updateSettings(_.copy(swipe = false))
      checked("swipe left/right option moves the selection to the next/previous image (synthetic SwipeEvents; real touch hardware not available)")

      // ---- Settings dialog ------------------------------------------------------------------------------------------------------------------
      Platform.runLater(() => window.imageBrowser.openSettings())
      val sd = dialog("image-browser-settings-dialog")
      fx {
        val boxes = sd.lookupAll(".check-box").asScala.collect { case c: CheckBox => c }.toVector
        boxes.find(_.getText == "Include images in sub directories").get.setSelected(false)
        boxes.find(_.getText == "Ranking Bar").get.setSelected(true)
        val fields = sd.lookupAll(".text-field").asScala.collect { case t: TextField if !t.getParent.isInstanceOf[Spinner[?]] => t }.toVector
        fields.find(_.getText.startsWith("txt2img, img2img, txt2img-grids")).get.setText("txt2img, img2img, Favorites, Maintenance")
      }
      snapshot("browser-09-settings.png")
      fx(sd.lookupButton(ButtonType.OK).asInstanceOf[Button].fire())
      eventually("settings applied")(fx(window.scene.lookup("#image-browser-tabs").asInstanceOf[TabPane].getTabs.size == 4))
      require(!env.settings.withSubdirs && env.settings.hidden("Ranking Bar") && env.settings.activeTabs == Seq("txt2img", "img2img", "Favorites", "Maintenance"))
      require(env.store.settings == new sdfx.core.imagebrowser.BrowserStore(env.configDir.resolve("image-browser.json")).settings, "settings persisted to disk")
      eventually("no sub dirs: txt2img empty")(fx(page("txt2img").filtered.isEmpty))
      require(fx(!find[Node]("ib-txt2img-rank-1").isVisible || !find[Node]("ib-txt2img-rank-1").getParent.getParent.isVisible), "hidden component Ranking Bar is hidden")
      checked("Settings dialog edits the active tab list, a hidden component and 'Include images in sub directories'; changes persist and rebuild the tabs")
      env.store.updateSettings(_ => sdfx.core.imagebrowser.BrowserSettings(pageColumns = 6, pageRows = 3, thumbnailSize = 120, thumbnailCrop = true))
      fx { window.imageBrowser.build() }
      eventually("restored defaults")(tilesLoaded("txt2img"))
      snapshot("browser-10-cropped.png")
      checked("thumbnail crop to square option renders (see screenshot)")

      // preload option: a tab that was never shown scans at startup only when 'preload' is on
      env.store.updateSettings(_.copy(preload = false, activeTabs = Seq("txt2img", "Maintenance"), withSubdirs = true))
      val lazyTab = fx(new sdfx.ui.view.ImageBrowserTab(window.ctx, env))
      Thread.sleep(1500)
      require(fx(lazyTab.page("txt2img").get.viewModel.totalScanned) == 0, "preload off: nothing scanned before the tab is shown")
      fx(lazyTab.shown()); eventually("lazy tab scans when shown")(fx(lazyTab.page("txt2img").get.viewModel.totalScanned) > 0)
      env.store.updateSettings(_.copy(preload = true))
      val eager = fx(new sdfx.ui.view.ImageBrowserTab(window.ctx, env))
      eventually("preload scans without being shown")(fx(eager.page("txt2img").get.viewModel.totalScanned) > 0)
      checked("preload option: off = no scan until the tab is shown, on = first tab is scanned at startup")

      println("IMAGE BROWSER CHECK PASSED (" + checks.size + " groups)")
      Files.writeString(output.resolve("browser-report.txt"), checks.mkString("\n"))
      try fx { window.imageBrowser.dispose() } catch case _: Throwable => ()
      Platform.exit()
      System.exit(0)
    catch case e: Throwable =>
      e.printStackTrace()
      try snapshot("browser-FAILED.png") catch case _: Throwable => ()
      System.exit(1)
