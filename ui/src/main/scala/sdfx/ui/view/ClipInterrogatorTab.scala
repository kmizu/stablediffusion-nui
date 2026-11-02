package sdfx.ui.view

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.{FlowPane, HBox, Priority, VBox}
import javafx.stage.DirectoryChooser
import sdfx.core.protocol.EngineEvent
import sdfx.engine.ClipInterrogatorHandle
import sdfx.ui.Fx
import sdfx.ui.vm.Mode

import java.nio.file.Paths
import scala.util.control.NonFatal

/** Native counterpart of the A1111 "Interrogator" tab: Prompt, Analyze, Batch and About. */
final class ClipInterrogatorTab(ctx: UiContext):
  import ClipInterrogatorTab.*

  private val clipModel = new ComboBox[String](FXCollections.observableArrayList(DefaultClip))
  clipModel.setId("clip-model")
  clipModel.setValue(DefaultClip)
  clipModel.setEditable(false)
  private val captionModel = new ComboBox[String](FXCollections.observableArrayList(CaptionModels*))
  captionModel.setId("clip-caption-model")
  captionModel.setValue("blip-large")
  private val keepLoaded = new CheckBox("Keep models loaded")
  keepLoaded.setId("clip-keep-loaded")
  keepLoaded.setTooltip(new Tooltip("Keep CLIP and the caption model in memory between runs. Off frees GPU memory after each run."))
  private val status = W.label("Choose an image, then generate a prompt.", "muted")
  status.setId("clip-status")
  status.setWrapText(true)
  private val progress = new ProgressBar(0)
  progress.setId("clip-progress")
  progress.setMaxWidth(Double.MaxValue)
  progress.setVisible(false)

  private var active: Option[ClipInterrogatorHandle] = None
  private var disposed = false
  private var modelsLoaded = false
  private val busyActions = scala.collection.mutable.ArrayBuffer.empty[Control]
  private val cancel = W.button("Cancel", "Cancel only this interrogation") {
    active.foreach(h => Fx.onFx(h.interrupt()) { d =>
      if !disposed then status.setText(if d.blocked then "Cancellation blocked by interruption protection."
        else if d.affected then "Cancelling…" else "Nothing to cancel.")
    }(e => if !disposed then status.setText(Fx.message(e))))
  }
  cancel.setId("clip-cancel")
  cancel.setDisable(true)
  private val unload = W.button("Unload", "Free the CLIP and caption models from memory") {
    start(ujson.Obj("operation" -> "unload"), "Unloading…") { v =>
      status.setText(if v("unloaded").bool then "Models unloaded." else "No models were loaded.")
    }
  }
  unload.setId("clip-unload")
  busyActions += unload

  private def baseParams(operation: String): ujson.Obj = ujson.Obj(
    "operation" -> operation,
    "clip_model" -> Option(clipModel.getValue).getOrElse(DefaultClip),
    "caption_model" -> Option(captionModel.getValue).getOrElse("blip-large"),
    "keep_loaded" -> keepLoaded.isSelected,
    "cache_dir" -> ctx.services.home.configDir.resolve("clip-interrogator").toString)

  private def plus(o: ujson.Obj, kv: (String, ujson.Value)*): ujson.Obj =
    kv.foreach((k, v) => o(k) = v)
    o

  private def protectedSession: Boolean = ctx.services.imageScrambleSession.state.secret.nonEmpty

  /** One operation at a time; everything that starts work is disabled while it runs. */
  private def start(params: ujson.Obj, message: String)(onDone: ujson.Value => Unit): Unit =
    if active.nonEmpty || disposed then return
    try
      status.setText(message + " (queued after any running job)")
      progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS)
      progress.setVisible(true)
      val handle = ctx.services.client.clipInterrogatorOwned(params, {
        case p: EngineEvent.Progress => Fx.run {
          if !disposed && active.nonEmpty && p.total > 0 then
            progress.setProgress(p.step.toDouble / p.total)
            if p.pass.startsWith("clip_interrogator") then
              val what = if p.pass == "clip_interrogator_caption" then "Captioning" else "Interrogating"
              status.setText(s"$what: ${p.step} / ${p.total}")
        }
        case _ => ()
      })
      active = Some(handle)
      controls()
      Fx.onFx(handle.result) { value =>
        if !disposed then
          active = None; progress.setVisible(false); controls()
          onDone(value)
      } { e =>
        if !disposed then
          active = None; progress.setVisible(false); controls(); status.setText(Fx.message(e))
      }
    catch case NonFatal(e) =>
      active = None; progress.setVisible(false); controls(); status.setText(Fx.message(e))

  private def controls(): Unit =
    val busy = active.nonEmpty
    busyActions.foreach(_.setDisable(busy))
    Seq(clipModel, captionModel, keepLoaded).foreach(_.setDisable(busy))
    cancel.setDisable(!busy)
    refreshSend()

  private def action(text: String, tip: String, id: String, primary: Boolean = true)(body: => Unit): Button =
    val b = W.button(text, tip, if primary then Seq("primary") else Nil)(body)
    b.setId(id)
    busyActions += b
    b

  // ---------------------------------------------------------------- Prompt
  val promptImage = new SimpleStringProperty("")
  private val promptDrop = new ImageDropZone(ctx, promptImage, "Image to interrogate", 240)
  promptDrop.node.setId("clip-prompt-image")
  private val mode = new ComboBox[String](FXCollections.observableArrayList("best", "fast", "classic", "negative"))
  mode.setId("clip-mode")
  mode.setValue("best")
  mode.setTooltip(new Tooltip("best: chain of the most similar terms (slowest). fast: top terms after the caption. classic: caption, artist, trending, movement, flavors. negative: terms least like the image, for a negative prompt."))
  private val promptOut = new TextArea()
  promptOut.setId("clip-prompt-output")
  promptOut.setWrapText(true)
  promptOut.setPrefRowCount(4)
  promptOut.setPromptText("The generated prompt appears here")
  private var lastMode = "best"
  private val generate = action("Generate", "Interrogate the image", "clip-generate") {
    if protectedSession then status.setText("Interrogation is unavailable during a protected image session.")
    else if promptImage.get.isEmpty then status.setText("Choose an image first.")
    else
      val m = mode.getValue
      start(plus(baseParams("prompt"), "image_path" -> promptImage.get, "mode" -> m), "Interrogating…") { v =>
        lastMode = m
        promptOut.setText(v("prompt").str)
        val used = v("clip_model").str
        val similarity = v("similarity").num
        status.setText(f"Prompt generated with $used ($m mode); similarity to the image $similarity%.3f.")
        refreshSend()
      }
  }
  private def sendBtn(label: String, id: String)(body: => Unit): Button =
    val b = W.button(label, "", Nil)(body)
    b.setId(id)
    b
  private val toTxt = sendBtn("Send to txt2img", "clip-to-txt2img")(sendPrompt(Mode.Txt2Img, withImage = false))
  private val toImg = sendBtn("Send to img2img", "clip-to-img2img")(sendPrompt(Mode.Img2Img, withImage = true))
  private val toInp = sendBtn("Send to inpaint", "clip-to-inpaint")(sendPrompt(Mode.Inpaint, withImage = true))
  private val toExtras = sendBtn("Send to Extras", "clip-to-extras") {
    if promptImage.get.nonEmpty then ctx.sendToExtras(Paths.get(promptImage.get))
  }
  private val sendButtons = Seq(toTxt, toImg, toInp, toExtras)
  private def refreshSend(): Unit =
    val has = promptOut.getText.trim.nonEmpty && active.isEmpty
    Seq(toTxt, toImg, toInp).foreach(_.setDisable(!has))
    toExtras.setDisable(promptImage.get.isEmpty || active.nonEmpty)
  private def sendPrompt(target: Mode, withImage: Boolean): Unit =
    val text = promptOut.getText.trim
    if text.nonEmpty then
      if withImage && promptImage.get.nonEmpty then ctx.sendToTab(target, Paths.get(promptImage.get), None)
      ctx.sendPrompt(target, text, lastMode == "negative")
  promptOut.textProperty.addListener((_, _, _) => refreshSend())
  promptImage.addListener((_, _, _) => refreshSend())

  private val promptPage = new VBox(10, promptDrop.node,
    W.hbox(8, W.label("Mode", "muted"), mode, generate, cancel), promptOut,
    new FlowPane(6, 6, sendButtons*))
  promptPage.setPadding(new Insets(10))

  // ---------------------------------------------------------------- Analyze
  val analyzeImage = new SimpleStringProperty("")
  private val analyzeDrop = new ImageDropZone(ctx, analyzeImage, "Image to analyze", 200)
  analyzeDrop.node.setId("clip-analyze-image")
  private val tables = AnalyzeKeys.map { key =>
    val t = new TableView[(String, Double)]()
    t.setId(s"clip-analyze-$key")
    val label = new TableColumn[(String, Double), String](key.capitalize)
    label.setCellValueFactory(c => new SimpleStringProperty(c.getValue._1))
    label.setPrefWidth(190)
    val score = new TableColumn[(String, Double), String]("Score")
    score.setCellValueFactory(c => new SimpleStringProperty(f"${c.getValue._2}%.4f"))
    score.setPrefWidth(70)
    t.getColumns.addAll(label, score)
    t.setPrefHeight(190)
    t.setPlaceholder(new Label("—"))
    key -> t
  }.toMap
  private val analyze = action("Analyze", "Rank the five best medium, artist, movement, trending and flavor labels", "clip-analyze") {
    if protectedSession then status.setText("Interrogation is unavailable during a protected image session.")
    else if analyzeImage.get.isEmpty then status.setText("Choose an image first.")
    else start(plus(baseParams("analyze"), "image_path" -> analyzeImage.get), "Analyzing…") { v =>
      AnalyzeKeys.foreach { k =>
        tables(k).getItems.setAll(v(k).obj.toSeq.map((l, s) => (l, s.num))*)
      }
      status.setText(s"Analyzed with ${v("clip_model").str}.")
    }
  }
  private val analyzeGrid = new FlowPane(8, 8, AnalyzeKeys.map(tables(_))*)
  private val analyzePage = new VBox(10, analyzeDrop.node, W.hbox(8, analyze, cancelProxy()), analyzeGrid)
  analyzePage.setPadding(new Insets(10))
  private def cancelProxy(): Button =
    val b = W.button("Cancel", "Cancel only this interrogation")(cancel.fire())
    b.disableProperty.bind(cancel.disableProperty)
    b

  // ---------------------------------------------------------------- Batch
  private val folder = new TextField()
  folder.setId("clip-batch-folder")
  folder.setPromptText("Folder with .png, .jpg or .jpeg images")
  HBox.setHgrow(folder, Priority.ALWAYS)
  private val browse = W.button("Browse…", "Choose the image folder") {
    val picker = new DirectoryChooser()
    picker.setTitle("Images folder")
    val chosen = picker.showDialog(ctx.stage)
    if chosen != null then folder.setText(chosen.getAbsolutePath)
  }
  browse.setId("clip-batch-browse")
  busyActions += browse
  private val batchMode = new ComboBox[String](FXCollections.observableArrayList("caption", "best", "fast", "classic", "negative"))
  batchMode.setId("clip-batch-mode")
  batchMode.setValue("fast")
  private val outputMode = new ComboBox[String](FXCollections.observableArrayList(BatchOutputModes*))
  outputMode.setId("clip-batch-output")
  outputMode.setValue(BatchOutputModes.head)
  private val overwrite = new CheckBox("Overwrite existing outputs")
  overwrite.setId("clip-batch-overwrite")
  overwrite.setTooltip(new Tooltip("Off: images that already have a .txt are skipped and batch.txt / batch.csv are never replaced."))
  private val batchResult = new TextArea()
  batchResult.setId("clip-batch-result")
  batchResult.setEditable(false)
  batchResult.setPrefRowCount(5)
  private val go = action("Go!", "Interrogate every image in the folder and write the results", "clip-batch-go") {
    if protectedSession then status.setText("Interrogation is unavailable during a protected image session.")
    else if folder.getText.trim.isEmpty then status.setText("Choose a folder first.")
    else
      val params = plus(baseParams("batch"), "folder" -> folder.getText.trim, "mode" -> batchMode.getValue,
        "output_mode" -> outputMode.getValue, "overwrite" -> overwrite.isSelected)
      start(params, "Batch interrogating…") { v =>
        val failed = v("failed").arr.map(_.str)
        val skipped = v("skipped_existing").arr.map(_.str)
        batchResult.setText(
          s"Processed ${v("processed").num.toInt} of ${v("total").num.toInt} images.\n" +
          (if skipped.nonEmpty then s"Skipped (output exists): ${skipped.mkString(", ")}\n" else "") +
          (if failed.nonEmpty then s"Could not read: ${failed.mkString(", ")}\n" else "") +
          s"Outputs: ${v("outputs").arr.take(5).map(_.str).mkString(", ")}${if v("outputs").arr.size > 5 then ", …" else ""}")
        status.setText(s"Batch finished: ${v("processed").num.toInt} prompt(s) written.")
      }
  }
  private val batchPage = new VBox(10, W.hbox(8, folder, browse),
    W.hbox(8, W.label("Prompt mode", "muted"), batchMode, W.label("Output", "muted"), outputMode),
    overwrite, W.hbox(8, go, cancelProxy()), batchResult)
  batchPage.setPadding(new Insets(10))

  // ---------------------------------------------------------------- About
  private val about = W.label(
    "Works out a prompt for an existing image with CLIP, after pharmapsychotic's CLIP Interrogator. " +
    "ViT-L-14/openai suits Stable Diffusion 1.x and XL; ViT-H-14/laion2b_s32b_b79k suits 2.x; ViT-bigG-14/laion2b_s39b_b160k also suits XL. " +
    "The first run downloads the CLIP and caption models from Hugging Face and computes the label embeddings once (cached in the config folder). " +
    "Press Unload when you are done to free memory. Models and label lists are the upstream ones; see the Extensions page for licences.", "muted")
  about.setWrapText(true)
  private val aboutPage = new VBox(10, about)
  aboutPage.setPadding(new Insets(10))

  private val pages = new TabPane()
  pages.setId("clip-pages")
  pages.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE)
  Seq("Prompt" -> promptPage, "Analyze" -> analyzePage, "Batch" -> batchPage, "About" -> aboutPage).foreach { (name, page) =>
    val scroll = new ScrollPane(page)
    scroll.setFitToWidth(true)
    val tab = new Tab(name, scroll)
    tab.setId("clip-page-" + name.toLowerCase)
    pages.getTabs.add(tab)
  }
  VBox.setVgrow(pages, Priority.ALWAYS)

  val node: VBox = new VBox(8, W.label("Interrogator", "section-title"),
    new FlowPane(8, 6, W.label("CLIP model", "muted"), clipModel, W.label("Caption model", "muted"), captionModel, keepLoaded, unload),
    pages, progress, status)
  node.setPadding(new Insets(14))
  node.setMinWidth(0)
  node.setId("clip-interrogator-tool")
  controls()

  /** Fill the model lists from the engine the first time the tab is opened. */
  def shown(): Unit =
    if !modelsLoaded && ctx.services.supervisor.state.isUsable then
      modelsLoaded = true
      val handle = ctx.services.client.clipInterrogatorOwned(ujson.Obj("operation" -> "models"))
      Fx.onFx(handle.result) { v =>
        if !disposed then
          val keepClip = clipModel.getValue
          clipModel.getItems.setAll(v("clip_models").arr.map(_.str).toSeq*)
          clipModel.setValue(if clipModel.getItems.contains(keepClip) then keepClip else v("default_clip_model").str)
          captionModel.getItems.setAll((v("caption_models").arr.map(_.str).toSeq :+ "none")*)
          captionModel.setValue(v("default_caption_model").str)
      }(_ => modelsLoaded = false)

  def dispose(): Unit =
    disposed = true
    active.foreach(_.interrupt())
    active = None

object ClipInterrogatorTab:
  val DefaultClip = "ViT-L-14/openai"
  val CaptionModels = Seq("blip-base", "blip-large", "blip2-2.7b", "blip2-flan-t5-xl", "git-large-coco")
  val AnalyzeKeys = Seq("medium", "artist", "movement", "trending", "flavor")
  val BatchOutputModes = Seq("Text file for each image", "Single text file with all prompts",
    "csv file with columns for filenames and prompts")
