package sdfx.plugins

import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

/** Outpainting without ControlNet (own design; no upstream code): the canvas is grown on the chosen sides, the new strip is pre-filled
  * (mirror / edge) and inpainted with the img2img prompt in `passes` steps of `px / passes` pixels, each pass seeing the previous result. */
class MosaicOutpaintPlugin extends Plugin:
  def id = "mosaic_outpaint"
  def name = "Mosaic outpaint"
  def kind = PluginKind.Script
  override def tabs: Set[Tab] = Set(Tab.Img2Img)
  override def description = "Extends the init image by N px on the chosen sides in several passes (new strip pre-filled, then inpainted with the " +
    "prompt and the img2img strength). Works with any checkpoint; an inpainting checkpoint gives the best seams. Returns the final extended image."
  override def controls: Seq[Control] = Seq(
    IntField("left", "Extend left (px)", 0, 2048, 0), IntField("top", "Extend top (px)", 0, 2048, 0),
    IntField("right", "Extend right (px)", 0, 2048, 256), IntField("bottom", "Extend bottom (px)", 0, 2048, 0),
    IntField("passes", "Passes", 1, 8, 2),
    Choice("fill", "Pre-fill", Seq("Mirror", "Edge", "Gray"), "Mirror"),
    IntField("overlap", "Overlap with the existing image (px, also masked)", 0, 256, 32),
    IntField("mask_blur", "Mask blur (px)", 0, 64, 8)
  )

  private def multiple8(n: Int) = math.max(8, (n + 4) / 8 * 8)

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    Support.requireImg2Img(p0, name)
    val passes = v.int("passes", 2)
    val sides = Seq(v.int("left", 0), v.int("top", 0), v.int("right", 256), v.int("bottom", 0))
    require(sides.sum > 0, "Extend by at least one pixel")
    var cur = Support.readImage(ctx, p0.img2img.get.initImage)
    val outs = scala.collection.mutable.ArrayBuffer[OutputImage]()
    var running = true; var k = 1
    while running && k <= passes do
      val Seq(l, t, r, b) = sides.map(s => s / passes + (if k == passes then s % passes else 0))
      val ov = math.min(v.int("overlap", 32), math.min(cur.getWidth, cur.getHeight) / 2)
      val (img, mask0) = OutpaintCanvas.extend(cur, l, t, r, b, v.str("fill", "Mirror"))
      // widen the mask into the old image by `ov` px on the sides that grew
      val mask = new java.awt.image.BufferedImage(img.getWidth, img.getHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
      for y <- 0 until img.getHeight; x <- 0 until img.getWidth do
        val sx = x - l; val sy = y - t
        val inside = sx >= 0 && sx < cur.getWidth && sy >= 0 && sy < cur.getHeight
        val near = inside && ((l > 0 && sx < ov) || (t > 0 && sy < ov) || (r > 0 && sx >= cur.getWidth - ov) || (b > 0 && sy >= cur.getHeight - ov))
        mask.setRGB(x, y, if !inside || near then 0xFFFFFF else 0)
      val initP = Support.writeTemp(ctx, img); val maskP = Support.writeTemp(ctx, mask)
      try
        ctx.status(s"Mosaic outpaint pass $k/$passes", k - 1, passes)
        val q = p0.copy(width = multiple8(img.getWidth), height = multiple8(img.getHeight), batchSize = 1, nIter = 1,
          img2img = Some(p0.img2img.get.copy(initImage = initP.toString, mask = Some(maskP.toString), maskBlur = v.int("mask_blur", 8),
            inpaintingFill = 1, inpaintFullRes = false, inpaintingMaskInvert = false)),
          extra = p0.extra + ("Script" -> "Mosaic outpaint") + ("Mosaic outpaint pass" -> s"$k/$passes"))
        Support.step(ctx)(ctx.generate(q)) match
          case Some(o) if o.nonEmpty =>
            cur = ctx.readImage(o.head.path)
            if k == passes then outs ++= o
          case _ => running = false
      finally Support.deleteQuietly(initP, maskP)
      k += 1
    JobOutput(outs.toSeq)
