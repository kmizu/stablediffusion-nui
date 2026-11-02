package sdfx.ui.view

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.{ButtonType, CheckBox, Dialog, Label, ListCell, ListView, Spinner, TextArea, TextField}
import javafx.scene.image.{Image, ImageView}
import javafx.scene.input.{InputMethodEvent, KeyCode, KeyEvent}
import javafx.scene.layout.{GridPane, HBox, StackPane, VBox}
import javafx.stage.{FileChooser, Popup}
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.vm.{CompletionData, CompletionEngine, CompletionResult, CompletionTag, NetFile, Suggestion, TagCompletion}
import java.nio.file.{Path, Paths}

/** One dictionary per window; file loading never blocks the FX thread. Tags, translations, the extra file and chants
  * are read together; wildcard names are rescanned in the background; LoRA/embedding names come from the model catalog. */
final class TagDictionary(ctx: UiContext):
  private var loaded = CompletionData()
  private var loadedKey: Option[Key] = None
  private var version = 0L
  private var wildcards = Vector.empty[String]
  private var wildcardsAt: Option[Long] = None
  private var scanning = false
  private final case class Key(tags: Option[String], translation: Option[String], legacy: Boolean, extra: Option[String], chants: Option[String])

  def tags: Vector[CompletionTag] = loaded.tags
  def config: TagCompleteSettings = ctx.services.settings.tagComplete

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

  private def scanWildcards(): Unit =
    if !scanning then
      scanning = true
      val dirs = wildcardDirs
      sdfx.ui.Fx.async(new sdfx.plugins.prompt.DynamicPrompt.Wildcards(dirs).names)(names =>
        wildcards = names; wildcardsAt = Some(System.nanoTime()); scanning = false
      )(_ => { wildcardsAt = Some(System.nanoTime()); scanning = false })

  def ensureLoaded(): Unit =
    if wildcardsAt.forall(t => System.nanoTime() - t > 5000000000L) then scanWildcards()
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

  def reload(): Unit =
    loadedKey = None
    wildcardsAt = None
    ensureLoaded()

  /** Persist the form's values and reload; used by the dialog's Save button. */
  def apply(csv: Option[String], cfg: TagCompleteSettings): Unit =
    ctx.services.updateSettings(_.copy(tagAutocompleteCsv = csv, tagComplete = cfg))
    reload()

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
    val result = dialog.showAndWait().orElse(ButtonType.CANCEL)
    if result == save then
      val (csv, cfg) = pane.read()
      apply(csv, cfg)
    else if result == disable then
      ctx.services.updateSettings(_.copy(tagAutocompleteCsv = None))
      ensureLoaded()
    else if result == reloadButton then reload()

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
  private val max = new Spinner[Integer](1, 50, cfg.maxResults)
  max.setId("tac-max"); max.setEditable(true)

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
    Seq(underscores, escape, comma, alias, onlyAlias, wildcards, networks, chantsBox, trigger, preview, legacy, extraAfter)
      .zipWithIndex.foreach((c, i) => options.add(c, i % 2, i / 2))
    val maxRow = new HBox(6, new Label("Maximum results"), max)
    val help = new Label("Up/Down: select · Enter/Tab: insert · Escape: close · __ wildcards · < extra networks")
    val v = new VBox(8,
      new Label("Local files only; nothing is downloaded. Leave a path empty to turn that source off."),
      row("Tag CSV", tags, csv), row("Translation CSV", translation, csv), row("Extra tag CSV", extra, csv),
      row("Chants JSON", chantsField, new FileChooser.ExtensionFilter("JSON", "*.json")),
      options, maxRow, help)
    v.setPadding(new Insets(4))
    v

  private def opt(f: TextField): Option[String] = Option(f.getText).map(_.trim).filter(_.nonEmpty)
  def read(): (Option[String], TagCompleteSettings) =
    val n = try max.getEditor.getText.trim.toInt.max(1).min(50) catch case _: NumberFormatException => cfg.maxResults
    (opt(tags), TagCompleteSettings(
      translationCsv = opt(translation), translationLegacy = legacy.isSelected, extraCsv = opt(extra), extraAfter = extraAfter.isSelected,
      chantsJson = opt(chantsField), maxResults = n, replaceUnderscores = underscores.isSelected, escapeParens = escape.isSelected,
      appendComma = comma.isSelected, searchByAlias = alias.isSelected, onlyShowAlias = onlyAlias.isSelected,
      wildcards = wildcards.isSelected, extraNetworks = networks.isSelected, chants = chantsBox.isSelected,
      loraTriggerWords = trigger.isSelected, extraNetworkPreview = preview.isSelected))

