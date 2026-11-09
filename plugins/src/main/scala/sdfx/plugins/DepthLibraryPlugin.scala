package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*

/** Depth map helpers (own implementation; no upstream code): 8/16-bit grayscale handling, a folder-based depth library. */
object DepthIo:
  /** Reads a depth image of any grayscale/colour PNG depth (8 or 16 bit) as values 0..1. Colour images use their mean channel. */
  def values(img: BufferedImage): (Array[Double], Int, Int) =
    val w = img.getWidth; val h = img.getHeight
    val r = img.getRaster
    val vs = new Array[Double](w * h)
    val sixteen = r.getNumBands == 1 && img.getColorModel.getPixelSize == 16
    for y <- 0 until h; x <- 0 until w do
      vs(y * w + x) =
        if sixteen then r.getSample(x, y, 0) / 65535.0
        else { val c = img.getRGB(x, y); (((c >> 16 & 255) + (c >> 8 & 255) + (c & 255)) / 3.0) / 255.0 }
    (vs, w, h)

  /** Stretches the values to the full range (when `normalize`), optionally inverts, resizes to (ow, oh) with bilinear sampling. */
  def process(vs: Array[Double], w: Int, h: Int, normalize: Boolean, invert: Boolean, ow: Int, oh: Int): Array[Double] =
    val (mn, mx) = if normalize then (vs.min, vs.max) else (0.0, 1.0)
    val span = if mx - mn < 1e-12 then 1.0 else mx - mn
    def at(x: Int, y: Int) = (vs(math.max(0, math.min(h - 1, y)) * w + math.max(0, math.min(w - 1, x))) - mn) / span
    Array.tabulate(ow * oh) { i =>
      val x = i % ow; val y = i / ow
      val fx = (x + 0.5) * w / ow - 0.5; val fy = (y + 0.5) * h / oh - 0.5
      val x0 = math.floor(fx).toInt; val y0 = math.floor(fy).toInt; val tx = fx - x0; val ty = fy - y0
      val v = (at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx) * (1 - ty) + (at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx) * ty
      val c = math.max(0.0, math.min(1.0, v)); if invert then 1 - c else c
    }

  def to8(vs: Array[Double], w: Int, h: Int): BufferedImage =
    val o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for i <- vs.indices do { val g = math.round(vs(i) * 255).toInt; o.setRGB(i % w, i / w, g * 0x010101) }
    o

  def to16(vs: Array[Double], w: Int, h: Int): BufferedImage =
    val o = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY)
    for i <- vs.indices do o.getRaster.setSample(i % w, i / w, 0, math.round(vs(i) * 65535).toInt)
    o

  def freeName(dir: Path, stem: String): String =
    Iterator.from(0).map(n => if n == 0 then stem else s"$stem-$n").find(s => !Files.exists(dir.resolve(s + ".png"))).get

  def checkName(n: String): String =
    require(n.matches("[A-Za-z0-9._ -]+") && !n.contains(".."), "Name may only contain letters, digits, space, . _ -")
    n

