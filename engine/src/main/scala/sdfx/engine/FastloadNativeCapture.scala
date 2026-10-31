package sdfx.engine

import sdfx.core.model.ControlNetUnit
import sdfx.core.png.{ImageScrambleSecret, ScrambledPng}
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import javax.imageio.stream.FileImageInputStream

/** Build worker wire values in memory; never stage decoded protected inputs. */
object FastloadNativeCapture:
  private def dict(fields: Seq[(String, ujson.Value)]): ujson.Value =
    ujson.Obj("type" -> "dict", "items" -> ujson.Arr.from(fields.map((k, v) => ujson.Arr(k, v))))
  private def string(s: String): ujson.Value = ujson.Obj("type" -> "string", "value" -> s)
  private def number(n: Double): ujson.Value =
    require(n.isFinite, "Invalid ControlNet number")
    ujson.Obj("type" -> "float", "value" -> n)
  private def image(path: String, secret: Option[ImageScrambleSecret]): ujson.Value =
    val input = new FileImageInputStream(Paths.get(path).toFile)
    try
      val readers = ImageIO.getImageReaders(input)
      require(readers.hasNext, "ControlNet image is not readable")
      val reader = readers.next()
      try
        reader.setInput(input, true, true)
        require(reader.getWidth(0).toLong * reader.getHeight(0).toLong <= 32000000, "ControlNet image is too large")
      finally reader.dispose()
    finally input.close()
    val decoded = ScrambledPng.read(Paths.get(path), secret.map(_.use(identity))).image
    require(decoded.getWidth.toLong * decoded.getHeight.toLong <= 32000000, "ControlNet image is too large")
    val bytes = new ByteArrayOutputStream
    val stream = new MemoryCacheImageOutputStream(bytes)
    val writer = ImageIO.getImageWritersByFormatName("png").next()
    try
      writer.setOutput(stream); writer.write(decoded); stream.flush()
    finally
      writer.dispose(); stream.close()
    require(bytes.size() <= 128 * 1024 * 1024, "ControlNet image is too large")
    ujson.Obj("type" -> "bytes", "base64" -> Base64.getEncoder.encodeToString(bytes.toByteArray))

  def encode(units: Seq[ControlNetUnit], secret: Option[ImageScrambleSecret]): ujson.Value =
    val items = units.map { unit =>
      val args = dict(unit.preprocessorArgs.toSeq.sortBy(_._1).map((k, v) => k -> number(v)))
      val params = dict(Seq(
        "enabled" -> ujson.Obj("type" -> "bool", "value" -> unit.enabled),
        "model" -> string(unit.model), "preprocessor" -> string(unit.preprocessor),
        "weight" -> number(unit.weight), "guidance_start" -> number(unit.guidanceStart),
        "guidance_end" -> number(unit.guidanceEnd), "preprocessor_args" -> args,
        "resize_mode" -> ujson.Obj("type" -> "int", "value" -> unit.resizeMode.toString),
        "source_compatible_resize" -> ujson.Obj("type" -> "bool", "value" -> unit.sourceCompatibleResize),
        "control_mode" -> string(unit.controlMode),
        "processor_res" -> ujson.Obj("type" -> "int", "value" -> unit.processorRes.toString),
        "pixel_perfect" -> ujson.Obj("type" -> "bool", "value" -> unit.pixelPerfect)))
      val src = unit.image.filter(_.nonEmpty).map(path => image(path, secret)).getOrElse(ujson.Obj("type" -> "null"))
      dict(Seq("params" -> params, "image" -> src))
    }
    ujson.Obj("type" -> "list", "items" -> ujson.Arr.from(items))
