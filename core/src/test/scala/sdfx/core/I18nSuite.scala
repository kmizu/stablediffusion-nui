package sdfx.core

import sdfx.core.config.{LocalizationPreferences, Settings}
import sdfx.core.i18n.*
import sdfx.core.json.SnakePickle

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class I18nSuite extends munit.FunSuite:
  private def pack(json: String, id: String = "t"): LocalizationPack = LocalizationPack.parse(id, json).fold(e => fail(e), identity)
  private def tmp(): Path = Files.createTempDirectory("i18n-test-")

  private final case class Node(nodeId: String = "", styleClasses: Set[String] = Set.empty, typeNames: Seq[String] = Seq("Node"),
      parentNode: Option[ScopeNode] = None) extends ScopeNode

  // ---- parsing ----------------------------------------------------------------------------------------------
  test("plain keys translate exactly, whitespace around the text is kept"):
    val p = pack("""{"Generate": "生成", "Steps": "ステップ数"}""")
    assertEquals(p.translate("Generate"), Some("生成"))
    assertEquals(p.translate("  Steps\n"), Some("  ステップ数\n"))
    assertEquals(p.translate("generate"), None) // keys are case sensitive like the upstream lookup
    assertEquals(p.plainCount, 2)

  test("numbers, blanks, identical and empty translations are not translated"):
    val p = pack("""{"12": "twelve", "Same": "Same", "Empty": "", "Ok": "OK"}""")
    assertEquals(p.translate("12"), None)
    assertEquals(p.translate("3.5"), None)
    assertEquals(p.translate("   "), None)
    assertEquals(p.translate("Same"), None)
    assertEquals(p.translate("Empty"), None)
    assertEquals(p.translate(null), None)

  test("BOM, non-object JSON, bad JSON and non-string values"):
    assert(pack("\uFEFF{\"A b\": \"c\"}").translate("A b").contains("c"))
    assert(LocalizationPack.parse("x", "[1,2]").isLeft)
    assert(LocalizationPack.parse("x", "{not json").isLeft)
    val p = pack("""{"A": 1, "B": null, "C": ["x"], "D": "d"}""")
    assertEquals(p.skippedValues, 3)
    assertEquals(p.plainCount, 1)

  test("size limits: huge keys and values are skipped, not loaded"):
    val bigKey = "k" * (LocalizationPack.MaxKeyLength + 1)
    val bigValue = "v" * (LocalizationPack.MaxValueLength + 1)
    val p = pack(ujson.write(ujson.Obj(bigKey -> "x", "ok" -> bigValue, "fine" -> "gut")))
    assertEquals(p.plainCount, 1)
    assertEquals(p.skippedValues, 2)
    assertEquals(p.translate("x" * (LocalizationPack.MaxSourceLength + 1)), None)

  test("decoration around a known text is kept: trailing colon, ellipsis and leading icon"):
    val p = pack("""{"Brush": "ブラシ", "Open folder": "フォルダーを開く", "Seed": "シード"}""")
    assertEquals(p.translate("✎ Brush"), Some("✎ ブラシ"))
    assertEquals(p.translate("Open folder…"), Some("フォルダーを開く…"))
    assertEquals(p.translate("Seed:"), Some("シード:"))
    assertEquals(p.translate("🎲 Seed:"), Some("🎲 シード:"))
    assertEquals(p.translate("Unknown thing:"), None)

  // ---- regex rules ------------------------------------------------------------------------------------------
  test("@@/regex/ rules expand $1, $&, $$ like JavaScript replace"):
    val p = pack("""{"@@/^(\\d+) images in (\\w+)$/": "$2に$1枚", "@@/^Total$/": "$$合計 $& $9"}""")
    assertEquals(p.translate("12 images in outputs"), Some("outputsに12枚"))
    assertEquals(p.translate("Total"), Some("$合計 Total $9"))
    assertEquals(p.regexCount, 2)

  test("regex flags: i is case insensitive, g replaces every match, plain @@ text is literal and unanchored"):
    val p = pack("""{"@@/error/i": "エラー", "@@/cat/g": "ネコ", "@@a.b(": "括弧"}""")
    assertEquals(p.translate("An ERROR here"), Some("An エラー here"))
    assertEquals(p.translate("cat and cat"), Some("ネコ and ネコ"))
    assertEquals(p.translate("x a.b( y"), Some("x 括弧 y")) // the literal text is replaced inside the string
    assertEquals(p.translate("axb("), None)

  test("an invalid regex is dropped with a warning and the rest of the pack still loads"):
    val p = pack("""{"@@/(unclosed/": "x", "Good": "良い"}""")
    assertEquals(p.regexCount, 0)
    assertEquals(p.warnings.size, 1)
    assertEquals(p.translate("Good"), Some("良い"))

  test("a catastrophic regex cannot freeze the caller"):
    val p = pack("""{"@@/^(a+)+$/": "boom"}""")
    val started = System.nanoTime()
    assertEquals(p.translate("a" * 60 + "b"), None)
    assert((System.nanoTime() - started) / 1e9 < 5, "regex budget must stop catastrophic backtracking")

  test("regex rules ignore very long inputs"):
    val p = pack("""{"@@/.*/": "x"}""")
    assertEquals(p.translate("y" * (LocalizationPack.MaxRegexInput + 1)), None)

  // ---- scoped rules -----------------------------------------------------------------------------------------
  test("##id##text only applies below an element with that id; the plain translation stays elsewhere"):
    val p = pack("""{"Normal": "通常", "##tab_ti##Normal": "正規", "##@.card .btn##Lora": "ローラ"}""")
    val tab = Node(nodeId = "tab_ti")
    val inside = Node(parentNode = Some(tab))
    val other = Node(nodeId = "other")
    assertEquals(p.translate("Normal", Some(inside)), Some("正規"))
    assertEquals(p.translate("Normal", Some(tab)), Some("正規"))
    assertEquals(p.translate("Normal", Some(other)), Some("通常"))
    assertEquals(p.translate("Normal"), Some("通常"))
    assertEquals(p.scopedCount, 2)
    assert(p.hasScoped)

  test("scoped selectors: classes, types, descendant and child combinators, lists"):
    val p = pack("""{"##@.card .btn##Lora": "A", "##@Pane > Label##Hi": "B", "##@#x, .y##Go": "C"}""")
    val card = Node(styleClasses = Set("card"))
    val btn = Node(styleClasses = Set("btn"), parentNode = Some(Node(parentNode = Some(card))))
    assertEquals(p.translate("Lora", Some(btn)), Some("A"))
    assertEquals(p.translate("Lora", Some(Node(styleClasses = Set("btn")))), None)
    val pane = Node(typeNames = Seq("VBox", "Pane", "Region"))
    assertEquals(p.translate("Hi", Some(Node(typeNames = Seq("Label"), parentNode = Some(pane)))), Some("B"))
    assertEquals(p.translate("Hi", Some(Node(typeNames = Seq("Label"), parentNode = Some(Node(parentNode = Some(pane)))))), None)
    assertEquals(p.translate("Go", Some(Node(nodeId = "x"))), Some("C"))
    assertEquals(p.translate("Go", Some(Node(styleClasses = Set("y")))), Some("C"))

  test("unsupported selector syntax is dropped with a warning"):
    val p = pack("""{"##@a:hover##T": "x", "##@[data-x]##T": "y", "##@> a##T": "z", "##@##T": "w"}""")
    assertEquals(p.scopedCount, 0)
    assert(p.warnings.size >= 3)

  test("Selector.parse"):
    assert(Selector.parse("Button.primary#ok").isRight)
    assert(Selector.parse("a b > c").isRight)
    assert(Selector.parse("a >").isLeft)
    assert(Selector.parse("a + b").isLeft)
    assert(Selector.parse("").isLeft)
    assert(Selector.parse("x" * 600).isLeft)

  // ---- layering and display ---------------------------------------------------------------------------------
  test("layered packs: the first pack with a translation wins"):
    val a = pack("""{"One": "いち"}""", "a")
    val b = pack("""{"One": "ONE", "Two": "に"}""", "b")
    val l = new LayeredTranslations(Seq(a, b))
    assertEquals(l.translate("One"), Some("いち"))
    assertEquals(l.translate("Two"), Some("に"))
    assertEquals(l.translate("Three"), None)
    assert(new LayeredTranslations(Nil).isEmpty)

  test("display layouts for every style, order and kind"):
    val so = Display(DisplayStyle.TranslationOnly, DisplayOrder.OriginalFirst)
    assertEquals(so.render(TextKind.Text, "Seed", "シード"), "シード")
    val stacked = Display(DisplayStyle.Stacked, DisplayOrder.TranslationFirst)
    assertEquals(stacked.render(TextKind.Text, "Seed", "シード"), "シード\nSeed")
    assertEquals(stacked.render(TextKind.Tooltip, "Seed", "シード"), "シード\nSeed")
    assertEquals(stacked.render(TextKind.Prompt, "Seed", "シード"), "シード / Seed")
    assertEquals(stacked.render(TextKind.Item, "Seed", "シード"), "シード (Seed)")
    assertEquals(stacked.render(TextKind.Title, "Seed", "シード"), "シード (Seed)")
    val inlineOriginal = Display(DisplayStyle.Inline, DisplayOrder.OriginalFirst)
    assertEquals(inlineOriginal.render(TextKind.Text, "Seed", "シード"), "Seed (シード)")
    assertEquals(inlineOriginal.render(TextKind.Tooltip, "Seed", "シード"), "Seed\nシード")
    assertEquals(Display.parse("nonsense", "nonsense"), Display.Default)
    assertEquals(Display.parse(DisplayStyle.Inline.label, DisplayOrder.OriginalFirst.label), inlineOriginal)

  // ---- catalog ----------------------------------------------------------------------------------------------
  test("catalog finds packs in the app folder, plugin and extension folders and user folders; later ones replace earlier ones"):
    val home = tmp()
    def write(p: Path, json: String): Unit = { Files.createDirectories(p.getParent); Files.writeString(p, json) }
    write(home.resolve("localizations/ja_JP.json"), """{"A": "app"}""")
    write(home.resolve("plugins-ext/zh/localizations/zh_CN.json"), """{"A": "zh"}""")
    write(home.resolve("extensions/ko/localizations/ko_KR.json"), """{"A": "ko"}""")
    write(home.resolve("extensions/ko/localizations/ja_JP.json"), """{"A": "replaced"}""")
    val a1111 = tmp()
    write(a1111.resolve("localizations/de_DE.json"), """{"A": "de"}""")
    val direct = tmp()
    write(direct.resolve("fi_FI.json"), """{"A": "fi"}""")
    write(direct.resolve("notes.txt"), "ignored")
    val catalog = new PackCatalog(home, () => Seq(a1111, direct), () => Nil)
    val ids = catalog.list().map(_.id).toSet
    assertEquals(ids, Set("ja_JP", "zh_CN", "ko_KR", "de_DE", "fi_FI"))
    assertEquals(catalog.load("ja_JP").map(_.translate("A")), Right(Some("replaced")))
    assertEquals(catalog.find("de_DE").map(_.label), Some("Deutsch"))
    assertEquals(catalog.find("zh_CN").map(_.label), Some("简体中文"))
    assert(catalog.load("nope").isLeft)
    assertEquals(catalog.load(""), Right(LocalizationPack.Empty))

  test("catalog reloads a pack file that changed on disk and reports a broken one"):
    val home = tmp()
    val file = home.resolve("localizations/fr_FR.json")
    Files.createDirectories(file.getParent)
    Files.writeString(file, """{"A": "un"}""")
    val catalog = new PackCatalog(home, () => Nil, () => Nil)
    assertEquals(catalog.load("fr_FR").map(_.translate("A")), Right(Some("un")))
    Files.writeString(file, """{"A": "deux", "B": "b"}""")
    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))
    assertEquals(catalog.load("fr_FR").map(_.translate("A")), Right(Some("deux")))
    Files.writeString(file, "{broken")
    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 9000))
    assert(catalog.load("fr_FR").isLeft)

  test("resolve skips unreadable packs and reports them"):
    val home = tmp()
    Files.createDirectories(home.resolve("localizations"))
    Files.writeString(home.resolve("localizations/ok.json"), """{"A": "a"}""")
    Files.writeString(home.resolve("localizations/bad.json"), "[]")
    val catalog = new PackCatalog(home, () => Nil, () => Nil)
    val (t, problems) = catalog.resolve("bad", "ok")
    assertEquals(t.translate("A"), Some("a"))
    assertEquals(problems.size, 1)
    val (none, p2) = catalog.resolve("", "")
    assert(none.isEmpty && p2.isEmpty)

  test("importFile copies a valid pack, never overwrites and refuses junk"):
    val home = tmp()
    val src = tmp().resolve("my pack.json")
    Files.writeString(src, """{"A": "a"}""")
    val catalog = new PackCatalog(home, () => Nil, () => Nil)
    assertEquals(catalog.importFile(src).map(_.id), Right("my pack"))
    assertEquals(catalog.importFile(src).map(_.id), Right("my pack (2)"))
    assertEquals(catalog.list().map(_.id).toSet, Set("my pack", "my pack (2)"))
    val junk = src.resolveSibling("junk.json")
    Files.writeString(junk, "not json")
    assert(catalog.importFile(junk).isLeft)
    val empty = src.resolveSibling("empty.json")
    Files.writeString(empty, "{}")
    assert(catalog.importFile(empty).isLeft)
    assert(catalog.importFile(src.resolveSibling("missing.json")).isLeft)

  test("bundled packs are listed first under the builtin/ prefix and load from resources"):
    val catalog = new PackCatalog(tmp())
    val bundled = catalog.list().filter(_.origin == PackOrigin.Bundled)
    assert(bundled.nonEmpty, "the application ships at least one pack")
    assert(bundled.forall(_.id.startsWith("builtin/")))
    bundled.foreach { info =>
      val p = catalog.load(info.id).fold(e => fail(e), identity)
      assert(p.plainCount > 100, s"${info.id} has ${p.plainCount} entries")
      assertEquals(p.warnings, Vector.empty[String])
      assertEquals(p.skippedValues, 0)
    }

  // ---- upstream store ---------------------------------------------------------------------------------------
  test("the pinned store lists the upstream packs with hashes, commits and licences"):
    val store = PackStore.catalog()
    assertEquals(store.size, 35)
    assert(store.forall(p => p.sha256.matches("[0-9a-f]{64}") && p.commit.matches("[0-9a-f]{40}") && p.size > 0 && p.entries > 0))
    assertEquals(store.map(_.id).distinct.size, store.size)
    assertEquals(store.map(_.index).distinct.sorted, Seq(2, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 209))
    val hans = store.find(_.path.contains("Stable) [vladmandic]")).get
    assert(hans.rawUrl.endsWith("/zh-Hans%20%28Stable%29%20%5Bvladmandic%5D.json"), hans.rawUrl)
    assertEquals(store.find(_.id == "ja_JP (L4Ph)").map(_.license), Some("CC0-1.0"))

  private def storePack(bytes: Array[Byte], license: Option[Array[Byte]] = None): StorePack =
    StorePack("de_DE (x)", "de_DE", 136, "x/y", "a" * 40, "localizations/de_DE.json", bytes.length, PackStore.sha256(bytes), 1, "MIT",
      license.map(_ => "LICENSE"), license.map(PackStore.sha256))

  test("install verifies size and hash, writes the licence and a source note, and never overwrites"):
    val dest = tmp()
    val bytes = """{"A": "a"}""".getBytes(StandardCharsets.UTF_8)
    val lic = "MIT licence text".getBytes(StandardCharsets.UTF_8)
    val sp = storePack(bytes, Some(lic))
    val fetched = scala.collection.mutable.Buffer.empty[String]
    val fetch: String => Array[Byte] = u => { fetched += u; if u.endsWith("LICENSE") then lic else bytes }
    val result = PackStore.install(sp, dest, fetch)
    assert(result.isRight, result.toString)
    assertEquals(fetched.toSeq, Seq(sp.rawUrl, sp.licenseUrl.get))
    assert(Files.exists(dest.resolve("de_DE (x).json")) && Files.exists(dest.resolve("de_DE (x).LICENSE.txt")))
    assert(Files.readString(dest.resolve("de_DE (x).source.txt")).contains("licence: MIT"))
    assert(PackStore.install(sp, dest, fetch).isLeft) // already there
    assert(PackStore.install(sp, dest, fetch, overwrite = true).isRight)
    assertEquals(new PackCatalog(dest, () => Seq(dest), () => Nil).list().map(_.id), Vector("de_DE (x)")) // the pack is selectable

  test("install rejects tampered, truncated or unusable downloads and saves nothing"):
    val dest = tmp()
    val bytes = """{"A": "a"}""".getBytes(StandardCharsets.UTF_8)
    val sp = storePack(bytes)
    assert(PackStore.install(sp, dest, _ => """{"A": "b"}""".getBytes(StandardCharsets.UTF_8)).left.exists(_.contains("SHA-256")))
    assert(PackStore.install(sp, dest, _ => bytes.take(3)).left.exists(_.contains("size")))
    assert(PackStore.install(sp, dest, _ => throw new java.io.IOException("offline")).left.exists(_.contains("offline")))
    val notPack = "[1]".getBytes(StandardCharsets.UTF_8)
    assert(PackStore.install(storePack(notPack), dest, _ => notPack).isLeft)
    assertEquals(Files.list(dest).count(), 0L)

  test("only raw.githubusercontent.com is fetched"):
    intercept[IllegalArgumentException](PackStore.httpFetch("https://example.com/x.json"))
    intercept[IllegalArgumentException](PackStore.httpFetch("http://raw.githubusercontent.com/x"))

  // ---- settings ---------------------------------------------------------------------------------------------
  test("settings written before localization existed load with English defaults, and preferences round-trip"):
    val old = SnakePickle.read[Settings]("""{"outputDir": "o"}""")
    assertEquals(old.localization, LocalizationPreferences())
    assertEquals(old.localization.pack, "")
    val s = old.copy(localization = LocalizationPreferences(pack = "builtin/ja_JP", fallbackPack = "ja_JP (L4Ph)", style = "Bilingual (one line)",
      order = "Original first", tooltips = false, extraDirs = Seq("D:/a1111")))
    assertEquals(SnakePickle.read[Settings](SnakePickle.write(s)).localization, s.localization)