class DepthImageIoPlugin extends ExtrasPlugin:
  def id = "depth_image_io"
  def name = "Depth image I/O"
  override def description = "Import: reads your own depth file (8 or 16-bit PNG), stretches it to full range, optional invert, resized to the " +
    "input image, returned as an 8-bit depth image (for ControlNet depth). Export: estimates the depth of the input (Depth Anything V2) " +
    "and writes a 16-bit grayscale PNG into your folder (never overwrites)."
  override def controls: Seq[Control] = Seq(
    Choice("operation", "Operation", Seq("Import depth file", "Export 16-bit depth PNG"), "Import depth file"),
    Text("file", "Import: depth file", ""),
    Check("normalize", "Import: stretch to full range", true),
    Check("invert", "Import: invert (near <-> far)", false),
    Text("folder", "Export: output folder", ""),
    Text("name", "Export: file name (no extension)", "depth")
  )
  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext) =
    if v.str("operation").startsWith("Import") then
      val f = Paths.get(v.str("file").trim)
      require(v.str("file").trim.nonEmpty && Files.isRegularFile(f) && Files.size(f) <= 64L * 1024 * 1024, "A readable depth file is required")
      val src = ImageIO.read(f.toFile); require(src != null, s"Cannot decode $f")
      val (vs, w, h) = DepthIo.values(src)
      val out = DepthIo.process(vs, w, h, v.bool("normalize", true), v.bool("invert", false), img.getWidth, img.getHeight)
      Seq((DepthIo.to8(out, img.getWidth, img.getHeight), Seq("Depth import" -> s"${f.getFileName} ${w}x$h -> ${img.getWidth}x${img.getHeight}")))
    else
      val folder = v.str("folder").trim; require(folder.nonEmpty, "An output folder is required")
      val dir = Paths.get(folder).toAbsolutePath.normalize(); Files.createDirectories(dir)
      val r = ImageTools.run(ctx, "depth_mask", img, "near" -> 0.0, "far" -> 1.0, "invert" -> false, "blur" -> 0)
      try
        val depth = Support.readImage(ctx, r.outputs.getOrElse("depth", throw new IllegalStateException("depth estimation returned no depth map")))
        val (vs, w, h) = DepthIo.values(depth)
        val name = DepthIo.freeName(dir, DepthIo.checkName(v.str("name", "depth").trim))
        val tmp = Files.createTempFile(dir, ".depth", ".png")
        ImageIO.write(DepthIo.to16(vs, w, h), "png", tmp.toFile)
        Files.move(tmp, dir.resolve(name + ".png"))
        ctx.log(s"Depth image I/O: ${dir.resolve(name + ".png")} (16-bit, ${w}x$h)")
        Seq((depth, Seq("Depth export" -> s"$name.png 16-bit ${w}x$h")))
      finally Support.deleteQuietly(r.outputs.values.toSeq*)

class DepthLibraryPlugin extends ExtrasPlugin:
  def id = "depth_library"
  def name = "Depth library"
  override def description = "A folder of named depth maps for ControlNet: Save (estimates the depth of the input with Depth Anything V2 and stores it " +
    "under a name, never overwriting), Load (returns a stored map resized to the input), List (names in the log)."
  override def controls: Seq[Control] = Seq(
    Text("folder", "Library folder", ""),
    Choice("operation", "Operation", Seq("Save", "Load", "List"), "Load"),
    Text("name", "Name", "")
  )
  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext) =
    val folder = v.str("folder").trim; require(folder.nonEmpty, "A library folder is required")
    val dir = Paths.get(folder).toAbsolutePath.normalize()
    v.str("operation") match
      case "Save" =>
        Files.createDirectories(dir)
        val r = ImageTools.run(ctx, "depth_mask", img, "near" -> 0.0, "far" -> 1.0, "invert" -> false, "blur" -> 0)
        try
          val depth = Support.readImage(ctx, r.outputs.getOrElse("depth", throw new IllegalStateException("depth estimation returned no depth map")))
          val name = DepthIo.freeName(dir, DepthIo.checkName(v.str("name").trim))
          val (vs, w, h) = DepthIo.values(depth)
          val tmp = Files.createTempFile(dir, ".depth", ".png")
          ImageIO.write(DepthIo.to16(vs, w, h), "png", tmp.toFile); Files.move(tmp, dir.resolve(name + ".png"))
          ctx.log(s"Depth library: saved $name")
          Seq((depth, Seq("Depth library" -> s"saved $name")))
        finally Support.deleteQuietly(r.outputs.values.toSeq*)
      case "Load" =>
        val f = dir.resolve(DepthIo.checkName(v.str("name").trim) + ".png")
        require(Files.isRegularFile(f), s"No depth map named '${v.str("name").trim}' in $dir")
        val (vs, w, h) = DepthIo.values(ImageIO.read(f.toFile))
        Seq((DepthIo.to8(DepthIo.process(vs, w, h, normalize = false, invert = false, img.getWidth, img.getHeight), img.getWidth, img.getHeight),
          Seq("Depth library" -> s"loaded ${v.str("name").trim}")))
      case "List" =>
        val names = if Files.isDirectory(dir) then Files.list(dir).iterator().asScala.map(_.getFileName.toString).filter(_.endsWith(".png")).map(_.stripSuffix(".png")).toSeq.sorted else Nil
        ctx.log(s"Depth library: ${names.size} map(s)\n" + names.mkString(", "))
        Seq((img, Seq("Depth library" -> s"${names.size} map(s)")))
      case o => throw new IllegalArgumentException(s"unknown operation: $o")
