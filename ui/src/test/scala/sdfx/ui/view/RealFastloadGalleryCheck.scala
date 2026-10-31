package sdfx.ui.view

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.{Button, ComboBox, Label, ListView, TextField}
import javafx.scene.image.ImageView
import javafx.scene.layout.TilePane
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.core.png.{PngMetadata, ScrambledPng}
import sdfx.core.model.ControlNetUnit
import sdfx.engine.AppServices
import sdfx.ui.vm.{CatalogVM, Mode}
import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import scala.jdk.CollectionConverters.*

/** Synthetic native gallery smoke with protected input and filter intersections. */
object RealFastloadGalleryCheck:
  def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(30, TimeUnit.SECONDS)
  def main(args: Array[String]): Unit =
    require(args.length == 3, "expected root, Python executable and synthetic CNI")
    val root = Path.of(args(0)).toAbsolutePath; Files.createDirectories(root)
    val images = root.resolve("images"); Files.createDirectories(images)
    val image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    image.setRGB(1, 1, 0x123456)
    val protectedPath = images.resolve("canny.png")
    val canny = "fixture prompt\nSteps: 20, Sampler: Euler, CFG scale: 7, Seed: 1, Size: 512x512, ControlNet 0: \"Module: canny, Model: one\""
    ScrambledPng.write(image, protectedPath, Seq("parameters" -> canny), Some("test-secret"))
    val sidecar = images.resolve("canny.cni")
    Files.copy(Path.of(args(2)), sidecar)
    PngMetadata.write(image, images.resolve("depth.png"), Seq("parameters" -> "ControlNet 0: \"Module: depth, Model: two\""))
    for i <- 0 until 35 do
      PngMetadata.write(image, images.resolve(f"filler-$i%02d.png"), Seq("parameters" -> "ControlNet 0: \"Module: depth, Model: two\""))
    val home = AppHome(Files.createTempDirectory(root, "home-")); Files.createDirectories(home.pythonDir)
    val service = new AppServices(home, Settings(pythonExe = Some(Path.of(args(1)).toAbsolutePath.toString)),
      dryRun = true, builtins = Nil, persistSettings = false, installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS)); service.start()
    var stage: Stage = null
    try
      var inspected: Option[Path] = None
      var sent: Option[(Mode, Path, Option[String])] = None
      var applied: Option[(Mode, String)] = None
      var control = ControlNetUnit(enabled = false, model = "keep-model", weight = 0.42)
      var context: UiContext = null
      val tab = fx {
        stage = new Stage()
        val ctx = new UiContext(service, new CatalogVM, stage); context = ctx
        ctx.sendToTab = (mode, path, text) => sent = Some((mode, path, text))
        ctx.applyFastloadInfotext = (mode, text) => { applied = Some((mode, text)); Right(()) }
        ctx.fastloadTarget = (_, index) => if index == 0 then Some(control) else None
        ctx.applyFastloadTarget = (_, _, expected, incoming) =>
          if control != expected then Left("stale") else { control = incoming; Right(()) }
        val value = new ControlNetFastloadGalleryTab(ctx, path => inspected = Some(path))
        stage.setScene(new Scene(value.node, 1000, 850)); stage.show()
        service.imageScrambleSession.unlock("wrong-secret")
        value.node.lookup("#fastload-gallery-directory").asInstanceOf[TextField].setText(images.toString)
        value.node.lookup("#fastload-gallery-scan").asInstanceOf[Button].fire()
        value
      }
      def status: String = fx(tab.node.lookup("#fastload-gallery-status").asInstanceOf[Label].getText)
      def waitFor(test: => Boolean): Unit =
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while !test && System.nanoTime() < deadline do Thread.sleep(50)
        require(test, s"Gallery status: $status")
      waitFor(status.startsWith("Page 1 of 2"))
      fx(tab.node.lookup("#fastload-gallery-files").asInstanceOf[ListView[String]].getSelectionModel.selectFirst())
      waitFor(status.startsWith("Cannot preview image"))
      require(fx(tab.node.lookup("#fastload-gallery-send-img").asInstanceOf[Button].isDisabled), "unreadable protected image can be sent")
      fx {
        service.imageScrambleSession.unlock("test-secret"); context.imageAccessChanged()
        tab.node.lookup("#fastload-gallery-scan").asInstanceOf[Button].fire()
      }
      waitFor(status.startsWith("Page 1 of 2"))
      waitFor(fx {
        val grid = tab.node.lookup("#fastload-gallery-grid").asInstanceOf[TilePane]
        grid.getChildren.size() == 36 && grid.getChildren.asScala.forall(_.asInstanceOf[Button].getGraphic != null)
      })
      fx {
        val shot = tab.node.snapshot(null, null)
        javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(shot, null), "png", root.resolve("fastload-gallery-grid36.png").toFile)
      }
      fx(tab.node.lookup("#fastload-gallery-next").asInstanceOf[Button].fire())
      waitFor(status.startsWith("Page 2 of 2"))
      require(fx(tab.node.lookup("#fastload-gallery-files").asInstanceOf[ListView[String]].getItems.size()) == 1)
      fx(tab.node.lookup("#fastload-gallery-first").asInstanceOf[Button].fire())
      waitFor(status.startsWith("Page 1 of 2"))
      fx {
        val key = tab.node.lookup("#fastload-gallery-key").asInstanceOf[ComboBox[String]]
        val value = tab.node.lookup("#fastload-gallery-value").asInstanceOf[ComboBox[String]]
        key.setValue("Module"); value.setValue("canny")
        tab.node.lookup("#fastload-gallery-add").asInstanceOf[Button].fire()
      }
      waitFor(fx(tab.node.lookup("#fastload-gallery-files").asInstanceOf[ListView[String]].getItems.size()) == 1 && status.startsWith("Page"))
      waitFor(fx {
        val grid = tab.node.lookup("#fastload-gallery-grid").asInstanceOf[TilePane]
        grid.getChildren.size() == 1 && grid.getChildren.get(0).asInstanceOf[Button].getGraphic.isInstanceOf[ImageView]
      })
      val thumbPixel = fx {
        val grid = tab.node.lookup("#fastload-gallery-grid").asInstanceOf[TilePane]
        val thumb = grid.getChildren.get(0).asInstanceOf[Button].getGraphic.asInstanceOf[ImageView].getImage
        javafx.embed.swing.SwingFXUtils.fromFXImage(thumb, null).getRGB(1, 1)
      }
      require((thumbPixel & 0xffffff) == 0x123456, "protected thumbnail differs")
      fx(tab.node.lookup("#fastload-gallery-files").asInstanceOf[ListView[String]].getSelectionModel.selectFirst())
      waitFor(fx(Option(tab.node.lookup("#fastload-gallery-preview").asInstanceOf[ImageView].getImage).exists(image => !image.isError)))
      val pixel = fx(javafx.embed.swing.SwingFXUtils.fromFXImage(tab.node.lookup("#fastload-gallery-preview").asInstanceOf[ImageView].getImage, null).getRGB(1, 1))
      require((pixel & 0xffffff) == 0x123456, "protected gallery preview differs")
      fx(tab.node.lookup("#fastload-gallery-inspect").asInstanceOf[Button].fire())
      waitFor(fx(inspected.nonEmpty))
      fx {
        tab.node.lookup("#fastload-gallery-params-txt").asInstanceOf[Button].fire()
        tab.node.lookup("#fastload-gallery-send-img").asInstanceOf[Button].fire()
        tab.node.lookup("#fastload-gallery-control-img").asInstanceOf[Button].fire()
      }
      require(inspected.contains(sidecar) && sent.exists((mode, path, text) => mode == Mode.Img2Img && path == protectedPath && text.contains(canny)))
      require(applied.contains(Mode.Txt2Img -> canny), "metadata-only send did not use the selected parameters")
      require(control == ControlNetUnit(enabled = false, model = "keep-model", image = Some(protectedPath.toString), weight = 0.42),
        "ControlNet image send changed other values")
      fx {
        val shot = tab.node.snapshot(null, null)
        javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(shot, null), "png", root.resolve("fastload-gallery-ui.png").toFile)
        service.imageScrambleSession.lock()
        sent = None
        tab.node.lookup("#fastload-gallery-send-img").asInstanceOf[Button].fire()
        require(sent.isEmpty, "stale session sent gallery data")
        require(tab.node.lookup("#fastload-gallery-status").asInstanceOf[Label].getText.startsWith("Image session changed"))
        context.imageAccessChanged()
        require(tab.node.lookup("#fastload-gallery-files").asInstanceOf[ListView[String]].getItems.isEmpty)
        require(tab.node.lookup("#fastload-gallery-preview").asInstanceOf[ImageView].getImage == null)
        require(tab.node.lookup("#fastload-gallery-key").asInstanceOf[ComboBox[String]].getItems.isEmpty)
        require(tab.node.lookup("#fastload-gallery-filters").asInstanceOf[ListView[?]].getItems.isEmpty)
        tab.dispose()
      }
      println("PASS native Fastload gallery: metadata filter, protected preview, send and session cleanup")
    finally
      if stage != null then fx(stage.close())
      service.shutdown(); Platform.exit()
