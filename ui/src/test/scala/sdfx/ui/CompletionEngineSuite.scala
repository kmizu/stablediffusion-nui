package sdfx.ui

import java.nio.file.Files
import munit.FunSuite
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.vm.*

class CompletionEngineSuite extends FunSuite:
  private val cfg = TagCompleteSettings()
  private def run(data: CompletionData, text: String, c: TagCompleteSettings = cfg): Option[CompletionResult] =
    val caret = text.indexOf('|')
    val clean = text.replace("|", "")
    CompletionEngine.complete(data, c, clean, caret, caret, caret)
  private def applied(data: CompletionData, text: String, pick: Int = 0, c: TagCompleteSettings = cfg): String =
    val clean = text.replace("|", "")
    val r = run(data, text, c).get
    clean.take(r.start) + r.items(pick).insert() + clean.drop(r.end)

  private val tags = TagCompletion.parse(Iterator("blue_hair,0,20,azure_hair", "blue_eyes,0,10,", "red_(dress),0,5,", "猫,0,1,"))
  private val dir = Files.createTempDirectory("tac-suite")
  private val loraFile = Files.createFile(dir.resolve("styleA.safetensors"))
  Files.writeString(dir.resolve("styleA.json"), """{"activation text": "sksstyle, vivid", "preferred weight": 0.8}""")
  Files.createFile(dir.resolve("styleA.preview.png"))
  private val plain = Files.createFile(dir.resolve("plainB.safetensors"))
  private val emb = Files.createFile(dir.resolve("bad_hands.pt"))
  private val data = CompletionData(tags = tags, wildcards = Vector("hair", "colors/warm", "colors/cold", "animals"),
    loras = Vector(NetFile("styleA", loraFile), NetFile("plainB", plain)), embeddings = Vector(NetFile("bad_hands", emb)),
    chants = Vector(Chant("Basic-HighQuality", "Best,High,Quality", "(masterpiece, best quality)", 1),
      Chant("Basic-Negative", "Negative,Low", "(worst quality)", 3)))

  test("wildcard trigger: names, nested paths, replaces an existing closing __") {
    assertEquals(run(data, "a, __col|").get.items.map(_.label), Vector("colors/warm", "colors/cold"))
    assertEquals(applied(data, "a, __col|", 1), "a, __colors/cold__")
    assertEquals(applied(data, "__hai| rest"), "__hair__ rest")
    assertEquals(applied(data, "__ha|ir__, x"), "__hair__, x")
    assertEquals(run(data, "__|").get.items.size, 4)
    assertEquals(run(data, "__hair__|"), None, "finished wildcard must not reopen")
    assertEquals(run(data, "foo__ha|"), None, "double underscore inside a word is not a trigger")
    assertEquals(run(data, "__zzz|"), None)
  }
  test("wildcards substring and disabled") {
    assertEquals(run(data, "__warm|").get.items.map(_.label), Vector("colors/warm"))
    assertEquals(run(data, "__col|", cfg.copy(wildcards = false)), None)
  }
  test("wildcard common prefix includes matches beyond the displayed result limit") {
    val many = data.copy(wildcards = Vector("colors/warm", "colors/water", "colors/cold"))
    val result = run(many, "__col|", cfg.copy(maxResults = 2)).get
    assertEquals(result.items.map(_.label), Vector("colors/warm", "colors/water"))
    assertEquals(result.wildcardPrefix, Some("colors/"))
  }
  test("extra network: mixed list, filters, long forms") {
    assertEquals(run(data, "<|").get.items.map(_.kind.title).distinct, Vector("LoRA", "embedding", "chant"))
    assertEquals(run(data, "<e:|").get.items.map(_.label), Vector("bad_hands"))
    assertEquals(run(data, "<l:sty|").get.items.map(_.label), Vector("styleA"))
    assertEquals(run(data, "<lora:pl|").get.items.map(_.label), Vector("plainB"))
    assertEquals(run(data, "<c:qual|").get.items.map(_.label), Vector("Basic-HighQuality"))
    assertEquals(run(data, "<c:low|").get.items.map(_.label), Vector("Basic-Negative"), "matches search terms")
    assertEquals(run(data, "<c:high|").get.items.map(_.label), Vector("Basic-HighQuality"))
    assertEquals(run(data, "<h:x|"), None)
    assertEquals(run(data, "<unknown:x|"), None)
    assertEquals(run(data, "<lora:zzz|"), None)
  }
  test("lora insertion: weight kept, lyco form, trigger words from sidecar, tail replaced") {
    assertEquals(applied(data, "<lora:plai|"), "<lora:plainB:1.0>")
    assertEquals(applied(data, "x <l:plai|nB:0.6> y"), "x <lora:plainB:0.6> y")
    assertEquals(applied(data, "<lyco:plai|"), "<lyco:plainB:1.0>")
    assertEquals(applied(data, "<lora:sty|"), "<lora:styleA:1.0>, sksstyle, vivid")
    assertEquals(applied(data, "<lora:sty|", c = cfg.copy(loraTriggerWords = false)), "<lora:styleA:1.0>")
    assertEquals(run(data, "<lora:plainB:0.|5>"), None, "caret in the weight")
    assertEquals(run(data, "<lo|ra:plainB:1>"), None, "caret in the head")
  }
  test("embedding and chant insertion") {
    assertEquals(applied(data, "a, <e:bad|, b"), "a, bad_hands, b")
    assertEquals(applied(data, "<c:high|"), "(masterpiece, best quality)")
    assertEquals(run(data, "<|", cfg.copy(chants = false)).get.items.map(_.kind.title).distinct, Vector("LoRA", "embedding"))
    assertEquals(run(data, "<|", cfg.copy(extraNetworks = false)), None)
  }
  test("preview image and trigger words lookups") {
    assertEquals(CompletionEngine.previewFor(loraFile).map(_.getFileName.toString), Some("styleA.preview.png"))
    assertEquals(CompletionEngine.previewFor(plain), None)
    assertEquals(CompletionEngine.triggerWords(plain), None)
    assertEquals(run(data, "<l:sty|").get.items.head.preview().map(_.getFileName.toString), Some("styleA.preview.png"))
  }
  test("tag options: underscores, parens, comma, max results, alias display") {
    assertEquals(applied(data, "red|"), "red \\(dress\\)")
    assertEquals(applied(data, "red|", c = cfg.copy(escapeParens = false, replaceUnderscores = false)), "red_(dress)")
    assertEquals(applied(data, "blue h|", c = cfg.copy(appendComma = true)), "blue hair, ")
    assertEquals(applied(data, "blue h|, x", c = cfg.copy(appendComma = true)), "blue hair, x")
    assertEquals(run(data, "blue|", cfg.copy(maxResults = 1)).get.items.size, 1)
    assertEquals(run(data, "azure|").get.items.map(_.label), Vector("azure_hair ➝ blue_hair"))
    assertEquals(run(data, "azure|", cfg.copy(onlyShowAlias = true)).get.items.map(_.label), Vector("azure_hair"))
    assertEquals(applied(data, "azure|"), "blue hair")
    assertEquals(run(data, "azure|", cfg.copy(searchByAlias = false)), None)
  }
  test("translations: search by translation, shown as detail, legacy 3-column") {
    val tr = CompletionEngine.parseTranslations(Iterator("blue_hair,青い髪", "blue_eyes,青い目", "\"red_(dress)\",赤いドレス"), legacy = false)
    val d = data.copy(translations = tr)
    assertEquals(run(d, "青い|").get.items.map(_.label), Vector("blue_hair", "blue_eyes"))
    assertEquals(applied(d, "青い髪|"), "blue hair")
    assert(run(d, "blue h|").get.items.head.detail.startsWith("青い髪"))
    val legacy = CompletionEngine.parseTranslations(Iterator("blue_hair,9,青い髪"), legacy = true)
    assertEquals(legacy("blue hair"), "青い髪")
    intercept[IllegalArgumentException](CompletionEngine.parseTranslations(Iterator("blue_hair"), legacy = false))
    intercept[IllegalArgumentException](CompletionEngine.parseTranslations(Iterator.empty, legacy = false))
  }
  test("extra file: meta text, ordering before and after") {
    val extra = CompletionEngine.parseExtra(Iterator("masterpiece,0,Quality tag,", "blue_sky,0,,"))
    val d = data.copy(extra = extra)
    assertEquals(run(d, "blue|").get.items.map(_.label), Vector("blue_hair", "blue_eyes", "blue_sky"))
    assertEquals(run(d, "blue|", cfg.copy(extraAfter = false)).get.items.map(_.label), Vector("blue_sky", "blue_hair", "blue_eyes"))
    assertEquals(run(d, "mast|").get.items.head.detail, "Quality tag  category 0")
    assertEquals(run(d, "blue_s|").get.items.head.detail, "Custom tag  category 0")
    assertEquals(run(d, "blue_s|").get.items.head.kind, SuggestionKind.Extra)
  }
  test("chants JSON parsing and validation") {
    val chants = CompletionEngine.parseChants("""[{"name":"A","terms":"x,y","content":"c1","color":2},{"name":"B","content":"c2"}]""")
    assertEquals(chants.map(_.name), Vector("A", "B"))
    assertEquals(chants(1).color, 0)
    assertEquals(chants(1).colorSpecified, false)
    assertEquals(run(data.copy(chants = chants), "<c:b|").get.items.head.category, None)
    intercept[IllegalArgumentException](CompletionEngine.parseChants("""[{"name":"A"}]"""))
    intercept[IllegalArgumentException](CompletionEngine.parseChants("[]"))
    intercept[Exception](CompletionEngine.parseChants("{"))
  }
  test("tag and chant suggestions carry their source category without changing insertion") {
    val parsed = TagCompletion.parse(Iterator("name,category,count,aliases", "azure_hair,4,20,bluehair", "landscape,0,10,"))
    val d = data.copy(tags = parsed, chants = Vector(Chant("Bright", "light", "sunlit sky", 3)))
    val tag = run(d, "bluehair|").get.items.head
    assert(tag.detail.contains("category 4"))
    assertEquals(tag.insert(), "azure hair")
    val chant = run(d, "<c:bright|").get.items.head
    assert(chant.detail.contains("category 3"))
    assertEquals(chant.insert(), "sunlit sky")
    assertEquals(tag.category, Some(4))
    assertEquals(chant.category, Some(3))
    val extra = CompletionEngine.parseExtra(Iterator("extra_tag,1,custom,"))
    assertEquals(run(d.copy(extra = extra), "extra|").get.items.head.category, Some(1))
    assert(run(d.copy(extra = extra), "extra|").get.items.head.detail.contains("category 1"))
    val malformed = TagCompletion.parse(Iterator("a,not-a-number,1,", "b,99,1,"))
    assertEquals(malformed.find(_.name == "a").get.category, None)
    assertEquals(malformed.find(_.name == "b").get.category, Some(99))
  }
  test("plain tags still work with weights and ignore finished network tags") {
    assertEquals(applied(data, "(blue h|:1.2), x"), "(blue hair:1.2), x")
    assertEquals(run(data, "<lora:styleA:1.0> blue|").map(_.items.head.label), Some("blue_hair"))
  }
  test("settings: an old settings.json without tagComplete loads with defaults and new options round-trip") {
    val home = Files.createTempDirectory("tac-settings")
    val file = home.resolve("settings.json")
    Files.writeString(file, """{"tag_autocomplete_csv": "tags.csv"}""")
    val old = sdfx.core.config.Settings.load(file)
    assertEquals(old.tagComplete, TagCompleteSettings())
    assertEquals(old.tagAutocompleteCsv, Some("tags.csv"))
    val changed = old.copy(tagComplete = TagCompleteSettings(translationCsv = Some("t.csv"), maxResults = 7, appendComma = true, extraAfter = false,
      categoryColorMap = """{"danbooru":{"4":["red","blue"]}}""", wildcardPathMode = "next-folder"))
    sdfx.core.config.Settings.save(file, changed)
    assertEquals(sdfx.core.config.Settings.load(file).tagComplete, changed.tagComplete)
  }
