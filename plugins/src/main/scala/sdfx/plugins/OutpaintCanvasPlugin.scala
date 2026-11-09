package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.awt.image.BufferedImage

/** Canvas extension for outpainting (own design; no upstream code): grows the canvas, fills the new area and returns the inpaint mask. */
object OutpaintCanvas:
  /** Returns (extended image, mask) where the mask is white on the new area and black on the original pixels. */
  def extend(src: BufferedImage, l: Int, t: Int, r: Int, b: Int, fill: String): (BufferedImage, BufferedImage) =
    require(l >= 0 && t >= 0 && r >= 0 && b >= 0 && l + t + r + b > 0, "Extend by at least one pixel")
    val w = src.getWidth; val h = src.getHeight; val nw = w + l + r; val nh = h + t + b
    require(nw <= 8192 && nh <= 8192, "Result larger than 8192 px")
    val o = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
    val m = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
    def reflect(c: Int, n: Int) = { val p = Math.floorMod(c, 2 * n); if p < n then p else 2 * n - 1 - p }
    for y <- 0 until nh; x <- 0 until nw do
      val sx = x - l; val sy = y - t
      val inside = sx >= 0 && sx < w && sy >= 0 && sy < h
      val c = if inside then src.getRGB(sx, sy) else fill match
        case "Mirror" => src.getRGB(reflect(sx, w), reflect(sy, h))
        case "Edge"   => src.getRGB(math.max(0, math.min(w - 1, sx)), math.max(0, math.min(h - 1, sy)))
        case "Gray"   => 0x808080
        case other    => throw new IllegalArgumentException(s"unknown fill: $other")
      o.setRGB(x, y, c & 0xFFFFFF)
      m.setRGB(x, y, if inside then 0 else 0xFFFFFF)
    (o, m)

class OutpaintCanvasPlugin extends ExtrasPlugin:
  def id = "outpaint_canvas"
  def name = "Outpaint canvas"
  override def description = "Extends the canvas on any side, fills the new area (mirror, edge or gray) and returns the extended image " +
    "plus the inpaint mask (white = new area) to send to img2img inpainting."
  override def controls: Seq[Control] = Seq(
    IntField("left", "Extend left (px)", 0, 4096, 0), IntField("top", "Extend top (px)", 0, 4096, 0),
    IntField("right", "Extend right (px)", 0, 4096, 128), IntField("bottom", "Extend bottom (px)", 0, 4096, 0),
    Choice("fill", "Fill", Seq("Mirror", "Edge", "Gray"), "Mirror")
  )
  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext) =
    val (o, m) = OutpaintCanvas.extend(img, v.int("left", 0), v.int("top", 0), v.int("right", 128), v.int("bottom", 0), v.str("fill", "Mirror"))
    val note = s"${img.getWidth}x${img.getHeight} -> ${o.getWidth}x${o.getHeight}"
    Seq((o, Seq("Outpaint" -> note)), (m, Seq("Outpaint" -> "mask (white = new area)")))
