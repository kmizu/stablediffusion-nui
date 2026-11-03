package sdfx.ui.view

import javafx.animation.{AnimationTimer, PauseTransition}
import javafx.beans.property.*
import javafx.collections.FXCollections
import javafx.geometry.{Insets, Pos}
import javafx.scene.{Cursor, Scene}
import javafx.scene.control.*
import javafx.scene.image.{ImageView, PixelFormat, WritableImage}
import javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent, ScrollEvent}
import javafx.scene.layout.{Priority, Region, StackPane, VBox}
import javafx.scene.shape.Rectangle
import javafx.stage.{FileChooser, Stage}
import javafx.util.Duration
import sdfx.core.panorama.*
import sdfx.core.png.PngMetadata
import sdfx.ui.Fx
import sdfx.ui.vm.Mode

import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/** What the viewers show: one picture, how it is projected, and where the camera looks. */
final class PanoramaModel:
  val raster = new SimpleObjectProperty[Raster](null)
  val projection = new SimpleObjectProperty[Projection](Projection.Equirectangular)
  val yaw = new SimpleDoubleProperty(0)
  val pitch = new SimpleDoubleProperty(0)
  val fov = new SimpleDoubleProperty(90)
  /** File the picture came from (or was saved to); used to step through its folder and to send it on. */
  val path = new SimpleObjectProperty[Path](null)
  val video = new SimpleBooleanProperty(false)
  def view: View = View(yaw.get, pitch.get, fov.get)
  def setView(v: View): Unit =
    val n = v.normalized
    yaw.set(n.yaw); pitch.set(n.pitch); fov.set(n.fov)
  def resetView(): Unit = setView(View(0, 0, 90))

/** The interactive picture: drag to look around, wheel to zoom, arrow keys / +/- as well. Renders off the FX thread,
  * coarse while moving and sharp once it settles.
  */
