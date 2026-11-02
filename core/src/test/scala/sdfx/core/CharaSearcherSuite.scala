package sdfx.core

import sdfx.core.charasearch.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.zip.{ZipEntry, ZipOutputStream}

class CharaSearcherSuite extends munit.FunSuite:
  private def tmp(): Path = Files.createTempDirectory("chara-test-")

  private def npy(rows: Array[Array[Float]], dims: Option[(Int, Int)] = None, version: Int = 1, descr: String = "<f4"): Array[Byte] =
    val (r, c) = dims.getOrElse((rows.length, rows.headOption.map(_.length).getOrElse(0)))
    var header = s"{'descr': '$descr', 'fortran_order': False, 'shape': ($r, $c), }"
    val prefix = if version == 1 then 10 else 12
    while (prefix + header.length + 1) % 64 != 0 do header += " "
    header += "\n"
    val out = new ByteArrayOutputStream()
    out.write(Array[Byte](0x93.toByte, 'N', 'U', 'M', 'P', 'Y', version.toByte, 0))
    val len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    if version == 1 then len.putShort(header.length.toShort) else len.putInt(header.length)
    out.write(len.array(), 0, if version == 1 then 2 else 4)
    out.write(header.getBytes(StandardCharsets.ISO_8859_1))
    val data = ByteBuffer.allocate(rows.map(_.length).sum * 4).order(ByteOrder.LITTLE_ENDIAN)
    rows.foreach(_.foreach(data.putFloat))
    out.write(data.array())
    out.toByteArray

  private def npz(file: Path, rows: Array[Array[Float]]): Path =
    val zip = new ZipOutputStream(Files.newOutputStream(file))
    zip.putNextEntry(new ZipEntry("embedding.npy"))
    zip.write(npy(rows))
    zip.closeEntry()
    zip.close()
    file

  /** A dataset with one cut-out per row; vectors are given, tags per file. */
  private def dataset(dir: Path, items: Seq[(String, Array[Float], String)]): Path =
    Files.createDirectories(dir)
    items.foreach { (name, _, tags) =>
      Files.write(dir.resolve(name), Array[Byte](1, 2, 3))
      Files.writeString(dir.resolve(name.stripSuffix(".png") + ".txt"), tags)
    }
    npz(dir.resolve("embedding.npz"), items.map(_._2).toArray)
    val files = ujson.Arr.from(items.map((n, _, _) => ujson.Obj("path" -> n, "width" -> 10, "height" -> 20)))
    Files.writeString(dir.resolve("embedding.json"), ujson.write(ujson.Obj("files" -> files)))
    dir

  test("Npy reads what numpy wrote (fixture) and both header versions") {
    val fixture = Path.of(getClass.getResource("/chara/embedding.npz").toURI)
    val rows = Npy.readNpz(fixture)
    assertEquals(rows.map(_.toList).toList, List(List(3f, 4f, 0f, 0f), List(0f, 0f, 1f, 0f), List(1f, 1f, 1f, 1f)))
    val synthetic = Array(Array(1f, 2f), Array(3f, 4f))
    assertEquals(Npy.parse(npy(synthetic, version = 2)).map(_.toList).toList, List(List(1f, 2f), List(3f, 4f)))
    assertEquals(Npy.parse(npy(Array.empty[Array[Float]], Some((0, 512)))).length, 0)
  }

  test("Npy rejects other types, truncated data and non arrays") {
    intercept[java.io.IOException](Npy.parse(npy(Array(Array(1f)), descr = "<f8")))
    intercept[java.io.IOException](Npy.parse(npy(Array(Array(1f, 2f)), Some((2, 2)))))
    intercept[java.io.IOException](Npy.parse(Array.fill[Byte](20)(0)))
  }

  test("tags compare without escapes, underscores and case") {
    assertEquals(CharaTags.normalize("Ear \\(Test\\)"), "ear (test)")
    assertEquals(CharaTags.normalize("long_hair"), "long hair")
    assertEquals(CharaTags.split(" a, b ,, c "), Vector("a", "b", "c"))
  }

  test("a dataset is listed, loaded with tags and normalised vectors, and reports missing files") {
    val root = tmp()
    val dir = dataset(root.resolve("set"), Seq(("a_0.png", Array(3f, 4f), "1girl, solo"), ("b_0.png", Array(0f, 2f), "boy"), ("c_0.png", Array(1f, 0f), "")))
    Files.delete(dir.resolve("c_0.png"))
    assertEquals(CharaDataset.list(root), Vector("set"))
    Files.createDirectories(root.resolve("not-a-dataset"))
    assertEquals(CharaDataset.list(root), Vector("set"))
    val loaded = CharaDataset.load(dir)
    assertEquals(loaded.size, 2)
    assertEquals(loaded.problems, Vector("c_0.png: missing"))
    assertEquals(loaded.entries.head.tags, Vector("1girl", "solo"))
    assertEqualsDouble(loaded.entries.head.vector(0), 0.6, 1e-6)
    assertEqualsDouble(loaded.entries.head.vector(1), 0.8, 1e-6)
    assertEquals(loaded.entries.head.width, 10)
  }

  test("an index that does not match its vectors is an error and paths cannot leave the folder") {
    val root = tmp()
    val dir = dataset(root.resolve("set"), Seq(("a_0.png", Array(1f, 0f), "x")))
    Files.writeString(dir.resolve("embedding.json"), """{"files":[{"path":"a_0.png"},{"path":"b.png"}]}""")
    intercept[java.io.IOException](CharaDataset.load(dir))
    Files.writeString(dir.resolve("embedding.json"), """{"files":[{"path":"../evil.png"}]}""")
    assertEquals(CharaDataset.load(dir).problems.size, 1)
  }

  private def two(): Seq[CharaDataset] =
    val root = tmp()
    val a = dataset(root.resolve("a"), Seq(("one.png", Array(1f, 0f), "1girl, solo, long_hair"), ("two.png", Array(0.9f, 0.1f), "1girl, red hair"),
      ("three.png", Array(0f, 1f), "1boy, solo")))
    val b = dataset(root.resolve("b"), Seq(("four.png", Array(0.7f, 0.7f), "1girl, 2girls")))
    Seq(CharaDataset.load(a), CharaDataset.load(b))

  test("without a search image the hits keep dataset and file order; tags are an AND, negative tags a NOT") {
    val sets = two()
    assertEquals(CharaSearch.run(sets, CharaQuery()).map(_.entry.path), Vector("one.png", "two.png", "three.png", "four.png"))
    assertEquals(CharaSearch.run(sets, CharaQuery(tags = Vector("1girl", "Long Hair"))).map(_.entry.path), Vector("one.png"))
    assertEquals(CharaSearch.run(sets, CharaQuery(tags = Vector("solo"), negativeTags = Vector("1boy"))).map(_.entry.path), Vector("one.png"))
    assertEquals(CharaSearch.run(sets, CharaQuery(negativeTags = Vector("1girl"))).map(_.entry.path), Vector("three.png"))
    assertEquals(CharaSearch.run(sets.take(1), CharaQuery(tags = Vector("2girls"))).size, 0)
    assert(CharaSearch.run(sets, CharaQuery()).forall(_.similarity.isEmpty))
  }

  test("a search image sorts by cosine similarity and hides everything below the threshold") {
    val sets = two()
    val all = CharaSearch.run(sets, CharaQuery(image = Some(Array(2f, 0f)), threshold = 0.0))
    assertEquals(all.map(_.entry.path), Vector("one.png", "two.png", "four.png", "three.png"))
    assertEqualsDouble(all.head.similarity.get, 1.0, 1e-6)
    assertEqualsDouble(all(2).similarity.get, math.sqrt(0.5), 1e-6)
    val near = CharaSearch.run(sets, CharaQuery(image = Some(Array(1f, 0f)), threshold = 0.9))
    assertEquals(near.map(_.entry.path), Vector("one.png", "two.png"))
    assertEquals(CharaSearch.run(sets, CharaQuery(image = Some(Array(1f, 0f, 0f)), threshold = 0.0)).size, 0, "different embedding sizes never match")
    assertEquals(CharaSearch.run(sets, CharaQuery(image = Some(Array(1f, 0f)), threshold = 0.5, tags = Vector("1girl"))).map(_.entry.path),
      Vector("one.png", "two.png", "four.png"))
  }

  test("excluded keys are hidden and keys include the dataset") {
    val sets = two()
    val first = CharaSearch.run(sets, CharaQuery()).head
    assertEquals(first.key, "a/one.png")
    assertEquals(CharaSearch.run(sets, CharaQuery(excluded = Set(first.key))).map(_.entry.path), Vector("two.png", "three.png", "four.png"))
  }

  test("a caption puts additional tags first, drops excluded tags and duplicates") {
    val entry = CharaEntry("x.png", 1, 1, Vector("1girl", "solo", "long hair", "Solo"), Array(1f))
    assertEquals(CharaExport.caption(entry, Seq("mychar", "1girl"), Seq("SOLO")), "mychar, 1girl, long hair")
    assertEquals(CharaExport.caption(entry, Nil, Nil), "1girl, solo, long hair")
  }

  test("export copies the images with captions, numbers them and never overwrites") {
    val sets = two()
    val hits = CharaSearch.run(sets, CharaQuery(tags = Vector("1girl")))
    val out = tmp().resolve("out")
    val first = CharaExport.run(hits, out, Seq("chara"), Seq("2girls"))
    assertEquals(first.written.map(_.getFileName.toString), Vector("00000_one.png", "00001_two.png", "00002_four.png"))
    assertEquals(Files.readString(out.resolve("00002_four.png".replace(".png", ".txt"))), "chara, 1girl")
    assertEquals(Files.readString(out.resolve("00000_one.txt")), "chara, 1girl, solo, long_hair")
    val again = CharaExport.run(hits.take(1), out, Nil, Nil)
    assertEquals(again.written.map(_.getFileName.toString), Vector("00001_one.png"))
    assertEquals(Files.readAllBytes(out.resolve("00000_one.png")).toList, List[Byte](1, 2, 3))
    intercept[IllegalArgumentException](CharaExport.run(Nil, out, Nil, Nil))
  }

  test("a source that vanished is skipped and reported") {
    val sets = two()
    val hits = CharaSearch.run(sets, CharaQuery())
    Files.delete(hits.head.file)
    val result = CharaExport.run(hits, tmp().resolve("o"), Nil, Nil)
    assertEquals(result.written.size, 3)
    assertEquals(result.skipped.size, 1)
  }
