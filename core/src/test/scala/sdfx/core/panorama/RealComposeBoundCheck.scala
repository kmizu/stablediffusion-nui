package sdfx.core.panorama

/** Manual finite resource probe, not a performance guarantee for other machines. */
object RealComposeBoundCheck:
  def main(args: Array[String]): Unit =
    val w = 5792; val h = 2896
    require(w.toLong * h <= PanoramaCompose.MaxPixels)
    val base = new Raster(w, h, Array.fill(w * h)(0xff102030))
    val patch = new Raster(1024, 1024, Array.fill(1024 * 1024)(0xffe01020))
    val start = System.nanoTime()
    val out = PanoramaCompose.applyView(base, patch, View(30, 15, 90), 32)
    val millis = (System.nanoTime() - start) / 1000000L
    require(out.width == w && out.height == h && out.argb.exists(_ != 0xff102030))
    println(s"PANORAMA_COMPOSE_BOUND_OK dimensions=${w}x$h elapsed_ms=$millis")