/** What the popup needs from its owner. */
trait CompletionSource:
  def ensureLoaded(): Unit
  def complete(text: String, caret: Int, selStart: Int, selEnd: Int): Option[CompletionResult]
  def previews: Boolean

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
      def previews: Boolean = dictionary.config.extraNetworkPreview)
  private val popup = new Popup()
  private val list = new ListView[Suggestion]()
  private val previewPopup = new Popup()
  private val previewView = new ImageView()
  popup.setAutoHide(true)
  popup.setHideOnEscape(false)
  list.setFocusTraversable(false)
  list.setPrefWidth(420)
  list.setCellFactory(_ => new ListCell[Suggestion]():
    setOnMousePressed(e =>
      if getItem != null then
        list.getSelectionModel.select(getItem)
        accept()
        e.consume()
    )
    override def updateItem(item: Suggestion, empty: Boolean): Unit =
      super.updateItem(item, empty)
      setText(if empty || item == null then null else
        val kind = if item.kind == sdfx.ui.vm.SuggestionKind.Tag then "" else s"[${item.kind.title}] "
        s"$kind${item.label}    ${item.detail}")
  )
  popup.getContent.add(list)
  previewView.setFitWidth(160); previewView.setFitHeight(160); previewView.setPreserveRatio(true)
  previewView.setId("tag-autocomplete-preview")
  previewPopup.getContent.add(new StackPane(previewView))
  private var composing = false
  private var imeCommitGuard = false
  private var accepting = false
  private var suppressed = false
  private var current: Option[(String, CompletionResult)] = None
  def isComposing: Boolean = composing || imeCommitGuard
  def isShowing: Boolean = popup.isShowing
  /** True while the extra-network preview image window is up (for checks). */
  def previewShowing: Boolean = previewPopup.isShowing
  def previewImage: Option[Image] = Option(previewView.getImage)
  def suggestions: Vector[Suggestion] = current.map(_._2.items).getOrElse(Vector.empty)
  def hide(): Unit =
    popup.hide(); previewPopup.hide(); current = None

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
          list.setPrefHeight(math.min(8, r.items.size) * 26 + 4)
          val bounds = area.localToScreen(area.getBoundsInLocal)
          if bounds != null then
            list.getStylesheets.setAll(area.getScene.getStylesheets)
            if !popup.isShowing then popup.show(area, bounds.getMinX, bounds.getMaxY)
            showPreview()

  private def accept(): Unit =
    val selected = Option(list.getSelectionModel.getSelectedItem)
    for item <- selected; (text, r) <- current if text == area.getText do
      accepting = true
      try
        val insertion = item.insert()
        area.replaceText(r.start, r.end, insertion)
        area.positionCaret(r.start + insertion.length)
      finally accepting = false
    suppressed = true
    hide()

  area.textProperty.addListener((_, _, _) =>
    if !accepting then
      if !isComposing then suppressed = false
      Platform.runLater(() => update())
  )
  area.caretPositionProperty.addListener((_, _, _) => update())
  area.selectionProperty.addListener((_, _, _) => update())
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
    if !isComposing && popup.isShowing && !e.isControlDown && !e.isMetaDown && !e.isAltDown && !e.isShiftDown then
      e.getCode match
        case KeyCode.DOWN => list.getSelectionModel.select((list.getSelectionModel.getSelectedIndex + 1).min(list.getItems.size - 1)); e.consume()
        case KeyCode.UP => list.getSelectionModel.select((list.getSelectionModel.getSelectedIndex - 1).max(0)); e.consume()
        case KeyCode.ENTER | KeyCode.TAB => accept(); e.consume()
        case KeyCode.ESCAPE => suppressed = true; hide(); e.consume()
        case _ => ()
  )