final class PanoramaViewport(val model: PanoramaModel) extends Region:
  private val imageView = new ImageView()
  imageView.setSmooth(true)
  imageView.setPreserveRatio(false)
  getChildren.add(imageView)
  private val clip = new Rectangle()
  clip.widthProperty.bind(widthProperty)
  clip.heightProperty.bind(heightProperty)
  setClip(clip)
  setFocusTraversable(true)
  setCursor(Cursor.OPEN_HAND)
  setMinSize(120, 80)
  setStyle("-fx-background-color: #101114;")
  getStyleClass.add("panorama-viewport")

  private val executor = Executors.newSingleThreadExecutor(new ThreadFactory:
    def newThread(r: Runnable): Thread = { val t = new Thread(r, "panorama-render"); t.setDaemon(true); t })
  private val ticket = new AtomicLong(0)
  private val videoEpoch = new AtomicLong(0)
  private case class VideoRender(source: Raster, projection: Projection, view: View, width: Int, height: Int, epoch: Long)
  private val videoFrames = new LatestFrame[VideoRender](job =>
    val out = Panorama.render(job.source, job.projection, job.view, job.width, job.height)
    if videoEpoch.get == job.epoch then
      lastPixels = out; lastSize = (job.width, job.height); renders += 1
      Fx.run {
        if videoEpoch.get == job.epoch then
          val img = new WritableImage(job.width, job.height)
          img.getPixelWriter.setPixels(0, 0, job.width, job.height, PixelFormat.getIntArgbInstance, out.argb, 0, job.width)
          imageView.setImage(img)
      }
  )
  private var dragging = false
  /** True while something else (auto-rotate) moves the camera continuously. */
  var live = false
  private var lastX = 0.0
  private var lastY = 0.0
  private val settle = new PauseTransition(Duration.millis(140))
  settle.setOnFinished(_ => request(coarse = false))

  /** Number of finished renders, the size of the last one and the last pixels: seams for the native acceptance check. */
  @volatile var renders = 0
  @volatile var lastSize: (Int, Int) = (0, 0)
  @volatile var lastPixels: Raster = null

  private val invalidated: javafx.beans.InvalidationListener = _ => request(coarse = dragging || live)
  private val observed = Seq[javafx.beans.Observable](model.raster, model.projection, model.yaw, model.pitch, model.fov)
  observed.foreach(_.addListener(invalidated))
  widthProperty.addListener(invalidated)
  heightProperty.addListener(invalidated)
  private val videoModeChanged: javafx.beans.InvalidationListener = _ =>
    videoEpoch.incrementAndGet(); ticket.incrementAndGet(); request(coarse = false)
  model.video.addListener(videoModeChanged)

  def dispose(): Unit =
    observed.foreach(_.removeListener(invalidated))
    model.video.removeListener(videoModeChanged)
    ticket.incrementAndGet()
    videoEpoch.incrementAndGet()
    videoFrames.close()
    settle.stop()
    executor.shutdownNow()

  override protected def layoutChildren(): Unit =
    imageView.setFitWidth(getWidth)
    imageView.setFitHeight(getHeight)
    imageView.relocate(0, 0)

  /** Schedules a render of the current model; later requests make earlier unfinished ones obsolete. */
  def request(coarse: Boolean): Unit =
    val src = model.raster.get
    if src == null || getWidth < 8 || getHeight < 8 || executor.isShutdown then
      if src == null then imageView.setImage(null)
    else
      val isVideo = model.video.get
      val scale = if coarse || isVideo then 0.5 else 1.0
      val w = math.max(8, (getWidth * scale).toInt)
      val h = math.max(8, (getHeight * scale).toInt)
      val projection = model.projection.get
      val view = model.view
      val mine = ticket.incrementAndGet()
      if isVideo then
        videoFrames.submit(VideoRender(src, projection, view, w, h, videoEpoch.get))
      else try
        executor.execute { () =>
          if ticket.get == mine then
            try
              val out = Panorama.render(src, projection, view, w, h)
              if ticket.get == mine then
                lastPixels = out; lastSize = (w, h); renders += 1
                Fx.run {
                  if ticket.get == mine then
                    val img = new WritableImage(w, h)
                    img.getPixelWriter.setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance, out.argb, 0, w)
                    imageView.setImage(img)
                }
            catch case NonFatal(e) => System.err.println("[sdfx] panorama render failed: " + e)
        }
      catch case _: java.util.concurrent.RejectedExecutionException => ()
      if coarse then settle.playFromStart()

  setOnMousePressed { e =>
    requestFocus()
    if e.getButton == MouseButton.PRIMARY then
      dragging = true; lastX = e.getX; lastY = e.getY; setCursor(Cursor.CLOSED_HAND)
  }
  setOnMouseDragged { e =>
    if dragging && getWidth > 0 then
      // the scene follows the pointer: dragging right turns the camera left, dragging down looks up
      val degrees = model.fov.get / getWidth
      model.setView(model.view.turned(-(e.getX - lastX) * degrees, (e.getY - lastY) * degrees))
      lastX = e.getX; lastY = e.getY
  }
  setOnMouseReleased { _ =>
    if dragging then
      dragging = false; setCursor(Cursor.OPEN_HAND); request(coarse = false)
  }
  setOnMouseClicked { e => if e.getButton == MouseButton.PRIMARY && e.getClickCount == 2 then model.resetView() }
  setOnScroll { (e: ScrollEvent) =>
    if e.getDeltaY != 0 then
      model.setView(model.view.zoomed(if e.getDeltaY > 0 then 1 / 1.1 else 1.1))
      e.consume()
  }
  setOnKeyPressed { (e: KeyEvent) =>
    val step = model.fov.get / 18
    val handled = e.getCode match
      case KeyCode.LEFT => model.setView(model.view.turned(-step, 0)); true
      case KeyCode.RIGHT => model.setView(model.view.turned(step, 0)); true
      case KeyCode.UP => model.setView(model.view.turned(0, step)); true
      case KeyCode.DOWN => model.setView(model.view.turned(0, -step)); true
      case KeyCode.PLUS | KeyCode.ADD | KeyCode.EQUALS => model.setView(model.view.zoomed(1 / 1.1)); true
      case KeyCode.MINUS | KeyCode.SUBTRACT => model.setView(model.view.zoomed(1.1)); true
      case KeyCode.HOME | KeyCode.DIGIT0 => model.resetView(); true
      case _ => false
    if handled then e.consume()
  }

