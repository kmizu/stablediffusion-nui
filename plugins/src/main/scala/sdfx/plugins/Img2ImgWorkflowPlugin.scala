package sdfx.plugins

import java.nio.file.{Files, Path, Paths}
import java.awt.image.BufferedImage
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

/** The documented phase settings of Inzaniak's Workflow, independently implemented from its README and guide. */
class Img2ImgWorkflowPlugin extends Plugin:
  def id = "img2img_workflow"
  def name = "Img2Img Workflow"
  def kind = PluginKind.Script
  override def tabs: Set[Tab] = Set(Tab.Img2Img)
  override def description = "Choose a documented workflow phase to set image size, count and denoising. Each phase can be adjusted. Optionally create an inpaint mask from a selected colour. Effects and overlays are not yet implemented."
  override def controls: Seq[Control] = Seq(
    Section("quickmask_section", "Quick Mask", expanded = false),
    Choice("quick_mask", "Colour mask", Seq("Default", "White", "Black", "Custom"), "Default"),
    Text("mask_color", "Custom colour (#RRGGBB)", "#ffffff"),
    IntField("mask_threshold", "Colour threshold (1 = exact)", 1, 256, 1),
    Check("mask_invert", "Invert colour selection"),
    Choice("phase", "Phase", Seq("None", "Phase 1", "Phase 2", "Phase 3"), "None"),
    Choice("orientation", "Orientation", Seq("Guess", "Vertical", "Horizontal", "Square"), "Guess"),
    Choice("ratio", "Ratio", Seq("Base", "2:1"), "Base"),
    Check("override_denoise", "Override phase denoising"),
    Slider("denoise", "Denoising override", 0, 1, 0.01, 0.5),
    Section("phase1", "Phase 1", expanded = false),
    IntField("phase1_width", "Width", 64, 4096, 512), IntField("phase1_height", "Height", 64, 4096, 768),
    IntField("phase1_count", "Images", 1, 100, 4), Slider("phase1_denoise", "Denoising", 0, 1, 0.01, 0.5),
    Section("phase2", "Phase 2", expanded = false),
    IntField("phase2_width", "Width", 64, 4096, 768), IntField("phase2_height", "Height", 64, 4096, 1152),
    IntField("phase2_count", "Images", 1, 100, 4), Slider("phase2_denoise", "Denoising", 0, 1, 0.01, 0.5),
    Section("phase3", "Phase 3", expanded = false),
    IntField("phase3_width", "Width", 64, 4096, 1280), IntField("phase3_height", "Height", 64, 4096, 1920),
    IntField("phase3_count", "Images", 1, 100, 1), Slider("phase3_denoise", "Denoising", 0, 1, 0.01, 0.2))

  override def enabledBy: Map[String, String] = Map("denoise" -> "override_denoise")

  private def integer(v: Values, key: String, default: Int, low: Int, high: Int): Int =
    val value = v.str(key, default.toString).trim.toIntOption.getOrElse(throw new IllegalArgumentException(s"Workflow: $key must be an integer"))
    require(value >= low && value <= high, s"Workflow: $key must be in $low..$high")
    value
  private def number(v: Values, key: String, default: Double): Double =
    val value = v.str(key, default.toString).trim.toDoubleOption.filter(_.isFinite)
      .getOrElse(throw new IllegalArgumentException(s"Workflow: $key must be a number"))
    require(value >= 0 && value <= 1, s"Workflow: $key must be in 0..1")
    value
  private def flag(v: Values, key: String): Boolean = v.str(key, "false").trim.toLowerCase(java.util.Locale.ROOT) match
    case "true" | "1" => true
    case "false" | "0" | "" => false
    case _ => throw new IllegalArgumentException(s"Workflow: $key must be true or false")

  override def run(p: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    Support.requireImg2Img(p, name)
    val next = phaseParams(p, v, ctx)
    val mode = v.str("quick_mask", "Default")
    if mode == "Default" then
      val invert = flag(v, "mask_invert")
      val withMask = if invert && next.img2img.exists(_.mask.nonEmpty) then
        next.copy(img2img = next.img2img.map(i => i.copy(inpaintingMaskInvert = !i.inpaintingMaskInvert)),
          extra = next.extra + ("Img2Img Workflow mask" -> ujson.Obj("mode" -> mode, "invert" -> true).render()))
      else next
      return JobOutput(ctx.generate(withMask))
    val color = mode match
      case "White" => 0xffffff
      case "Black" => 0x000000
      case "Custom" =>
        val value = v.str("mask_color", "#ffffff").trim
        require(value.matches("#[0-9a-fA-F]{6}"), "Workflow: custom colour must be #RRGGBB")
        Integer.parseInt(value.substring(1), 16)
      case _ => throw new IllegalArgumentException(s"Workflow: unknown colour mask '$mode'")
    val threshold = integer(v, "mask_threshold", 1, 1, 256)
    val invert = flag(v, "mask_invert")
    val source = ctx.readImage(Paths.get(p.img2img.get.initImage))
    val mask = Img2ImgWorkflowPlugin.colorMask(source, color, threshold, invert)
    if ctx.isInterrupted then return JobOutput(Nil)
    val temp = ctx.tempFile(".png")
    try
      Option(temp.getParent).foreach(Files.createDirectories(_))
      ctx.writeTempImage(mask, temp)
      val settings = ujson.Obj("mode" -> mode, "color" -> f"#$color%06x", "threshold" -> threshold, "invert" -> invert).render()
      val prepared = next.copy(img2img = next.img2img.map(_.copy(mask = Some(temp.toString), inpaintingMaskInvert = false)),
        extra = next.extra + ("Img2Img Workflow mask" -> settings))
      JobOutput(ctx.generate(prepared))
    finally Support.deleteQuietly(temp)

  private def phaseParams(p: GenerationParams, v: Values, ctx: PluginContext): GenerationParams =
    val phase = v.str("phase", "None")
    if phase == "None" then return p
    val (prefix, defaultWidth, defaultHeight, defaultCount, defaultDenoise) = phase match
      case "Phase 1" => ("phase1", 512, 768, 4, 0.5)
      case "Phase 2" => ("phase2", 768, 1152, 4, 0.5)
      case "Phase 3" => ("phase3", 1280, 1920, 1, 0.2)
      case _ => throw new IllegalArgumentException(s"Workflow: unknown phase '$phase'")
    val width0 = integer(v, s"${prefix}_width", defaultWidth, 64, 4096)
    val height0 = integer(v, s"${prefix}_height", defaultHeight, 64, 4096)
    require(width0 % 8 == 0 && height0 % 8 == 0, "Workflow: dimensions must be multiples of 8")
    val count = integer(v, s"${prefix}_count", defaultCount, 1, 100)
    val denoising = if flag(v, "override_denoise") then number(v, "denoise", 0.5) else number(v, s"${prefix}_denoise", defaultDenoise)
    val ratio = v.str("ratio", "Base")
    val width = ratio match
      case "Base" => width0
      case "2:1" => height0 / 2
      case _ => throw new IllegalArgumentException(s"Workflow: unknown ratio '$ratio'")
    require(width >= 64 && width % 8 == 0, "Workflow: selected ratio requires a valid width divisible by 8")
    val orientation = v.str("orientation", "Guess") match
      case "Guess" =>
        val image = ctx.readImage(Paths.get(p.img2img.get.initImage))
        if image.getWidth > image.getHeight then "Horizontal" else if image.getWidth < image.getHeight then "Vertical" else "Square"
      case s @ ("Vertical" | "Horizontal" | "Square") => s
      case s => throw new IllegalArgumentException(s"Workflow: unknown orientation '$s'")
    val (w, h) = orientation match
      case "Horizontal" => (height0, width)
      case "Square" => val side = math.max(width, height0); (side, side)
      case _ => (width, height0)
    val metadata = ujson.Obj("phase" -> phase, "orientation" -> orientation, "ratio" -> ratio,
      "width" -> w, "height" -> h, "count" -> count, "denoising" -> denoising).render()
    val next = p.copy(width = w, height = h, batchSize = 1, nIter = count,
      img2img = p.img2img.map(_.copy(denoisingStrength = denoising)), extra = p.extra + ("Img2Img Workflow" -> metadata))
    next


object Img2ImgWorkflowPlugin:
  /** Threshold 1 means exact RGB; larger values allow up to threshold - 1 per channel. */
  def colorMask(src: BufferedImage, color: Int, threshold: Int, invert: Boolean): BufferedImage =
    require(threshold >= 1 && threshold <= 256, "Workflow: threshold must be in 1..256")
    val out = new BufferedImage(src.getWidth, src.getHeight, BufferedImage.TYPE_INT_RGB)
    val r = (color >> 16) & 255; val g = (color >> 8) & 255; val b = color & 255
    for y <- 0 until src.getHeight; x <- 0 until src.getWidth do
      val pixel = src.getRGB(x, y)
      val delta = math.max(math.abs(((pixel >> 16) & 255) - r),
        math.max(math.abs(((pixel >> 8) & 255) - g), math.abs((pixel & 255) - b)))
      val selected = ((pixel >>> 24) != 0) && ((delta < threshold) != invert)
      out.setRGB(x, y, if selected then 0xffffff else 0x000000)
    out
