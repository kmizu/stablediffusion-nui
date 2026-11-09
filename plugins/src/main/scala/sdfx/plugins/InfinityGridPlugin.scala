package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import sdfx.core.prompttools.MiniYaml
import sdfx.core.prompttools.YNode.*
import java.awt.image.BufferedImage
import java.awt.{Color, Font, RenderingHints}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Multi-axis grids from a YAML description (own format and implementation; no upstream code).
  * {{{
  * axes:
  *   steps:  { values: "10, 20, 30" }
  *   cfg:    { values: "5, 7.5" }
  *   prompt: { values: "red hair | blue hair" }   # `|` separates values; prompt values are appended to the base prompt
  * }}}
  * Supported axis names: steps, cfg, sampler, seed, width, height, prompt, negative. Axes 1 and 2 make columns and rows;
  * every further axis creates one more grid page. */
object InfinityGrid:
  final case class Axis(name: String, values: Vector[String])
  private val Supported = Set("steps", "cfg", "sampler", "seed", "width", "height", "prompt", "negative")

  def parse(yaml: String): Vector[Axis] =
    val root = MiniYaml.parse(yaml)
    val axes = root match
      case YMap(es) => es.collectFirst { case ("axes", YMap(a)) => a }.getOrElse(Vector.empty)
      case _        => Vector.empty
    require(axes.nonEmpty, "The YAML needs an `axes:` map")
    axes.map { (name, n) =>
      require(Supported(name), s"Unsupported axis '$name' (supported: ${Supported.toSeq.sorted.mkString(", ")})")
      val raw = n match
        case YMap(es) => es.collectFirst { case ("values", YStr(s)) => s }.getOrElse(throw new IllegalArgumentException(s"Axis '$name' has no values"))
        case YStr(s)  => s
        case _        => throw new IllegalArgumentException(s"Axis '$name' has no values")
      val sep = if raw.contains("|") then "\\|" else ","
      val vals = raw.split(sep).iterator.map(_.trim).filter(_.nonEmpty).toVector
      require(vals.nonEmpty, s"Axis '$name' has no values")
      Axis(name, vals)
    }

  def combos(axes: Seq[Axis]): Vector[Vector[(Axis, String)]] =
    axes.foldLeft(Vector(Vector.empty[(Axis, String)])) { (acc, a) => for c <- acc; v <- a.values yield c :+ (a -> v) }

  def apply(p: GenerationParams, c: Seq[(Axis, String)]): GenerationParams =
    c.foldLeft(p) { case (q, (a, v)) =>
      a.name match
        case "steps"    => q.copy(steps = v.toInt)
        case "cfg"      => q.copy(cfgScale = v.toDouble)
        case "sampler"  => q.copy(samplerName = v)
        case "seed"     => q.copy(seed = v.toLong)
        case "width"    => q.copy(width = v.toInt)
        case "height"   => q.copy(height = v.toInt)
        case "prompt"   => q.copy(prompt = Seq(q.prompt.trim.stripSuffix(","), v).filter(_.nonEmpty).mkString(", "))
        case "negative" => q.copy(negativePrompt = Seq(q.negativePrompt.trim.stripSuffix(","), v).filter(_.nonEmpty).mkString(", "))
    }

  /** One grid page: columns = axis 1 values, rows = axis 2 values (a single row when there is only one axis). */
  def page(cells: Map[(Int, Int), BufferedImage], cols: Seq[String], rows: Seq[String], colName: String, rowName: String, title: String): BufferedImage =
    val tw = cells.values.map(_.getWidth).max; val th = cells.values.map(_.getHeight).max
    val head = 28; val side = if rows.size > 1 || rowName.nonEmpty then 110 else 0; val top = 22 + head
    val img = new BufferedImage(side + cols.size * tw, top + rows.size * th, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setColor(Color.WHITE); g.fillRect(0, 0, img.getWidth, img.getHeight)
    g.setColor(Color.BLACK); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14))
    if title.nonEmpty then g.drawString(title, 6, 16)
    for ((c, r), im) <- cells do g.drawImage(im, side + c * tw, top + r * th, null)
    for (v, c) <- cols.zipWithIndex do g.drawString(s"$colName: ${v.take(40)}", side + c * tw + 6, top - 8)
    if side > 0 then for (v, r) <- rows.zipWithIndex do g.drawString(s"$rowName: ${v.take(14)}", 4, top + r * th + 20)
    g.dispose(); img

