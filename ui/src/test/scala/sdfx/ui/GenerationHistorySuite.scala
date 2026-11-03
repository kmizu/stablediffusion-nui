package sdfx.ui

import javafx.beans.property.SimpleBooleanProperty
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.{CountDownLatch, TimeUnit}
import sdfx.core.presets.*
import sdfx.core.plugins.api.*
import sdfx.ui.presets.*
import sdfx.ui.vm.*

class GenerationHistorySuite extends munit.FunSuite:
  private val extension = new Plugin:
    val id = "history-extension"
    val name = "History extension"
    val kind = PluginKind.AlwaysOn
    override val controls = Seq(Control.Check("enabled", "Enabled"), Control.Text("text", "Extension input", "default"))

  private def fixture() =
    val vm = new GenerationVM(Mode.Txt2Img)
    val plugins = new PluginsVM(Mode.Txt2Img, Nil, Seq(extension), _ => Values.empty)
    val fields = new NativePresetFields(vm, plugins, new CatalogVM, new SimpleBooleanProperty(false), namedState = true)
    (vm, plugins, fields)

  private val at = LocalDateTime.of(2026, 11, 2, 10, 0, 0)
  private val empty = PresetLibrary(1, Vector.empty, Vector.empty)
  private def values(n: Int): Map[FieldKey, PresetAtom] =
    Map(FieldKey(Vector("generation", "steps")) -> PresetAtom.Integer(n))

  test("clear keeps explicitly named history and favourites, deleting only unnamed entries") {
    val (a, first, _) = GenerationHistory.append(empty, Mode.Txt2Img, values(1), at, 10)
    val (b, second, _) = GenerationHistory.append(a, Mode.Txt2Img, values(2), at, 10)
    val (c, third, _) = GenerationHistory.append(b, Mode.Txt2Img, values(3), at, 10)
    val named = GenerationHistory.rename(c, Mode.Txt2Img, first.get.id, first.get.name, "keep this").toOption.get
    val (favourited, _) = GenerationHistory.toggleFavourites(named, Mode.Txt2Img, Set(second.get.id))
    val bytes = PresetJson.writeNative(favourited, PresetLimits(maxCollections = 6)).toOption.get
    val loaded = PresetJson.readNative(bytes, PresetLimits(maxCollections = 6)).toOption.get
    val (cleared, removed) = GenerationHistory.clearNonFavourites(loaded, Mode.Txt2Img)
    assertEquals(GenerationHistory.entries(cleared, Mode.Txt2Img).map(_.id).toSet, Set(first.get.id, second.get.id))
    assertEquals(removed, Vector(third.get.id))
    val confirmed = GenerationHistory.rename(c, Mode.Txt2Img, third.get.id, third.get.name, third.get.name).toOption.get
    assert(GenerationHistory.entries(GenerationHistory.clearNonFavourites(confirmed, Mode.Txt2Img)._1, Mode.Txt2Img).exists(_.id == third.get.id))
  }

  test("capture current settings as a favourite without adding automatic history") {
    val record = PresetRecord(java.util.UUID.randomUUID(), "Saved setup", values(42), None, Set.empty, Vector.empty, Vector.empty)
    val saved = GenerationHistory.saveFavourite(empty, Mode.Txt2Img, record).toOption.get
    assertEquals(GenerationHistory.entries(saved, Mode.Txt2Img), Vector.empty)
    assertEquals(GenerationHistory.favourites(saved, Mode.Txt2Img), Vector(record))
    assert(GenerationHistory.saveFavourite(saved, Mode.Txt2Img, record.copy(id = java.util.UUID.randomUUID())).isLeft)
    assert(GenerationHistory.saveFavourite(saved, Mode.Txt2Img, record.copy(name = "different")).isLeft)
    assert(GenerationHistory.saveFavourite(saved, Mode.Img2Img, record.copy(id = java.util.UUID.randomUUID())).isRight)
  }

  test("State Manager import saves bounded inline preview without overwriting an existing thumbnail") {
    val image = new java.awt.image.BufferedImage(16, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, 0xffff0000)
    val png = new java.io.ByteArrayOutputStream()
    assert(javax.imageio.ImageIO.write(image, "png", png))
    val uri = "data:image/png;base64," + java.util.Base64.getEncoder.encodeToString(png.toByteArray)
    val json = ujson.Obj("defaults" -> ujson.Obj("d" -> ujson.Obj("txt2img/Prompt" -> "sample")),
      "entries" -> ujson.Obj("1700000000000" -> ujson.Obj("saveVersion" -> "2.0", "type" -> "txt2img", "defaults" -> "d",
        "groups" -> ujson.Arr("history"), "quickSettings" -> ujson.Obj(), "componentSettings" -> ujson.Obj(), "preview" -> uri)))
    val bytes = new java.io.ByteArrayOutputStream()
    val gzip = new java.util.zip.GZIPOutputStream(bytes)
    gzip.write(json.render().getBytes(java.nio.charset.StandardCharsets.UTF_8)); gzip.close()
    val incoming = StateManagerV2.decode(bytes.toByteArray).toOption.get
    val dir = Files.createTempDirectory("sdfx-import-preview-")
    val store = new GenerationHistoryStore(dir, 50, thunk => thunk())
    val done = new CountDownLatch(1)
    var result: Either[PresetError, HistoryImportSummary] = null
    store.importV2WithPreviews(incoming) { r => result = r; done.countDown() }
    assert(done.await(20, TimeUnit.SECONDS) && result.isRight, result.toString)
    assertEquals(result.toOption.get.previewsSaved, 1)
    val listed = new CountDownLatch(1)
    var entries = Vector.empty[PresetRecord]
    store.list(Mode.Txt2Img) { r => entries = r.toOption.get; listed.countDown() }
    assert(listed.await(20, TimeUnit.SECONDS))
    val thumb = store.thumbnailIfSafe(entries.head.id).get
    val decoded = javax.imageio.ImageIO.read(thumb.toFile)
    assertEquals((decoded.getWidth, decoded.getHeight), (16, 8))
    val original = Files.readAllBytes(thumb).toVector
    val retry = new CountDownLatch(1)
    var retryResult: Either[PresetError, Unit] = null
    store.importV2(incoming) { r => retryResult = r; retry.countDown() }
    assert(retry.await(20, TimeUnit.SECONDS))
    assert(retryResult.isRight, retryResult.toString)
    assertEquals(Files.readAllBytes(thumb).toVector, original)
    store.close()
  }

  test("import rejects unsafe previews without dropping history records") {
    val wide = new java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(101, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", wide)
    val previews = Vector("https://example.invalid/preview.png", "data:image/png;base64,not-valid!",
      "data:image/png;base64," + java.util.Base64.getEncoder.encodeToString(wide.toByteArray),
      "data:image/png;base64," + ("A" * 200000))
    val entries = ujson.Obj.from(previews.zipWithIndex.map { (preview, i) =>
      s"170000000000$i" -> ujson.Obj("saveVersion" -> "2.0", "type" -> "txt2img", "defaults" -> "d",
        "groups" -> ujson.Arr("history"), "quickSettings" -> ujson.Obj(), "componentSettings" -> ujson.Obj(), "preview" -> preview)
    })
    val json = ujson.Obj("defaults" -> ujson.Obj("d" -> ujson.Obj("txt2img/Prompt" -> "sample")), "entries" -> entries)
    val bytes = new java.io.ByteArrayOutputStream()
    val gzip = new java.util.zip.GZIPOutputStream(bytes)
    gzip.write(json.render().getBytes(java.nio.charset.StandardCharsets.UTF_8)); gzip.close()
    val incoming = StateManagerV2.decode(bytes.toByteArray).toOption.get
    val dir = Files.createTempDirectory("sdfx-import-invalid-preview-")
    val store = new GenerationHistoryStore(dir, 50, thunk => thunk())
    val done = new CountDownLatch(1)
    var result: Either[PresetError, HistoryImportSummary] = null
    store.importV2WithPreviews(incoming) { r => result = r; done.countDown() }
    assert(done.await(20, TimeUnit.SECONDS) && result.isRight, result.toString)
    assertEquals(result.toOption.get, HistoryImportSummary(0, 4))
    val listed = new CountDownLatch(1)
    var count = 0
    store.list(Mode.Txt2Img) { r => count = r.toOption.get.size; listed.countDown() }
    assert(listed.await(20, TimeUnit.SECONDS))
    assertEquals(count, 4)
    assert(!Files.exists(dir.resolve("thumbs")))
    store.close()
  }

  test("rename updates both references while preserving identity and rejects stale or duplicate names") {
    val (one, first, _) = GenerationHistory.append(empty, Mode.Txt2Img, values(1), at, 10)
    val (two, second, _) = GenerationHistory.append(one, Mode.Txt2Img, values(2), at, 10)
    val (both, _) = GenerationHistory.toggleFavourites(two, Mode.Txt2Img, Set(first.get.id))
    val updated = GenerationHistory.rename(both, Mode.Txt2Img, first.get.id, first.get.name, "  cherry  ").toOption.get
    val history = GenerationHistory.entries(updated, Mode.Txt2Img).find(_.id == first.get.id).get
    val favourite = GenerationHistory.favourites(updated, Mode.Txt2Img).head
    assertEquals(history.name, "cherry")
    assertEquals(favourite, history)
    assertEquals(history.fields, first.get.fields)
    assert(GenerationHistory.rename(updated, Mode.Txt2Img, first.get.id, first.get.name, "stale").isLeft)
    assert(GenerationHistory.rename(updated, Mode.Txt2Img, first.get.id, "cherry", second.get.name).isLeft)
    assert(GenerationHistory.rename(updated, Mode.Txt2Img, first.get.id, "cherry", " ").isLeft)
    assert(GenerationHistory.rename(updated, Mode.Img2Img, first.get.id, "cherry", "other").isLeft)
  }

  test("concurrent rename keeps one winner and stale failure leaves stored bytes unchanged") {
    val dir = Files.createTempDirectory("sdfx-history-rename-")
    val a = new GenerationHistoryStore(dir, 10, thunk => thunk())
    val b = new GenerationHistoryStore(dir, 10, thunk => thunk())
    val saved = new CountDownLatch(1)
    var record: Option[PresetRecord] = None
    a.record(Mode.Txt2Img, values(1), r => { record = r.toOption.flatten; saved.countDown() })
    assert(saved.await(20, TimeUnit.SECONDS) && record.nonEmpty)
    val first = record.get
    val finished = new CountDownLatch(2)
    val results = new java.util.concurrent.ConcurrentLinkedQueue[Either[PresetError, Unit]]()
    for (store, name) <- Vector(a -> "A", b -> "B") do
      store.rename(Mode.Txt2Img, first.id, first.name, name) { r => results.add(r); finished.countDown() }
    assert(finished.await(20, TimeUnit.SECONDS))
    val outcomes = results.toArray.toVector.map(_.asInstanceOf[Either[PresetError, Unit]])
    assertEquals(outcomes.count(_.isRight), 1)
    assertEquals(outcomes.count(_.isLeft), 1)
    val path = dir.resolve("library-v1.json")
    val before = Files.readAllBytes(path).toVector
    val rejected = new CountDownLatch(1)
    var failure: Either[PresetError, Unit] = null
    a.rename(Mode.Txt2Img, first.id, first.name, "stale") { r => failure = r; rejected.countDown() }
    assert(rejected.await(20, TimeUnit.SECONDS) && failure.isLeft)
    assertEquals(Files.readAllBytes(path).toVector, before)
    a.close(); b.close()
  }

  test("history search requires each comma term across supported fields without case sensitivity") {
    val fields = Map(FieldKey(Vector("generation", "prompt")) -> PresetAtom.Text("Blue Fox"),
      FieldKey(Vector("generation", "negativePrompt")) -> PresetAtom.Text("blurry"),
      FieldKey(Vector("generation", "sampler")) -> PresetAtom.Text("Euler"),
      FieldKey(Vector("model", "checkpoint")) -> PresetAtom.Text("DreamXL"),
      FieldKey(Vector("plugin", "secret")) -> PresetAtom.Text("not searched"))
    val record = PresetRecord(java.util.UUID.randomUUID(), "My Scene", fields, None, Set.empty, Vector.empty, Vector.empty)
    assert(GenerationHistory.matchesSearch(record, " FOX,  dreamxl, BLURRY, euler, scene"))
    assert(GenerationHistory.matchesSearch(record, " , "))
    assert(!GenerationHistory.matchesSearch(record, "fox, absent"))
    assert(!GenerationHistory.matchesSearch(record, "not searched"))
  }

  test("imported history precedes existing history and is evicted first") {
    val json = """{"defaults":{"d":{"txt2img/Prompt":"imported"}},"entries":{"1700000000000":{"saveVersion":"2.0","type":"txt2img","defaults":"d","groups":["history"],"quickSettings":{},"componentSettings":{}}}}"""
    val out = new java.io.ByteArrayOutputStream()
    val gz = new java.util.zip.GZIPOutputStream(out)
    gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); gz.close()
    val source = StateManagerV2.decode(out.toByteArray).toOption.get
    val (existing, first, _) = GenerationHistory.append(empty, Mode.Txt2Img, values(1), at, 2)
    val imported = GenerationHistory.importV2(existing, source, 2).toOption.get
    val history = GenerationHistory.entries(imported, Mode.Txt2Img)
    assertEquals(history.last.id, first.get.id)
    assertEquals(GenerationHistory.importV2(imported, source, 2).toOption.get, imported)
    val (next, _, _) = GenerationHistory.append(imported, Mode.Txt2Img, values(2), at, 2)
    assert(GenerationHistory.entries(next, Mode.Txt2Img).exists(_.id == first.get.id))
    assert(!GenerationHistory.entries(next, Mode.Txt2Img).exists(_.id == history.head.id))
  }

  test("State Manager import keeps history/favourite groups, provenance and is idempotent") {
    val json = """{"defaults":{"d":{"txt2img/Prompt":"before","txt2img/Unknown":"retained","img2img/Prompt":"other"}},"entries":{"1700000000000":{"saveVersion":"2.0","name":"Scene A","type":"txt2img","defaults":"d","groups":["history","favourites"],"quickSettings":{},"componentSettings":{"txt2img/Prompt":"after"}},"1700000000001":{"saveVersion":"2.0","type":"img2img","defaults":"d","groups":["history"],"quickSettings":{},"componentSettings":{}}}}"""
    val buffer = new java.io.ByteArrayOutputStream()
    val gz = new java.util.zip.GZIPOutputStream(buffer)
    gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); gz.close()
    val entries = StateManagerV2.decode(buffer.toByteArray).toOption.get
    val namedOnly = GenerationHistory.importV2(empty, Vector(entries.head.copy(groups = Set("history"))), 50).toOption.get
    assertEquals(GenerationHistory.entries(GenerationHistory.clearNonFavourites(namedOnly, Mode.Txt2Img)._1, Mode.Txt2Img).size, 1)
    val imported = GenerationHistory.importV2(empty, entries, 50).toOption.get
    val txt = GenerationHistory.entries(imported, Mode.Txt2Img)
    assertEquals(txt.size, 1)
    assert(txt.head.name.startsWith("Scene A ["))
    assertEquals(GenerationHistory.favourites(imported, Mode.Txt2Img).map(_.id), Vector(txt.head.id))
    assertEquals(txt.head.fields(FieldKey(Vector("generation", "prompt"))), PresetAtom.Text("after"))
    assert(txt.head.source.get.originalFields.text.contains("retained"))
    assertEquals(GenerationHistory.entries(imported, Mode.Img2Img).size, 1)
    assertEquals(GenerationHistory.importV2(imported, entries, 50).toOption.get, imported)
    assert(GenerationHistory.importV2(empty, entries, 0).isLeft)
    assert(GenerationHistory.importV2(empty, Vector(entries.head.copy(groups = Set("other"))), 50).isLeft)

    val dir = Files.createTempDirectory("sdfx-history-import-")
    val store = new GenerationHistoryStore(dir, 50, thunk => thunk())
    val done = new CountDownLatch(1)
    var result: Either[PresetError, Unit] = null
    store.importV2(entries) { r => result = r; done.countDown() }
    assert(done.await(20, TimeUnit.SECONDS) && result.isRight, result.toString)
    store.close()
    val reopened = new GenerationHistoryStore(dir, 50, thunk => thunk())
    val loaded = new CountDownLatch(1)
    var favourite: Either[PresetError, Vector[PresetRecord]] = null
    reopened.listFavourites(Mode.Txt2Img) { r => favourite = r; loaded.countDown() }
    assert(loaded.await(20, TimeUnit.SECONDS) && favourite.isRight)
    assertEquals(favourite.toOption.get.map(_.fields), txt.map(_.fields))
    assert(favourite.toOption.get.head.source.get.originalFields.text.contains("retained"))
    reopened.close()
    val constrainedDir = Files.createTempDirectory("sdfx-history-import-full-")
    val constrained = new GenerationHistoryStore(constrainedDir, 1, thunk => thunk())
    val initial = new CountDownLatch(1)
    constrained.record(Mode.Txt2Img, values(100), _ => initial.countDown())
    assert(initial.await(20, TimeUnit.SECONDS))
    val before = Files.readAllBytes(constrainedDir.resolve("library-v1.json")).toVector
    val attempted = new CountDownLatch(1)
    var rejected: Either[PresetError, Unit] = null
    constrained.importV2(entries) { r => rejected = r; attempted.countDown() }
    assert(attempted.await(20, TimeUnit.SECONDS) && rejected.isLeft)
    assertEquals(Files.readAllBytes(constrainedDir.resolve("library-v1.json")).toVector, before)
    constrained.close()
  }

  test("favourites survive history eviction and clear; mixed selection favours all then toggles off") {
    val (a, first, _) = GenerationHistory.append(empty, Mode.Txt2Img, values(1), at, 2)
    val (b, second, _) = GenerationHistory.append(a, Mode.Txt2Img, values(2), at, 2)
    val ids = Set(first.get.id, second.get.id)
    val (oneFavourite, _) = GenerationHistory.toggleFavourites(b, Mode.Txt2Img, Set(first.get.id))
    val (favourited, deleted0) = GenerationHistory.toggleFavourites(oneFavourite, Mode.Txt2Img, ids)
    assert(deleted0.isEmpty)
    assertEquals(GenerationHistory.favourites(favourited, Mode.Txt2Img).map(_.id).toSet, ids)
    val (c, _, removed) = GenerationHistory.append(favourited, Mode.Txt2Img, values(3), at, 2)
    assert(!removed.contains(first.get.id), "evicted favourite must keep its thumbnail")
    assertEquals(GenerationHistory.favourites(c, Mode.Txt2Img).map(_.id).toSet, ids)
    val (cleared, removedByClear) = GenerationHistory.clearNonFavourites(c, Mode.Txt2Img)
    assertEquals(GenerationHistory.entries(cleared, Mode.Txt2Img).map(_.id), Vector(second.get.id))
    assert(!removedByClear.contains(first.get.id) && !removedByClear.contains(second.get.id))
    val (unfavourited, deleted1) = GenerationHistory.toggleFavourites(cleared, Mode.Txt2Img, ids)
    assert(GenerationHistory.favourites(unfavourited, Mode.Txt2Img).isEmpty)
    assertEquals(deleted1.toSet, Set(first.get.id))
    assertEquals(GenerationHistory.entries(unfavourited, Mode.Txt2Img).map(_.id), Vector(second.get.id))
  }

  test("favourites persist per mode and retain thumbnails across eviction and clear") {
    val dir = Files.createTempDirectory("sdfx-favourites-")
    def sync[A](start: (Either[PresetError, A] => Unit) => Unit): A =
      val latch = new CountDownLatch(1)
      var out: Either[PresetError, A] = null
      start { result => out = result; latch.countDown() }
      assert(latch.await(10, TimeUnit.SECONDS))
      out.fold(e => fail(e.toString), identity)
    val store = new GenerationHistoryStore(dir, 2, thunk => thunk())
    val first = sync[Option[PresetRecord]](cb => store.record(Mode.Txt2Img, values(1), cb)).get
    val image = dir.resolve("test.png")
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image.toFile)
    store.writeThumbnail(first.id, image)
    sync[Unit](cb => store.toggleFavourites(Mode.Txt2Img, Set(first.id))(cb))
    sync[Option[PresetRecord]](cb => store.record(Mode.Txt2Img, values(2), cb))
    sync[Option[PresetRecord]](cb => store.record(Mode.Txt2Img, values(3), cb))
    assert(Files.exists(store.thumbnail(first.id)), "eviction removed a favourite thumbnail")
    sync[Unit](cb => store.clear(Mode.Txt2Img)(cb))
    assertEquals(sync[Vector[PresetRecord]](cb => store.listFavourites(Mode.Txt2Img)(cb)).map(_.id), Vector(first.id))
    assert(sync[Vector[PresetRecord]](cb => store.listFavourites(Mode.Inpaint)(cb)).isEmpty)
    for mode <- Vector(Mode.Img2Img, Mode.Inpaint) do
      val entry = sync[Option[PresetRecord]](cb => store.record(mode, values(4), cb)).get
      sync[Unit](cb => store.toggleFavourites(mode, Set(entry.id))(cb))
      assertEquals(sync[Vector[PresetRecord]](cb => store.listFavourites(mode)(cb)).map(_.id), Vector(entry.id))
    val missing = new java.util.UUID(1L, 2L)
    val latch = new CountDownLatch(1)
    var stale: Either[PresetError, Unit] = null
    store.toggleFavourites(Mode.Txt2Img, Set(missing)) { result => stale = result; latch.countDown() }
    assert(latch.await(10, TimeUnit.SECONDS) && stale.isLeft)
    store.close()
    val reopened = new GenerationHistoryStore(dir, 2, thunk => thunk())
    assertEquals(sync[Vector[PresetRecord]](cb => reopened.listFavourites(Mode.Txt2Img)(cb)).map(_.id), Vector(first.id))
    sync[Unit](cb => reopened.delete(Mode.Txt2Img, Set(first.id))(cb))
    assert(sync[Vector[PresetRecord]](cb => reopened.listFavourites(Mode.Txt2Img)(cb)).isEmpty)
    assert(!Files.exists(reopened.thumbnail(first.id)))
    reopened.close()
  }

  test("concurrent stores preserve both writes and coordinate favourites against clear") {
    val dir = Files.createTempDirectory("sdfx-history-concurrent-")
    val a = new GenerationHistoryStore(dir, 200, thunk => thunk())
    val b = new GenerationHistoryStore(dir, 200, thunk => thunk())
    val failures = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    for i <- 0 until 20 do
      val latch = new CountDownLatch(2)
      for (store, n) <- Vector(a -> (i * 2), b -> (i * 2 + 1)) do
        store.record(Mode.Txt2Img, values(n), result => {
          if result.isLeft then failures.add(result.toString)
          latch.countDown()
        })
      assert(latch.await(20, TimeUnit.SECONDS))
    assert(failures.isEmpty, s"concurrent records failed: $failures")
    val latch = new CountDownLatch(1)
    var records = Vector.empty[PresetRecord]
    a.list(Mode.Txt2Img) { result => records = result.toOption.get; latch.countDown() }
    assert(latch.await(20, TimeUnit.SECONDS))
    assertEquals(records.size, 40)
    val chosen = records.last.id
    val both = new CountDownLatch(2)
    var favourite: Either[PresetError, Unit] = null
    var clear: Either[PresetError, Unit] = null
    a.toggleFavourites(Mode.Txt2Img, Set(chosen)) { result => favourite = result; both.countDown() }
    b.clear(Mode.Txt2Img) { result => clear = result; both.countDown() }
    assert(both.await(20, TimeUnit.SECONDS) && clear.isRight)
    val checked = new CountDownLatch(1)
    var saved = Vector.empty[PresetRecord]
    b.listFavourites(Mode.Txt2Img) { result => saved = result.toOption.get; checked.countDown() }
    assert(checked.await(20, TimeUnit.SECONDS))
    if favourite.isRight then assert(saved.exists(_.id == chosen))
    else assert(favourite.swap.toOption.get.issues.exists(_.code == "stale-selection"))
    a.close(); b.close()
  }

  test("oversized history record reports an error and keeps the existing library bytes") {
    val dir = Files.createTempDirectory("sdfx-history-limit-")
    val store = new GenerationHistoryStore(dir, 50, thunk => thunk())
    val first = new CountDownLatch(1)
    var initial: Either[PresetError, Option[PresetRecord]] = null
    store.record(Mode.Txt2Img, values(1), result => { initial = result; first.countDown() })
    assert(first.await(20, TimeUnit.SECONDS) && initial.isRight)
    val path = dir.resolve("library-v1.json")
    val before = Files.readAllBytes(path).toVector
    val done = new CountDownLatch(1)
    var rejected: Either[PresetError, Option[PresetRecord]] = null
    store.record(Mode.Txt2Img, Map(FieldKey(Vector("generation", "prompt")) -> PresetAtom.Text("x" * 262145)), result => {
      rejected = result; done.countDown()
    })
    assert(done.await(20, TimeUnit.SECONDS) && rejected.isLeft)
    assertEquals(Files.readAllBytes(path).toVector, before)
    store.close()
  }

  test("stores using a Windows junction alias share the same process monitor") {
    if System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows") then
      val base = Files.createTempDirectory("sdfx-history-junction-")
      val actual = Files.createDirectories(base.resolve("real").resolve("history"))
      val alias = base.resolve("alias")
      val process = new ProcessBuilder("cmd", "/c", "mklink", "/J", alias.toString, actual.getParent.toString)
        .redirectErrorStream(true).start()
      assert(process.waitFor(20, TimeUnit.SECONDS))
      val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assertEquals(process.exitValue(), 0, output)
      val a = new GenerationHistoryStore(actual, 100, thunk => thunk())
      val b = new GenerationHistoryStore(alias.resolve("history"), 100, thunk => thunk())
      val failures = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      for i <- 0 until 10 do
        val done = new CountDownLatch(2)
        for (store, value) <- Vector(a -> (i * 2), b -> (i * 2 + 1)) do
          store.record(Mode.Txt2Img, values(value), result => {
            if result.isLeft then failures.add(result.toString)
            done.countDown()
          })
        assert(done.await(20, TimeUnit.SECONDS))
      assert(failures.isEmpty, failures.toString)
      a.close(); b.close()
  }

  test("a junction in the thumbnail directory is not followed for read, write or delete") {
    if System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows") then
      val base = Files.createTempDirectory("sdfx-history-thumb-link-")
      val dir = base.resolve("history")
      val external = Files.createDirectories(base.resolve("external"))
      val store = new GenerationHistoryStore(dir, 10, thunk => thunk())
      val created = new CountDownLatch(1)
      var result: Either[PresetError, Option[PresetRecord]] = null
      store.record(Mode.Txt2Img, values(1), r => { result = r; created.countDown() })
      assert(created.await(20, TimeUnit.SECONDS) && result.isRight)
      val id = result.toOption.get.get.id
      val sentinel = external.resolve(id.toString + ".png")
      Files.write(sentinel, Array[Byte](1, 2, 3, 4))
      val process = new ProcessBuilder("cmd", "/c", "mklink", "/J", dir.resolve("thumbs").toString, external.toString)
        .redirectErrorStream(true).start()
      assert(process.waitFor(20, TimeUnit.SECONDS))
      val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assertEquals(process.exitValue(), 0, output)
      assert(store.thumbnailIfSafe(id).isEmpty)
      val image = base.resolve("image.png")
      javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image.toFile)
      store.writeThumbnail(id, image)
      val deleted = new CountDownLatch(1)
      var deletion: Either[PresetError, Unit] = null
      store.delete(Mode.Txt2Img, Set(id)) { r => deletion = r; deleted.countDown() }
      assert(deleted.await(20, TimeUnit.SECONDS) && deletion.isRight)
      assertEquals(Files.readAllBytes(sentinel).toVector, Vector[Byte](1, 2, 3, 4))
      store.close()
  }

  test("two separate JVMs can append to the same history without lost writes") {
    val dir = Files.createTempDirectory("sdfx-history-process-")
    val javaExecutable = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val loaders = Iterator.iterate(getClass.getClassLoader)(_.getParent).takeWhile(_ != null)
    val urls = loaders.collect { case u: java.net.URLClassLoader => u.getURLs.toSeq }.flatten.map(u => java.nio.file.Paths.get(u.toURI).toString)
    val classpath = (urls.toSeq ++ System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct.mkString(java.io.File.pathSeparator)
    val processes = Vector(1000, 2000).map { start =>
      new ProcessBuilder(javaExecutable, "-cp", classpath, "sdfx.ui.ConcurrentHistoryProcess", dir.toString, start.toString)
        .redirectErrorStream(true).start()
    }
    try processes.foreach { process =>
      assert(process.waitFor(90, TimeUnit.SECONDS), "child process timed out")
      val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assertEquals(process.exitValue(), 0, output)
      assert(output.contains("HISTORY_CHILD_OK"), output)
    }
    finally processes.foreach(p => if p.isAlive then p.destroyForcibly())
    val store = new GenerationHistoryStore(dir, 200, thunk => thunk())
    val done = new CountDownLatch(1)
    var result: Either[PresetError, Vector[PresetRecord]] = null
    store.list(Mode.Txt2Img) { r => result = r; done.countDown() }
    assert(done.await(20, TimeUnit.SECONDS) && result.isRight)
    val actual = result.toOption.get.flatMap(_.fields.get(FieldKey(Vector("generation", "steps")))).toSet
    assertEquals(actual, ((1000 until 1010) ++ (2000 until 2010)).map(n => PresetAtom.Integer(n.toLong): PresetAtom).toSet)
    val chosen = result.toOption.get.head.id
    val source = dir.resolve("source.png")
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", source.toFile)
    store.close()
    val contenders = Vector("thumbnail", "delete").map { action =>
      new ProcessBuilder(javaExecutable, "-cp", classpath, "sdfx.ui.ConcurrentHistoryProcess", dir.toString,
        action, chosen.toString, source.toString).redirectErrorStream(true).start()
    }
    try contenders.foreach { process =>
      assert(process.waitFor(90, TimeUnit.SECONDS), "thumbnail/delete child timed out")
      val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assertEquals(process.exitValue(), 0, output)
      assert(output.contains("HISTORY_CHILD_OK"), output)
    }
    finally contenders.foreach(p => if p.isAlive then p.destroyForcibly())
    assert(!Files.exists(dir.resolve("thumbs").resolve(chosen.toString + ".png")), "thumbnail survived deletion race")
    val target = result.toOption.get.tail.head.id
    val actions = Vector("favourite", "clear").map { action =>
      new ProcessBuilder(javaExecutable, "-cp", classpath, "sdfx.ui.ConcurrentHistoryProcess", dir.toString,
        action, target.toString, source.toString).redirectErrorStream(true).start()
    }
    val actionOutputs = try actions.map { process =>
      assert(process.waitFor(90, TimeUnit.SECONDS), "favourite/clear child timed out")
      val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assertEquals(process.exitValue(), 0, output)
      assert(output.contains("HISTORY_CHILD_OK"), output)
      output
    } finally actions.foreach(p => if p.isAlive then p.destroyForcibly())
    val check = new GenerationHistoryStore(dir, 200, thunk => thunk())
    val favDone = new CountDownLatch(1)
    var favResult: Either[PresetError, Vector[PresetRecord]] = null
    check.listFavourites(Mode.Txt2Img) { r => favResult = r; favDone.countDown() }
    assert(favDone.await(20, TimeUnit.SECONDS) && favResult.isRight)
    if actionOutputs.head.contains("FAVOURITE_ADDED") then assert(favResult.toOption.get.exists(_.id == target))
    else assert(actionOutputs.head.contains("FAVOURITE_STALE"))
    check.close()
    val limited = new GenerationHistoryStore(dir, 1, thunk => thunk())
    def sync[A](f: (Either[PresetError, A] => Unit) => Unit): A =
      val done = new CountDownLatch(1)
      var result: Either[PresetError, A] = null
      f { r => result = r; done.countDown() }
      assert(done.await(20, TimeUnit.SECONDS))
      result.fold(e => fail(e.toString), identity)
    val retained = sync[Option[PresetRecord]](cb => limited.record(Mode.Inpaint,
      Map(FieldKey(Vector("generation", "steps")) -> PresetAtom.Integer(9001)), cb)).get
    sync[Unit](cb => limited.toggleFavourites(Mode.Inpaint, Set(retained.id))(cb))
    sync[Option[PresetRecord]](cb => limited.record(Mode.Inpaint,
      Map(FieldKey(Vector("generation", "steps")) -> PresetAtom.Integer(9002)), cb))
    assert(sync[Vector[PresetRecord]](cb => limited.list(Mode.Inpaint)(cb)).forall(_.id != retained.id))
    limited.close()
    val removeAndWrite = Vector("thumbnail", "unfavourite").map { action =>
      new ProcessBuilder(javaExecutable, "-cp", classpath, "sdfx.ui.ConcurrentHistoryProcess", dir.toString,
        action, retained.id.toString, source.toString).redirectErrorStream(true).start()
    }
    try removeAndWrite.foreach { process =>
      assert(process.waitFor(90, TimeUnit.SECONDS), "thumbnail/unfavourite child timed out")
      val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assertEquals(process.exitValue(), 0, output)
      assert(output.contains("HISTORY_CHILD_OK"), output)
    }
    finally removeAndWrite.foreach(p => if p.isAlive then p.destroyForcibly())
    assert(!Files.exists(dir.resolve("thumbs").resolve(retained.id.toString + ".png")), "unfavourite left a thumbnail")
  }

  test("a history operation waits while another JVM holds its operation lock") {
    val dir = Files.createTempDirectory("sdfx-history-held-lock-")
    val ready = dir.resolve("holder-ready")
    val release = dir.resolve("holder-release")
    val javaExecutable = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val loaders = Iterator.iterate(getClass.getClassLoader)(_.getParent).takeWhile(_ != null)
    val urls = loaders.collect { case u: java.net.URLClassLoader => u.getURLs.toSeq }.flatten.map(u => java.nio.file.Paths.get(u.toURI).toString)
    val classpath = (urls.toSeq ++ System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct.mkString(java.io.File.pathSeparator)
    val holder = new ProcessBuilder(javaExecutable, "-cp", classpath, "sdfx.ui.ConcurrentHistoryProcess", dir.toString,
      "hold", ready.toString, release.toString).redirectErrorStream(true).start()
    val store = new GenerationHistoryStore(dir, 50, thunk => thunk())
    try
      val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
      while !Files.exists(ready) && holder.isAlive && System.nanoTime() < end do Thread.sleep(10)
      assert(Files.exists(ready), "child did not acquire the operation lock")
      val finished = new CountDownLatch(1)
      var result: Either[PresetError, Option[PresetRecord]] = null
      store.record(Mode.Txt2Img, values(42), r => { result = r; finished.countDown() })
      assert(!finished.await(500, TimeUnit.MILLISECONDS), "record bypassed the held operation lock")
      Files.writeString(release, "release")
      assert(finished.await(20, TimeUnit.SECONDS) && result.isRight)
      assert(holder.waitFor(20, TimeUnit.SECONDS) && holder.exitValue() == 0)
    finally
      if holder.isAlive then holder.destroyForcibly()
      store.close()
  }

  test("append skips an identical newest state, names collisions and caps with eviction of the oldest") {
    val (l1, r1, _) = GenerationHistory.append(empty, Mode.Txt2Img, values(1), at, 3)
    assert(r1.nonEmpty)
    val (l1b, r1b, _) = GenerationHistory.append(l1, Mode.Txt2Img, values(1), at, 3)
    assert(r1b.isEmpty && (l1b eq l1))
    val (l2, r2, _) = GenerationHistory.append(l1, Mode.Txt2Img, values(2), at, 3)
    assertEquals(r2.get.name, "2026-11-02 10:00:00 (2)")
    val (l3, _, _) = GenerationHistory.append(l2, Mode.Txt2Img, values(3), at, 3)
    val (l4, r4, evicted) = GenerationHistory.append(l3, Mode.Txt2Img, values(4), at, 3)
    assertEquals(evicted, Vector(r1.get.id))
    assertEquals(GenerationHistory.entries(l4, Mode.Txt2Img).map(_.fields(FieldKey(Vector("generation", "steps")))),
      Vector(2L, 3L, 4L).map(PresetAtom.Integer(_)))
    assertEquals(GenerationHistory.entries(l4, Mode.Img2Img), Vector.empty)
    assertEquals(r4.get.name, "2026-11-02 10:00:00 (4)")
    val removed = GenerationHistory.remove(l4, Mode.Txt2Img, Set(r4.get.id))
    assertEquals(GenerationHistory.entries(removed, Mode.Txt2Img).size, 2)
  }

  test("disk store records, lists, deletes, clears, keeps modes apart and writes bounded thumbnails") {
    val dir = Files.createTempDirectory("sdfx-history-")
    val store = new GenerationHistoryStore(dir, 2, thunk => thunk())
    def sync[A](start: (Either[PresetError, A] => Unit) => Unit): A =
      val latch = new CountDownLatch(1)
      var out: Either[PresetError, A] = null
      start { r => out = r; latch.countDown() }
      assert(latch.await(10, TimeUnit.SECONDS))
      out.fold(e => fail(e.toString), identity)
    val a = sync[Option[PresetRecord]](cb => store.record(Mode.Txt2Img, values(1), cb)).get
    sync[Option[PresetRecord]](cb => store.record(Mode.Txt2Img, values(2), cb))
    assertEquals(sync[Option[PresetRecord]](cb => store.record(Mode.Txt2Img, values(2), cb)), None)
    val c = sync[Option[PresetRecord]](cb => store.record(Mode.Txt2Img, values(3), cb)).get
    sync[Option[PresetRecord]](cb => store.record(Mode.Inpaint, values(9), cb))
    val listed = sync[Vector[PresetRecord]](cb => store.list(Mode.Txt2Img)(cb))
    assertEquals(listed.size, 2)
    assert(!listed.exists(_.id == a.id))
    assertEquals(sync[Vector[PresetRecord]](cb => store.list(Mode.Inpaint)(cb)).size, 1)

    val image = dir.resolve("big.png")
    val big = new java.awt.image.BufferedImage(300, 150, java.awt.image.BufferedImage.TYPE_INT_RGB)
    javax.imageio.ImageIO.write(big, "png", image.toFile)
    store.writeThumbnail(c.id, image)
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while !Files.exists(store.thumbnail(c.id)) && System.nanoTime() < end do Thread.sleep(20)
    val thumb = javax.imageio.ImageIO.read(store.thumbnail(c.id).toFile)
    assertEquals((thumb.getWidth, thumb.getHeight), (100, 50))

    sync[Unit](cb => store.delete(Mode.Txt2Img, Set(c.id))(cb))
    assert(!Files.exists(store.thumbnail(c.id)))
    assertEquals(sync[Vector[PresetRecord]](cb => store.list(Mode.Txt2Img)(cb)).size, 1)
    sync[Unit](cb => store.clear(Mode.Txt2Img)(cb))
    assertEquals(sync[Vector[PresetRecord]](cb => store.list(Mode.Txt2Img)(cb)), Vector.empty)
    assertEquals(sync[Vector[PresetRecord]](cb => store.list(Mode.Inpaint)(cb)).size, 1)

    assert(store.autosave)
    assert(store.setAutosave(false).isRight && !store.autosave)
    assert(store.setAutosave(true).isRight && store.autosave)
    store.close()
  }

  test("category restore changes only the chosen categories and ignores unavailable resources of the others") {
    val (vm, plugins, fields) = fixture()
    vm.prompt.set("saved prompt")
    vm.steps.set(33)
    vm.shared.clipSkip.set(2)
    plugins.form(extension.id).get.prop("text").get.set("saved ext")
    val record = NamedGenerationStates.capture("x", fields).toOption.get
    assertEquals(StateCategory.of(FieldKey(Vector("generation", "prompt"))), StateCategory.Prompts)
    assertEquals(StateCategory.of(FieldKey(Vector("generation", "steps"))), StateCategory.Sampling)
    assertEquals(StateCategory.of(FieldKey(Vector("model", "clipSkip"))), StateCategory.Model)
    assertEquals(StateCategory.of(FieldKey(Vector("plugin", extension.id, "text"))), StateCategory.Scripts)
    assert(record.fields.keys.map(StateCategory.of).toSet.size >= 4)

    vm.prompt.set("current prompt")
    vm.steps.set(10)
    vm.shared.clipSkip.set(1)
    plugins.form(extension.id).get.prop("text").get.set("current ext")
    val only = StateCategory.keys(record, Set(StateCategory.Prompts, StateCategory.Sampling))
    val plan = NamedGenerationStates.preflight(record, fields, Some(only)).fold(e => fail(e.toString), identity)
    assert(fields.write(plan.changes).isRight)
    assertEquals(vm.prompt.get, "saved prompt")
    assertEquals(vm.steps.get, 33)
    assertEquals(vm.shared.clipSkip.get, 1)
    assertEquals(plugins.form(extension.id).get.prop("text").get.get, "current ext")
    assert(StateCategory.keys(record, Set.empty).isEmpty)
  }

object ConcurrentHistoryProcess:
  def main(args: Array[String]): Unit =
    val store = new GenerationHistoryStore(java.nio.file.Paths.get(args(0)), 200, thunk => thunk())
    try
      if args(1) == "hold" then
        val channel = java.nio.channels.FileChannel.open(java.nio.file.Paths.get(args(0)).resolve(".history-operations.lock"),
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
        try
          val lock = channel.lock()
          try
            Files.writeString(java.nio.file.Paths.get(args(2)), "held")
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while !Files.exists(java.nio.file.Paths.get(args(3))) && System.nanoTime() < until do Thread.sleep(10)
            require(Files.exists(java.nio.file.Paths.get(args(3))), "lock release timed out")
          finally lock.release()
        finally channel.close()
      else if args(1) == "thumbnail" then
        store.writeThumbnail(java.util.UUID.fromString(args(2)), java.nio.file.Paths.get(args(3)))
        val done = new CountDownLatch(1)
        store.list(Mode.Txt2Img)(_ => done.countDown())
        require(done.await(20, TimeUnit.SECONDS), "thumbnail barrier timed out")
      else if args(1) == "unfavourite" then
        val done = new CountDownLatch(1)
        var result: Either[PresetError, Unit] = null
        store.toggleFavourites(Mode.Inpaint, Set(java.util.UUID.fromString(args(2)))) { r => result = r; done.countDown() }
        require(done.await(20, TimeUnit.SECONDS) && result.isRight, s"child unfavourite failed: $result")
      else if args(1) == "delete" then
        val done = new CountDownLatch(1)
        var result: Either[PresetError, Unit] = null
        store.delete(Mode.Txt2Img, Set(java.util.UUID.fromString(args(2)))) { r => result = r; done.countDown() }
        require(done.await(20, TimeUnit.SECONDS) && result.isRight, s"child delete failed: $result")
      else if args(1) == "clear" then
        val done = new CountDownLatch(1)
        var result: Either[PresetError, Unit] = null
        store.clear(Mode.Txt2Img) { r => result = r; done.countDown() }
        require(done.await(20, TimeUnit.SECONDS) && result.isRight, s"child clear failed: $result")
      else if args(1) == "favourite" then
        val done = new CountDownLatch(1)
        var result: Either[PresetError, Unit] = null
        store.toggleFavourites(Mode.Txt2Img, Set(java.util.UUID.fromString(args(2)))) { r => result = r; done.countDown() }
        require(done.await(20, TimeUnit.SECONDS), "child favourite timed out")
        if result.isRight then println("FAVOURITE_ADDED")
        else
          require(result.swap.toOption.get.issues.exists(_.code == "stale-selection"), result.toString)
          println("FAVOURITE_STALE")
      else
        val start = args(1).toInt
        for n <- start until start + 10 do
          val done = new CountDownLatch(1)
          var result: Either[PresetError, Option[PresetRecord]] = null
          store.record(Mode.Txt2Img, Map(FieldKey(Vector("generation", "steps")) -> PresetAtom.Integer(n.toLong)), r => {
            result = r; done.countDown()
          })
          require(done.await(20, TimeUnit.SECONDS) && result.isRight, s"child record failed: $result")
      println("HISTORY_CHILD_OK")
    finally store.close()
