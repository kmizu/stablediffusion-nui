package sdfx.ui.view

import javafx.beans.property.{SimpleBooleanProperty, SimpleDoubleProperty, SimpleIntegerProperty, SimpleStringProperty}
import javafx.collections.FXCollections
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import sdfx.core.model.PreprocessorControls
import sdfx.core.plugins.api.ChoiceSource
import sdfx.ui.Fx
import sdfx.ui.vm.Mode

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/** Extras step "ControlNet Preprocessor": run a ControlNet preprocessor on an image, or on every image of a folder, and
  * hand the result to a ControlNet unit. The Extras tab supplies the input (source image or its current result) and
  * shows the single-image result. */
final class ControlNetExtrasCard(ctx: UiContext, source: () => Option[Path], showResult: Path => Unit, onBusy: Boolean => Unit,
                                 inputOption: javafx.scene.Node):
  import ControlNetExtrasCard.*

  val module = new SimpleStringProperty("")
  val pixelPerfect = new SimpleBooleanProperty(false)
  val resolution = new SimpleIntegerProperty(PreprocessorControls.DefaultResolution)
  val inputDir = new SimpleStringProperty("")
  val outputDir = new SimpleStringProperty("")
  private val unitNumber = new SimpleIntegerProperty(0)
  private val modules = FXCollections.observableArrayList[String]()
  private val catalogChoices = ctx.catalog.choices(ChoiceSource.Preprocessors)
  private def refreshModules(): Unit =
    modules.setAll(PreprocessorControls.selectable(catalogChoices.asScala.toSeq).asJava)
    if module.get.isEmpty || !modules.contains(module.get) then modules.stream.findFirst.ifPresent(m => module.set(m))
  catalogChoices.addListener((_: javafx.collections.ListChangeListener.Change[? <: String]) => refreshModules())
  refreshModules()

  private val sliderBox = new VBox(6)
  private var sliderValues = Map.empty[String, SimpleDoubleProperty]
  private val resolutionControl = W.intSlider(resolution, PreprocessorControls.MinResolution, PreprocessorControls.MaxResolution, 64)
  private val resolutionRow = new VBox(3, W.label("Preprocessor resolution", "form-label"), resolutionControl)
  private val pixelPerfectBox = W.check("Pixel perfect", pixelPerfect, "Use the short side of the image as the resolution")
  private val hint = W.label("", "hint")
  hint.setWrapText(true)

  private def rebuild(): Unit =
    val controls = PreprocessorControls.of(Option(module.get).getOrElse(""))
    sliderValues = controls.sliders.map(s => s.key -> new SimpleDoubleProperty(s.default)).toMap
    sliderBox.getChildren.setAll(controls.sliders.map { s =>
      val row = new VBox(3, W.label(s.label, "form-label"), W.doubleSlider(sliderValues(s.key), s.min, s.max, s.step))
      row.setId(s"extras-cn-slider-${s.key}")
      row
    }.asJava)
    resolutionRow.setVisible(controls.resolution); resolutionRow.setManaged(controls.resolution)
    pixelPerfectBox.setVisible(controls.resolution); pixelPerfectBox.setManaged(controls.resolution)
    resolutionControl.setDisable(pixelPerfect.get)
    hint.setText(
      if controls.resolution then "The resolution applies to this module; Pixel perfect uses the short side of the image."
      else "This module has no resolution; the resolution and Pixel perfect settings are ignored.")
  module.addListener((_, _, _) => rebuild())
  pixelPerfect.addListener((_, _, on) => resolutionControl.setDisable(on))
  rebuild()

  /** The arguments and resolution the engine receives for the current controls. */
  def args: Map[String, Double] =
    PreprocessorControls.of(module.get).sliders.flatMap(s => sliderValues.get(s.key).map { p =>
      s.key -> (if s.integer then p.get.round.toDouble else p.get)
    }).toMap
  def processorRes: Int = if PreprocessorControls.of(module.get).resolution && !pixelPerfect.get then resolution.get else -1
  def pixelPerfectOn: Boolean = PreprocessorControls.of(module.get).resolution && pixelPerfect.get
  def slider(key: String): Option[SimpleDoubleProperty] = sliderValues.get(key)

  // ---- single image ----------------------------------------------------------------------------------------------
  private var lastResult: Option[Path] = None
  private val status = W.label("", "hint")
  status.setWrapText(true)
  private val busy = new SimpleBooleanProperty(false)
  private val cancel = new AtomicBoolean(false)
  private val spinner = new ProgressIndicator()
  spinner.setPrefSize(22, 22)
  spinner.visibleProperty.bind(busy)

  private def setBusy(on: Boolean): Unit =
    busy.set(on)
    onBusy(on)

  private val run = W.button("◉ Run preprocessor", "Run the module on the image (or on the current result when selected)", Seq("accent-button")) {
    source() match
      case None => ctx.info("Add an image first")
      case Some(path) =>
        val session = ctx.services.imageScrambleSession.state
        val chosen = module.get
        setBusy(true)
        val saveDir = ctx.services.generator.outputsDir.resolve("extras")
        Fx.async(ctx.services.generator.preprocessExtras(path, chosen, args, processorRes, pixelPerfectOn, Some(saveDir),
          secret = session.secret, warn = message => Fx.run(ctx.info(message)))) { out =>
          setBusy(false)
          if session.revision == ctx.services.imageScrambleSession.state.revision then
            lastResult = Some(out)
            showResult(out)
            status.setText(s"$chosen done")
            ctx.success(s"ControlNet preprocessor: $chosen")
          else Fx.async(Files.deleteIfExists(out))(_ => ())(_ => ())
        } { e =>
          setBusy(false)
          status.setText(s"Failed: ${Fx.message(e)}")
          ctx.error(s"ControlNet preprocessor failed: ${Fx.message(e)}")
        }
  }
  run.setId("extras-cn-run")

  // ---- folder ----------------------------------------------------------------------------------------------------
  private def pick(p: SimpleStringProperty, title: String): Unit =
    val chooser = new DirectoryChooser()
    chooser.setTitle(title)
    Option(p.get).filter(_.nonEmpty).map(Paths.get(_)).filter(Files.isDirectory(_)).foreach(d => chooser.setInitialDirectory(d.toFile))
    Option(chooser.showDialog(run.getScene.getWindow)).foreach(f => p.set(f.toString))

  private val runBatch: Button = W.button("▶ Run on folder", "Process every image in the input folder") { startBatch() }
  runBatch.setId("extras-cn-batch")
  private val cancelBatch: Button = W.button("Cancel", "Stop after the image being processed")(cancel.set(true))
  cancelBatch.setId("extras-cn-cancel")
  cancelBatch.disableProperty.bind(busy.not)
  run.disableProperty.bind(busy)
  runBatch.disableProperty.bind(busy)

  private def startBatch(): Unit =
    val in = Option(inputDir.get).map(_.trim).filter(_.nonEmpty).map(Paths.get(_))
    val out = Option(outputDir.get).map(_.trim).filter(_.nonEmpty).map(Paths.get(_))
    (in, out) match
      case (None, _) => ctx.info("Choose the input folder")
      case (_, None) => ctx.info("Choose the output folder")
      case (Some(i), _) if !Files.isDirectory(i) => ctx.error(s"Not a folder: $i")
      case (Some(i), Some(o)) if sameFolder(i, o) => ctx.error("The output folder must differ from the input folder")
      case (Some(i), Some(o)) =>
        val files = listImages(i)
        if files.isEmpty then ctx.info("No images in the input folder")
        else
          val session = ctx.services.imageScrambleSession.state
          val chosen = module.get
          val (a, res, pp) = (args, processorRes, pixelPerfectOn)
          cancel.set(false)
          setBusy(true)
          status.setText(s"0 / ${files.size}")
          Fx.async {
            var done = 0
            var failed = Vector.empty[String]
            val iterator = files.iterator
            while iterator.hasNext && !cancel.get do
              val f = iterator.next()
              try ctx.services.generator.preprocessExtras(f, chosen, a, res, pp, Some(o), session.secret)
              catch case e: Exception => failed :+= s"${f.getFileName}: ${Fx.message(e).linesIterator.nextOption().getOrElse("").take(120)}"
              done += 1
              val (d, n) = (done, failed.size)
              Fx.run(status.setText(s"$d / ${files.size}" + (if n > 0 then s" ($n failed)" else "")))
            BatchOutcome(done, files.size, failed, cancel.get)
          } { r =>
            setBusy(false)
            val text = s"${r.done - r.failed.size} of ${r.total} written to $o" + (if r.cancelled then " (cancelled)" else "") +
              (if r.failed.nonEmpty then s"; ${r.failed.size} failed, first: ${r.failed.head}" else "")
            status.setText(text)
            if r.failed.isEmpty then ctx.success(text) else ctx.error(text)
          } { e =>
            setBusy(false)
            ctx.error(s"Batch failed: ${Fx.message(e)}")
          }

  // ---- send to ControlNet ----------------------------------------------------------------------------------------
  private def sendControlNet(mode: Mode): Unit =
    lastResult.filter(Files.isRegularFile(_)) match
      case None => ctx.info("Run the preprocessor first")
      case Some(path) =>
        val index = unitNumber.get
        ctx.fastloadTarget(mode, index) match
          case None => ctx.error(s"ControlNet unit $index is unavailable in ${mode.label}")
          case Some(existing) =>
            // the image is already preprocessed, so the unit must not process it again
            val incoming = existing.copy(image = Some(path.toString), preprocessor = "none", preprocessorArgs = Map.empty,
              processorRes = -1, pixelPerfect = false)
            ctx.applyFastloadTarget(mode, index, existing, incoming) match
              case Left(m)  => ctx.error(m)
              case Right(_) => ctx.success(s"Set ${mode.label} ControlNet unit $index image")

  def clearResult(): Unit = lastResult = None

  private val unitSpinner = W.intSpinner(unitNumber, 0, 2)
  private val sendTxt = W.button("⇢ txt2img ControlNet", "Use the result as the control image of the unit in txt2img")(sendControlNet(Mode.Txt2Img))
  private val sendImg = W.button("⇢ img2img ControlNet", "Use the result as the control image of the unit in img2img")(sendControlNet(Mode.Img2Img))
  sendTxt.setId("extras-cn-send-txt2img")
  sendImg.setId("extras-cn-send-img2img")

  private val moduleBox = W.combo(modules, module)
  moduleBox.setId("extras-cn-module")
  pixelPerfectBox.setId("extras-cn-pixel-perfect")
  resolutionRow.setId("extras-cn-resolution")
  private val inField = W.textField(inputDir, "Input folder")
  inField.setId("extras-cn-input-dir")
  private val outField = W.textField(outputDir, "Output folder")
  outField.setId("extras-cn-output-dir")
  status.setId("extras-cn-status")

  val node: VBox =
    val card = W.card("ControlNet Preprocessor",
      W.form().add("Module", moduleBox, "Preprocessor to run, the same list as a ControlNet unit").grid,
      pixelPerfectBox, resolutionRow, sliderBox, hint,
      inputOption,
      W.hbox(8, run, spinner),
      W.label("Folder", "card-title"),
      W.hbox(6, W.hgrow(inField), W.button("…", "Choose the input folder")(pick(inputDir, "Input folder"))),
      W.hbox(6, W.hgrow(outField), W.button("…", "Choose the output folder")(pick(outputDir, "Output folder"))),
      W.hbox(8, runBatch, cancelBatch),
      status,
      W.hbox(6, sendTxt, sendImg, W.label("unit"), unitSpinner))
    card.setId("extras-cn-card")
    card

object ControlNetExtrasCard:
  final case class BatchOutcome(done: Int, total: Int, failed: Vector[String], cancelled: Boolean)

  /** Images directly inside `dir`, sorted by name. */
  def listImages(dir: Path): Seq[Path] =
    val s = Files.list(dir)
    try s.iterator.asScala.filter(p => Files.isRegularFile(p) && PreprocessorControls.isImage(p.getFileName.toString)).toVector.sortBy(_.getFileName.toString.toLowerCase)
    finally s.close()

  def sameFolder(a: Path, b: Path): Boolean =
    try Files.exists(b) && Files.isSameFile(a, b) catch case _: java.io.IOException => a.toAbsolutePath.normalize == b.toAbsolutePath.normalize
