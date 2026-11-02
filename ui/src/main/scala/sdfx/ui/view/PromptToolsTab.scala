package sdfx.ui.view

import javafx.beans.property.{SimpleBooleanProperty, StringProperty}
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.*
import javafx.stage.{DirectoryChooser, FileChooser}
import sdfx.core.prompttools.*
import sdfx.ui.Fx
import sdfx.ui.vm.{Mode, PromptToolsVM}

import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** The "Prompt Tools" tab: saved prompts (Multi-Prompt Manager), history and favourites and a tag editor with a
  * blacklist (Prompt All in One, without translation), YAML tag buttons (D2 Prompt Selector) and `.tag` cards
  * (Easy Tag Insert). Decisions live in [[PromptToolsVM]] and `sdfx.core.prompttools`.
  */
final class PromptToolsTab(ctx: UiContext, service: PromptToolsService, lastMode: () => Mode):
  private val modeFields: Mode => (StringProperty, StringProperty) = m => ctx.promptFields(m)
  private val modeBox = new ComboBox[Mode](FXCollections.observableArrayList(Mode.values.toSeq*))
  private var current: Mode = Mode.Txt2Img
  val vm = new PromptToolsVM(service, () => modeFields(current))
  val node = new BorderPane()
  node.setId("prompt-tools")

  /** Every insertion goes to the negative prompt when this is on (Ctrl-click does it for a single click). */
  val negativeTarget = new SimpleBooleanProperty(false)
  private val status = W.label("", "muted")
  status.setId("prompt-tools-status")
  status.setWrapText(true)
  private def say(text: String): Unit = status.setText(text)
  private def guard(f: => Unit): Unit =
    try f catch case NonFatal(e) =>
      say(Fx.message(e))
      ctx.error(Fx.message(e))

  // ---- header: target + live preview ----------------------------------------------------------------------
  private val promptPreview = preview("prompt-tools-prompt", "Prompt")
  private val negativePreview = preview("prompt-tools-negative", "Negative prompt")
  private def preview(id: String, hint: String): TextArea =
    val a = new TextArea()
    a.setId(id); a.setEditable(false); a.setPrefRowCount(2); a.setWrapText(true); a.setPromptText(hint)
    a
  private var bound: Option[(StringProperty, StringProperty)] = None
  private def bindPreview(): Unit =
    bound.foreach { (p, n) => promptPreview.textProperty.unbind(); negativePreview.textProperty.unbind() }
    val fields = modeFields(current)
    promptPreview.textProperty.bind(fields._1)
    negativePreview.textProperty.bind(fields._2)
    bound = Some(fields)
  modeBox.setId("prompt-tools-mode")
  modeBox.getSelectionModel.select(current)
  modeBox.valueProperty.addListener((_, _, m) => if m != null then { current = m; bindPreview(); refreshTagEditor() })
  private val negativeBox = W.check("Insert into the negative prompt", negativeTarget,
    "Applies to tag buttons, tag cards and the tag editor. Ctrl-click inserts one tag into the negative prompt.")
  negativeBox.setId("prompt-tools-negative-target")
  private val header = new VBox(6,
    W.hbox(8, W.label("Target tab"), modeBox, negativeBox, W.spacer()),
    promptPreview, negativePreview, status)
  header.setPadding(new Insets(8))
  node.setTop(header)

  /** Called when the tab is selected: follow the generation tab the user came from. */
  def shown(): Unit =
    val m = lastMode()
    if m != current then modeBox.setValue(m) else bindPreview()
    reloadLibrary(); reloadHistory(); reloadCards(); reloadBlacklist()
    if !tagsLoaded then reloadTags()
    refreshTagEditor()

  // ---- pages ----------------------------------------------------------------------------------------------
  private val pages = new TabPane()
  pages.setId("prompt-tools-pages")
  pages.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE)
  private def page(id: String, title: String, content: Node): Tab =
    val t = new Tab(title, content); t.setId(id); t
  node.setCenter(pages)

  // ============ Saved prompts =============================================================================
  private val names = new ListView[SavedPrompt]()
  names.setId("prompt-tools-saved-list")
  names.setCellFactory(_ => new ListCell[SavedPrompt]:
    override def updateItem(item: SavedPrompt, empty: Boolean): Unit =
      super.updateItem(item, empty)
      setText(if empty || item == null then null else item.name + (if item.negative then "  (negative)" else "")))
  private val savedName = new TextField()
  savedName.setId("prompt-tools-saved-name"); savedName.setPromptText("Name (blank: prompt 1, prompt 2, ...)")
  private val savedText = new TextArea()
  savedText.setId("prompt-tools-saved-text"); savedText.setPrefRowCount(6); savedText.setWrapText(true); savedText.setPromptText("Prompt text")
  private val savedNegative = new CheckBox("This is a negative prompt")
  savedNegative.setId("prompt-tools-saved-negative")
  W.tip(savedNegative, "Whether Use sends the text to the negative prompt")
  names.getSelectionModel.selectedItemProperty.addListener((_, _, e) =>
    if e != null then { savedName.setText(e.name); savedText.setText(e.prompt); savedNegative.setSelected(e.negative) })

  private def reloadLibrary(select: Option[String] = None): Unit =
    val keep = select.orElse(Option(names.getSelectionModel.getSelectedItem).map(_.name))
    try
      val lib = service.library
      names.getItems.setAll(lib.entries.asJava)
      keep.flatMap(n => lib.entries.indexWhere(_.name == n) match { case -1 => None; case i => Some(i) })
        .foreach(i => names.getSelectionModel.select(i))
    catch case NonFatal(e) => say("Cannot read prompts.json: " + Fx.message(e))

  private val saveBtn = W.button("Save", "Save the text under this name (replaces a prompt of the same name)") {
    guard {
      vm.saveText(savedName.getText, savedText.getText, savedNegative.isSelected) match
        case Right(n) => say(s"Saved '$n'"); reloadLibrary(Some(n))
        case Left(e) => say(e)
    }
  }
  saveBtn.setId("prompt-tools-saved-save")
  private val useBtn = W.button("Use", "Replace the target prompt (or negative prompt) with this text") {
    guard {
      if savedText.getText.trim.isEmpty then say("Nothing to use: the text is empty")
      else
        vm.useText(savedText.getText, savedNegative.isSelected)
        say(s"Sent to the ${if savedNegative.isSelected then "negative prompt" else "prompt"} of ${current.label}")
    }
  }
  useBtn.setId("prompt-tools-saved-use")
  private val deleteBtn = W.button("Delete", "Delete the selected saved prompt") {
    Option(names.getSelectionModel.getSelectedItem).foreach { e =>
      val confirm = new Alert(Alert.AlertType.CONFIRMATION, s"Delete the saved prompt '${e.name}'?", ButtonType.CANCEL, ButtonType.OK)
      confirm.initOwner(ctx.stage)
      if confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK then
        guard { vm.delete(e.name).left.foreach(say); say(s"Deleted '${e.name}'"); reloadLibrary() }
    }
  }
  deleteBtn.setId("prompt-tools-saved-delete")
  private val renameBtn = W.button("Rename", "Rename the selected prompt to the name in the box") {
    Option(names.getSelectionModel.getSelectedItem).foreach { e =>
      guard {
        vm.rename(e.name, savedName.getText) match
          case Right(_) => say(s"Renamed to '${savedName.getText.trim}'"); reloadLibrary(Some(savedName.getText.trim))
          case Left(m) => say(m)
      }
    }
  }
  renameBtn.setId("prompt-tools-saved-rename")
  private val copyBtn = W.button("Copy from active prompt", "Copy the target tab's prompt (or negative prompt) into the text box") {
    savedText.setText(vm.text(savedNegative.isSelected))
  }
  copyBtn.setId("prompt-tools-saved-copy")
  private val importBtn = W.button("Import…", "Merge a prompts.json file (Multi-Prompt Manager format)") {
    val chooser = new FileChooser()
    chooser.getExtensionFilters.add(new FileChooser.ExtensionFilter("JSON", "*.json"))
    Option(chooser.showOpenDialog(ctx.stage)).foreach { f =>
      guard {
        val other = service.libraryStore.importFile(f.toPath)
        val overwrite = other.entries.exists(e => service.library.get(e.name).nonEmpty) && {
          val a = new Alert(Alert.AlertType.CONFIRMATION, "Some names already exist. Replace them?", ButtonType.NO, ButtonType.YES)
          a.initOwner(ctx.stage)
          a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES
        }
        var taken = 0
        service.updateLibrary { lib => val (m, n) = lib.merge(other, overwrite); taken = n; m }
        say(s"Imported $taken of ${other.entries.size} prompts"); reloadLibrary()
      }
    }
  }
  importBtn.setId("prompt-tools-saved-import")
  private val exportBtn = W.button("Export…", "Write all saved prompts to a prompts.json file") {
    val chooser = new FileChooser()
    chooser.setInitialFileName("prompts.json")
    Option(chooser.showSaveDialog(ctx.stage)).foreach(f => guard { service.libraryStore.exportTo(service.library, f.toPath); say("Exported to " + f) })
  }
  exportBtn.setId("prompt-tools-saved-export")
  private val savedPage = new VBox(6,
    W.label("Saved prompts"), W.vgrow(names), savedName, savedText, savedNegative,
    new FlowPane(6, 6, useBtn, saveBtn, renameBtn, deleteBtn, copyBtn, importBtn, exportBtn))
  savedPage.setPadding(new Insets(8))

  // ============ History & favourites ======================================================================
  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
  private def recordCell(): ListCell[PromptRecord] = new ListCell[PromptRecord]:
    override def updateItem(item: PromptRecord, empty: Boolean): Unit =
      super.updateItem(item, empty)
      if empty || item == null then { setText(null); setTooltip(null) }
      else
        val head = if item.name.nonEmpty then item.name + " - " else ""
        val body = item.prompt.replace('\n', ' ')
        setText(head + (if body.length > 140 then body.take(140) + "..." else body))
        setTooltip(new Tooltip(s"${if item.at > 0 then dateFormat.format(new Date(item.at)) else ""}\nPrompt: ${item.prompt}\nNegative: ${item.negative}"))
  private val historyList = new ListView[PromptRecord]()
  historyList.setId("prompt-tools-history-list"); historyList.setCellFactory(_ => recordCell())
  private val favoritesList = new ListView[PromptRecord]()
  favoritesList.setId("prompt-tools-favorites-list"); favoritesList.setCellFactory(_ => recordCell())
  private val favoriteName = new TextField()
  favoriteName.setId("prompt-tools-favorite-name"); favoriteName.setPromptText("Favourite name (optional)")
  private def reloadHistory(): Unit =
    try
      val h = service.history
      historyList.getItems.setAll(h.history.asJava)
      favoritesList.getItems.setAll(h.favorites.asJava)
    catch case NonFatal(e) => say("Cannot read history.json: " + Fx.message(e))
  /** A job was submitted: show the new history entry. */
  def historyChanged(): Unit = reloadHistory()
  private def restore(list: ListView[PromptRecord]): Unit =
    Option(list.getSelectionModel.getSelectedItem) match
      case Some(r) => vm.restore(r); say(s"Restored into ${current.label}")
      case None => say("Select an entry first")
  private val restoreHistory = W.button("Restore", "Put the selected prompt and negative prompt back") { restore(historyList) }
  restoreHistory.setId("prompt-tools-history-restore")
  private val bookmarkHistory = W.button("Bookmark selected", "Add the selected history entry to the favourites") {
    Option(historyList.getSelectionModel.getSelectedItem) match
      case Some(r) => guard { service.updateHistory(_.addFavorite(r.prompt, r.negative, service.now, favoriteName.getText)); reloadHistory(); say("Bookmarked") }
      case None => say("Select a history entry first")
  }
  bookmarkHistory.setId("prompt-tools-history-bookmark")
  private val bookmarkCurrent = W.button("Bookmark current", "Add the target tab's current prompt and negative prompt to the favourites") {
    guard { vm.favoriteCurrent(favoriteName.getText).fold(say, _ => { reloadHistory(); say("Bookmarked the current prompts") }) }
  }
  bookmarkCurrent.setId("prompt-tools-favorite-current")
  private val removeHistory = W.button("Remove", "Remove the selected history entry") {
    val i = historyList.getSelectionModel.getSelectedIndex
    if i >= 0 then guard { service.updateHistory(_.removeHistory(i)); reloadHistory() }
  }
  removeHistory.setId("prompt-tools-history-remove")
  private val clearHistory = W.button("Clear history", "Delete the whole history (favourites are kept)") {
    val confirm = new Alert(Alert.AlertType.CONFIRMATION, "Clear the whole prompt history? Favourites are kept.", ButtonType.CANCEL, ButtonType.OK)
    confirm.initOwner(ctx.stage)
    if confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK then guard { service.updateHistory(_.clearHistory()); reloadHistory() }
  }
  clearHistory.setId("prompt-tools-history-clear")
  private val restoreFavorite = W.button("Restore", "Put the selected favourite back") { restore(favoritesList) }
  restoreFavorite.setId("prompt-tools-favorite-restore")
  private val removeFavorite = W.button("Remove", "Remove the selected favourite") {
    val i = favoritesList.getSelectionModel.getSelectedIndex
    if i >= 0 then guard { service.updateHistory(_.removeFavorite(i)); reloadHistory() }
  }
  removeFavorite.setId("prompt-tools-favorite-remove")
  private val historyPage = new HBox(10,
    W.hgrow(new VBox(6, W.label("History (newest first, recorded when you generate)"), W.vgrow(historyList),
      new FlowPane(6, 6, restoreHistory, bookmarkHistory, removeHistory, clearHistory))),
    W.hgrow(new VBox(6, W.label("Favourites"), W.vgrow(favoritesList), favoriteName,
      new FlowPane(6, 6, restoreFavorite, bookmarkCurrent, removeFavorite))))
  historyPage.setPadding(new Insets(8))

  // ============ Tag editor + blacklist ====================================================================
  private val tagList = new ListView[String]()
  tagList.setId("prompt-tools-tag-list")
  private var editorLocked = false
  def refreshTagEditor(): Unit =
    val keep = tagList.getSelectionModel.getSelectedIndex
    editorLocked = true
    tagList.getItems.setAll(vm.tags(negativeTarget.get).asJava)
    editorLocked = false
    if keep >= 0 && keep < tagList.getItems.size then tagList.getSelectionModel.select(keep)
  negativeTarget.addListener((_, _, _) => refreshTagEditor())
  // follow edits made elsewhere (the generation tab) while this tab is open
  promptPreview.textProperty.addListener((_, _, _) => if !negativeTarget.get then refreshTagEditor())
  negativePreview.textProperty.addListener((_, _, _) => if negativeTarget.get then refreshTagEditor())

  private def selected: Int = tagList.getSelectionModel.getSelectedIndex
  private def edit(f: Int => Unit, reselect: Int => Int = identity): Unit =
    val i = selected
    if i < 0 then say("Select a tag first")
    else { f(i); refreshTagEditor(); val j = reselect(i); if j >= 0 && j < tagList.getItems.size then tagList.getSelectionModel.select(j) }
  private val upBtn = W.button("Up", "Move the selected tag earlier") { edit(i => vm.moveTag(negativeTarget.get, i, i - 1), i => math.max(0, i - 1)) }
  upBtn.setId("prompt-tools-tag-up")
  private val downBtn = W.button("Down", "Move the selected tag later") { edit(i => vm.moveTag(negativeTarget.get, i, i + 1), i => i + 1) }
  downBtn.setId("prompt-tools-tag-down")
  private val plusBtn = W.button("Weight +0.1", "Raise the weight of the selected tag") { edit(i => vm.weightTag(negativeTarget.get, i, 0.1)) }
  plusBtn.setId("prompt-tools-tag-plus")
  private val minusBtn = W.button("Weight -0.1", "Lower the weight of the selected tag") { edit(i => vm.weightTag(negativeTarget.get, i, -0.1)) }
  minusBtn.setId("prompt-tools-tag-minus")
  private val delTagBtn = W.button("Delete tag", "Remove the selected tag from the prompt") { edit(i => vm.removeTag(negativeTarget.get, i)) }
  delTagBtn.setId("prompt-tools-tag-delete")
  private val dedupeBtn = W.button("Remove duplicates", "Drop repeated tags, keeping the first") {
    val n = vm.dedupe(negativeTarget.get); refreshTagEditor(); say(s"Removed $n duplicate tag(s)")
  }
  dedupeBtn.setId("prompt-tools-tag-dedupe")
  private val blacklistArea = new TextArea()
  blacklistArea.setId("prompt-tools-blacklist"); blacklistArea.setPrefRowCount(4)
  blacklistArea.setPromptText("Blacklisted tags, one per line")
  private def reloadBlacklist(): Unit =
    try blacklistArea.setText(service.blacklist.mkString("\n")) catch case NonFatal(e) => say(Fx.message(e))
  private val saveBlacklistBtn = W.button("Save blacklist", "Store the blacklist") {
    guard { service.setBlacklist(blacklistArea.getText.linesIterator.toSeq); say(s"Blacklist saved (${service.blacklist.size} tags)") }
  }
  saveBlacklistBtn.setId("prompt-tools-blacklist-save")
  private val applyBlacklistBtn = W.button("Apply blacklist", "Remove blacklisted tags from the prompt now (saves the list first)") {
    guard {
      service.setBlacklist(blacklistArea.getText.linesIterator.toSeq)
      val n = vm.applyBlacklist(negativeTarget.get); refreshTagEditor(); say(s"Removed $n blacklisted tag(s)")
    }
  }
  applyBlacklistBtn.setId("prompt-tools-blacklist-apply")
  private val editorPage = new VBox(6,
    W.label("Tags of the prompt (the negative prompt when the box above is ticked). Editing rewrites the field as 'a, b, c'."),
    W.vgrow(tagList), new FlowPane(6, 6, upBtn, downBtn, plusBtn, minusBtn, delTagBtn, dedupeBtn),
    W.label("Blacklist"), blacklistArea, new FlowPane(6, 6, saveBlacklistBtn, applyBlacklistBtn))
  editorPage.setPadding(new Insets(8))

  // ============ Tag buttons (YAML tag files) ==============================================================
  private var tagsLoaded = false
  private var tagLibrary = TagLibrary(Vector.empty)
  private val hover = W.label("Hover a tag button to see what it inserts.", "muted")
  hover.setId("prompt-tools-hover"); hover.setWrapText(true)
  private val tagSearch = new TextField()
  tagSearch.setId("prompt-tools-tag-search"); tagSearch.setPromptText("Search tag buttons by tag or button name")
  private val tagFiles = new TabPane()
  tagFiles.setId("prompt-tools-tag-files"); tagFiles.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE)
  private val tagFolderLabel = W.label("", "muted")
  tagFolderLabel.setId("prompt-tools-tag-folder"); tagFolderLabel.setWrapText(true)
  private val tagBody = new StackPane(tagFiles)
  private val tagHits = new ScrollPane()
  tagHits.setId("prompt-tools-tag-hits"); tagHits.setFitToWidth(true)

  private def tagButton(label: String, text: String): Button =
    val b = new Button(label)
    b.getStyleClass.add("tag-button")
    b.setUserData(text)
    b.setOnAction(_ => insertSnippet(text, negativeTarget.get))
    // Ctrl-click: negative prompt. Right-click: remove from the prompt.
    b.addEventFilter(MouseEvent.MOUSE_PRESSED, (e: MouseEvent) =>
      if e.getButton == MouseButton.SECONDARY then { removeSnippet(text, negativeTarget.get); e.consume() }
      else if e.getButton == MouseButton.PRIMARY && e.isShortcutDown then { insertSnippet(text, true); e.consume() })
    b.addEventFilter(MouseEvent.MOUSE_RELEASED, (e: MouseEvent) =>
      if e.getButton == MouseButton.SECONDARY || (e.getButton == MouseButton.PRIMARY && e.isShortcutDown) then e.consume())
    b.setOnMouseEntered(_ => hover.setText(text))
    b.setOnMouseExited(_ => hover.setText("Hover a tag button to see what it inserts."))
    W.tip(b, text)
    b

  private def insertSnippet(text: String, negative: Boolean): Unit =
    guard { if vm.insert(text, negative) then say(s"Inserted: $text") else say("Already in the prompt: " + text) }
  private def removeSnippet(text: String, negative: Boolean): Unit =
    guard { if vm.remove(text, negative) then say(s"Removed: $text") else say("Not in the prompt: " + text) }

  private def groupView(g: TagNode.Group, depth: Int): Node =
    val head = new Button(g.name)
    head.getStyleClass.add("tag-group-button")
    head.setStyle("-fx-background-color: #b8691f; -fx-text-fill: white; -fx-font-weight: bold;")
    W.tip(head, "Insert a random tag from this group")
    head.setOnAction(_ => guard {
      val neg = negativeTarget.get
      vm.insertRandom(g, neg) match
        case Some(t) => say(s"Random from '${g.name}': $t")
        case None => say(s"'${g.name}' is empty")
    })
    head.addEventFilter(MouseEvent.MOUSE_PRESSED, (e: MouseEvent) =>
      if e.getButton == MouseButton.PRIMARY && e.isShortcutDown then
        guard { vm.insertRandom(g, true).foreach(t => say(s"Random from '${g.name}' (negative): $t")) }
        e.consume())
    head.addEventFilter(MouseEvent.MOUSE_RELEASED, (e: MouseEvent) => if e.getButton == MouseButton.PRIMARY && e.isShortcutDown then e.consume())
    val leaves = new FlowPane(6, 6)
    g.children.collect { case l: TagNode.Leaf => l }.foreach(l => leaves.getChildren.add(tagButton(l.label, l.text)))
    val box = new VBox(4, head, leaves)
    g.children.collect { case sub: TagNode.Group => sub }.foreach(sub => box.getChildren.add(groupView(sub, depth + 1)))
    box.setPadding(new Insets(2, 0, 4, 12.0 * math.min(depth, 1)))
    box

  private def fileView(f: TagFile): Node =
    val box = new VBox(8)
    box.setPadding(new Insets(8))
    f.error.foreach(e => box.getChildren.add(W.label("This file could not be read: " + e, "bracket-warning")))
    val topLeaves = new FlowPane(6, 6)
    f.nodes.collect { case l: TagNode.Leaf => l }.foreach(l => topLeaves.getChildren.add(tagButton(l.label, l.text)))
    if !topLeaves.getChildren.isEmpty then box.getChildren.add(topLeaves)
    f.nodes.collect { case g: TagNode.Group => g }.foreach(g => box.getChildren.add(groupView(g, 0)))
    val s = new ScrollPane(box); s.setFitToWidth(true); s
  private def showTags(): Unit =
    val keep = Option(tagFiles.getSelectionModel.getSelectedItem).map(_.getText)
    tagFiles.getTabs.setAll(tagLibrary.files.map { f =>
      val t = new Tab(f.name + (if f.error.nonEmpty then " (!)" else ""), fileView(f)); t.setId("prompt-tools-tagfile-" + f.name); t.setUserData(f.name); t
    }.asJava)
    keep.flatMap(k => tagFiles.getTabs.asScala.find(_.getText == k)).foreach(t => tagFiles.getSelectionModel.select(t))
    tagFolderLabel.setText("Tag folder: " + service.tagFolder + (if tagLibrary.files.isEmpty then "  (no .yml files)" else ""))
    runSearch()
  def reloadTags(): Unit =
    Fx.async(service.loadTags())(lib => { tagLibrary = lib; tagsLoaded = true; showTags() })(e => say("Cannot read tag files: " + Fx.message(e)))
  /** Load the tag files synchronously (tests and the check driver). */
  def reloadTagsNow(): Unit = { tagLibrary = service.loadTags(); tagsLoaded = true; showTags() }
  private def runSearch(): Unit =
    val q = tagSearch.getText
    if q.trim.isEmpty then tagBody.getChildren.setAll(tagFiles)
    else
      val hits = TagSelector.search(tagLibrary, q)
      val flow = new FlowPane(6, 6)
      flow.setPadding(new Insets(8))
      hits.take(400).foreach { h =>
        val b = tagButton(h.leaf.label, h.leaf.text)
        W.tip(b, s"${h.file} / ${h.path.mkString(" / ")}\n${h.leaf.text}")
        flow.getChildren.add(b)
      }
      if hits.isEmpty then flow.getChildren.add(W.label("No tag button matches."))
      tagHits.setContent(flow)
      tagBody.getChildren.setAll(tagHits)
  tagSearch.textProperty.addListener((_, _, _) => runSearch())
  private val tagReload = W.button("Reload", "Read the tag files again") { reloadTags() }
  tagReload.setId("prompt-tools-tag-reload")
  private val tagFolderBtn = W.button("Folder…", "Choose another folder of YAML tag files (for example one in a cloud drive)") {
    val chooser = new DirectoryChooser()
    chooser.setTitle("Tag file folder")
    Option(chooser.showDialog(ctx.stage)).foreach { d => guard { service.setTagFolder(Some(d.toPath)); reloadTags() } }
  }
  tagFolderBtn.setId("prompt-tools-tag-folder-choose")
  private val tagFolderReset = W.button("Default folder", "Go back to config/prompt-tools/tag-selector") { guard { service.setTagFolder(None); reloadTags() } }
  tagFolderReset.setId("prompt-tools-tag-folder-reset")
  private val tagsPage = new VBox(6,
    W.hbox(6, W.hgrow(tagSearch), tagReload, tagFolderBtn, tagFolderReset), tagFolderLabel,
    W.label("Click inserts, a group button inserts a random tag from it, Ctrl-click goes to the negative prompt, right-click removes.", "muted"),
    W.vgrow(tagBody), hover)
  tagsPage.setPadding(new Insets(8))

  // ============ Tag cards (.tag files) ====================================================================
  private val cardsBox = new VBox(8)
  cardsBox.setId("prompt-tools-cards")
  private val cardsScroll = new ScrollPane(cardsBox)
  cardsScroll.setFitToWidth(true)
  private val cardCategory = new TextField(); cardCategory.setId("prompt-tools-card-category"); cardCategory.setPromptText("Category (use / for sub folders)")
  private val cardName = new TextField(); cardName.setId("prompt-tools-card-name"); cardName.setPromptText("Name")
  private val cardText = new TextArea(); cardText.setId("prompt-tools-card-text"); cardText.setPrefRowCount(3); cardText.setWrapText(true)
  cardText.setPromptText("Text inserted when the card is clicked")
  private def reloadCards(): Unit =
    try
      val cards = service.cards.list()
      cardsBox.getChildren.clear()
      cards.groupBy(_.category).toVector.sortBy(_._1.toLowerCase).foreach { (category, group) =>
        val flow = new FlowPane(6, 6)
        group.foreach { c =>
          val b = new Button(c.name)
          b.setId("prompt-tools-card-" + c.id.replace('/', '-').replace(' ', '_'))
          b.getStyleClass.add("tag-card")
          b.setOnAction(_ => insertSnippet(c.text, negativeTarget.get))
          b.addEventFilter(MouseEvent.MOUSE_PRESSED, (e: MouseEvent) =>
            if e.getButton == MouseButton.PRIMARY && e.isShortcutDown then { insertSnippet(c.text, true); e.consume() })
          b.addEventFilter(MouseEvent.MOUSE_RELEASED, (e: MouseEvent) => if e.getButton == MouseButton.PRIMARY && e.isShortcutDown then e.consume())
          val menu = new ContextMenu(
            menuItem("Insert into the prompt", () => insertSnippet(c.text, false)),
            menuItem("Insert into the negative prompt", () => insertSnippet(c.text, true)),
            menuItem("Remove from the prompt", () => removeSnippet(c.text, negativeTarget.get)),
            menuItem("Edit", () => { cardCategory.setText(c.category); cardName.setText(c.name); cardText.setText(c.text) }))
          b.setContextMenu(menu)
          W.tip(b, c.text)
          flow.getChildren.add(b)
        }
        val pane = new TitledPane(if category.isEmpty then "(no category)" else category, flow)
        pane.setExpanded(true)
        cardsBox.getChildren.add(pane)
      }
      if cards.isEmpty then cardsBox.getChildren.add(W.label("No cards yet. Fill in the fields below and press Save card."))
    catch case NonFatal(e) => say("Cannot read the cards: " + Fx.message(e))
  private def menuItem(text: String, action: () => Unit): MenuItem =
    val m = new MenuItem(text); m.setOnAction(_ => action()); m
  private val saveCard = W.button("Save card", "Write category/name.tag") {
    guard {
      val c = service.cards.save(TagCard(cardCategory.getText, cardName.getText, cardText.getText))
      say(s"Saved card ${c.id}"); reloadCards()
    }
  }
  saveCard.setId("prompt-tools-card-save")
  private val deleteCard = W.button("Delete card", "Delete the card named in the fields") {
    val confirm = new Alert(Alert.AlertType.CONFIRMATION, s"Delete the card '${cardName.getText.trim}'?", ButtonType.CANCEL, ButtonType.OK)
    confirm.initOwner(ctx.stage)
    if confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK then
      guard { if service.cards.delete(TagCard(cardCategory.getText, cardName.getText, "")) then say("Card deleted") else say("No such card"); reloadCards() }
  }
  deleteCard.setId("prompt-tools-card-delete")
  private val cardsPage = new VBox(6,
    W.label("Click a card to insert it. Right-click for more (including Edit).", "muted"),
    W.vgrow(cardsScroll), cardCategory, cardName, cardText, new FlowPane(6, 6, saveCard, deleteCard))
  cardsPage.setPadding(new Insets(8))

  pages.getTabs.addAll(
    page("prompt-tools-saved-tab", "Saved prompts", savedPage),
    page("prompt-tools-history-tab", "History & favourites", historyPage),
    page("prompt-tools-editor-tab", "Tag editor", editorPage),
    page("prompt-tools-tags-tab", "Tag buttons", tagsPage),
    page("prompt-tools-cards-tab", "Tag cards", cardsPage))
  bindPreview()
