package sdfx.plugins.booru

import java.io.ByteArrayOutputStream
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

/** Tag categories of a post. Boorus without categories (Gelbooru style, Moebooru) report everything as General. */
enum Cat:
  case General, Artist, Copyright, Character, Species, Meta, Lore, Rating

enum Api:
  case Danbooru, E621, Dapi, Moebooru

/** One supported booru. `credentials` names what the two credential fields mean for this site. */
final case class Site(name: String, host: String, api: Api, user: String, requiresKey: Boolean = false):
  def hasCategories: Boolean = api == Api.Danbooru || api == Api.E621

object Sites:
  val all: Seq[Site] = Seq(
    Site("Danbooru", "danbooru.donmai.us", Api.Danbooru, "login"),
    Site("AIBooru", "aibooru.online", Api.Danbooru, "login"),
    Site("e621", "e621.net", Api.E621, "login"),
    Site("Gelbooru", "gelbooru.com", Api.Dapi, "user_id", requiresKey = true),
    Site("Rule34", "api.rule34.xxx", Api.Dapi, "user_id", requiresKey = true),
    Site("Safebooru", "safebooru.org", Api.Dapi, "user_id"),
    Site("Xbooru", "xbooru.com", Api.Dapi, "user_id"),
    Site("Konachan", "konachan.net", Api.Moebooru, ""),
    Site("Yande.re", "yande.re", Api.Moebooru, "")
  )
  val names: Seq[String] = all.map(_.name)
  def byName(name: String): Site =
    all.find(_.name.equalsIgnoreCase(name.trim)).getOrElse(throw new IllegalArgumentException(s"Unknown booru: $name"))
  /** Site for a post link's host (`www.`, `safebooru.org`, `rule34.xxx` and similar variants included). */
  def forHost(host: String): Option[Site] =
    val h = host.toLowerCase.stripPrefix("www.").stripPrefix("api.")
    all.find(s => s.host.stripPrefix("api.") == h)

/** A booru post reduced to what prompts need. */
final case class Post(
    site: String, id: Long, md5: String, rating: String, score: Int, tags: Seq[(Cat, String)],
    fileUrl: Option[String], sampleUrl: Option[String], ext: String):
  def animated: Boolean =
    Set("mp4", "webm", "gif", "zip", "swf", "apng").contains(ext.toLowerCase) ||
      tags.exists((_, t) => t == "animated" || t == "video" || t == "animated_gif" || t == "animated_png")

final case class Creds(user: String = "", key: String = "")

/** A reference to one post: numeric id or md5. */
enum PostRef:
  case Id(id: Long)
  case Md5(md5: String)

object PostRef:
  private val Hex32 = "(?i)[0-9a-f]{32}".r
  /** Parses `123`, `id:123`, a post link (any known booru, query strings ignored), a 32 hex md5 or a file name /
    * path whose stem is an md5 (a saved booru image). Returns the site named by a link, if any.
    */
  def parse(raw: String): (Option[Site], PostRef) =
    val s = raw.trim
    require(s.nonEmpty, "Post is empty")
    if s.forall(_.isDigit) then (None, Id(toId(s)))
    else if s.toLowerCase.startsWith("id:") && s.drop(3).trim.forall(_.isDigit) && s.drop(3).trim.nonEmpty then (None, Id(toId(s.drop(3).trim)))
    else if Hex32.matches(s) then (None, Md5(s.toLowerCase))
    else if s.toLowerCase.startsWith("md5:") && Hex32.matches(s.drop(4).trim) then (None, Md5(s.drop(4).trim.toLowerCase))
    else if s.matches("(?i)https?://.*") then link(s)
    else file(s)

  private def toId(s: String): Long =
    s.toLongOption.filter(_ > 0).getOrElse(throw new IllegalArgumentException(s"Not a post id: $s"))

  private def link(s: String): (Option[Site], PostRef) =
    val uri = try new URI(s.replace(" ", "%20")) catch case NonFatal(_) => throw new IllegalArgumentException(s"Not a valid link: $s")
    val site = Option(uri.getHost).flatMap(Sites.forHost)
    val query = Option(uri.getRawQuery).getOrElse("").split("&").flatMap { kv =>
      kv.split("=", 2) match
        case Array(k, v) => Some(k -> v)
        case _           => None
    }.toMap
    val path = Option(uri.getPath).getOrElse("")
    val segments = path.split("/").filter(_.nonEmpty)
    // Gelbooru style: index.php?page=post&s=view&id=123
    val fromQuery = query.get("id").filter(i => i.nonEmpty && i.forall(_.isDigit)).map(i => Id(toId(i)))
    // /posts/123, /post/show/123/tags, /posts/123?q=...
    val idx = segments.indexWhere(seg => seg == "posts" || seg == "show")
    val fromPath = Option.when(idx >= 0 && idx + 1 < segments.length && segments(idx + 1).forall(_.isDigit))(Id(toId(segments(idx + 1))))
    // file link: .../<md5>.jpg
    val fromFile = segments.lastOption.flatMap { last =>
      val stem = last.takeWhile(_ != '.')
      Option.when(Hex32.matches(stem))(Md5(stem.toLowerCase))
    }
    (fromQuery.orElse(fromPath).orElse(fromFile)) match
      case Some(ref) => (site, ref)
      case None      => throw new IllegalArgumentException(s"No post id found in link: $s")

  private def file(s: String): (Option[Site], PostRef) =
    val name = s.replace('\\', '/').split("/").filter(_.nonEmpty).lastOption.getOrElse(s)
    if name.toLowerCase.startsWith("sample_") then
      throw new IllegalArgumentException("Sample images have no original md5: use the original file name or the post id")
    val stem = name.takeWhile(_ != '.')
    if Hex32.matches(stem) then (None, Md5(stem.toLowerCase))
    else throw new IllegalArgumentException(s"Not a post id, link, md5 or md5-named file: $s")

