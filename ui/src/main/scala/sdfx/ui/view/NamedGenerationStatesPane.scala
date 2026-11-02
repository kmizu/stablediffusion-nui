package sdfx.ui.view

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.image.{Image, ImageView}
import javafx.scene.layout.{FlowPane, HBox, Priority, VBox}
import java.nio.file.Files
import sdfx.core.presets.*
import sdfx.ui.presets.{GenerationHistoryStore, NamedGenerationStates, NativePresetFields, NativePresetsController, NativePresetsSession, StateCategory}
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
  private val status = new TextArea()
  status.setId("generation-states-status")
  status.setEditable(false)
  status.setWrapText(true)
  status.setPrefRowCount(3)

  private val historyList = new ListView[PresetRecord]()
  historyList.setId("generation-history-list")
  historyList.setPrefHeight(150)
  historyList.getSelectionModel.setSelectionMode(SelectionMode.MULTIPLE)
  private val autosave = new CheckBox("Record every generation automatically (newest 50 per mode)")
  autosave.setId("generation-history-auto")
  autosave.setSelected(history.autosave)
  private var historyEntries = Vector.empty[PresetRecord]
  private val categoryBoxes = StateCategory.values.toVector.map { c =>
    val box = new CheckBox(c.label)
    box.setId("generation-states-cat-" + c.id)
    box.setSelected(true)
    c -> box
  }
  private def chosenCategories: Set[StateCategory] = categoryBoxes.filter(_._2.isSelected).map(_._1).toSet
  private val tabs = new TabPane()
  tabs.setId("generation-states-tabs")
  private def historyActive: Boolean = tabs.getSelectionModel.getSelectedIndex == 1

  private def namedSelected: Option[PresetRecord] = for
    state <- loaded
    selectedName <- Option(list.getSelectionModel.getSelectedItem)
    record <- NamedGenerationStates.collection(state.library, mode).presets.find(_.name == selectedName)
  yield record
  private def historySelected: Vector[PresetRecord] =
    historyList.getSelectionModel.getSelectedItems.toArray.toVector.collect { case r: PresetRecord => r }
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

  private def failure(error: PresetError): Unit = status.setText(NamedGenerationStates.describe(error))
  private def refreshButtons(): Unit =
    save.setDisable(pending || readOnly || loaded.isEmpty)
    restore.setDisable(pending || selected.isEmpty)
    delete.setDisable(pending || readOnly || namedSelected.isEmpty)
    keep.setDisable(pending || readOnly || loaded.isEmpty || historySelected.size != 1)
    historyDelete.setDisable(historySelected.isEmpty)
    historyClear.setDisable(historyEntries.isEmpty)
    reload.setDisable(pending)
    backup.setDisable(pending)

  private def showSelected(): Unit =
    selected match
      case None => detail.clear()
      case Some(record) =>
        if !historyActive then name.setText(record.name)
        val warnings = NamedGenerationStates.preflight(record, fields(mode), scopeOf(record)).left.toOption
          .map(e => "Restore blocked; current settings remain unchanged:\n" + NamedGenerationStates.describe(e) + "\n\n").getOrElse("")
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
  private val restore = W.button("Restore state", "Restore the saved settings (all, or only the checked categories) after checking all resources") {
    val targetMode = mode
    selected.foreach { record =>
      val only = scopeOf(record)
      val keys = only.getOrElse(record.fields.keySet)
      if keys.isEmpty then status.setText("The selected categories contain no saved fields; nothing to restore.")
      else controllers(targetMode).admitApply() match
        case Left(error) => failure(error)
        case Right(_) => NamedGenerationStates.preflight(record, fields(targetMode), only) match
          case Left(error) => failure(error)
          case Right(_) =>
            val partial = only.nonEmpty
            val scopeNote = if partial then
              s"Only: ${chosenCategories.toVector.sortBy(_.ordinal).map(_.label).mkString(", ")}.\n" else ""
            val sharedNote = if keys.exists(_.segments.headOption.contains("model")) then
              "Shared checkpoint/VAE/clip skip also change.\n" else ""
            if confirm("Restore state?", s"Replace current ${targetMode.key} settings with '${record.name}'?\n$scopeNote${sharedNote}Current init image and painted mask stay in place.") then
              // Confirmations run a nested event loop: repeat admission and preflight afterward.
              val result = for
                _ <- controllers(targetMode).admitApply()
                _ <- NamedGenerationStates.preflight(record, fields(targetMode), only)
                _ <- ctx.commitPresetEditors(targetMode).left.map(message =>
                  PresetError(Vector(PresetIssue("editor", targetMode.key, message))))
                plan <- controllers(targetMode).apply(record, keys, ApplyBase.Current)
              yield plan
              result match
                case Left(error) => failure(error)
                case Right(_) =>
                  val what = if partial then s"${keys.size} selected fields of '${record.name}'" else s"'${record.name}'"
                  status.setText(s"Restored $what. Init image and painted mask were kept; no generation was started.")
    }
  }
  restore.setId("generation-states-restore")
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
  private val historyDelete = W.button("Delete selected", "Remove the selected history entries") {
    val ids = historySelected.map(_.id).toSet
    if ids.nonEmpty && confirm("Delete history entries?", s"Delete ${ids.size} history entr${if ids.size == 1 then "y" else "ies"} from ${mode.key}?") then
      history.delete(mode, ids) {
        case Left(error) => failure(error)
        case Right(_) => status.setText(s"Deleted ${ids.size} history entries.")
      }
  }
  historyDelete.setId("generation-history-delete")
  private val historyClear = W.button("Clear history", "Remove every history entry of this mode") {
    if confirm("Clear history?", s"Delete all ${historyEntries.size} ${mode.key} history entries?") then
      history.clear(mode) {
        case Left(error) => failure(error)
        case Right(_) => status.setText(s"Cleared ${mode.key} history.")
      }
  }
  historyClear.setId("generation-history-clear")

  autosave.selectedProperty.addListener((_, _, on) =>
    history.setAutosave(on) match
      case Left(error) =>
        failure(error)
        autosave.setSelected(!on)
      case Right(_) => status.setText(if on then "History recording is on." else "History recording is off; existing entries stay."))
  list.getSelectionModel.selectedItemProperty.addListener((_, _, _) => showSelected())
  historyList.getSelectionModel.selectedItemProperty.addListener((_, _, _) => showSelected())
  historyList.getSelectionModel.getSelectedItems.addListener(
    new javafx.collections.ListChangeListener[PresetRecord]:
      def onChanged(c: javafx.collections.ListChangeListener.Change[? <: PresetRecord]): Unit = refreshButtons())
  tabs.getSelectionModel.selectedItemProperty.addListener((_, _, _) => showSelected())
  categoryBoxes.foreach(_._2.selectedProperty.addListener((_, _, _) => showSelected()))

  historyList.setCellFactory(_ => new ListCell[PresetRecord]:
    override def updateItem(item: PresetRecord, empty: Boolean): Unit =
      super.updateItem(item, empty)
      if empty || item == null then
        setText(null)
        setGraphic(null)
      else
        val prompt = item.fields.get(FieldKey(Vector("generation", "prompt"))).collect { case PresetAtom.Text(t) => t }
          .getOrElse("").replace('\n', ' ').take(70)
        setText(s"${item.name}\n$prompt")
        val file = history.thumbnail(item.id)
        setGraphic(if Files.isRegularFile(file) then
          val view = new ImageView(new Image(file.toUri.toString))
          view.setFitHeight(48)
          view.setFitWidth(48)
          view.setPreserveRatio(true)
          view
        else null))

  private val namedTab = new Tab("Named states", new VBox(8, list))
  namedTab.setId("generation-states-tab-named")
  namedTab.setClosable(false)
  private val historyTab = new Tab("History", new VBox(8, autosave, historyList,
    new HBox(8, keep, historyDelete, historyClear)))
  historyTab.setId("generation-states-tab-history")
  historyTab.setClosable(false)
  tabs.getTabs.addAll(namedTab, historyTab)

  private val categories = new FlowPane(12, 4)
  categories.getChildren.addAll(categoryBoxes.map(_._2)*)
  private val actions = new HBox(8, save, restore, delete, reload, backup)
  val node = new VBox(10, heading, scope,
    W.label("Unlike Settings presets, a named state always captures all supported settings for one mode.", "hint"),
    tabs, name, W.label("Restore only these categories (all checked = complete state):", "hint"), categories,
    actions, detail, status)
  node.setPadding(new Insets(16))
  VBox.setVgrow(detail, Priority.ALWAYS)
  history.onChange(() => refreshHistory())
  refreshButtons()

  def openMode(value: Mode): Unit =
    mode = value
    heading.setText(s"Named generation states — ${mode.key}")
    refreshList()
    refreshHistory()

  private def refreshHistory(): Unit =
    val target = mode
    history.list(target) {
      case Left(error) => if !closed then failure(error)
      case Right(entries) => if !closed && target == mode then
        val old = historySelected.map(_.id).toSet
        historyEntries = entries.reverse
        historyList.getItems.setAll(historyEntries*)
        historyEntries.filter(e => old(e.id)).foreach(historyList.getSelectionModel.select)
        showSelected()
    }

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