/** The "Panorama" tab: view equirectangular pictures and cubemap nets as an interactive 360° scene. */
final class PanoramaTab(ctx: UiContext, saveDir: () => Path):
  val model = new PanoramaModel
  val viewport = new PanoramaViewport(model)
  viewport.setId("panorama-viewport")
  private var disposed = false
  private var loadTicket = 0L
  private var fullscreen: Option[Stage] = None
  private var fullscreenViewport: Option[PanoramaViewport] = None

  private val Auto = "Auto-detect from the shape"
  private val projectionChoice = new ComboBox[String](FXCollections.observableArrayList(Auto, Projection.Equirectangular.label, Projection.CubemapNet.label))
  projectionChoice.setId("panorama-projection")
  projectionChoice.setValue(Auto)
  private val status = W.label("Send an image here from the gallery, the image browser or the preview, or open one.", "muted")
  status.setId("panorama-status")
  status.setWrapText(true)
  private val hintLabel = W.label("", "muted")
  hintLabel.setId("panorama-hint")
  hintLabel.setWrapText(true)
  private val nameLabel = W.label("No picture", "section-title")
  nameLabel.setId("panorama-name")
  private val readout = W.label("", "muted")
  readout.setId("panorama-readout")
  private val empty = new Label("Equirectangular (2:1) and cubemap (4:3) pictures\nopen here as a 360° view")
  empty.setMouseTransparent(true)
  empty.getStyleClass.add("empty-preview")
  empty.setId("panorama-empty")
  private var busy = false
  private val actions = scala.collection.mutable.ArrayBuffer.empty[Control]
  private var player: Option[PanoramaVideoPlayback] = None
  private var videoProbe: Option[PanoramaFrameDecoder] = None
  private var videoInfo: Option[PanoramaVideoInfo] = None
  private var videoTicket = 0L
  private var lastVideoTick = 0L
  private var updatingVideoSlider = false

  private def action(text: String, tip: String, id: String, primary: Boolean = false)(body: => Unit): Button =
    val b = W.button(text, tip, if primary then Seq("primary") else Nil)(body)
    b.setId(id)
    b

  private def say(message: String): Unit = status.setText(message)

  // ---- loading -------------------------------------------------------------------------------------------------
  /** Shows a picture from disk. Scrambled PNGs are decoded in memory with the current session password. */
  def open(path: Path): Unit =
    if disposed then return
    if !Files.isRegularFile(path) then { say(s"Not found: $path"); return }
    stopVideo()
    loadTicket += 1
    val mine = loadTicket
    say(s"Loading ${path.getFileName}…")
    ctx.imageCopyReads.request(path) {
      case scala.util.Success(display) =>
        if mine == loadTicket && !disposed then
          Fx.async(pixelsOf(display.image))(r => if mine == loadTicket && !disposed then install(r, Some(path), display.scrambled))(
            e => if mine == loadTicket then say("Cannot read the picture: " + Fx.message(e)))
      case scala.util.Failure(e) => if mine == loadTicket then say("Cannot read the picture: " + Fx.message(e).stripPrefix("requirement failed: "))
    }

  private def pixelsOf(image: javafx.scene.image.Image): Raster =
    val w = image.getWidth.toInt
    val h = image.getHeight.toInt
    PanoramaFiles.checkSize(w, h).left.foreach(m => throw new IllegalArgumentException(m))
    val data = new Array[Int](w * h)
    image.getPixelReader.getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance, data, 0, w)
    new Raster(w, h, data)

  private def chosenProjection(r: Raster): Projection =
    projectionChoice.getValue match
      case v if v == Projection.Equirectangular.label => Projection.Equirectangular
      case v if v == Projection.CubemapNet.label => Projection.CubemapNet
      case _ => Panorama.detect(r.width, r.height).getOrElse(Projection.Equirectangular)

  private def install(r: Raster, path: Option[Path], scrambled: Boolean = false): Unit =
    var projection = chosenProjection(r)
    var note = ""
    if projection == Projection.CubemapNet && !PanoramaFiles.isCubeNet(r.width, r.height) then
      projection = Projection.Equirectangular
      note = " A cubemap net needs square faces (width = 4 x face, height = 3 x face), so it is shown as equirectangular."
    model.path.set(path.orNull)
    model.projection.set(projection)
    model.raster.set(r)
    model.resetView()
    nameLabel.setText(path.fold("Converted picture")(_.getFileName.toString))
    hintLabel.setText(Panorama.hint(r.width, r.height, projection))
    empty.setVisible(false)
    controls()
    say(s"${r.width}x${r.height} shown as ${projection.label}." + note + (if scrambled then " Decoded in memory from a protected file." else ""))
    viewport.requestFocus()

  /** Previous / next picture of the same folder (wraps around). */
  def step(by: Int): Unit =
    Option(model.path.get).foreach { p =>
      PanoramaFiles.neighbour(p, by) match
        case Some(n) => open(n)
        case None => say("There is no other picture in this folder.")
    }

  private def chooseFile(): Unit =
    val fc = ImageFiles.chooser("Open a panorama")
    Option(model.path.get).flatMap(p => Option(p.getParent)).filter(Files.isDirectory(_)).foreach(d => fc.setInitialDirectory(d.toFile))
    Option(fc.showOpenDialog(ctx.stage)).foreach(f => open(f.toPath))

  /** Open local equirectangular video using the user's ffmpeg installation. */
  def openVideo(path: Path): Unit =
    if disposed then return
    if !Files.isRegularFile(path) then { say(s"Not found: $path"); return }
    val configured = ctx.services.settings.panoramaFfmpegExe.filter(_.trim.nonEmpty)
    if configured.isEmpty then { say("Choose an ffmpeg executable before opening video."); return }
    stopVideo()
    loadTicket += 1
    videoTicket += 1
    val mine = videoTicket
    try
      val ffmpeg = Path.of(configured.get).toAbsolutePath.normalize()
      val probeName = if System.getProperty("os.name").toLowerCase.startsWith("windows") then "ffprobe.exe" else "ffprobe"
      val ffprobe = ffmpeg.resolveSibling(probeName)
      if !Files.isRegularFile(ffmpeg) then throw new IllegalArgumentException("Configured ffmpeg executable not found")
      if !Files.isRegularFile(ffprobe) then throw new IllegalArgumentException("ffprobe was not found beside ffmpeg")
      val probe = new PanoramaFrameDecoder(ffprobe, 10000)
      val generation = probe.generation
      videoProbe = Some(probe)
      controls()
      say(s"Reading video information for ${path.getFileName}…")
      Fx.async(PanoramaVideoInfo.probeUsing(probe, ffprobe, path, generation)) { info =>
        if videoProbe.contains(probe) then videoProbe = None
        Fx.async(probe.close())(_ => ())(_ => ())
        if mine == videoTicket && !disposed then
          try
            val ratio = math.min(1.0, math.min(1024.0 / info.width, 1024.0 / info.height))
            val w = math.max(1, (info.width * ratio).toInt)
            val h = math.max(1, (info.height * ratio).toInt)
            val decoder = new PanoramaFrameDecoder(ffmpeg, 10000)
            var next: PanoramaVideoPlayback = null
            next = new PanoramaVideoPlayback(path, info.duration, w, h, decoder, () => System.nanoTime(),
              (epoch, _, bytes) =>
                val pixels = new Array[Int](w * h)
                var i = 0
                while i < pixels.length do
                  val j = i * 3
                  pixels(i) = 0xff000000 | ((bytes(j) & 255) << 16) | ((bytes(j + 1) & 255) << 8) | (bytes(j + 2) & 255)
                  i += 1
                val frame = new Raster(w, h, pixels)
                Fx.run {
                  if mine == videoTicket && !disposed && player.contains(next) && next.currentEpoch == epoch then
                    val firstFrame = model.raster.get == null
                    model.raster.set(frame)
                    if firstFrame then controls()
                },
              (epoch, error) => Fx.run {
                if mine == videoTicket && !disposed && player.contains(next) && next.currentEpoch == epoch then
                  stopVideo()
                  say("Cannot decode video: " + Fx.message(error))
              })
            player = Some(next)
            videoInfo = Some(info)
            model.path.set(null)
            model.projection.set(Projection.Equirectangular)
            model.video.set(true)
            model.resetView()
            nameLabel.setText(path.getFileName.toString)
            hintLabel.setText(Panorama.hint(info.width, info.height, Projection.Equirectangular))
            empty.setVisible(false)
            updatingVideoSlider = true
            try { videoSeek.setMax(info.duration); videoSeek.setValue(0.0) }
            finally updatingVideoSlider = false
            val selectedSpeed = Option(videoSpeed.getValue).fold(1.0)(_.doubleValue())
            if selectedSpeed != 1.0 then next.setSpeed(selectedSpeed)
            controls()
            say(s"Playing ${path.getFileName} as equirectangular video.")
            next.play()
          catch case NonFatal(e) =>
            stopVideo()
            say("Cannot play video: " + Fx.message(e))
      } { error =>
        if videoProbe.contains(probe) then videoProbe = None
        Fx.async(probe.close())(_ => ())(_ => ())
        if mine == videoTicket && !disposed then
          stopVideo()
          say("Cannot read video information: " + Fx.message(error))
      }
    catch case NonFatal(e) =>
      stopVideo()
      say("Cannot play video: " + Fx.message(e))

  def videoActive: Boolean = player.nonEmpty || videoProbe.nonEmpty
  def videoPaused: Boolean = player.exists(p => !p.playing)
  def videoSeconds: Double = player.fold(0.0)(_.position)
  def videoRate: Double = player.fold(1.0)(_.speed)
  def seekVideo(seconds: Double): Unit = player.foreach { p =>
    if seconds.isFinite && seconds >= 0 && videoInfo.exists(i => seconds <= i.duration) then p.seek(seconds)
  }
  def stopVideo(): Unit =
    videoTicket += 1
    val oldProbe = videoProbe; videoProbe = None
    val oldPlayer = player; player = None
    videoInfo = None
    oldProbe.foreach { p => p.cancel(); Fx.async(p.close())(_ => ())(_ => ()) }
    oldPlayer.foreach { p => Fx.async(p.close())(_ => ())(_ => ()) }
    model.video.set(false)
    controls()

  private def captureVideoFrame(now: Long): Unit = player.foreach { p =>
    if now - lastVideoTick >= 150000000L then
      lastVideoTick = now
      p.tick()
      updatingVideoSlider = true
      try videoSeek.setValue(p.position)
      finally updatingVideoSlider = false
  }

  // ---- converting & saving -------------------------------------------------------------------------------------
  private def convert(toCube: Boolean): Unit =
    val src = model.raster.get
    if src == null || busy then return
    val projection = model.projection.get
    val sourcePath = Option(model.path.get)
    if toCube && projection != Projection.Equirectangular then { say("Switch the projection to Equirectangular first: this picture is shown as a cubemap."); return }
    if !toCube && projection != Projection.CubemapNet then { say("Switch the projection to Cubemap first: this picture is shown as equirectangular."); return }
    busy = true; controls()
    say(if toCube then "Converting to a cubemap…" else "Converting to equirectangular…")
    val dir = saveDir()
    val password = ctx.services.imageScrambleSession.snapshot.map(_.use(identity))
    val name = sourcePath.fold("panorama.png")(_.getFileName.toString)
    Fx.async {
      val out = if toCube then Panorama.equirectToCubeNet(src, math.max(16, src.width / 4)) else Panorama.cubeNetToEquirect(src, src.width)
      val target = PanoramaFiles.uniqueTarget(dir, name, if toCube then "cubemap" else "equirect")
      PanoramaFiles.savePng(out, target, sourcePath, password)
      (out, target)
    } { case (out, target) =>
      busy = false
      if !disposed then
        val keep = projectionChoice.getValue
        projectionChoice.setValue(if toCube then Projection.CubemapNet.label else Projection.Equirectangular.label)
        install(out, Some(target))
        projectionChoice.setValue(keep)
        say(s"Converted and saved to $target")
        ctx.success("Saved " + target.getFileName)
    }(e => { busy = false; controls(); say("Conversion failed: " + Fx.message(e)) })

  /** Saves the picture the camera sees now; renders and writes in the background and returns the target at once. */
  def saveView(width: Int = 1600): Option[Path] =
    val src = model.raster.get
    if src == null then None
    else
      val h = math.max(2, (width * math.max(1.0, viewport.getHeight) / math.max(1.0, viewport.getWidth)).toInt)
      val projection = model.projection.get
      val view = model.view
      val sourcePath = Option(model.path.get)
      val target = PanoramaFiles.uniqueTarget(saveDir(), sourcePath.fold("panorama.png")(_.getFileName.toString), "view")
      val password = ctx.services.imageScrambleSession.snapshot.map(_.use(identity))
      saved = Some(target)
      Fx.async {
        PanoramaFiles.savePng(Panorama.render(src, projection, view, width, h), target, sourcePath, password)
        target
      }(t => { say(s"Saved the current view (${width}x$h) to $t"); ctx.success("Saved " + t.getFileName) })(e => say("Cannot save the view: " + Fx.message(e)))
      Some(target)
  /** Last path handed to saveView (test seam). */
  var saved: Option[Path] = None

  // ---- fullscreen ----------------------------------------------------------------------------------------------
  def openFullscreen(): Unit =
    if fullscreen.nonEmpty || model.raster.get == null then return
    val big = new PanoramaViewport(model)
    big.setId("panorama-fullscreen-viewport")
    val stage = new Stage()
    stage.initOwner(ctx.stage)
    stage.setTitle("Panorama")
    val hint = new Label("Drag to look around, wheel to zoom, Esc to leave")
    hint.setStyle("-fx-text-fill: #cccccc; -fx-background-color: rgba(0,0,0,0.5); -fx-padding: 4 10 4 10;")
    hint.setMouseTransparent(true)
    StackPane.setAlignment(hint, Pos.BOTTOM_CENTER)
    val root = new StackPane(big, hint)
    root.setStyle("-fx-background-color: black;")
    val scene = new Scene(root, 1280, 720)
    scene.addEventFilter(KeyEvent.KEY_PRESSED, (e: KeyEvent) => if e.getCode == KeyCode.ESCAPE then { closeFullscreen(); e.consume() })
    stage.setScene(scene)
    stage.setFullScreenExitHint("")
    stage.setOnHidden(_ => { big.dispose(); fullscreen = None; fullscreenViewport = None; viewport.request(coarse = false) })
    fullscreen = Some(stage); fullscreenViewport = Some(big)
    stage.setFullScreen(true)
    stage.show()
    big.requestFocus()

  def closeFullscreen(): Unit = fullscreen.foreach(_.close())
  def fullscreenOpen: Boolean = fullscreen.exists(_.isShowing)
  def fullscreenView: Option[PanoramaViewport] = fullscreenViewport

  // ---- controls ------------------------------------------------------------------------------------------------
  private val openButton = action("Open…", "Open an equirectangular picture or a cubemap net", "panorama-open")(chooseFile())
  private val openVideoButton = action("Open video…", "Open a local equirectangular video", "panorama-open-video") {
    val chooser = new FileChooser()
    chooser.setTitle("Open a panorama video")
    chooser.getExtensionFilters.add(new FileChooser.ExtensionFilter("Video", "*.mp4", "*.m4v", "*.mov", "*.mkv", "*.webm", "*.avi"))
    Option(chooser.showOpenDialog(ctx.stage)).foreach(f => openVideo(f.toPath))
  }
  private val ffmpegButton = action("Choose ffmpeg…", "Choose your local ffmpeg executable; ffprobe must be beside it", "panorama-choose-ffmpeg") {
    val chooser = new FileChooser()
    chooser.setTitle("Choose ffmpeg executable")
    Option(chooser.showOpenDialog(ctx.stage)).foreach { f =>
      val selected = f.toPath.toAbsolutePath.normalize().toString
      ctx.services.updateSettingsChecked(_.copy(panoramaFfmpegExe = Some(selected))) match
        case Right(_) => say("ffmpeg executable saved: " + selected)
        case Left(error) => say(error)
    }
  }
  private val videoPlay = action("Pause", "Pause or resume the panorama video", "panorama-video-play") {
    player.foreach { p => if p.playing then p.pause() else p.play() }
  }
  private val videoStop = action("Stop video", "Close the current video", "panorama-video-stop")(stopVideo())
  private val videoSeek = new Slider(0, 1, 0)
  videoSeek.setId("panorama-video-seek")
  videoSeek.valueProperty.addListener((_, _, value) => if !updatingVideoSlider then seekVideo(value.doubleValue()))
  private val videoSpeed = new ComboBox[java.lang.Double](FXCollections.observableArrayList(
    java.lang.Double.valueOf(0.25), java.lang.Double.valueOf(0.5), java.lang.Double.valueOf(1.0),
    java.lang.Double.valueOf(2.0), java.lang.Double.valueOf(4.0)))
  videoSpeed.setId("panorama-video-speed")
  videoSpeed.setValue(java.lang.Double.valueOf(1.0))
  videoSpeed.valueProperty.addListener((_, _, value) => if value != null then player.foreach(_.setSpeed(value.doubleValue())))
  private val prevButton = action("◀", "Previous picture in this folder", "panorama-prev")(step(-1))
  private val nextButton = action("▶", "Next picture in this folder", "panorama-next")(step(1))
  private val resetButton = action("Reset view", "Look straight ahead at 90° (double click does the same)", "panorama-reset")(model.resetView())
  private val directionButtons = Seq(
    ("front", "Front", 0.0, 0.0), ("back", "Back", 180.0, 0.0),
    ("left", "Left", -90.0, 0.0), ("right", "Right", 90.0, 0.0),
    ("up", "Up", 0.0, 90.0), ("down", "Down", 0.0, -90.0)
  ).map { (id, label, yaw, pitch) =>
    action(label, s"Look $label while keeping the current field of view", "panorama-direction-" + id) {
      model.setView(View(yaw, pitch, model.fov.get))
    }
  }
  private val fullButton = action("Fullscreen", "Show the panorama on a full-screen window; Esc leaves", "panorama-fullscreen")(openFullscreen())
  private val saveViewButton = action("Save view", "Save what the camera sees now as a PNG in the panorama folder of the outputs", "panorama-save-view")(saveView())
  private val toCube = action("→ Cubemap", "Convert the equirectangular picture to a 4:3 cubemap net, save it and show it", "panorama-to-cube")(convert(toCube = true))
  private val toEqui = action("→ Equirectangular", "Convert the cubemap net back to an equirectangular picture, save it and show it", "panorama-to-equirect")(convert(toCube = false))
  private def sendOn(f: Path => Unit): Unit =
    Option(model.path.get).foreach(f)
  private val toImg2img = action("⇢ img2img", "Send this picture and its parameters to img2img", "panorama-to-img2img")(
    sendOn(p => ctx.sendToTab(Mode.Img2Img, p, PngMetadata.readParameters(p))))
  private val toInpaint = action("⇢ Inpaint", "Send this picture to Inpaint, e.g. to repair the seams or the poles", "panorama-to-inpaint")(
    sendOn(p => ctx.sendToTab(Mode.Inpaint, p, PngMetadata.readParameters(p))))
  private val toExtras = action("⇢ Extras", "Send this picture to Extras, e.g. to upscale it", "panorama-to-extras")(sendOn(ctx.sendToExtras))
  private val pathButtons = Seq(prevButton, nextButton, toImg2img, toInpaint, toExtras)
  private val pictureButtons = Seq(resetButton, fullButton, saveViewButton, toCube, toEqui) ++ directionButtons
  private val autoRotate = new CheckBox("Auto-rotate")
  autoRotate.setId("panorama-autorotate")
  W.tip(autoRotate, "Turn slowly to the right while the tab is open")
  private val speed = new SimpleDoubleProperty(12)
  private val speedSpinner = W.doubleSpinner(speed, 1, 90, 1, 80)
  speedSpinner.setId("panorama-speed")
  W.tip(speedSpinner, "Degrees per second")
  private val yawSpinner = W.doubleSpinner(model.yaw, -180, 180, 5, 92)
  yawSpinner.setId("panorama-yaw")
  private val pitchSpinner = W.doubleSpinner(model.pitch, -90, 90, 5, 92)
  pitchSpinner.setId("panorama-pitch")
  private val fovBox = W.doubleSlider(model.fov, View.MinFov, View.MaxFov, 5)
  fovBox.setId("panorama-fov")

  private def controls(): Unit =
    val has = model.raster.get != null
    val hasPath = has && model.path.get != null
    pictureButtons.foreach(_.setDisable(!has || busy))
    pathButtons.foreach(_.setDisable(!hasPath))
    openButton.setDisable(busy)
    openVideoButton.setDisable(busy)
    videoPlay.setDisable(player.isEmpty)
    videoStop.setDisable(!videoActive)
    videoSeek.setDisable(player.isEmpty)
    videoSpeed.setDisable(player.isEmpty)
    projectionChoice.setDisable(videoActive)
    toCube.setDisable(!has || busy || videoActive || model.projection.get != Projection.Equirectangular)
    toEqui.setDisable(!has || busy || videoActive || model.projection.get != Projection.CubemapNet)

  projectionChoice.valueProperty.addListener((_, _, _) =>
    Option(model.raster.get).foreach { r =>
      val wanted = chosenProjection(r)
      if wanted == Projection.CubemapNet && !PanoramaFiles.isCubeNet(r.width, r.height) then
        say(s"${r.width}x${r.height} cannot be a cubemap net: it needs square faces (width = 4 x face, height = 3 x face).")
        projectionChoice.setValue(Auto)
      else model.projection.set(wanted)
      hintLabel.setText(Panorama.hint(r.width, r.height, model.projection.get))
      controls()
    })
  private val readoutListener: javafx.beans.InvalidationListener = _ =>
    readout.setText(f"yaw ${model.yaw.get}%.1f°  pitch ${model.pitch.get}%.1f°  fov ${model.fov.get}%.0f°")
  Seq[javafx.beans.Observable](model.yaw, model.pitch, model.fov).foreach(_.addListener(readoutListener))
  readoutListener.invalidated(null)

  private var lastTick = 0L
  private val timer = new AnimationTimer:
    override def handle(now: Long): Unit =
      val dt = if lastTick == 0 then 0.0 else (now - lastTick) / 1e9
      lastTick = now
      captureVideoFrame(now)
      videoPlay.setText(if videoPaused then "Play" else "Pause")
      if autoRotate.isSelected && model.raster.get != null && node.isVisible && node.getScene != null then
        model.setView(model.view.turned(speed.get * math.min(dt, 0.25), 0))
  autoRotate.selectedProperty.addListener((_, _, on) => { viewport.live = on; lastTick = 0L; if !on then viewport.request(coarse = false) })
  timer.start()

  private val toolbar1 = W.hbox(6, openButton, openVideoButton, prevButton, nextButton, nameLabel, W.spacer(), W.label("Projection"), projectionChoice)
  private val toolbar2 = W.hbox(8, W.label("Yaw"), yawSpinner, W.label("Pitch"), pitchSpinner, W.label("FOV"), W.hgrow(fovBox), resetButton, autoRotate, speedSpinner)
  private val toolbar3 = W.hbox(6, fullButton, saveViewButton, toCube, toEqui, W.spacer(), toImg2img, toInpaint, toExtras)
  private val videoToolbar = W.hbox(6, ffmpegButton, videoPlay, videoStop, W.label("Speed"), videoSpeed, W.label("Video position"), W.hgrow(videoSeek))
  private val directionsToolbar = W.hbox(6, (Seq(W.label("Look")) ++ directionButtons)*)
  private val stage = new StackPane(viewport, empty)
  VBox.setVgrow(stage, Priority.ALWAYS)
  val node: VBox = new VBox(8, toolbar1, toolbar2, toolbar3, directionsToolbar, videoToolbar, stage, W.hbox(12, readout, W.hgrow(hintLabel)), status)
  node.setPadding(new Insets(10))
  node.setId("panorama-tab")
  node.setMinWidth(0)

  // drop an image file on the viewer
  stage.setOnDragOver { e =>
    if e.getDragboard.hasFiles || e.getDragboard.hasImage then e.acceptTransferModes(javafx.scene.input.TransferMode.COPY)
    e.consume()
  }
  stage.setOnDragDropped { e =>
    val ok = ImageFiles.fromDragboard(ctx, e.getDragboard)(open)
    e.setDropCompleted(ok); e.consume()
  }
  controls()

  def dispose(): Unit =
    disposed = true
    timer.stop()
    stopVideo()
    closeFullscreen()
    viewport.dispose()

  def shown(): Unit = viewport.request(coarse = false)
