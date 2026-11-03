package sdfx.plugins

import sdfx.core.image.Grid
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import sdfx.plugins.imageops.ImageOps

import java.awt.{Color, RenderingHints}
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import javax.imageio.{IIOImage, ImageIO, ImageTypeSpecifier}
import javax.imageio.metadata.IIOMetadataNode
import scala.collection.mutable.ArrayBuffer

/** Corridor Crawler Outpainting (brick2face/corridor-crawler-outpainting, AGPL-3.0, revision
  * 680aed7e96fd2785387949222acbb25dee16b332), re-implemented from its README and observed behaviour.
  *
  * Each step pastes the previous image, shrunk, into a "prefab" corridor picture and inpaints everything around it,
  * so the new picture shows the same hallway from one step further back. The source needs a hand-picked mask image
  * and a depth-to-image checkpoint; here the mask is derived from the window rectangle and the prefab can be the
  * built-in brick corridor, so any inpaint-capable SD1.5 / SDXL checkpoint works.
  */
object CorridorCrawlerPlugin:
  val BuiltInPrefab = "Brick corridor (built in)"
  val CustomPrefab = "Custom image"
  val Prefabs: Seq[String] = Seq(BuiltInPrefab, CustomPrefab)
  val Stretch = "Stretch"
  val PreserveAspect = "Preserve aspect"
  val Placements: Seq[String] = Seq(Stretch, PreserveAspect)
  val MinWindow = 16

  /** Window rectangle in canvas pixels. */
  final case class Rect(x: Int, y: Int, w: Int, h: Int):
    def x1: Int = x + w
    def y1: Int = y + h
    def text: String = s"$x,$y,$w,$h"

  /** Validated control values. Out-of-range values are rejected, not clamped. */
  final case class Settings(steps: Int, prefab: String, prefabImage: String, widthPct: Double, heightPct: Double,
      xPct: Double, yPct: Double, maskShrink: Int, makeGrid: Boolean, animate: Boolean, framesPerStep: Int,
      frameMs: Int, animSize: Int, placement: String):
    /** Window in pixels. The defaults give 254x254 at (127,81) on 512x512, the source's thumbnail box. */
    def window(width: Int, height: Int): Rect =
      val r = Rect(math.round(width * xPct / 100).toInt, math.round(height * yPct / 100).toInt,
        math.round(width * widthPct / 100).toInt, math.round(height * heightPct / 100).toInt)
      if r.w < MinWindow || r.h < MinWindow then
        throw new IllegalArgumentException(s"Corridor Crawler: the window is ${r.w}x${r.h}px, smaller than ${MinWindow}px")
      if r.x1 > width || r.y1 > height then
        throw new IllegalArgumentException(s"Corridor Crawler: the window (${r.text}) leaves the ${width}x$height canvas; lower its position or size")
      r
    def protectedRect(win: Rect): Rect =
      val m = maskShrink
      if win.w - 2 * m < 1 || win.h - 2 * m < 1 then
        throw new IllegalArgumentException(s"Corridor Crawler: a mask shrink of ${m}px leaves nothing of the ${win.w}x${win.h}px window")
      Rect(win.x + m, win.y + m, win.w - 2 * m, win.h - 2 * m)

  object Settings:
    private def number(v: Values, key: String, default: Double, min: Double, max: Double): Double =
      v.map.get(key).map(_.trim) match
        case None | Some("") => default
        case Some(s) =>
          val d = s.toDoubleOption.filter(x => !x.isNaN && !x.isInfinite)
            .getOrElse(throw new IllegalArgumentException(s"Corridor Crawler: $key must be a number (got '$s')"))
          if d < min - 1e-9 || d > max + 1e-9 then
            throw new IllegalArgumentException(s"Corridor Crawler: $key must be in ${Values.fmt(min)}..${Values.fmt(max)} (got $s)")
          d
    private def integer(v: Values, key: String, default: Int, min: Int, max: Int): Int =
      val d = number(v, key, default, min, max)
      if d != math.rint(d) then throw new IllegalArgumentException(s"Corridor Crawler: $key must be a whole number (got ${Values.fmt(d)})")
      d.toInt
    private def flag(v: Values, key: String, default: Boolean): Boolean = v.map.get(key).map(_.trim.toLowerCase) match
      case None | Some("")                           => default
      case Some("false") | Some("0") | Some("no")    => false
      case Some("true") | Some("1") | Some("yes")    => true
      case Some(s) => throw new IllegalArgumentException(s"Corridor Crawler: $key must be true or false (got '$s')")

    def parse(v: Values): Settings =
      val prefab = v.str("prefab", BuiltInPrefab)
      if !Prefabs.contains(prefab) then
        throw new IllegalArgumentException(s"Corridor Crawler: prefab must be one of ${Prefabs.mkString(", ")} (got '$prefab')")
      val placement = v.str("placement", Stretch)
      if !Placements.contains(placement) then
        throw new IllegalArgumentException(s"Corridor Crawler: placement must be one of ${Placements.mkString(", ")} (got '$placement')")
      val image = v.str("prefab_image").trim
      if prefab == CustomPrefab && image.isEmpty then
        throw new IllegalArgumentException("Corridor Crawler: choose a prefab image for the custom prefab")
      Settings(
        steps = integer(v, "steps", 3, 1, 30), prefab = prefab, prefabImage = image,
        widthPct = number(v, "window_width", 49.6, 10, 90), heightPct = number(v, "window_height", 49.6, 10, 90),
        xPct = number(v, "window_x", 24.8, 0, 90), yPct = number(v, "window_y", 15.8, 0, 90),
        maskShrink = integer(v, "mask_shrink", 0, 0, 64), makeGrid = flag(v, "make_grid", true),
        animate = flag(v, "animation", false), framesPerStep = integer(v, "frames_per_step", 20, 0, 120),
        frameMs = integer(v, "frame_ms", 50, 10, 1000), animSize = integer(v, "animation_size", 256, 64, 1024),
        placement = placement)

  // ------------------------------------------------------------------------------------------- images

  private def rgb(w: Int, h: Int): BufferedImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

  private def smooth(g: java.awt.Graphics2D): Unit =
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

  private def flatten(img: BufferedImage): BufferedImage =
    val out = rgb(img.getWidth, img.getHeight)
    val g = out.createGraphics()
    g.setColor(new Color(128, 128, 128))
    g.fillRect(0, 0, out.getWidth, out.getHeight)
    g.drawImage(img, 0, 0, null)
    g.dispose()
    out

  /** The picture resized to the whole canvas (a custom prefab is stretched to the canvas). */
  def fit(img: BufferedImage, w: Int, h: Int): BufferedImage =
    val flat = flatten(img)
    if flat.getWidth == w && flat.getHeight == h then flat
    else
      val out = rgb(w, h)
      val g = out.createGraphics()
      smooth(g)
      g.drawImage(flat, 0, 0, w, h, null)
      g.dispose()
      out

  private def quad(g: java.awt.Graphics2D, pts: (Double, Double)*): Unit =
    val p = new Path2D.Double()
    p.moveTo(pts.head._1, pts.head._2)
    pts.tail.foreach((x, y) => p.lineTo(x, y))
    p.closePath()
    g.fill(p)

  /** One-point perspective brick corridor: ceiling, floor and walls converge on the window. It only has to give the
    * model a hallway layout to repaint; the window itself is overwritten by the previous step.
    */
  def brickCorridor(w: Int, h: Int, win: Rect): BufferedImage =
    val out = rgb(w, h)
    val g = out.createGraphics()
    smooth(g)
    val (x0, y0, x1, y1) = (0.0, 0.0, w.toDouble, h.toDouble)
    val (wx0, wy0, wx1, wy1) = (win.x.toDouble, win.y.toDouble, win.x1.toDouble, win.y1.toDouble)
    g.setColor(new Color(40, 36, 34))
    g.fillRect(0, 0, w, h)
    // surfaces: ceiling, floor, left wall, right wall (outer edge to window edge)
    g.setColor(new Color(92, 86, 80)); quad(g, (x0, y0), (x1, y0), (wx1, wy0), (wx0, wy0))
    g.setColor(new Color(118, 104, 92)); quad(g, (x0, y1), (x1, y1), (wx1, wy1), (wx0, wy1))
    g.setColor(new Color(150, 98, 76)); quad(g, (x0, y0), (wx0, wy0), (wx0, wy1), (x0, y1))
    g.setColor(new Color(158, 104, 80)); quad(g, (x1, y0), (wx1, wy0), (wx1, wy1), (x1, y1))
    // mortar lines: courses run into the window, depth ticks get denser towards it
    g.setStroke(new java.awt.BasicStroke(math.max(1f, w / 256f)))
    g.setColor(new Color(60, 44, 38))
    val courses = 8
    for k <- 0 to courses do
      val f = k.toDouble / courses
      g.draw(new java.awt.geom.Line2D.Double(x0, y0 + (y1 - y0) * f, wx0, wy0 + (wy1 - wy0) * f))
      g.draw(new java.awt.geom.Line2D.Double(x1, y0 + (y1 - y0) * f, wx1, wy0 + (wy1 - wy0) * f))
      g.draw(new java.awt.geom.Line2D.Double(x0 + (x1 - x0) * f, y0, wx0 + (wx1 - wx0) * f, wy0))
      g.draw(new java.awt.geom.Line2D.Double(x0 + (x1 - x0) * f, y1, wx0 + (wx1 - wx0) * f, wy1))
    for k <- 1 to 6 do
      val d = k * 0.45
      val s = d / (1 + d) // 0 at the outer edge, towards 1 at the window
      def lerp(a: Double, b: Double) = a + (b - a) * s
      g.draw(new java.awt.geom.Line2D.Double(lerp(x0, wx0), lerp(y0, wy0), lerp(x0, wx0), lerp(y1, wy1)))
      g.draw(new java.awt.geom.Line2D.Double(lerp(x1, wx1), lerp(y0, wy0), lerp(x1, wx1), lerp(y1, wy1)))
      g.draw(new java.awt.geom.Line2D.Double(lerp(x0, wx0), lerp(y0, wy0), lerp(x1, wx1), lerp(y0, wy0)))
      g.draw(new java.awt.geom.Line2D.Double(lerp(x0, wx0), lerp(y1, wy1), lerp(x1, wx1), lerp(y1, wy1)))
    g.dispose()
    out

  /** Fit the previous image in the upper left; never enlarge in preserve-aspect mode. */
  def placementRect(previous: BufferedImage, win: Rect, placement: String): Rect =
    placementRect(previous.getWidth, previous.getHeight, win, placement)

  private def placementRect(width: Int, height: Int, win: Rect, placement: String): Rect =
    if placement == Stretch then win
    else
      val scale = math.min(1.0, math.min(win.w.toDouble / width, win.h.toDouble / height))
      Rect(win.x, win.y, math.max(1, math.min(win.w, math.round(width * scale).toInt)),
        math.max(1, math.min(win.h, math.round(height * scale).toInt)))

  /** The prefab with `previous` pasted (stretched) into the window. */
  def composite(prefab: BufferedImage, previous: BufferedImage, win: Rect): BufferedImage =
    val out = rgb(prefab.getWidth, prefab.getHeight)
    val g = out.createGraphics()
    smooth(g)
    g.drawImage(prefab, 0, 0, null)
    g.drawImage(flatten(previous), win.x, win.y, win.w, win.h, null)
    g.dispose()
    out

  /** Inpaint mask: white = repaint everywhere except the (shrunk) window, which is black = keep. */
  def mask(w: Int, h: Int, keep: Rect): BufferedImage =
    val out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
    val g = out.createGraphics()
    g.setColor(Color.WHITE)
    g.fillRect(0, 0, w, h)
    g.setColor(Color.BLACK)
    g.fillRect(keep.x, keep.y, keep.w, keep.h)
    g.dispose()
    out

  // ------------------------------------------------------------------------------------------- animation

  private def lerpInt(a: Int, b: Int, t: Double): Int = math.round(a + (b - a) * t).toInt

  /** Zoom from the newer picture into the older one. The newer picture is enlarged until its window has the size of
    * the older picture, the older picture is pasted there, and a crop rectangle moves from the whole canvas to the
    * older picture, so the camera walks into the corridor. Frames are scaled to fit `size` x `size`.
    * `frames` = frames per transition (the end frame belongs to the next transition), 0 = none.
    */
  def zoomFrames(newer: BufferedImage, older: BufferedImage, win: Rect, frames: Int, size: Int): Seq[BufferedImage] =
    if frames <= 0 then Nil
    else
      val w = newer.getWidth
      val h = newer.getHeight
      val sx = w.toDouble / win.w
      val sy = h.toDouble / win.h
      val cw = math.round(w * sx).toInt
      val ch = math.round(h * sy).toInt
      val canvas = rgb(cw, ch)
      val g = canvas.createGraphics()
      smooth(g)
      g.drawImage(newer, 0, 0, cw, ch, null)
      val ox = math.round(win.x * sx).toInt
      val oy = math.round(win.y * sy).toInt
      g.drawImage(flatten(older), ox, oy, w, h, null)
      g.dispose()
      val (tw, th) = fitSize(w, h, size)
      (0 until frames).map { f =>
        val t = f.toDouble / frames
        val rx0 = lerpInt(0, ox, t); val ry0 = lerpInt(0, oy, t)
        val rx1 = lerpInt(cw, ox + w, t); val ry1 = lerpInt(ch, oy + h, t)
        val out = rgb(tw, th)
        val og = out.createGraphics()
        smooth(og)
        og.drawImage(canvas, 0, 0, tw, th, rx0, ry0, rx1, ry1, null)
        og.dispose()
        out
      }

  /** Longest side `size`, aspect kept, never enlarged (PIL `thumbnail`). */
  def fitSize(w: Int, h: Int, size: Int): (Int, Int) =
    if w <= size && h <= size then (w, h)
    else if w >= h then (size, math.max(1, math.round(h.toDouble * size / w).toInt))
    else (math.max(1, math.round(w.toDouble * size / h).toInt), size)

  def scaled(img: BufferedImage, size: Int): BufferedImage =
    val (tw, th) = fitSize(img.getWidth, img.getHeight, size)
    ImageOps.resize(img, tw, th)

  /** Frames of the whole walk, from the newest step in to the start image. `steps` is oldest first, starting with the
    * start image. With `frames` = 0 the animation is a slideshow of the steps.
    */
  def walk(steps: Seq[BufferedImage], win: Rect, frames: Int, size: Int): Seq[BufferedImage] =
    val newestFirst = steps.reverse
    if frames == 0 then newestFirst.map(scaled(_, size))
    else
      newestFirst.sliding(2).flatMap(pair => zoomFrames(pair(0), pair(1), win, frames, size)).toSeq :+ scaled(steps.head, size)

  /** Letterbox a crop to the fixed GIF canvas without stretching either source axis. */
  private def fittedFrame(img: BufferedImage, x: Int, y: Int, width: Int, height: Int, tw: Int, th: Int): BufferedImage =
    val out = rgb(tw, th)
    val g = out.createGraphics()
    smooth(g)
    val scale = math.min(tw.toDouble / width, th.toDouble / height)
    val dw = math.max(1, math.round(width * scale).toInt)
    val dh = math.max(1, math.round(height * scale).toInt)
    val dx = (tw - dw) / 2; val dy = (th - dh) / 2
    g.drawImage(img, dx, dy, dx + dw, dy + dh, x, y, x + width, y + height, null)
    g.dispose()
    out

  /** Aspect-safe zoom uses each step's actual pasted rectangle and fixed-size letterboxed frames. */
  def aspectWalk(steps: Seq[BufferedImage], windows: Seq[Rect], frames: Int, size: Int): Seq[BufferedImage] =
    require(steps.nonEmpty && windows.size == steps.size - 1)
    val (tw, th) = fitSize(steps.last.getWidth, steps.last.getHeight, size)
    def fitted(img: BufferedImage) = fittedFrame(img, 0, 0, img.getWidth, img.getHeight, tw, th)
    if frames == 0 then steps.reverse.map(fitted)
    else
      val transitions = steps.reverse.sliding(2).zip(windows.reverse).flatMap { (pair, win) =>
        val newer = pair(0); val older = pair(1)
        val canvas = composite(newer, older, win)
        (0 until frames).map { i =>
          val t = i.toDouble / frames
          val x = lerpInt(0, win.x, t); val y = lerpInt(0, win.y, t)
          val x1 = lerpInt(newer.getWidth, win.x1, t); val y1 = lerpInt(newer.getHeight, win.y1, t)
          fittedFrame(canvas, x, y, math.max(1, x1 - x), math.max(1, y1 - y), tw, th)
        }
      }.toSeq
      transitions :+ fitted(steps.head)

  /** Prefer atomic publication; fall back to exclusive creation when hard links are unavailable. */
  private[plugins] def publishGif(temp: Path, out: Path,
      link: (Path, Path) => Unit = (target, source) => { Files.createLink(target, source); () },
      open: Path => java.io.OutputStream = path => Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)): Unit =
    try link(out, temp)
    catch
      case exists: java.nio.file.FileAlreadyExistsException => throw exists
      case scala.util.control.NonFatal(_) =>
        val dest = open(out)
        try Files.copy(temp, dest)
        catch
          case error: Throwable =>
            try dest.close() catch case close: Throwable => error.addSuppressed(close)
            if scala.util.control.NonFatal(error) then
              throw new java.io.IOException(s"GIF copy failed; an incomplete file may remain at $out", error)
            else throw error
        try dest.close() catch
          case scala.util.control.NonFatal(error) =>
            throw new java.io.IOException(s"GIF copy failed; an incomplete file may remain at $out", error)

  /** Looping GIF with a fixed frame delay (the GIF clock has a 10 ms resolution). */
  def writeGif(frames: Seq[BufferedImage], delayMs: Int, out: Path): Unit =
    require(frames.nonEmpty, "no frames")
    // Publish only a fully written file and never replace a pre-existing destination.
    val parent = out.toAbsolutePath.normalize().getParent
    val temp = Files.createTempFile(parent, ".corridor-gif-", ".tmp")
    var stream: java.io.OutputStream = null
    var failure: Throwable = null
    var published = false
    try
      stream = Files.newOutputStream(temp, java.nio.file.StandardOpenOption.WRITE)
      val writer = ImageIO.getImageWritersByFormatName("gif").next()
      val ios = try ImageIO.createImageOutputStream(stream) catch
        case error: Throwable =>
          writer.dispose()
          throw error
      if ios == null then
        writer.dispose()
        throw new java.io.IOException("Cannot create GIF output stream")
      try
        writer.setOutput(ios)
        writer.prepareWriteSequence(null)
        frames.zipWithIndex.foreach { (frame, i) =>
          val param = writer.getDefaultWriteParam
          val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), param)
          val format = meta.getNativeMetadataFormatName
          val root = meta.getAsTree(format).asInstanceOf[IIOMetadataNode]
          def child(name: String): IIOMetadataNode =
            val existing = (0 until root.getLength).map(root.item).collectFirst { case n: IIOMetadataNode if n.getNodeName == name => n }
            existing.getOrElse { val n = new IIOMetadataNode(name); root.appendChild(n); n }
          val gce = child("GraphicControlExtension")
          gce.setAttribute("disposalMethod", "none")
          gce.setAttribute("userInputFlag", "FALSE")
          gce.setAttribute("transparentColorFlag", "FALSE")
          gce.setAttribute("delayTime", math.max(1, delayMs / 10).toString)
          gce.setAttribute("transparentColorIndex", "0")
          if i == 0 then
            val apps = child("ApplicationExtensions")
            val app = new IIOMetadataNode("ApplicationExtension")
            app.setAttribute("applicationID", "NETSCAPE")
            app.setAttribute("authenticationCode", "2.0")
            app.setUserObject(Array[Byte](1, 0, 0)) // loop forever
            apps.appendChild(app)
          meta.setFromTree(format, root)
          writer.writeToSequence(new IIOImage(frame, null, meta), param)
        }
        writer.endWriteSequence()
      finally
        try ios.close() finally writer.dispose()
      stream.close()
      stream = null
      publishGif(temp, out.toAbsolutePath.normalize())
      published = true
    catch
      case error: Throwable =>
        failure = error
        throw error
    finally
      var cleanupError: Throwable = null
      try if stream != null then stream.close() catch case e: Throwable => cleanupError = e
      try Files.deleteIfExists(temp) catch
        case e: Throwable =>
          if cleanupError == null then cleanupError = e else cleanupError.addSuppressed(e)
      if cleanupError != null then
        if failure != null then failure.addSuppressed(cleanupError)
        else if !published then throw cleanupError

