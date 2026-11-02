package sdfx.ui.view

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.{Button, ButtonType, CheckBox, ComboBox, Dialog, Label, ListCell, ListView, Spinner, TextArea, TextField}
import javafx.scene.image.{Image, ImageView}
import javafx.scene.input.{InputMethodEvent, KeyCode, KeyEvent}
import javafx.scene.layout.{GridPane, HBox, StackPane, VBox}
import javafx.stage.{FileChooser, Popup}
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.vm.{CompletionData, CompletionEngine, CompletionResult, CompletionTag, NetFile, Suggestion, SuggestionKind, TagCategoryColors, TagCompletion, TagWiki}
import java.nio.file.{Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** One dictionary per window; file loading never blocks the FX thread. Tags, translations, the extra file and chants
  * are read together; wildcard names are rescanned in the background; LoRA/embedding names come from the model catalog. */
final class TagDictionary(ctx: UiContext):
  private var loaded = CompletionData()
  private var loadedKey: Option[Key] = None
  private var version = 0L
  private var wildcards = Vector.empty[String]
  private var wildcardsAt: Option[Long] = None
  private var scanning = false
  private var wildcardErrorKey: Option[String] = None
  private var colorsKey: Option[String] = None
  private var colors: TagCategoryColors.Palette = Map.empty
  private final case class Key(tags: Option[String], translation: Option[String], legacy: Boolean, extra: Option[String], chants: Option[String])

  def tags: Vector[CompletionTag] = loaded.tags
  def config: TagCompleteSettings = ctx.services.settings.tagComplete
  def wikiUri(item: Suggestion): Option[java.net.URI] =
    TagWiki.uri(config.showWikiLinks, ctx.services.settings.tagAutocompleteCsv, item.kind, item.wikiTag.getOrElse(""), item.category)
  def openWiki(uri: java.net.URI): Unit =
    try java.awt.Desktop.getDesktop.browse(uri)
    catch case NonFatal(e) => ctx.error(s"Cannot open tag wiki: ${sdfx.ui.Fx.message(e)}")

  private def key: Key =
    val c = config
    Key(ctx.services.settings.tagAutocompleteCsv, c.translationCsv, c.translationLegacy, c.extraCsv, c.chantsJson)

  /** Wildcard folders: the app's own, plus the Dynamic Prompts plugin's extra folders. */
  def wildcardDirs: Seq[Path] =
    val root = ctx.services.home.root
    val extra = ctx.services.settings.pluginValues.get("dynamic_prompts").flatMap(_.get("wildcard_dirs")).getOrElse("")
    (Seq(root.resolve("wildcards"), root.resolve("models").resolve("wildcards")) ++
      extra.split("[;\\r\\n]+").map(_.trim).filter(_.nonEmpty).map(Paths.get(_))).distinct

  private def stem(file: String): String =
    val base = file.replace('\\', '/').split('/').last
    val i = base.lastIndexOf('.')
    if i > 0 then base.substring(0, i) else base

  /** Current data; the catalog lists are read each time so a refreshed model list is seen immediately. */
  def data: CompletionData =
    val snap = ctx.catalog.snapshot.get
    loaded.copy(wildcards = wildcards,
      loras = snap.loras.map(f => NetFile(stem(f.name), Paths.get(f.path))).distinctBy(_.name).toVector,
      embeddings = snap.embeddings.map(f => NetFile(stem(f.name), Paths.get(f.path))).distinctBy(_.name).toVector)

  def complete(text: String, caret: Int, selStart: Int, selEnd: Int): Option[CompletionResult] =
    CompletionEngine.complete(data, config, text, caret, selStart, selEnd)

  def categoryColor(item: Suggestion, light: Boolean): Option[javafx.scene.paint.Color] =
    val raw = config.categoryColorMap
    if !colorsKey.contains(raw) then
      colors = scala.util.Try(TagCategoryColors.parse(raw)).recover { case _ =>
        ctx.error("Saved category colours are invalid; using default markers.")
        Map.empty
      }.get
      colorsKey = Some(raw)
    val path = item.kind match
      case SuggestionKind.Tag | SuggestionKind.Chant => ctx.services.settings.tagAutocompleteCsv
      case SuggestionKind.Extra => config.extraCsv
      case _ => None
    item.category.flatMap(c => TagCategoryColors.color(colors, TagCategoryColors.stem(path), c, light))

  def wildcardLines(name: String)(done: Either[Throwable, Vector[String]] => Unit): Unit =
    try
      val roots = wildcardDirs
      sdfx.ui.Fx.async(sdfx.ui.vm.WildcardLines.read(roots, name))(v => done(Right(v)))(e => done(Left(e)))
    catch case NonFatal(e) => done(Left(e))
  def wildcardContext: Any = (ctx.services.home.root.toString, config,
    ctx.services.settings.pluginValues.get("dynamic_prompts").flatMap(_.get("wildcard_dirs")))
  def wildcardError(e: Throwable): Unit = ctx.error(s"Cannot read wildcard: ${e.getMessage}")

  private def scanWildcards(): Unit =
    if !scanning then
      val dirs = wildcardDirs
      scanning = true
      sdfx.ui.Fx.async(new sdfx.plugins.prompt.DynamicPrompt.Wildcards(dirs).names)(names =>
        wildcards = names; wildcardsAt = Some(System.nanoTime()); scanning = false
      )(_ => { wildcardsAt = Some(System.nanoTime()); scanning = false })

  def ensureLoaded(): Unit =
    try
      ensureLoadedChecked()
      wildcardErrorKey = None
    catch case NonFatal(e) =>
      val message = sdfx.ui.Fx.message(e)
      if !wildcardErrorKey.contains(message) then ctx.error(s"Cannot load wildcard folders: $message")
      wildcardErrorKey = Some(message)

  private def ensureLoadedChecked(): Unit =
    val scanFailure = scala.util.Try {
      if wildcardsAt.forall(t => System.nanoTime() - t > 5000000000L) then scanWildcards()
    }.failed.toOption
    val wanted = key
    if !loadedKey.contains(wanted) then
      loadedKey = Some(wanted)
      version += 1
      val ticket = version
      loaded = CompletionData()
      sdfx.ui.Fx.async {
        def part[A](path: Option[String], what: String)(read: Path => A): Option[A] =
          path.map(p => try read(Paths.get(p)) catch case e: Exception => throw new IllegalArgumentException(s"$what: ${e.getMessage}", e))
        CompletionData(
          tags = part(wanted.tags, "Tag CSV")(TagCompletion.load).getOrElse(Vector.empty),
          extra = part(wanted.extra, "Extra tag CSV")(CompletionEngine.loadExtra).getOrElse(Vector.empty),
          translations = part(wanted.translation, "Translation CSV")(CompletionEngine.loadTranslations(_, wanted.legacy)).getOrElse(Map.empty),
          chants = part(wanted.chants, "Chants file")(CompletionEngine.loadChants).getOrElse(Vector.empty))
      }(result => if ticket == version then loaded = result)(
        e => if ticket == version then ctx.error(s"Cannot load tag completion data: ${e.getMessage}"))
    scanFailure.foreach(throw _)

  def reload(): Unit =
    loadedKey = None
    wildcardsAt = None
    ensureLoadedChecked()

  /** Persist the form's values and reload; used by the dialog's Save button. */
  def apply(csv: Option[String], cfg: TagCompleteSettings): Either[String, Option[String]] =
    ctx.services.updateSettingsChecked(_.copy(tagAutocompleteCsv = csv, tagComplete = cfg)).map { _ =>
      try { reload(); None }
      catch case NonFatal(e) => Some(s"Saved tag settings, but reload failed: ${sdfx.ui.Fx.message(e)}")
    }

  def configure(): Unit =
    val dialog = new Dialog[ButtonType]()
    dialog.initOwner(ctx.stage)
    dialog.setTitle("Tag autocomplete")
    val save = new ButtonType("Save")
    val reloadButton = new ButtonType("Reload")
    val disable = new ButtonType("Disable")
    dialog.getDialogPane.getButtonTypes.addAll(save, reloadButton, disable, ButtonType.CANCEL)
    val pane = new TagSettingsPane(ctx.services.settings.tagAutocompleteCsv, config, ctx.stage)
    dialog.getDialogPane.setContent(pane.node)
    dialog.getDialogPane.getStylesheets.setAll(ctx.stage.getScene.getStylesheets)
    dialog.getDialogPane.lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, (event: javafx.event.ActionEvent) =>
      scala.util.Try(pane.read()) match
        case scala.util.Failure(_) => pane.showError("Invalid category colours. Check the JSON and colour names."); event.consume()
        case scala.util.Success((csv, cfg)) =>
          apply(csv, cfg) match
            case Right(warning) => pane.showError(""); warning.foreach(ctx.error)
            case Left(error) => pane.showError(s"Could not save tag settings: $error"); event.consume())
    dialog.getDialogPane.lookupButton(disable).addEventFilter(javafx.event.ActionEvent.ACTION, (event: javafx.event.ActionEvent) =>
      ctx.services.updateSettingsChecked(_.copy(tagAutocompleteCsv = None)) match
        case Right(_) =>
          try ensureLoadedChecked()
          catch case NonFatal(e) => ctx.error(s"Saved tag settings, but reload failed: ${sdfx.ui.Fx.message(e)}")
        case Left(error) => pane.showError(s"Could not save tag settings: $error"); event.consume())
    val result = dialog.showAndWait().orElse(ButtonType.CANCEL)
    if result == reloadButton then
      try reload()
      catch case NonFatal(e) => ctx.error(s"Cannot reload tag suggestions: ${sdfx.ui.Fx.message(e)}")

