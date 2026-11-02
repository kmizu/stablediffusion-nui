package sdfx.core

import sdfx.core.prompttools.*
import sdfx.core.prompttools.YNode.*

import java.nio.file.{Files, Path}
import scala.util.Random

class PromptToolsSuite extends munit.FunSuite:
  private def tmp(): Path = Files.createTempDirectory("prompt-tools-test-")

  // ---- PromptText --------------------------------------------------------------------------------------
  test("splitTags respects brackets, escapes and full-width commas"):
    assertEquals(PromptText.splitTags("a, (b, c:1.2), <lora:x:0.8>,  d "), Vector("a", "(b, c:1.2)", "<lora:x:0.8>", "d"))
    assertEquals(PromptText.splitTags("a\\, b, c"), Vector("a\\, b", "c"))
    assertEquals(PromptText.splitTags("\u732b\u3001\u72ac\uff0c\u9ce5"), Vector("\u732b", "\u72ac", "\u9ce5"))
    assertEquals(PromptText.splitTags(",,  ,"), Vector.empty[String])
    assertEquals(PromptText.splitTags("a, ((b, c), d"), Vector("a", "((b, c), d"), "an unclosed bracket swallows the rest")
    assertEquals(PromptText.splitTags("a)), b"), Vector("a))", "b"), "stray closers do not underflow")

  test("insert appends with one separator and skips duplicates"):
    assertEquals(PromptText.insert("", "masterpiece, best quality"), "masterpiece, best quality")
    assertEquals(PromptText.insert("1girl", "solo"), "1girl, solo")
    assertEquals(PromptText.insert("1girl,", "solo"), "1girl, solo")
    assertEquals(PromptText.insert("1girl, \n", "solo"), "1girl, solo")
    assertEquals(PromptText.insert("1girl, Solo", "solo"), "1girl, Solo", "case-insensitive duplicate is skipped")
    assertEquals(PromptText.insert("1girl, solo", "solo", skipDuplicates = false), "1girl, solo, solo")
    assertEquals(PromptText.insert("a", "a, b"), "a, b", "only the missing tag is added")
    assertEquals(PromptText.insert("a", "  "), "a")

  test("remove drops the matching tags only"):
    assertEquals(PromptText.remove("a, b, c", "b"), Some("a, c"))
    assertEquals(PromptText.remove("a, B, c", "b, c"), Some("a"))
    assertEquals(PromptText.remove("a, b", "z"), None)
    assertEquals(PromptText.remove("a, (b:1.2)", "b"), None, "weighted tags are a different tag")
    assert(PromptText.contains("a, b, c", "b, c"))
    assert(!PromptText.contains("a, b", "b, z"))
    assert(!PromptText.contains("a, b", ""))

  test("weights"):
    assertEquals(PromptText.adjustWeight("cat"), "(cat:1.1)")
    assertEquals(PromptText.adjustWeight("(cat:1.1)"), "(cat:1.2)")
    assertEquals(PromptText.adjustWeight("(cat:1.1)", -0.1), "cat")
    assertEquals(PromptText.adjustWeight("(cat)"), "(cat:1.2)")
    assertEquals(PromptText.adjustWeight("cat", -0.1), "(cat:0.9)")
    assertEquals(PromptText.adjustWeight("(cat:0.05)", -0.1), "(cat:0)", "weight is floored at 0")
    assertEquals(PromptText.adjustWeight("(cat:4.95)", 0.1), "(cat:5)", "weight is capped at 5")
    assertEquals(PromptText.adjustWeight("<lora:x:1>"), "<lora:x:1>", "networks are untouched")
    assertEquals(PromptText.adjustWeight("BREAK"), "BREAK")
    assertEquals(PromptText.adjustWeight("(a, b:1.3)", 0.1), "(a, b:1.4)")
    assertEquals(PromptText.core("[cat]")._1, "cat")
    assertEquals(PromptText.core("(cat:1.5)"), ("cat", 1.5))

  test("dedupe, blacklist, move"):
    assertEquals(PromptText.dedupe(Seq("a", "B", "b", "A", "c")), Vector("a", "B", "c"))
    assertEquals(PromptText.applyBlacklist("nsfw, (Nude:1.2), cat, [dog]", Seq("nude", "dog", " ")), "nsfw, cat")
    assertEquals(PromptText.applyBlacklist("a, b", Nil), "a, b")
    assertEquals(PromptText.moveTag(Vector(1, 2, 3, 4), 0, 2), Vector(2, 3, 1, 4))
    assertEquals(PromptText.moveTag(Vector(1, 2, 3, 4), 3, 0), Vector(4, 1, 2, 3))
    assertEquals(PromptText.moveTag(Vector(1, 2), 0, 5), Vector(1, 2))

  // ---- library -----------------------------------------------------------------------------------------
  test("library save, auto names, rename, delete, merge"):
    var (lib, n1) = PromptLibrary().save("", "a cat", false)
    assertEquals(n1, "prompt 1")
    val (l2, n2) = lib.save("", "b", true)
    assertEquals(n2, "prompt 2")
    val (l3, _) = l2.save("prompt 1", "replaced", false)
    assertEquals(l3.names, Vector("prompt 1", "prompt 2"), "same name replaces in place")
    assertEquals(l3.get("prompt 1").map(_.prompt), Some("replaced"))
    val l4 = l3.delete("prompt 1")
    assertEquals(l4.save("", "c", false)._2, "prompt 1", "lowest free number is reused")
    assertEquals(l3.rename("prompt 2", "neg").names, Vector("prompt 1", "neg"))
    intercept[IllegalArgumentException](l3.rename("prompt 2", "prompt 1"))
    intercept[IllegalArgumentException](l3.rename("missing", "x"))
    intercept[IllegalArgumentException](l3.save("bad\nname", "x", false))
    val (merged, taken) = l3.merge(PromptLibrary(Vector(SavedPrompt("prompt 1", "other", false), SavedPrompt("new", "n", false))), overwrite = false)
    assertEquals(taken, 1)
    assertEquals(merged.get("prompt 1").map(_.prompt), Some("replaced"))
    assertEquals(l3.merge(PromptLibrary(Vector(SavedPrompt("prompt 1", "other", false))), overwrite = true)._1.get("prompt 1").map(_.prompt), Some("other"))

  test("library JSON round trip uses the Multi-Prompt Manager format"):
    val dir = tmp()
    val store = new PromptLibraryStore(dir.resolve("sub/prompts.json"))
    assertEquals(store.load(), PromptLibrary())
    val lib = PromptLibrary().save("\u30cd\u30b3", "\u732b, \"quote\"\nnewline", false)._1.save("neg", "bad hands", true)._1
    store.save(lib)
    assertEquals(store.load(), lib)
    val json = ujson.read(Files.readString(store.file))
    assertEquals(json("neg")("is_negative").bool, true)
    assertEquals(json("\u30cd\u30b3")("prompt").str, "\u732b, \"quote\"\nnewline")
    // an upstream file without is_negative is accepted
    assertEquals(PromptLibrary.parse("""{"x": {"prompt": "p"}}""").get("x"), Some(SavedPrompt("x", "p", false)))
    intercept[IllegalArgumentException](PromptLibrary.parse("[1]"))
    intercept[IllegalArgumentException](PromptLibrary.parse("""{"x": {"is_negative": true}}"""))
    intercept[IllegalArgumentException](PromptLibrary.parse("{broken"))
    val exported = dir.resolve("out.json")
    store.exportTo(lib, exported)
    assertEquals(store.importFile(exported), lib)

  // ---- history -----------------------------------------------------------------------------------------
  test("history records newest first without repeats and honours the limit"):
    var h = PromptHistory()
    h = h.record("a", "n", 1).record("b", "n", 2).record("a", "n", 3)
    assertEquals(h.history.map(_.prompt), Vector("a", "b"))
    assertEquals(h.history.head.at, 3L)
    assertEquals(h.record(" ", "", 4), h, "blank pairs are not recorded")
    assertEquals(h.record("c", "", 5, limit = 2).history.map(_.prompt), Vector("c", "a"))
    assertEquals(h.removeHistory(0).history.map(_.prompt), Vector("b"))
    assertEquals(h.removeHistory(9), h)
    assertEquals(h.clearHistory().history, Vector.empty)

  test("favourites are unique per pair, persisted, and survive clearing history"):
    val dir = tmp()
    val store = new PromptHistoryStore(dir.resolve("h.json"))
    var h = PromptHistory().record("a", "n", 1).addFavorite("a", "n", 2, "first").addFavorite("a", "n", 3, "renamed").addFavorite("b", "", 4)
    assertEquals(h.favorites.map(_.name), Vector("", "renamed"))
    assert(h.isFavorite("a", "n") && !h.isFavorite("a", ""))
    intercept[IllegalArgumentException](h.addFavorite(" ", "", 5))
    store.save(h)
    assertEquals(store.load(), h)
    assertEquals(h.clearHistory().favorites.size, 2)
    assertEquals(h.removeFavorite(0).favorites.map(_.name), Vector("renamed"))

  // ---- MiniYaml / tag selector -------------------------------------------------------------------------
  test("yaml subset"):
    val y = MiniYaml.parse(
      """# comment
        |hair:
        |  single:
        |    - black hair   # trailing
        |    - "blonde: hair"
        |    - 'it''s'
        |  multi:
        |    gradient: gradient hair
        |    "q k": (a:1.2), b
        |flow: [x, "y, z", w]
        |list:
        |- one
        |- k: v
        |  k2: v2
        |""".stripMargin)
    val YMap(top) = y: @unchecked
    assertEquals(top.map(_._1), Vector("hair", "flow", "list"))
    val YMap(hair) = top(0)._2: @unchecked
    val YSeq(single) = hair(0)._2: @unchecked
    assertEquals(single, Vector(YStr("black hair"), YStr("blonde: hair"), YStr("it's")))
    assertEquals(hair(1)._2.asInstanceOf[YMap].entries, Vector("gradient" -> YStr("gradient hair"), "q k" -> YStr("(a:1.2), b")))
    assertEquals(top(1)._2, YSeq(Vector(YStr("x"), YStr("y, z"), YStr("w"))))
    assertEquals(top(2)._2.asInstanceOf[YSeq].items(1), YMap(Vector("k" -> YStr("v"), "k2" -> YStr("v2"))))
    assertEquals(MiniYaml.parse(""), YMap(Vector.empty))

  test("yaml errors carry a line number and unsupported syntax is rejected"):
    val bad = Seq("a:\n\tb: 1" -> "tabs", "a: |\n  text" -> "multi-line", "a: {x: 1}" -> "flow mappings", "a: &x 1" -> "anchors",
      "a: [1, 2" -> "unterminated", "just text" -> "key", "a:\n    b: 1\n  c: 2" -> "indentation", "a: \"open" -> "quoted")
    bad.foreach { (src, hint) =>
      val e = intercept[MiniYaml.YamlError](MiniYaml.parse(src))
      assert(e.getMessage.startsWith("line "), s"$hint: ${e.getMessage}")
    }
    intercept[MiniYaml.YamlError](MiniYaml.parse("a:\n" + (1 to 40).map(i => "  " * i + "k" + i + ":").mkString("\n") + "\n" + "  " * 41 + "x: 1"))

  test("tag files: nested groups, leaves, search, random, sort order, broken files"):
    val dir = tmp()
    Files.writeString(dir.resolve("b.yml"), "hair:\n  single:\n    - black hair\n    - blonde hair\n  multi:\n    gradient: gradient hair\n")
    Files.writeString(dir.resolve("a.yaml"), "- masterpiece\n- best quality\n")
    Files.writeString(dir.resolve("c.yml"), "x:\n\ty: 1\n")
    Files.writeString(dir.resolve("__config__.yml"), "sort:\n  - c\n  - b\n")
    Files.writeString(dir.resolve("notes.txt"), "ignored")
    val lib = TagSelector.load(dir)
    assertEquals(lib.files.map(_.name), Vector("c", "b", "a"), "sort list first, then alphabetical")
    assert(lib.files.head.error.exists(_.startsWith("line 2")))
    val b = lib.files(1)
    assertEquals(b.leaves.map(_.text), Vector("black hair", "blonde hair", "gradient hair"))
    assertEquals(lib.files(2).leaves.map(_.label), Vector("masterpiece", "best quality"))
    assertEquals(TagSelector.search(lib, "BLOND").map(h => (h.file, h.path, h.leaf.text)), Vector(("b", Vector("hair", "single"), "blonde hair")))
    assertEquals(TagSelector.search(lib, "hair gradient").map(_.leaf.label), Vector("gradient"))
    assertEquals(TagSelector.search(lib, "  "), Vector.empty)
    val group = b.nodes.head.asInstanceOf[TagNode.Group]
    val picks = (1 to 200).flatMap(i => TagSelector.randomLeaf(group, new Random(i)).map(_.text)).toSet
    assertEquals(picks, Set("black hair", "blonde hair", "gradient hair"))
    assertEquals(TagSelector.randomLeaf(TagNode.Group("e", Vector.empty), new Random(1)), None)

  test("sample tag file is created only for a missing folder"):
    val dir = tmp().resolve("tags")
    TagSelector.ensureSample(dir)
    val first = TagSelector.load(dir)
    assertEquals(first.files.map(_.name), Vector("sample"))
    assert(first.files.head.error.isEmpty && first.files.head.leaves.size >= 5)
    Files.delete(dir.resolve("sample.yml"))
    TagSelector.ensureSample(dir)
    assertEquals(TagSelector.load(dir).files, Vector.empty, "an emptied folder stays empty")

  // ---- tag cards ---------------------------------------------------------------------------------------
  test("tag cards: save, list, categories, delete and cleanup of empty folders"):
    val dir = tmp().resolve("cards")
    val store = new TagCardStore(dir)
    assertEquals(store.list(), Vector.empty)
    store.save(TagCard("Quality\\Good", "pos", "masterpiece, best quality"))
    store.save(TagCard("", "root", "root text"))
    store.save(TagCard("Quality/Good", "alt", "alt"))
    val cards = store.list()
    assertEquals(cards.map(_.id), Vector("root", "Quality/Good/alt", "Quality/Good/pos"))
    assert(Files.isRegularFile(dir.resolve("Quality/Good/pos.tag")))
    assertEquals(cards.last.text, "masterpiece, best quality")
    store.save(TagCard("Quality/Good", "pos", "changed"))
    assertEquals(store.list().find(_.name == "pos").map(_.text), Some("changed"))
    assert(store.delete(TagCard("Quality/Good", "pos", "")))
    assert(store.delete(TagCard("Quality/Good", "alt", "")))
    assert(!Files.exists(dir.resolve("Quality")), "empty category folders are removed")
    assert(Files.isDirectory(dir))
    assert(!store.delete(TagCard("", "nothing", "")))

  test("tag cards reject unsafe names and paths"):
    val dir = tmp().resolve("cards")
    val store = new TagCardStore(dir)
    val bad = Seq(TagCard("", "..", "x"), TagCard("..", "a", "x"), TagCard("a/../..", "a", "x"), TagCard("", "a/b", "x"), TagCard("", "a\\b", "x"),
      TagCard("", "con", "x"), TagCard("", "name.", "x"), TagCard("", "a:b", "x"), TagCard("", "", "x"), TagCard("", "ok", "  "),
      TagCard("C:\\evil", "a", "x"), TagCard("", "a" * 101, "x"), TagCard("", "c\u0001tl", "x"), TagCard("a/b/c/d/e/f/g", "x", "x"))
    bad.foreach(c => assert(scala.util.Try(store.save(c)).isFailure, s"should reject $c"))
    assert(!Files.exists(dir) || store.list().isEmpty)
    assert(!Files.exists(dir.resolveSibling("a.tag")))
    intercept[IllegalArgumentException](store.delete(TagCard("..", "x", "")))

  test("tag cards read UTF-8 with a BOM and ignore oversized or foreign files"):
    val dir = tmp()
    Files.writeString(dir.resolve("bom.tag"), "\uFEFF\u732b\n")
    Files.writeString(dir.resolve("other.txt"), "x")
    Files.write(dir.resolve("huge.tag"), new Array[Byte](TagCardStore.MaxCardBytes + 1))
    assertEquals(new TagCardStore(dir).list(), Vector(TagCard("", "bom", "\u732b")))

  test("service persists blacklist, history and the tag folder"):
    val root = tmp()
    var clock = 100L
    val svc = new PromptToolsService(root.resolve("pt"), () => { clock += 1; clock })
    assertEquals(svc.blacklist, Vector.empty)
    svc.setBlacklist(Seq("nude", " ", "nude", "gore"))
    assertEquals(new PromptToolsService(root.resolve("pt")).blacklist, Vector("nude", "gore"))
    svc.recordUsed("a", "n"); svc.recordUsed("b", "")
    assertEquals(new PromptToolsService(root.resolve("pt")).history.history.map(_.prompt), Vector("b", "a"))
    assertEquals(svc.history.history.head.at, 102L)
    assertEquals(svc.tagFolder, svc.defaultTagFolder)
    val other = root.resolve("elsewhere")
    Files.createDirectories(other)
    Files.writeString(other.resolve("t.yml"), "g:\n  - x\n")
    svc.setTagFolder(Some(other))
    assertEquals(svc.loadTags().files.map(_.name), Vector("t"))
    assert(!Files.exists(svc.defaultTagFolder), "no sample is written into a user-chosen folder")
    svc.setTagFolder(None)
    assertEquals(svc.loadTags().files.map(_.name), Vector("sample"))
