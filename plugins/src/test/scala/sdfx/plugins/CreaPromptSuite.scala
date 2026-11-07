package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.plugins.creaprompt.CreaData

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.Random

/** CreaPrompt: bundled lists, preset matching, manual picks, prompt composition, seeding, infotext and the script run. */
class CreaPromptSuite extends munit.FunSuite:
  private val plugin = new CreaPromptPlugin(() => None)
  private def values(kv: (String, String)*) = Values(Values.defaults(plugin.controls).map ++ kv)
  private val base = GenerationParams(prompt = "masterpiece", negativePrompt = "lowres", seed = 50, width = 64, height = 64)
  private lazy val lib = CreaData.load(None)

  test("bundled lists: every category has entries, the collection and presets load, names drop the order prefix") {
    assertEquals(lib.categories.size, 42)
    assert(lib.categories.forall(_.entries.nonEmpty))
    assertEquals(lib.collection.size, 801)
    assertEquals(lib.presets.size, 11)
    assertEquals(lib.categories.head.name, "Man")
    assertEquals(lib.categories.map(_.file), lib.categories.map(_.file).sorted)
    assert(lib.category("woman").exists(_.file == "1_0Woman.csv"))
    assertEquals(CreaData.categoryName("2_6fictional characters.csv"), "fictional characters")
    assertEquals(CreaData.categoryName("MyOwn.csv"), "MyOwn")
    assertEquals(lib.category("Woman").get.entries.size, 41)
  }

  test("lines: BOM, blank lines and CRLF are removed; trailing commas are cleaned") {
    assertEquals(CreaData.lines("﻿a\r\n\r\n b \nc"), Vector("a", "b", "c"))
    assertEquals(CreaData.clean(" red hair,, "), "red hair")
  }

  test("presets match category names without regard to case and ignore unknown names") {
    val keys = plugin.controls.collect { case c: Control.Check if c.key.startsWith("cat_") => c.key }
    assertEquals(keys.size, 42)
    val (cats, unknown) = CreaPromptPlugin.selectCategories(lib, values("preset" -> "Woman"), Seq.empty)
    assert(cats.map(_.name).contains("Woman"))
    assert(cats.map(_.name).contains("Artists"), "'artists' in the preset matches the Artists category")
    assertEquals(cats.map(_.file), cats.map(_.file).sorted, "library order")
    assertEquals(unknown, Vector("outdoors", "lighting_light"))
    intercept[IllegalArgumentException](CreaPromptPlugin.selectCategories(lib, values("preset" -> "Nope"), Seq.empty))
  }

  test("random from categories draws one entry of each category in order and is seed-determined") {
    val cats = Vector(lib.category("Woman").get, lib.category("lighting").get)
    val a = CreaPromptPlugin.fromCategories(cats, new Random(7))
    val b = CreaPromptPlugin.fromCategories(cats, new Random(7))
    assertEquals(a, b)
    val parts = a.split(", ")
    assert(cats.head.entries.exists(e => a.startsWith(CreaData.clean(e))))
    assert(cats(1).entries.exists(e => a.endsWith(CreaData.clean(e))), a)
    assert(parts.nonEmpty)
    assertNotEquals(CreaPromptPlugin.fromCategories(cats, new Random(8)), a)
    intercept[IllegalArgumentException](CreaPromptPlugin.fromCategories(Nil, new Random(1)))
  }

  test("manual picks: exact entries, random, none, library order, and clear errors") {
    val w = lib.category("Woman").get
    val picks = CreaPromptPlugin.parseManual(lib, s"# comment\nlighting: random\nWoman: ${w.entries(2)}\nCameras: none")
    assertEquals(picks.map(_._1.name), Vector("Woman", "lighting"))
    assertEquals(picks.head._2, Some(w.entries(2)))
    assertEquals(picks(1)._2, None)
    assert(CreaPromptPlugin.fromManual(picks, new Random(1)).startsWith(CreaData.clean(w.entries(2))))
    assertEquals(CreaPromptPlugin.parseManual(lib, "woman: " + w.entries(0).toUpperCase).head._2, Some(w.entries(0)))
    intercept[IllegalArgumentException](CreaPromptPlugin.parseManual(lib, "Nothing: random"))
    intercept[IllegalArgumentException](CreaPromptPlugin.parseManual(lib, "Woman: not an entry"))
    intercept[IllegalArgumentException](CreaPromptPlugin.parseManual(lib, "Woman: random\nwoman: random"))
    intercept[IllegalArgumentException](CreaPromptPlugin.parseManual(lib, "Woman"))
    intercept[IllegalArgumentException](CreaPromptPlugin.fromManual(Nil, new Random(1)))
  }

  test("manual mode exposes category choices and uses them without typing entries") {
    val choices = plugin.controls.collect { case c: Control.Choice if c.key.startsWith("manual_cat_") => c }
    assertEquals(choices.size, lib.categories.size)
    val woman = choices.find(_.key == "manual_cat_Woman").get
    assert(woman.options.contains(lib.category("Woman").get.entries(2)))
    assert(woman.options.contains("(random)"))
    val ctx = new FakeContext()
    plugin.run(base, values("mode" -> CreaPromptPlugin.ModeManual,
      "manual_cat_Woman" -> lib.category("Woman").get.entries(2), "manual_cat_lighting" -> "(random)"), ctx)
    val prompt = ctx.generated.head.prompt
    assert(prompt.startsWith(CreaData.clean(lib.category("Woman").get.entries(2)) + ", "), prompt)
    assert(lib.category("lighting").get.entries.exists(e => prompt.endsWith(CreaData.clean(e))), prompt)
  }

  test("manual preview updates chosen values and names random categories without drawing random entries") {
    val w = lib.category("Woman").get.entries(2)
    val v = values("mode" -> CreaPromptPlugin.ModeManual, "manual_cat_Woman" -> w, "manual_cat_lighting" -> "(random)")
    val preview = plugin.manualPreview(v)
    assert(preview.startsWith(CreaData.clean(w)), preview)
    assert(preview.contains("Random: lighting"), preview)
    assertEquals(plugin.manualPreview(v.updated("manual_cat_lighting", "(none)")), CreaData.clean(w))
  }

  test("saved category preset is immediately available and rejects overwrite and unsafe names") {
    val dir = Files.createTempDirectory("crea-preset")
    val p = new CreaPromptPlugin(() => Some(dir))
    val v = Values(Values.defaults(p.controls).map ++ Map("preset" -> "Woman"))
    val saved = p.savePreset(v, "My preset")
    assertEquals(saved, dir.resolve("My preset.config"))
    assert(Files.readString(saved).linesIterator.contains("Woman"))
    assert(p.presetNames.contains("My preset"))
    val ctx = new FakeContext()
    p.run(base, v.updated("preset", "My preset"), ctx)
    assert(ctx.generated.head.extra(CreaPromptPlugin.CategoriesKey).contains("Woman"))
    intercept[IllegalArgumentException](p.savePreset(v, "../outside"))
    intercept[IllegalArgumentException](p.savePreset(v, ""))
    intercept[IllegalArgumentException](p.savePreset(v, "woman"))
    intercept[java.nio.file.FileAlreadyExistsException](p.savePreset(v, "My preset"))
    intercept[java.nio.file.FileAlreadyExistsException](p.savePreset(v, "my PRESET"))
    val reopened = new CreaPromptPlugin(() => Some(dir))
    intercept[java.nio.file.FileAlreadyExistsException](reopened.savePreset(v, "My preset"))
  }

  test("prefix, suffix and position") {
    assertEquals(CreaPromptPlugin.wrap("mid", "pre", "suf"), "pre, mid, suf")
    assertEquals(CreaPromptPlugin.wrap("mid", "", ""), "mid")
    assertEquals(CreaPromptPlugin.place("Replace prompt", "old", "new"), "new")
    assertEquals(CreaPromptPlugin.place("After prompt", "old", "new"), "old, new")
    assertEquals(CreaPromptPlugin.place("Before prompt", "old,", "new"), "new, old")
    assertEquals(CreaPromptPlugin.place("After prompt", "", "new"), "new")
  }

  test("user folder: replaces a bundled list, adds categories, presets and a collection; foreign and empty files are skipped") {
    val dir = Files.createTempDirectory("crea-user")
    Files.writeString(dir.resolve("1_0Woman.csv"), "only one woman\n")
    Files.write(dir.resolve("9_1Mine.csv"), "﻿foo\r\nbar\r\n".getBytes(StandardCharsets.UTF_8))
    Files.writeString(dir.resolve("Mine.config"), "Mine\nWoman\n")
    Files.writeString(dir.resolve("collection.txt"), "a ready prompt\n")
    Files.writeString(dir.resolve("notes.md"), "ignored")
    Files.writeString(dir.resolve("empty.csv"), "\n\n")
    val l = CreaData.load(Some(dir))
    assertEquals(l.category("Woman").get.entries, Vector("only one woman"))
    assertEquals(l.category("Mine").get.entries, Vector("foo", "bar"))
    assertEquals(l.categories.size, 43, "empty.csv has no entries and is dropped, so only Mine is added")
    assertEquals(l.collection, Vector("a ready prompt"))
    assertEquals(l.preset("mine"), Some(Vector("Mine", "Woman")))
    val p = new CreaPromptPlugin(() => Some(dir))
    assert(p.controls.exists { case c: Control.Check => c.key == "cat_Mine"; case _ => false })
    assertEquals(CreaData.load(Some(dir.resolve("missing"))).categories.size, 42)
  }

  test("standard plugins use the host's explicit app home for custom CreaPrompt categories") {
    val home = Files.createTempDirectory("crea-explicit-home")
    val dir = home.resolve("creaprompt/csv")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("9_1OnlyAtHome.csv"), "unique prompt\n")
    val p = StandardPlugins.scripts(() => Nil, sdfx.core.config.AppHome(home)).find(_.id == "creaprompt").get
    assert(p.controls.exists(_.key == "cat_OnlyAtHome"))
  }

  test("script run: a different seeded prompt per image, infotext, negative prompt untouched") {
    val ctx = new FakeContext()
    val v = values("mode" -> CreaPromptPlugin.ModeCategories)
    val out = plugin.run(base.copy(batchSize = 2, nIter = 2), v, ctx)
    assertEquals(out.images.size, 4)
    val gens = ctx.generated
    assertEquals(gens.map(_.seed), Seq(50L, 51L, 52L, 53L))
    assert(gens.forall(g => g.batchSize == 1 && g.nIter == 1 && g.negativePrompt == "lowres"))
    assertEquals(gens.map(_.prompt).distinct.size, 4)
    assert(gens.forall(_.extra.contains(CreaPromptPlugin.CategoriesKey)))
    assert(gens.head.extra(CreaPromptPlugin.CategoriesKey).contains("Woman"))
    val ctx2 = new FakeContext()
    plugin.run(base.copy(batchSize = 2, nIter = 2), v, ctx2)
    assertEquals(ctx2.generated.map(_.prompt), gens.map(_.prompt), "same seed, same prompts")
  }

  test("script run: one prompt for the whole batch keeps the batch settings") {
    val ctx = new FakeContext()
    plugin.run(base.copy(batchSize = 2, nIter = 3), values("per_image" -> "false", "position" -> "After prompt", "prefix" -> "pre"), ctx)
    assertEquals(ctx.generated.size, 1)
    val g = ctx.generated.head
    assertEquals((g.batchSize, g.nIter, g.seed), (2, 3, 50L))
    assert(g.prompt.startsWith("masterpiece, pre, "), g.prompt)
  }

  test("script run: collection mode and manual mode set the upstream infotext") {
    val c1 = new FakeContext()
    plugin.run(base.copy(batchSize = 3), values("mode" -> CreaPromptPlugin.ModeCollection, "suffix" -> "best quality"), c1)
    assertEquals(c1.generated.size, 3)
    assert(c1.generated.forall(g => g.extra(CreaPromptPlugin.CollectionKey) == "Collection" && g.prompt.endsWith(", best quality")))
    assert(c1.generated.forall(g => lib.collection.exists(e => g.prompt.startsWith(CreaData.clean(e)))))
    val c2 = new FakeContext()
    plugin.run(base, values("mode" -> CreaPromptPlugin.ModeManual, "manual_list" -> "Cameras: random"), c2)
    assertEquals(c2.generated.head.extra(CreaPromptPlugin.CollectionKey), "manual from categories")
    assert(lib.category("Cameras").get.entries.exists(e => CreaData.clean(e) == c2.generated.head.prompt))
  }

  test("script run: bad input fails before any generation") {
    val ctx = new FakeContext()
    intercept[IllegalArgumentException](plugin.run(base, values("mode" -> CreaPromptPlugin.ModeManual), ctx))
    intercept[IllegalArgumentException](plugin.run(base, values("preset" -> "Deselect All"), ctx))
    intercept[IllegalArgumentException](plugin.run(base, values("mode" -> "Other"), ctx))
    intercept[IllegalArgumentException](plugin.run(base, values("position" -> "Middle"), ctx))
    assertEquals(ctx.generated.size, 0)
  }

  test("script run: interruption stops after the images made so far") {
    val ctx = new FakeContext(interruptAfter = 2)
    val out = plugin.run(base.copy(batchSize = 4), values(), ctx)
    assertEquals(out.images.size, 2)
  }

  test("prompt actions return a prompt for the box; registered once") {
    val add = plugin.promptActions.find(_.id == "add_random").get
    val rep = plugin.promptActions.find(_.id == "replace_random").get
    assert(!add.replace && rep.replace)
    assert(add.text(values()).nonEmpty)
    assert(StandardPlugins.scripts(() => Nil).count(_.id == "creaprompt") == 1)
  }
