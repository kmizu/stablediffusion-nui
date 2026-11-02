package sdfx.ui.view

import javafx.geometry.Insets
import javafx.collections.FXCollections
import javafx.scene.control.{ButtonBar, ButtonType, CheckBox, ComboBox, Dialog, Label, Tooltip}
import javafx.scene.layout.{FlowPane, HBox, VBox}
import javafx.stage.Window
import sdfx.ui.vm.NegativeSuggestions

/** A fresh, opt-in selection each time; closing or cancelling changes nothing. */
object NegativeSuggestionPicker:
  def create(owner: Window): Dialog[Vector[String]] =
    val dialog = new Dialog[Vector[String]]()
    dialog.initOwner(owner)
    dialog.setTitle("Negative prompt suggestions")
    dialog.setHeaderText("Choose terms to add to the negative prompt")
    val add = new ButtonType("Add selected", ButtonBar.ButtonData.OK_DONE)
    dialog.getDialogPane.getButtonTypes.addAll(add, ButtonType.CANCEL)
    val hint = new Label("Suggestions are examples; their effect depends on the model.")
    hint.setWrapText(true)
    val checks = NegativeSuggestions.items.map { tag =>
      val check = new CheckBox(tag)
      check.setId("negative-candidate-" + tag.replace(' ', '-'))
      check.setTooltip(new Tooltip(NegativeSuggestions.descriptions(tag)))
      check
    }
    val choices = new FlowPane(12, 10)
    choices.setPrefWrapLength(420)
    choices.getChildren.addAll(checks*)
    val preset = new ComboBox[String](FXCollections.observableArrayList(("Choose a set" +: NegativeSuggestions.presets.map(_._1))*))
    preset.setId("negative-suggestion-preset")
    preset.setValue("Choose a set")
    preset.valueProperty.addListener((_, _, choice) =>
      NegativeSuggestions.presets.find(_._1 == choice).foreach { (_, tags) =>
        checks.zip(NegativeSuggestions.items).foreach { (check, tag) => if tags.contains(tag) then check.setSelected(true) }
      }
    )
    val selected = new Label()
    selected.setId("negative-selection-preview")
    selected.setWrapText(true)
    def updateSelection(): Unit =
      selected.setText(checks.zip(NegativeSuggestions.items).collect { case (c, tag) if c.isSelected => tag }.mkString(", "))
    checks.foreach(_.selectedProperty.addListener((_, _, _) => updateSelection()))
    updateSelection()
    val setHint = new Label("Choosing a set adds its terms to the selection; you can uncheck any of them.")
    setHint.setWrapText(true)
    val content = new VBox(12, hint, new HBox(8, new Label("Add a set"), preset), setHint, choices, new Label("Selected terms"), selected)
    content.setPadding(new Insets(8))
    dialog.getDialogPane.setContent(content)
    if owner != null && owner.getScene != null then dialog.getDialogPane.getStylesheets.setAll(owner.getScene.getStylesheets)
    dialog.setResultConverter(button =>
      if button == add then checks.zip(NegativeSuggestions.items).collect { case (c, tag) if c.isSelected => tag }
      else null)
    dialog
