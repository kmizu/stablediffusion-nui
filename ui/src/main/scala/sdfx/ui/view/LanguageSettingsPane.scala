package sdfx.ui.view

import javafx.beans.property.{SimpleBooleanProperty, SimpleStringProperty}
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.{HBox, Priority, VBox}
import javafx.stage.{DirectoryChooser, FileChooser, Stage}
import javafx.util.StringConverter
import sdfx.core.config.LocalizationPreferences
import sdfx.core.i18n.*
import sdfx.ui.Fx
import sdfx.ui.i18n.LocalizationController

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Settings > Language: pick the interface language pack(s), the bilingual layout, extra pack folders, import and
  * download packs, and export the strings that still lack a translation.
  */
final class LanguageSettingsPane(owner: Stage, initial: LocalizationPreferences, support: Option[LocalizationController]):
  private final case class Choice(id: String, label: String)
  private val none = Choice("", "None (English)")
  private val catalog: Option[PackCatalog] = support.map(_.catalog)

  private val pack = new ComboBox[Choice]()
  private val fallback = new ComboBox[Choice]()
  for c <- Seq(pack, fallback) do
    c.setMaxWidth(Double.MaxValue)
    c.setConverter(new StringConverter[Choice]:
      override def toString(x: Choice): String = if x == null then "" else x.label
      override def fromString(s: String): Choice = null)
  private val style = new ComboBox[String](FXCollections.observableArrayList(DisplayStyle.values.map(_.label).toSeq*))
  private val order = new ComboBox[String](FXCollections.observableArrayList(DisplayOrder.values.map(_.label).toSeq*))
  style.setValue(DisplayStyle.values.find(_.label == initial.style).getOrElse(DisplayStyle.TranslationOnly).label)
  order.setValue(DisplayOrder.values.find(_.label == initial.order).getOrElse(DisplayOrder.TranslationFirst).label)
  style.setMaxWidth(Double.MaxValue)
  order.setMaxWidth(Double.MaxValue)
  private val tooltips = new SimpleBooleanProperty(initial.tooltips)
  private val items = new SimpleBooleanProperty(initial.items)
  private val extraDirs = FXCollections.observableArrayList(initial.extraDirs*)
  private val status = new Label()
  status.setWrapText(true)
  status.getStyleClass.add("hint")
  status.setId("language-status")
  pack.setId("language-pack")
  fallback.setId("language-fallback")
  style.setId("language-style")
  order.setId("language-order")

  private def choices(selected: String): (Seq[Choice], Choice) =
    val listed = catalog.map(_.list().map(i => Choice(i.id, i.display))).getOrElse(Vector.empty)
    val all = none +: listed
    // a stored id whose file is gone stays selectable so Cancel does not silently drop it
    val withMissing = if selected.nonEmpty && !all.exists(_.id == selected) then all :+ Choice(selected, s"$selected (not found)") else all
    (withMissing, withMissing.find(_.id == selected).getOrElse(none))

  private def refresh(): Unit =
    val p = Option(pack.getValue).map(_.id).getOrElse(initial.pack)
    val f = Option(fallback.getValue).map(_.id).getOrElse(initial.fallbackPack)
    val (pc, ps) = choices(p)
    val (fc, fs) = choices(f)
    pack.setItems(FXCollections.observableArrayList(pc*)); pack.setValue(ps)
    fallback.setItems(FXCollections.observableArrayList(fc*)); fallback.setValue(fs)
    describe()

  private def describe(): Unit =
    val id = Option(pack.getValue).map(_.id).getOrElse("")
    val text = (catalog, id) match
      case (_, "") => "English interface: no translation is applied."
      case (None, _) => "Language packs are not available here."
      case (Some(c), _) =>
        c.load(id) match
          case Right(p) =>
            s"${p.plainCount} translations, ${p.regexCount} pattern rules, ${p.scopedCount} scoped rules" +
              (if p.skippedValues > 0 then s", ${p.skippedValues} entries skipped" else "") +
              (if p.warnings.nonEmpty then ". Warnings: " + p.warnings.take(3).mkString("; ") else "") + "."
          case Left(e) => "Cannot use this pack: " + e
    status.setText(text)

  pack.valueProperty.addListener((_, _, _) => describe())

  private def say(message: String): Unit = status.setText(message)

  private val reload = new Button("Reload list")
  reload.setOnAction(_ => refresh())
  private val importBtn = new Button("Import pack file…")
  importBtn.setOnAction { _ =>
    val fc = new FileChooser()
    fc.setTitle("Choose a language pack (.json)")
    fc.getExtensionFilters.add(new FileChooser.ExtensionFilter("Language pack", "*.json"))
    Option(fc.showOpenDialog(owner)).foreach { f =>
      catalog.foreach { c =>
        c.importFile(f.toPath) match
          case Right(info) => refresh(); pack.getItems.asScala.find(_.id == info.id).foreach(pack.setValue); say(s"Imported ${info.id} into ${c.userDir}")
          case Left(e) => say("Import failed: " + e)
      }
    }
  }
  private val downloadBtn = new Button("Download pack…")
  downloadBtn.setId("language-download")
  downloadBtn.setOnAction { _ =>
    catalog.foreach { c =>
      new LanguagePackStoreDialog(owner, c.userDir, installed => { refresh(); pack.getItems.asScala.find(_.id == installed).foreach(pack.setValue) }).show()
    }
  }
  private val exportBtn = new Button("Export untranslated strings…")
  exportBtn.setId("language-export")
  exportBtn.setOnAction { _ =>
    support.foreach { s =>
      val missing = s.localizer.untranslated
      if missing.isEmpty then say("Nothing to export: every text seen so far has a translation (or no language is active).")
      else
        val fc = new FileChooser()
        fc.setTitle("Save untranslated strings")
        fc.setInitialFileName("untranslated.json")
        fc.getExtensionFilters.add(new FileChooser.ExtensionFilter("Language pack template", "*.json"))
        Option(fc.showSaveDialog(owner)).foreach { f =>
          val obj = ujson.Obj.from(missing.map(m => m -> ujson.Str("")))
          Files.writeString(f.toPath, ujson.write(obj, indent = 2) + "\n", StandardCharsets.UTF_8)
          say(s"Wrote ${missing.size} untranslated strings to ${f.getName}; fill in the values and import the file.")
        }
    }
  }
  private def syncExtra(): Unit = support.foreach(_.setExtraDirs(extraDirs.asScala.toSeq))
  private val folders = new ListView[String](extraDirs)
  folders.setPrefHeight(70)
  private val addFolder = new Button("＋ Add folder…")
  addFolder.setOnAction { _ =>
    val dc = new DirectoryChooser()
    dc.setTitle("Folder with language packs (a localizations folder, or an A1111 extension / install folder)")
    Option(dc.showDialog(owner)).map(_.getAbsolutePath).foreach(d => if !extraDirs.contains(d) then { extraDirs.add(d); syncExtra(); refresh() })
  }
  private val removeFolder = new Button("－ Remove")
  removeFolder.setOnAction { _ =>
    val i = folders.getSelectionModel.getSelectedIndex
    if i >= 0 then { extraDirs.remove(i); syncExtra(); refresh() }
  }

  W.tip(pack, "Interface language. Packs are A1111 / reForge localization files (English text -> translation).")
  W.tip(fallback, "Used for texts the main pack does not have (for example a full A1111 pack behind the built-in one).")
  W.tip(style, "Translation only, or show the English text too (two lines or one line).")
  W.tip(order, "Which of the two comes first in the bilingual layouts.")
  W.tip(exportBtn, "Writes the English texts seen in the windows that have no translation, as an empty pack to fill in.")

  refresh()

  val node: VBox =
    val form = W.form()
      .add("Language", pack, "Packs found in the app's localizations folder, plugins-ext/*/localizations, extensions/*/localizations and your folders")
      .add("Fallback pack", fallback)
      .add("Display", style)
      .add("Order", order)
      .full(W.check("Translate tool tips", tooltips))
      .full(W.check("Translate drop-down items (the choice itself is unchanged)", items))
      .full(status)
      .full(W.hbox(6, reload, importBtn, downloadBtn, exportBtn))
      .full(W.label("Extra pack folders", "hint"))
      .full(W.hbox(6, W.hgrow(folders), new VBox(6, addFolder, removeFolder)))
      .full(W.label("Changes apply when you press OK. Dynamic messages need pattern rules (@@/regex/ keys) in the pack.", "hint"))
    val v = new VBox(form.grid)
    v.setPadding(new Insets(14))
    v

  def current: LocalizationPreferences = LocalizationPreferences(
    pack = Option(pack.getValue).map(_.id).getOrElse(""),
    fallbackPack = Option(fallback.getValue).map(_.id).getOrElse(""),
    style = style.getValue, order = order.getValue, tooltips = tooltips.get, items = items.get,
    extraDirs = extraDirs.asScala.toSeq)

