package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.input.{MouseButton, MouseEvent}
import javafx.scene.layout.TilePane
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.UiState

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** MANUAL gate. args: python output-directory sources-directory video-file query-image.
  * Mounts the real MainWindow with the engine (`--dry-run`, so no checkpoint or GPU is touched) and drives the Character
  * Searcher tab: imports the real generated images and a video through the real detector / segmentation / tagger / CLIP
  * models, searches by tags and image, clicks results, exports, and writes screenshots and a report to output-directory.
  */
object RealCharaSearcherCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body
    )
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private def eventually(label: String, seconds: Int = 60)(body: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(100)
    require(body, "Timed out: " + label)

  def main(args: Array[String]): Unit =
    require(args.length == 5, "python output-directory sources-directory video-file query-image")
    val output = Paths.get(args(1)).toAbsolutePath.normalize()
    val sources = Paths.get(args(2)).toAbsolutePath.normalize()
    val video = Paths.get(args(3)).toAbsolutePath.normalize()
    val query = Paths.get(args(4)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val work = Files.createTempDirectory(output, "chara-run-")
    val home = AppHome(Files.createTempDirectory(work, "app-home-"))
    val engineDir = AppHome.detect().pythonDir
    val services = new AppServices(home, Settings(outputDir = work.resolve("outputs").toString, previewEvery = 0), dryRun = true,
      builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(Paths.get(args(0)).toAbsolutePath.toString, "-m", "sdfx", "--dry-run"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0", "CUDA_VISIBLE_DEVICES" -> "-1"))),
      persistSettings = false, timeouts = EngineTimeouts(load = 2.minutes), installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    var checks = Vector.empty[String]
    def checked(message: String): Unit = { checks :+= message; println("CHARA CHECK: " + message) }
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
    def press(id: String): Unit = fx { val b = window.scene.lookup("#" + id).asInstanceOf[ButtonBase]; require(!b.isDisabled, s"#$id disabled"); b.fire() }
    def label(id: String): String = fx(window.scene.lookup("#" + id).asInstanceOf[Labeled].getText)
    def setText(id: String, v: String): Unit = fx(window.scene.lookup("#" + id).asInstanceOf[TextInputControl].setText(v))
    def tiles: Vector[Node] = fx(find[TilePane]("chara-grid").getChildren.asScala.toVector)
    def tileCount: Int = fx(find[TilePane]("chara-grid").getChildren.size)
    def tileImages: Boolean = fx {
      val views = find[TilePane]("chara-grid").lookupAll(".image-view").asScala.collect { case v: javafx.scene.image.ImageView => v }.toVector
      views.nonEmpty && views.forall(v => v.getImage != null && v.getImage.getProgress >= 1.0 && !v.getImage.isError)
    }

    try
      window = fx { val w = new MainWindow(new Stage(), services, UiState(), CliOptions()); w.stage.setWidth(1700); w.stage.setHeight(1000); w.show(); w }
      services.start()
      fx {
        val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
        tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == "chara-searcher-tab").get)
      }
      val vm = window.charaSearcher.vm
      eventually("tab shown")(label("chara-summary") == "No dataset selected")
      checked("Character Searcher main tab opens with no dataset ('No dataset selected')")
      snapshot("chara-01-empty.png")

      // ---- import: a folder of real SD1.5 / SDXL images plus a video ---------------------------------------------------
      val imageCount = Files.list(sources).iterator().asScala.count(p => p.toString.endsWith(".png"))
      setText("chara-new-name", "demo set")
      setText("chara-sources", sources.toString + "\n" + video.toString)
      setText("chara-frame-interval", "4")
      press("chara-import")
      eventually("import running", 30)(fx(find[Button]("chara-cancel").isDisabled == false))
      eventually("import finished", 1500)(fx(find[Button]("chara-import").isDisabled == false) && label("chara-status").startsWith("Imported"))
      val importStatus = label("chara-status")
      println("IMPORT STATUS: " + importStatus)
      val dir = vm.datasetDir("demo set")
      val cutouts = Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".png")).toVector
      require(cutouts.size >= imageCount, s"expected at least one character per image: ${cutouts.size} cut-outs from $imageCount images")
      require(cutouts.forall(p => Files.isRegularFile(p.resolveSibling(p.getFileName.toString.stripSuffix(".png") + ".txt"))), "every cut-out has a caption")
      require(cutouts.exists(_.getFileName.toString.contains("scene_")) || cutouts.exists(_.getFileName.toString.contains("_00000000")), "video frames produced cut-outs")
      checked(s"Import of $imageCount real images and 1 video made ${cutouts.size} cut-outs with .txt captions: $importStatus")
      snapshot("chara-02-imported.png")

      // ---- the dataset appears and loads ------------------------------------------------------------------------------------
      eventually("dataset listed and selected")(fx(find[ListView[String]]("chara-datasets").getItems.contains("demo set")))
      fx(find[ListView[String]]("chara-datasets").getSelectionModel.select("demo set"))
      eventually("results shown", 120)(tileCount == cutouts.size && tileImages)
      require(label("chara-summary").startsWith(s"${cutouts.size} of ${cutouts.size} images"), label("chara-summary"))
      snapshot("chara-03-all.png")
      checked(s"Selecting the dataset lists all ${cutouts.size} cut-outs as thumbnails that decode (summary '${label("chara-summary")}')")

      // ---- tags ------------------------------------------------------------------------------------------------------
      val tagCounts = vm.datasets.flatMap(_.entries).flatMap(_.tagSet).groupBy(identity).view.mapValues(_.size).toMap
      val common = tagCounts.filter((t, n) => n < cutouts.size && n >= 2 && !t.isEmpty).maxByOption(_._2).map(_._1)
        .orElse(tagCounts.filter(_._2 < cutouts.size).maxByOption(_._2).map(_._1)).get
      setText("chara-tags", common); press("chara-search")
      val expectWith = vm.datasets.flatMap(_.entries).count(_.tagSet.contains(common))
      eventually("tag filter", 60)(tileCount == expectWith && tileCount > 0 && tileCount < cutouts.size)
      setText("chara-tags", ""); setText("chara-negative-tags", common); press("chara-search")
      eventually("negative tag", 60)(tileCount == cutouts.size - expectWith)
      checked(s"Search Tags '$common' keeps $expectWith of ${cutouts.size}; Negative Tags '$common' keeps ${cutouts.size - expectWith}")
      setText("chara-negative-tags", ""); press("chara-search")
      eventually("filters cleared")(tileCount == cutouts.size)

      // ---- search image -------------------------------------------------------------------------------------------------
      fx(window.charaSearcher.setSearchImage(query))
      eventually("query embedded", 120)(fx(vm.queryVector.isDefined) && fx(vm.results.forall(_.similarity.isDefined)))
      fx(find[Slider]("chara-threshold").setValue(0.0))
      val sorted = fx(vm.results)
      require(sorted.size == cutouts.size)
      val sims = sorted.flatMap(_.similarity)
      require(sims == sims.sorted.reverse, "results sorted by similarity")
      val best = sorted.head
      val queryStem = query.getFileName.toString.stripSuffix(".png")
      println(f"BEST: ${best.entry.path} ${best.similarity.get}%.4f for query $queryStem; worst ${sims.last}%.4f")
      require(best.entry.path.contains(queryStem), s"the top hit ${best.entry.path} should be a character of the query image $queryStem")
      require(best.similarity.get > 0.8, s"similarity of the query's own character: ${best.similarity}")
      snapshot("chara-04-image-search.png")
      checked(f"Search Image: all ${sorted.size} hits sorted by CLIP similarity (${sims.head}%.3f .. ${sims.last}%.3f); the top hit ${best.entry.path} is a cut-out of the query image itself")

      // ---- threshold hides, click modes ----------------------------------------------------------------------------------
      val mid = (sims.head + sims.last) / 2
      val expectAbove = sims.count(_ >= mid)
      setText("chara-threshold-text", f"$mid%.3f"); fx(find[TextField]("chara-threshold-text").fireEvent(new javafx.event.ActionEvent()))
      val rounded = f"$mid%.3f".toDouble
      eventually("threshold applied")(tileCount == sims.count(_ >= rounded) && tileCount > 0 && tileCount < sorted.size)
      checked(s"Threshold ${f"$mid%.3f"} hides the dissimilar ones ($tileCount of ${sorted.size} remain; ${expectAbove} expected)")
      val before = tileCount
      click(tiles.last); eventually("excluded")(tileCount == before - 1 && label("chara-summary").contains("1 excluded"))
      press("chara-reset-excluded"); eventually("reset")(tileCount == before && !label("chara-summary").contains("excluded"))
      fx(find[ToggleButton]("chara-click-threshold").setSelected(true))
      val target = fx(vm.results.last)
      click(tiles.last)
      eventually("threshold from click")(fx(math.abs(vm.threshold - math.floor(target.similarity.get * 1000) / 1000) < 1e-9))
      eventually("results after threshold click")(tileCount >= 1 && tileCount <= before)
      checked(s"Click on the Image: Exclude hides one image and Reset Excluded Images brings it back; Threshold mode set the threshold to ${f"${vm.threshold}%.3f"} from the clicked image")
      snapshot("chara-05-threshold.png")
      fx(find[ToggleButton]("chara-click-exclude").setSelected(true))
      fx(find[Slider]("chara-threshold").setValue(0.0))
      eventually("all again")(tileCount == cutouts.size)

      // ---- export --------------------------------------------------------------------------------------------------------------
      val exportDir = work.resolve("export")
      setText("chara-export-dir", exportDir.toString); setText("chara-export-additional", "mychar, 1girl"); setText("chara-export-exclude", common)
      fx(find[TextField]("chara-tags").setText("")); press("chara-clear-image"); eventually("no image")(fx(vm.queryVector.isEmpty))
      eventually("export ready")(tileCount == cutouts.size)
      press("chara-export")
      eventually("exported", 60)(label("chara-status").startsWith("Exported"))
      val exported = Files.list(exportDir).iterator().asScala.toVector
      val pngs = exported.filter(_.toString.endsWith(".png"))
      val txts = exported.filter(_.toString.endsWith(".txt"))
      require(pngs.size == cutouts.size && txts.size == cutouts.size, s"exported ${pngs.size} png / ${txts.size} txt")
      val sample = Files.readString(txts.sortBy(_.toString).head)
      require(sample.startsWith("mychar, 1girl"), sample)
      require(!txts.exists(t => Files.readString(t).split(", ").exists(_.trim.toLowerCase == common)), s"excluded tag '$common' absent from every caption")
      checked(s"Export copied ${pngs.size} images with ${txts.size} captions that start with the additional tags and never contain the excluded tag '$common' (first caption: ${sample.take(120)})")
      snapshot("chara-06-exported.png")

      // ---- second import is incremental --------------------------------------------------------------------------------------
      val first = cutouts.size
      setText("chara-sources", query.toString)
      press("chara-import")
      eventually("second import", 600)(fx(find[Button]("chara-import").isDisabled == false) && label("chara-status").startsWith("Imported"))
      val second = Files.list(dir).iterator().asScala.count(_.toString.endsWith(".png"))
      require(second > first, s"the second import added files ($first -> $second)")
      checked(s"Importing another file into the same dataset keeps the existing ones and adds the new cut-outs ($first -> $second): ${label("chara-status")}")

      // ---- bad input is reported, nothing breaks ------------------------------------------------------------------------------
      setText("chara-new-name", "../escape")
      press("chara-import")
      eventually("bad name reported")(label("chara-status").startsWith("Give the dataset a name"))
      require(!Files.exists(home.root.resolve("escape")) && !Files.exists(vm.root.resolve("../escape")))
      setText("chara-new-name", "bad set"); setText("chara-sources", work.resolve("missing.png").toString)
      press("chara-import")
      eventually("missing file reported", 30)(label("chara-status").nonEmpty && !label("chara-status").startsWith("Imported") && !label("chara-status").startsWith("Importing"))
      checked(s"A dataset name with '/' and a missing source are refused with a message (status: '${label("chara-status")}')")

      println("CHARA SEARCHER CHECK PASSED (" + checks.size + " groups)")
      Files.writeString(output.resolve("chara-report.txt"), checks.mkString("\n"))
      try fx { window.charaSearcher.dispose() } catch case _: Throwable => ()
      Platform.exit()
      System.exit(0)
    catch case e: Throwable =>
      e.printStackTrace()
      try snapshot("chara-FAILED.png") catch case _: Throwable => ()
      System.exit(1)
