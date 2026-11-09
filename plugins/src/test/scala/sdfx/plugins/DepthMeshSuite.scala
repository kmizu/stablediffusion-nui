package sdfx.plugins

import java.awt.image.BufferedImage
import java.nio.file.Files

class DepthMeshSuite extends munit.FunSuite:
  private def grey(w: Int, h: Int)(f: (Int, Int) => Int) =
    val i = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until h; x <- 0 until w do i.setRGB(x, y, f(x, y) * 0x010101)
    i

  test("a flat depth map gives a full grid: vertices, uvs and two triangles per quad") {
    val (obj, v, f) = DepthMesh.obj(9, 5, grey(9, 5)((_, _) => 128), 2, 0.5, 0.1, "m.mtl")
    assertEquals((v, f), (5 * 3, 2 * 4 * 2))
    assertEquals(obj.linesIterator.count(_.startsWith("v ")), v)
    assertEquals(obj.linesIterator.count(_.startsWith("vt ")), v)
    assertEquals(obj.linesIterator.count(_.startsWith("f ")), f)
    assert(obj.startsWith("mtllib m.mtl"))
  }

  test("faces across a depth jump are dropped and indices stay in range") {
    val d = grey(8, 8)((x, _) => if x < 4 then 0 else 255)
    val (obj, v, f) = DepthMesh.obj(8, 8, d, 1, 1.0, 0.2, "m.mtl")
    assert(f < 2 * 7 * 7 && f > 0)
    val idx = obj.linesIterator.filter(_.startsWith("f ")).flatMap(_.split(" ").tail).map(_.split("/")(0).toInt).toSeq
    assert(idx.forall(i => i >= 1 && i <= v))
  }

  test("nearest points get the largest z and coordinates use '.' decimals") {
    val (obj, _, _) = DepthMesh.obj(4, 4, grey(4, 4)((x, _) => if x == 0 then 255 else 0), 1, 2.0, 1.0, "m.mtl")
    val zs = obj.linesIterator.filter(_.startsWith("v ")).map(_.split(" ")(3).toDouble).toSeq
    assertEquals(zs.max, 2.0)
    assert(!obj.linesIterator.filter(_.startsWith("v ")).exists(_.contains(",")))
  }

  test("freeStem never reuses an existing file set") {
    val d = Files.createTempDirectory("mesh")
    assertEquals(DepthMesh.freeStem(d, "a"), "a")
    Files.writeString(d.resolve("a.obj"), "x")
    assertEquals(DepthMesh.freeStem(d, "a"), "a-1")
  }