class InfinityGridPlugin extends Plugin:
  def id = "infinity_grid"
  def name = "Infinity grid"
  def kind = PluginKind.Script
  override def description = "Reads a YAML file with `axes:` (steps, cfg, sampler, seed, width, height, prompt, negative; values separated by `,` " +
    "or `|`) and generates every combination. Axis 1 = columns, axis 2 = rows, more axes make more grid pages. Returns the grids first, then the single images."
  override def controls: Seq[Control] = Seq(
    Text("file", "Grid YAML file", ""),
    IntField("max_images", "Safety limit: max images", 1, 2000, 64),
    Check("keep_images", "Also return the single images", true)
  )

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val path = v.str("file").trim
    require(path.nonEmpty, "A grid YAML file path is required")
    val f = Paths.get(path)
    require(Files.isRegularFile(f) && Files.size(f) <= 1024L * 1024, s"Cannot read $path (missing or larger than 1 MiB)")
    val axes = InfinityGrid.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))
    val all = InfinityGrid.combos(axes)
    require(all.size <= v.int("max_images", 64), s"${all.size} images exceed the limit ${v.int("max_images", 64)}")
    val seed = if p0.seed >= 0 then p0.seed else scala.util.Random.nextInt(Int.MaxValue).toLong
    val base = p0.copy(seed = seed, batchSize = 1, nIter = 1)
    val results = scala.collection.mutable.LinkedHashMap[Vector[(InfinityGrid.Axis, String)], OutputImage]()
    var running = true; val it = all.iterator.zipWithIndex
    while running && it.hasNext do
      val (c, i) = it.next()
      ctx.status(s"Infinity grid ${i + 1}/${all.size}", i, all.size)
      val q = InfinityGrid(base, c)
      Support.step(ctx)(ctx.generate(q.copy(extra = q.extra + ("Script" -> "Infinity grid") + ("Infinity grid cell" -> c.map((a, x) => s"${a.name}=$x").mkString(", "))))) match
        case Some(o) if o.nonEmpty => results(c) = o.head
        case _                     => running = false
    // one page per combination of axes 3..n
    val grids = scala.collection.mutable.ArrayBuffer[OutputImage]()
    if results.nonEmpty then
      val colAx = axes.head; val rowAx = axes.lift(1)
      val rest = axes.drop(2)
      for tail <- InfinityGrid.combos(rest) do
        val cells = (for
          (cv, ci) <- colAx.values.zipWithIndex; (rv, ri) <- rowAx.map(_.values).getOrElse(Vector("")).zipWithIndex
          out <- results.get(Vector(colAx -> cv) ++ rowAx.map(_ -> rv).toVector ++ tail)
        yield (ci, ri) -> ctx.readImage(out.path)).toMap
        if cells.nonEmpty then
          val title = tail.map((a, x) => s"${a.name}=$x").mkString(", ")
          val img = InfinityGrid.page(cells, colAx.values, rowAx.map(_.values).getOrElse(Vector("")), colAx.name, rowAx.map(_.name).getOrElse(""), title)
          val gp = base.copy(extra = base.extra + ("Script" -> "Infinity grid"))
          grids += ctx.saveImage(img, "grids", gp, Support.infotext(gp, seed))
    JobOutput(if v.bool("keep_images", true) then results.values.toSeq else Nil, grids.toSeq)
