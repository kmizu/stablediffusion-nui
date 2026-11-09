package sdfx.ui.view

import javafx.geometry.Pos
import javafx.scene.control.{Button, Label, Slider}
import javafx.scene.layout.{HBox, Priority, VBox}
import javafx.scene.media.{Media, MediaPlayer, MediaView}
import javafx.util.Duration
import java.nio.file.Path
import scala.util.control.NonFatal

/** In-app video preview for the Image Browser: first frame, play/pause and a seek bar via JavaFX Media (H.264/AAC mp4/m4v and the
  * other formats the platform decoder supports). Formats it cannot decode show a message; the caller keeps its "Open video" button. */
final class VideoPreviewPane(idPrefix: String):
  private val view = new MediaView(); view.setPreserveRatio(true); view.setFitWidth(400); view.setFitHeight(320)
  view.setId(idPrefix + "-video")
  private val play = new Button("Play"); play.setId(idPrefix + "-video-play")
  private val seek = new Slider(0, 1, 0); seek.setId(idPrefix + "-video-seek")
  private val note = new Label(""); note.setWrapText(true); note.setId(idPrefix + "-video-note")
  private var player: Option[MediaPlayer] = None
  private var seeking = false

  val node: VBox =
    val bar = new HBox(8, play, seek); bar.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(seek, Priority.ALWAYS)
    val b = new VBox(4, view, bar, note); b.setVisible(false); b.setManaged(false); b.setId(idPrefix + "-video-pane"); b

  def status: String = note.getText

  play.setOnAction { _ =>
    player.foreach { p =>
      if p.getStatus == MediaPlayer.Status.PLAYING then { p.pause(); play.setText("Play") }
      else { if p.getCurrentTime.greaterThanOrEqualTo(p.getTotalDuration) then p.seek(Duration.ZERO); p.play(); play.setText("Pause") }
    }
  }
  seek.valueProperty.addListener((_, _, v) => player.foreach { p =>
    if seeking && p.getTotalDuration != null && !p.getTotalDuration.isUnknown then p.seek(p.getTotalDuration.multiply(v.doubleValue)) })
  seek.setOnMousePressed(_ => seeking = true); seek.setOnMouseReleased(_ => seeking = false)

  def hide(): Unit = { dispose(); node.setVisible(false); node.setManaged(false) }

  private def dispose(): Unit =
    player.foreach { p => try { p.stop(); p.dispose() } catch case NonFatal(_) => () }
    player = None; current = None; view.setMediaPlayer(null); play.setText("Play"); seek.setValue(0); note.setText("")

  private var current: Option[Path] = None

  def load(path: Path): Unit =
    if current.contains(path) && player.isDefined then { node.setVisible(true); node.setManaged(true); return }
    dispose(); current = Some(path); node.setVisible(true); node.setManaged(true)
    try
      val p = new MediaPlayer(new Media(path.toUri.toString))
      player = Some(p); view.setMediaPlayer(p); p.setVolume(0.5)
      p.setOnReady { () =>
        if player.contains(p) then play.setDisable(false)
        note.setText(f"${p.getTotalDuration.toSeconds}%.1f s, ${p.getMedia.getWidth}x${p.getMedia.getHeight}")
        p.seek(Duration.millis(1)); p.pause()
      }
      p.currentTimeProperty.addListener((_, _, t) => if !seeking && p.getTotalDuration != null && !p.getTotalDuration.isUnknown && p.getTotalDuration.toMillis > 0 then
        seek.setValue(t.toMillis / p.getTotalDuration.toMillis))
      p.setOnEndOfMedia { () => play.setText("Play") }
      p.setOnError { () => if player.contains(p) then note.setText("This video cannot be played in the app (" + Option(p.getError).map(_.getMessage).getOrElse("unsupported format") + "). Use Open video."); if player.contains(p) then play.setDisable(true) }
      play.setDisable(false)
    catch case NonFatal(e) => note.setText("This video cannot be played in the app (" + e.getMessage + "). Use Open video."); play.setDisable(true)
