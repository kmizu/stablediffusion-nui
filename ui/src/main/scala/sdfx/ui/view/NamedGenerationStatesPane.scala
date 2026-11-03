package sdfx.ui.view

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.image.{Image, ImageView}
import javafx.scene.layout.{FlowPane, HBox, Priority, Region, VBox}
import java.nio.file.Files
import java.nio.file.{LinkOption, StandardOpenOption}
import sdfx.core.presets.*
import sdfx.ui.Fx
import sdfx.ui.presets.{GenerationHistory, GenerationHistoryStore, NamedGenerationStates, NativePresetFields, NativePresetsController, NativePresetsSession, StateCategory}
import sdfx.ui.vm.Mode

/** Separate full-state library plus automatic history; selected-field presets are never read or mutated here. */
final class NamedGenerationStatesPane(
    ctx: UiContext,
    session: NativePresetsSession,
    fields: Map[Mode, NativePresetFields],
    controllers: Map[Mode, NativePresetsController],
    history: GenerationHistoryStore
):
  private var mode = Mode.Txt2Img
  private var loaded: Option[LoadedPresets] = None
  private var pending = false
  private var readOnly = true
  private var closed = false
  private val heading = W.label("Named generation states", "section-title")
  private val scope = W.label(NamedGenerationStates.scope, "hint")
  scope.setWrapText(true)
  private val list = new ListView[String]()
  list.setId("generation-states-list")
  list.setPrefHeight(150)
  private val name = new TextField()
  name.setId("generation-states-name")
  name.setPromptText("Name this complete settings snapshot")
  private val detail = new TextArea()
  detail.setId("generation-states-detail")
  detail.setEditable(false)
  detail.setWrapText(true)
  private val fieldPicker = new ComboBox[String]()
  private var fieldLabels = Map.empty[String, String]
  fieldPicker.setConverter(new javafx.util.StringConverter[String]:
    override def toString(value: String): String =
      if value == null then "" else fieldLabels.get(value).fold(value)(label => s"$label · $value")
    override def fromString(value: String): String = value)
  fieldPicker.setId("generation-states-field")
  fieldPicker.setPromptText("Choose a saved setting")
  fieldPicker.setPrefWidth(360)
  fieldPicker.setMinWidth(120)
  fieldPicker.setMaxWidth(Double.MaxValue)
  HBox.setHgrow(fieldPicker, Priority.ALWAYS)
  private val status = new TextArea()
  status.setId("generation-states-status")
  status.setEditable(false)
  status.setWrapText(true)
  status.setPrefRowCount(3)

  private val historyList = new ListView[PresetRecord]()
  historyList.setId("generation-history-list")
  historyList.setPrefHeight(150)
  historyList.getSelectionModel.setSelectionMode(SelectionMode.MULTIPLE)
  private val favouritesList = new ListView[PresetRecord]()
  favouritesList.setId("generation-favourites-list")
  favouritesList.setPrefHeight(150)
  favouritesList.getSelectionModel.setSelectionMode(SelectionMode.MULTIPLE)
  private val historySearch = new TextField()
  historySearch.setId("generation-history-search")
  historySearch.setPromptText("Search name, prompts, checkpoint or sampler; separate terms with commas")
  private val favouritesSearch = new TextField()
  favouritesSearch.setId("generation-favourites-search")
  favouritesSearch.setPromptText("Search name, prompts, checkpoint or sampler; separate terms with commas")
  private val autosave = new CheckBox("Record every generation automatically (newest 50 per mode)")
  autosave.setId("generation-history-auto")
  autosave.setSelected(history.autosave)
  private var historyEntries = Vector.empty[PresetRecord]
  private var favouriteEntries = Vector.empty[PresetRecord]
  private var historyPending = false
  private var historyLoading = false
  private var historyRequest = 0L
  private val categoryBoxes = StateCategory.values.toVector.map { c =>
    val box = new CheckBox(c.label)
    box.setId("generation-states-cat-" + c.id)
    box.setSelected(true)
    c -> box
  }
  private def chosenCategories: Set[StateCategory] = categoryBoxes.filter(_._2.isSelected).map(_._1).toSet
  private val tabs = new TabPane()
  tabs.setId("generation-states-tabs")
  private def historyActive: Boolean = tabs.getSelectionModel.getSelectedIndex != 0
  private def selectedHistoryList: ListView[PresetRecord] =
    if tabs.getSelectionModel.getSelectedIndex == 2 then favouritesList else historyList

  private def namedSelected: Option[PresetRecord] = for
    state <- loaded
    selectedName <- Option(list.getSelectionModel.getSelectedItem)
    record <- NamedGenerationStates.collection(state.library, mode).presets.find(_.name == selectedName)
  yield record
  private def historySelected: Vector[PresetRecord] =
    selectedHistoryList.getSelectionModel.getSelectedItems.toArray.toVector.collect { case r: PresetRecord => r }
  /** The record the shared Restore button and the detail view act on. */
  private def selected: Option[PresetRecord] =
    if historyActive then historySelected match
      case Vector(one) => Some(one)
      case _ => None
    else namedSelected

  /** None means every category (full restore); otherwise only the checked categories' fields. */
  private def scopeOf(record: PresetRecord): Option[Set[FieldKey]] =
    val chosen = chosenCategories
    if chosen.size == StateCategory.values.length then None else Some(StateCategory.keys(record, chosen))

  private def confirm(title: String, message: String): Boolean =
    val alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL)
    alert.initOwner(ctx.stage)
    alert.setTitle(title)
    alert.setHeaderText(title)
    alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK

  private def confirmImport(message: String): Boolean =
    val alert = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.OK, ButtonType.CANCEL)
    alert.initOwner(ctx.stage)
    alert.setTitle("Import State Manager history?")
    alert.setHeaderText("Review supported and unsupported settings")
    val summary = new TextArea(message)
    summary.setId("state-manager-import-summary")
    summary.setEditable(false)
    summary.setWrapText(true)
    summary.setPrefColumnCount(75)
    summary.setPrefRowCount(16)
    alert.getDialogPane.setContent(summary)
    alert.setResizable(true)
    alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK

  private def failure(error: PresetError): Unit = status.setText(NamedGenerationStates.describe(error))
  private def refreshButtons(): Unit =
    save.setDisable(pending || readOnly || loaded.isEmpty)
    restore.setDisable(pending || (historyActive && (historyPending || historyLoading)) || selected.isEmpty)
    restoreField.setDisable(pending || (historyActive && (historyPending || historyLoading)) || selected.isEmpty || fieldPicker.getValue == null)
    delete.setDisable(pending || readOnly || namedSelected.isEmpty)
    keep.setDisable(pending || historyPending || historyLoading || readOnly || loaded.isEmpty || historySelected.size != 1)
    historyDelete.setDisable(historyPending || historyLoading || historySelected.isEmpty)
    favouritesDelete.setDisable(historyPending || historyLoading || historySelected.isEmpty)
    historyFavourite.setDisable(historyPending || historyLoading || historySelected.isEmpty)
    favouritesToggle.setDisable(historyPending || historyLoading || historySelected.isEmpty)
    historyRename.setDisable(historyPending || historyLoading || historySelected.size != 1)
    favouritesRename.setDisable(historyPending || historyLoading || historySelected.size != 1)
    favouriteCapture.setDisable(historyPending || historyLoading || pending)
    historyClear.setDisable(historyPending || historyLoading || historyEntries.isEmpty)
    historyImport.setDisable(historyPending || historyLoading)
    reload.setDisable(pending)
    backup.setDisable(pending)

  private def showSelected(): Unit =
    val previousField = Option(fieldPicker.getValue)
    val fieldNames = selected.toVector.flatMap(_.fields.keys.toVector.map(_.render).sorted)
    fieldLabels = fields(mode).schema.fields.map(spec => spec.key.render -> spec.label).toMap
    fieldPicker.setValue(null)
    fieldPicker.getItems.setAll(fieldNames*)
    previousField.filter(fieldNames.contains).foreach(fieldPicker.setValue)
    selected match
      case None => detail.clear()
      case Some(record) =>
        if !historyActive then name.setText(record.name)
        val warnings = NamedGenerationStates.preflight(record, fields(mode), scopeOf(record)).left.toOption
          .map(e => "Category restore blocked; current settings remain unchanged. Individual settings may still be restored:\n" + NamedGenerationStates.describe(e) + "\n\n").getOrElse("")
        detail.setText(warnings + record.fields.toVector.sortBy(_._1.render)
          .map((key, value) => s"${key.render}: $value").mkString("\n"))
    refreshButtons()

  private val save = W.button("Save named state", "Capture all supported controls for this mode") {
    val targetMode = mode
    ctx.commitPresetEditors(targetMode) match
      case Left(error) => status.setText(error)
      case Right(_) => NamedGenerationStates.capture(name.getText, fields(targetMode)) match
        case Left(error) => failure(error)
        case Right(record) => loaded.foreach { state =>
          val exists = NamedGenerationStates.collection(state.library, targetMode).presets.exists(_.name == record.name)
          if !exists || confirm("Replace named state?", s"Replace '${record.name}' with the current ${targetMode.key} settings?") then
            pending = true
            refreshButtons()
            session.save(state.revision, NamedGenerationStates.put(state.library, targetMode, record))
        }
  }
  save.setId("generation-states-save")
  private def restoreRecord(record: PresetRecord, only: Option[Set[FieldKey]], scopeNote: String,
      selectedField: Option[String] = None): Unit =
    val targetMode = mode
    val keys = only.getOrElse(record.fields.keySet)
    if keys.isEmpty then status.setText("The selected categories contain no saved fields; nothing to restore.")
    else controllers(targetMode).admitApply() match
      case Left(error) => failure(error)
      case Right(_) => NamedGenerationStates.preflight(record, fields(targetMode), only) match
        case Left(error) => failure(error)
        case Right(_) =>
          // Imported State Manager records contain only mapped fields, never a complete native form.
          val imported = record.source.exists(_.format == "state-manager-v2")
          val partial = only.nonEmpty || imported
          val sharedNote = if keys.exists(_.segments.headOption.contains("model")) then
            "Shared checkpoint/VAE/clip skip also change.\n" else ""
          val importNote = if imported then "Only mapped imported settings change; unsupported settings stay as they are.\n" else ""
          if confirm("Restore state?", s"Replace current ${targetMode.key} settings with '${record.name}'?\n$scopeNote${sharedNote}${importNote}Current init image and painted mask stay in place.") then
            // Confirmations run a nested event loop: repeat admission and preflight afterward.
            val result = for
              _ <- Either.cond(mode == targetMode && selected.contains(record) &&
                selectedField.forall(value => Option(fieldPicker.getValue).contains(value)), (),
                PresetError(Vector(PresetIssue("stale-selection", targetMode.key, "The selected state or mode changed during confirmation"))))
              _ <- controllers(targetMode).admitApply()
              _ <- NamedGenerationStates.preflight(record, fields(targetMode), only)
              _ <- if partial then Right(()) else ctx.commitPresetEditors(targetMode).left.map(message =>
                PresetError(Vector(PresetIssue("editor", targetMode.key, message))))
              plan <- controllers(targetMode).apply(record, keys, ApplyBase.Current)
            yield plan
            result match
              case Left(error) => failure(error)
              case Right(_) =>
                if partial then ctx.resetPresetEditorDrafts(targetMode, fields(targetMode).numericEditorOwners(keys))
                val what = if partial then s"${keys.size} selected fields of '${record.name}'" else s"'${record.name}'"
                status.setText(s"Restored $what. Init image and painted mask were kept; no generation was started.")
  private val restore = W.button("Restore state", "Restore the saved settings (all, or only the checked categories) after checking all resources") {
    selected.foreach { record =>
      val only = scopeOf(record)
      val scopeNote = if only.nonEmpty then s"Only: ${chosenCategories.toVector.sortBy(_.ordinal).map(_.label).mkString(", ")}.\n" else ""
      restoreRecord(record, only, scopeNote)
    }
  }
  restore.setId("generation-states-restore")
  private val restoreField = W.button("Restore selected setting", "Restore just one saved setting after checking its resources") {
    selected.foreach { record =>
      Option(fieldPicker.getValue).flatMap(name => record.fields.keys.find(_.render == name)).foreach { key =>
        restoreRecord(record, Some(Set(key)), s"Only: ${key.render}.\n", Some(key.render))
      }
    }
  }
  restoreField.setId("generation-states-restore-field")
  restoreField.setMinWidth(Region.USE_PREF_SIZE)
  private val delete = W.button("Delete", "Delete the selected named state") {
    val targetMode = mode
    namedSelected.foreach { record => loaded.foreach { state =>
      if confirm("Delete named state?", s"Delete '${record.name}' from ${targetMode.key} named states?") then
        pending = true
        refreshButtons()
        session.save(state.revision, NamedGenerationStates.remove(state.library, targetMode, record.name))
    }}
  }
  delete.setId("generation-states-delete")
  private val reload = W.button("Reload", "Reload the library after an external change or error") {
    pending = true
    refreshButtons()
    session.reload()
    refreshHistory()
  }
  private val backup = W.button("Restore backup", "Recover the previous saved library") {
    if confirm("Restore library backup?", "Replace the entire named-state library with its previous saved version?") then
      pending = true
      refreshButtons()
      session.readLibraryRevision {
        case Left(error) =>
          pending = false
          failure(error)
          refreshButtons()
        case Right(revision) => session.restoreBackup(revision)
      }
  }

  private val keep = W.button("Keep as named state", "Copy the selected history entry into the named states under the name above") {
    val targetMode = mode
    historySelected match
      case Vector(entry) => loaded.foreach { state =>
        val clean = name.getText.trim
        if clean.isEmpty then status.setText("Enter a name above to keep this history entry.")
        else NamedGenerationStates.capture(clean, fields(targetMode)).map(_ => entry.copy(name = clean)) match
          case Left(error) => failure(error)
          case Right(record) =>
            val exists = NamedGenerationStates.collection(state.library, targetMode).presets.exists(_.name == clean)
            if !exists || confirm("Replace named state?", s"Replace '$clean' with this history entry?") then
              pending = true
              refreshButtons()
              session.save(state.revision, NamedGenerationStates.put(state.library, targetMode,
                record.copy(id = java.util.UUID.randomUUID())))
      }
      case _ => status.setText("Select exactly one history entry to keep.")
  }
  keep.setId("generation-history-keep")
  private def deleteHistorySelection(): Unit =
    val ids = historySelected.map(_.id).toSet
    val target = mode
    if ids.nonEmpty && confirm("Delete history entries?", s"Delete ${ids.size} entr${if ids.size == 1 then "y" else "ies"} from $target history and favourites?") then
      historyPending = true
      refreshButtons()
      history.delete(target, ids) { result =>
        historyPending = false
        result match
          case Left(error) => failure(error)
          case Right(_) => status.setText(s"Deleted ${ids.size} history entries.")
        refreshButtons()
      }
  private val historyDelete = W.button("Delete selected", "Delete the selected entries from history and favourites") {
    deleteHistorySelection()
  }
  historyDelete.setId("generation-history-delete")
  private val favouritesDelete = W.button("Delete selected", "Delete the selected entries from history and favourites") {
    deleteHistorySelection()
  }
  favouritesDelete.setId("generation-favourites-delete")
  private def toggleSelectedFavourites(): Unit =
    val ids = historySelected.map(_.id).toSet
    val target = mode
    if ids.nonEmpty then
      historyPending = true
      refreshButtons()
      history.toggleFavourites(target, ids) { result =>
        historyPending = false
        result match
          case Left(error) => failure(error)
          case Right(_) => status.setText(s"Updated favourites for ${ids.size} selected entries.")
        refreshButtons()
      }
  private val historyFavourite = W.button("Toggle favourites", "Add all selected entries, or remove all if already favourites") { toggleSelectedFavourites() }
  historyFavourite.setId("generation-history-favourite")
  private val favouritesToggle = W.button("Remove favourites", "Remove the selected entries from favourites") { toggleSelectedFavourites() }
  favouritesToggle.setId("generation-favourites-toggle")
  private def renameSelectedHistory(): Unit =
    historySelected match
      case Vector(record) =>
        val target = mode
        val dialog = new TextInputDialog(record.name)
        dialog.initOwner(ctx.stage)
        dialog.setTitle("Rename history entry")
        dialog.setHeaderText("Rename the selected entry")
        dialog.setContentText("Name:")
        val answer = dialog.showAndWait()
        if answer.isPresent && !closed then
          if mode != target || historySelected != Vector(record) then
            status.setText("The selected entry or mode changed; nothing was renamed.")
          else
            historyPending = true
            refreshButtons()
            history.rename(target, record.id, record.name, answer.get()) { result =>
              historyPending = false
              if !closed then
                result match
                  case Left(error) => failure(error)
                  case Right(_) => status.setText("History entry renamed.")
                refreshButtons()
            }
      case _ => ()
  private val historyRename = W.button("Rename", "Rename the selected entry in history and favourites") { renameSelectedHistory() }
  historyRename.setId("generation-history-rename")
  private val favouritesRename = W.button("Rename", "Rename the selected entry in history and favourites") { renameSelectedHistory() }
  favouritesRename.setId("generation-favourites-rename")
  private val favouriteCapture = W.button("Save current as favourite", "Commit the numeric editors and save using the name below; no image is generated. Committed values remain applied if saving fails.") {
    val target = mode
    ctx.commitPresetEditors(target) match
      case Left(error) => status.setText(error)
      case Right(_) => NamedGenerationStates.capture(name.getText, fields(target)) match
        case Left(error) => failure(error)
        case Right(record) =>
          historyPending = true
          refreshButtons()
          history.saveFavourite(target, record) { result =>
            historyPending = false
            if !closed then
              result match
                case Left(error) => failure(error)
                case Right(_) => status.setText(s"Saved '${record.name}' to ${target.key} favourites; no image was generated.")
              refreshButtons()
          }
  }
  favouriteCapture.setId("generation-favourites-capture")
  private val historyClear = W.button("Clear history", "Remove non-favourite history entries of this mode") {
    val target = mode
    if confirm("Clear history?", s"Delete all non-favourite ${target.key} history entries, including those hidden by search? Favourites are kept.") then
      historyPending = true
      refreshButtons()
      history.clear(target) { result =>
        historyPending = false
        result match
          case Left(error) => failure(error)
          case Right(_) => status.setText(s"Cleared non-favourite ${target.key} history entries.")
        refreshButtons()
      }
  }
  historyClear.setId("generation-history-clear")

  private val historyImport = W.button("Import State Manager V2…", "Preview a gzip history.txt before importing supported settings") {
    ctx.chooseStateManagerImport().foreach { path =>
      historyPending = true
      refreshButtons()
      Fx.async {
        val limits = PresetLimits()
        val input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        val bytes = try input.readNBytes(limits.maxBytes + 1) finally input.close()
        StateManagerV2.decode(bytes, limits)
      } { decoded =>
        historyPending = false
        if !closed then decoded match
          case Left(error) => failure(error); refreshButtons()
          case Right(entries) if entries.isEmpty => status.setText("The source contains no entries."); refreshButtons()
          case Right(entries) =>
            val missing = entries.flatMap(_.unmapped).distinct.sorted
            val shown = missing.take(40)
            val more = if missing.size > shown.size then s"\n… and ${missing.size - shown.size} more" else ""
            val warning = if missing.isEmpty then "None" else shown.mkString("\n") + more
            val mapped = entries.flatMap(_.mapped.keys.map(_.render)).distinct.sorted
            val supported = if mapped.isEmpty then "None" else mapped.mkString(", ")
            val message = s"Import ${entries.size} entries from ${path.getFileName}?\n" +
              "Only supported fields are restored; the supported fields differ by entry. " +
              "Original entries and defaults, including any private settings or preview images, are copied into local history metadata. " +
              "Embedded PNG previews up to 100 × 100 pixels may be copied as thumbnails; external URLs are not fetched. " +
              "Imported history is treated as older than existing history and may be evicted first. " +
              "If history capacity or storage limits would be exceeded, nothing is imported.\n\n" +
              s"Supported fields across all entries: $supported\n\n" +
              s"Unsupported or invalid settings (${missing.size}; preserved but not restored):\n$warning"
            if confirmImport(message) && !closed then
              historyPending = true
              refreshButtons()
              history.importV2WithPreviews(entries) { result =>
                historyPending = false
                if !closed then
                  result match
                    case Left(error) => failure(error)
                    case Right(summary) => status.setText(s"Imported ${entries.size} source entries; existing matching entries were kept. " +
                      s"Preview thumbnails saved: ${summary.previewsSaved}; preview values skipped (unsupported or failed): ${summary.previewsSkipped}.")
                  refreshButtons()
              }
            else refreshButtons()
      } { error =>
        historyPending = false
        if !closed then { status.setText("Could not read State Manager history: " + Fx.message(error)); refreshButtons() }
      }
    }
  }
  historyImport.setId("generation-history-import")

  autosave.selectedProperty.addListener((_, _, on) =>
    history.setAutosave(on) match
      case Left(error) =>
        failure(error)
        autosave.setSelected(!on)
      case Right(_) => status.setText(if on then "History recording is on." else "History recording is off; existing entries stay."))
  list.getSelectionModel.selectedItemProperty.addListener((_, _, _) => showSelected())
  historyList.getSelectionModel.selectedItemProperty.addListener((_, _, _) => showSelected())
  favouritesList.getSelectionModel.selectedItemProperty.addListener((_, _, _) => showSelected())
  historyList.getSelectionModel.getSelectedItems.addListener(
    new javafx.collections.ListChangeListener[PresetRecord]:
      def onChanged(c: javafx.collections.ListChangeListener.Change[? <: PresetRecord]): Unit = refreshButtons())
  favouritesList.getSelectionModel.getSelectedItems.addListener(
    new javafx.collections.ListChangeListener[PresetRecord]:
      def onChanged(c: javafx.collections.ListChangeListener.Change[? <: PresetRecord]): Unit = refreshButtons())
  tabs.getSelectionModel.selectedItemProperty.addListener((_, _, _) => showSelected())
  categoryBoxes.foreach(_._2.selectedProperty.addListener((_, _, _) => showSelected()))
  fieldPicker.valueProperty.addListener((_, _, _) => refreshButtons())

  private def historyCell(): ListCell[PresetRecord] = new ListCell[PresetRecord]:
    override def updateItem(item: PresetRecord, empty: Boolean): Unit =
      super.updateItem(item, empty)
      if empty || item == null then
        setText(null)
        setGraphic(null)
      else
        val prompt = item.fields.get(FieldKey(Vector("generation", "prompt"))).collect { case PresetAtom.Text(t) => t }
          .getOrElse("").replace('\n', ' ').take(70)
        val heart = if favouriteEntries.exists(_.id == item.id) then "♥ " else ""
        setText(s"$heart${item.name}\n$prompt")
        val file = history.thumbnailIfSafe(item.id)
        setGraphic(file.map { path =>
          val view = new ImageView(new Image(path.toUri.toString))
          view.setFitHeight(48)
          view.setFitWidth(48)
          view.setPreserveRatio(true)
          view
        }.orNull)
  historyList.setCellFactory(_ => historyCell())
  favouritesList.setCellFactory(_ => historyCell())

  private val namedTab = new Tab("Named states", new VBox(8, list))
  namedTab.setId("generation-states-tab-named")
  namedTab.setClosable(false)
  private val historyTab = new Tab("History", new VBox(8, autosave, historySearch, historyList,
    new FlowPane(8, 6, keep, historyRename, historyFavourite, historyDelete, historyClear, historyImport)))
  historyTab.setId("generation-states-tab-history")
  historyTab.setClosable(false)
  private val favouritesTab = new Tab("Favourites", new VBox(8, favouritesSearch, favouritesList, new FlowPane(8, 6, favouriteCapture, favouritesRename, favouritesToggle, favouritesDelete)))
  favouritesTab.setId("generation-states-tab-favourites")
  favouritesTab.setClosable(false)
  tabs.getTabs.addAll(namedTab, historyTab, favouritesTab)

  private val categories = new FlowPane(12, 4)
  categories.getChildren.addAll(categoryBoxes.map(_._2)*)
  private val actions = new HBox(8, save, restore, delete, reload, backup)
  val node = new VBox(10, heading, scope,
    W.label("Unlike Settings presets, a named state always captures all supported settings for one mode.", "hint"),
    tabs, name, W.label("Restore only these categories (all checked = complete state):", "hint"), categories,
    actions, new HBox(8, fieldPicker, restoreField), detail, status)
  node.setPadding(new Insets(16))
  VBox.setVgrow(detail, Priority.ALWAYS)
  history.onChange(() => refreshHistory())
  historySearch.textProperty.addListener((_, _, _) => applyHistoryFilter())
  favouritesSearch.textProperty.addListener((_, _, _) => applyHistoryFilter())
  refreshButtons()

  def openMode(value: Mode): Unit =
    if mode != value then
      historyEntries = Vector.empty
      favouriteEntries = Vector.empty
      historyList.getSelectionModel.clearSelection()
      favouritesList.getSelectionModel.clearSelection()
      historyList.getItems.clear()
      favouritesList.getItems.clear()
    mode = value
    heading.setText(s"Named generation states — ${mode.key}")
    refreshList()
    refreshHistory()

  private def refreshHistory(): Unit =
    val target = mode
    historyRequest += 1
    val request = historyRequest
    historyLoading = true
    refreshButtons()
    history.listGroups(target) {
      case result if !closed && target == mode && request == historyRequest =>
        historyLoading = false
        result match
          case Left(error) =>
            historyEntries = Vector.empty
            favouriteEntries = Vector.empty
            applyHistoryFilter()
            failure(error)
          case Right((entries, favourites)) =>
            historyEntries = entries.reverse
            favouriteEntries = favourites.reverse
            applyHistoryFilter()
      case _ => ()
    }

  private def applyHistoryFilter(): Unit =
    def update(list: ListView[PresetRecord], records: Vector[PresetRecord], query: String): Unit =
      val selected = list.getSelectionModel.getSelectedItems.toArray.toVector.collect { case r: PresetRecord => r.id }.toSet
      val visible = records.filter(GenerationHistory.matchesSearch(_, query))
      list.getItems.setAll(visible*)
      visible.filter(r => selected(r.id)).foreach(list.getSelectionModel.select)
    update(historyList, historyEntries, historySearch.getText)
    update(favouritesList, favouriteEntries, favouritesSearch.getText)
    showSelected()

  private def refreshList(): Unit =
    val old = Option(list.getSelectionModel.getSelectedItem)
    val names = loaded.toVector.flatMap(state => NamedGenerationStates.collection(state.library, mode).presets.map(_.name)).sorted
    list.getItems.setAll(names*)
    old.filter(names.contains).foreach(list.getSelectionModel.select)
    showSelected()

  def acceptResult(result: Either[PresetError, LoadedPresets]): Unit =
    if !closed then
      pending = false
      result match
        case Left(error) =>
          readOnly = true
          failure(error)
        case Right(value) =>
          loaded = Some(value)
          readOnly = false
          status.setText(s"Library ready: ${session.directoryPath}. Input files are referenced, not archived.")
      refreshList()

  def dispose(): Unit = closed = true
