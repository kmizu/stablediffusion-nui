package sdfx.core.panorama

import java.awt.image.BufferedImage

/** A plain ARGB raster (row-major). The panorama maths never touches JavaFX, so it is testable headless. */
final class Raster(val width: Int, val height: Int, val argb: Array[Int]):
  require(width > 0 && height > 0, "empty raster")
  require(argb.length == width * height, "pixel count does not match the size")
  def pixel(x: Int, y: Int): Int = argb(y * width + x)
  def toBufferedImage: BufferedImage =
    val img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, width, height, argb, 0, width)
    img

object Raster:
  def fromImage(img: BufferedImage): Raster =
    val w = img.getWidth
    val h = img.getHeight
    new Raster(w, h, img.getRGB(0, 0, w, h, null, 0, w))

enum Projection(val label: String):
  case Equirectangular extends Projection("Equirectangular (2:1)")
  case CubemapNet extends Projection("Cubemap: Polyhedron net (4:3)")

/** Camera orientation in degrees: yaw 0 looks at the middle of the image and positive turns right, pitch positive looks up,
  * `fov` is the horizontal field of view.
  */
final case class View(yaw: Double = 0, pitch: Double = 0, fov: Double = 90):
  def normalized: View =
    val y = ((yaw + 180) % 360 + 360) % 360 - 180
    View(y, math.max(-90.0, math.min(90.0, pitch)), math.max(View.MinFov, math.min(View.MaxFov, fov)))
  def turned(dYaw: Double, dPitch: Double): View = View(yaw + dYaw, pitch + dPitch, fov).normalized
  def zoomed(factor: Double): View = View(yaw, pitch, fov * factor).normalized

object View:
  val MinFov = 20.0
  val MaxFov = 120.0