/** Lists the pinned upstream packs and installs the chosen one (nothing is downloaded until the user presses Download). */
final class LanguagePackStoreDialog(owner: Stage, destination: Path, installed: String => Unit):
  private val store = PackStore.catalog()
  private val list = new ListView[StorePack](FXCollections.observableArrayList(store*))
  list.setId("language-store-list")
  list.setCellFactory(_ => new ListCell[StorePack]:
    override def updateItem(item: StorePack, empty: Boolean): Unit =
      super.updateItem(item, empty)
      setText(if empty || item == null then null else item.display))
  private val detail = new Label("Select a pack. Packs are downloaded from the author's repository at a pinned commit and verified with a SHA-256 hash.")
  detail.setWrapText(true)
  detail.getStyleClass.add("hint")
  private val result = new Label()
  result.setWrapText(true)
  result.setId("language-store-result")
  private val download = new Button("Download")
  download.setId("language-store-download")
  download.setDisable(true)
  list.getSelectionModel.selectedItemProperty.addListener((_, _, p) =>
    download.setDisable(p == null)
    if p != null then
      detail.setText(s"${p.repo} @ ${p.commit.take(7)}\n${p.path}\n${p.size / 1024} KiB, ${p.entries} entries, licence ${p.license}" +
        (if p.licensePath.isEmpty then "\nNo licence file in the repository: downloaded for your own use only." else "\nThe licence text is saved next to the pack.")))
  download.setOnAction { _ =>
    Option(list.getSelectionModel.getSelectedItem).foreach { p =>
      download.setDisable(true)
      result.setText("Downloading…")
      Fx.async(PackStore.install(p, destination))(r =>
        download.setDisable(false)
        r match
          case Right(path) => result.setText("Saved " + path.getFileName); installed(PackStore.stemOf(path))
          case Left(e) => result.setText("Failed: " + e)
      )(e => { download.setDisable(false); result.setText("Failed: " + Fx.message(e)) })
    }
  }
  def show(): Unit =
    val d = new Dialog[Unit]()
    d.initOwner(owner)
    d.setTitle("Download a language pack")
    d.setHeaderText(null)
    val box = new VBox(8, list, detail, new HBox(8, download), result)
    VBox.setVgrow(list, Priority.ALWAYS)
    box.setPadding(new Insets(10))
    box.setPrefSize(640, 420)
    d.getDialogPane.setContent(box)
    d.getDialogPane.getButtonTypes.add(ButtonType.CLOSE)
    d.getDialogPane.getStylesheets.setAll(owner.getScene.getStylesheets)
    d.show()
