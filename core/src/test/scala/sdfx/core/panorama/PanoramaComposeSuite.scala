package sdfx.core.panorama

class PanoramaComposeSuite extends munit.FunSuite:
  private def solid(w: Int, h: Int, value: Int): Raster = new Raster(w, h, Array.fill(w * h)(value))
  private val black = 0xff000000
  private val red = 0xffff0000
  private def at(r: Raster, lon: Int, lat: Int = 0): Int = r.pixel(lon + 180, 90 - lat)
  private def redOf(p: Int): Int = (p >>> 16) & 255

  test("projects each cardinal direction and preserves the opposite hemisphere") {
    val base = solid(360, 180, black)
    val patch = solid(128, 128, red)
    for (yaw, lon) <- Seq((0, 0), (90, 90), (-90, -90), (180, -180)) do
      val out = PanoramaCompose.applyView(base, patch, View(yaw, 0, 90), 0)
      assert(redOf(at(out, lon)) > 250, s"centre $yaw")
      assertEquals(at(out, ((lon + 180 + 180) % 360) - 180), black)
    assertEquals(base.argb.toSeq, Seq.fill(360 * 180)(black))
  }

  test("the back view crosses the panorama seam, and upward view reaches the pole") {
    val base = solid(360, 180, black)
    val patch = solid(128, 128, red)
    val back = PanoramaCompose.applyView(base, patch, View(180, 0, 90), 0)
    assert(redOf(at(back, -179)) > 250 && redOf(at(back, 179)) > 250)
    val up = PanoramaCompose.applyView(base, patch, View(0, 90, 90), 0)
    assert(redOf(up.pixel(180, 0)) > 250)
    assertEquals(at(up, 0, -40), black)
  }

  test("alpha and edge feather blend rather than replace unrelated pixels") {
    val base = solid(360, 180, black)
    val half = solid(128, 128, 0x80ff0000)
    val alpha = PanoramaCompose.applyView(base, half, View(0, 0, 90), 0)
    assert(math.abs(redOf(at(alpha, 0)) - 128) <= 1)
    val hard = PanoramaCompose.applyView(base, solid(128, 128, red), View(0, 0, 90), 0)
    val soft = PanoramaCompose.applyView(base, solid(128, 128, red), View(0, 0, 90), 24)
    assert(redOf(at(hard, 44)) > 250)
    assert(redOf(at(soft, 44)) > 0 && redOf(at(soft, 44)) < 100)
    assertEquals(at(soft, 90), black)
  }

  test("transparent patch colors do not bleed and destination alpha is retained") {
    val base = solid(360, 180, 0x80000000)
    val patch = new Raster(2, 2, Array(0x00ff0000, 0xff0000ff, 0x00ff0000, 0xff0000ff))
    val result = PanoramaCompose.applyView(base, patch, View(0, 0, 90), 0)
    val pixel = at(result, 0)
    assertEquals((pixel >>> 16) & 255, 0)
    assert((pixel & 255) > 150 && (pixel & 255) < 190)
    assert(((pixel >>> 24) & 255) >= 190 && ((pixel >>> 24) & 255) <= 194)
    assertEquals(at(result, 90), 0x80000000)
  }

  test("rendered direction colors survive projection back into the panorama") {
    val w = 720; val h = 360
    def color(d: (Double, Double, Double)): Int =
      def c(v: Double) = math.round((v + 1) * 127.5).toInt.max(0).min(255)
      0xff000000 | (c(d._1) << 16) | (c(d._2) << 8) | c(d._3)
    val scene = new Raster(w, h, Array.tabulate(w * h)(i =>
      color(Panorama.equirectDirection(w, h, i % w + 0.5, i / w + 0.5))))
    for view <- Seq(View(37, 25, 80), View(179, -40, 100), View(-70, 85, 70)) do
      val patch = Panorama.render(scene, Projection.Equirectangular, view, 180, 120)
      val pasted = PanoramaCompose.applyView(solid(w, h, black), patch, view, 0)
      val rendered = Panorama.render(pasted, Projection.Equirectangular, view, 180, 120)
      var total = 0L; var count = 0
      for y <- 15 until 105 by 3; x <- 15 until 165 by 3; shift <- Seq(0, 8, 16) do
        total += math.abs(((patch.pixel(x, y) >>> shift) & 255) - ((rendered.pixel(x, y) >>> shift) & 255))
        count += 1
      assert(total.toDouble / count < 3.0, s"$view mean diff ${total.toDouble / count}")
  }

  test("invalid view and feather values are rejected") {
    val base = solid(360, 180, black)
    val patch = solid(128, 128, red)
    intercept[IllegalArgumentException](PanoramaCompose.applyView(base, patch, View(Double.NaN, 0, 90), 0))
    intercept[IllegalArgumentException](PanoramaCompose.applyView(base, patch, View(0, 0, 90), -1))
    intercept[IllegalArgumentException](PanoramaCompose.applyView(solid(1, 1, black), patch, View(), 0))
    intercept[IllegalArgumentException](PanoramaCompose.applyView(solid(PanoramaFiles.MaxWidth + 1, 2, black), patch, View(), 0))
    intercept[IllegalArgumentException](PanoramaCompose.applyView(solid(4097, 4096, black), patch, View(), 0))
    var calls = 0
    intercept[java.util.concurrent.CancellationException] {
      PanoramaCompose.applyView(base, patch, View(), 0, () => { calls += 1; calls >= 3 })
    }
    assertEquals(calls, 3)
  }
