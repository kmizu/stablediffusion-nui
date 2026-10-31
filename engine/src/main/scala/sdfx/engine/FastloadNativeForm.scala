package sdfx.engine

import sdfx.core.model.ControlNetUnit
import java.util.Base64

/** Validate a worker response before any native form or input file is changed. */
object FastloadNativeForm:
  final case class Decoded(unit: ControlNetUnit, image: Option[Array[Byte]])

  def decode(value: ujson.Value): Decoded =
    val unsupported = value("unapplied").arr.map(_.str).toVector
    require(unsupported.isEmpty, s"Cannot apply saved options: ${unsupported.mkString(", ")}")
    require(value("mask") == ujson.Null, "Cannot apply a ControlNet mask in the current form")
    val p = value("params")
    require(!p("preprocessor").str.startsWith("inpaint"), "Cannot yet apply a ControlNet inpaint preprocessor")
    val args = p("preprocessor_args").obj.iterator.map { (key, value) =>
      val n = value.num
      require(n.isFinite, "Invalid preprocessor argument")
      key -> n
    }.toMap
    def number(name: String): Double =
      val n = p(name).num
      require(n.isFinite, s"Invalid $name")
      n
    val resizeMode = p.obj.get("resize_mode").map(_.num).getOrElse(1.0)
    require(resizeMode.isWhole && resizeMode >= 0 && resizeMode <= 2, "Invalid ControlNet resize mode")
    val sourceResize = p.obj.get("source_compatible_resize").map(_.bool).getOrElse(true)
    val controlMode = p.obj.get("control_mode").map(_.str).getOrElse("Balanced")
    require(ControlNetUnit.ControlModes.contains(controlMode), "Invalid ControlNet control mode")
    val processorRes = p.obj.get("processor_res").map(_.num).getOrElse(-1.0)
    require(processorRes == -1 || (processorRes.isWhole && processorRes >= 64 && processorRes <= 2048),
      "Invalid ControlNet processor resolution")
    val unit = ControlNetUnit(enabled = p("enabled").bool, model = p("model").str,
      preprocessor = p("preprocessor").str, weight = number("weight"),
      guidanceStart = number("guidance_start"), guidanceEnd = number("guidance_end"),
      preprocessorArgs = args, resizeMode = resizeMode.toInt, sourceCompatibleResize = sourceResize,
      controlMode = controlMode, processorRes = processorRes.toInt,
      pixelPerfect = p.obj.get("pixel_perfect").exists(_.bool))
    val image = value("image") match
      case ujson.Null => None
      case ujson.Str(s) =>
        require(s.length <= 180000000, "Fastload image is too large")
        Some(Base64.getDecoder.decode(s))
      case _ => throw new IllegalArgumentException("Invalid Fastload image")
    Decoded(unit, image)
