package sdfx.plugins

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.plugins.booru.*

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/** Booru prompt: post parsing for four API families, link/md5/file references, filters, tag clean-up, chaos mode,
  * random picks, the script run (prompt composition, seeds, infotext, img2img download, LoRAnado) and the real JDK
  * client against a local HTTP server. Requests are answered by a canned booru; nothing leaves the machine.
  */
class BooruPromptSuite extends munit.FunSuite:

  // ------------------------------------------------------------------------------------------------ fixtures
  private def danbooruPost(id: Int, rating: String = "g", score: Int = 10, ext: String = "jpg", extra: String = "") =
    s"""{"id":$id,"md5":"${"%032x".format(id)}","rating":"$rating","score":$score,"file_ext":"$ext",
       |"tag_string_general":"1girl solo long_hair blue_eyes watermark hatsune_miku_(vocaloid)$extra","tag_string_character":"hatsune_miku",
       |"tag_string_copyright":"vocaloid","tag_string_artist":"some_artist","tag_string_meta":"highres",
       |"file_url":"https://cdn.donmai.us/original/$id.$ext","large_file_url":"https://cdn.donmai.us/sample/$id.jpg"}""".stripMargin

  private def e621Post(id: Int, rating: String = "s") =
    s"""{"id":$id,"rating":"$rating","score":{"up":3,"down":0,"total":3},
       |"tags":{"general":["solo","blue_fur"],"species":["canine"],"character":["rex"],"copyright":[],"artist":["fox_artist"],
       |"invalid":[],"lore":["lore_tag"],"meta":["hi_res"]},
       |"file":{"width":10,"height":10,"ext":"png","md5":"${"%032x".format(id)}","url":"https://static1.e621.net/data/$id.png"},
       |"sample":{"url":"https://static1.e621.net/sample/$id.jpg"}}""".stripMargin

  private def gelbooruPost(id: Int, rating: String = "general", score: Int = 5) =
    s"""{"id":$id,"md5":"${"%032x".format(id)}","rating":"$rating","score":$score,"tags":"1girl smile it&#039;s_a_tag (parens)",
       |"file_url":"https://img3.gelbooru.com/images/$id.png","sample_url":"https://img3.gelbooru.com/samples/$id.jpg"}""".stripMargin

  private def moebooruPost(id: Int, rating: String = "s") =
    s"""{"id":$id,"md5":"${"%032x".format(id)}","rating":"$rating","score":7,"tags":"scenery sky tree","file_url":"https://konachan.net/image/$id.jpg","file_ext":"jpg"}"""

  /** Canned responder: records URLs, answers by function. */
  private final class Fake(answer: String => String, image: () => Array[Byte] = () => png(40, 20)) extends BooruTransport:
    val urls = ArrayBuffer[String]()
    val downloads = ArrayBuffer[String]()
    def text(url: String, headers: Map[String, String]): String =
      urls += url
      answer(url)
    def bytes(url: String, headers: Map[String, String], maxBytes: Int): Array[Byte] =
      downloads += url
      image()

  private def png(w: Int, h: Int): Array[Byte] =
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val out = new ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    out.toByteArray

  private val plugin = new BooruPromptPlugin
  private def values(kv: (String, String)*) = Values(Values.defaults(plugin.controls).map ++ kv)
  private val root: Path = Files.createTempDirectory("booru-root")
  private def run(p: BooruPromptPlugin, ctx: FakeContext, base: GenerationParams, v: Values) = p.run(base, v, ctx)
  private val base = GenerationParams(prompt = "masterpiece", negativePrompt = "lowres, bad hands", seed = 50, width = 64, height = 64)

  private def firstPage(u: String) = u.endsWith("page=1") || u.contains("page=1&")
  private def danbooruPage(ids: Seq[Int], rating: String = "g") = ids.map(danbooruPost(_, rating)).mkString("[", ",", "]")

  // ------------------------------------------------------------------------------------------------ references
  test("post references: id, id:, md5, links of all families, md5 file names") {
    assertEquals(PostRef.parse("123"), (None, PostRef.Id(123)))
    assertEquals(PostRef.parse("id:5298308"), (None, PostRef.Id(5298308)))
    val md5 = "9d27c7ff0afc26f47bf898c7090f151f"
    assertEquals(PostRef.parse(md5.toUpperCase), (None, PostRef.Md5(md5)))
    assertEquals(PostRef.parse("C:\\pics\\" + md5 + ".png"), (None, PostRef.Md5(md5)))
    assertEquals(PostRef.parse("/home/me/" + md5 + ".jpg")._2, PostRef.Md5(md5))
    assertEquals(PostRef.parse("https://danbooru.donmai.us/posts/5298308?q=1girl+solo"), (Some(Sites.byName("Danbooru")), PostRef.Id(5298308)))
    assertEquals(PostRef.parse("https://e621.net/posts/8595"), (Some(Sites.byName("e621")), PostRef.Id(8595)))
    assertEquals(PostRef.parse("https://gelbooru.com/index.php?page=post&s=view&id=9876&tags=x"), (Some(Sites.byName("Gelbooru")), PostRef.Id(9876)))
    assertEquals(PostRef.parse("https://konachan.net/post/show/410344/animal_ears-ass"), (Some(Sites.byName("Konachan")), PostRef.Id(410344)))
    assertEquals(PostRef.parse("https://rule34.xxx/index.php?page=post&s=view&id=55"), (Some(Sites.byName("Rule34")), PostRef.Id(55)))
    assertEquals(PostRef.parse("https://example.org/img/" + md5 + ".jpg"), (None, PostRef.Md5(md5)))
    intercept[IllegalArgumentException](PostRef.parse("sample_" + md5 + ".jpg"))
    intercept[IllegalArgumentException](PostRef.parse("hello.png"))
    intercept[IllegalArgumentException](PostRef.parse("  "))
    intercept[IllegalArgumentException](PostRef.parse("https://danbooru.donmai.us/wiki_pages/help"))
    intercept[IllegalArgumentException](PostRef.parse("0"))
  }

  // ------------------------------------------------------------------------------------------------ parsing
  test("Danbooru posts keep their tag categories, rating and picture links") {
    val c = new BooruClient(new Fake(_ => "[" + danbooruPost(7, "s", 99) + "]"))
    val p = c.page(Sites.byName("Danbooru"), Seq("1girl"), 1, Creds()).head
    assertEquals(p.id, 7L)
    assertEquals(p.rating, "sensitive")
    assertEquals(p.score, 99)
    assertEquals(p.tags.filter(_._1 == Cat.Artist).map(_._2), Seq("some_artist"))
    assertEquals(p.tags.filter(_._1 == Cat.Character).map(_._2), Seq("hatsune_miku"))
    assertEquals(p.sampleUrl, Some("https://cdn.donmai.us/sample/7.jpg"))
    assertEquals(p.ext, "jpg")
  }

  test("e621 posts: nested tags, safe rating means general, score.total") {
    val c = new BooruClient(new Fake(_ => s"""{"post":${e621Post(8595)}}"""))
    val p = c.post(Sites.byName("e621"), PostRef.Id(8595), Creds())
    assertEquals(p.rating, "general")
    assertEquals(p.score, 3)
    assertEquals(p.tags.filter(_._1 == Cat.Species).map(_._2), Seq("canine"))
    assertEquals(p.tags.filter(_._1 == Cat.Lore).map(_._2), Seq("lore_tag"))
    assertEquals(p.md5, "%032x".format(8595))
    val list = new BooruClient(new Fake(_ => s"""{"posts":[${e621Post(1)},${e621Post(2, "e")}]}"""))
    assertEquals(list.page(Sites.byName("e621"), Nil, 1, Creds()).map(_.rating), Seq("general", "explicit"))
  }

  test("Gelbooru style: wrapped object, plain array, empty body, HTML escapes and hash field") {
    val gel = new BooruClient(new Fake(_ => s"""{"@attributes":{"limit":1,"count":1},"post":[${gelbooruPost(3)}]}"""))
    val p = gel.page(Sites.byName("Safebooru"), Seq("a"), 1, Creds()).head
    assertEquals(p.tags.map(_._2), Seq("1girl", "smile", "it's_a_tag", "(parens)"))
    val single = new BooruClient(new Fake(_ => s"""{"post":${gelbooruPost(4)}}"""))
    assertEquals(single.page(Sites.byName("Safebooru"), Nil, 1, Creds()).map(_.id), Seq(4L))
    val r34 = new BooruClient(new Fake(_ => """[{"id":9,"hash":"abc","rating":"questionable","score":1,"tags":"a b","file_url":"https://x.test/a.png"}]"""))
    assertEquals(r34.page(Sites.byName("Xbooru"), Nil, 1, Creds()).head.md5, "abc")
    assertEquals(new BooruClient(new Fake(_ => "")).page(Sites.byName("Xbooru"), Nil, 1, Creds()), Nil)
    assertEquals(new BooruClient(new Fake(_ => """{"@attributes":{"limit":100,"offset":0,"count":0}}""")).page(Sites.byName("Safebooru"), Nil, 1, Creds()), Nil)
  }

  test("Moebooru posts map s/q/e to general/questionable/explicit") {
    val c = new BooruClient(new Fake(_ => "[" + moebooruPost(1) + "," + moebooruPost(2, "q") + "," + moebooruPost(3, "e") + "]"))
    assertEquals(c.page(Sites.byName("Konachan"), Nil, 1, Creds()).map(_.rating), Seq("general", "questionable", "explicit"))
  }

  test("non-JSON and failure replies are explained without the query string") {
    val c = new BooruClient(new Fake(_ => "<html>Cloudflare</html>"))
    val e = intercept[IllegalArgumentException](c.page(Sites.byName("Danbooru"), Nil, 1, Creds("me", "secretkey")))
    assert(e.getMessage.contains("JSON"), e.getMessage)
    assert(!e.getMessage.contains("secretkey"))
    val missing = intercept[IllegalArgumentException](new BooruClient(new Fake(_ => "[]")).post(Sites.byName("Danbooru"), PostRef.Id(1), Creds()))
    assert(missing.getMessage.contains("not found"), missing.getMessage)
    val denied = intercept[IllegalArgumentException](new BooruClient(new Fake(_ => "[]")).page(Sites.byName("Gelbooru"), Nil, 1, Creds()))
    assert(denied.getMessage.contains("API key"), denied.getMessage)
  }

  // ------------------------------------------------------------------------------------------------ URLs
  test("request URLs per API family, credentials and pages") {
    val fake = new Fake(_ => "[]")
    val c = new BooruClient(fake)
    def tryCall(f: => Any): Unit = try f catch case _: IllegalArgumentException => ()
    tryCall(c.page(Sites.byName("Danbooru"), Seq("1girl", "order:score"), 3, Creds("me", "k y")))
    tryCall(c.page(Sites.byName("e621"), Seq("fox"), 2, Creds()))
    tryCall(c.page(Sites.byName("Gelbooru"), Seq("1girl"), 4, Creds("123", "KEY")))
    tryCall(c.page(Sites.byName("Konachan"), Seq("sky"), 5, Creds()))
    tryCall(c.post(Sites.byName("Danbooru"), PostRef.Id(77), Creds()))
    tryCall(c.post(Sites.byName("Danbooru"), PostRef.Md5("a" * 32), Creds()))
    tryCall(c.post(Sites.byName("Safebooru"), PostRef.Id(5), Creds()))
    tryCall(c.post(Sites.byName("Konachan"), PostRef.Id(6), Creds()))
    val u = fake.urls.toSeq
    assertEquals(u(0), "https://danbooru.donmai.us/posts.json?tags=1girl+order%3Ascore&limit=100&page=3&login=me&api_key=k+y")
    assertEquals(u(1), "https://e621.net/posts.json?tags=fox&limit=100&page=2")
    assertEquals(u(2), "https://gelbooru.com/index.php?page=dapi&s=post&q=index&json=1&limit=100&pid=3&tags=1girl&user_id=123&api_key=KEY")
    assertEquals(u(3), "https://konachan.net/post.json?limit=100&page=5&tags=sky")
    assertEquals(u(4), "https://danbooru.donmai.us/posts/77.json?")
    assertEquals(u(5), s"https://danbooru.donmai.us/posts.json?tags=md5%3A${"a" * 32}&limit=1")
    assertEquals(u(6), "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1&id=5")
    assertEquals(u(7), "https://konachan.net/post.json?limit=1&tags=id%3A6")
  }

  // ------------------------------------------------------------------------------------------------ tags
  test("tag lists: commas, lines, booru style spaces; globs") {
    assertEquals(BooruTags.parseList("1girl, solo , long hair"), Seq("1girl", "solo", "long_hair"))
    assertEquals(BooruTags.parseList("1girl solo blue_eyes"), Seq("1girl", "solo", "blue_eyes"))
    assertEquals(BooruTags.parseList("long hair\nblue eyes"), Seq("long_hair", "blue_eyes"))
    assertEquals(BooruTags.parseList("  "), Nil)
    assert(BooruTags.globMatches("*hair", "Long Hair"))
    assert(BooruTags.globMatches("long_*", "long_hair"))
    assert(!BooruTags.globMatches("hair", "long_hair"))
    assert(BooruTags.globMatches("a*b*c", "a_x_b_y_c"))
    assert(BooruTags.globMatches("1girl", "1GIRL"))
    assert(!BooruTags.globMatches("a.c", "abc")) // regex characters stay literal
  }

  private val danbooruSite = Sites.byName("Danbooru")
  private def dpost = new BooruClient(new Fake(_ => "[" + danbooruPost(1, extra = " school_uniform blue_sky red_hair") + "]")).page(danbooruSite, Nil, 1, Creds()).head

  test("tag pipeline: default categories, bad tags, prefix, underscores, brackets, append") {
    val o = BooruTags.Options()
    val t = BooruTags.tags(Seq(dpost), o, new Random(1))
    assertEquals(t.take(3), Seq("hatsune miku", "vocaloid", "1girl"))
    assert(!t.contains("watermark"))
    assert(!t.contains("some artist"))
    assert(!t.contains("highres"))
    assert(t.contains("hatsune miku \\(vocaloid\\)"), t.toString)
    val artist = BooruTags.tags(Seq(dpost), o.copy(categories = Set(Cat.Artist, Cat.General), artistPrefix = "by "), new Random(1))
    assertEquals(artist.head, "by some artist")
    val raw = BooruTags.tags(Seq(dpost), o.copy(underscoresToSpaces = false, escapeBrackets = false, removeBad = false, appendTags = Seq("best_quality", "solo")), new Random(1))
    assert(raw.contains("watermark") && raw.contains("hatsune_miku_(vocaloid)"))
    assertEquals(raw.takeRight(1), Seq("best_quality")) // "solo" was already present: no duplicate
    assertEquals(raw.count(_ == "solo"), 1)
  }

  test("tag pipeline: remove patterns, limit, max, shuffle determinism, rating category, emoticons") {
    val t = BooruTags.tags(Seq(dpost), BooruTags.Options(removePatterns = Seq("*hair", "1girl")), new Random(1))
    assert(!t.exists(_.contains("hair")) && !t.contains("1girl"), t.toString)
    val all = BooruTags.tags(Seq(dpost), BooruTags.Options(), new Random(1))
    val half = BooruTags.tags(Seq(dpost), BooruTags.Options(limitPercent = 50), new Random(1))
    assertEquals(half.size, math.ceil(all.size / 2.0).toInt)
    assertEquals(BooruTags.tags(Seq(dpost), BooruTags.Options(maxTags = 2), new Random(1)), all.take(2))
    val s1 = BooruTags.tags(Seq(dpost), BooruTags.Options(shuffle = true), new Random(7))
    assertEquals(s1, BooruTags.tags(Seq(dpost), BooruTags.Options(shuffle = true), new Random(7)))
    assertEquals(s1.toSet, all.toSet)
    assert(BooruTags.tags(Seq(dpost), BooruTags.Options(categories = Set(Cat.General, Cat.Rating)), new Random(1)).contains("rating:general"))
    assertEquals(BooruTags.format("^_^", BooruTags.Options()), "^_^")
    assertEquals(BooruTags.format(">_<", BooruTags.Options()), ">_<")
    assertEquals(BooruTags.format("long_hair", BooruTags.Options()), "long hair")
    // boorus without categories ignore the category selection
    val g = new BooruClient(new Fake(_ => "[" + gelbooruPost(1) + "]")).page(Sites.byName("Safebooru"), Nil, 1, Creds()).head
    assertEquals(BooruTags.tags(Seq(g), BooruTags.Options(categories = Set(Cat.Meta)), new Random(1)).size, 4)
  }

  test("background and colour changes") {
    val rem = BooruTags.tags(Seq(dpost), BooruTags.Options(background = "Remove"), new Random(1))
    assert(!rem.contains("blue sky"), rem.toString)
    val white = BooruTags.tags(Seq(dpost), BooruTags.Options(background = "Add white background"), new Random(1))
    assertEquals(white.takeRight(2), Seq("simple background", "white background"))
    assert(!white.contains("blue sky"))
    val noColor = BooruTags.tags(Seq(dpost), BooruTags.Options(color = "Remove"), new Random(1))
    assert(!noColor.contains("red hair") && noColor.contains("blue eyes") == false, noColor.toString)
    val re = BooruTags.tags(Seq(dpost), BooruTags.Options(color = "Randomize"), new Random(3))
    assert(!re.contains("red hair") && re.exists(_.endsWith(" hair")) && re.exists(_.endsWith(" eyes")), re.toString)
    assertEquals(re, BooruTags.tags(Seq(dpost), BooruTags.Options(color = "Randomize"), new Random(3)))
  }

  test("mixing posts merges tags without duplicates") {
    val a = dpost
    val b = new BooruClient(new Fake(_ => "[" + gelbooruPost(2) + "]")).page(Sites.byName("Safebooru"), Nil, 1, Creds()).head
    val t = BooruTags.tags(Seq(a, b), BooruTags.Options(), new Random(1))
    assertEquals(t.count(_ == "1girl"), 1)
    assert(t.contains("smile") && t.contains("hatsune miku"))
  }

  test("chaos modes") {
    val booru = (1 to 10).map("t" + _)
    val neg = Seq("n1", "n2", "n3", "n4")
    assertEquals(BooruTags.chaos("Off", 0.5, booru, neg, new Random(1)), (booru, Nil, Nil))
    val (pos, ng, back) = BooruTags.chaos("Chaos", 0.5, booru, neg, new Random(1))
    assertEquals(ng.size, 5)
    assertEquals(back.size, 2)
    assertEquals((pos.toSet -- back).union(ng.toSet), booru.toSet)
    val (lp, ln, lb) = BooruTags.chaos("Less chaos", 0.3, booru, neg, new Random(1))
    assertEquals((lp.size, ln.size, lb), (7, 3, Nil))
    assertEquals(BooruTags.chaos("Negative mode", 0.1, booru, neg, new Random(1)), (Nil, booru, Nil))
    assertEquals(BooruTags.chaos("Chaos", 0, booru, neg, new Random(1)), (booru, Nil, Nil))
    assertEquals(BooruTags.chaos("Chaos", 1, booru, neg, new Random(1))._2.size, 10)
    assertEquals(BooruTags.join("a, b,", "c"), "a, b, c")
    assertEquals(BooruTags.join("", "c"), "c")
    assertEquals(BooruTags.prepend("a, b", "c"), "c, a, b")
  }

  // ------------------------------------------------------------------------------------------------ random picks
  private def query(site: String = "Danbooru", rating: String = "General", tags: Seq[String] = Seq("1girl"), pages: Int = 5, order: Order = Order.Random,
      creds: Creds = Creds(), cache: Boolean = false) =
    Query(Sites.byName(site), tags, rating, pages, true, order, creds, cache)

  test("random pick: page range, rating filter, animation filter and the server side rating tag") {
    val fake = new Fake(u =>
      if firstPage(u) || u.contains("page=2&") || u.endsWith("page=2") then "[" + danbooruPost(1, "e") + "," + danbooruPost(2, "g", ext = "webm") + "," + danbooruPost(3, "g") + "]" else "[]")
    val c = new BooruClient(fake)
    val q = query(pages = 6, tags = Seq("1girl"))
    val picks = (0 until 30).map(i => c.random(q, new Random(i)).id).toSet
    assertEquals(picks, Set(3L)) // the explicit and the animated one never come back
    assert(fake.urls.forall(_.contains("tags=1girl+rating%3Ag")), fake.urls.head)
    assert(fake.urls.exists(firstPage))
    assert(fake.urls.map(u => "page=(\\d+)".r.findFirstMatchIn(u).get.group(1).toInt).forall(_ <= 6))
  }

  test("anonymous Danbooru with two search tags drops the rating tag and filters locally") {
    val fake = new Fake(_ => "[" + danbooruPost(1, "e") + "," + danbooruPost(2, "g") + "]")
    new BooruClient(fake).random(query(tags = Seq("a", "b")), new Random(1))
    assert(!fake.urls.head.contains("rating"), fake.urls.head)
    val withKey = new Fake(_ => "[" + danbooruPost(2, "g") + "]")
    new BooruClient(withKey).random(query(tags = Seq("a", "b"), creds = Creds("me", "k")), new Random(1))
    assert(withKey.urls.head.contains("rating%3Ag"), withKey.urls.head)
  }

  test("rating tags per site and the combined filter") {
    val c = new BooruClient(new Fake(_ => "[]"))
    assertEquals(c.ratingTag(query("e621", "General")), Some("rating:s"))
    assertEquals(c.ratingTag(query("Gelbooru", "General")), Some("rating:general"))
    assertEquals(c.ratingTag(query("Konachan", "Explicit")), Some("rating:e"))
    assertEquals(c.ratingTag(query("Danbooru", "Any")), None)
    assertEquals(c.ratingTag(query("Safebooru", "General")), None)
    assert(c.accepts(query(rating = "General + Sensitive"), c.posts(danbooruSite, ujson.read("[" + danbooruPost(1, "s") + "]")).head))
    assert(!c.accepts(query(rating = "General"), c.posts(danbooruSite, ujson.read("[" + danbooruPost(1, "s") + "]")).head))
  }

  test("empty far pages shrink the range; hopeless searches fail with the query in the message") {
    val onlyFirst = new Fake(u => if firstPage(u) then "[" + danbooruPost(11) + "]" else "[]")
    val c = new BooruClient(onlyFirst)
    assertEquals(c.random(query(pages = 500), new Random(4)).id, 11L)
    val e = intercept[IllegalArgumentException](new BooruClient(new Fake(_ => "[]")).random(query(tags = Seq("zzz")), new Random(1)))
    assert(e.getMessage.contains("zzz") && e.getMessage.contains("nothing"), e.getMessage)
    val filtered = intercept[IllegalArgumentException](new BooruClient(new Fake(_ => "[" + danbooruPost(1, "e") + "]")).random(query(), new Random(1)))
    assert(filtered.getMessage.contains("No Danbooru post matched"), filtered.getMessage)
  }

  test("score order prefers the front of the sorted page; same seed gives the same post") {
    val page = (1 to 20).map(i => danbooruPost(i, score = i * 10)).mkString("[", ",", "]")
    val c = new BooruClient(new Fake(_ => page))
    val high = (0 until 200).map(i => c.random(query(order = Order.HighScore, pages = 1), new Random(i)).score)
    val low = (0 until 200).map(i => c.random(query(order = Order.LowScore, pages = 1), new Random(i)).score)
    assert(high.sum.toDouble / high.size > 130, high.sum.toDouble / high.size)
    assert(low.sum.toDouble / low.size < 80, low.sum.toDouble / low.size)
    assertEquals(c.random(query(pages = 1), new Random(9)).id, c.random(query(pages = 1), new Random(9)).id)
  }

  test("cache: a page is fetched once per run, and across runs only with Use cache") {
    BooruCache.clear()
    val fake = new Fake(_ => danbooruPage(1 to 3))
    val c = new BooruClient(fake)
    (0 until 5).foreach(i => c.random(query(pages = 1), new Random(i)))
    assertEquals(fake.urls.size, 1)
    new BooruClient(fake).random(query(pages = 1), new Random(1))
    assertEquals(fake.urls.size, 2)
    new BooruClient(fake).random(query(pages = 1, cache = true), new Random(1))
    assertEquals(fake.urls.size, 3)
    new BooruClient(fake).random(query(pages = 1, cache = true), new Random(2))
    assertEquals(fake.urls.size, 3)
    BooruCache.clear()
  }

  // ------------------------------------------------------------------------------------------------ config
  test("configuration validation") {
    def cfg(kv: (String, String)*) = BooruPromptPlugin.config(values(kv*), root)
    intercept[IllegalArgumentException](cfg("booru" -> "Nowhere"))
    intercept[IllegalArgumentException](cfg("mode" -> "Specific post(s)"))
    intercept[IllegalArgumentException](cfg("rating" -> "Mild"))
    intercept[IllegalArgumentException](cfg("max_pages" -> "0"))
    intercept[IllegalArgumentException](cfg("limit_percent" -> "0"))
    intercept[IllegalArgumentException](cfg("chaos_amount" -> "1.5"))
    intercept[IllegalArgumentException](cfg("lora_enabled" -> "true", "lora_min" -> "1", "lora_max" -> "0.2"))
    intercept[IllegalArgumentException](cfg("lora_enabled" -> "true", "lora_weights" -> "a,b"))
    intercept[IllegalArgumentException](cfg("search_file" -> "../secret.txt"))
    intercept[IllegalArgumentException](cfg("search_file" -> "missing.txt"))
    val c = cfg("search" -> "1girl, solo", "booru" -> "e621", "cat_artist" -> "true", "mix" -> "true", "mix_amount" -> "3")
    assertEquals(c.query.tags, Seq("1girl", "solo"))
    assertEquals(c.site.name, "e621")
    assert(c.tags.categories.contains(Cat.Artist))
    assertEquals(c.mix, 3)
    assertEquals(c.query.rating, "General")
  }

  test("tag files from booru/search and booru/remove") {
    val r = Files.createTempDirectory("booru-files")
    Files.createDirectories(r.resolve("booru/search"))
    Files.createDirectories(r.resolve("booru/remove"))
    Files.writeString(r.resolve("booru/search/pairs.txt"), "# comment\n1girl, solo\nlong hair\n")
    Files.writeString(r.resolve("booru/remove/junk.txt"), "*hair\nsmile\n")
    val c = BooruPromptPlugin.config(values("search" -> "scenery", "search_file" -> "pairs.txt", "remove_file" -> "junk.txt", "remove_tags" -> "sky"), r)
    assertEquals(c.query.tags, Seq("scenery", "1girl", "solo", "long_hair"))
    assertEquals(c.tags.removePatterns, Seq("sky", "*hair", "smile"))
  }

  // ------------------------------------------------------------------------------------------------ the run
  private def pageOnly(ids: Seq[Int]) = new Fake(u => if firstPage(u) then danbooruPage(ids) else "[]")

  test("random run: one post per image, tags after the prompt, seeds, infotext, negative untouched") {
    val fake = pageOnly(Seq(1, 2, 3, 4, 5, 6))
    val p = new BooruPromptPlugin(fake, () => root)
    val ctx = new FakeContext()
    val out = run(p, ctx, base.copy(batchSize = 2, nIter = 2), values("search" -> "1girl", "max_pages" -> "1"))
    assertEquals(out.images.size, 4)
    val gens = ctx.generated
    assertEquals(gens.map(_.seed), Seq(50L, 51L, 52L, 53L))
    assert(gens.forall(g => g.batchSize == 1 && g.nIter == 1))
    gens.foreach { g =>
      assert(g.prompt.startsWith("masterpiece, "), g.prompt)
      assert(g.prompt.contains("hatsune miku"), g.prompt)
      assertEquals(g.negativePrompt, "lowres, bad hands")
      assertEquals(g.extra("Booru"), "Danbooru")
      assert(g.extra("Booru posts").toIntOption.isDefined)
      assertEquals(g.extra("Booru search"), "1girl")
    }
    assert(gens.map(_.extra("Booru posts")).toSet.size > 1, "different posts for different images")
    assert(!gens.exists(_.extra.values.exists(_.contains("secret"))))
    // reproducible with the same seed
    val ctx2 = new FakeContext()
    run(new BooruPromptPlugin(pageOnly(Seq(1, 2, 3, 4, 5, 6)), () => root), ctx2, base.copy(batchSize = 2, nIter = 2), values("search" -> "1girl", "max_pages" -> "1"))
    assertEquals(ctx2.generated.map(_.prompt), gens.map(_.prompt))
  }

  test("same prompt for all images keeps the batch; before and replace positions") {
    val p = new BooruPromptPlugin(pageOnly(Seq(1, 2, 3)), () => root)
    val ctx = new FakeContext()
    run(p, ctx, base.copy(batchSize = 2, nIter = 3), values("max_pages" -> "1", "same_prompt" -> "true"))
    assertEquals(ctx.generated.size, 1)
    assertEquals((ctx.generated.head.batchSize, ctx.generated.head.nIter, ctx.generated.head.seed), (2, 3, 50L))
    val before = new FakeContext()
    run(p, before, base, values("max_pages" -> "1", "prompt_position" -> "Before prompt"))
    assert(before.generated.head.prompt.endsWith(", masterpiece"), before.generated.head.prompt)
    val replace = new FakeContext()
    run(p, replace, base, values("max_pages" -> "1", "prompt_position" -> "Replace prompt"))
    assert(!replace.generated.head.prompt.contains("masterpiece"))
    val sameSeed = new FakeContext()
    run(p, sameSeed, base.copy(nIter = 3), values("max_pages" -> "1", "same_seed" -> "true"))
    assertEquals(sameSeed.generated.map(_.seed), Seq(50L, 50L, 50L))
  }

  test("random seed (-1) is fixed once for the run") {
    val p = new BooruPromptPlugin(pageOnly(Seq(1, 2, 3)), () => root)
    val ctx = new FakeContext()
    run(p, ctx, base.copy(seed = -1, nIter = 3), values("max_pages" -> "1"))
    val seeds = ctx.generated.map(_.seed)
    assert(seeds.forall(_ >= 0))
    assertEquals(seeds.map(_ - seeds.head), Seq(0L, 1L, 2L))
  }

  test("specific posts: link beats the selected booru, ids cycle over the images, md5 and mix") {
    val fake = new Fake(u =>
      if u.contains("e621.net/posts/8595.json") then s"""{"post":${e621Post(8595)}}"""
      else if u.contains("danbooru.donmai.us/posts/12.json") then danbooruPost(12)
      else if u.contains("tags=md5%3A") then "[" + danbooruPost(77) + "]"
      else "[]")
    val p = new BooruPromptPlugin(fake, () => root)
    val ctx = new FakeContext()
    run(p, ctx, base.copy(nIter = 3), values("mode" -> "Specific post(s)", "post" -> ("https://e621.net/posts/8595?q=fox\n12\n" + "c" * 32)))
    assertEquals(ctx.generated.map(_.extra("Booru")), Seq("e621", "Danbooru", "Danbooru"))
    assertEquals(ctx.generated.map(_.extra("Booru posts")), Seq("8595", "12", "77"))
    assert(ctx.generated.head.prompt.contains("canine"), ctx.generated.head.prompt)
    assert(!ctx.generated.head.prompt.contains("fox artist"))
    assert(!ctx.generated.head.extra.contains("Booru search"))
    val mixed = new FakeContext()
    run(p, mixed, base, values("mode" -> "Specific post(s)", "post" -> "12, 12", "mix" -> "true", "mix_amount" -> "2"))
    assertEquals(mixed.generated.head.extra("Booru posts"), "12 12")
    val none = new FakeContext()
    intercept[IllegalArgumentException](run(p, none, base, values("mode" -> "Specific post(s)", "post" -> "999999")))
    assertEquals(none.generated, Nil)
  }

  test("mix prompts takes several distinct posts per image") {
    val p = new BooruPromptPlugin(pageOnly(Seq(1, 2, 3)), () => root)
    val ctx = new FakeContext()
    run(p, ctx, base, values("max_pages" -> "1", "mix" -> "true", "mix_amount" -> "3"))
    assertEquals(ctx.generated.head.extra("Booru posts").split(" ").toSet, Set("1", "2", "3"))
    val few = new BooruPromptPlugin(pageOnly(Seq(1)), () => root)
    val ctx2 = new FakeContext()
    run(few, ctx2, base, values("max_pages" -> "1", "mix" -> "true", "mix_amount" -> "4"))
    assertEquals(ctx2.generated.head.extra("Booru posts"), "1") // a single result still works
  }

  test("chaos and negative mode edit both prompts") {
    val p = new BooruPromptPlugin(pageOnly(Seq(1)), () => root)
    val neg = new FakeContext()
    run(p, neg, base, values("max_pages" -> "1", "chaos_mode" -> "Negative mode"))
    assert(neg.generated.head.prompt == "masterpiece", neg.generated.head.prompt)
    assert(neg.generated.head.negativePrompt.startsWith("lowres, bad hands, "), neg.generated.head.negativePrompt)
    assert(neg.generated.head.negativePrompt.contains("hatsune miku"))
    val chaos = new FakeContext()
    run(p, chaos, base, values("max_pages" -> "1", "chaos_mode" -> "Chaos", "chaos_amount" -> "0.5"))
    val g = chaos.generated.head
    val booruNeg = g.negativePrompt.split(", ").toSet -- Set("lowres", "bad hands")
    assert(booruNeg.nonEmpty && booruNeg.exists(_ != ""), g.negativePrompt)
    val less = new FakeContext()
    run(p, less, base, values("max_pages" -> "1", "chaos_mode" -> "Less chaos", "chaos_amount" -> "0.5"))
    assert(less.generated.head.negativePrompt.startsWith("lowres, bad hands"))
    assert(!less.generated.head.prompt.contains("lowres"))
    // Chaos can also hand negative tags to the positive prompt: they leave the negative prompt then
    val full = new FakeContext()
    run(p, full, base.copy(negativePrompt = "n1, n2"), values("max_pages" -> "1", "chaos_mode" -> "Chaos", "chaos_amount" -> "1"))
    assertEquals(full.generated.head.prompt.contains("n1") && full.generated.head.prompt.contains("n2"), true)
    assert(!full.generated.head.negativePrompt.contains("n1"))
  }

  test("an empty tag list after the filters is an error, not a silent empty prompt") {
    val p = new BooruPromptPlugin(pageOnly(Seq(1)), () => root)
    val ctx = new FakeContext()
    val e = intercept[IllegalArgumentException](run(p, ctx, base, values("max_pages" -> "1", "remove_tags" -> "*")))
    assert(e.getMessage.contains("No tags are left"), e.getMessage)
    assertEquals(ctx.generated, Nil)
  }

  test("img2img downloads the sample picture once per image (or once with the last-image option) and cleans up") {
    val fake = pageOnly(Seq(1, 2, 3, 4))
    val p = new BooruPromptPlugin(fake, () => root)
    val ctx = new FakeContext()
    run(p, ctx, base.copy(nIter = 3), values("max_pages" -> "1", "use_img2img" -> "true", "denoising" -> "0.6"))
    assertEquals(fake.downloads.size, 3)
    assert(fake.downloads.forall(_.contains("/sample/")), fake.downloads.toString)
    val ii = ctx.generated.map(_.img2img.get)
    assert(ii.forall(i => i.denoisingStrength == 0.6 && i.resizeMode == 1))
    assertEquals(ctx.inputs.map(i => (i._1.getWidth, i._1.getHeight)).toSet, Set((40, 20)))
    ii.foreach(i => assert(!Files.exists(java.nio.file.Paths.get(i.initImage)), "temp picture removed"))
    val fake2 = pageOnly(Seq(1, 2, 3, 4))
    val ctx2 = new FakeContext()
    run(new BooruPromptPlugin(fake2, () => root), ctx2, base.copy(nIter = 3), values("max_pages" -> "1", "use_img2img" -> "true", "last_image" -> "true", "crop_center" -> "false"))
    assertEquals(fake2.downloads.size, 1)
    assertEquals(ctx2.generated.map(_.img2img.get.initImage).distinct.size, 1)
    assertEquals(ctx2.generated.head.img2img.get.resizeMode, 0)
  }

  test("img2img refuses non-https and IP hosts and unreadable pictures") {
    val post = Post("Danbooru", 1, "m", "general", 0, Nil, Some("http://cdn.example/x.png"), None, "png")
    val ctx = new FakeContext()
    val t = new Fake(_ => "")
    intercept[IllegalArgumentException](BooruPromptPlugin.download(post, t, ctx))
    intercept[IllegalArgumentException](BooruPromptPlugin.download(post.copy(fileUrl = Some("https://127.0.0.1/x.png")), t, ctx))
    intercept[IllegalArgumentException](BooruPromptPlugin.download(post.copy(fileUrl = Some("https://localhost/x.png")), t, ctx))
    intercept[IllegalArgumentException](BooruPromptPlugin.download(post.copy(fileUrl = Some("https://[::1]/x.png")), t, ctx))
    intercept[IllegalArgumentException](BooruPromptPlugin.download(post.copy(fileUrl = None), t, ctx))
    val bad = new Fake(_ => "", () => "not an image".getBytes)
    val e = intercept[IllegalArgumentException](BooruPromptPlugin.download(post.copy(fileUrl = Some("https://cdn.example/x.webp"), ext = "webp"), bad, ctx))
    assert(e.getMessage.contains("webp"), e.getMessage)
  }

  test("LoRAnado: random LoRAs from a subfolder, custom weights, lock") {
    val fake = pageOnly(Seq(1, 2))
    val p = new BooruPromptPlugin(fake, () => root)
    val ctx = new FakeContext():
      override def choices(source: ChoiceSource): Seq[String] =
        if source == ChoiceSource.Loras then Seq("styles/a", "styles/b", "styles/c", "chars/x") else super.choices(source)
    run(p, ctx, base, values("max_pages" -> "1", "lora_enabled" -> "true", "lora_folder" -> "styles", "lora_amount" -> "2", "lora_weights" -> "0.2, 0.3"))
    val prompt = ctx.generated.head.prompt
    val tags = "<lora:(styles/[abc]):([0-9.]+)>".r.findAllMatchIn(prompt).map(m => m.group(1) -> m.group(2)).toSeq
    assertEquals(tags.size, 2)
    assertEquals(tags.map(_._2), Seq("0.2", "0.3"))
    assert(!prompt.contains("chars/x"))
    val first = tags
    val locked = new FakeContext():
      override def choices(source: ChoiceSource): Seq[String] =
        if source == ChoiceSource.Loras then Seq("styles/a", "styles/b", "styles/c", "chars/x") else super.choices(source)
    run(p, locked, base.copy(seed = 999), values("max_pages" -> "1", "lora_enabled" -> "true", "lora_folder" -> "styles", "lora_amount" -> "2", "lora_lock" -> "true"))
    val again = "<lora:(styles/[abc]):([0-9.]+)>".r.findAllMatchIn(locked.generated.head.prompt).map(m => m.group(1) -> m.group(2)).toSeq
    assertEquals(again, first)
    val empty = new FakeContext()
    intercept[IllegalArgumentException](run(p, empty, base, values("max_pages" -> "1", "lora_enabled" -> "true", "lora_folder" -> "styles")))
    val ranged = new FakeContext():
      override def choices(source: ChoiceSource): Seq[String] = if source == ChoiceSource.Loras then Seq("only") else super.choices(source)
    run(new BooruPromptPlugin(pageOnly(Seq(1)), () => root), ranged, base, values("max_pages" -> "1", "lora_enabled" -> "true", "lora_min" -> "0.5", "lora_max" -> "0.5"))
    assert(ranged.generated.head.prompt.contains("<lora:only:0.5>"), ranged.generated.head.prompt)
  }

  test("interrupting stops the batch; an interrupt before the first image generates nothing") {
    val p = new BooruPromptPlugin(pageOnly(Seq(1, 2, 3)), () => root)
    val ctx = new FakeContext(interruptAfter = 1)
    val out = run(p, ctx, base.copy(nIter = 3), values("max_pages" -> "1"))
    assertEquals(out.images.size, 1)
    assertEquals(ctx.generated.size, 1)
  }

  test("prompt actions fetch tags for the visible prompt") {
    val fake = new Fake(u => if u.contains("/posts/12.json") then danbooruPost(12) else danbooruPage(Seq(1, 2)))
    val p = new BooruPromptPlugin(fake, () => root)
    val a = p.promptActions.map(x => x.id -> x).toMap
    assert(!a("fetch_post").replace && !a("fetch_random").replace)
    val tags = a("fetch_post").text(values("post" -> "12"))
    assert(tags.startsWith("hatsune miku, vocaloid, 1girl"), tags)
    assert(a("fetch_random").text(values("max_pages" -> "1")).contains("blue eyes"))
    val e = intercept[IllegalArgumentException](a("fetch_post").text(values()))
    assert(e.getMessage.contains("Enter a post"), e.getMessage)
  }

  test("plugin surface: Script on both tabs, unique control keys, enabledBy targets exist") {
    assertEquals(plugin.id, "booru_prompt")
    assertEquals(plugin.kind, PluginKind.Script)
    assertEquals(plugin.tabs, Set(Tab.Txt2Img, Tab.Img2Img))
    val keys = plugin.controls.map(_.key)
    assertEquals(keys.distinct.size, keys.size)
    plugin.enabledBy.foreach { (k, e) => assert(keys.contains(k) && keys.contains(e), s"$k -> $e") }
    assert(StandardPlugins.scripts(() => Nil).exists(_.id == "booru_prompt"))
    // every Choice default is one of its options
    plugin.controls.collect { case c: Control.Choice => assert(c.options.contains(c.defaultValue), c.key) }
    // the stored API key never reaches generation params or logs
    val fake = pageOnly(Seq(1))
    val ctx = new FakeContext()
    run(new BooruPromptPlugin(fake, () => root), ctx, base, values("max_pages" -> "1", "user" -> "me", "api_key" -> "SECRETKEY"))
    assert(fake.urls.head.contains("api_key=SECRETKEY"))
    assert(!ctx.generated.head.toString.contains("SECRETKEY") && !ctx.logs.exists(_.contains("SECRETKEY")))
  }

  // ------------------------------------------------------------------------------------------------ real client
  test("JDK transport against a local HTTP server: body, headers, status errors, caps, retry-after, no query in errors") {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val seenUa = ArrayBuffer[String]()
    var throttled = 0
    def reply(x: HttpExchange, code: Int, body: Array[Byte], headers: (String, String)*): Unit =
      headers.foreach((k, v) => x.getResponseHeaders.add(k, v))
      x.sendResponseHeaders(code, if body.isEmpty then -1 else body.length)
      if body.nonEmpty then x.getResponseBody.write(body)
      x.close()
    server.createContext("/ok", (x: HttpExchange) => { seenUa += x.getRequestHeaders.getFirst("User-Agent"); reply(x, 200, """[{"id":1}]""".getBytes) })
    server.createContext("/forbidden", (x: HttpExchange) => reply(x, 403, "no".getBytes))
    server.createContext("/big", (x: HttpExchange) => reply(x, 200, new Array[Byte](2048)))
    server.createContext("/throttle", (x: HttpExchange) =>
      if throttled == 0 then { throttled += 1; reply(x, 429, Array.emptyByteArray, "Retry-After" -> "0") } else reply(x, 200, "again".getBytes))
    server.start()
    try
      val base = s"http://127.0.0.1:${server.getAddress.getPort}"
      assertEquals(JdkTransport.text(base + "/ok?x=1", Map.empty), """[{"id":1}]""")
      assert(seenUa.head.startsWith("StableDiffusionNUI/"), seenUa.toString)
      val e = intercept[IllegalArgumentException](JdkTransport.text(base + "/forbidden?api_key=TOPSECRET", Map.empty))
      assert(e.getMessage.contains("HTTP 403") && e.getMessage.contains("API key") && !e.getMessage.contains("TOPSECRET"), e.getMessage)
      val big = intercept[IllegalArgumentException](JdkTransport.bytes(base + "/big", Map.empty, 1024))
      assert(big.getMessage.contains("larger"), big.getMessage)
      assertEquals(JdkTransport.bytes(base + "/big", Map.empty, 4096).length, 2048)
      assertEquals(JdkTransport.text(base + "/throttle", Map.empty), "again")
      val down = intercept[IllegalArgumentException](JdkTransport.text("http://127.0.0.1:1/x?api_key=TOPSECRET", Map.empty))
      assert(down.getMessage.contains("Network error") && !down.getMessage.contains("TOPSECRET"), down.getMessage)
    finally server.stop(0)
  }
