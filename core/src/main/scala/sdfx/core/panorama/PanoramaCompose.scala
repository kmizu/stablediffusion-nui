package sdfx.core.panorama

/** Inverse perspective projection onto an equirectangular panorama. */
object PanoramaCompose:
  val MaxPixels: Long = 16L * 1024 * 1024
  /** Edge feather in patch pixels. Input and output are independent; the panorama is not modified. */
  def applyView(base: Raster, patch: Raster, view: View, featherPixels: Int, cancelled: () => Boolean = () => false): Raster =
    require(base.width >= 2 && base.height >= 2 && base.width <= PanoramaFiles.MaxWidth && base.width.toLong * base.height <= MaxPixels,
      "Invalid panorama dimensions")
    require(patch.width >= 2 && patch.height >= 2 && patch.width <= PanoramaFiles.MaxWidth &&
      patch.width.toLong * patch.height <= MaxPixels, "Invalid perspective dimensions")
    require(view.yaw.isFinite && view.pitch.isFinite && view.fov.isFinite &&
      view.pitch >= -90 && view.pitch <= 90 && view.fov >= View.MinFov && view.fov <= View.MaxFov,
      "Invalid camera view")
    require(featherPixels >= 0 && featherPixels <= math.min(patch.width, patch.height) / 2,
      "Invalid edge feather")
    val v = view.normalized
    val cp = math.cos(math.toRadians(v.pitch)); val sp = math.sin(math.toRadians(v.pitch))
    val cy = math.cos(math.toRadians(v.yaw)); val sy = math.sin(math.toRadians(v.yaw))
    val tanH = math.tan(math.toRadians(v.fov) / 2)
    val tanV = tanH * patch.height / patch.width
    val out = base.argb.clone()
    val cosLon = Array.tabulate(base.width)(x => math.cos(((x + 0.5) / base.width - 0.5) * 2 * math.Pi))
    val sinLon = Array.tabulate(base.width)(x => math.sin(((x + 0.5) / base.width - 0.5) * 2 * math.Pi))
    var y = 0
    while y < base.height do
      if cancelled() then throw new java.util.concurrent.CancellationException("Panorama composition cancelled")
      val latitude = (0.5 - (y + 0.5) / base.height) * math.Pi
      val cosLat = math.cos(latitude); val wy = math.sin(latitude)
      var x = 0
      while x < base.width do
        val wx = cosLat * sinLon(x); val wz = cosLat * cosLon(x)
        val cameraX = wx * cy - wz * sy
        val yawZ = wx * sy + wz * cy
        val cameraY = wy * cp - yawZ * sp
        val cameraZ = wy * sp + yawZ * cp
        if cameraZ > 0 then
          val u = (cameraX / cameraZ / tanH + 1) * patch.width / 2 - 0.5
          val w = (1 - cameraY / cameraZ / tanV) * patch.height / 2 - 0.5
          if u >= -0.5 && u <= patch.width - 0.5 && w >= -0.5 && w <= patch.height - 0.5 then
            val edge = math.min(math.min(u + 0.5, patch.width - 0.5 - u), math.min(w + 0.5, patch.height - 0.5 - w))
            val fade = if featherPixels == 0 then 1.0 else math.min(1.0, math.max(0.0, edge / featherPixels))
            out(y * base.width + x) = blend(base.pixel(x, y), patch, u, w, fade)
        x += 1
      y += 1
    new Raster(base.width, base.height, out)

  private def blend(background: Int, patch: Raster, x: Double, y: Double, fade: Double): Int =
    val x0 = math.floor(x).toInt; val y0 = math.floor(y).toInt
    val fx = x - x0; val fy = y - y0
    def clamp(v: Int, n: Int) = math.max(0, math.min(n - 1, v))
    val p00 = patch.pixel(clamp(x0, patch.width), clamp(y0, patch.height))
    val p10 = patch.pixel(clamp(x0 + 1, patch.width), clamp(y0, patch.height))
    val p01 = patch.pixel(clamp(x0, patch.width), clamp(y0 + 1, patch.height))
    val p11 = patch.pixel(clamp(x0 + 1, patch.width), clamp(y0 + 1, patch.height))
    val a00 = ((p00 >>> 24) & 255) / 255.0 * (1 - fx) * (1 - fy)
    val a10 = ((p10 >>> 24) & 255) / 255.0 * fx * (1 - fy)
    val a01 = ((p01 >>> 24) & 255) / 255.0 * (1 - fx) * fy
    val a11 = ((p11 >>> 24) & 255) / 255.0 * fx * fy
    val srcA = (a00 + a10 + a01 + a11) * fade
    if srcA <= 0 then background
    else
      val dstA = ((background >>> 24) & 255) / 255.0
      val outA = srcA + dstA * (1 - srcA)
      def channel(shift: Int): Int =
        val srcPremult = (((p00 >>> shift) & 255) * a00 + ((p10 >>> shift) & 255) * a10 +
          ((p01 >>> shift) & 255) * a01 + ((p11 >>> shift) & 255) * a11) * fade
        val dstPremult = ((background >>> shift) & 255) * dstA * (1 - srcA)
        math.max(0, math.min(255, math.round((srcPremult + dstPremult) / outA).toInt))
      (math.round(outA * 255).toInt << 24) | (channel(16) << 16) | (channel(8) << 8) | channel(0)