/** Settings form: local files (typed or browsed; empty = unused) and the completion options. */
final class TagSettingsPane(tagCsv: Option[String], cfg: TagCompleteSettings, owner: javafx.stage.Window):
  private def pathField(id: String, value: Option[String]): TextField =
    val f = new TextField(value.getOrElse(""))
    f.setId(id); f.setPrefColumnCount(34)
    f
  private val tags = pathField("tac-tags", tagCsv)
  private val translation = pathField("tac-translation", cfg.translationCsv)
  private val extra = pathField("tac-extra", cfg.extraCsv)
  private val chantsField = pathField("tac-chants-file", cfg.chantsJson)
  private def box(id: String, label: String, on: Boolean): CheckBox =
    val c = new CheckBox(label); c.setId(id); c.setSelected(on); c.setMnemonicParsing(false); c
  private val legacy = box("tac-legacy", "Translation file uses the old 3-column format", cfg.translationLegacy)
  private val extraAfter = box("tac-extra-after", "Show extra file tags after the normal tags", cfg.extraAfter)
  private val underscores = box("tac-underscores", "Replace _ with space on insertion", cfg.replaceUnderscores)
  private val escape = box("tac-escape", "Escape ( ) in inserted tags", cfg.escapeParens)
  private val comma = box("tac-comma", "Append \", \" after inserting", cfg.appendComma)
  private val alias = box("tac-alias", "Search by alias", cfg.searchByAlias)
  private val onlyAlias = box("tac-only-alias", "Show only the alias (not alias ➝ tag)", cfg.onlyShowAlias)
  private val wildcards = box("tac-wildcards", "Complete __wildcards__", cfg.wildcards)
  private val networks = box("tac-networks", "Complete <extra networks> (LoRA, embeddings)", cfg.extraNetworks)
  private val chantsBox = box("tac-chants", "Complete chants (<c:...>)", cfg.chants)
  private val trigger = box("tac-trigger", "Insert LoRA trigger words from the model's .json file", cfg.loraTriggerWords)
  private val preview = box("tac-preview", "Show extra network preview images", cfg.extraNetworkPreview)
  private val wiki = box("tac-wiki", "Show external tag wiki links (F1; may contain adult content)", cfg.showWikiLinks)
  private val max = new Spinner[Integer](1, 50, cfg.maxResults)
  max.setId("tac-max"); max.setEditable(true)
  private val delay = new Spinner[Integer](0, 5000, cfg.completionDelayMs.max(0).min(5000), 50)
  delay.setId("tac-delay"); delay.setEditable(true)
  private val pathModes = Vector("first-difference", "next-folder", "always-full")
  private val pathMode = new ComboBox[String]()
  pathMode.setId("tac-wildcard-path-mode")
  pathMode.getItems.addAll("To first difference", "To next folder", "Always fully")
  private val currentPathMode = pathModes.indexOf(cfg.wildcardPathMode)
  if currentPathMode < 0 then pathMode.getItems.add(s"Unsupported saved mode: ${cfg.wildcardPathMode}")
  pathMode.getSelectionModel.select(if currentPathMode < 0 then pathModes.size else currentPathMode)
  private val colors = new TextArea(cfg.categoryColorMap)
  colors.setId("tac-colormap"); colors.setPrefRowCount(3); colors.setWrapText(true)
  colors.setPromptText("{\"danbooru\": {\"4\": [\"lightgreen\", \"darkgreen\"]}}")
  private val colorError = new Label()
  colorError.setWrapText(true); colorError.getStyleClass.add("error")
  def showError(message: String): Unit = colorError.setText(message)

  private def row(label: String, field: TextField, filter: FileChooser.ExtensionFilter): HBox =
    val browse = new javafx.scene.control.Button("Browse…")
    browse.setOnAction(_ =>
      val chooser = new FileChooser()
      chooser.setTitle(label)
      chooser.getExtensionFilters.add(filter)
      Option(chooser.showOpenDialog(owner)).foreach(f => field.setText(f.getAbsolutePath)))
    val name = new Label(label)
    name.setMinWidth(130)
    new HBox(6, name, field, browse)

  val node: VBox =
    val csv = new FileChooser.ExtensionFilter("CSV", "*.csv")
    val options = new GridPane()
    options.setHgap(16); options.setVgap(4)
    Seq(underscores, escape, comma, alias, onlyAlias, wildcards, networks, chantsBox, trigger, preview, legacy, extraAfter, wiki)
      .zipWithIndex.foreach((c, i) => options.add(c, i % 2, i / 2))
    val maxRow = new HBox(6, new Label("Maximum results"), max)
    val help = new Label("Up/Down: select · Enter/Tab: insert · Escape: close · __ wildcards · < extra networks")
    val missingHelp = new Label("A listed source uses only its listed categories; omitted categories have no coloured marker.")
    missingHelp.setWrapText(true)
    val v = new VBox(8,
      new Label("Local files only; nothing is downloaded. Leave a path empty to turn that source off."),
      row("Tag CSV", tags, csv), row("Translation CSV", translation, csv), row("Extra tag CSV", extra, csv),
      row("Chants JSON", chantsField, new FileChooser.ExtensionFilter("JSON", "*.json")),
      options, maxRow, new HBox(6, new Label("Completion delay (ms)"), delay), new HBox(6, new Label("Wildcard Tab completion"), pathMode), new Label("Category colours JSON (source: category: [dark, light])"),
      missingHelp, colors, colorError, help)
    v.setPadding(new Insets(4))
    v

  private def opt(f: TextField): Option[String] = Option(f.getText).map(_.trim).filter(_.nonEmpty)
  def read(): (Option[String], TagCompleteSettings) =
    TagCategoryColors.parse(colors.getText)
    val n = try max.getEditor.getText.trim.toInt.max(1).min(50) catch case _: NumberFormatException => cfg.maxResults
    val ms = try delay.getEditor.getText.trim.toInt.max(0).min(5000) catch case _: NumberFormatException => cfg.completionDelayMs
    (opt(tags), TagCompleteSettings(
      translationCsv = opt(translation), translationLegacy = legacy.isSelected, extraCsv = opt(extra), extraAfter = extraAfter.isSelected,
      chantsJson = opt(chantsField), maxResults = n, replaceUnderscores = underscores.isSelected, escapeParens = escape.isSelected,
      appendComma = comma.isSelected, searchByAlias = alias.isSelected, onlyShowAlias = onlyAlias.isSelected,
      wildcards = wildcards.isSelected, extraNetworks = networks.isSelected, chants = chantsBox.isSelected,
      loraTriggerWords = trigger.isSelected, extraNetworkPreview = preview.isSelected, categoryColorMap = colors.getText,
      wildcardPathMode = pathModes.lift(pathMode.getSelectionModel.getSelectedIndex).getOrElse(cfg.wildcardPathMode), completionDelayMs = ms,
      showWikiLinks = wiki.isSelected))

