package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.awt.image.BufferedImage

/** Equirectangular panorama helpers (own maths; no upstream code): horizontal roll, wrap-around seam blend, perspective view. */
object PanoramaTools:
  private def rgb(i: BufferedImage) =
    val o = new BufferedImage(i.getWidth, i.getHeight, BufferedImage.TYPE_INT_RGB)
    o.getGraphics.drawImage(i, 0, 0, null); o

  /** Shifts the image right by `dx` pixels with wrap-around (negative = left). */
  def roll(i: BufferedImage, dx: Int): BufferedImage =
    val s = rgb(i); val w = s.getWidth; val o = new BufferedImage(w, s.getHeight, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until s.getHeight; x <- 0 until w do o.setRGB(Math.floorMod(x + dx, w), y, s.getRGB(x, y))
    o

  /** Cross-fades the `band` columns on each side of the wrap seam so the left and right edges meet without a step. */
  def blendSeam(i: BufferedImage, band: Int): BufferedImage =
    val s = rgb(i); val w = s.getWidth; val b = math.min(band, w / 2)
    val o = rgb(s)
    for y <- 0 until s.getHeight; k <- 0 until b do
      // column k from the left edge and column w-1-k from the right edge are neighbours across the seam
      val t = 0.5 * (1.0 - (k + 0.5) / b)
      val l = s.getRGB(k, y); val r = s.getRGB(w - 1 - k, y)
      def mix(a: Int, c: Int, sh: Int) = (((a >> sh & 255) * (1 - t) + (c >> sh & 255) * t) + 0.5).toInt
      def px(a: Int, c: Int) = (mix(a, c, 16) << 16) | (mix(a, c, 8) << 8) | mix(a, c, 0)
      o.setRGB(k, y, px(l, r)); o.setRGB(w - 1 - k, y, px(r, l))
    o

  /** Perspective view (pinhole camera) of an equirectangular image: yaw/pitch in degrees, horizontal field of view in degrees. */
  def view(i: BufferedImage, yawDeg: Double, pitchDeg: Double, fovDeg: Double, ow: Int, oh: Int): BufferedImage =
    val s = rgb(i); val w = s.getWidth; val h = s.getHeight
    val o = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB)
    val f = (ow / 2.0) / math.tan(math.toRadians(fovDeg) / 2)
    val yaw = math.toRadians(yawDeg); val pit = math.toRadians(pitchDeg)
    for y <- 0 until oh; x <- 0 until ow do
      val dx = x + 0.5 - ow / 2.0; val dy = oh / 2.0 - (y + 0.5); val dz = f
      val y1 = dy * math.cos(pit) + dz * math.sin(pit); val z1 = -dy * math.sin(pit) + dz * math.cos(pit)
      val x2 = dx * math.cos(yaw) + z1 * math.sin(yaw); val z2 = -dx * math.sin(yaw) + z1 * math.cos(yaw)
      val lon = math.atan2(x2, z2); val lat = math.atan2(y1, math.hypot(x2, z2))
      val u = ((lon / (2 * math.Pi) + 0.5) * w).toInt; val v = ((0.5 - lat / math.Pi) * h).toInt
      o.setRGB(x, y, s.getRGB(Math.floorMod(u, w), math.max(0, math.min(h - 1, v))))
    o

class PanoramaToolsPlugin extends ExtrasPlugin:
  def id = "panorama_tools"
  def name = "Panorama tools"
  override def description = "Equirectangular helpers: Roll (wrap-around shift, e.g. 50% to inspect or inpaint the seam), " +
    "Blend seam (cross-fade the left/right edges), View (perspective view at yaw/pitch/field of view)."
  override def controls: Seq[Control] = Seq(
    Choice("operation", "Operation", Seq("Roll", "Blend seam", "View"), "Roll"),
    Slider("roll_percent", "Roll: shift (% of width)", -100, 100, 1, 50),
    IntField("band", "Blend seam: band width (px per side)", 1, 1024, 64),
    Slider("yaw", "View: yaw (degrees)", -180, 180, 1, 0),
    Slider("pitch", "View: pitch (degrees)", -89, 89, 1, 0),
    Slider("fov", "View: field of view (degrees)", 20, 140, 1, 90),
    IntField("view_width", "View: output width (px)", 64, 4096, 1024),
    IntField("view_height", "View: output height (px)", 64, 4096, 768)
  )
  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext) =
    v.str("operation", "Roll") match
      case "Roll" =>
        val dx = math.round(img.getWidth * v.double("roll_percent", 50) / 100.0).toInt
        Seq((PanoramaTools.roll(img, dx), Seq("Panorama" -> s"roll $dx px")))
      case "Blend seam" =>
        Seq((PanoramaTools.blendSeam(img, v.int("band", 64)), Seq("Panorama" -> s"blend seam ${v.int("band", 64)} px")))
      case "View" =>
        val (yw, pt, fv) = (v.double("yaw", 0), v.double("pitch", 0), v.double("fov", 90))
        Seq((PanoramaTools.view(img, yw, pt, fv, v.int("view_width", 1024), v.int("view_height", 768)),
          Seq("Panorama" -> s"view yaw $yw pitch $pt fov $fv")))
      case o => throw new IllegalArgumentException(s"unknown operation: $o")
