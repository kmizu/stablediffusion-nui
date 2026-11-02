package sdfx.ui.view

import javafx.beans.property.{ReadOnlyStringWrapper, SimpleBooleanProperty}
import javafx.collections.FXCollections
import javafx.geometry.{Insets, Orientation, Pos, Rectangle2D}
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.image.{Image, ImageView}
import javafx.scene.input.{KeyCode, KeyEvent, MouseButton}
import javafx.scene.layout.*
import javafx.scene.text.Text
import javafx.stage.DirectoryChooser
import sdfx.core.imagebrowser.*
import sdfx.core.png.ImageScrambleSecret
import sdfx.ui.Fx
import sdfx.ui.vm.*

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** The "Image Browser" tab: one inner tab per active browser tab (txt2img, img2img, grids, Extras, Favorites, Others,
  * All, Maintenance or a custom folder). All decisions live in [[ImageBrowserVM]] / `sdfx.core.imagebrowser`; this class
  * only builds controls and forwards events.
  */
final class ImageBrowserTab(ctx: UiContext, env: BrowserEnv):
  val vm = new ImageBrowserVM(env)
  val node = new BorderPane()
  node.setId("image-browser")
  private val inner = new TabPane()
  inner.setId("image-browser-tabs")
  inner.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE)
  private val status = W.label("", "muted")
  status.setId("image-browser-status")
  private var pages: Vector[Page] = Vector.empty
  private var disposed = false
  private var shownOnce = false

  private val settingsButton = W.button("Settings…", "Image Browser options (tabs, paging, thumbnails, deletion, keys)") { openSettings() }
  settingsButton.setId("image-browser-settings")
  private val header = W.hbox(8, W.label("Image Browser", "section-title"), W.spacer(), status, settingsButton)
  header.setPadding(new Insets(8, 12, 4, 12))
  node.setTop(header)
  node.setCenter(inner)
  inner.getSelectionModel.selectedIndexProperty.addListener((_, _, i) => if shownOnce then pages.lift(i.intValue).foreach(_.activate()))

  private def say(msg: String, ok: Boolean = true): Unit =
    status.setText(msg)
    if !ok then ctx.error(msg)

  /** (Re)creates the inner tabs from the active-tab option. */
  def build(): Unit =
    pages.foreach(_.dispose())
    vm.reset()
    env.store.notice.foreach(n => status.setText(n))
    val specs = vm.specs
    pages = specs.map { spec =>
      if spec.kind == TabKind.Maintenance then new MaintenancePage(spec) else new BrowserPage(spec, vm.tab(spec))
    }
    inner.getTabs.setAll(pages.map { p =>
      val t = new Tab(p.spec.name, p.node)
      t.setId(p.spec.id)
      t
    }*)
    // "Preload images at startup for first tab": otherwise nothing is scanned until the tab is shown.
    if env.settings.preload then pages.collectFirst { case p: BrowserPage => p }.foreach(_.reload())
    if shownOnce then pages.lift(inner.getSelectionModel.getSelectedIndex).foreach(_.activate())

  /** Called when the Image Browser main tab is selected: scans the visible inner tab. */
  def shown(): Unit =
    shownOnce = true
    pages.lift(inner.getSelectionModel.getSelectedIndex).foreach(_.activate())

  def selectInner(name: String): Unit = inner.getTabs.asScala.find(t => sdfx.ui.i18n.Localizer.original(t) == name).foreach(inner.getSelectionModel.select)
  def page(name: String): Option[BrowserPage] = pages.collectFirst { case p: BrowserPage if p.spec.name == name => p }
  def dispose(): Unit = { disposed = true; pages.foreach(_.dispose()); env.cache.persist() }

  // ---- shared helpers -------------------------------------------------------------------------------------
  private def hideIfConfigured(component: String, n: Node): Node =
    if env.settings.hidden(component) then { n.setVisible(false); n.setManaged(false) }
    n

  private def keyName(c: KeyCode): String =
    if c.isKeypadKey && c.getName.startsWith("Numpad ") then c.getName.stripPrefix("Numpad ") else c.getName

  // ---- settings dialog --------------------------------------------------------------------------------------
  def openSettings(): Unit =
    val s = env.settings
    val dlg = new Dialog[ButtonType]()
    dlg.initOwner(ctx.stage)
    dlg.setTitle("Image Browser settings")
    dlg.getDialogPane.getButtonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
    dlg.getDialogPane.setId("image-browser-settings-dialog")
    def cb(text: String, v: Boolean) = { val c = new CheckBox(text); c.setSelected(v); c }
    def num(v: Int, lo: Int, hi: Int) = { val sp = new Spinner[Integer](lo, hi, v); sp.setEditable(true); sp.setPrefWidth(90); sp }
    val tabsField = new TextField(BrowserTabs.formatList(s.activeTabs)); tabsField.setPrefColumnCount(40)
    W.tip(tabsField, "Comma separated: " + BrowserTabs.Defaults.mkString(", ") + ". A folder path adds a custom tab (quote it if it contains commas).")
    val favField = new TextField(s.favoritesDir); favField.setPrefColumnCount(40)
    W.tip(favField, "Folder that the Favorites tab lists and the favorites button moves/copies into (relative to the outputs folder)")
    val orderField = new TextField(s.infoOrder.mkString(",")); orderField.setPrefColumnCount(40)
    val subdirs = cb("Include images in sub directories", s.withSubdirs)
    val preload = cb("Preload images at startup for first tab", s.preload)
    val copy = cb("Move buttons copy instead of move", s.copyInsteadOfMove)
    val delMsg = cb("Print image deletion messages to the console", s.deleteMessage)
    val txt = cb("Move/Copy/Delete matching .txt files", s.txtFiles)
    val recycle = cb("Use recycle bin when deleting images", s.deleteRecycle)
    val scan = cb("Scan Exif-/.txt-data (initially slower, but required for many features to work)", s.scanExif)
    val shift = cb("Change CTRL keybindings to SHIFT", s.modShift)
    val ctrlShift = cb("or to CTRL+SHIFT", s.modCtrlShift)
    val swipe = cb("Swipe left/right navigates to the next image", s.swipe)
    val rankPng = cb("Save ranking in image's pnginfo", s.rankingPnginfo)
    val cols = num(s.pageColumns, 1, 30); val rows = num(s.pageRows, 1, 30); val thumb = num(s.thumbnailSize, 48, 512)
    val crop = cb("Crop thumbnail to square", s.thumbnailCrop)
    val tips = cb("Enable thumbnail tooltips", s.imgTooltips)
    val progress = cb("Show progress indicator", s.showProgress)
    val format = cb("Initially display Generation Info as formatted", s.infoFormat)
    val add = cb("Show Additional Generation Info", s.infoAdd)
    val hiddenChecks = BrowserSettings.Components.map(c => c -> cb(c, s.hidden(c)))
    val grid = new GridPane(); grid.setHgap(8); grid.setVgap(6)
    var r = 0
    def row(l: String, n: Node): Unit = { grid.add(new Label(l), 0, r); grid.add(n, 1, r); r += 1 }
    def single(n: Node): Unit = { grid.add(n, 0, r, 2, 1); r += 1 }
    row("Active tabs", tabsField); row("Favorites folder", favField)
    Seq(subdirs, preload, copy, delMsg, txt, recycle, scan, shift, ctrlShift, swipe, rankPng).foreach(single)
    row("Number of columns on the page", cols); row("Number of rows on the page", rows); row("Size of the thumbnails (px)", thumb)
    Seq(crop, tips, progress, format).foreach(single)
    row("Display order for Generation Info as formatted", orderField)
    single(add)
    single(new Label("Hide components:"))
    val hid = new FlowPane(12, 4); hid.setPrefWrapLength(520); hid.getChildren.addAll(hiddenChecks.map(_._2)*)
    single(hid)
    val sp = new ScrollPane(grid); sp.setFitToWidth(true); sp.setPrefViewportHeight(520); sp.setPrefViewportWidth(620)
    dlg.getDialogPane.setContent(sp)
    val res = dlg.showAndWait()
    if res.isPresent && res.get == ButtonType.OK then
      def n(sp: Spinner[Integer]): Int = { W.commitSpinner(sp); sp.getValue.intValue }
      val updated = env.store.updateSettings(_.copy(
        activeTabs = BrowserTabs.parseList(tabsField.getText), favoritesDir = favField.getText, withSubdirs = subdirs.isSelected,
        preload = preload.isSelected, copyInsteadOfMove = copy.isSelected, deleteMessage = delMsg.isSelected, txtFiles = txt.isSelected,
        deleteRecycle = recycle.isSelected, scanExif = scan.isSelected, modShift = shift.isSelected, modCtrlShift = ctrlShift.isSelected,
        swipe = swipe.isSelected, rankingPnginfo = rankPng.isSelected, pageColumns = n(cols), pageRows = n(rows), thumbnailSize = n(thumb),
        thumbnailCrop = crop.isSelected, imgTooltips = tips.isSelected, showProgress = progress.isSelected, infoFormat = format.isSelected,
        infoOrder = BrowserTabs.parseList(orderField.getText), infoAdd = add.isSelected,
        hiddenComponents = hiddenChecks.collect { case (c, b) if b.isSelected => c }))
      build()
      say(s"Image Browser settings saved (${updated.activeTabs.size} tabs)")

  // ===========================================================================================================
  /** Common behaviour of the inner tab pages. */
  sealed trait Page:
    def spec: TabSpec
    def node: Node
    def activate(): Unit
    def dispose(): Unit

  /** Thumbnails are decoded off the FX thread; entries are keyed by file, mtime and image-session revision. */
  private val thumbCache = new java.util.LinkedHashMap[(Path, Long, Long, Int), (ImageFiles.DisplayImage, Option[(Int, Int)])](256, 0.75f, true):
    override def removeEldestEntry(e: java.util.Map.Entry[(Path, Long, Long, Int), (ImageFiles.DisplayImage, Option[(Int, Int)])]): Boolean = size() > 500
  ctx.watchImageAccess(node)(() => thumbCache.clear())

  private def dimensions(path: Path): Option[(Int, Int)] =
    try
      val in = javax.imageio.ImageIO.createImageInputStream(path.toFile)
      if in == null then None
      else
        try
          val readers = javax.imageio.ImageIO.getImageReaders(in)
          if !readers.hasNext then None
          else
            val rd = readers.next()
            try { rd.setInput(in, true, true); Some((rd.getWidth(0), rd.getHeight(0))) } finally rd.dispose()
        finally in.close()
    catch case NonFatal(_) => None

  // ===========================================================================================================
  final class BrowserPage(val spec: TabSpec, tvm: ImageBrowserTabVM) extends Page:
    private val settings = env.settings
    private var loaded = false
    private var scanTicket = 0L
    private var updating = false
    private var loadedDims: Option[(Int, Int)] = None

    // ---- top controls: Others / Favorites ------------------------------------------------------------------
    private val dirField = new TextField(); dirField.setPromptText("Input images directory"); dirField.setId(spec.id + "-dir")
    private val depthField = new Spinner[Integer](-1, 20, 0); depthField.setEditable(true); depthField.setPrefWidth(80); depthField.setId(spec.id + "-depth")
    W.tip(depthField, "Sub directory depth: 0 = only this folder, N = N levels down, -1 = unlimited")
    private val savedBox = new ComboBox[String](); savedBox.setPromptText("Saved directories"); savedBox.setPrefWidth(260); savedBox.setId(spec.id + "-saved")
    private val subBox = new ComboBox[String](); subBox.setPromptText("Sub directories"); subBox.setPrefWidth(200); subBox.setId(spec.id + "-subdirs")
    private val sourceBar = new VBox(6)
    if spec.kind == TabKind.Others then
      val browse = W.button("Browse", "Choose an image directory") {
        val picker = new DirectoryChooser()
        Option(picker.showDialog(ctx.stage)).foreach(f => { dirField.setText(f.getAbsolutePath); applyOthers() })
      }
      val save = W.button("Add to / replace in saved directories", "Remember this directory and depth") {
        if dirField.getText.trim.nonEmpty then
          W.commitSpinner(depthField)
          env.store.addSavedDir(dirField.getText.trim, depthField.getValue.intValue); refreshSaved()
      }; save.setId(spec.id + "-save")
      val remove = W.button("Remove from saved directories", "Forget the chosen saved directory") {
        Option(savedBox.getValue).foreach { v => env.store.removeSavedDir(savedPath(v)); savedBox.setValue(null); refreshSaved() }
      }; remove.setId(spec.id + "-remove")
      val getSubs = W.button("Get sub directories", "List the folders directly below the chosen directory") { loadSubdirs() }
      getSubs.setId(spec.id + "-getsubs")
      val load = W.button("Load", "Scan this directory") { applyOthers() }; load.setId(spec.id + "-load")
      dirField.setOnAction(_ => applyOthers())
      savedBox.valueProperty.addListener((_, _, v) => if v != null && !updating then
        env.store.savedDirs.find(d => v == label(d)).foreach { d =>
          dirField.setText(d.path); depthField.getValueFactory.setValue(d.depth); applyOthers() })
      subBox.valueProperty.addListener((_, _, v) => if !updating then
        tvm.subdir = Option(v).filter(_ != NoSubdir).map(name => Path.of(dirField.getText.trim).resolve(name))
        reload())
      sourceBar.getChildren.addAll(
        W.hbox(8, W.label("Images directory"), W.hgrow(dirField), browse, load),
        W.hbox(8, W.label("Sub directory depth"), depthField, save),
        W.hbox(8, savedBox, remove),
        W.hbox(8, subBox, getSubs))
      val st = env.store.othersState
      dirField.setText(st.path); depthField.getValueFactory.setValue(st.depth)
      tvm.othersDir = st.path; tvm.othersDepth = st.depth
      refreshSaved()
    else if spec.isFavorites then
      sourceBar.getChildren.add(W.label(s"Favorites path from settings: ${spec.dirs.headOption.getOrElse("")}", "muted"))
    else if spec.kind == TabKind.Custom then
      sourceBar.getChildren.add(W.label(s"Folder: ${spec.dirs.headOption.getOrElse("")}", "muted"))

    private val NoSubdir = "(none)"
    private def label(d: SavedDir): String = s"${d.path} [${d.depth}]"
    private def savedPath(v: String): String = env.store.savedDirs.find(d => label(d) == v).map(_.path).getOrElse(v)
    private def refreshSaved(): Unit =
      updating = true
      savedBox.getItems.setAll(env.store.savedDirs.map(label).asJava)
      updating = false
    private def loadSubdirs(): Unit =
      val d = dirField.getText.trim
      updating = true
      subBox.getItems.setAll((NoSubdir +: (if d.isEmpty then Vector.empty else env.subdirectories(Path.of(d)).map(_.getFileName.toString))).asJava)
      subBox.setValue(NoSubdir)
      updating = false
      tvm.subdir = None
    private def applyOthers(): Unit =
      W.commitSpinner(depthField)
      tvm.othersDir = dirField.getText.trim
      tvm.othersDepth = depthField.getValue.intValue
      env.store.setOthersState(tvm.othersDir, tvm.othersDepth)
      tvm.subdir = None
      loadSubdirs()
      reload()

    // ---- gallery controls bar ---------------------------------------------------------------------------------
    private val firstBtn = W.button("First Page", "")(go(() => tvm.first())); firstBtn.setId(spec.id + "-first")
    private val prevBtn = W.button("Prev Page", "Ctrl+Left")(go(() => tvm.prev())); prevBtn.setId(spec.id + "-prev")
    private val pageField = new TextField("1"); pageField.setPrefColumnCount(4); pageField.setId(spec.id + "-page")
    private val pageLabel = W.label("Page Index (1/1)", "muted"); pageLabel.setId(spec.id + "-pagelabel")
    private val refreshBtn = W.button("⟳", "Refresh (R)")(reload()); refreshBtn.setId(spec.id + "-refresh")
    private val nextBtn = W.button("Next Page", "Ctrl+Right")(go(() => tvm.next())); nextBtn.setId(spec.id + "-next")
    private val endBtn = W.button("End Page", "")(go(() => tvm.end())); endBtn.setId(spec.id + "-end")
    pageField.setOnAction(_ => pageField.getText.trim.toIntOption.foreach(n => go(() => tvm.goTo(n))))
    private val controls = W.hbox(6, firstBtn, prevBtn, pageField, refreshBtn, nextBtn, endBtn, pageLabel)
    private val controlsPanel = hideIfConfigured("Gallery Controls Bar", controls)

    // ---- ranking bar -----------------------------------------------------------------------------------------
    private val rankingCurrent = W.label("None", "hint"); rankingCurrent.setId(spec.id + "-ranking-current")
    private val rankToggle = new ToggleGroup()
    private val rankButtons = (Seq("1", "2", "3", "4", "5", "None")).map { t =>
      val b = new RadioButton(t); b.setToggleGroup(rankToggle); b.setUserData(t); b.setId(s"${spec.id}-rank-$t"); b
    }
    rankToggle.selectedToggleProperty.addListener((_, _, t) => if t != null && !updating then
      val out = tvm.setRanking(t.getUserData.toString.toIntOption)
      say(out.message, out.ok); render())
    private val rankingBar = hideIfConfigured("Ranking Bar", W.hbox(8, W.label("Current ranking"), rankingCurrent, W.label("Set ranking to"), W.hbox(4, rankButtons*)))

    // ---- thumbnails ---------------------------------------------------------------------------------------------
    private val grid = new TilePane(8, 8)
    grid.setPadding(new Insets(6)); grid.setId(spec.id + "-grid"); grid.setPrefTileWidth(settings.thumbnailSize + 6)
    private val gridScroll = new ScrollPane(grid)
    gridScroll.setFitToWidth(true); gridScroll.setId(spec.id + "-scroll"); gridScroll.setMinHeight(300); gridScroll.setPrefHeight(540)
    private val empty = W.label("No images", "muted")
    private val tiles = mutable.LinkedHashMap.empty[Path, Tile]

    private final class Tile(val rec: ImageRecord):
      private val size = settings.thumbnailSize.toDouble
      private val view = new ImageView(); view.setFitWidth(size); view.setFitHeight(size); view.setPreserveRatio(!settings.thumbnailCrop); view.setSmooth(true)
      private val badge = new Label(); badge.setStyle("-fx-background-color: rgba(0,0,0,0.65); -fx-text-fill: gold; -fx-padding: 0 4 0 4; -fx-background-radius: 4;")
      private val name = new Label(rec.fileName); name.setMaxWidth(size); name.setStyle("-fx-font-size: 10px;")
      private val picture = new StackPane(view, badge)
      picture.setPrefSize(size, size); picture.setMinSize(size, size); picture.setMaxSize(size, size)
      StackPane.setAlignment(badge, Pos.TOP_RIGHT)
      val box = new VBox(2, picture, name)
      box.setPadding(new Insets(2)); box.setAlignment(Pos.TOP_CENTER)
      box.setId(spec.id + "-tile-" + rec.fileName.replace('.', '_')); box.setUserData(rec.fileName)
      private val reads = new ImageReadGate[(ImageFiles.DisplayImage, Option[(Int, Int)])](ctx.services.imageScrambleSession,
        run => Fx.run(run()), (p, secret) => readTile(p, secret))(using Fx.bg)
      private var dims: Option[(Int, Int)] = None
      private var tooltip: Tooltip = null
      refreshBadge()
      setTip()
      box.setOnMouseClicked { e =>
        if e.getButton == MouseButton.PRIMARY then
          tvm.select(Some(rec.path)); renderSelection()
          if e.getClickCount == 2 && !rec.isVideo then ctx.showImage(rec.path)
          if e.getClickCount == 2 && rec.isVideo then openExternally(rec.path)
      }
      load()
      private def readTile(p: Path, secret: Option[ImageScrambleSecret]): (ImageFiles.DisplayImage, Option[(Int, Int)]) =
        val key = (p, rec.modified, ctx.services.imageScrambleSession.state.revision, settings.thumbnailSize)
        Option(thumbCache.synchronized(thumbCache.get(key))).getOrElse {
          val d = dimensions(p)
          val img = ImageFiles.readThumbnail(p, secret, (size * 2).toInt)
          val v = (img, d)
          thumbCache.synchronized(thumbCache.put(key, v))
          v
        }
      private def load(): Unit =
        if rec.isVideo then
          view.setVisible(false)
          val l = new Label("▶ video"); l.setStyle("-fx-font-size: 16px;"); picture.getChildren.add(0, l)
        else reads.request(rec.path) {
          case scala.util.Success((img, d)) =>
            view.setImage(img.image); dims = d
            if settings.thumbnailCrop then
              val w = img.image.getWidth; val h = img.image.getHeight; val s = math.min(w, h)
              view.setViewport(new Rectangle2D((w - s) / 2, (h - s) / 2, s, s))
            setTip()
          case scala.util.Failure(e) =>
            name.setText("⚠ " + rec.fileName); Tooltip.install(box, new Tooltip("Cannot display image: " + Fx.message(e)))
        }
      def refreshBadge(): Unit =
        env.rankingOf(rec) match
          case Some(r) => badge.setText("★" * r); badge.setVisible(true)
          case None => badge.setText(""); badge.setVisible(false)
      private def setTip(): Unit =
        if settings.imgTooltips then
          val t = new Tooltip(BrowserInfo.thumbnailTooltip(rec, dims) + rec.meta.parameters.fold("")(p => "\n\n" + p.take(300)))
          t.setWrapText(true); t.setMaxWidth(420)
          Tooltip.install(box, t)
      def setSelected(on: Boolean): Unit =
        box.setStyle(if on then "-fx-border-color: -fx-accent, #f5a623; -fx-border-width: 2; -fx-background-color: rgba(245,166,35,0.18);" else "")
      def knownDims: Option[(Int, Int)] = dims
      def stop(): Unit = reads.invalidate()

    // ---- delete bar ---------------------------------------------------------------------------------------------
    private val deleteNum = new Spinner[Integer](1, 1000, 1); deleteNum.setEditable(true); deleteNum.setPrefWidth(80); deleteNum.setId(spec.id + "-delnum")
    private val deleteOff = new CheckBox("also delete off-screen images"); deleteOff.setId(spec.id + "-deloff")
    private val deleteBtn = W.button("Delete", "Delete (asks first; goes to the recycle bin unless disabled)  [Delete]")(deleteSelected())
    deleteBtn.setId(spec.id + "-delete")
    private val deleteBar = hideIfConfigured("Delete Bar", W.hbox(8, W.label("delete next"), deleteNum, deleteOff, deleteBtn))

    // ---- sort / filters -------------------------------------------------------------------------------------------
    private val sortBy = new ComboBox[String](FXCollections.observableArrayList(SortKeys.All*)); sortBy.setValue("date"); sortBy.setId(spec.id + "-sort")
    private val orderBtn: Button = W.button("▼", "Sort order (descending)") { tvm.flipSortOrder(); orderBtn.setText(if tvm.query.ascending then "▲" else "▼"); render() }
    orderBtn.setId(spec.id + "-order")
    private val sortBar = hideIfConfigured("Sort by", W.hbox(8, W.label("Sort by"), sortBy, orderBtn))
    private val fileKw = new TextField(); fileKw.setPromptText("Filename keyword search"); fileKw.setId(spec.id + "-filekw")
    private val fileBar = hideIfConfigured("Filename keyword search", W.hbox(8, W.label("Filename keyword search"), W.hgrow(fileKw)))
    private val exifKw = new TextField(); exifKw.setPromptText("EXIF keyword search"); exifKw.setId(spec.id + "-exifkw")
    private val negGroup = new ToggleGroup()
    private val negButtons = NegativeSearch.All.map { t => val b = new RadioButton(t); b.setToggleGroup(negGroup); b.setUserData(t); b.setId(s"${spec.id}-neg-$t"); b }
    negButtons.head.setSelected(true)
    private val regex = new CheckBox("regex - e.g. ^(?!.*Hires).*$"); regex.setId(spec.id + "-regex")
    private val exifBar = hideIfConfigured("EXIF keyword search", W.vbox(4, W.hbox(8, W.label("EXIF keyword search"), W.hgrow(exifKw)),
      W.hbox(8, W.label("Search negative prompt"), W.hbox(4, negButtons*), regex)))
    private val rfGroup = new ToggleGroup()
    private val rfButtons = RankingFilters.All.map { t => val b = new RadioButton(t); b.setToggleGroup(rfGroup); b.setUserData(t); b.setId(s"${spec.id}-rf-$t"); b }
    rfButtons.head.setSelected(true)
    private val rfMin = new Spinner[Integer](1, 5, 1); private val rfMax = new Spinner[Integer](1, 5, 5)
    Seq(rfMin, rfMax).foreach { s => s.setPrefWidth(70); s.setDisable(true) }
    rfMin.setId(spec.id + "-rfmin"); rfMax.setId(spec.id + "-rfmax")
    private val rankFilterBar = hideIfConfigured("Ranking Filter", W.vbox(4, W.hbox(6, (W.label("Ranking filter") +: rfButtons)*),
      W.hbox(6, W.label("Minimum ranking"), rfMin, W.label("Maximum ranking"), rfMax, W.label("Choose Min-max to activate these controls", "muted"))))
    private val aesMin = new TextField(); aesMin.setPrefColumnCount(6); aesMin.setId(spec.id + "-aesmin")
    private val aesMax = new TextField(); aesMax.setPrefColumnCount(6); aesMax.setId(spec.id + "-aesmax")
    private val aesBar = hideIfConfigured("Aesthetic Score", W.hbox(8, W.label("Minimum score"), aesMin, W.label("Maximum score"), aesMax))
    private val warning = W.label("", "hint"); warning.setWrapText(true); warning.setId(spec.id + "-warning")

    private def readQuery(): BrowserQuery =
      BrowserQuery(sortBy = sortBy.getValue, ascending = tvm.query.ascending, filenameKeyword = fileKw.getText, exifKeyword = exifKw.getText,
        negativePromptSearch = Option(negGroup.getSelectedToggle).map(_.getUserData.toString).getOrElse("No"), useRegex = regex.isSelected,
        rankingFilter = Option(rfGroup.getSelectedToggle).map(_.getUserData.toString).getOrElse("All"),
        rankingMin = Option(rfMin.getValue).fold(1)(_.intValue), rankingMax = Option(rfMax.getValue).fold(5)(_.intValue),
        aestheticMin = aesMin.getText, aestheticMax = aesMax.getText)
    private def queryChanged(): Unit =
      if !updating then
        Seq(rfMin, rfMax).foreach(s => s.setDisable(Option(rfGroup.getSelectedToggle).map(_.getUserData.toString) != Some("Min-max")))
        tvm.setQuery(readQuery()); render()
    sortBy.valueProperty.addListener((_, _, _) => queryChanged())
    Seq(fileKw, exifKw, aesMin, aesMax).foreach(_.textProperty.addListener((_, _, _) => queryChanged()))
    private val queryListener: javafx.beans.InvalidationListener = _ => queryChanged()
    Seq[javafx.beans.Observable](negGroup.selectedToggleProperty, rfGroup.selectedToggleProperty, regex.selectedProperty,
      rfMin.valueProperty, rfMax.valueProperty).foreach(_.addListener(queryListener))

    // ---- info panel ---------------------------------------------------------------------------------------------
    private val bigView = new ImageView(); bigView.setPreserveRatio(true); bigView.setFitWidth(400); bigView.setFitHeight(320); bigView.setId(spec.id + "-preview")
    private val bigReads = new ImageReadGate[ImageFiles.DisplayImage](ctx.services.imageScrambleSession, run => Fx.run(run()),
      (p, secret) => ImageFiles.readThumbnail(p, secret, 900))(using Fx.bg)
    private val formatted = new CheckBox("Formatted display"); formatted.setSelected(settings.infoFormat); formatted.setId(spec.id + "-formatted")
    private val infoText = new TextArea(); infoText.setEditable(false); infoText.setWrapText(true); infoText.setPrefRowCount(6); infoText.setId(spec.id + "-info")
    private val infoTable = new TableView[(String, String)](); infoTable.setPrefHeight(190); infoTable.setId(spec.id + "-info-table")
    private val keyCol = new TableColumn[(String, String), String]("Key"); keyCol.setCellValueFactory(c => new ReadOnlyStringWrapper(c.getValue._1)); keyCol.setPrefWidth(110)
    private val valCol = new TableColumn[(String, String), String]("Value"); valCol.setCellValueFactory(c => new ReadOnlyStringWrapper(c.getValue._2)); valCol.setPrefWidth(250)
    valCol.setCellFactory(_ => new TableCell[(String, String), String]:
      setWrapText(true)
      override def updateItem(item: String, empty: Boolean): Unit =
        super.updateItem(item, empty)
        setText(if empty then null else item)
        if !empty && item != null then { val t = new Text(item); t.setWrappingWidth(230); setPrefHeight(t.getLayoutBounds.getHeight + 8) })
    infoTable.getColumns.addAll(keyCol, valCol)
    infoTable.setPlaceholder(new Label("No generation info"))
    infoTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)
    private val additional = new TextArea(); additional.setEditable(false); additional.setPrefRowCount(3); additional.setId(spec.id + "-info-add")
    private val fileName = new TextField(); fileName.setPrefColumnCount(12); fileName.setEditable(false); fileName.setId(spec.id + "-filename")
    private val fileTime = W.label("", "muted"); fileTime.setId(spec.id + "-filetime")
    private val fileFacts = W.label("", "muted"); fileFacts.setId(spec.id + "-filefacts")
    private val openFolderBtn = W.button("📁", "Open the folder in the file manager") { tvm.selected.foreach(ImageActions.openFolder) }
    openFolderBtn.setId(spec.id + "-openfolder")
    private val openVideoBtn = W.button("Open video", "Play with the system player") { tvm.selected.foreach(openExternally) }
    openVideoBtn.setVisible(false); openVideoBtn.setManaged(false)
    formatted.selectedProperty.addListener((_, _, _) => renderInfo())

    // ---- send-to buttons -------------------------------------------------------------------------------------------
    private val favBtn = W.button("", "Favorites (F)")(onFavorite()); favBtn.setId(spec.id + "-favorite")
    private val toTxt = W.button("Send to txt2img", "Restore the generation parameters into txt2img")(sendRestore(Mode.Txt2Img)); toTxt.setId(spec.id + "-to-txt2img")
    private val toImg = W.button("Send to img2img", "Use as the img2img input image with its parameters")(sendImage(Mode.Img2Img)); toImg.setId(spec.id + "-to-img2img")
    private val toInp = W.button("Send to inpaint", "Use as the inpaint input image with its parameters")(sendImage(Mode.Inpaint)); toInp.setId(spec.id + "-to-inpaint")
    private val toExtras = W.button("Send to extras", "Use as the Extras input image")(sendExtras()); toExtras.setId(spec.id + "-to-extras")
    private val cnNumber = new ComboBox[String](FXCollections.observableArrayList("0", "1", "2")); cnNumber.setValue("0"); cnNumber.setId(spec.id + "-cn-number")
    W.tip(cnNumber, "ControlNet unit number (0 = first unit)")
    private val cnTxt = W.button("Send to txt2img ControlNet", "Set the image of the chosen ControlNet unit in txt2img")(sendControlNet(Mode.Txt2Img)); cnTxt.setId(spec.id + "-cn-txt2img")
    private val cnImg = W.button("Send to img2img ControlNet", "Set the image of the chosen ControlNet unit in img2img")(sendControlNet(Mode.Img2Img)); cnImg.setId(spec.id + "-cn-img2img")
    private val sendBar = hideIfConfigured("Send to buttons", W.vbox(6,
      W.hbox(6, (if spec.isFavorites then Seq.empty[Node] else Seq[Node](favBtn)) ++ Seq[Node](toTxt, toImg, toInp, toExtras)*),
      W.hbox(6, cnTxt, cnImg, W.label("ControlNet number"), cnNumber)))

    private val toDirPath = new TextField(); toDirPath.setPromptText("Directory path"); toDirPath.setId(spec.id + "-todir-path")
    private val toDirSaved = new ComboBox[String](); toDirSaved.setPromptText("Saved directories"); toDirSaved.setPrefWidth(300); toDirSaved.setId(spec.id + "-todir-saved")
    toDirSaved.setOnShowing(_ => toDirSaved.getItems.setAll(env.store.savedDirs.map(_.path).asJava))
    toDirSaved.valueProperty.addListener((_, _, v) => if v != null then toDirPath.setText(v))
    private val toDirBtn = W.button("", "Copy or move the image to this directory")(onToDirectory()); toDirBtn.setId(spec.id + "-todir")
    private val toDirBar = hideIfConfigured("Copy to directory", W.vbox(6, W.hbox(8, W.label("Directory path"), W.hgrow(toDirPath)), W.hbox(8, toDirSaved, toDirBtn)))

    private def verb: String = if env.settings.copyInsteadOfMove then "Copy" else "Move"
    favBtn.setText(s"$verb to favorites"); toDirBtn.setText(s"$verb to directory")

    // ---- layout ------------------------------------------------------------------------------------------------------
    private val left = new VBox(6, sourceBar, controlsPanel, rankingBar, gridScroll, deleteBar, sortBar, fileBar, exifBar, rankFilterBar, aesBar, warning)
    left.setPadding(new Insets(8)); VBox.setVgrow(gridScroll, Priority.ALWAYS)
    private val infoBox = hideIfConfigured("Generation Info", new VBox(4, formatted, infoText, infoTable))
    private val addBox = hideIfConfigured("Additional Generation Info", new VBox(4, W.label("Additional Generation Info", "pane-title"), additional))
    private val fileNameBox = hideIfConfigured("File Name", W.hbox(6, W.label("File Name"), W.hgrow(fileName)))
    private val fileTimeBox = hideIfConfigured("File Time", W.hbox(6, W.label("File Time"), fileTime))
    private val openBox = hideIfConfigured("Open Folder", W.hbox(6, openFolderBtn, openVideoBtn))
    private val rightBox = new VBox(6, bigView, infoBox, addBox, fileNameBox, fileTimeBox, fileFacts, openBox, sendBar, toDirBar)
    rightBox.setPadding(new Insets(8))
    private val rightScroll = new ScrollPane(rightBox); rightScroll.setFitToWidth(true)
    rightScroll.setPrefWidth(440); rightScroll.setMinWidth(380); rightScroll.setMaxWidth(440)
    private val leftScroll = new ScrollPane(left); leftScroll.setFitToWidth(true); leftScroll.setMinWidth(300)
    val node: Node =
      val h = new HBox(leftScroll, rightScroll)
      HBox.setHgrow(leftScroll, Priority.ALWAYS)
      h.setId(spec.id + "-root")
      h
    // keys and swipe
    node.addEventFilter(KeyEvent.KEY_PRESSED, (e: KeyEvent) => handleKey(e))
    grid.setOnSwipeLeft(_ => if env.settings.swipe then { tvm.selectNeighbor(1); render() })
    grid.setOnSwipeRight(_ => if env.settings.swipe then { tvm.selectNeighbor(-1); render() })
    private def handleKey(e: KeyEvent): Unit =
      val typing = e.getTarget.isInstanceOf[javafx.scene.control.TextInputControl] || e.getTarget.isInstanceOf[ComboBox[?]]
      if !typing then
        BrowserKeys.resolve(env.settings, keyName(e.getCode), e.isControlDown, e.isShiftDown, e.isAltDown).foreach { a =>
          e.consume()
          a match
            case BrowserKeyAction.Rank(v) => if tvm.selected.isDefined then { val o = tvm.setRanking(v); say(o.message, o.ok); render() }
            case BrowserKeyAction.Favorite => if !spec.isFavorites then onFavorite()
            case BrowserKeyAction.Refresh => reload()
            case BrowserKeyAction.Delete => deleteSelected()
            case BrowserKeyAction.PrevPage => go(() => tvm.prev())
            case BrowserKeyAction.NextPage => go(() => tvm.next())
        }

    // ---- behaviour ------------------------------------------------------------------------------------------------------
    def activate(): Unit = reload()
    def dispose(): Unit = { scanTicket += 1; tiles.values.foreach(_.stop()); bigReads.invalidate() }
    private def go(move: () => Unit): Unit = { move(); renderGrid(); renderPageBar() }

    /** Rescans in the background and re-renders; keeps page and selection when still valid. */
    def reload(): Unit =
      scanTicket += 1
      val ticket = scanTicket
      if settings.showProgress then warning.setText("Loading…")
      Fx.async(tvm.scan()) { recs =>
        if ticket == scanTicket && !disposed then { tvm.accept(recs); loaded = true; render() }
      } { e => if ticket == scanTicket then warning.setText("Scan failed: " + Fx.message(e)) }

    private def render(): Unit =
      warning.setText(tvm.warnings.mkString("; "))
      renderPageBar(); renderGrid(); renderInfo()
      status.setText(s"${spec.name}: ${tvm.filtered.size} image${if tvm.filtered.size == 1 then "" else "s"}" +
        (if tvm.filtered.size != tvm.totalScanned then s" of ${tvm.totalScanned}" else ""))

    private def renderPageBar(): Unit =
      pageField.setText(tvm.page.toString)
      pageLabel.setText(tvm.pageLabel)
      val p = tvm.page; val n = tvm.pager.pageCount
      firstBtn.setDisable(p <= 1); prevBtn.setDisable(p <= 1); nextBtn.setDisable(p >= n); endBtn.setDisable(p >= n)

    private def renderGrid(): Unit =
      tiles.values.foreach(_.stop()); tiles.clear()
      val items = tvm.pageItems
      items.foreach(r => tiles.put(r.path, new Tile(r)))
      grid.getChildren.setAll(tiles.values.map(_.box).toSeq*)
      if items.isEmpty then grid.getChildren.setAll(empty)
      renderSelection()

    private def renderSelection(): Unit =
      tiles.foreach((p, t) => t.setSelected(tvm.selected.contains(p)))
      renderInfo()

    private def renderInfo(): Unit =
      val rec = tvm.selectedRecord
      val has = rec.isDefined
      Seq[Node](favBtn, toTxt, toImg, toInp, toExtras, cnTxt, cnImg, cnNumber, toDirBtn, toDirPath, toDirSaved, deleteBtn).foreach(_.setDisable(!has))
      rankButtons.foreach(_.setDisable(!has))
      updating = true
      rankToggle.selectToggle(null)
      rec.flatMap(env.rankingOf) match
        case Some(r) => rankingCurrent.setText(r.toString); rankButtons.find(_.getText == r.toString).foreach(_.setSelected(true))
        case None => rankingCurrent.setText("None"); if has then rankButtons.last.setSelected(true)
      updating = false
      tiles.get(rec.fold(Path.of(""))(_.path)).foreach(_.refreshBadge())
      rec match
        case None =>
          bigReads.invalidate(); bigView.setImage(null)
          infoText.clear(); infoTable.getItems.clear(); additional.clear(); fileName.clear(); fileTime.setText(""); fileFacts.setText("")
          openVideoBtn.setVisible(false); openVideoBtn.setManaged(false)
        case Some(r) =>
          fileName.setText(r.path.toString); fileTime.setText(BrowserInfo.fileTime(r))
          val facts = BrowserInfo.sizeText(r.size) + tiles.get(r.path).flatMap(_.knownDims).fold("")(d => s"  ·  ${d._1} x ${d._2}")
          fileFacts.setText(facts)
          openVideoBtn.setVisible(r.isVideo); openVideoBtn.setManaged(r.isVideo)
          if r.isVideo then { bigReads.invalidate(); bigView.setImage(null) }
          else bigReads.request(r.path) {
            case scala.util.Success(d) if tvm.selected.contains(r.path) =>
              bigView.setImage(d.image)
              if tiles.get(r.path).forall(_.knownDims.isEmpty) then fileFacts.setText(facts + f"  ·  ${d.image.getWidth}%.0f x ${d.image.getHeight}%.0f (preview)")
            case _ => ()
          }
          val text = r.meta.parameters.getOrElse("")
          infoText.setText(text.ifEmpty("(no generation info)"))
          val rows = BrowserInfo.formattedRows(r.meta, env.settings.infoOrder)
          infoTable.getItems.setAll(rows.asJava)
          additional.setText(if env.settings.infoAdd || true then BrowserInfo.additionalRows(r.meta, env.settings.infoOrder).map((k, v) => s"$k: $v").mkString("\n") else "")
      infoText.setVisible(!formatted.isSelected); infoText.setManaged(!formatted.isSelected)
      infoTable.setVisible(formatted.isSelected); infoTable.setManaged(formatted.isSelected)
      addBox.setVisible(env.settings.infoAdd && !env.settings.hidden("Additional Generation Info")); addBox.setManaged(addBox.isVisible)

    private def openExternally(p: Path): Unit =
      try java.awt.Desktop.getDesktop.open(p.toFile)
      catch case NonFatal(e) => say("Cannot open: " + e.getMessage, ok = false)

    // ---- actions ---------------------------------------------------------------------------------------------------------
    private def finish(out: BrowserOutcome): Unit =
      say(out.message, out.ok)
      if out.ok && env.settings.deleteMessage then println(s"[image-browser] ${out.message}")
      render()

    private def onFavorite(): Unit =
      if !spec.isFavorites && tvm.selected.isDefined then { val out = tvm.favorite(); say(out.message, out.ok); render() }

    private def onToDirectory(): Unit =
      if tvm.selected.isDefined then { val out = tvm.toDirectory(toDirPath.getText); say(out.message, out.ok); render() }

    private def deleteSelected(): Unit =
      W.commitSpinner(deleteNum)
      val plan = tvm.deletePlan(deleteNum.getValue.intValue, deleteOff.isSelected)
      if plan.nonEmpty then
        val how = if env.settings.deleteRecycle then "They are moved to the recycle bin." else "They are removed from disk permanently."
        val a = new Alert(Alert.AlertType.CONFIRMATION,
          s"Delete ${plan.size} image${if plan.size == 1 then "" else "s"}?\n${plan.take(5).map(_.fileName).mkString(", ")}${if plan.size > 5 then ", …" else ""}\n$how" +
            (if env.settings.txtFiles then "\nMatching .txt files are included." else ""), ButtonType.CANCEL, ButtonType.OK)
        a.initOwner(ctx.stage); a.setHeaderText(null); a.setTitle("Delete images"); a.getDialogPane.setId("image-browser-delete-confirm")
        val r = a.showAndWait()
        if r.isPresent && r.get == ButtonType.OK then
          finish(tvm.deleteConfirmed(plan))

    private def sendRestore(mode: Mode): Unit =
      tvm.selectedRecord.foreach { r =>
        r.meta.parameters match
          case None => say("This image has no generation parameters", ok = false)
          case Some(t) => ctx.applyFastloadInfotext(mode, t) match
            case Left(m) => say(m, ok = false)
            case Right(_) => say(s"Sent parameters to ${mode.label}")
      }
    private def sendImage(mode: Mode): Unit =
      tvm.selectedRecord.foreach { r => if r.isVideo then say("Videos cannot be used as an input image", ok = false) else
        ctx.sendToTab(mode, r.path, r.meta.parameters); say(s"Sent to ${mode.label}") }
    private def sendExtras(): Unit =
      tvm.selectedRecord.foreach { r => if r.isVideo then say("Videos cannot be sent to Extras", ok = false) else
        ctx.sendToExtras(r.path); say("Sent to Extras") }
    private def sendControlNet(mode: Mode): Unit =
      tvm.selectedRecord.foreach { r =>
        val index = cnNumber.getValue.toIntOption.getOrElse(0)
        ctx.fastloadTarget(mode, index) match
          case None => say(s"ControlNet unit $index is unavailable in ${mode.label}", ok = false)
          case Some(existing) =>
            ctx.applyFastloadTarget(mode, index, existing, existing.copy(image = Some(r.path.toString))) match
              case Left(m) => say(m, ok = false)
              case Right(_) => say(s"Set ${mode.label} ControlNet unit $index image")
      }

    // ---- test hooks (the real check drives the controls through these ids; no behaviour of its own) -----------------------
    def viewModel: ImageBrowserTabVM = tvm

  // ===========================================================================================================
  final class MaintenancePage(val spec: TabSpec) extends Page:
    private val wait = W.label("Status:", "muted"); wait.setId("ib-maintenance-status")
    private val last = new TextField(); last.setEditable(false); last.setId("ib-maintenance-last")
    private val pathRecorder = new CheckBox("path_recorder"); pathRecorder.setMnemonicParsing(false); pathRecorder.setSelected(true); pathRecorder.setId("ib-maintenance-recorder")
    private val exifData = new CheckBox("exif_data"); exifData.setMnemonicParsing(false); exifData.setSelected(true); exifData.setId("ib-maintenance-exif")
    private val rankingBox = new CheckBox("ranking"); rankingBox.setSelected(true); rankingBox.setId("ib-maintenance-ranking")
    private val fromField = new TextField(); fromField.setPromptText("From (full path)"); fromField.setId("ib-maintenance-from")
    private val toField = new TextField(); toField.setPromptText("to (full path)"); toField.setId("ib-maintenance-to")
    private var busy = false
    private val buttons = mutable.Buffer.empty[Button]
    private def op(text: String, tip: String, id: String)(f: => String): Button =
      val b = W.button(text, tip) {
        if !busy then
          busy = true; buttons.foreach(_.setDisable(true)); wait.setText("Status: working…")
          Fx.async(f) { m => busy = false; buttons.foreach(_.setDisable(false)); wait.setText("Status: done"); last.setText(m) } { e =>
            busy = false; buttons.foreach(_.setDisable(false)); wait.setText("Status: failed"); last.setText("Failed: " + Fx.message(e)) }
      }
      b.setId(id); buttons += b; b
    private val m = vm.maintenance
    val node: Node =
      val warn = W.label("Caution: You should only use these options if you know what you are doing.", "hint")
      val box = new VBox(10, warn, wait, W.hbox(8, W.label("Last message"), W.hgrow(last)),
        W.hbox(8, op("Rebuild exif cache", "Forget the cached metadata and read every file again", "ib-maintenance-rebuild")(m.rebuildCache()),
          op("Delete 0-entries from exif cache", "Drop cached entries of files without generation info", "ib-maintenance-zero")(m.deleteZeroEntries())),
        W.hbox(8, op("Update directory names in database", "Rewrite stored folder names after you renamed a folder", "ib-maintenance-update")(
            m.updateDirectoryNames(fromField.getText, toField.getText, pathRecorder.isSelected, exifData.isSelected, rankingBox.isSelected)),
          pathRecorder, exifData, rankingBox),
        W.hbox(8, W.label("From"), W.hgrow(fromField), W.label("to"), W.hgrow(toField)),
        W.hbox(8, op("Recreate hash for existing files", "Recompute the content hash of every ranked file", "ib-maintenance-hash")(m.recreateHash())),
        W.hbox(8, op("Reapply ranking after moving files", "Rankings follow files with the same content hash", "ib-maintenance-reapply")(m.reapplyRanking())),
        W.hbox(8, op("Rebuild ranking from exif info", "Read rankings that are stored inside the images", "ib-maintenance-rebuild-ranking")(m.rebuildRanking())))
      box.setPadding(new Insets(12))
      box
    def activate(): Unit = status.setText("")
    def dispose(): Unit = ()

  extension (s: String) private def ifEmpty(other: String): String = if s.isEmpty then other else s

  build()