object Panorama:
  /** Which projection a picture of this size is shaped for, if any (the extension recommends 2:1 and 4:3). */
  def detect(width: Int, height: Int, tolerance: Double = 0.03): Option[Projection] =
    if width <= 0 || height <= 0 then None
    else
      val ratio = width.toDouble / height
      if math.abs(ratio - 2.0) <= 2.0 * tolerance then Some(Projection.Equirectangular)
      else if math.abs(ratio - 4.0 / 3.0) <= 4.0 / 3.0 * tolerance then Some(Projection.CubemapNet)
      else None

  /** Human hint for the aspect ratio of a picture, shown next to the viewer. */
  def hint(width: Int, height: Int, projection: Projection): String =
    val detected = detect(width, height)
    if detected.contains(projection) then s"${width}x$height matches ${projection.label}"
    else
      val want = projection match
        case Projection.Equirectangular => "2:1, e.g. 1024x512"
        case Projection.CubemapNet => "4:3, e.g. 1024x768"
      s"${width}x$height is not $want; the view will be distorted" + detected.fold("")(d => s" (it looks like ${d.label})")

  // ---------------------------------------------------------------------------------------------------- directions

  /** cos/sin of pitch and yaw. */
  private def trig(view: View): (Double, Double, Double, Double) =
    (math.cos(math.toRadians(view.pitch)), math.sin(math.toRadians(view.pitch)),
      math.cos(math.toRadians(view.yaw)), math.sin(math.toRadians(view.yaw)))

  /** World direction of the camera ray (cx, cy, 1): x to the right, y up, z forward at yaw 0. */
  private def worldDir(cx: Double, cy: Double, cp: Double, sp: Double, cyaw: Double, syaw: Double): (Double, Double, Double) =
    val y1 = cy * cp + sp
    val z1 = -cy * sp + cp
    val x2 = cx * cyaw + z1 * syaw
    val z2 = -cx * syaw + z1 * cyaw
    (x2, y1, z2)

  private final class Face(val fwd: Array[Double], val right: Array[Double], val up: Array[Double], val col: Int, val row: Int)
  /** Faces of the 4x3 cross: the top face sits above the front face, the bottom below it, left/front/right/back in the middle row. */
  private val Faces: Array[Face] = Array(
    new Face(Array(0, 0, 1), Array(1, 0, 0), Array(0, 1, 0), 1, 1),   // front  +z
    new Face(Array(1, 0, 0), Array(0, 0, -1), Array(0, 1, 0), 2, 1),  // right  +x
    new Face(Array(0, 0, -1), Array(-1, 0, 0), Array(0, 1, 0), 3, 1), // back   -z
    new Face(Array(-1, 0, 0), Array(0, 0, 1), Array(0, 1, 0), 0, 1),  // left   -x
    new Face(Array(0, 1, 0), Array(1, 0, 0), Array(0, 0, -1), 1, 0),  // top    +y
    new Face(Array(0, -1, 0), Array(1, 0, 0), Array(0, 0, 1), 1, 2))  // bottom -y

  // ------------------------------------------------------------------------------------------------- sampling

  /** Bilinear lookup in the window [x0, x0+w) x [y0, y0+h); x wraps when `wrap`, otherwise it clamps. Returns opaque ARGB. */
  private def bilinear(src: Raster, px: Double, py: Double, x0: Int, y0: Int, w: Int, h: Int, wrap: Boolean): Int =
    val fx = math.floor(px)
    val fy = math.floor(py)
    val tx = px - fx
    val ty = py - fy
    var ix0 = fx.toInt
    var ix1 = ix0 + 1
    if wrap then
      ix0 = ((ix0 % w) + w) % w
      ix1 = ((ix1 % w) + w) % w
    else
      ix0 = math.max(0, math.min(w - 1, ix0))
      ix1 = math.max(0, math.min(w - 1, ix1))
    val iy0 = math.max(0, math.min(h - 1, fy.toInt))
    val iy1 = math.max(0, math.min(h - 1, fy.toInt + 1))
    val p00 = src.pixel(x0 + ix0, y0 + iy0)
    val p10 = src.pixel(x0 + ix1, y0 + iy0)
    val p01 = src.pixel(x0 + ix0, y0 + iy1)
    val p11 = src.pixel(x0 + ix1, y0 + iy1)
    var out = 0xFF000000
    var shift = 0
    while shift <= 16 do
      val c00 = (p00 >>> shift) & 255
      val c10 = (p10 >>> shift) & 255
      val c01 = (p01 >>> shift) & 255
      val c11 = (p11 >>> shift) & 255
      val v = (c00 + (c10 - c00) * tx) * (1 - ty) + (c01 + (c11 - c01) * tx) * ty
      out |= math.max(0, math.min(255, (v + 0.5).toInt)) << shift
      shift += 8
    out

  private def sampleEquirect(src: Raster, x: Double, y: Double, z: Double): Int =
    val len = math.sqrt(x * x + y * y + z * z)
    val lon = math.atan2(x, z)
    val lat = math.asin(math.max(-1.0, math.min(1.0, y / len)))
    val u = (lon / (2 * math.Pi) + 0.5) * src.width - 0.5
    val v = (0.5 - lat / math.Pi) * src.height - 0.5
    bilinear(src, u, v, 0, 0, src.width, src.height, wrap = true)

  private def cubeFaceOf(x: Double, y: Double, z: Double): Int =
    val ax = math.abs(x)
    val ay = math.abs(y)
    val az = math.abs(z)
    if az >= ax && az >= ay then (if z >= 0 then 0 else 2)
    else if ax >= ay then (if x >= 0 then 1 else 3)
    else if y >= 0 then 4
    else 5

  private def sampleCube(src: Raster, x: Double, y: Double, z: Double): Int =
    val f = Faces(cubeFaceOf(x, y, z))
    val s = src.width / 4
    val d = x * f.fwd(0) + y * f.fwd(1) + z * f.fwd(2)
    val a = (x * f.right(0) + y * f.right(1) + z * f.right(2)) / d
    val b = (x * f.up(0) + y * f.up(1) + z * f.up(2)) / d
    bilinear(src, (a + 1) / 2 * s - 0.5, (1 - b) / 2 * s - 0.5, f.col * s, f.row * s, s, s, wrap = false)

  private def requireNet(src: Raster): Unit =
    require(src.width % 4 == 0 && src.height % 3 == 0 && src.width / 4 == src.height / 3,
      s"A cubemap net needs a 4:3 picture made of square faces, got ${src.width}x${src.height}")

  private def sampler(src: Raster, projection: Projection): (Double, Double, Double) => Int = projection match
    case Projection.Equirectangular => (x, y, z) => sampleEquirect(src, x, y, z)
    case Projection.CubemapNet =>
      requireNet(src)
      (x, y, z) => sampleCube(src, x, y, z)

  // ----------------------------------------------------------------------------------------------- operations

  /** Renders the perspective view the viewer shows for `view`. */
  def render(src: Raster, projection: Projection, view: View, outW: Int, outH: Int): Raster =
    require(outW > 0 && outH > 0, "empty output")
    val v = view.normalized
    val (cp, sp, cyaw, syaw) = trig(v)
    val tanH = math.tan(math.toRadians(v.fov) / 2)
    val sample = sampler(src, projection)
    val out = new Array[Int](outW * outH)
    var py = 0
    while py < outH do
      val cy = (1 - 2 * (py + 0.5) / outH) * tanH * outH / outW
      var px = 0
      while px < outW do
        val cx = (2 * (px + 0.5) / outW - 1) * tanH
        val (x, y, z) = worldDir(cx, cy, cp, sp, cyaw, syaw)
        out(py * outW + px) = sample(x, y, z)
        px += 1
      py += 1
    new Raster(outW, outH, out)

  /** Unit world direction under a point of the rendered view (x right, y up, z forward at yaw 0). */
  def viewDirection(view: View, outW: Int, outH: Int, px: Double, py: Double): (Double, Double, Double) =
    val v = view.normalized
    val (cp, sp, cyaw, syaw) = trig(v)
    val tanH = math.tan(math.toRadians(v.fov) / 2)
    val cy = (1 - 2 * py / outH) * tanH * outH / outW
    val cx = (2 * px / outW - 1) * tanH
    val (x, y, z) = worldDir(cx, cy, cp, sp, cyaw, syaw)
    val len = math.sqrt(x * x + y * y + z * z)
    (x / len, y / len, z / len)

  /** Unit direction of the equirectangular pixel position (px, py) of a picture `width` x `height`. */
  def equirectDirection(width: Int, height: Int, px: Double, py: Double): (Double, Double, Double) =
    val lon = (px / width - 0.5) * 2 * math.Pi
    val lat = (0.5 - py / height) * math.Pi
    (math.cos(lat) * math.sin(lon), math.sin(lat), math.cos(lat) * math.cos(lon))

  /** Equirectangular picture to a 4x3 cube net with square faces of `faceSize` pixels; the unused corner cells are opaque black. */
  def equirectToCubeNet(src: Raster, faceSize: Int): Raster =
    require(faceSize >= 1, "face size must be positive")
    val w = faceSize * 4
    val h = faceSize * 3
    val out = new Array[Int](w * h)
    java.util.Arrays.fill(out, 0xFF000000)
    for f <- Faces do
      val x0 = f.col * faceSize
      val y0 = f.row * faceSize
      var j = 0
      while j < faceSize do
        val b = 1 - 2 * (j + 0.5) / faceSize
        var i = 0
        while i < faceSize do
          val a = 2 * (i + 0.5) / faceSize - 1
          val x = f.fwd(0) + a * f.right(0) + b * f.up(0)
          val y = f.fwd(1) + a * f.right(1) + b * f.up(1)
          val z = f.fwd(2) + a * f.right(2) + b * f.up(2)
          out((y0 + j) * w + x0 + i) = sampleEquirect(src, x, y, z)
          i += 1
        j += 1
    new Raster(w, h, out)

  /** A 4x3 cube net back to an equirectangular picture `outW` wide (and half as high). */
  def cubeNetToEquirect(src: Raster, outW: Int): Raster =
    require(outW >= 2, "output too small")
    val sample = sampler(src, Projection.CubemapNet)
    val outH = outW / 2
    val out = new Array[Int](outW * outH)
    var j = 0
    while j < outH do
      val lat = (0.5 - (j + 0.5) / outH) * math.Pi
      var i = 0
      while i < outW do
        val lon = ((i + 0.5) / outW - 0.5) * 2 * math.Pi
        out(j * outW + i) = sample(math.cos(lat) * math.sin(lon), math.sin(lat), math.cos(lat) * math.cos(lon))
        i += 1
      j += 1
    new Raster(outW, outH, out)

  /** Face of the net a direction falls on (0 front, 1 right, 2 back, 3 left, 4 top, 5 bottom). */
  def faceOf(x: Double, y: Double, z: Double): Int = cubeFaceOf(x, y, z)
