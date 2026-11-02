package sdfx.ui.view

import javafx.collections.FXCollections
import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.image.{Image, ImageView}
import javafx.scene.layout.*
import javafx.stage.{DirectoryChooser, FileChooser}
import sdfx.core.charasearch.*
import sdfx.core.protocol.EngineEvent
import sdfx.engine.{CharaSearcherHandle, CharaSearcherService}
import sdfx.ui.Fx
import sdfx.ui.vm.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** The "Character Searcher" tab (extension chara-searcher): cut characters out of images and videos, tag and embed them
  * into datasets, then search those datasets by image and tags and export the result as a training set.
  * All decisions live in [[CharaSearcherVM]] and `sdfx.core.charasearch`; this class builds controls and forwards events.
  */
final class CharaSearcherTab(ctx: UiContext, root: Path):
  val vm = new CharaSearcherVM(root)
  private val service = new CharaSearcherService(ctx.services)
  val node = new BorderPane()
  node.setId("chara-searcher")
  private val MaxShown = 300
  private val ThumbSize = 150.0

  private val status = W.label("Create or pick a dataset, add images or videos and import them.", "muted")
  status.setId("chara-status")
  status.setWrapText(true)
  private def say(text: String): Unit = status.setText(text)

  // ---- datasets -------------------------------------------------------------------------------------------------
  private val datasets = new ListView[String]()
  datasets.setId("chara-datasets")
  datasets.getSelectionModel.setSelectionMode(SelectionMode.MULTIPLE)
  datasets.setPrefHeight(110)
  W.tip(datasets, "Target Datas: the datasets to search (select several with Ctrl)")
  private val refreshButton = W.button("Refresh", "Rescan the dataset folder") { refreshDatasets(vm.selected) }
  refreshButton.setId("chara-refresh")
  private var suppress = false
  datasets.getSelectionModel.getSelectedItems.addListener((_: javafx.collections.ListChangeListener.Change[? <: String]) =>
    if !suppress then selectDatasets(datasets.getSelectionModel.getSelectedItems.asScala.toVector))

  def refreshDatasets(keep: Seq[String] = Nil): Unit =
    val names = vm.datasetNames
    suppress = true
    datasets.getItems.setAll(names*)
    keep.filter(names.contains).foreach(n => datasets.getSelectionModel.select(n))
    suppress = false
    selectDatasets(datasets.getSelectionModel.getSelectedItems.asScala.toVector)

  private var loadTicket = 0L
  private def selectDatasets(names: Vector[String]): Unit =
    loadTicket += 1
    val ticket = loadTicket
    vm.select(Nil)
    render()
    if names.nonEmpty then
      say("Loading datasets…")
      Fx.async(names.map(n => try Right(vm.read(n)) catch case NonFatal(e) => Left(s"$n: ${Fx.message(e)}"))) { loaded =>
        if ticket == loadTicket then
          vm.install(loaded.collect { case Right(d) => d })
          vm.select(names.filter(n => loaded.exists(_.exists(_.name == n))))
          val problems = loaded.collect { case Left(m) => m } ++ loaded.collect { case Right(d) => d.problems.map(p => s"${d.name}: $p") }.flatten
          render()
          if problems.nonEmpty then say(s"${vm.results.size} of ${vm.total} images. Problems: ${problems.take(3).mkString("; ")}")
      }(e => say(Fx.message(e)))

  // ---- import ---------------------------------------------------------------------------------------------------
  private val newName = new TextField()
  newName.setId("chara-new-name")
  newName.setPromptText("Dataset name (new or existing)")
  private val sources = new TextArea()
  sources.setId("chara-sources")
  sources.setPromptText("Images, folders and videos to import, one path per line")
  sources.setPrefRowCount(3)
  private def addPaths(files: Seq[Path]): Unit =
    val have = sources.getText.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    sources.setText((have ++ files.map(_.toAbsolutePath.toString).filterNot(have.contains)).mkString("\n"))
  private val addImages = W.button("Upload Images…", "Choose images to cut characters out of") {
    val fc = ImageFiles.chooser("Images to import")
    Option(fc.showOpenMultipleDialog(ctx.stage)).foreach(fs => addPaths(fs.asScala.map(_.toPath).toSeq))
  }
  addImages.setId("chara-add-images")
  private val addFolder = W.button("Upload Images Directory…", "Choose a folder of images (sub folders included)") {
    Option(new DirectoryChooser().showDialog(ctx.stage)).foreach(f => addPaths(Seq(f.toPath)))
  }
  addFolder.setId("chara-add-folder")
  private val addVideo = W.button("Upload Video File…", "Choose videos; one frame every few seconds is used") {
    val fc = new FileChooser()
    fc.getExtensionFilters.add(new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.mov", "*.mkv", "*.avi", "*.webm", "*.m4v"))
    Option(fc.showOpenMultipleDialog(ctx.stage)).foreach(fs => addPaths(fs.asScala.map(_.toPath).toSeq))
  }
  addVideo.setId("chara-add-video")
  private def number(initial: Double, id: String, tip: String) =
    val f = new TextField(initial.toString)
    f.setId(id); f.setPrefColumnCount(5); W.tip(f, tip)
    f
  private val maskThreshold = number(0.6, "chara-mask-threshold", "Mask threshold: how sure the cut-out has to be that a pixel belongs to the character (0 to 1)")
  private val instanceThreshold = number(0.3, "chara-instance-threshold", "Instance threshold: minimum score of a detected character (0 to 1)")
  private val padding = number(0.1, "chara-padding", "Padding around the character as a fraction of its size")
  private val tagThreshold = number(0.35, "chara-tag-threshold", "Tagging threshold (0 to 1)")
  private val frameInterval = number(4.0, "chara-frame-interval", "Seconds between the frames taken from a video")
  private val importExclude = new TextField()
  importExclude.setId("chara-import-exclude")
  importExclude.setPromptText("Tags never written to the captions, comma separated")
  private val progress = new ProgressBar(0)
  progress.setId("chara-progress")
  progress.setMaxWidth(Double.MaxValue)
  private var active: Option[CharaSearcherHandle] = None
  private val importButton = W.button("Import", "Cut out, tag and embed every character of the sources into the dataset", Seq("primary"))(runImport())
  importButton.setId("chara-import")
  private val cancelButton = W.button("Cancel", "Stop the running import at the next safe point") {
    active.foreach(h => Fx.onFx(h.interrupt())(d => say(if d.affected then "Cancelling…" else d.message.getOrElse("Nothing to cancel")))(e => say(Fx.message(e))))
  }
  cancelButton.setId("chara-cancel")
  cancelButton.setDisable(true)

  private def parse(field: TextField, label: String): Double =
    field.getText.trim.toDoubleOption.getOrElse(throw new IllegalArgumentException(s"$label must be a number"))

  private def runImport(): Unit =
    try
      val name = vm.validName(newName.getText).getOrElse(throw new IllegalArgumentException("Give the dataset a name (letters, digits, space, - _ .)"))
      val paths = sources.getText.linesIterator.map(_.trim).filter(_.nonEmpty).map(Paths.get(_)).toSeq
      if paths.isEmpty then throw new IllegalArgumentException("Add images, a folder or a video first")
      val options = service.ImportOptions(parse(maskThreshold, "Mask threshold"), parse(instanceThreshold, "Instance threshold"),
        parse(padding, "Padding"), parse(tagThreshold, "Tag threshold"), parse(frameInterval, "Frame interval"), importExclude.getText)
      importButton.setDisable(true); cancelButton.setDisable(false)
      progress.setProgress(-1)
      say(s"Importing into '$name'… the first run downloads the models.")
      val handle = service.importSources(vm.datasetDir(name), paths, options, {
        case EngineEvent.Progress(step, total, _, _, _, _, _) => Fx.run(progress.setProgress(if total > 0 then step.toDouble / total else -1))
        case EngineEvent.Log(_, message) => Fx.run(say(message))
        case _ => ()
      })
      active = Some(handle)
      Fx.onFx(handle.result) { value =>
        active = None
        importButton.setDisable(false); cancelButton.setDisable(true); progress.setProgress(1)
        val errors = value("errors").arr.map(_.str)
        refreshDatasets((vm.selected :+ name).distinct)
        say(s"Imported ${value("created").arr.size} character image(s) from ${value("sources")} source(s) into '$name' (${value("total")} in total)." +
          (if errors.nonEmpty then s" ${errors.size} file(s) failed: ${errors.take(2).mkString("; ")}" else ""))
      } { error =>
        active = None
        importButton.setDisable(false); cancelButton.setDisable(true); progress.setProgress(0)
        refreshDatasets(vm.selected)
        say(Fx.message(error))
      }
    catch case NonFatal(e) => say(Fx.message(e).stripPrefix("requirement failed: "))

  // ---- search ---------------------------------------------------------------------------------------------------
  private val imageLabel = W.label("No search image", "muted")
  imageLabel.setId("chara-image-label")
  private val searchImage = W.button("Search Image…", "Choose an image; characters that look like it are listed first") {
    val fc = ImageFiles.chooser("Search image")
    Option(fc.showOpenDialog(ctx.stage)).foreach(f => setSearchImage(f.toPath))
  }
  searchImage.setId("chara-search-image")
  private val clearImage = W.button("Clear", "Remove the search image") {
    loadTicket += 1; vm.setImage(None, None); imageLabel.setText("No search image"); render()
  }
  clearImage.setId("chara-clear-image")
  def setSearchImage(path: Path): Unit =
    say("Embedding the search image…")
    imageLabel.setText(path.getFileName.toString)
    val handle = service.embed(path)
    Fx.onFx(handle.result) { value =>
      vm.setImage(Some(path), Some(CharaSearcherService.embeddingOf(value)))
      render()
      say(s"Search image: ${path.getFileName}")
    }(e => { imageLabel.setText("No search image"); say(Fx.message(e)) })
  private val threshold = new Slider(0, 1, vm.threshold)
  threshold.setId("chara-threshold")
  threshold.setBlockIncrement(0.01)
  private val thresholdText = new TextField(f"${vm.threshold}%.3f")
  thresholdText.setId("chara-threshold-text")
  thresholdText.setPrefColumnCount(5)
  private var syncing = false
  threshold.valueProperty.addListener((_, _, v) => if !syncing then { syncing = true; thresholdText.setText(f"${v.doubleValue}%.3f"); vm.threshold = v.doubleValue; render(); syncing = false })
  thresholdText.setOnAction(_ => thresholdText.getText.trim.toDoubleOption.foreach(v => setThreshold(v)))
  private def setThreshold(v: Double): Unit =
    syncing = true
    val c = math.max(0, math.min(1, v))
    vm.threshold = c; threshold.setValue(c); thresholdText.setText(f"$c%.3f")
    syncing = false
    render()
  private val tagsField = new TextField()
  tagsField.setId("chara-tags")
  tagsField.setPromptText("Search Tags: comma separated")
  private val negativeField = new TextField()
  negativeField.setId("chara-negative-tags")
  negativeField.setPromptText("Negative Tags: comma separated")
  private val searchButton = W.button("Search", "Apply the tags and the threshold") {
    vm.tags = tagsField.getText; vm.negativeTags = negativeField.getText; render()
  }
  searchButton.setId("chara-search")
  tagsField.setOnAction(_ => searchButton.fire())
  negativeField.setOnAction(_ => searchButton.fire())
  private val excludeMode = new ToggleButton("Exclude")
  private val thresholdMode = new ToggleButton("Threshold")
  private val modes = new ToggleGroup()
  excludeMode.setToggleGroup(modes); thresholdMode.setToggleGroup(modes); excludeMode.setSelected(true)
  excludeMode.setId("chara-click-exclude"); thresholdMode.setId("chara-click-threshold")
  W.tip(excludeMode, "Clicking an image hides it from the result")
  W.tip(thresholdMode, "Clicking an image sets the threshold to its similarity")
  modes.selectedToggleProperty.addListener((_, _, t) => if t == null then excludeMode.setSelected(true) else vm.click = if t eq thresholdMode then CharaClick.Threshold else CharaClick.Exclude)
  private val resetExcluded = W.button("Reset Excluded Images", "Show the images hidden by clicking again") { vm.resetExcluded(); render() }
  resetExcluded.setId("chara-reset-excluded")

  // ---- export ---------------------------------------------------------------------------------------------------
  private val exportDir = new TextField(root.resolve("export").toAbsolutePath.toString)
  exportDir.setId("chara-export-dir")
  private val exportBrowse = W.button("…", "Choose the export folder") {
    Option(new DirectoryChooser().showDialog(ctx.stage)).foreach(f => exportDir.setText(f.getAbsolutePath))
  }
  private val additionalTags = new TextField()
  additionalTags.setId("chara-export-additional")
  additionalTags.setPromptText("Additional Tags: comma separated")
  private val excludeTags = new TextField()
  excludeTags.setId("chara-export-exclude")
  excludeTags.setPromptText("Exclude Tags: comma separated")
  private def runExport(): Unit =
    val dir = Paths.get(exportDir.getText.trim)
    val additional = additionalTags.getText
    val exclude = excludeTags.getText
    exportButton.setDisable(true)
    Fx.async(vm.exportHits(dir, additional, exclude)) { result =>
      exportButton.setDisable(false)
      result match
        case Right(r) => say(s"Exported ${r.written.size} image(s) with captions to $dir" + (if r.skipped.nonEmpty then s"; skipped ${r.skipped.size}: ${r.skipped.head}" else ""))
        case Left(m) => say(m)
    }(e => { exportButton.setDisable(false); say(Fx.message(e)) })
  private val exportButton: Button = W.button("Export", "Copy the displayed images with a caption file each", Seq("primary"))(runExport())
  exportButton.setId("chara-export")

  // ---- results --------------------------------------------------------------------------------------------------
  private val grid = new TilePane(8, 8)
  grid.setPadding(new Insets(8))
  grid.setId("chara-grid")
  grid.setPrefTileWidth(ThumbSize + 6)
  private val summary = W.label("", "muted")
  summary.setId("chara-summary")
  private val gridScroll = new ScrollPane(grid)
  gridScroll.setFitToWidth(true)
  gridScroll.setId("chara-scroll")

  private def render(): Unit =
    val hits = vm.search()
    val shown = hits.take(MaxShown)
    grid.getChildren.setAll(shown.map(tile)*)
    summary.setText(
      if vm.selected.isEmpty then "No dataset selected"
      else s"${hits.size} of ${vm.total} images" + (if hits.size > MaxShown then s" (showing the first $MaxShown)" else "") +
        (if vm.excludedCount > 0 then s", ${vm.excludedCount} excluded" else ""))
    gridScroll.setVvalue(0)

  /** Thumbnails are decoded in the background once per file and kept, so moving the threshold never reloads them. */
  private val thumbs = new java.util.LinkedHashMap[Path, Image](256, 0.75f, true):
    override def removeEldestEntry(e: java.util.Map.Entry[Path, Image]): Boolean = size() > 600
  private def thumbnail(file: Path): Image =
    Option(thumbs.get(file)).getOrElse {
      val image = new Image(file.toUri.toString, ThumbSize * 2, ThumbSize * 2, true, true, true)
      thumbs.put(file, image)
      image
    }

  private def tile(hit: CharaHit): Node =
    val view = new ImageView()
    view.setFitWidth(ThumbSize); view.setFitHeight(ThumbSize); view.setPreserveRatio(true); view.setSmooth(true)
    view.setImage(thumbnail(hit.file))
    val holder = new StackPane(view)
    holder.setPrefSize(ThumbSize, ThumbSize); holder.setMinSize(ThumbSize, ThumbSize)
    holder.setStyle("-fx-background-color: #6b6b6b;")
    val caption = new Label(hit.similarity.fold(hit.entry.path)(s => f"$s%.3f  ${hit.entry.path}"))
    caption.setMaxWidth(ThumbSize); caption.setStyle("-fx-font-size: 10px;")
    val box = new VBox(2, holder, caption)
    box.setAlignment(Pos.TOP_CENTER)
    box.setId("chara-tile-" + hit.entry.path)
    W.tip(box, hit.entry.tags.mkString(", ") + s"\n${hit.entry.width}x${hit.entry.height}  ${hit.dataset.name}")
    box.setOnMouseClicked { e =>
      if e.getButton == javafx.scene.input.MouseButton.PRIMARY then
        if e.getClickCount == 2 then ctx.showImage(hit.file)
        else
          val text = vm.clicked(hit)
          render()
          say(text)
    }
    box

  // ---- layout ---------------------------------------------------------------------------------------------------
  private def group(title: String, nodes: Node*): TitledPane =
    val pane = new TitledPane(title, new VBox(6, nodes*))
    pane.setExpanded(true)
    pane.setCollapsible(true)
    pane
  private val left = new VBox(8,
    group("Target Datas", datasets, W.hbox(6, refreshButton)),
    group("Import", newName, W.hbox(6, addImages, addFolder, addVideo), sources,
      new FlowPane(6, 6, W.label("Mask"), maskThreshold, W.label("Instance"), instanceThreshold, W.label("Padding"), padding,
        W.label("Tags"), tagThreshold, W.label("Video s"), frameInterval), importExclude, W.hbox(6, importButton, cancelButton), progress),
    group("Search", W.hbox(6, searchImage, clearImage), imageLabel, W.hbox(6, W.label("Threshold"), threshold, thresholdText), tagsField, negativeField, searchButton,
      W.hbox(6, W.label("Click on the Image"), excludeMode, thresholdMode), resetExcluded),
    group("Export", W.hbox(6, exportDir, exportBrowse), additionalTags, excludeTags, exportButton))
  left.setPadding(new Insets(8))
  HBox.setHgrow(threshold, Priority.ALWAYS)
  private val leftScroll = new ScrollPane(left)
  leftScroll.setFitToWidth(true)
  leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)
  leftScroll.setPrefWidth(430)
  leftScroll.setMinWidth(380)
  private val right = new BorderPane()
  private val head = new VBox(2, W.label("Character Searcher", "section-title"), summary, status)
  head.setPadding(new Insets(8, 12, 4, 12))
  right.setTop(head)
  right.setCenter(gridScroll)
  node.setLeft(leftScroll)
  node.setCenter(right)
  private var shownOnce = false

  /** Called when the tab is selected. */
  def shown(): Unit =
    if !shownOnce then
      shownOnce = true
      Files.createDirectories(root)
    refreshDatasets(vm.selected)

  def dispose(): Unit = active.foreach(_.interrupt())
