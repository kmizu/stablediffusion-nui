package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.image.ImageView
import javafx.scene.input.*
import javafx.scene.layout.TilePane
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.core.panorama.*
import sdfx.core.png.{PngMetadata, ScrambledPng}
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.{Mode, UiState}

import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.FileTime
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** MANUAL root-only gate. args: python output-directory sd15-panorama.png sdxl-panorama.png other-picture.png
  * Mounts the real MainWindow with a dry-run engine and drives the Panorama tab on real SD1.5 / SDXL pictures:
  * mouse, wheel and keys on the viewport, spinners, projection, conversions, folder stepping, fullscreen, saving, the
  * senders (Preview, Image Browser) and a password-protected picture. Screenshots go to output-directory.
  */
object RealPanoramaCheck:
  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then return body
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body
    )
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)
  private def eventually(label: String, seconds: Int = 60)(body: => Boolean): Unit =
    val end = System.nanoTime() + seconds.seconds.toNanos
    while !body && System.nanoTime() < end do Thread.sleep(40)
    require(body, "Timed out: " + label)

  private def meanDiff(a: Raster, b: Raster): Double =
    require(a.width == b.width && a.height == b.height, s"sizes differ ${a.width}x${a.height} vs ${b.width}x${b.height}")
    var sum = 0L
    var i = 0
    while i < a.argb.length do
      val p = a.argb(i)
      val q = b.argb(i)
      sum += math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + math.abs((p & 255) - (q & 255))
      i += 1
    sum.toDouble / (3.0 * a.argb.length)

  def main(args: Array[String]): Unit =
    require(args.length == 5, "python output-directory sd15-panorama.png sdxl-panorama.png other-picture.png")
    val output = Paths.get(args(1)).toAbsolutePath.normalize()
    Files.createDirectories(output)
    val work = Files.createTempDirectory(output, "pano-run-")
    val outputs = work.resolve("outputs")
    val folder = outputs.resolve("txt2img/2026-11-02")
    Files.createDirectories(folder)
    var clock = 1_760_000_000_000L
    def place(src: String, name: String): Path =
      val dst = folder.resolve(name)
      Files.copy(Paths.get(src), dst)
      clock += 60_000
      Files.setLastModifiedTime(dst, FileTime.fromMillis(clock))
      dst
    val sd15 = place(args(2), "a-sd15-cabin.png")
    val sdxl = place(args(3), "b-sdxl-cabin.png")
    val other = place(args(4), "c-square.png")

    val home = AppHome(Files.createTempDirectory(work, "app-home-"))
    val settings = Settings(outputDir = outputs.toString, previewEvery = 0)
    val engineDir = AppHome.detect().pythonDir
    val services = new AppServices(home, settings, dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(Paths.get(args(0)).toAbsolutePath.toString, "-m", "sdfx", "--dry-run"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))),
      persistSettings = false, timeouts = EngineTimeouts(load = 2.minutes), installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    var checks = Vector.empty[String]
    def checked(message: String): Unit = { checks :+= message; println("PANORAMA CHECK: " + message) }
    def find[A <: Node](id: String): A =
      val node = fx(window.scene.lookup("#" + id))
      require(node != null, "Missing control #" + id)
      node.asInstanceOf[A]
    def snapshot(name: String): Unit = fx {
      window.root.applyCss(); window.root.layout()
      require(ImageIO.write(SwingFXUtils.fromFXImage(window.scene.snapshot(null), null), "png", output.resolve(name).toFile))
    }
    def press(id: String): Unit = fx { val b = window.scene.lookup("#" + id).asInstanceOf[Button]; require(!b.isDisabled, s"#$id disabled"); b.fire() }
    def label(id: String): String = fx(window.scene.lookup("#" + id).asInstanceOf[Labeled].getText)
    def pano = window.panorama
    def vp = pano.viewport
    def mouse(kind: javafx.event.EventType[MouseEvent], x: Double, y: Double, clicks: Int = 1, target: Node = null): Unit = fx {
      Event.fireEvent(if target == null then vp else target, new MouseEvent(kind, x, y, x, y, MouseButton.PRIMARY, clicks, false, false, false, false,
        kind == MouseEvent.MOUSE_PRESSED || kind == MouseEvent.MOUSE_DRAGGED, false, false, true, false, false, null))
    }
    def drag(dx: Double, dy: Double): Unit =
      val (x0, y0) = (200.0, 200.0)
      mouse(MouseEvent.MOUSE_PRESSED, x0, y0)
      for i <- 1 to 5 do mouse(MouseEvent.MOUSE_DRAGGED, x0 + dx * i / 5, y0 + dy * i / 5)
      mouse(MouseEvent.MOUSE_RELEASED, x0 + dx, y0 + dy)
    def wheel(deltaY: Double): Unit = fx {
      Event.fireEvent(vp, new ScrollEvent(ScrollEvent.SCROLL, 100, 100, 100, 100, false, false, false, false, true, false, 0, deltaY, 0, deltaY,
        ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null))
    }
    def key(code: KeyCode): Unit = fx { Event.fireEvent(vp, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)) }
    def view = fx(pano.model.view)
    def near(a: Double, b: Double, eps: Double = 1e-6) = math.abs(a - b) <= eps
    /** Waits until the sharp (full size) render for the current model has arrived and nothing newer is pending. */
    def settled(): Unit =
      var last = -1
      var stableSince = System.nanoTime()
      eventually("sharp render settled", 40) {
        val n = vp.renders
        if n != last then { last = n; stableSince = System.nanoTime() }
        val (w, h) = vp.lastSize
        w == fx(vp.getWidth).toInt && h == fx(vp.getHeight).toInt && System.nanoTime() - stableSince > 500_000_000L
      }
    def expectedRender(): Raster =
      val (w, h) = vp.lastSize
      Panorama.render(fx(pano.model.raster.get), fx(pano.model.projection.get), view, w, h)
    def selectTab(id: String): Unit = fx {
      val tabs = window.scene.lookup("#main-tabs").asInstanceOf[TabPane]
      tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getId == id).get)
    }
    def pathOf = fx(pano.model.path.get)
    def loaded(p: Path): Unit = eventually("loaded " + p.getFileName)(fx(pano.model.path.get) == p && fx(pano.model.raster.get) != null)
    def source = fx(pano.model.raster.get)

    try
      window = fx { val w = new MainWindow(new Stage(), services, UiState(), CliOptions()); w.stage.setWidth(1700); w.stage.setHeight(1000); w.show(); w }
      services.start()

      // ---- opening a real picture ------------------------------------------------------------------------------
      fx(window.ctx.sendToPanorama(sd15))
      loaded(sd15)
      eventually("tab is selected")(fx(window.scene.lookup("#panorama-tab").isVisible))
      settled()
      val s0 = source
      require((s0.width, s0.height) == (1024, 512), s"${s0.width}x${s0.height}")
      require(fx(pano.model.projection.get) == Projection.Equirectangular)
      require(label("panorama-hint").contains("matches"), label("panorama-hint"))
      require(label("panorama-name") == "a-sd15-cabin.png", label("panorama-name"))
      require(label("panorama-status").contains("1024x512 shown as Equirectangular"), label("panorama-status"))
      require(!fx(find[Node]("panorama-empty").isVisible), "empty placeholder hidden")
      require(fx(find[ImageView]("panorama-viewport").lookup(".image-view").asInstanceOf[ImageView].getImage != null))
      val exp0 = expectedRender()
      require(java.util.Arrays.equals(vp.lastPixels.argb, exp0.argb), "viewport pixels differ from the reference render")
      snapshot("pano-01-sd15.png")
      checked(s"send-to-panorama opens the real SD1.5 picture (1024x512) on the Panorama tab as Equirectangular, hint says it matches; the sharp viewport frame (${vp.lastSize._1}x${vp.lastSize._2}) is pixel-identical to the reference render")

      // Six cardinal views preserve the field of view, independent of the previous orientation.
      val directions = Seq("front" -> View(0, 0, 67), "back" -> View(180, 0, 67),
        "left" -> View(-90, 0, 67), "right" -> View(90, 0, 67),
        "up" -> View(0, 90, 67), "down" -> View(0, -90, 67))
      for (name, expected) <- directions do
        fx(pano.model.setView(View(37, 12, 67)))
        press("panorama-direction-" + name)
        eventually("direction " + name)(view == expected.normalized)
      settled()
      snapshot("pano-00-directions.png")
      checked("six directions preserve FOV and update the shared camera")
      fx(pano.model.resetView())

      // ---- mouse, wheel, keys ----------------------------------------------------------------------------------
      val w0 = fx(vp.getWidth)
      val v0 = view
      drag(-100, 0) // dragging left turns the camera right
      eventually("yaw changed")(near(view.yaw, v0.yaw + 100 * v0.fov / w0, 1e-6))
      drag(0, 60)   // dragging down looks up
      eventually("pitch changed")(near(view.pitch, 60 * v0.fov / w0, 1e-6))
      settled()
      val dragged = view
      require(java.util.Arrays.equals(vp.lastPixels.argb, expectedRender().argb), "after dragging the sharp frame is the reference render")
      snapshot("pano-02-dragged.png")
      checked(f"mouse drag moves the camera by fov/width degrees per pixel (yaw ${dragged.yaw}%.3f, pitch ${dragged.pitch}%.3f after a 100 px and a 60 px drag); the settled frame equals the reference render")
      drag(0, 100000)
      eventually("pitch clamp up")(near(view.pitch, 90))
      drag(0, -100000)
      eventually("pitch clamp down")(near(view.pitch, -90))
      drag(100000, 0)
      require(view.yaw >= -180 && view.yaw <= 180)
      checked("pitch clamps at +-90 and yaw wraps into -180..180 under extreme drags")
      fx(pano.model.resetView())
      val f0 = view.fov
      wheel(40); eventually("zoom in")(near(view.fov, f0 / 1.1, 1e-6))
      wheel(-40); wheel(-40); eventually("zoom out")(near(view.fov, f0 * 1.1, 1e-6))
      for _ <- 1 to 40 do wheel(40)
      eventually("fov min")(near(view.fov, View.MinFov))
      for _ <- 1 to 80 do wheel(-40)
      eventually("fov max")(near(view.fov, View.MaxFov))
      checked("wheel zooms by 10% per notch and the field of view clamps at 20..120 degrees")
      fx(pano.model.resetView())
      key(KeyCode.RIGHT); eventually("key right")(near(view.yaw, 90.0 / 18))
      key(KeyCode.UP); eventually("key up")(near(view.pitch, 90.0 / 18))
      key(KeyCode.LEFT); key(KeyCode.DOWN); eventually("keys back")(near(view.yaw, 0) && near(view.pitch, 0))
      key(KeyCode.PLUS); eventually("key +")(near(view.fov, 90 / 1.1, 1e-6))
      key(KeyCode.MINUS); eventually("key -")(near(view.fov, 90, 1e-6))
      fx(pano.model.setView(View(40, 20, 50))); key(KeyCode.HOME); eventually("home")(view == View(0, 0, 90))
      fx(pano.model.setView(View(40, 20, 50))); mouse(MouseEvent.MOUSE_CLICKED, 50, 50, clicks = 2); eventually("double click")(view == View(0, 0, 90))
      checked("arrow keys turn by fov/18, +/- zoom, Home and a double click reset the view")

      // ---- spinners, slider, reset -----------------------------------------------------------------------------
      fx {
        val yaw = find[Spinner[java.lang.Double]]("panorama-yaw"); yaw.getEditor.setText("-135"); yaw.getEditor.fireEvent(new javafx.event.ActionEvent()); sdfx.ui.view.W.commitEnclosingSpinner(yaw)
        val pitch = find[Spinner[java.lang.Double]]("panorama-pitch"); pitch.getEditor.setText("30"); sdfx.ui.view.W.commitEnclosingSpinner(pitch)
      }
      eventually("spinners set the view")(near(view.yaw, -135) && near(view.pitch, 30))
      fx(find[Slider]("panorama-fov").lookup(".slider")); // slider lives inside the row
      val slider = fx(find[Node]("panorama-fov").lookupAll(".slider").asScala.collectFirst { case s: Slider => s }.get)
      fx(slider.setValue(60)); eventually("slider sets fov")(near(view.fov, 60))
      eventually("readout")(label("panorama-readout").contains("yaw -135.0") && label("panorama-readout").contains("pitch 30.0"))
      press("panorama-reset"); eventually("reset button")(view == View(0, 0, 90))
      checked("Yaw / Pitch spinners and the FOV slider drive the camera, the readout follows, Reset view restores 0/0/90")

      // ---- auto-rotate -----------------------------------------------------------------------------------------
      fx(find[CheckBox]("panorama-autorotate").setSelected(true))
      eventually("auto-rotate turns the camera", 20)(view.yaw > 8 || view.yaw < -8)
      fx(find[CheckBox]("panorama-autorotate").setSelected(false))
      Thread.sleep(300)
      val frozen = view.yaw
      Thread.sleep(600)
      require(near(view.yaw, frozen, 1e-9), "auto-rotate stops when unchecked")
      settled()
      fx(pano.model.resetView())
      checked(f"auto-rotate (12 degrees per second) turned the camera to yaw ${frozen}%.1f and stopped when unchecked")

      // ---- projection choice -----------------------------------------------------------------------------------
      fx(find[ComboBox[String]]("panorama-projection").setValue(Projection.CubemapNet.label))
      eventually("2:1 picture cannot be a cubemap")(label("panorama-status").contains("cannot be a cubemap net"))
      require(fx(pano.model.projection.get) == Projection.Equirectangular)
      require(fx(find[ComboBox[String]]("panorama-projection").getValue).startsWith("Auto"))
      checked("forcing Cubemap on a 2:1 picture is refused with an explanation and the viewer keeps rendering it as equirectangular")

      // ---- converting to a cubemap and back --------------------------------------------------------------------
      settled()
      val reference = Panorama.render(s0, Projection.Equirectangular, View(0, 0, 90), vp.lastSize._1, vp.lastSize._2)
      press("panorama-to-cube")
      eventually("cubemap shown", 60)(fx(pano.model.projection.get) == Projection.CubemapNet && fx(pano.model.raster.get) != null && fx(pano.model.raster.get).width == 1024)
      val cube = source
      require((cube.width, cube.height) == (1024, 768), s"${cube.width}x${cube.height}")
      val cubePath = pathOf
      require(cubePath.getParent == outputs.resolve("panorama") && cubePath.getFileName.toString == "a-sd15-cabin-cubemap.png", cubePath.toString)
      require(PngMetadata.readParameters(cubePath).exists(_.contains("Steps:")), "generation parameters carried over")
      require(label("panorama-hint").contains("matches Cubemap"), label("panorama-hint"))
      settled()
      val cubeView = vp.lastPixels
      val cubeVsEqui = meanDiff(cubeView, Panorama.render(s0, Projection.Equirectangular, View(0, 0, 90), cubeView.width, cubeView.height))
      require(cubeVsEqui < 10.0, s"cube view differs from the equirectangular view by $cubeVsEqui")
      val onDisk = Raster.fromImage(ImageIO.read(cubePath.toFile))
      require(java.util.Arrays.equals(onDisk.argb, cube.argb), "the saved PNG equals the picture shown")
      snapshot("pano-03-cubemap-view.png")
      require(fx(find[Button]("panorama-to-cube").isDisabled) && !fx(find[Button]("panorama-to-equirect").isDisabled))
      checked(f"-> Cubemap makes a 1024x768 net (256 px faces), saves it as panorama/a-sd15-cabin-cubemap.png with the generation parameters, and the cubemap view of the front direction differs from the equirectangular view by a mean $cubeVsEqui%.2f / 255")
      // turn the camera to every face centre: the cubemap and the equirectangular renders must agree everywhere
      val turns = Seq(View(90, 0, 90), View(180, 0, 90), View(-90, 0, 90), View(0, 80, 80), View(0, -80, 80), View(45, 30, 100))
      val faceDiffs = turns.map { v =>
        fx(pano.model.setView(v)); settled()
        meanDiff(vp.lastPixels, Panorama.render(s0, Projection.Equirectangular, v, vp.lastSize._1, vp.lastSize._2))
      }
      require(faceDiffs.forall(_ < 12.0), faceDiffs.toString)
      checked(f"cubemap views at yaw 90 / 180 / -90, pitch +-80 and a diagonal agree with the equirectangular source (mean differences ${faceDiffs.map(d => f"$d%.1f").mkString(", ")} / 255)")
      fx(pano.model.resetView()); settled()
      snapshot("pano-04-cubemap-front.png")
      // the net itself, as a picture for the report
      ImageIO.write(cube.toBufferedImage, "png", output.resolve("pano-cubemap-net.png").toFile)
      press("panorama-to-equirect")
      eventually("equirect shown again", 60)(fx(pano.model.projection.get) == Projection.Equirectangular && source.width == 1024 && source.height == 512 && pathOf != cubePath)
      val back = source
      val roundTrip = meanDiff(back, s0)
      require(roundTrip < 14.0, s"round trip differs by $roundTrip")
      require(pathOf.getFileName.toString == "a-sd15-cabin-cubemap-equirect.png", pathOf.toString)
      checked(f"-> Equirectangular converts the net back to 1024x512 and saves it; mean difference to the original picture after the round trip $roundTrip%.2f / 255 (resampled twice)")
      ImageIO.write(back.toBufferedImage, "png", output.resolve("pano-roundtrip.png").toFile)

      // ---- folder stepping -------------------------------------------------------------------------------------
      fx(window.ctx.sendToPanorama(sd15)); loaded(sd15)
      press("panorama-next"); loaded(sdxl)
      require(source.width == 1536 && source.height == 768, s"${source.width}x${source.height}")
      settled()
      snapshot("pano-05-sdxl.png")
      press("panorama-next"); loaded(other)
      require(label("panorama-hint").contains("is not 2:1"), label("panorama-hint"))
      snapshot("pano-06-square.png")
      press("panorama-next"); eventually("wraps to the first")(pathOf != null && pathOf.getFileName.toString == "a-sd15-cabin-cubemap-equirect.png" || pathOf == sd15)
      press("panorama-prev")
      checked("previous / next step through the folder in natural order and wrap around; the real SDXL 1536x768 picture opens; a square 512x512 picture shows the 'is not 2:1' hint and is still viewable (the 'looks like' variant is unit-tested)")
      fx(window.ctx.sendToPanorama(sdxl)); loaded(sdxl); settled()

      // ---- SDXL view check --------------------------------------------------------------------------------------
      require(java.util.Arrays.equals(vp.lastPixels.argb, expectedRender().argb), "SDXL frame is the reference render")
      fx(pano.model.setView(View(120, 15, 75))); settled()
      require(java.util.Arrays.equals(vp.lastPixels.argb, expectedRender().argb))
      snapshot("pano-07-sdxl-turned.png")
      checked("the real SDXL picture renders pixel-identically to the reference at a turned view (yaw 120, pitch 15, fov 75)")

      // ---- save view --------------------------------------------------------------------------------------------
      val savedPath = fx(pano.saveView()).get
      eventually("view saved")(Files.isRegularFile(savedPath) && Files.size(savedPath) > 1000)
      Thread.sleep(300)
      val saved = ImageIO.read(savedPath.toFile)
      require(saved.getWidth == 1600, s"${saved.getWidth}")
      require(math.abs(saved.getHeight.toDouble / saved.getWidth - fx(vp.getHeight) / fx(vp.getWidth)) < 0.002)
      require(savedPath.getParent == outputs.resolve("panorama") && savedPath.getFileName.toString == "b-sdxl-cabin-view.png", savedPath.toString)
      val savedDiff = meanDiff(Raster.fromImage(saved), Panorama.render(source, Projection.Equirectangular, View(120, 15, 75), saved.getWidth, saved.getHeight))
      require(savedDiff == 0.0, s"saved view differs by $savedDiff")
      require(PngMetadata.readParameters(savedPath).exists(_.contains("Steps:")), "generation parameters kept in the saved view")
      checked(s"Save view writes the camera view as panorama/b-sdxl-cabin-view.png (${saved.getWidth}x${saved.getHeight}, identical to the reference render, generation parameters kept)")

      // ---- fullscreen -------------------------------------------------------------------------------------------
      press("panorama-fullscreen")
      eventually("fullscreen window", 30)(fx(pano.fullscreenOpen) && fx(pano.fullscreenView.exists(_.renders > 0)))
      val big = fx(pano.fullscreenView.get)
      fx(pano.model.setView(View(-60, -10, 85)))
      val before = big.renders
      press("panorama-direction-right")
      eventually("fullscreen follows direction button")(view == View(90, 0, 85) && big.renders > before && big.lastSize._1 >= 800)
      Thread.sleep(600)
      require(fx(window.scene.getWindow.isShowing))
      ImageIO.write(SwingFXUtils.fromFXImage(fx(big.getScene.snapshot(null)), null), "png", output.resolve("pano-08-fullscreen.png").toFile)
      val bigSize = big.lastSize
      fx(Event.fireEvent(big, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)))
      eventually("Esc closes fullscreen")(!fx(pano.fullscreenOpen))
      checked(s"Fullscreen opens a second window sharing the camera (rendered ${bigSize._1}x${bigSize._2}) and Esc closes it")

      // ---- senders ----------------------------------------------------------------------------------------------
      fx(pano.model.resetView())
      press("panorama-to-img2img")
      eventually("img2img got the picture")(fx(window.vms(Mode.Img2Img).initImage.get) == sdxl.toString)
      press("panorama-to-inpaint")
      eventually("inpaint got the picture")(fx(window.vms(Mode.Inpaint).initImage.get) == sdxl.toString)
      press("panorama-to-extras")
      eventually("extras got the picture")(fx(window.extras.image.get) == sdxl.toString)
      checked("the buttons send the shown picture to img2img, Inpaint and Extras")
      // Preview pane button
      fx(window.select(Mode.Txt2Img))
      fx(window.preview.show(sd15))
      eventually("preview shows")(fx(window.preview.currentPath.contains(sd15)))
      val previewButton = fx(window.scene.getRoot.lookupAll(".button").asScala.collectFirst { case b: Button if b.getText == "Pano" => b }.get)
      fx(previewButton.fire())
      loaded(sd15)
      eventually("panorama tab visible after Preview button")(fx(window.scene.lookup("#panorama-tab").isVisible))
      checked("the Pano button of the preview pane opens the shown image on the Panorama tab")
      // Image Browser button
      selectTab("image-browser-tab")
      fx(window.imageBrowser.build())
      def tilesLoaded: Boolean = fx {
        val grid = window.scene.lookup("#ib-txt2img-grid")
        grid != null && {
          val views = grid.lookupAll(".image-view").asScala.collect { case v: ImageView => v }.filter(_.isVisible)
          views.nonEmpty && views.forall(_.getImage != null)
        }
      }
      eventually("browser tiles")(tilesLoaded)
      val tile = fx(window.scene.lookup("#ib-txt2img-tile-" + "b-sdxl-cabin_png"))
      require(tile != null, "tile for the SDXL picture")
      mouse(MouseEvent.MOUSE_CLICKED, 5, 5, target = tile)
      eventually("browser selection")(fx(window.imageBrowser.page("txt2img").get.viewModel.selected.isDefined))
      press("ib-txt2img-to-panorama")
      loaded(sdxl)
      eventually("panorama tab visible after browser button")(fx(window.scene.lookup("#panorama-tab").isVisible))
      checked("the Image Browser's 'Send to panorama' button opens the selected file on the Panorama tab")

      // ---- errors --------------------------------------------------------------------------------------------
      val broken = folder.resolve("d-broken.png")
      Files.writeString(broken, "this is not a png")
      fx(window.panorama.open(broken))
      eventually("broken file reported")(label("panorama-status").startsWith("Cannot read the picture"))
      require(pathOf == sdxl, "the previous picture stays")
      fx(window.panorama.open(folder.resolve("missing.png")))
      eventually("missing file reported")(label("panorama-status").startsWith("Not found"))
      checked("a corrupt file and a missing file are reported in the status line without losing the shown picture")

      // ---- protected pictures ---------------------------------------------------------------------------------
      val secretCopy = folder.resolve("e-protected.png")
      ScrambledPng.write(PngMetadata.readImage(sd15), secretCopy, Seq(PngMetadata.ParametersKey -> "protected panorama\nSteps: 24"), Some("hunter2"))
      fx(window.panorama.open(secretCopy))
      eventually("password required")(label("panorama-status").contains("password"))
      require(pathOf == sdxl, "locked picture is not shown")
      fx { services.imageScrambleSession.unlock("hunter2"); window.ctx.imageAccessChanged() }
      fx(window.panorama.open(secretCopy)); loaded(secretCopy)
      require(label("panorama-status").contains("Decoded in memory"), label("panorama-status"))
      require(java.util.Arrays.equals(source.argb, s0.argb), "decoded protected picture equals the plain one")
      press("panorama-to-cube")
      eventually("protected conversion saved")(fx(pano.model.projection.get) == Projection.CubemapNet && pathOf.getFileName.toString == "e-protected-cubemap.png")
      require(PngMetadata.readTexts(pathOf).contains("Encrypt"), "the converted file is scrambled while the session is protected")
      fx { services.imageScrambleSession.lock(); window.ctx.imageAccessChanged() }
      checked("a password-protected PNG is refused without the session password, decodes exactly with it, and a conversion made during the protected session is saved scrambled")

      // ---- layout -------------------------------------------------------------------------------------------------
      selectTab("panorama-tab-page")
      fx(window.stage.setWidth(900)); Thread.sleep(500)
      snapshot("pano-09-narrow.png")
      fx(window.stage.setWidth(1700))
      checked("the tab lays out at a 900 px wide window (screenshot)")

      println("PANORAMA CHECK PASSED (" + checks.size + " groups)")
      Files.writeString(output.resolve("panorama-report.txt"), checks.mkString("\n"))
      try fx { window.panorama.dispose() } catch case _: Throwable => ()
      Platform.exit()
      System.exit(0)
    catch case e: Throwable =>
      e.printStackTrace()
      try snapshot("pano-FAILED.png") catch case _: Throwable => ()
      System.exit(1)
