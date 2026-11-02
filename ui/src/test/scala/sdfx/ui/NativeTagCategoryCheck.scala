package sdfx.ui

import java.nio.file.{Files, Paths}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.control.{ListCell, TextArea}
import javafx.scene.layout.StackPane
import javafx.scene.shape.Circle
import javafx.stage.{PopupWindow, Stage, Window}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.view.{CompletionSource, TagAutocomplete}
import sdfx.ui.vm.{Chant, CompletionData, CompletionEngine, CompletionResult, Suggestion, TagCompletion}

/** Real JavaFX popup with theme, category markers and selection, no engine. */
object NativeTagCategoryCheck:
  def main(args: Array[String]): Unit =
    require(args.length == 2 && Set("dark", "light")(args(0)), "dark|light output-directory")
    val output = Paths.get(args(1)).toAbsolutePath.normalize(); Files.createDirectories(output)
    val done = new CountDownLatch(1); val failure = new AtomicReference[Throwable]()
    Platform.startup(() => {
      val area = new TextArea()
      val tags = TagCompletion.parse(Iterator("name,category,count,aliases", "blue_hair,4,20,", "blue_sky,0,10,", "blue_unknown,99,1,"))
      val data = CompletionData(tags = tags, chants = Vector(Chant("sunlight", "sun", "sunlit sky", 3)))
      val source = new CompletionSource:
        def ensureLoaded(): Unit = ()
        def previews: Boolean = false
        def complete(text: String, caret: Int, start: Int, end: Int): Option[CompletionResult] =
          CompletionEngine.complete(data, TagCompleteSettings(), text, caret, start, end)
      val completion = new TagAutocomplete(area, source)
      val stage = new Stage(); val scene = new Scene(new StackPane(area), 600, 300)
      scene.getStylesheets.add(getClass.getResource(s"/sdfx/ui/${args(0)}.css").toExternalForm)
      scene.getStylesheets.add(getClass.getResource("/sdfx/ui/app.css").toExternalForm)
      stage.setScene(scene); stage.show(); area.requestFocus()
      Platform.runLater(() => {
        try
          area.setText("blue"); area.positionCaret(4)
          require(completion.isShowing, "completion popup must be visible")
          val popup = Window.getWindows.asScala.collectFirst { case p: PopupWindow if p.isShowing => p }.get
          popup.getScene.getRoot.applyCss(); popup.getScene.getRoot.layout()
          val cells = popup.getScene.getRoot.lookupAll(".list-cell").asScala.collect {
            case c: ListCell[?] if c.getItem.isInstanceOf[Suggestion] => c
          }.map(c => c.getItem.asInstanceOf[Suggestion].label -> c).toMap
          require(cells("blue_hair").getGraphic.isInstanceOf[Circle], "known category must show a marker")
          require(cells("blue_sky").getGraphic.isInstanceOf[Circle], "category zero must show a marker")
          require(cells("blue_unknown").getGraphic == null, "unknown category must not invent a colour")
          require(cells("blue_hair").getAccessibleText.contains("category 4"), "category must be available without colour")
          require(ImageIO.write(SwingFXUtils.fromFXImage(popup.getScene.snapshot(null), null), "png", output.resolve(s"category-${args(0)}.png").toFile))
          area.setText("<c:sun"); area.positionCaret(area.getLength)
          popup.getScene.getRoot.applyCss(); popup.getScene.getRoot.layout()
          val chant = popup.getScene.getRoot.lookupAll(".list-cell").asScala.collectFirst {
            case c: ListCell[?] if Option(c.getItem).exists(_.isInstanceOf[Suggestion]) &&
                c.getItem.asInstanceOf[Suggestion].label == "sunlight" => c
          }.get
          require(chant.getGraphic.isInstanceOf[Circle] && chant.getAccessibleText.contains("category 3"), "chant category must render")
          require(ImageIO.write(SwingFXUtils.fromFXImage(popup.getScene.snapshot(null), null), "png", output.resolve(s"chant-${args(0)}.png").toFile))
          println(s"CATEGORY_POPUP_OK theme=${args(0)}")
        catch case e: Throwable => failure.set(e)
        finally { stage.close(); done.countDown() }
      })
    })
    require(done.await(30, TimeUnit.SECONDS), "category UI check timed out")
    Platform.exit()
    if failure.get() != null then throw failure.get()
