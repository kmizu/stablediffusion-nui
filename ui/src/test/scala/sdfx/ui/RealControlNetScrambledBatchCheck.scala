package sdfx.ui

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}
import javafx.application.Platform
import javafx.scene.control.{Button, CheckBox, ComboBox, Label, ListView, PasswordField, TabPane, TextField}
import javafx.stage.Stage
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome, Settings}
import sdfx.core.png.{PngMetadata, ScrambledPng}
import sdfx.engine.{AppServices, EngineLaunch}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.UiState

/** Real MainWindow and engine; model-generated PNGs are inputs. The OS chooser is not automated. */
object RealControlNetScrambledBatchCheck:
  def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A] { def call(): A = body })
    Platform.runLater(task); task.get(60, TimeUnit.SECONDS)
  def eventually(label: String)(body: => Boolean): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
    while !body && System.nanoTime() < deadline do Thread.sleep(50)
    require(body, s"Timed out: $label")
  def main(args: Array[String]): Unit =
    require(args.length == 4, "python output-directory sd15-image sdxl-image")
    val base = Paths.get(args(1)).toAbsolutePath.normalize(); Files.createDirectories(base)
    val root = Files.createTempDirectory(base, "protected-real-")
    val home = AppHome(root.resolve("home")); val output = Files.createDirectories(root.resolve("out"))
    val originals = args.drop(2).map(p => Paths.get(p).toAbsolutePath.normalize()).toVector
    val pass = "temporary-test-password"
    val inputs = originals.zipWithIndex.map { (p, i) =>
      val image = ImageIO.read(p.toFile)
      require(image != null, s"input is not an image: $p")
      val path = root.resolve(if i == 0 then "sd15.png" else "sdxl.png")
      ScrambledPng.write(image, path, PngMetadata.readTexts(p).toSeq.filterNot((k, _) => k == "Encrypt" || k == "EncryptPwdSha"), Some(pass))
      require(ScrambledPng.read(path, Some(pass)).scrambled)
      path
    }
    val pythonDir = Paths.get("python").toAbsolutePath.normalize()
    val services = new AppServices(home, Settings(outputDir = home.root.resolve("outputs").toString), dryRun = false,
      builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false,
      launchOverride = Some(() => EngineLaunch(Seq(args(0), "-m", "sdfx"), pythonDir,
        Map("PYTHONPATH" -> pythonDir.toString, "HF_HUB_OFFLINE" -> "1", "TRANSFORMERS_OFFLINE" -> "1", "USE_TF" -> "0"))))
    val ready = new CountDownLatch(1); Platform.startup(() => { Platform.setImplicitExit(false); ready.countDown() })
    require(ready.await(30, TimeUnit.SECONDS))
    var window: MainWindow = null
    try
      val hello = services.start(); require(!hello.dryRun)
      window = fx { val w = new MainWindow(new Stage(), services, UiState(), CliOptions()); w.show(); w }
      eventually("preprocessor choices")(fx(Option(window.scene.lookup("#extras-cn-module")).exists(_.asInstanceOf[ComboBox[String]].getItems.contains("canny"))))
      fx {
        val password = window.scene.lookup("#scramble-session-password").asInstanceOf[PasswordField]
        password.setText(pass); window.scene.lookup("#scramble-session-enable").asInstanceOf[Button].fire()
        require(password.getText.isEmpty && services.imageScrambleSession.snapshot.nonEmpty, "session must be enabled through the GUI")
        val tabs = window.scene.getRoot.lookupAll(".tab-pane").asScala.collect { case t: TabPane => t }
          .find(_.getTabs.asScala.exists(_.getText == "Extras")).get
        tabs.getSelectionModel.select(tabs.getTabs.asScala.find(_.getText == "Extras").get)
        window.scene.lookup("#extras-cn-module").asInstanceOf[ComboBox[String]].setValue("canny")
        window.scene.lookup("#extras-cn-pixel-perfect").asInstanceOf[CheckBox].setSelected(true)
        window.scene.lookup("#extras-cn-output-dir").asInstanceOf[TextField].setText(output.toString)
        window.scene.lookup("#extras-cn-selected-list").asInstanceOf[ListView[Path]].getItems.setAll(inputs.asJava)
        window.scene.lookup("#extras-cn-run-selected").asInstanceOf[Button].fire()
      }
      eventually("protected selected batch") { fx {
        window.scene.lookup("#extras-cn-cancel").asInstanceOf[Button].isDisabled &&
          window.scene.lookup("#extras-cn-status").asInstanceOf[Label].getText.contains("2 of 2 written")
      } }
      val produced = Vector(output.resolve("sd15-canny.png"), output.resolve("sdxl-canny.png"))
      produced.zip(originals).foreach { (p, original) =>
        val decoded = ScrambledPng.read(p, Some(pass)); require(decoded.scrambled)
        require(scala.util.Try(ScrambledPng.read(p, None)).isFailure && scala.util.Try(ScrambledPng.read(p, Some("incorrect"))).isFailure)
        val plain = services.generator.preprocessExtras(original, "canny", Map.empty, -1, true)
        try
          val expected = ImageIO.read(plain.toFile); val actual = decoded.image
          require(expected.getWidth == actual.getWidth && expected.getHeight == actual.getHeight)
          val count = expected.getWidth * expected.getHeight
          require(java.util.Arrays.equals(expected.getRGB(0, 0, expected.getWidth, expected.getHeight, null, 0, expected.getWidth),
            actual.getRGB(0, 0, actual.getWidth, actual.getHeight, null, 0, actual.getWidth)), s"protected pixels differ: $p ($count pixels)")
          require(PngMetadata.readTexts(p).get("parameters").exists(_.contains("ControlNet Preprocessor")))
        finally Files.deleteIfExists(plain)
      }
      val before = produced.map(p => Files.readAllBytes(p).toVector)
      fx {
        window.scene.lookup("#scramble-session-lock").asInstanceOf[Button].fire()
        window.scene.lookup("#scramble-session-password").asInstanceOf[PasswordField].setText("incorrect")
        window.scene.lookup("#scramble-session-enable").asInstanceOf[Button].fire()
        window.scene.lookup("#extras-cn-run-selected").asInstanceOf[Button].fire()
      }
      eventually("wrong password rejected") { fx {
        val status = window.scene.lookup("#extras-cn-status").asInstanceOf[Label].getText
        window.scene.lookup("#extras-cn-cancel").asInstanceOf[Button].isDisabled && status.contains("2 failed")
      } }
      require(produced.zip(before).forall((p, bytes) => Files.readAllBytes(p).toVector == bytes), "failed run must preserve existing outputs")
      require(scala.util.Using.resource(Files.list(output))(_.count()) == 2, "failed run must not publish new outputs or leave staging")
      println(s"REAL_CN_SCRAMBLED_BATCH_OK root=$root")
    finally
      if window != null then fx { window.dispose(); window.stage.close() }
      services.shutdown(); Platform.exit()
