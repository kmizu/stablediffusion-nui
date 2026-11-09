package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

/** Textured 3D mesh (Wavefront OBJ + MTL + PNG) from an image and its depth map (re-implemented, no upstream code). */
object DepthMesh:
  /** Returns the OBJ text. Vertices sit on a `step`-pixel grid; x/y are in [-aspect/2, aspect/2] x [-0.5, 0.5], z = depth * zScale
    * (depth 1 = near, towards the viewer). Quads whose corner depths differ by more than `edge` (0..1) are dropped so that
    * silhouettes do not turn into stretched sheets. */
  def obj(w: Int, h: Int, depth: BufferedImage, step: Int, zScale: Double, edge: Double, mtl: String): (String, Int, Int) =
    require(step >= 1 && w >= 2 && h >= 2, "invalid mesh size")
    val cols = (w - 1) / step + 1
    val rows = (h - 1) / step + 1
    require(cols >= 2 && rows >= 2, "image too small for this step")
    val aspect = w.toDouble / h
    def d(c: Int, r: Int): Double = (depth.getRGB(math.min(w - 1, c * step), math.min(h - 1, r * step)) & 0xFF) / 255.0
    val sb = new StringBuilder
    sb.append(s"mtllib $mtl\nusemtl material0\n")
    for r <- 0 until rows; c <- 0 until cols do
      val u = math.min(w - 1, c * step).toDouble / (w - 1)
      val v = math.min(h - 1, r * step).toDouble / (h - 1)
      sb.append(f"v ${(u - 0.5) * aspect}%.5f ${0.5 - v}%.5f ${d(c, r) * zScale}%.5f\n".replace(',', '.'))
    for r <- 0 until rows; c <- 0 until cols do
      val u = math.min(w - 1, c * step).toDouble / (w - 1)
      val v = math.min(h - 1, r * step).toDouble / (h - 1)
      sb.append(f"vt $u%.5f ${1.0 - v}%.5f\n".replace(',', '.'))
    var faces = 0
    def idx(c: Int, r: Int) = r * cols + c + 1
    for r <- 0 until rows - 1; c <- 0 until cols - 1 do
      val ds = Seq(d(c, r), d(c + 1, r), d(c, r + 1), d(c + 1, r + 1))
      if ds.max - ds.min <= edge then
        val a = idx(c, r); val b = idx(c + 1, r); val cc = idx(c, r + 1); val dd = idx(c + 1, r + 1)
        sb.append(s"f $a/$a $cc/$cc $b/$b\nf $b/$b $cc/$cc $dd/$dd\n")
        faces += 2
    (sb.toString, cols * rows, faces)

  /** First free `<stem>[-n]` in `dir` for all three files (never overwrites). */
  def freeStem(dir: Path, stem: String): String =
    Iterator.from(0).map(n => if n == 0 then stem else s"$stem-$n").find { s =>
      Seq(".obj", ".mtl", ".png").forall(e => !Files.exists(dir.resolve(s + e)))
    }.get

class DepthMeshPlugin extends ExtrasPlugin:
  def id = "depth_mesh"
  def name = "Depth 3D mesh"
  override def description = "Estimates depth (Depth Anything V2) and writes a textured 3D mesh (.obj + .mtl + .png) of the image into " +
    "the folder you give. Triangles across large depth jumps are dropped. Returns the depth map. Files are never overwritten."
  override def controls: Seq[Control] = Seq(
    Text("folder", "Output folder (created if missing)", ""),
    Text("name", "File name (no extension)", "depth-mesh"),
    IntField("step", "Grid step (pixels per vertex)", 1, 64, 4),
    Slider("depth_scale", "Depth scale (height of the nearest point, relative to image height)", 0, 2, 0.05, 0.3),
    Slider("edge", "Drop triangles across depth jumps larger than", 0.02, 1, 0.01, 0.08)
  )
  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext) =
    val folder = v.str("folder").trim
    require(folder.nonEmpty, "An output folder is required")
    val stem = v.str("name", "depth-mesh").trim
    require(stem.nonEmpty && stem.matches("[A-Za-z0-9._ -]+") && !stem.contains(".."), "File name may only contain letters, digits, space, . _ -")
    val dir = Paths.get(folder).toAbsolutePath.normalize()
    val r = ImageTools.run(ctx, "depth_mask", img, "near" -> 0.0, "far" -> 1.0, "invert" -> false, "blur" -> 0)
    try
      val depth = Support.readImage(ctx, r.outputs.getOrElse("depth", throw new IllegalStateException("depth estimation returned no depth map")))
      Files.createDirectories(dir)
      val name = DepthMesh.freeStem(dir, stem)
      val (obj, verts, faces) = DepthMesh.obj(img.getWidth, img.getHeight, depth, v.int("step", 4), v.double("depth_scale", 0.3), v.double("edge", 0.08), name + ".mtl")
      require(faces > 0, "No triangles left; raise the depth-jump limit")
      javax.imageio.ImageIO.write(img, "png", dir.resolve(name + ".png").toFile)
      Files.writeString(dir.resolve(name + ".mtl"), s"newmtl material0\nKd 1 1 1\nmap_Kd $name.png\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
      Files.writeString(dir.resolve(name + ".obj"), obj, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
      ctx.log(s"Depth mesh: ${dir.resolve(name + ".obj")} ($verts vertices, $faces triangles)")
      Seq((depth, Seq("Depth mesh" -> s"$name.obj, $verts vertices, $faces triangles")))
    finally Support.deleteQuietly(r.outputs.values.toSeq*)
