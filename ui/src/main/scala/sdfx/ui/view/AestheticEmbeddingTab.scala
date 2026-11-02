package sdfx.ui.view

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import sdfx.engine.AestheticEmbeddingHandle
import sdfx.ui.Fx

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Creates and selects image-derived CLIP aesthetic embeddings. */
final class AestheticEmbeddingTab(ctx: UiContext, applyEmbedding: String => Either[String, Unit]):
  private val references = new ListView[Path]()
  references.setId("aesthetic-images")
  references.setPrefHeight(120)
  references.setMaxHeight(160)
  private val name = new TextField()
  name.setId("aesthetic-name")
  name.setPromptText("New embedding name")
  private val overwrite = new CheckBox("Replace an existing embedding")
  overwrite.setId("aesthetic-overwrite")
  private val embeddings = new ComboBox[String]()
  embeddings.setId("aesthetic-embeddings")
  embeddings.setPromptText("Select an aesthetic embedding")
  embeddings.setMaxWidth(Double.MaxValue)
  embeddings.setTooltip(new Tooltip("Select an embedding; the full path appears here."))
  private val status = W.label("Add reference images and choose a name, or refresh the embedding list.", "muted")
  status.setId("aesthetic-status")
  status.setWrapText(true)
  private val progress = new ProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS)
  progress.setVisible(false)
  private var active: Option[AestheticEmbeddingHandle] = None
  private var disposed = false

  private val add = W.button("Add images…", "Choose reference images") {
    val chooser = new FileChooser()
    chooser.setTitle("Choose reference images")
    chooser.getExtensionFilters.add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp"))
    Option(chooser.showOpenMultipleDialog(ctx.stage)).foreach { files =>
      val current = references.getItems.asScala.toSet
      references.getItems.addAll(files.asScala.map(_.toPath.toAbsolutePath.normalize()).filterNot(current.contains).distinct.asJava)
      controls()
    }
  }
  add.setId("aesthetic-add-images")
  private val remove = W.button("Remove selected", "Remove the selected reference image") {
    Option(references.getSelectionModel.getSelectedItem).foreach(references.getItems.remove)
    controls()
  }
  remove.setId("aesthetic-remove-image")
  private val refresh = W.button("Refresh list", "List available aesthetic embeddings") { request(ujson.Obj("op" -> "list"), false) }
  refresh.setId("aesthetic-refresh")
  private val create = W.button("Create embedding", "Create a CLIP image embedding", Seq("primary")) {
    try
      val params = ctx.services.imageScrambleSession.synchronized {
        val p = AestheticEmbeddingTab.createParams(name.getText, references.getItems.asScala.toSeq,
          overwrite.isSelected, ctx.services.imageScrambleSession.state.secret.nonEmpty)
        p("output_dir") = Files.createDirectories(managedDirectory).toAbsolutePath.normalize().toString
        p
      }
      request(params, true)
    catch case NonFatal(e) => status.setText(Fx.message(e).stripPrefix("requirement failed: "))
  }
  create.setId("aesthetic-create")
  private val cancel = W.button("Cancel", "Cancel only this embedding operation") {
    active.foreach(handle => Fx.onFx(handle.interrupt()) { decision =>
      if !disposed then status.setText(if decision.tooLate then "Output already committed; cancellation is too late."
        else if decision.blocked then "Cancellation blocked by interruption protection."
        else if decision.affected then "Cancelling…" else "No active embedding operation.")
    }(error => if !disposed then status.setText(Fx.message(error))))
  }
  cancel.setId("aesthetic-cancel")
  private val use = W.button("Use in generation", "Enable Aesthetic Gradients in the current generation mode") {
    Option(embeddings.getValue).filter(_.nonEmpty).foreach { selected =>
      applyEmbedding(selected) match
        case Right(_) => status.setText(s"Selected $selected for generation.")
        case Left(error) => status.setText(error)
    }
  }
  use.setId("aesthetic-use")
  private val help = W.label("Create a style embedding from one or more reference images, then use it with Aesthetic Gradients. " +
    "The first creation downloads CLIP ViT-L/14. Images marked as protected and protected image sessions cannot be used.", "muted")
  help.setWrapText(true)
  val node = new VBox(9, W.label("Aesthetic Embeddings", "section-title"), help, references,
    W.hbox(8, add, remove), name, overwrite, W.hbox(8, create, cancel), progress,
    embeddings, W.hbox(8, refresh, use), status)
  node.setPadding(new Insets(14)); node.setMinWidth(0)
  name.textProperty.addListener((_, _, _) => controls())
  embeddings.valueProperty.addListener((_, _, selected) =>
    embeddings.getTooltip.setText(Option(selected).getOrElse("Select an embedding; the full path appears here."))
    controls())
  references.getSelectionModel.selectedItemProperty.addListener((_, _, _) => controls())
  controls()

  private def controls(): Unit =
    val busy = active.nonEmpty
    add.setDisable(busy); references.setDisable(busy); name.setDisable(busy); overwrite.setDisable(busy)
    remove.setDisable(busy || references.getSelectionModel.getSelectedItem == null)
    create.setDisable(busy || references.getItems.isEmpty || name.getText.trim.isEmpty)
    refresh.setDisable(busy); embeddings.setDisable(busy)
    use.setDisable(busy || Option(embeddings.getValue).forall(_.isEmpty))
    cancel.setDisable(!busy); progress.setVisible(busy)

  private def managedDirectory: Path = ctx.services.generator.outputsDir.resolve("aesthetic_embeddings")

  /** Keep full paths so two files sharing a name remain distinguishable. */
  private def listPaths(result: ujson.Value): Vector[String] =
    val dirs = result("folders").arr.map(_.str).map { raw =>
      val path = Path.of(raw)
      if path.isAbsolute then path else ctx.services.home.pythonDir.resolve(path)
    }.toVector :+ managedDirectory
    dirs.distinct.flatMap { dir =>
      if !Files.isDirectory(dir) then Vector.empty
      else
        val stream = Files.list(dir)
        try stream.iterator().asScala.filter { p =>
          val n = p.getFileName.toString.toLowerCase(java.util.Locale.ROOT)
          Files.isRegularFile(p) && (n.endsWith(".pt") || n.endsWith(".safetensors"))
        }.map(_.toRealPath().toString).toVector.sorted
        finally stream.close()
    }.distinct

  private def request(params: ujson.Obj, creating: Boolean): Unit =
    if active.nonEmpty || disposed then return
    try
      val handle = ctx.services.client.aestheticEmbeddingOwned(params)
      active = Some(handle); controls()
      status.setText(if creating then "Creating embedding…" else "Listing embeddings…")
      Fx.onFx(handle.result) { result =>
        if !disposed then
          active = None
          try
            if creating then
              val path = result("path").str
              if !embeddings.getItems.contains(path) then embeddings.getItems.add(path)
              embeddings.setValue(path)
              val root = ctx.services.generator.outputsDir.toAbsolutePath.normalize()
              def permitted(p: Path): Boolean = scala.util.Try(p.toRealPath().startsWith(root.toRealPath())).getOrElse(false)
              val registered = sdfx.core.aesthetic.AestheticEmbeddingDirectories.register(
                ctx.services.home.configDir.resolve("aesthetic-embedding-directories.json"), Path.of(path).getParent, permitted)
              val warning = registered.left.toOption.fold("")(e => s" Directory could not be registered for replay: $e")
              status.setText(s"Created $path from ${result("count").num.toInt} image(s).$warning")
            else
              val previous = Option(embeddings.getValue)
              val paths = listPaths(result)
              embeddings.setItems(FXCollections.observableArrayList(paths.asJava))
              previous.filter(paths.contains).foreach(embeddings.setValue)
              status.setText(s"Found ${paths.size} aesthetic embedding(s).")
          catch case NonFatal(error) => status.setText(Fx.message(error))
          controls()
      } { error => if !disposed then
        active = None; controls(); status.setText(Fx.message(error))
      }
    catch case NonFatal(error) =>
      active = None; controls(); status.setText(Fx.message(error))

  def dispose(): Unit =
    disposed = true
    active.foreach(_.interrupt())
    active = None

object AestheticEmbeddingTab:
  def createParams(name: String, images: Seq[Path], overwrite: Boolean, protectedSession: Boolean): ujson.Obj =
    require(!protectedSession, "Aesthetic embedding creation is unavailable during a protected image session")
    val clean = Option(name).getOrElse("").trim
    require(clean.nonEmpty && !clean.startsWith(".") && !clean.exists(ch => "\\/:*?\"<>|".contains(ch)), "Name must be a plain file name")
    require(images.nonEmpty && images.size <= 32, "Select 1 to 32 reference images")
    val paths = images.map(_.toAbsolutePath.normalize())
    require(paths.forall(Files.isRegularFile(_)), "Every reference image must exist")
    require(paths.forall(p => !sdfx.core.png.PngMetadata.readTexts(p).contains("Encrypt")), "Protected images cannot be used")
    ujson.Obj("op" -> "create", "name" -> clean, "images" -> ujson.Arr.from(paths.map(_.toString)), "overwrite" -> overwrite)