/** What the popup needs from its owner. */
trait CompletionSource:
  def ensureLoaded(): Unit
  def complete(text: String, caret: Int, selStart: Int, selEnd: Int): Option[CompletionResult]
  def previews: Boolean
  def categoryColor(item: Suggestion, light: Boolean): Option[javafx.scene.paint.Color] =
    item.category.flatMap(c => TagCategoryColors.color(Map.empty, None, c, light))
  def wildcardLines(name: String)(done: Either[Throwable, Vector[String]] => Unit): Unit = done(Right(Vector.empty))
  def wildcardContext: Any = ()
  def wildcardError(error: Throwable): Unit = ()
  def maxResults: Int = 12
  def wildcardPathMode: String = "first-difference"
  def completionDelayMs: Int = 0
  def wikiUri(item: Suggestion): Option[java.net.URI] = None
  def openWiki(uri: java.net.URI): Unit = ()

/** Popup does not take focus, so native text editing and IME stay in the TextArea. */
final class TagAutocomplete(area: TextArea, source: CompletionSource):
  def this(area: TextArea, tags: () => Vector[CompletionTag], load: () => Unit) =
    this(area, new CompletionSource:
      def ensureLoaded(): Unit = load()
      def complete(text: String, caret: Int, selStart: Int, selEnd: Int): Option[CompletionResult] =
        CompletionEngine.complete(CompletionData(tags = tags()), TagCompleteSettings(), text, caret, selStart, selEnd)
      def previews: Boolean = false)
  def this(area: TextArea, dictionary: TagDictionary) =
    this(area, new CompletionSource:
      def ensureLoaded(): Unit = dictionary.ensureLoaded()
      def complete(text: String, caret: Int, selStart: Int, selEnd: Int): Option[CompletionResult] = dictionary.complete(text, caret, selStart, selEnd)
      def previews: Boolean = dictionary.config.extraNetworkPreview
      override def categoryColor(item: Suggestion, light: Boolean): Option[javafx.scene.paint.Color] = dictionary.categoryColor(item, light)
      override def wildcardLines(name: String)(done: Either[Throwable, Vector[String]] => Unit): Unit = dictionary.wildcardLines(name)(done)
      override def wildcardContext: Any = dictionary.wildcardContext
      override def wildcardError(error: Throwable): Unit = dictionary.wildcardError(error)
      override def maxResults: Int = dictionary.config.maxResults
      override def wildcardPathMode: String = dictionary.config.wildcardPathMode
      override def completionDelayMs: Int = dictionary.config.completionDelayMs
      override def wikiUri(item: Suggestion): Option[java.net.URI] = dictionary.wikiUri(item)
      override def openWiki(uri: java.net.URI): Unit = dictionary.openWiki(uri))
  private val popup = new Popup()
  private val list = new ListView[Suggestion]()
  private val linePrevious = new Button("Previous")
  private val lineNext = new Button("Next")
  private val lineStatus = new Label()
  private val lineNavigation = new HBox(6, linePrevious, lineStatus, lineNext)
  linePrevious.setId("tac-lines-previous"); lineNext.setId("tac-lines-next"); lineStatus.setId("tac-lines-status")
  linePrevious.setFocusTraversable(false); lineNext.setFocusTraversable(false)
  lineNavigation.setVisible(false); lineNavigation.setManaged(false)
  private val previewPopup = new Popup()
  private val previewView = new ImageView()
  popup.setAutoHide(true)
  popup.setHideOnEscape(false)
  list.setFocusTraversable(false)
  list.setPrefWidth(420)
  list.setCellFactory(_ => new ListCell[Suggestion]():
    setOnMousePressed(e =>
      def isWikiButton(node: javafx.scene.Node): Boolean =
        node != null && (node.getId == "tac-wiki-link" || isWikiButton(node.getParent))
      val onLink = e.getTarget match
        case node: javafx.scene.Node => isWikiButton(node)
        case _ => false
      if getItem != null && !onLink then
        list.getSelectionModel.select(getItem)
        accept()
        e.consume()
    )
    override def updateItem(item: Suggestion, empty: Boolean): Unit =
      super.updateItem(item, empty)
      setText(if empty || item == null then null else
        val kind = if item.kind == sdfx.ui.vm.SuggestionKind.Tag then "" else s"[${item.kind.title}] "
        s"$kind${item.label}    ${item.detail}")
      setGraphic(null)
      setAccessibleText(null)
      if !empty && item != null then
        var marker: Option[javafx.scene.Node] = None
        item.category.foreach { category =>
          setAccessibleText(s"${item.label}, ${item.detail}, category $category")
          val light = Option(area.getScene).exists(_.getStylesheets.asScala.exists(_.endsWith("light.css")))
          source.categoryColor(item, light).foreach { color =>
            val dot = new javafx.scene.shape.Circle(4, color)
            dot.setStroke(javafx.scene.paint.Color.web("#343b46"))
            dot.setStrokeWidth(0.8)
            dot.setAccessibleText(s"category $category")
            marker = Some(dot)
          }
        }
        source.wikiUri(item) match
          case None => marker.foreach(setGraphic)
          case Some(uri) =>
            val link = new Button("?")
            link.setId("tac-wiki-link")
            link.setFocusTraversable(false)
            link.setAccessibleText("Open external tag wiki")
            link.setTooltip(new javafx.scene.control.Tooltip("Open external tag wiki (F1)"))
            link.setOnAction(_ => source.openWiki(uri))
            val box = new HBox(4)
            marker.foreach(box.getChildren.add)
            box.getChildren.add(link)
            setGraphic(box)
  )
  popup.getContent.add(new VBox(list, lineNavigation))
  previewView.setFitWidth(160); previewView.setFitHeight(160); previewView.setPreserveRatio(true)
  previewView.setId("tag-autocomplete-preview")
  previewPopup.getContent.add(new StackPane(previewView))
  private var composing = false
  private var imeCommitGuard = false
  private var accepting = false
  private var suppressed = false
  private var selectedByKeyboard = false
  private var revision = 0L
  private val delayTimer = new javafx.animation.PauseTransition()
  private var lineItems = Vector.empty[Suggestion]
  private var linePage = 0
  private var lineTruncated = false
  private var current: Option[(String, CompletionResult)] = None
  def isComposing: Boolean = composing || imeCommitGuard
  def isShowing: Boolean = popup.isShowing
  def isPending: Boolean = delayTimer.getStatus == javafx.animation.Animation.Status.RUNNING
  /** True while the extra-network preview image window is up (for checks). */
  def previewShowing: Boolean = previewPopup.isShowing
  def previewImage: Option[Image] = Option(previewView.getImage)
  def suggestions: Vector[Suggestion] = current.map(_._2.items).getOrElse(Vector.empty)
  def hide(): Unit =
    delayTimer.stop()
    revision += 1
    popup.hide(); previewPopup.hide(); current = None; lineItems = Vector.empty
    lineNavigation.setVisible(false); lineNavigation.setManaged(false)

  private def showLinePage(): Unit =
    val pageSize = source.maxResults.max(1)
    val pages = (lineItems.size + pageSize - 1) / pageSize
    val page = lineItems.slice(linePage * pageSize, (linePage + 1) * pageSize)
    current = current.map { (text, result) => text -> result.copy(items = page) }
    list.getItems.setAll(page*)
    list.getSelectionModel.select(0)
    list.setPrefHeight(math.min(8, page.size) * 26 + 4)
    lineStatus.setText(s"${linePage + 1}/$pages · PageUp/PageDown" + (if lineTruncated then " · first 5,000 lines only" else ""))
    linePrevious.setDisable(linePage == 0)
    lineNext.setDisable(linePage + 1 >= pages)
    lineNavigation.setVisible(true); lineNavigation.setManaged(true)

  linePrevious.setOnAction(_ => if linePage > 0 then { linePage -= 1; showLinePage() })
  lineNext.setOnAction(_ => if (linePage + 1) * source.maxResults.max(1) < lineItems.size then { linePage += 1; showLinePage() })

  private def showPreview(): Unit =
    val item = Option(list.getSelectionModel.getSelectedItem)
    val path = if source.previews then item.flatMap(_.preview()) else None
    path match
      case Some(p) if popup.isShowing =>
        previewView.setImage(new Image(p.toUri.toString, 160, 160, true, true, true))
        if !previewPopup.isShowing then previewPopup.show(area, popup.getX + popup.getWidth + 4, popup.getY)
        else previewPopup.setX(popup.getX + popup.getWidth + 4)
      case _ => previewPopup.hide()
  list.getSelectionModel.selectedItemProperty.addListener((_, _, _) => showPreview())

  private def update(): Unit =
    if accepting || suppressed || isComposing || !area.isFocused || area.getScene == null then hide()
    else
      source.ensureLoaded()
      val text = area.getText
      val result = source.complete(text, area.getCaretPosition, area.getSelection.getStart, area.getSelection.getEnd)
      result match
        case None => hide()
        case Some(r) =>
          current = Some(text -> r)
          list.getItems.setAll(r.items*)
          list.getSelectionModel.select(0)
          selectedByKeyboard = false
          list.setPrefHeight(math.min(8, r.items.size) * 26 + 4)
          val bounds = area.localToScreen(area.getBoundsInLocal)
          if bounds != null then
            list.getStylesheets.setAll(area.getScene.getStylesheets)
            if !popup.isShowing then popup.show(area, bounds.getMinX, bounds.getMaxY)
            showPreview()

  private def accept(): Unit =
    val selected = Option(list.getSelectionModel.getSelectedItem)
    var selectedWildcard: Option[(String, Int, Int)] = None
    for item <- selected; (text, r) <- current if text == area.getText do
      accepting = true
      try
        val insertion = item.insert()
        area.replaceText(r.start, r.end, insertion)
        area.positionCaret(r.start + insertion.length)
        if item.kind == sdfx.ui.vm.SuggestionKind.Wildcard then
          selectedWildcard = Some((item.label, r.start, r.start + item.label.length + 4))
      finally accepting = false
    suppressed = true
    hide()
    selectedWildcard.foreach { (name, start, end) =>
      val expected = area.getText
      val caret = area.getCaretPosition
      val ticket = revision
      val context = source.wildcardContext
      source.wildcardLines(name) { response =>
        if ticket == revision && context == source.wildcardContext && area.isFocused && area.getText == expected && area.getCaretPosition == caret then
          response match
            case Left(error) => source.wildcardError(error)
            case Right(lines) if lines.nonEmpty =>
              lineTruncated = lines.size > 5000
              lineItems = lines.take(5000).map(line => Suggestion(sdfx.ui.vm.SuggestionKind.WildcardValue, line, name, () => line))
              linePage = 0
              current = Some(expected -> CompletionResult(start, end, Vector.empty))
              showLinePage()
              selectedByKeyboard = false
              val bounds = area.localToScreen(area.getBoundsInLocal)
              if bounds != null then
                list.getStylesheets.setAll(area.getScene.getStylesheets)
                popup.show(area, bounds.getMinX, bounds.getMaxY)
            case _ => ()
      }
    }

  /** Tab may extend an ambiguous wildcard path without choosing the first file. */
  private def partialWildcard(): Boolean =
    current.exists { (text, result) =>
      val items = result.items
      val caret = area.getCaretPosition
      val mode = source.wildcardPathMode
      if items.isEmpty || !items.forall(_.kind == sdfx.ui.vm.SuggestionKind.Wildcard) ||
          caret < result.start + 2 || caret > result.end || text != area.getText || mode == "always-full" then false
      else
        val typed = text.substring(result.start + 2, caret)
        val prefix = if mode == "next-folder" then
          Option(list.getSelectionModel.getSelectedItem).flatMap { item =>
            val slash = item.label.indexOf('/', typed.length)
            if slash < 0 then None else Some(item.label.substring(0, slash + 1))
          }
        else if selectedByKeyboard then None else result.wildcardPrefix
        if prefix.isEmpty then false
        else if caret < result.end || prefix.get.length <= typed.length || !prefix.get.regionMatches(true, 0, typed, 0, typed.length) then true
        else
          accepting = true
          try
            val extension = prefix.get.substring(typed.length)
            area.insertText(caret, extension)
            area.positionCaret(caret + extension.length)
          finally accepting = false
          suppressed = false
          update()
          true
    }

  private def scheduleUpdate(): Unit =
    val ms = source.completionDelayMs.max(0).min(5000)
    if ms == 0 then update()
    else if !accepting then
      hide()
      if !suppressed && !isComposing && area.isFocused && area.getScene != null then
        val ticket = revision
        delayTimer.setDuration(javafx.util.Duration.millis(ms.toDouble))
        delayTimer.setOnFinished(_ => if ticket == revision then update())
        delayTimer.playFromStart()

  area.textProperty.addListener((_, _, _) =>
    revision += 1
    if !accepting then
      if !isComposing then suppressed = false
      val ticket = revision
      if source.completionDelayMs > 0 then scheduleUpdate()
      else Platform.runLater(() => if ticket == revision then update())
  )
  area.caretPositionProperty.addListener((_, _, _) => { revision += 1; scheduleUpdate() })
  area.selectionProperty.addListener((_, _, _) => { revision += 1; scheduleUpdate() })
  area.focusedProperty.addListener((_, _, focused) =>
    if focused then source.ensureLoaded()
    else
      composing = false
      imeCommitGuard = false
      hide()
  )
  area.sceneProperty.addListener((_, _, scene) => if scene == null then hide())
  area.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, (e: InputMethodEvent) =>
    composing = !e.getComposed.isEmpty
    imeCommitGuard = true
    suppressed = true
    hide()
    // Never consume IME events; wait for the next ordinary edit after commit.
  )
  area.addEventFilter(KeyEvent.KEY_RELEASED, (_: KeyEvent) => if !composing then imeCommitGuard = false)
  area.addEventFilter(KeyEvent.KEY_PRESSED, (e: KeyEvent) =>
    if e.getCode == KeyCode.ESCAPE && !popup.isShowing then { revision += 1; delayTimer.stop() }
    if !isComposing && popup.isShowing && !e.isControlDown && !e.isMetaDown && !e.isAltDown && !e.isShiftDown then
      e.getCode match
        case KeyCode.DOWN => selectedByKeyboard = true; list.getSelectionModel.select((list.getSelectionModel.getSelectedIndex + 1).min(list.getItems.size - 1)); e.consume()
        case KeyCode.UP => selectedByKeyboard = true; list.getSelectionModel.select((list.getSelectionModel.getSelectedIndex - 1).max(0)); e.consume()
        case KeyCode.ENTER => accept(); e.consume()
        case KeyCode.TAB => if !partialWildcard() then accept(); e.consume()
        case KeyCode.F1 =>
          Option(list.getSelectionModel.getSelectedItem).flatMap(source.wikiUri).foreach { uri =>
            source.openWiki(uri); e.consume()
          }
        case KeyCode.PAGE_DOWN if lineItems.nonEmpty =>
          if (linePage + 1) * source.maxResults.max(1) < lineItems.size then { linePage += 1; showLinePage() }
          e.consume()
        case KeyCode.PAGE_UP if lineItems.nonEmpty =>
          if linePage > 0 then { linePage -= 1; showLinePage() }
          e.consume()
        case KeyCode.ESCAPE => suppressed = true; hide(); e.consume()
        case _ => ()
  )
