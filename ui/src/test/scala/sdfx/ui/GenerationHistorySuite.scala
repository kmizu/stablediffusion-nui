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
