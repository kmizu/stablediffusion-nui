package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.awt.image.BufferedImage

/** Stereo views from a depth map (Depth Maps / depthmap-script "stereo image" equivalent, re-implemented). */
object DepthStereo:
  /** Forward-warps `img` horizontally by `shiftPx * (depth - 0.5)` per pixel (depth 1 = near), painting far pixels first so
    * near ones win, then fills disocclusion holes from the nearest filled pixel on the left (or right at the row start). */
  def warp(img: BufferedImage, depth: BufferedImage, shiftPx: Double): BufferedImage =
    val w = img.getWidth; val h = img.getHeight
    require(depth.getWidth == w && depth.getHeight == h, "depth map must match the image size")
    val out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val rgb = new Array[Int](w); val d = new Array[Double](w)
    val dst = new Array[Int](w); val filled = new Array[Boolean](w)
    for y <- 0 until h do
      java.util.Arrays.fill(filled, false)
      for x <- 0 until w do { rgb(x) = img.getRGB(x, y) & 0xFFFFFF; d(x) = (depth.getRGB(x, y) & 0xFF) / 255.0 }
      val order = (0 until w).sortBy(d(_))
      for x <- order do
        val tx = math.round(x + (d(x) - 0.5) * shiftPx).toInt
        if tx >= 0 && tx < w then { dst(tx) = rgb(x); filled(tx) = true }
      var last = -1
      for x <- 0 until w do if filled(x) then last = x else if last >= 0 then { dst(x) = dst(last); filled(x) = true }
      var next = -1
      for x <- (0 until w).reverse do if filled(x) then next = x else if next >= 0 then { dst(x) = dst(next); filled(x) = true }
      for x <- 0 until w do out.setRGB(x, y, if filled(x) then dst(x) else rgb(x))
    out

  def sideBySide(l: BufferedImage, r: BufferedImage): BufferedImage =
    val o = new BufferedImage(l.getWidth * 2, l.getHeight, BufferedImage.TYPE_INT_RGB)
    val g = o.createGraphics(); g.drawImage(l, 0, 0, null); g.drawImage(r, l.getWidth, 0, null); g.dispose(); o

  def overUnder(l: BufferedImage, r: BufferedImage): BufferedImage =
    val o = new BufferedImage(l.getWidth, l.getHeight * 2, BufferedImage.TYPE_INT_RGB)
    val g = o.createGraphics(); g.drawImage(l, 0, 0, null); g.drawImage(r, 0, l.getHeight, null); g.dispose(); o

  /** Red (left) / cyan (right) anaglyph. */
  def anaglyph(l: BufferedImage, r: BufferedImage): BufferedImage =
    val o = new BufferedImage(l.getWidth, l.getHeight, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until l.getHeight; x <- 0 until l.getWidth do
      o.setRGB(x, y, (l.getRGB(x, y) & 0xFF0000) | (r.getRGB(x, y) & 0x00FFFF))
    o

class DepthStereoPlugin extends ExtrasPlugin:
  def id = "depth_maps"
  def name = "Depth stereo image"
  override def description = "Estimates depth (Depth Anything V2) and renders a stereo pair from it: side by side, over/under or " +
    "red-cyan anaglyph. Disoccluded gaps are filled from the neighbouring pixels. Needs the depth-anything-v2-small weights."
  private val modes = Seq("Side by side", "Over / under", "Red-cyan anaglyph")
  override def controls: Seq[Control] = Seq(
    Choice("mode", "Output", modes, "Side by side"),
    Slider("divergence", "Divergence (% of width)", 0, 10, 0.1, 2.0),
    Check("swap", "Swap eyes (cross-eyed viewing)"),
    Check("depth_too", "Also return the depth map")
  )
  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext) =
    val mode = v.str("mode", "Side by side")
    require(modes.contains(mode), "Invalid stereo output")
    val r = ImageTools.run(ctx, "depth_mask", img, "near" -> 0.0, "far" -> 1.0, "invert" -> false, "blur" -> 0)
    try
      val depthPath = r.outputs.getOrElse("depth", throw new IllegalStateException("depth estimation returned no depth map"))
      val depth = Support.readImage(ctx, depthPath)
      val shift = v.double("divergence", 2.0) / 100.0 * img.getWidth
      val a = DepthStereo.warp(img, depth, shift)
      val b = DepthStereo.warp(img, depth, -shift)
      val (l, rt) = if v.bool("swap") then (b, a) else (a, b)
      val out = mode match
        case "Side by side" => DepthStereo.sideBySide(l, rt)
        case "Over / under" => DepthStereo.overUnder(l, rt)
        case _ => DepthStereo.anaglyph(l, rt)
      val info = Seq("Depth stereo" -> s"$mode, divergence ${Values.fmt(v.double("divergence", 2.0))}%")
      (out, info) +: (if v.bool("depth_too") then Seq((depth, info :+ ("Output" -> "depth"))) else Nil)
    finally Support.deleteQuietly(r.outputs.values.toSeq*)
