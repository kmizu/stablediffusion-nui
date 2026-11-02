package sdfx.core

import sdfx.core.imagebrowser.*
import sdfx.core.png.PngMetadata

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}

class ImageBrowserSuite extends munit.FunSuite:
  private def tmp(): Path = Files.createTempDirectory("ib-test-")
  private def png(p: Path, params: Option[String], rgb: Int = 0x336699): Path =
    Files.createDirectories(p.getParent)
    val img = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB)
    img.setRGB(1, 1, rgb)
    PngMetadata.write(img, p, params.map(PngMetadata.ParametersKey -> _).toSeq)
    p
  private def params(prompt: String, neg: String = "bad", steps: Int = 20, cfg: String = "7", seed: Long = 1, sampler: String = "Euler",
      size: String = "512x512", model: String = "m1", hash: String = "abc", aes: Option[String] = None): String =
    s"$prompt\nNegative prompt: $neg\nSteps: $steps, Sampler: $sampler, CFG scale: $cfg, Seed: $seed, Size: $size, Model hash: $hash, Model: $model" +
      aes.map(a => s", aesthetic_score: $a").getOrElse("")
  private def rec(name: String, text: Option[String], mod: Long = 0): ImageRecord =
    ImageRecord(Path.of("/x", name), mod, 1, ImageMeta(text))
  private def env(root: Path): BrowserEnv = new BrowserEnv(root.resolve("outputs"), root.resolve("config"), new FolderTrash(root.resolve("trash")))

  // ---- tabs & settings --------------------------------------------------------------------------------------
  test("default tabs are the documented nine and resolve to the engine's output folders"):
    val out = Path.of("/o").toAbsolutePath
    val specs = BrowserTabs.resolveAll(BrowserSettings(), out)
    assertEquals(specs.map(_.name), Vector("txt2img", "img2img", "txt2img-grids", "img2img-grids", "Extras", "Favorites", "Others", "All", "Maintenance"))
    assertEquals(specs.find(_.name == "txt2img").get.dirs, Seq(out.resolve("txt2img")))
    assertEquals(specs.find(_.name == "img2img-grids").get.dirs, Seq(out.resolve("grids")))
    assertEquals(specs.find(_.name == "Favorites").get.dirs, Seq(out.resolve("favorites")))
    assertEquals(specs.find(_.name == "All").get.dirs.size, 5) // grids de-duplicated, favorites included
    assert(!specs.find(_.name == "Maintenance").get.lists)

  test("active tabs list supports custom folders and quoted commas"):
    assertEquals(BrowserTabs.parseList("txt2img, \"C:/a,b/pics\" , Others,,"), Seq("txt2img", "C:/a,b/pics", "Others"))
    val s = BrowserSettings(activeTabs = Seq("txt2img", "D:/pics"))
    val specs = BrowserTabs.resolveAll(s, Path.of("/o"))
    assertEquals(specs(1).kind, TabKind.Custom)
    assertEquals(BrowserTabs.parseList(BrowserTabs.formatList(Seq("a,b", "c"))), Seq("a,b", "c"))

  test("settings are sanitised and round-trip through the store"):
    val bad = BrowserSettings(pageColumns = 0, pageRows = 999, thumbnailSize = 1, activeTabs = Nil, hiddenComponents = Seq("nope", "Ranking Bar"), favoritesDir = " ")
    val s = bad.sanitized
    assertEquals((s.pageColumns, s.pageRows, s.thumbnailSize), (1, 30, 48))
    assertEquals(s.activeTabs, BrowserTabs.Defaults)
    assertEquals(s.hiddenComponents, Seq("Ranking Bar"))
    assertEquals(s.favoritesDir, "favorites")
    val dir = tmp()
    val store = new BrowserStore(dir.resolve("s.json"))
    store.updateSettings(_.copy(pageColumns = 4, pageRows = 3, copyInsteadOfMove = true, infoOrder = Seq("Seed")))
    val again = new BrowserStore(dir.resolve("s.json")).settings
    assertEquals((again.pageColumns, again.perPage, again.copyInsteadOfMove, again.infoOrder), (4, 12, true, Seq("Seed")))

  test("a corrupt store is preserved as .corrupt and reported, not silently replaced"):
    val dir = tmp()
    Files.writeString(dir.resolve("s.json"), "{not json")
    val store = new BrowserStore(dir.resolve("s.json"))
    assert(store.notice.exists(_.contains("unreadable")))
    assert(Files.exists(dir.resolve("s.json.corrupt")))
    assertEquals(store.settings, BrowserSettings())

  // ---- metadata ---------------------------------------------------------------------------------------------
  test("ImageMeta parses the sortable fields"):
    val m = ImageMeta(Some(params("a cat", steps = 30, cfg = "7.5", seed = 99, size = "640x384", aes = Some("5.8"))))
    assertEquals((m.steps, m.cfgScale, m.seed, m.size, m.aestheticScore), (Some(30), Some(7.5), Some(99L), Some((640, 384)), Some(5.8)))
    assertEquals((m.sampler, m.model, m.modelHash, m.prompt, m.negativePrompt), (Some("Euler"), Some("m1"), Some("abc"), "a cat", "bad"))
    assertEquals(ImageMeta(None).steps, None)

  test("EXIF UserComment (JPEG and WebP containers) is read; PNG ranking chunk round-trips without touching pixels"):
    val text = "日本語 prompt\nSteps: 5, Seed: 2"
    val tiff = Exif.encodeUserComment(text)
    assertEquals(Exif.tiff(tiff, 0, tiff.length), Some(text))
    val app1 = "Exif\u0000\u0000".getBytes("ISO-8859-1") ++ tiff
    val seg = Array(0xff.toByte, 0xe1.toByte, ((app1.length + 2) >> 8).toByte, ((app1.length + 2) & 0xff).toByte) ++ app1
    val jpeg = Array(0xff.toByte, 0xd8.toByte) ++ seg ++ Array[Byte](0xff.toByte, 0xd9.toByte, 0, 0, 0, 0, 0, 0, 0, 0)
    assertEquals(Exif.fromBytes(jpeg), Some(text))
    assertEquals(Exif.fromBytes(Array[Byte](1, 2, 3)), None)
    val dir = tmp()
    val p = png(dir.resolve("a.png"), Some("hello\nSteps: 1"), rgb = 0xabcdef)
    PngText.set(p, "ranking", Some("4"))
    assertEquals(PngMetadata.readTexts(p).get("ranking"), Some("4"))
    assertEquals(PngMetadata.readParameters(p), Some("hello\nSteps: 1"))
    assertEquals(javax.imageio.ImageIO.read(p.toFile).getRGB(1, 1) & 0xffffff, 0xabcdef)
    PngText.set(p, "ranking", Some("2"))
    assertEquals(PngMetadata.readTexts(p).get("ranking"), Some("2"))
    PngText.set(p, "ranking", None)
    assertEquals(PngMetadata.readTexts(p).get("ranking"), None)
    assertEquals(PngMetadata.readParameters(p), Some("hello\nSteps: 1"))

  test("a .txt side-car supplies the generation text for files without embedded data"):
    val dir = tmp()
    val img = png(dir.resolve("plain.png"), None)
    Files.writeString(dir.resolve("plain.txt"), "side prompt\nSteps: 9, Seed: 3")
    assertEquals(FileMetadata.read(img).parameters, Some("side prompt\nSteps: 9, Seed: 3"))
    assertEquals(FileMetadata.read(png(dir.resolve("none.png"), None)).parameters, None)

  // ---- query ------------------------------------------------------------------------------------------------
  private val recs = Vector(
    rec("a.png", Some(params("red cat", neg = "blurry", steps = 10, cfg = "5", seed = 3, sampler = "Euler", size = "512x512", model = "mB", hash = "h2", aes = Some("6.1"))), mod = 30),
    rec("b.png", Some(params("blue dog Hires", neg = "cat", steps = 30, cfg = "9.5", seed = 1, sampler = "DPM++", size = "768x512", model = "mA", hash = "h1", aes = Some("4.0"))), mod = 10),
    rec("c10.png", Some(params("green bird", neg = "none", steps = 20, cfg = "7", seed = 2, sampler = "Euler a", size = "256x256", model = "mA", hash = "h1")), mod = 20),
    rec("c2.png", None, mod = 5))
  private val noRank: ImageRecord => Option[Int] = _ => None
  private def run(q: BrowserQuery, rank: ImageRecord => Option[Int] = noRank) = BrowserQueryEngine(recs, q, rank)
  private def names(r: QueryResult) = r.records.map(_.fileName)

  test("sort keys: date, path name (natural), numeric fields, size, direction; missing values go last"):
    assertEquals(names(run(BrowserQuery())), Vector("a.png", "c10.png", "b.png", "c2.png"))
    assertEquals(names(run(BrowserQuery(ascending = true))), Vector("c2.png", "b.png", "c10.png", "a.png"))
    assertEquals(names(run(BrowserQuery(sortBy = "path name", ascending = true))), Vector("a.png", "b.png", "c10.png", "c2.png")) // plain string order
    assertEquals(names(run(BrowserQuery(sortBy = "steps", ascending = true))), Vector("a.png", "c10.png", "b.png", "c2.png"))
    assertEquals(names(run(BrowserQuery(sortBy = "cfg scale"))), Vector("b.png", "c10.png", "a.png", "c2.png"))
    assertEquals(names(run(BrowserQuery(sortBy = "seed", ascending = true))), Vector("b.png", "c10.png", "a.png", "c2.png"))
    assertEquals(names(run(BrowserQuery(sortBy = "aesthetic_score"))), Vector("a.png", "b.png", "c2.png", "c10.png")) // missing last, natural order
    assertEquals(names(run(BrowserQuery(sortBy = "size", ascending = true))), Vector("c10.png", "a.png", "b.png", "c2.png"))
    assertEquals(names(run(BrowserQuery(sortBy = "model", ascending = true))).take(3), Vector("b.png", "c10.png", "a.png"))
    assertEquals(names(run(BrowserQuery(sortBy = "model hash", ascending = true))).head, "b.png")
    assertEquals(names(run(BrowserQuery(sortBy = "sampler", ascending = true))).take(2), Vector("b.png", "a.png"))
    val r1 = run(BrowserQuery(sortBy = "random", randomSeed = 7)); val r2 = run(BrowserQuery(sortBy = "random", randomSeed = 7))
    assertEquals(names(r1), names(r2)); assertEquals(names(r1).toSet, recs.map(_.fileName).toSet)

  test("natural compare orders digit runs numerically"):
    assert(BrowserQueryEngine.naturalCompare("img2.png", "img10.png") < 0)
    assert(BrowserQueryEngine.naturalCompare("IMG1", "img1") == 0)
    assertEquals(Vector("c10", "c2", "c1").sortWith(BrowserQueryEngine.naturalCompare(_, _) < 0), Vector("c1", "c2", "c10"))

  test("filename keyword and EXIF keyword searches, negative prompt modes and regex"):
    assertEquals(names(run(BrowserQuery(filenameKeyword = " C1 "))), Vector("c10.png"))
    assertEquals(names(run(BrowserQuery(exifKeyword = "cat"))), Vector("a.png")) // "No": negative prompts excluded
    assertEquals(names(run(BrowserQuery(exifKeyword = "cat", negativePromptSearch = "Yes"))).toSet, Set("a.png", "b.png"))
    assertEquals(names(run(BrowserQuery(exifKeyword = "cat", negativePromptSearch = "Only"))), Vector("b.png"))
    assertEquals(names(run(BrowserQuery(exifKeyword = "seed: 2"))), Vector("c10.png"))
    val re = run(BrowserQuery(exifKeyword = "^(?!.*Hires).*$", useRegex = true, negativePromptSearch = "Yes"))
    assertEquals(names(re).toSet, Set("a.png", "c10.png")) // files without generation text never match
    val bad = run(BrowserQuery(exifKeyword = "(", useRegex = true))
    assertEquals(bad.records.size, 4)
    assert(bad.warnings.exists(_.startsWith("Regex error")))

  test("ranking filter (all, single, none, min-max) and aesthetic score bounds"):
    val ranks = Map("a.png" -> 5, "b.png" -> 2, "c10.png" -> 3)
    val rk: ImageRecord => Option[Int] = r => ranks.get(r.fileName)
    assertEquals(names(run(BrowserQuery(rankingFilter = "None"), rk)), Vector("c2.png"))
    assertEquals(names(run(BrowserQuery(rankingFilter = "2"), rk)), Vector("b.png"))
    assertEquals(names(run(BrowserQuery(rankingFilter = "Min-max", rankingMin = 3, rankingMax = 5), rk)).toSet, Set("a.png", "c10.png"))
    assertEquals(names(run(BrowserQuery(rankingFilter = "Min-max", rankingMin = 5, rankingMax = 3), rk)).toSet, Set("a.png", "c10.png")) // swapped bounds
    assertEquals(names(run(BrowserQuery(sortBy = "ranking"), rk)), Vector("a.png", "c10.png", "b.png", "c2.png"))
    assertEquals(names(run(BrowserQuery(sortBy = "ranking", ascending = true), rk)).head, "c2.png")
    assertEquals(names(run(BrowserQuery(aestheticMin = "5"))), Vector("a.png"))
    assertEquals(names(run(BrowserQuery(aestheticMax = "5"))), Vector("b.png"))
    assertEquals(names(run(BrowserQuery(aestheticMin = "4", aestheticMax = "6.5"))).toSet, Set("a.png", "b.png"))
    val oops = run(BrowserQuery(aestheticMin = "x"))
    assertEquals(oops.records.size, 4); assert(oops.warnings.nonEmpty)

  test("pager clamps and slices"):
    val p = Pager(25, 10)
    assertEquals((p.pageCount, p.clamp(0), p.clamp(99), p.label(2)), (3, 1, 3, "Page Index (2/3)"))
    assertEquals(p.slice((1 to 25).toVector, 3), (21 to 25).toVector)
    assertEquals(Pager(0, 10).pageCount, 1)
    assertEquals(Pager(0, 10).slice(Vector.empty[Int], 5), Vector.empty[Int])
    assertEquals(Pager(10, 10).pageCount, 1)
    intercept[IllegalArgumentException](Pager(1, 0))

  // ---- environment: scanning, ranking, favorites, delete ---------------------------------------------------------
  test("scan honours depth rules, skips hidden folders, lists side-car metadata and videos"):
    val root = tmp(); val e = env(root)
    val out = e.outputs.resolve("txt2img")
    png(out.resolve("2026-11-01/00000-1.png"), Some(params("x")))
    png(out.resolve("top.png"), Some(params("y")))
    png(out.resolve(".hidden/skip.png"), None)
    Files.writeString(out.resolve("clip.mp4"), "not really a video")
    Files.writeString(out.resolve("note.txt"), "ignored")
    assertEquals(e.list(TabBrowser.spec(e, "txt2img")).map(_.fileName).toSet, Set("00000-1.png", "top.png", "clip.mp4"))
    assert(e.list(TabBrowser.spec(e, "txt2img")).find(_.fileName == "clip.mp4").get.isVideo)
    e.store.updateSettings(_.copy(withSubdirs = false))
    assertEquals(e.list(TabBrowser.spec(e, "txt2img")).map(_.fileName).toSet, Set("top.png", "clip.mp4"))
    assertEquals(e.listOthers(out, 0).size, 2)
    assertEquals(e.listOthers(out, 1).size, 3)
    assertEquals(e.listOthers(out, -1).size, 3)
    assertEquals(e.subdirectories(out).map(_.getFileName.toString), Vector("2026-11-01"))
    assertEquals(e.scan(Seq(root.resolve("missing")), -1), Vector.empty)
    e.store.updateSettings(_.copy(scanExif = false))
    assert(e.listOthers(out, 1).forall(_.meta.parameters.isEmpty))

  test("ranking persists across restarts, can be cleared, and is mirrored into the PNG when enabled"):
    val root = tmp(); val e = env(root)
    val p = png(e.outputs.resolve("txt2img/a.png"), Some(params("x")))
    assert(e.setRanking(p, Some(4)).isRight)
    val e2 = env(root)
    assertEquals(e2.store.ranking(p), Some(4))
    assertEquals(PngMetadata.readTexts(p).get("ranking"), None) // pnginfo option is off by default
    e2.store.updateSettings(_.copy(rankingPnginfo = true))
    assert(e2.setRanking(p, Some(5)).isRight)
    assertEquals(PngMetadata.readTexts(p).get("ranking"), Some("5"))
    assertEquals(e2.list(TabBrowser.spec(e2, "txt2img")).head.embeddedRanking, Some(5))
    assert(e2.setRanking(p, None).isRight)
    assertEquals(e2.store.ranking(p), None)
    assertEquals(PngMetadata.readTexts(p).get("ranking"), None)
    intercept[IllegalArgumentException](e2.store.setRanking(p, Some(9)))

  test("favorites: move by default, copy when configured; ranking, side-car and unique names are handled"):
    val root = tmp(); val e = env(root)
    val a = png(e.outputs.resolve("txt2img/d/a.png"), Some(params("x")))
    Files.writeString(e.outputs.resolve("txt2img/d/a.txt"), "side")
    e.setRanking(a, Some(3))
    val moved = e.addToFavorites(a).toOption.get
    assertEquals(moved, e.favoritesDir.resolve("a.png"))
    assert(!Files.exists(a) && Files.exists(moved) && Files.exists(e.favoritesDir.resolve("a.txt")) && !Files.exists(a.resolveSibling("a.txt")))
    assertEquals(e.store.ranking(moved), Some(3)); assertEquals(e.store.ranking(a), None)
    // copy mode keeps the original and never overwrites an existing favorite
    e.store.updateSettings(_.copy(copyInsteadOfMove = true))
    val b = png(e.outputs.resolve("txt2img/d/a.png"), Some(params("other")))
    val copied = e.addToFavorites(b).toOption.get
    assertEquals(copied.getFileName.toString, "a-1.png")
    assert(Files.exists(b) && Files.exists(moved))
    // txt files option off: side-car stays
    e.store.updateSettings(_.copy(copyInsteadOfMove = false, txtFiles = false))
    val c = png(e.outputs.resolve("txt2img/c.png"), None); Files.writeString(e.outputs.resolve("txt2img/c.txt"), "s")
    e.addToFavorites(c).toOption.get
    assert(Files.exists(e.outputs.resolve("txt2img/c.txt")))
    // moving into the folder the file is already in is refused, missing files are reported
    assert(e.sendToDirectory(moved, e.favoritesDir).isLeft)
    assert(e.sendToDirectory(e.outputs.resolve("txt2img/ghost.png"), e.favoritesDir).isLeft)

  test("delete goes to the trash (with its side-car), reports missing files and drops the ranking"):
    val root = tmp(); val e = env(root)
    val a = png(e.outputs.resolve("txt2img/a.png"), Some(params("x"))); Files.writeString(e.outputs.resolve("txt2img/a.txt"), "side")
    val b = png(e.outputs.resolve("txt2img/b.png"), None)
    e.setRanking(a, Some(2))
    val results = e.delete(Seq(a, b, e.outputs.resolve("txt2img/ghost.png")))
    assertEquals(results.map(_._2.isRight), Vector(true, true, false))
    assert(!Files.exists(a) && !Files.exists(b) && !Files.exists(e.outputs.resolve("txt2img/a.txt")))
    val trashed = Files.walk(root.resolve("trash")).filter(Files.isRegularFile(_)).map(_.getFileName.toString).toArray.toSet
    assertEquals(trashed, Set[AnyRef]("a.png", "a.txt", "b.png"))
    assertEquals(e.store.ranking(a), None)

  test("permanent deletion only happens when the recycle option is off"):
    val root = tmp(); val e = env(root)
    val a = png(e.outputs.resolve("txt2img/a.png"), None)
    e.store.updateSettings(_.copy(deleteRecycle = false))
    assert(e.delete(Seq(a)).head._2.isRight)
    assert(!Files.exists(a) && !Files.exists(root.resolve("trash")))

  test("maintenance: rebuild cache, delete empty entries, rename directories, hashes, reapply ranking, ranking from files"):
    val root = tmp(); val e = env(root)
    val a = png(e.outputs.resolve("txt2img/a.png"), Some(params("x")), rgb = 0x111111)
    val b = png(e.outputs.resolve("txt2img/b.png"), None, rgb = 0x222222)
    assertEquals(e.rebuildCache(), 2)
    assertEquals(e.cache.size, 2)
    assertEquals(e.deleteEmptyCacheEntries(), 1)
    assertEquals(e.cache.size, 1)
    // ranking follows a file moved outside the app (same content hash)
    e.setRanking(a, Some(5))
    val elsewhere = e.outputs.resolve("img2img/moved.png")
    Files.createDirectories(elsewhere.getParent); Files.move(a, elsewhere)
    assertEquals(e.reapplyRanking(), (1, 0))
    assertEquals(e.store.ranking(elsewhere), Some(5)); assertEquals(e.store.ranking(a), None)
    // directory rename in the database
    e.store.addSavedDir(e.outputs.resolve("old").toString, 2)
    e.setRanking(elsewhere, Some(4))
    val counts = e.updateDirectoryNames(e.outputs.resolve("img2img").toString, e.outputs.resolve("renamed").toString, pathRecorder = true, exifData = true, ranking = true)
    assertEquals(counts("ranking"), 1); assertEquals(counts("exif_data"), 1)
    assert(e.store.rankings.keys.exists(_.contains("renamed")))
    val c2 = e.updateDirectoryNames(e.outputs.resolve("old").toString, e.outputs.resolve("new").toString, true, false, false)
    assertEquals(c2("path_recorder"), 1)
    assertEquals(e.store.savedDirs.map(d => Path.of(d.path).getFileName.toString), Seq("new"))
    // hashes
    Files.createDirectories(e.outputs.resolve("renamed")); Files.move(elsewhere, e.outputs.resolve("renamed/moved.png"))
    assertEquals(e.recreateHashes(), (1, 0))
    // ranking from file metadata
    val r = png(e.outputs.resolve("txt2img/r.png"), None); PngText.set(r, "ranking", Some("3"))
    assertEquals(e.rebuildRankingFromFiles(), 1)
    assertEquals(e.store.ranking(r), Some(3))
    assertEquals(e.rebuildRankingFromFiles(), 0) // idempotent
    assert(Files.exists(b))

  test("the metadata cache survives a restart and is invalidated by file changes"):
    val root = tmp(); val e = env(root)
    val a = png(e.outputs.resolve("txt2img/a.png"), Some(params("first")))
    e.rebuildCache()
    val e2 = env(root)
    assertEquals(e2.cache.size, 1)
    val got = e2.list(TabBrowser.spec(e2, "txt2img")).head
    assertEquals(got.meta.prompt, "first")
    png(a, Some(params("second")), rgb = 0x555555)
    Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(got.modified + 5000))
    assertEquals(e2.list(TabBrowser.spec(e2, "txt2img")).head.meta.prompt, "second")

object TabBrowser:
  def spec(e: BrowserEnv, name: String): TabSpec = BrowserTabs.resolve(name, e.outputs, e.settings)
