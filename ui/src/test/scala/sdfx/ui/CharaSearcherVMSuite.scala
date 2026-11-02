package sdfx.ui

import sdfx.core.charasearch.*
import sdfx.ui.vm.*

import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}

class CharaSearcherVMSuite extends munit.FunSuite:
  private def make(root: Path, name: String, items: Seq[(String, Array[Float], String)]): Unit =
    val dir = root.resolve(name)
    Files.createDirectories(dir)
    items.foreach { (file, _, tags) =>
      Files.write(dir.resolve(file), Array[Byte](9))
      Files.writeString(dir.resolve(file.stripSuffix(".png") + ".txt"), tags)
    }
    val dim = items.head._2.length
    val header0 = s"{'descr': '<f4', 'fortran_order': False, 'shape': (${items.size}, $dim), }"
    var header = header0
    while (10 + header.length + 1) % 64 != 0 do header += " "
    header += "\n"
    val data = java.nio.ByteBuffer.allocate(items.size * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
    items.foreach(_._2.foreach(data.putFloat))
    val zip = new ZipOutputStream(Files.newOutputStream(dir.resolve("embedding.npz")))
    zip.putNextEntry(new ZipEntry("embedding.npy"))
    zip.write(Array[Byte](0x93.toByte, 'N', 'U', 'M', 'P', 'Y', 1, 0, header.length.toByte, (header.length >> 8).toByte))
    zip.write(header.getBytes(StandardCharsets.ISO_8859_1))
    zip.write(data.array())
    zip.closeEntry(); zip.close()
    Files.writeString(dir.resolve("embedding.json"),
      ujson.write(ujson.Obj("files" -> ujson.Arr.from(items.map(i => ujson.Obj("path" -> i._1, "width" -> 8, "height" -> 8))))))

  private def fixture(): CharaSearcherVM =
    val root = Files.createTempDirectory("chara-vm-")
    make(root, "alpha", Seq(("a.png", Array(1f, 0f), "1girl, solo"), ("b.png", Array(0.8f, 0.6f), "1girl"), ("c.png", Array(0f, 1f), "1boy")))
    make(root, "beta", Seq(("d.png", Array(0.6f, 0.8f), "1girl")))
    new CharaSearcherVM(root)

  private def open(vm: CharaSearcherVM, names: String*): Unit =
    vm.install(names.map(vm.read))
    vm.select(names)

  test("datasets are listed and a selection shows every image of the chosen sets") {
    val vm = fixture()
    assertEquals(vm.datasetNames, Vector("alpha", "beta"))
    open(vm, "alpha")
    assertEquals(vm.results.map(_.entry.path), Vector("a.png", "b.png", "c.png"))
    open(vm, "alpha", "beta")
    assertEquals(vm.total, 4)
    assertEquals(vm.results.size, 4)
    vm.select(Nil)
    assertEquals(vm.results.size, 0)
  }

  test("search image, threshold and tags narrow the result") {
    val vm = fixture()
    open(vm, "alpha", "beta")
    vm.setImage(Some(Path.of("q.png")), Some(Array(1f, 0f)))
    vm.threshold = 0.7
    assertEquals(vm.search().map(_.entry.path), Vector("a.png", "b.png"))
    vm.tags = "solo"
    assertEquals(vm.search().map(_.entry.path), Vector("a.png"))
    vm.tags = ""; vm.negativeTags = "solo"
    assertEquals(vm.search().map(_.entry.path), Vector("b.png"))
    vm.setImage(None, None)
    assertEquals(vm.results.map(_.entry.path), Vector("b.png", "c.png", "d.png"))
  }

  test("clicking excludes an image or takes its similarity as the new threshold") {
    val vm = fixture()
    open(vm, "alpha")
    vm.setImage(Some(Path.of("q.png")), Some(Array(1f, 0f)))
    vm.threshold = 0.0
    vm.search()
    val b = vm.results.find(_.entry.path == "b.png").get
    vm.click = CharaClick.Exclude
    assert(vm.clicked(b).contains("Excluded"))
    assertEquals(vm.results.map(_.entry.path), Vector("a.png", "c.png"))
    assertEquals(vm.excludedCount, 1)
    vm.resetExcluded()
    assertEquals(vm.results.size, 3)
    vm.click = CharaClick.Threshold
    vm.clicked(b)
    assertEqualsDouble(vm.threshold, 0.8, 1e-9)
    assertEquals(vm.results.map(_.entry.path), Vector("a.png", "b.png"))
    vm.setImage(None, None)
    assert(vm.clicked(vm.results.head).contains("search image"))
  }

  test("names must be safe folder names") {
    val vm = fixture()
    assertEquals(vm.validName(" my set "), Some("my set"))
    for bad <- Seq("", "  ", "../x", "a/b", ".hidden", "bad.", "x" * 65, "a:b") do assertEquals(vm.validName(bad), None, bad)
  }

  test("export writes the displayed images and refuses an empty result") {
    val vm = fixture()
    open(vm, "alpha")
    val out = Files.createTempDirectory("chara-vm-out").resolve("o")
    vm.tags = "solo"
    vm.search()
    val ok = vm.exportHits(out, "mine", "solo")
    assertEquals(ok.map(_.written.size), Right(1))
    assertEquals(Files.readString(out.resolve("00000_a.txt")), "mine, 1girl")
    vm.tags = "nothing"
    vm.search()
    assertEquals(vm.exportHits(out, "", ""), Left("There is nothing to export"))
  }
