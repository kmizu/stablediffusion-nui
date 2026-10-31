package sdfx.ui.view

import javafx.geometry.Insets
import javafx.scene.control.{ComboBox, TextArea, TextField}
import javafx.scene.image.{Image, ImageView}
import javafx.scene.layout.{HBox, Priority, VBox}
import javafx.stage.FileChooser
import java.io.ByteArrayInputStream
import java.util.Base64
import sdfx.ui.Fx
import sdfx.ui.vm.{ControlNetFastloadSummary, FastloadCapture, FastloadForm, FastloadInputStore, FastloadPublisher, GenerationVM, Mode}
import scala.util.control.NonFatal

/** Inspect and copy ControlNet Fastload data without exposing source pixels in the form. */
final class ControlNetFastloadTab(ctx: UiContext):
  private val source = new TextField()
  source.setId("fastload-source")
  source.setPromptText("Image or .cni file to inspect")
  HBox.setHgrow(source, Priority.ALWAYS)
  private val destination = new TextField()
  destination.setId("fastload-destination")
  destination.setPromptText("New .cni file")
  private val details = new TextArea()
  details.setId("fastload-summary")
  details.setEditable(false)
  details.setWrapText(true)
  details.setPrefRowCount(12)
  private val unit = new ComboBox[Integer]()
  unit.setId("fastload-unit")
  private val image = new ImageView()
  private val mask = new ImageView()
  image.setId("fastload-image-preview"); mask.setId("fastload-mask-preview")
  Seq(image, mask).foreach { view => view.setPreserveRatio(true); view.setFitWidth(240); view.setFitHeight(180) }
  private val status = W.label("Choose an image or .cni file.", "muted")
  status.setId("fastload-status")
  status.setWrapText(true)
  private var units: Option[ujson.Value] = None
  private var loadedPath: Option[String] = None
  private var busy = false
  private var revision = 0L
  private var disposed = false
  private val browse = W.button("Browse", "Choose a Fastload image or .cni file") {
    val chooser = new FileChooser()
    chooser.setTitle("Open ControlNet Fastload data")
    Option(chooser.showOpenDialog(ctx.stage)).foreach(file => source.setText(file.getAbsolutePath))
  }
  private val inspect = W.button("Inspect", "Read embedded settings and source images") { load() }
  inspect.setId("fastload-inspect")
  private val save = W.button("Save .cni copy", "Save a new file; existing files are not overwritten") { saveCopy() }
  save.setId("fastload-save")
  private val preview = W.button("Preview images", "View the selected source image and mask in memory") { previewUnit() }
  preview.setId("fastload-preview")
  private val sendTxt = W.button("Send unit to txt2img", "Replace the destination unit with the same number") { sendUnit(Mode.Txt2Img) }
  private val sendImg = W.button("Send unit to img2img", "Replace the destination unit with the same number") { sendUnit(Mode.Img2Img) }
  sendTxt.setId("fastload-send-txt2img"); sendImg.setId("fastload-send-img2img")
  private val captureTxt = W.button("Capture txt2img", "Capture current ControlNet units in memory") { capture(Mode.Txt2Img) }
  private val captureImg = W.button("Capture img2img", "Capture current ControlNet units in memory") { capture(Mode.Img2Img) }
  captureTxt.setId("fastload-capture-txt2img"); captureImg.setId("fastload-capture-img2img")
  private val help = W.label("Inspect saved settings or capture the current ControlNet units, send a selected unit to the same numbered slot, or save a separate copy. Sending is unavailable for settings the current ControlNet form cannot apply. " +
    "A .cni file contains readable source images and masks; saving is unavailable while image scrambling is enabled. Captured NUI interpolation preferences use an NUI-only field that other apps may ignore or reject.", "muted")
  help.setWrapText(true)
  val node = new VBox(10, W.label("ControlNet Fastload", "section-title"), help,
    W.hbox(8, source, browse, inspect), W.hbox(8, captureTxt, captureImg), details,
    W.hbox(8, unit, preview, sendTxt, sendImg), W.hbox(8, image, mask), destination, save, status)
  node.setPadding(new Insets(14))
  node.setMinWidth(0)
  VBox.setVgrow(details, Priority.ALWAYS)
  private def updateControls(): Unit =
    source.setDisable(busy); browse.setDisable(busy); inspect.setDisable(busy || source.getText.trim.isEmpty)
    destination.setDisable(busy || units.isEmpty)
    save.setDisable(busy || units.isEmpty || destination.getText.trim.isEmpty)
    unit.setDisable(busy || units.isEmpty); preview.setDisable(busy || units.isEmpty || unit.getValue == null)
    sendTxt.setDisable(busy || units.isEmpty || unit.getValue == null)
    sendImg.setDisable(busy || units.isEmpty || unit.getValue == null)
    captureTxt.setDisable(busy); captureImg.setDisable(busy)
  private def clearForSessionChange(): Unit =
    revision += 1; busy = false; units = None; loadedPath = None
    details.clear(); unit.getItems.clear(); image.setImage(null); mask.setImage(null)
    status.setText("Image session changed. Load the data again.")
    updateControls()

  /** Call on the FX thread while holding the image session monitor. */
  private def currentSession(request: Long, sessionRevision: Long): Boolean =
    if disposed || request != revision then false
    else if ctx.services.imageScrambleSession.state.revision != sessionRevision then
      clearForSessionChange(); false
    else true
  source.textProperty.addListener((_, _, _) =>
    if loadedPath.exists(_ != source.getText.trim) then
      revision += 1; units = None; loadedPath = None; details.clear(); image.setImage(null); mask.setImage(null)
    updateControls())
  destination.textProperty.addListener((_, _, _) => updateControls())
  ctx.watchImageAccess(node) { () =>
    clearForSessionChange()
  }
  updateControls()

  private def load(): Unit =
    val path = source.getText.trim
    if busy || path.isEmpty then return
    revision += 1
    val request = revision
    val session = ctx.services.imageScrambleSession.state
    busy = true; units = None; loadedPath = None; details.clear(); image.setImage(null); mask.setImage(null); updateControls()
    status.setText("Reading ControlNet data…")
    Fx.onFx(ctx.services.client.controlNetFastloadData(ujson.Obj("operation" -> "inspect", "path" -> path), session.secret)) { result =>
      ctx.services.imageScrambleSession.synchronized {
        if currentSession(request, session.revision) then
          try
            val summary = ControlNetFastloadSummary.render(result)
            units = Some(result("units")); loadedPath = Some(path); details.setText(summary)
            val count = result("summary").arr.size
            unit.getItems.clear(); (1 to count).foreach(i => unit.getItems.add(Integer.valueOf(i)))
            if count > 0 then unit.getSelectionModel.selectFirst()
            status.setText("Data loaded. Review any options that cannot be applied.")
          catch case NonFatal(error) => status.setText(Fx.message(error))
          busy = false; updateControls()
      }
    } { error => ctx.services.imageScrambleSession.synchronized {
      if currentSession(request, session.revision) then { busy = false; updateControls(); status.setText(Fx.message(error)) }
    } }

  private def previewUnit(): Unit =
    val selected = Option(unit.getValue).map(_.intValue - 1)
    if busy || units.isEmpty || selected.isEmpty then return
    revision += 1
    val request = revision
    val session = ctx.services.imageScrambleSession.state
    busy = true; updateControls(); status.setText("Preparing preview…")
    val params = ujson.Obj("operation" -> "preview", "units" -> units.get, "index" -> selected.get)
    Fx.onFx(ctx.services.client.controlNetFastloadData(params, session.secret)) { result =>
      ctx.services.imageScrambleSession.synchronized {
        if currentSession(request, session.revision) then
          try
            def decoded(key: String): Image = result(key) match
              case ujson.Null => null
              case ujson.Str(value) =>
                val result = new Image(new ByteArrayInputStream(Base64.getDecoder.decode(value)))
                if result.isError then throw new IllegalArgumentException("Could not decode preview image")
                result
              case _ => throw new IllegalArgumentException("Invalid preview image")
            image.setImage(decoded("image")); mask.setImage(decoded("mask")); status.setText("Preview ready.")
          catch
            case NonFatal(error) =>
              image.setImage(null); mask.setImage(null); status.setText(Fx.message(error))
          busy = false; updateControls()
      }
    } { error => ctx.services.imageScrambleSession.synchronized {
      if currentSession(request, session.revision) then { busy = false; updateControls(); status.setText(Fx.message(error)) }
    } }

  private def capture(mode: Mode): Unit =
    if busy then return
    val snapshots = (0 until GenerationVM.ControlNetUnits).map(i => ctx.fastloadTarget(mode, i)).toVector
    if snapshots.exists(_.isEmpty) then
      status.setText("ControlNet source is unavailable."); return
    revision += 1
    val request = revision
    val session = ctx.services.imageScrambleSession.state
    busy = true; updateControls(); status.setText(s"Capturing ${mode.label} units…")
    Fx.async(FastloadCapture.encode(snapshots.flatten, session.secret)) { encoded =>
      val valid = ctx.services.imageScrambleSession.synchronized { currentSession(request, session.revision) }
      if valid then
        val params = ujson.Obj("operation" -> "from_native", "items" -> encoded)
        Fx.onFx(ctx.services.client.controlNetFastloadData(params, session.secret)) { result =>
          ctx.services.imageScrambleSession.synchronized {
            if currentSession(request, session.revision) then
              try
                val summary = ControlNetFastloadSummary.render(result)
                loadedPath = None; source.clear(); units = Some(result("units")); details.setText(summary)
                image.setImage(null); mask.setImage(null); unit.getItems.clear()
                (1 to result("summary").arr.size).foreach(i => unit.getItems.add(Integer.valueOf(i)))
                if !unit.getItems.isEmpty then unit.getSelectionModel.selectFirst()
                status.setText(s"Captured ${mode.label} snapshot in memory.")
              catch case NonFatal(error) => status.setText(Fx.message(error))
              busy = false; updateControls()
          }
        } { error => ctx.services.imageScrambleSession.synchronized {
          if currentSession(request, session.revision) then { busy = false; updateControls(); status.setText(Fx.message(error)) }
        } }
    } { error => ctx.services.imageScrambleSession.synchronized {
      if currentSession(request, session.revision) then { busy = false; updateControls(); status.setText(Fx.message(error)) }
    } }

  private def saveCopy(): Unit =
    val target = destination.getText.trim
    val data = units
    if busy || target.isEmpty || data.isEmpty then return
    val session = ctx.services.imageScrambleSession.state
    if session.secret.nonEmpty then
      status.setText("Protected sessions cannot save readable .cni files.")
      return
    revision += 1
    val request = revision
    busy = true; updateControls(); status.setText("Saving copy…")
    val params = ujson.Obj("operation" -> "encode", "units" -> data.get)
    Fx.onFx(ctx.services.client.controlNetFastloadData(params, session.secret)) { result =>
      ctx.services.imageScrambleSession.synchronized {
        if currentSession(request, session.revision) then
          try
            val encoded = result("base64").str
            require(encoded.length <= 180000000, "Fastload output is too large")
            FastloadPublisher.publish(java.nio.file.Paths.get(target), Base64.getDecoder.decode(encoded),
              ctx.services.imageScrambleSession, session.revision)
            status.setText("Saved new .cni file.")
          catch case NonFatal(error) => status.setText(Fx.message(error))
          busy = false; updateControls()
      }
    } { error => ctx.services.imageScrambleSession.synchronized {
      if currentSession(request, session.revision) then { busy = false; updateControls(); status.setText(Fx.message(error)) }
    } }

  private def sendUnit(mode: Mode): Unit =
    val index = Option(unit.getValue).map(_.intValue - 1).getOrElse(-1)
    if busy || units.isEmpty || index < 0 then return
    if index >= GenerationVM.ControlNetUnits then
      status.setText("The destination has only three ControlNet units; no settings were changed.")
      return
    val expected = ctx.fastloadTarget(mode, index)
    if expected.isEmpty then
      status.setText("ControlNet destination is unavailable.")
      return
    revision += 1
    val request = revision
    val session = ctx.services.imageScrambleSession.state
    busy = true; updateControls(); status.setText("Preparing ControlNet unit…")
    val params = ujson.Obj("operation" -> "native_form", "units" -> units.get, "index" -> index)
    Fx.onFx(ctx.services.client.controlNetFastloadData(params, session.secret)) { result =>
      val sessionMatches = ctx.services.imageScrambleSession.state.revision == session.revision
      if !disposed && request == revision && !sessionMatches then
        clearForSessionChange()
      if !disposed && request == revision && sessionMatches then
        var staged: Option[java.nio.file.Path] = None
        try
          val decoded = FastloadForm.decode(result)
          val preprocessor = decoded.unit.preprocessor
          if preprocessor != "none" && preprocessor.nonEmpty &&
              !ctx.catalog.choices(sdfx.core.plugins.api.ChoiceSource.Preprocessors).contains(preprocessor) then
            throw new IllegalArgumentException(s"Preprocessor '$preprocessor' is not available in this session")
          ctx.services.imageScrambleSession.synchronized {
            if ctx.services.imageScrambleSession.state.revision != session.revision then
              throw new IllegalStateException("Image session changed; retry the transfer.")
            try
              staged = decoded.image.map(bytes => FastloadInputStore.write(bytes, ctx.temps, session.secret))
              val replacement = decoded.unit.copy(image = staged.map(_.toString))
              ctx.applyFastloadTarget(mode, index, expected.get, replacement) match
                case Right(_) => staged = None; status.setText(s"Sent unit ${index + 1} to ${mode.label}.")
                case Left(message) => status.setText(message)
            finally staged.foreach { path =>
              ctx.temps.forgetInput(path)
              try java.nio.file.Files.deleteIfExists(path) catch case NonFatal(_) => ()
            }
          }
        catch
          case NonFatal(error) =>
            if ctx.services.imageScrambleSession.state.revision != session.revision then clearForSessionChange()
            else status.setText(Fx.message(error))
        if request == revision then { busy = false; updateControls() }
    } { error => ctx.services.imageScrambleSession.synchronized {
      if currentSession(request, session.revision) then { busy = false; updateControls(); status.setText(Fx.message(error)) }
    } }

  def dispose(): Unit =
    disposed = true; revision += 1; units = None; loadedPath = None
    details.clear(); unit.getItems.clear(); image.setImage(null); mask.setImage(null)

  def inspectPath(path: java.nio.file.Path): Boolean =
    if disposed || busy then false
    else { source.setText(path.toString); load(); true }
