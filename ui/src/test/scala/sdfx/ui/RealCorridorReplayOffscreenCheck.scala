package sdfx.ui

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.stage.Stage
import sdfx.core.config.{AppHome, Settings}
import sdfx.core.infotext.Infotext
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices, EngineLaunch}
import sdfx.plugins.{CorridorCrawlerPlugin, StandardPlugins}
import sdfx.ui.vm.{Mode, UiState}
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javax.imageio.ImageIO

/** Manual offscreen MainWindow check: no Stage.show, no mouse/keyboard or desktop manipulation. */
object RealCorridorReplayOffscreenCheck:
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task)
    task.get(40, TimeUnit.SECONDS)

  def main(args: Array[String]): Unit =
    require(args.length == 4, "python source-root real-png output-directory")
    val python = Path.of(args(0)).toAbsolutePath
    val source = Path.of(args(1)).toAbsolutePath
    val png = Path.of(args(2)).toAbsolutePath
    val output = Path.of(args(3)).toAbsolutePath
    Files.createDirectories(output)
    val home = AppHome(Files.createTempDirectory(output, "home-"))
    val settings = Settings(outputDir = output.resolve("images").toString, previewEvery = 0)
    val engineDir = source.resolve("python")
    val services = new AppServices(home, settings, dryRun = true, builtins = StandardPlugins.withOwnAxes,
      launchOverride = Some(() => EngineLaunch(Seq(python.toString, "-m", "sdfx", "--dry-run"), engineDir,
        Map("PYTHONPATH" -> engineDir.toString, "USE_TF" -> "0"))), persistSettings = false, installShutdownHook = false)
    val ready = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    var primaryFailure: Throwable = null
    try
      val text = PngMetadata.readParameters(png).getOrElse(sys.error("PNG has no infotext"))
      val fields = Infotext.parse(text).fields
      require(CorridorCrawlerPlugin.replay(fields).nonEmpty)
      fx {
        window = new MainWindow(new Stage(), services, UiState(), CliOptions())
        require(!window.stage.isShowing)
        window.vms(Mode.Inpaint).initImage.set("keep-inpaint.png")
        window.vms(Mode.Img2Img).initImage.set("clear-img2img.png")
        window.select(Mode.Inpaint)
        require(window.activeMode == Mode.Inpaint)
        window.plugins(Mode.Img2Img).form("corridor_crawler").get.prop("prefab_image").get.set("clear-old-prefab.png")
        window.restore(text)
        require(window.activeMode == Mode.Img2Img)
        require(window.vms(Mode.Inpaint).initImage.get == "keep-inpaint.png")
        require(window.vms(Mode.Img2Img).initImage.get == "")
        require(window.plugins(Mode.Img2Img).selectedScript.get == "corridor_crawler")
        val restored = window.plugins(Mode.Img2Img).form("corridor_crawler").get.values
        require(restored.str("steps") == "2" && restored.str("placement") == "Preserve aspect")
        require(restored.str("prefab_image") == "")
        val oldPayload = fields(CorridorCrawlerPlugin.ReplayKey)
        val custom = ujson.read(oldPayload).obj
        custom("prefab") = ujson.Str(CorridorCrawlerPlugin.CustomPrefab)
        val customText = text.replace(Infotext.quote(oldPayload), Infotext.quote(ujson.Obj.from(custom).render()))
        require(customText != text)
        window.plugins(Mode.Img2Img).form("corridor_crawler").get.prop("prefab_image").get.set("never-reuse.png")
        window.restore(customText)
        val customRestored = window.plugins(Mode.Img2Img).form("corridor_crawler").get.values
        require(customRestored.str("prefab") == CorridorCrawlerPlugin.CustomPrefab && customRestored.str("prefab_image") == "")
        window.sendTo(Mode.Inpaint, png, Some(text))
        require(window.vms(Mode.Inpaint).initImage.get == png.toString)
        require(window.plugins(Mode.Inpaint).selectedScript.get != "corridor_crawler")
        window.sendTo(Mode.Img2Img, png, Some(text))
        require(window.vms(Mode.Img2Img).initImage.get == png.toString)
        require(window.plugins(Mode.Img2Img).selectedScript.get == "corridor_crawler")
        require(!window.stage.isShowing)
        val root = window.root
        root.applyCss()
        root.resize(1280, 800)
        root.layout()
        val shot = root.snapshot(null, null)
        ImageIO.write(SwingFXUtils.fromFXImage(shot, null), "png", output.resolve("offscreen.png").toFile)
        val panel = window.panels(Mode.Img2Img).node
        val section = panel.lookup(".script-section")
        require(section != null, "script section not rendered")
        val bounds = panel.getContent.sceneToLocal(section.localToScene(section.getBoundsInLocal))
        val scrollable = panel.getContent.getLayoutBounds.getHeight - panel.getViewportBounds.getHeight
        if scrollable > 0 then panel.setVvalue(math.max(0.0, math.min(1.0, (bounds.getMinY - 20.0) / scrollable)))
        root.layout()
        val scriptShot = root.snapshot(null, null)
        ImageIO.write(SwingFXUtils.fromFXImage(scriptShot, null), "png", output.resolve("offscreen-script.png").toFile)
        val windowSection = panel.lookupAll(".titled-pane").toArray.collectFirst {
          case p: javafx.scene.control.TitledPane if p.getText == "Window (percent of the canvas)" => p
        }.getOrElse(sys.error("window settings not rendered"))
        windowSection.setExpanded(true)
        root.layout()
        val choice = panel.lookupAll(".combo-box").toArray.collectFirst {
          case c: javafx.scene.control.ComboBox[?] if c.getValue == "Preserve aspect" => c
        }.getOrElse(sys.error("placement choice not rendered"))
        val choiceBounds = panel.getContent.sceneToLocal(choice.localToScene(choice.getBoundsInLocal))
        val available = panel.getContent.getLayoutBounds.getHeight - panel.getViewportBounds.getHeight
        if available > 0 then panel.setVvalue(math.max(0.0, math.min(1.0, (choiceBounds.getMinY - 350.0) / available)))
        root.layout()
        val placementShot = root.snapshot(null, null)
        ImageIO.write(SwingFXUtils.fromFXImage(placementShot, null), "png", output.resolve("offscreen-placement.png").toFile)
      }
      println("CORRIDOR_REPLAY_OFFSCREEN_OK")
    catch
      case error: Throwable => primaryFailure = error; throw error
    finally
      NativeAcceptanceSafety.cleanup(primaryFailure)(
        () => { if window != null then fx { window.dispose() } },
        () => { if window != null then fx { window.stage.close() } },
        () => services.shutdown(), () => Platform.exit())
