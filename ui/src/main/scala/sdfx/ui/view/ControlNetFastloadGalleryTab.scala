package sdfx.ui.view

import javafx.geometry.Insets
import javafx.scene.control.{Button, ComboBox, ContentDisplay, ListCell, ListView, ScrollPane, TextField, Tooltip}
import javafx.scene.image.ImageView
import javafx.scene.layout.{HBox, Priority, Region, TilePane, VBox}
import javafx.stage.DirectoryChooser
import sdfx.core.png.PngMetadata
import sdfx.ui.Fx
import sdfx.ui.vm.{FastloadThumbnail, ImageReadGate, Mode}
import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** Browse metadata without persisting decoded pixels or the index. */
final class ControlNetFastloadGalleryTab(ctx: UiContext, inspect: Path => Unit):
  private case class Filter(key: String, value: String):
    override def toString: String = s"$key: $value"
  private val directory = new TextField()
  directory.setId("fastload-gallery-directory")
  directory.setPromptText("Image directory")
  HBox.setHgrow(directory, Priority.ALWAYS)
  private val key = new ComboBox[String]()
  private val value = new ComboBox[String]()
  key.setId("fastload-gallery-key"); value.setId("fastload-gallery-value")
  key.setPromptText("Filter field"); value.setPromptText("Filter value")
  private val selectedFilters = new ListView[Filter]()
  selectedFilters.setId("fastload-gallery-filters"); selectedFilters.setPrefHeight(85); selectedFilters.setMinHeight(60)
  private val files = new ListView[String]()
  files.setId("fastload-gallery-files")
  files.setPrefHeight(90); files.setMinHeight(60)
  files.setCellFactory(_ => new ListCell[String]():
    override def updateItem(item: String, empty: Boolean): Unit =
      super.updateItem(item, empty)
      setText(if empty || item == null then null else item)
  )
  private val preview = new ImageView()
  preview.setId("fastload-gallery-preview"); preview.setPreserveRatio(true); preview.setFitWidth(360); preview.setFitHeight(240)
  private val status = W.label("Choose a directory and scan its image metadata.", "muted")
  status.setId("fastload-gallery-status"); status.setWrapText(true)
  private val filters = ArrayBuffer.empty[Filter]
  private var choices = Map.empty[String, Vector[String]]
  private var page = 1
  private var pages = 0
  private var revision = 0L
  private var indexedSession: Option[Long] = None
  private var busy = false
  private var disposed = false
  private var previewReady = false
  private val thumbnailRevision = new AtomicLong(0L)
  private val grid = new TilePane()
  grid.setId("fastload-gallery-grid"); grid.setPrefColumns(6); grid.setHgap(6); grid.setVgap(6)
  private val gridScroll = new ScrollPane(grid)
  gridScroll.setId("fastload-gallery-grid-scroll"); gridScroll.setFitToWidth(true); gridScroll.setPrefHeight(220); gridScroll.setMinHeight(140)
  private val reads = new ImageReadGate[ImageFiles.DisplayImage](ctx.services.imageScrambleSession,
    run => Fx.run(run()), (path, secret) => boundedPreview(path, secret))(using Fx.bg)
  private def boundedPreview(path: Path, secret: Option[sdfx.core.png.ImageScrambleSecret]): ImageFiles.DisplayImage =
    val input = new javax.imageio.stream.FileImageInputStream(path.toFile)
    try
      val readers = javax.imageio.ImageIO.getImageReaders(input)
      require(readers.hasNext, "Image is not readable")
      val reader = readers.next()
      try
        reader.setInput(input, true, true)
        require(reader.getWidth(0).toLong * reader.getHeight(0).toLong <= 32000000, "Image is too large")
      finally reader.dispose()
    finally input.close()
    ImageFiles.readForDisplay(path, secret)
  private val browse = W.button("Browse", "Choose an image directory") {
    val picker = new DirectoryChooser()
    Option(picker.showDialog(ctx.stage)).foreach(file => directory.setText(file.getAbsolutePath))
  }
  private val scan = W.button("Scan", "Scan image metadata") { page = 1; search() }
  scan.setId("fastload-gallery-scan")
  private val add = W.button("Add filter", "Require this metadata value") {
    for k <- Option(key.getValue); v <- Option(value.getValue) do
      val item = Filter(k, v)
      if !filters.contains(item) then { filters += item; selectedFilters.getItems.add(item); page = 1; search() }
  }
  private val remove = W.button("Remove filter", "Remove selected filter") {
    Option(selectedFilters.getSelectionModel.getSelectedItem).foreach { item =>
      filters -= item; selectedFilters.getItems.remove(item); page = 1; search()
    }
  }
  add.setId("fastload-gallery-add"); remove.setId("fastload-gallery-remove")
  private val previous = W.button("Previous", "Previous page") { if page > 1 then { page -= 1; search() } }
  private val next = W.button("Next", "Next page") { if page < pages then { page += 1; search() } }
  private val first = W.button("First", "First page") { if pages > 0 then { page = 1; search() } }
  private val last = W.button("Last", "Last page") { if pages > 0 then { page = pages; search() } }
  private val pageInput = new TextField("1")
  pageInput.setId("fastload-gallery-page"); pageInput.setPrefColumnCount(4); pageInput.setMaxWidth(90)
  private val go = W.button("Go", "Open page") {
    pageInput.getText.trim.toIntOption match
      case Some(n) if n >= 1 && n <= pages => page = n; search()
      case _ => status.setText("Choose a page in the displayed range.")
  }
  private val inspectButton = W.button("Inspect Fastload data", "Open embedded or matching sidecar data") { inspectSelected() }
  private val sendTxt = W.button("Send to txt2img", "Send selected image and metadata") { send(Mode.Txt2Img) }
  private val sendImg = W.button("Send to img2img", "Send selected image and metadata") { send(Mode.Img2Img) }
  private val paramsTxt = W.button("Send parameters to txt2img", "Apply metadata without changing the input image") { sendParameters(Mode.Txt2Img) }
  private val paramsImg = W.button("Send parameters to img2img", "Apply metadata without changing the input image") { sendParameters(Mode.Img2Img) }
  private val controlTxt = W.button("Set ControlNet image (txt2img)", "Set unit 1 image without changing its other values") { sendControlImage(Mode.Txt2Img) }
  private val controlImg = W.button("Set ControlNet image (img2img)", "Set unit 1 image without changing its other values") { sendControlImage(Mode.Img2Img) }
  previous.setId("fastload-gallery-prev"); next.setId("fastload-gallery-next")
  first.setId("fastload-gallery-first"); last.setId("fastload-gallery-last"); go.setId("fastload-gallery-go")
  inspectButton.setId("fastload-gallery-inspect"); sendTxt.setId("fastload-gallery-send-txt"); sendImg.setId("fastload-gallery-send-img")
  paramsTxt.setId("fastload-gallery-params-txt"); paramsImg.setId("fastload-gallery-params-img")
  controlTxt.setId("fastload-gallery-control-txt"); controlImg.setId("fastload-gallery-control-img")
  private val help = W.label("Filter ControlNet metadata using all selected conditions. Sending an image requires a readable preview. " +
    "Nested links and oversized images are skipped and reported; some original gallery actions are not yet available.", "muted")
  help.setWrapText(true); help.setMaxWidth(Double.MaxValue); help.setMinHeight(Region.USE_PREF_SIZE)
  val node = new VBox(8, W.label("ControlNet Fastload Filter", "section-title"), help,
    W.hbox(8, directory, browse, scan), W.hbox(8, key, value, add, remove), selectedFilters,
    W.hbox(8, first, previous, pageInput, go, next, last), gridScroll, files, preview,
    W.hbox(8, inspectButton, sendTxt, sendImg), W.hbox(8, paramsTxt, paramsImg), W.hbox(8, controlTxt, controlImg), status)
  node.setPadding(new Insets(14)); node.setMinWidth(0); VBox.setVgrow(gridScroll, Priority.ALWAYS)
  private def selectedPath: Option[Path] =
    if indexedSession.contains(ctx.services.imageScrambleSession.state.revision) then
      Option(files.getSelectionModel.getSelectedItem).map(Paths.get(_))
    else None
  private def actionPath: Option[Path] =
    if indexedSession.exists(_ != ctx.services.imageScrambleSession.state.revision) then
      clear(); status.setText("Image session changed. Scan again."); None
    else selectedPath
  private def updateControls(): Unit =
    directory.setDisable(busy); browse.setDisable(busy); scan.setDisable(busy || directory.getText.trim.isEmpty)
    key.setDisable(busy); value.setDisable(busy); add.setDisable(busy || key.getValue == null || value.getValue == null)
    remove.setDisable(busy || selectedFilters.getSelectionModel.getSelectedItem == null)
    files.setDisable(busy); gridScroll.setDisable(busy)
    previous.setDisable(busy || page <= 1); next.setDisable(busy || page >= pages)
    first.setDisable(busy || page <= 1); last.setDisable(busy || pages == 0 || page >= pages)
    pageInput.setDisable(busy || pages == 0); go.setDisable(busy || pages == 0)
    val noSelection = busy || selectedPath.isEmpty
    inspectButton.setDisable(noSelection); sendTxt.setDisable(noSelection || !previewReady); sendImg.setDisable(noSelection || !previewReady)
    paramsTxt.setDisable(noSelection); paramsImg.setDisable(noSelection)
    controlTxt.setDisable(noSelection || !previewReady); controlImg.setDisable(noSelection || !previewReady)
  private def clear(): Unit =
    revision += 1; busy = false; reads.invalidate(); thumbnailRevision.incrementAndGet(); grid.getChildren.clear()
    choices = Map.empty; page = 1; pages = 0; indexedSession = None; previewReady = false; pageInput.setText("1")
    filters.clear(); selectedFilters.getItems.clear(); files.getItems.clear(); key.getItems.clear(); value.getItems.clear()
    preview.setImage(null); updateControls()
  directory.textProperty.addListener((_, _, _) => { clear(); status.setText("Directory changed. Scan to continue.") })
  key.valueProperty.addListener((_, _, _) =>
    value.getItems.setAll(choices.getOrElse(Option(key.getValue).getOrElse(""), Vector.empty)*)
    updateControls())
  value.valueProperty.addListener((_, _, _) => updateControls())
  selectedFilters.getSelectionModel.selectedItemProperty.addListener((_, _, _) => updateControls())
  files.getSelectionModel.selectedItemProperty.addListener((_, _, _) =>
    reads.invalidate(); previewReady = false; preview.setImage(null); updateControls()
    selectedPath.foreach { path =>
      val session = ctx.services.imageScrambleSession.state
      reads.request(path) {
        result => ctx.services.imageScrambleSession.synchronized {
          if ctx.services.imageScrambleSession.state.revision == session.revision then result match
            case Success(image) => preview.setImage(image.image); previewReady = true; updateControls()
            case Failure(error) => previewReady = false; status.setText(s"Cannot preview image: ${Fx.message(error)}"); updateControls()
          else { clear(); status.setText("Image session changed. Scan again.") }
        }
      }
    })
  ctx.watchImageAccess(node) { () => clear(); status.setText("Image session changed. Scan again.") }
  updateControls()

  private def search(): Unit =
    if busy || directory.getText.trim.isEmpty then return
    if indexedSession.exists(_ != ctx.services.imageScrambleSession.state.revision) then
      clear(); status.setText("Image session changed. Scan again."); return
    val absolute = try Paths.get(directory.getText.trim).toAbsolutePath.normalize().toString
      catch case NonFatal(error) => { status.setText(Fx.message(error)); return }
    revision += 1
    val request = revision
    val session = ctx.services.imageScrambleSession.state
    val args = ujson.Obj("operation" -> "gallery", "path" -> absolute, "page" -> page,
      "filters" -> ujson.Arr.from(filters.map(f => ujson.Arr(f.key, f.value))))
    busy = true; reads.invalidate(); thumbnailRevision.incrementAndGet(); grid.getChildren.clear()
    previewReady = false; files.getItems.clear(); preview.setImage(null); updateControls(); status.setText("Scanning image metadata…")
    Fx.onFx(ctx.services.client.controlNetFastloadData(args, session.secret)) { result =>
      ctx.services.imageScrambleSession.synchronized {
        if !disposed && request == revision then
          if ctx.services.imageScrambleSession.state.revision != session.revision then
            clear(); status.setText("Image session changed. Scan again.")
          else
            try
              choices = result("choices").obj.iterator.map((k, v) => k -> v.arr.map(_.str).toVector).toMap
              val oldKey = Option(key.getValue); key.getItems.setAll(choices.keys.toVector.sorted*)
              oldKey.filter(choices.contains).foreach(key.setValue)
              page = result("page").num.toInt; pages = result("pages").num.toInt
              pageInput.setText(page.toString)
              indexedSession = Some(session.revision)
              val paths = result("paths").arr.map(_.str).toVector
              files.getItems.setAll(paths*)
              loadThumbnails(paths, session)
              val links = result("skipped_links").num.toInt; val large = result("skipped_oversize").num.toInt
              status.setText(s"Page $page of $pages. Skipped links: $links; oversized images: $large.")
            catch case NonFatal(error) => status.setText(Fx.message(error))
            busy = false; updateControls()
      }
    } { error => ctx.services.imageScrambleSession.synchronized {
      if !disposed && request == revision then
        if ctx.services.imageScrambleSession.state.revision != session.revision then
          clear(); status.setText("Image session changed. Scan again.")
        else { busy = false; status.setText(Fx.message(error)); updateControls() }
    } }

  private def send(mode: Mode): Unit =
    ctx.services.imageScrambleSession.synchronized {
      actionPath.foreach { path => ctx.sendToTab(mode, path, PngMetadata.readParameters(path)) }
    }

  private def inspectSelected(): Unit =
    val chosen = actionPath
    if busy || chosen.isEmpty then return
    revision += 1
    val request = revision
    val session = ctx.services.imageScrambleSession.state
    busy = true; updateControls(); status.setText("Checking Fastload data…")
    val args = ujson.Obj("operation" -> "gallery_source", "path" -> chosen.get.toString)
    Fx.onFx(ctx.services.client.controlNetFastloadData(args, session.secret)) { result =>
      ctx.services.imageScrambleSession.synchronized {
        if !disposed && request == revision then
          if ctx.services.imageScrambleSession.state.revision != session.revision then
            clear(); status.setText("Image session changed. Scan again.")
          else
            busy = false; updateControls()
            if !selectedPath.contains(chosen.get) then status.setText("Selection changed. Try again.")
            else
              try result("path") match
                case ujson.Null => status.setText("The selected image has no Fastload data or matching sidecar.")
                case ujson.Str(path) =>
                  status.setText(s"Opening ${result("kind").str} Fastload data…")
                  inspect(Paths.get(path))
                case _ => status.setText("Invalid Fastload source response.")
              catch case NonFatal(error) => status.setText(Fx.message(error))
      }
    } { error => ctx.services.imageScrambleSession.synchronized {
      if !disposed && request == revision then
        if ctx.services.imageScrambleSession.state.revision != session.revision then
          clear(); status.setText("Image session changed. Scan again.")
        else { busy = false; status.setText(Fx.message(error)); updateControls() }
    } }

  private def sendParameters(mode: Mode): Unit =
    ctx.services.imageScrambleSession.synchronized {
      actionPath.foreach { path =>
        PngMetadata.readParameters(path) match
          case None => status.setText("The selected image has no parameters metadata.")
          case Some(text) =>
            try ctx.applyFastloadInfotext(mode, text) match
              case Left(message) => status.setText(message)
              case Right(_) => status.setText(s"Sent parameters to ${mode.label}.")
            catch case NonFatal(error) => status.setText(Fx.message(error))
      }
    }

  private def sendControlImage(mode: Mode): Unit =
    ctx.services.imageScrambleSession.synchronized {
      actionPath.foreach { path =>
        ctx.fastloadTarget(mode, 0) match
          case None => status.setText("ControlNet destination is unavailable.")
          case Some(existing) =>
            try ctx.applyFastloadTarget(mode, 0, existing, existing.copy(image = Some(path.toString))) match
              case Left(message) => status.setText(message)
              case Right(_) => status.setText(s"Set ${mode.label} ControlNet unit 1 image.")
            catch case NonFatal(error) => status.setText(Fx.message(error))
      }
    }

  private def loadThumbnails(paths: Vector[String], session: sdfx.core.png.ImageScrambleState): Unit =
    val ticket = thumbnailRevision.incrementAndGet()
    val buttons = paths.map { path =>
      val button = new Button(Paths.get(path).getFileName.toString)
      button.setContentDisplay(ContentDisplay.TOP); button.setPrefSize(150, 125); button.setMaxSize(150, 125)
      button.setTooltip(new Tooltip(path))
      button.setOnAction(_ => files.getSelectionModel.select(path))
      grid.getChildren.add(button)
      path -> button
    }
    Future {
      buttons.foreach { (path, button) =>
        if thumbnailRevision.get() == ticket && ctx.services.imageScrambleSession.state.revision == session.revision then
          val image = scala.util.Try(FastloadThumbnail.read(Paths.get(path), session.secret))
          Fx.run {
            ctx.services.imageScrambleSession.synchronized {
              if !disposed && thumbnailRevision.get() == ticket && ctx.services.imageScrambleSession.state.revision == session.revision then
                image match
                  case Success(pixels) =>
                    val view = new ImageView(javafx.embed.swing.SwingFXUtils.toFXImage(pixels, null))
                    view.setPreserveRatio(true); view.setFitWidth(144); view.setFitHeight(96); button.setGraphic(view)
                  case Failure(_) => button.setGraphic(W.label("Preview unavailable", "muted"))
            }
          }
      }
    }(using Fx.bg)
    ()

  def dispose(): Unit =
    disposed = true; clear()
