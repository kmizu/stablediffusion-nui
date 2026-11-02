package sdfx.core

import sdfx.core.panorama.*

class PanoramaSuite extends munit.FunSuite:
  /** Colour = world direction mapped to 0..255, so every sampled pixel can be checked against the ray that produced it. */
  private def colour(d: (Double, Double, Double)): Int =
    def c(v: Double) = math.max(0, math.min(255, math.round((v + 1) / 2 * 255).toInt))
    0xFF000000 | (c(d._1) << 16) | (c(d._2) << 8) | c(d._3)
  private def directionEquirect(w: Int): Raster =
    val h = w / 2
    new Raster(w, h, Array.tabulate(w * h)(i => colour(Panorama.equirectDirection(w, h, i % w + 0.5, i / w + 0.5))))
  private def channel(p: Int, shift: Int) = (p >>> shift) & 255
  private def maxDiff(a: Int, b: Int): Int = Seq(0, 8, 16).map(s => math.abs(channel(a, s) - channel(b, s))).max

  private val scene = directionEquirect(1024)

  test("every pixel of an equirectangular view shows the colour of the ray that produced it"):
    for view <- Seq(View(0, 0, 90), View(90, 0, 70), View(-135, 25, 100), View(180, -40, 60), View(33, 85, 110)) do
      val out = Panorama.render(scene, Projection.Equirectangular, view, 200, 120)
      var worst = 0
      for y <- Range(0, 120, 7); x <- Range(0, 200, 7) do
        val want = colour(Panorama.viewDirection(view, 200, 120, x + 0.5, y + 0.5))
        worst = math.max(worst, maxDiff(out.pixel(x, y), want))
      assert(worst <= 4, s"$view differs by $worst")

  test("the middle of the view looks along yaw and pitch: right is positive yaw, up is positive pitch"):
    val front = colour((0, 0, 1))
    assert(maxDiff(Panorama.render(scene, Projection.Equirectangular, View(0, 0, 90), 3, 3).pixel(1, 1), front) <= 3)
    // yaw 90 looks at +x (the image column at three quarters of the width), pitch 90 at +y (top of the picture)
    assert(maxDiff(Panorama.render(scene, Projection.Equirectangular, View(90, 0, 90), 3, 3).pixel(1, 1), colour((1, 0, 0))) <= 3)
    assert(maxDiff(Panorama.render(scene, Projection.Equirectangular, View(0, 90, 90), 3, 3).pixel(1, 1), colour((0, 1, 0))) <= 6)
    val (x, y, _) = Panorama.viewDirection(View(0, 0, 90), 100, 100, 99.9, 0.1) // top right corner of the view
    assert(x > 0 && y > 0)

  test("the seam at yaw 180 is continuous and the view wraps around"):
    val out = Panorama.render(scene, Projection.Equirectangular, View(180, 0, 90), 400, 100)
    var worst = 0
    for x <- 1 until 400 do worst = math.max(worst, maxDiff(out.pixel(x, 50), out.pixel(x - 1, 50)))
    assert(worst <= 4, s"seam step $worst")
    assertEquals(Panorama.render(scene, Projection.Equirectangular, View(540, 0, 90), 40, 30).argb.toSeq,
      Panorama.render(scene, Projection.Equirectangular, View(-180, 0, 90), 40, 30).argb.toSeq)

  test("a cube net has the documented 4x3 cross layout"):
    val net = Panorama.equirectToCubeNet(scene, 128)
    assertEquals((net.width, net.height), (512, 384))
    def centre(col: Int, row: Int) = net.pixel(col * 128 + 64, row * 128 + 64)
    val faces = Map((1, 1) -> (0.0, 0.0, 1.0), (2, 1) -> (1.0, 0.0, 0.0), (3, 1) -> (0.0, 0.0, -1.0), (0, 1) -> (-1.0, 0.0, 0.0),
      (1, 0) -> (0.0, 1.0, 0.0), (1, 2) -> (0.0, -1.0, 0.0))
    for ((col, row), d) <- faces do assert(maxDiff(centre(col, row), colour(d)) <= 4, s"face $col,$row")
    for (col, row) <- Seq((0, 0), (2, 0), (3, 0), (0, 2), (2, 2), (3, 2)) do assertEquals(centre(col, row), 0xFF000000) // blank corners

  test("neighbouring faces of the net are continuous across their shared edges"):
    val s = 128
    val net = Panorama.equirectToCubeNet(scene, s)
    def px(col: Int, row: Int, x: Int, y: Int) = net.pixel(col * s + x, row * s + y)
    var worst = 0
    for i <- 0 until s do
      worst = math.max(worst, maxDiff(px(1, 0, i, s - 1), px(1, 1, i, 0)))      // top / front
      worst = math.max(worst, maxDiff(px(1, 2, i, 0), px(1, 1, i, s - 1)))      // bottom / front
      worst = math.max(worst, maxDiff(px(0, 1, s - 1, i), px(1, 1, 0, i)))      // left / front
      worst = math.max(worst, maxDiff(px(1, 1, s - 1, i), px(2, 1, 0, i)))      // front / right
      worst = math.max(worst, maxDiff(px(2, 1, s - 1, i), px(3, 1, 0, i)))      // right / back
    assert(worst <= 8, s"edge step $worst")

  test("views of a cube net and of the matching equirectangular picture agree"):
    val net = Panorama.equirectToCubeNet(scene, 256)
    for view <- Seq(View(0, 0, 90), View(45, 35, 80), View(-120, -50, 100), View(180, 10, 60)) do
      val out = Panorama.render(net, Projection.CubemapNet, view, 160, 100)
      var worst = 0
      for y <- Range(0, 100, 5); x <- Range(0, 160, 5) do
        worst = math.max(worst, maxDiff(out.pixel(x, y), colour(Panorama.viewDirection(view, 160, 100, x + 0.5, y + 0.5))))
      assert(worst <= 6, s"$view differs by $worst")

  test("equirectangular to cubemap and back reproduces the picture"):
    val back = Panorama.cubeNetToEquirect(Panorama.equirectToCubeNet(scene, 256), 512)
    assertEquals((back.width, back.height), (512, 256))
    val reference = directionEquirect(512)
    var worst = 0
    var sum = 0L
    for y <- 8 until 248; x <- 0 until 512 do
      val d = maxDiff(back.pixel(x, y), reference.pixel(x, y))
      worst = math.max(worst, d)
      sum += d
    assert(worst <= 8, s"worst $worst")
    assert(sum.toDouble / (240 * 512) < 1.0)

  test("a real photograph survives the round trip with its low frequencies"):
    val img = new java.awt.image.BufferedImage(512, 256, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for y <- 0 until 256; x <- 0 until 512 do img.setRGB(x, y, ((x * 255 / 511) << 16) | ((y * 255 / 255) << 8) | (if (x / 32 + y / 32) % 2 == 0 then 200 else 60))
    val src = Raster.fromImage(img)
    val back = Panorama.cubeNetToEquirect(Panorama.equirectToCubeNet(src, 256), 512)
    var diff = 0L
    for y <- 16 until 240; x <- 0 until 512 do diff += math.abs(channel(back.pixel(x, y), 16) - channel(src.pixel(x, y), 16))
    assert(diff.toDouble / (224 * 512) < 14.0, s"mean red error ${diff.toDouble / (224 * 512)}")

  test("aspect ratio detection and hints"):
    assertEquals(Panorama.detect(1024, 512), Some(Projection.Equirectangular))
    assertEquals(Panorama.detect(1024, 768), Some(Projection.CubemapNet))
    assertEquals(Panorama.detect(768, 512), None)
    assertEquals(Panorama.detect(0, 5), None)
    assert(Panorama.hint(1024, 512, Projection.Equirectangular).contains("matches"))
    assert(Panorama.hint(512, 512, Projection.Equirectangular).contains("2:1"))
    assert(Panorama.hint(1024, 512, Projection.CubemapNet).contains("looks like Equirectangular"))

  test("view clamps pitch and fov and wraps yaw"):
    assertEquals(View(190, 100, 500).normalized, View(-170, 90, View.MaxFov))
    assertEquals(View(0, -100, 1).normalized, View(0, -90, View.MinFov))
    assertEquals(View(170, 0, 90).turned(20, 0).yaw, -170.0)
    assertEquals(View(0, 0, 90).zoomed(2).fov, View.MaxFov)

  test("a cube net must be made of square faces"):
    val bad = new Raster(8, 8, new Array[Int](64))
    intercept[IllegalArgumentException](Panorama.render(bad, Projection.CubemapNet, View(), 4, 4))
    intercept[IllegalArgumentException](Panorama.cubeNetToEquirect(bad, 8))

  test("folder navigation is natural-ordered, wraps and ignores other files"):
    val dir = java.nio.file.Files.createTempDirectory("pano-files-")
    val names = Seq("img-10.png", "img-2.png", "img-1.png", "notes.txt", "clip.mp4", "b.JPG")
    names.foreach(n => java.nio.file.Files.write(dir.resolve(n), Array[Byte](1)))
    assertEquals(PanoramaFiles.siblings(dir.resolve("img-1.png")).map(_.getFileName.toString), Vector("b.JPG", "img-1.png", "img-2.png", "img-10.png"))
    assertEquals(PanoramaFiles.neighbour(dir.resolve("img-2.png"), 1).map(_.getFileName.toString), Some("img-10.png"))
    assertEquals(PanoramaFiles.neighbour(dir.resolve("img-10.png"), 1).map(_.getFileName.toString), Some("b.JPG"))
    assertEquals(PanoramaFiles.neighbour(dir.resolve("b.JPG"), -1).map(_.getFileName.toString), Some("img-10.png"))
    val lonely = java.nio.file.Files.createTempDirectory("pano-one-")
    java.nio.file.Files.write(lonely.resolve("a.png"), Array[Byte](1))
    assertEquals(PanoramaFiles.neighbour(lonely.resolve("a.png"), 1), None)

  test("converted pictures get a free name, keep the generation parameters and survive a PNG round trip"):
    val dir = java.nio.file.Files.createTempDirectory("pano-save-")
    val src = dir.resolve("a.png")
    sdfx.core.png.PngMetadata.write(scene.toBufferedImage, src, Seq(sdfx.core.png.PngMetadata.ParametersKey -> "a pano\nSteps: 5, Seed: 7"))
    val first = PanoramaFiles.uniqueTarget(dir, "a.png", "cubemap")
    assertEquals(first.getFileName.toString, "a-cubemap.png")
    PanoramaFiles.savePng(Panorama.equirectToCubeNet(scene, 64), first, Some(src))
    assertEquals(PanoramaFiles.uniqueTarget(dir, "a.png", "cubemap").getFileName.toString, "a-cubemap-2.png")
    assertEquals(sdfx.core.png.PngMetadata.readParameters(first), Some("a pano\nSteps: 5, Seed: 7"))
    val back = Raster.fromImage(javax.imageio.ImageIO.read(first.toFile))
    assertEquals((back.width, back.height), (256, 192))

  test("oversized pictures are refused"):
    assert(PanoramaFiles.checkSize(1024, 512).isRight)
    assert(PanoramaFiles.checkSize(20000, 100).isLeft)
    assert(PanoramaFiles.checkSize(1, 1).isLeft)

