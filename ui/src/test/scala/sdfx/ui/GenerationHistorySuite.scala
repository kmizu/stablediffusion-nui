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