/** Network access seam: the JDK client in production, a canned responder in tests. */
trait BooruTransport:
  def text(url: String, headers: Map[String, String] = Map.empty): String
  def bytes(url: String, headers: Map[String, String] = Map.empty, maxBytes: Int = BooruTransport.MaxImage): Array[Byte]

object BooruTransport:
  val MaxText = 8 * 1024 * 1024
  val MaxImage = 25 * 1024 * 1024
  val UserAgent = "StableDiffusionNUI/0.06 (booru_prompt; +https://github.com/kmizu/stablediffusion-nui)"

/** JDK HTTP client with timeouts, a body cap and a single retry for 429 / 503. Errors never include the query
  * string, because it can hold API keys.
  */
object JdkTransport extends BooruTransport:
  private lazy val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
    .followRedirects(HttpClient.Redirect.NORMAL).build()

  private def where(url: String): String =
    val u = URI.create(url)
    s"${u.getHost}${Option(u.getPath).getOrElse("")}"

  private def fetch(url: String, headers: Map[String, String], limit: Int): Array[Byte] =
    def once(): HttpResponse[java.io.InputStream] =
      val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
        .header("User-Agent", BooruTransport.UserAgent).header("Accept", "application/json, image/*, */*")
      headers.foreach((k, v) => b.header(k, v))
      try client.send(b.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
      catch
        case e: InterruptedException => Thread.currentThread().interrupt(); throw new IllegalArgumentException("Interrupted")
        case NonFatal(e) => throw new IllegalArgumentException(s"Network error for ${where(url)}: ${e.getClass.getSimpleName}")
    var response = once()
    if response.statusCode == 429 || response.statusCode == 503 then
      val wait = response.headers.firstValue("Retry-After").map(_.trim).filter(_.forall(_.isDigit)).map(_.toLong).orElse(2L)
      response.body.close()
      Thread.sleep(math.min(wait, 5L) * 1000)
      response = once()
    val code = response.statusCode
    if code / 100 != 2 then
      response.body.close()
      val hint = if code == 401 || code == 403 then " (this booru may need an API key / user id)" else ""
      throw new IllegalArgumentException(s"HTTP $code from ${where(url)}$hint")
    val in = response.body
    try
      val out = new ByteArrayOutputStream()
      val buf = new Array[Byte](16384)
      var n = in.read(buf)
      while n >= 0 do
        out.write(buf, 0, n)
        if out.size > limit then throw new IllegalArgumentException(s"Response from ${where(url)} is larger than ${limit / 1024 / 1024} MB")
        n = in.read(buf)
      out.toByteArray
    finally in.close()

  def text(url: String, headers: Map[String, String]): String =
    new String(fetch(url, headers, BooruTransport.MaxText), StandardCharsets.UTF_8)
  def bytes(url: String, headers: Map[String, String], maxBytes: Int): Array[Byte] = fetch(url, headers, maxBytes)

/** Process-wide page cache for the "Use cache" option (bounded, cleared when full). */
object BooruCache:
  private val pages = new ConcurrentHashMap[String, Seq[Post]]()
  def get(key: String): Option[Seq[Post]] = Option(pages.get(key))
  def put(key: String, posts: Seq[Post]): Unit =
    if pages.size >= 256 then pages.clear()
    pages.put(key, posts)
  def clear(): Unit = pages.clear()
  def size: Int = pages.size

enum Order:
  case Random, HighScore, LowScore

/** What counts as an acceptable post. `rating` is one of Any / General / Sensitive / Questionable / Explicit /
  * General + Sensitive.
  */
final case class Query(
    site: Site, tags: Seq[String], rating: String, maxPages: Int, excludeAnimated: Boolean, order: Order, creds: Creds,
    cache: Boolean)

/** Booru API access: page search, single post lookup, post parsing, random picks. */
final class BooruClient(transport: BooruTransport = JdkTransport):
  private val local = mutable.Map[String, Seq[Post]]()
  private val PageSize = 100

  private def enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
  private def base(site: Site) = s"https://${site.host}"

  private def credQuery(site: Site, c: Creds): String =
    if c.key.trim.isEmpty && c.user.trim.isEmpty then ""
    else site.api match
      case Api.Danbooru | Api.E621 => s"&login=${enc(c.user.trim)}&api_key=${enc(c.key.trim)}"
      case Api.Dapi                => s"&user_id=${enc(c.user.trim)}&api_key=${enc(c.key.trim)}"
      case Api.Moebooru            => ""

  private def requireCreds(site: Site, c: Creds): Unit =
    if site.requiresKey && (c.key.trim.isEmpty || c.user.trim.isEmpty) then
      throw new IllegalArgumentException(s"${site.name} requires a user id and an API key (booru account settings)")

  private def get(site: Site, path: String, c: Creds): String =
    val headers = if site.api == Api.E621 then Map("Accept" -> "application/json") else Map.empty[String, String]
    transport.text(base(site) + path + credQuery(site, c), headers)

  private def parseJson(site: Site, body: String): ujson.Value =
    val t = body.trim
    if t.isEmpty then ujson.Arr()
    else
      try ujson.read(t)
      catch case NonFatal(_) => throw new IllegalArgumentException(s"${site.name} returned something other than JSON: ${t.take(80)}")

  /** One page of search results. `page` is 1-based for every site. */
  def page(site: Site, tags: Seq[String], page: Int, c: Creds): Seq[Post] =
    requireCreds(site, c)
    val t = enc(tags.mkString(" "))
    val path = site.api match
      case Api.Danbooru | Api.E621 => s"/posts.json?tags=$t&limit=$PageSize&page=$page"
      case Api.Dapi                => s"/index.php?page=dapi&s=post&q=index&json=1&limit=$PageSize&pid=${page - 1}&tags=$t"
      case Api.Moebooru            => s"/post.json?limit=$PageSize&page=$page&tags=$t"
    posts(site, parseJson(site, get(site, path, c)))

  def post(site: Site, ref: PostRef, c: Creds): Post =
    requireCreds(site, c)
    val result = (site.api, ref) match
      case (Api.Danbooru | Api.E621, PostRef.Id(id)) => posts(site, parseJson(site, get(site, s"/posts/$id.json?", c)))
      case (Api.Danbooru | Api.E621, PostRef.Md5(m)) => posts(site, parseJson(site, get(site, s"/posts.json?tags=md5%3A$m&limit=1", c)))
      case (Api.Dapi, PostRef.Id(id))                => posts(site, parseJson(site, get(site, s"/index.php?page=dapi&s=post&q=index&json=1&id=$id", c)))
      case (Api.Dapi, PostRef.Md5(m))                => posts(site, parseJson(site, get(site, s"/index.php?page=dapi&s=post&q=index&json=1&tags=md5%3A$m", c)))
      case (Api.Moebooru, PostRef.Id(id))            => posts(site, parseJson(site, get(site, s"/post.json?limit=1&tags=id%3A$id", c)))
      case (Api.Moebooru, PostRef.Md5(m))            => posts(site, parseJson(site, get(site, s"/post.json?limit=1&tags=md5%3A$m", c)))
    result.headOption.getOrElse(throw new IllegalArgumentException(s"Post not found on ${site.name}: ${ref match
      case PostRef.Id(i) => i.toString
      case PostRef.Md5(m) => m}"))

  // ---------------------------------------------------------------------------------------------- parsing
  private def str(o: ujson.Obj, k: String): String = o.value.get(k) match
    case Some(ujson.Str(s))  => s
    case Some(ujson.Num(n))  => if n == n.toLong then n.toLong.toString else n.toString
    case _                   => ""
  private def num(o: ujson.Obj, k: String): Int = o.value.get(k) match
    case Some(ujson.Num(n)) => n.toInt
    case Some(ujson.Str(s)) => s.toIntOption.getOrElse(0)
    case _                  => 0
  private def words(s: String): Seq[String] = s.split("\\s+").toSeq.filter(_.nonEmpty).map(unescape)

  /** Gelbooru style APIs HTML-escape tags (`&#039;`, `&amp;`). */
  private[plugins] def unescape(s: String): String =
    if !s.contains('&') then s
    else s.replace("&#039;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

  private def normRating(api: Api, r: String): String = r.trim.toLowerCase match
    case "g" | "general"                     => "general"
    case "s" if api == Api.Danbooru          => "sensitive"
    case "s" | "safe"                        => "general"
    case "sensitive"                         => "sensitive"
    case "q" | "questionable"                => "questionable"
    case "e" | "explicit"                    => "explicit"
    case _                                   => "unknown"

  private def ext(url: Option[String], declared: String): String =
    if declared.nonEmpty then declared
    else url.map(_.takeWhile(_ != '?')).filter(_.contains('.')).map(u => u.substring(u.lastIndexOf('.') + 1)).getOrElse("")

  private def goodUrl(s: String): Option[String] = Option.when(s.startsWith("http"))(s).orElse(Option.when(s.startsWith("//"))("https:" + s))

  private[plugins] def posts(site: Site, json: ujson.Value): Seq[Post] =
    val items: Seq[ujson.Value] = json match
      case a: ujson.Arr => a.value.toSeq
      case o: ujson.Obj =>
        o.value.get("posts").orElse(o.value.get("post")) match
          case Some(a: ujson.Arr) => a.value.toSeq
          case Some(p: ujson.Obj) => Seq(p)
          case _ =>
            if o.value.contains("id") then Seq(o)
            else if o.value.get("success").contains(ujson.False) then throw new IllegalArgumentException(s"${site.name}: ${str(o, "message").take(120)}")
            else Nil
      case _ => Nil
    items.collect { case o: ujson.Obj => parsePost(site, o) }.flatten

  private def parsePost(site: Site, o: ujson.Obj): Option[Post] =
    val id = str(o, "id").toLongOption.getOrElse(0L)
    if id <= 0 then None
    else site.api match
      case Api.Danbooru =>
        val tags = Seq(Cat.Artist -> "tag_string_artist", Cat.Character -> "tag_string_character", Cat.Copyright -> "tag_string_copyright",
          Cat.General -> "tag_string_general", Cat.Meta -> "tag_string_meta").flatMap((c, k) => words(str(o, k)).map(c -> _))
        val all = if tags.isEmpty then words(str(o, "tag_string")).map(Cat.General -> _) else tags
        val file = goodUrl(str(o, "file_url"))
        val sample = goodUrl(str(o, "large_file_url")).orElse(file)
        Some(Post(site.name, id, str(o, "md5"), normRating(site.api, str(o, "rating")), num(o, "score"), all, file, sample, ext(file, str(o, "file_ext"))))
      case Api.E621 =>
        val t = o.value.get("tags").collect { case x: ujson.Obj => x }.getOrElse(ujson.Obj())
        def cat(c: Cat, k: String) = t.value.get(k).collect { case a: ujson.Arr => a.value.toSeq.collect { case ujson.Str(s) => s } }.getOrElse(Nil).map(c -> _)
        val tags = cat(Cat.Artist, "artist") ++ cat(Cat.Character, "character") ++ cat(Cat.Copyright, "copyright") ++
          cat(Cat.Species, "species") ++ cat(Cat.General, "general") ++ cat(Cat.Lore, "lore") ++ cat(Cat.Meta, "meta")
        val f = o.value.get("file").collect { case x: ujson.Obj => x }.getOrElse(ujson.Obj())
        val s = o.value.get("sample").collect { case x: ujson.Obj => x }.getOrElse(ujson.Obj())
        val file = goodUrl(str(f, "url"))
        val sample = goodUrl(str(s, "url")).orElse(file)
        val score = o.value.get("score") match
          case Some(x: ujson.Obj) => num(x, "total")
          case Some(ujson.Num(n)) => n.toInt
          case _                  => 0
        Some(Post(site.name, id, str(f, "md5"), normRating(site.api, str(o, "rating")), score, tags, file, sample, ext(file, str(f, "ext"))))
      case Api.Dapi | Api.Moebooru =>
        val file = goodUrl(str(o, "file_url"))
        val sample = goodUrl(str(o, "sample_url")).orElse(file)
        val md5 = Seq(str(o, "md5"), str(o, "hash")).find(_.nonEmpty).getOrElse("")
        val rating = str(o, "rating")
        Some(Post(site.name, id, md5, normRating(site.api, rating), num(o, "score"), words(str(o, "tags")).map(Cat.General -> _), file, sample, ext(file, str(o, "file_ext"))))

  // ------------------------------------------------------------------------------------------ random picks
  /** Server side rating filter tag, or None when the site has none or the anonymous tag budget would be exceeded. */
  private[plugins] def ratingTag(q: Query): Option[String] =
    val r = q.rating
    if r == "Any" || r == "General + Sensitive" then None
    else if q.site.api == Api.Danbooru && q.creds.key.trim.isEmpty && q.tags.size >= 2 then None // anonymous accounts may search 2 tags at most
    else (q.site.api, r) match
      case (Api.Danbooru, "General")       => Some("rating:g")
      case (Api.Danbooru, "Sensitive")     => Some("rating:s")
      case (Api.Danbooru, "Questionable")  => Some("rating:q")
      case (Api.Danbooru, "Explicit")      => Some("rating:e")
      case (Api.E621, "General")           => Some("rating:s")
      case (Api.E621, "Questionable")      => Some("rating:q")
      case (Api.E621, "Explicit")          => Some("rating:e")
      case (Api.Dapi, "General") if q.site.name == "Gelbooru"      => Some("rating:general")
      case (Api.Dapi, "Sensitive") if q.site.name == "Gelbooru"    => Some("rating:sensitive")
      case (Api.Dapi, "Questionable")      => Some("rating:questionable")
      case (Api.Dapi, "Explicit")          => Some("rating:explicit")
      case (Api.Moebooru, "General")       => Some("rating:s")
      case (Api.Moebooru, "Questionable")  => Some("rating:q")
      case (Api.Moebooru, "Explicit")      => Some("rating:e")
      case _                               => None

  private[plugins] def accepts(q: Query, p: Post): Boolean =
    val ratingOk = q.rating match
      case "Any"                => true
      case "General + Sensitive" => p.rating == "general" || p.rating == "sensitive"
      case other                => p.rating == other.toLowerCase
    ratingOk && !(q.excludeAnimated && p.animated)

  private def cachedPage(q: Query, tags: Seq[String], n: Int): Seq[Post] =
    val key = s"${q.site.name}|${tags.mkString(" ")}|$n"
    local.get(key).orElse(if q.cache then BooruCache.get(key) else None).getOrElse {
      val posts = page(q.site, tags, n, q.creds)
      local(key) = posts
      if q.cache then BooruCache.put(key, posts)
      posts
    }

  /** Order bias: High/Low score sort the page and pick near the front (r^2), Random is uniform. */
  private[plugins] def choose(posts: Seq[Post], order: Order, rnd: Random): Post = order match
    case Order.Random => posts(rnd.nextInt(posts.size))
    case Order.HighScore =>
      val s = posts.sortBy(p => -p.score)
      s(math.min(s.size - 1, (math.pow(rnd.nextDouble(), 2) * s.size).toInt))
    case Order.LowScore =>
      val s = posts.sortBy(_.score)
      s(math.min(s.size - 1, (math.pow(rnd.nextDouble(), 2) * s.size).toInt))

  /** A random acceptable post: random page in 1..maxPages (shrunk when a page lies past the last result),
    * filtered by rating / animation, then picked by the order.
    */
  def random(q: Query, rnd: Random, exclude: Set[Long] = Set.empty, interrupted: () => Boolean = () => false): Post =
    val tags = q.tags ++ ratingTag(q)
    var upper = math.max(1, q.maxPages)
    var attempts = 0
    var lastRaw = 0
    while attempts < 8 do
      if interrupted() then throw new IllegalArgumentException("Interrupted")
      val n = 1 + rnd.nextInt(upper)
      val raw = cachedPage(q, tags, n)
      lastRaw = raw.size
      val ok = raw.filter(p => accepts(q, p) && !exclude.contains(p.id))
      if ok.nonEmpty then return choose(ok, q.order, rnd)
      if raw.isEmpty && n > 1 then upper = math.max(1, n - 1)
      attempts += 1
      if attempts == 7 then upper = 1 // last resort: first page
    throw new IllegalArgumentException(
      s"No ${q.site.name} post matched [${q.tags.mkString(" ")}] with rating ${q.rating}" +
        (if lastRaw == 0 then " (the search returned nothing)" else ""))