/** "Corridor Crawler Outpainting": img2img Script. */
class CorridorCrawlerPlugin extends Plugin:
  import CorridorCrawlerPlugin.*

  def id = "corridor_crawler"
  def name = "Corridor Crawler Outpainting"
  def kind = PluginKind.Script
  override def tabs: Set[Tab] = Set(Tab.Img2Img)
  override def description = "Walks backwards down a hallway: pastes the picture, shrunk, into a corridor prefab and " +
    "inpaints around it, step after step, optionally as a zoom-in GIF. img2img only."
  override def enabledBy: Map[String, String] = Map("frames_per_step" -> "animation", "frame_ms" -> "animation",
    "animation_size" -> "animation")

  override def controls: Seq[Control] = Seq(
    Note("help", "Needs an img2img init image (the first view). Each step shrinks the previous picture into the window " +
      "of the prefab and repaints the rest, so the hallway grows around it. The mask is built from the window; do not " +
      "draw one. Use denoising strength 0.8-0.95 so the prefab guides the layout. Batch size and count must be 1."),
    IntField("steps", "Number of corridor steps", 1, 30, 3),
    Choice("prefab", "Prefab", Prefabs, BuiltInPrefab),
    ImageFile("prefab_image", "Custom prefab image (stretched to the canvas)"),
    Section("window", "Window (percent of the canvas)", expanded = false),
    Slider("window_width", "Window width", 10, 90, 0.1, 49.6),
    Slider("window_height", "Window height", 10, 90, 0.1, 49.6),
    Slider("window_x", "Window left", 0, 90, 0.1, 24.8),
    Slider("window_y", "Window top", 0, 90, 0.1, 15.8),
    Choice("placement", "Previous image placement", Placements, Stretch),
    IntField("mask_shrink", "Shrink the kept area (px)", 0, 64, 0),
    Section("output", "Output", expanded = true),
    Check("make_grid", "Make a grid of all steps", true),
    Check("animation", "Save a zoom-in GIF"),
    Note("animation_help", "Existing GIF files are never overwritten. If GIF saving fails, generated images and an incomplete GIF may remain."),
    IntField("frames_per_step", "Frames per step (0 = slideshow)", 0, 120, 20),
    IntField("frame_ms", "Milliseconds per frame", 10, 1000, 50),
    IntField("animation_size", "Animation size (longest side)", 64, 1024, 256)
  )

  private def fail(msg: String): Nothing = throw new IllegalArgumentException(s"$name: $msg")

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    Support.requireImg2Img(p0, name)
    val s = Settings.parse(v)
    val i0 = p0.img2img.get
    if p0.batchSize != 1 || p0.nIter != 1 then fail("batch size and batch count must be 1")
    if i0.mask.isDefined then fail("builds its own mask; clear the inpaint mask")
    if i0.inpaintFullRes then fail("needs the whole picture inpainted; turn off 'Only masked'")
    if i0.inpaintingMaskInvert then fail("builds its own mask; turn off mask inversion")
    if s.animate then ctx.requirePlaintextVideoAllowed()
    val w = p0.width
    val h = p0.height
    val win = s.window(w, h)
    val prefab =
      if s.prefab == BuiltInPrefab then brickCorridor(w, h, win)
      else
        val path = java.nio.file.Paths.get(s.prefabImage)
        if !Files.isRegularFile(path) then fail(s"prefab image not found: ${s.prefabImage}")
        fit(Support.readImage(ctx, path), w, h)
    val input = Support.readImage(ctx, i0.initImage)
    val start = if s.placement == Stretch then fit(input, w, h) else input
    // Validate both the first image and later generated canvas-sized images before any generation.
    s.protectedRect(placementRect(start, win, s.placement))
    if s.steps > 1 then s.protectedRect(placementRect(w, h, win, s.placement))
    val seed = Support.fixSeed(p0.seed)
    val prefabName = if s.prefab == BuiltInPrefab then "Brick corridor" else Path.of(s.prefabImage).getFileName.toString
    val extra0 = p0.extra ++ Seq("Corridor Crawler steps" -> s.steps.toString, "Corridor Crawler prefab" -> prefabName,
      "Corridor Crawler window" -> win.text, "Corridor Crawler placement" -> s.placement) ++ (if s.maskShrink > 0 then Seq("Corridor Crawler mask shrink" -> s.maskShrink.toString) else Nil)
    val outputs = ArrayBuffer[OutputImage]()
    val images = ArrayBuffer[BufferedImage](start) // oldest first, the start image included
    val placedWindows = ArrayBuffer[Rect]()
    var previous = start
    var running = true
    var i = 0
    while running && i < s.steps do
      val placed = placementRect(previous, win, s.placement)
      val keep = s.protectedRect(placed)
      val initFile = Support.writeTemp(ctx, composite(prefab, previous, placed))
      val maskFile = try Support.writeTemp(ctx, mask(w, h, keep)) catch
        case e: Throwable =>
          Support.deleteQuietly(initFile)
          throw e
      try
        val p = p0.copy(seed = seed, batchSize = 1, nIter = 1, width = w, height = h,
            extra = extra0.updated("Corridor Crawler step", s"${i + 1}/${s.steps}")
              .updated("Corridor Crawler image window", placed.text),
            img2img = Some(i0.copy(initImage = initFile.toString, mask = Some(maskFile.toString), resizeMode = 0,
              inpaintFullRes = false, inpaintingMaskInvert = false)))
        ctx.status(s"$name: step ${i + 1}/${s.steps}", i, s.steps)
        Support.step(ctx)(ctx.generate(p)).flatMap(_.headOption) match
          case None => running = false
          case Some(o) =>
            outputs += o
            previous = ctx.readImage(o.path)
            images += previous
            placedWindows += placed
      finally
        Support.deleteQuietly(initFile)
        Support.deleteQuietly(maskFile)
      i += 1
    if outputs.size < s.steps then ctx.log(s"$name interrupted after ${outputs.size} of ${s.steps} step(s)")
    val grids =
      if s.makeGrid && outputs.size > 1 then
        val cols = math.min(outputs.size, 4)
        val gp = Support.gridParams(p0.copy(extra = extra0 + ("Script" -> "Corridor Crawler")), outputs.toSeq)
        Seq(ctx.saveImage(Grid.labeled(outputs.toSeq.map(Support.gridTile(ctx, _)), cols, Nil, Nil), "grids", gp,
          Support.infotext(gp, outputs.head.seed)))
      else Nil
    if s.animate && outputs.size == s.steps then
      val frames = if s.placement == Stretch then walk(images.toSeq, win, s.framesPerStep, s.animSize)
        else aspectWalk(images.toSeq, placedWindows.toSeq, s.framesPerStep, s.animSize)
      val last = outputs.last
      val gif = last.path.resolveSibling(last.path.getFileName.toString.stripSuffix(".png") + "-corridor.gif")
      writeGif(frames, s.frameMs, gif)
      ctx.log(s"$name: wrote ${frames.size}-frame animation ${gif.getFileName}")
    JobOutput(outputs.toSeq, grids)
