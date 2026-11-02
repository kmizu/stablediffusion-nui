package sdfx.plugins

import sdfx.core.model.{GenerationParams, Img2ImgParams, LoraSyntax}
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import sdfx.plugins.booru.*

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import scala.util.Random

object BooruPromptPlugin:
  val Id = "booru_prompt"
  val InfotextKeys: Seq[String] = Seq("Booru", "Booru posts", "Booru search")
  val Positions: Seq[String] = Seq("After prompt", "Before prompt", "Replace prompt")
  val ChaosModes: Seq[String] = Seq("Off", "Chaos", "Less chaos", "Negative mode")
  val Ratings: Seq[String] = Seq("Any", "General", "Sensitive", "Questionable", "Explicit", "General + Sensitive")
  val Orders: Seq[String] = Seq("Random", "High score", "Low score")

  /** Everything the user set, validated. */
  final case class Config(
      site: Site, random: Boolean, refs: Seq[String], query: Query, tags: BooruTags.Options, mix: Int, chaosMode: String,
      chaosAmount: Double, position: String, samePrompt: Boolean, sameSeed: Boolean, img2img: Boolean, denoising: Double,
      cropCenter: Boolean, lastImage: Boolean, lora: Option[LoraConfig])

  final case class LoraConfig(lockPrevious: Boolean, folder: String, amount: Int, min: Double, max: Double, custom: Seq[Double])

  /** Files for "search tags file" / "remove tags file" live in <app home>/booru/search and /remove. */
  def tagFile(kind: String, name: String, root: Path): Seq[String] =
    val n = name.trim
    if n.isEmpty then Nil
    else
      require(!n.contains('/') && !n.contains('\\') && !n.contains("..") && !n.contains(':'), s"Tag file name must be a plain file name: $n")
      val file = root.resolve("booru").resolve(kind).resolve(n)
      require(Files.isRegularFile(file), s"Tag file not found: booru/$kind/$n in ${root}")
      require(Files.size(file) <= 1024 * 1024, s"Tag file is larger than 1 MB: $n")
      BooruTags.parseList(Files.readString(file).linesIterator.filterNot(_.trim.startsWith("#")).mkString("\n"))

  def config(v: Values, root: => Path, forceSpecific: Boolean = false): Config =
    def int(k: String, lo: Int, hi: Int, d: Int): Int =
      val x = v.int(k, d)
      require(x >= lo && x <= hi, s"$k must be between $lo and $hi")
      x
    def dbl(k: String, lo: Double, hi: Double, d: Double): Double =
      val x = v.double(k, d)
      require(!x.isNaN && x >= lo && x <= hi, s"$k must be between $lo and $hi")
      x
    def choice(k: String, options: Seq[String], d: String): String =
      val x = v.str(k, d)
      require(options.contains(x), s"Unknown $k: $x")
      x
    val site = Sites.byName(v.str("booru", "Danbooru"))
    val random = choice("mode", Seq("Random post", "Specific post(s)"), "Random post") == "Random post"
    val refs = v.str("post").split("[\\r\\n,]+").map(_.trim).filter(_.nonEmpty).toSeq
    if !random && !forceSpecific then require(refs.nonEmpty, "Enter a post link, id or md5 to fetch (Specific post mode)")
    val searchFile = v.str("search_file")
    val search = BooruTags.parseList(v.str("search")) ++ tagFile("search", searchFile, root)
    val removeFile = v.str("remove_file")
    val patterns = BooruTags.parseList(v.str("remove_tags")) ++ tagFile("remove", removeFile, root)
    val cats = Seq("general" -> Cat.General, "character" -> Cat.Character, "copyright" -> Cat.Copyright, "artist" -> Cat.Artist,
      "species" -> Cat.Species, "meta" -> Cat.Meta, "lore" -> Cat.Lore, "rating" -> Cat.Rating)
    val defaults = Set("general", "character", "copyright", "species")
    val chosen = cats.collect { case (k, c) if v.bool("cat_" + k, defaults.contains(k)) => c }.toSet
    val order = choice("order", Orders, "Random") match
      case "High score" => Order.HighScore
      case "Low score"  => Order.LowScore
      case _            => Order.Random
    val maxPages = int("max_pages", 1, 1000, 10)
    val creds = Creds(v.str("user").trim, v.str("api_key").trim)
    val query = Query(site, search, choice("rating", Ratings, "General"), maxPages, v.bool("exclude_animated", true), order, creds, v.bool("use_cache"))
    val opts = BooruTags.Options(
      categories = chosen, artistPrefix = v.str("artist_prefix"), removeBad = v.bool("remove_bad", true), removePatterns = patterns,
      shuffle = v.bool("shuffle"), limitPercent = int("limit_percent", 1, 100, 100), maxTags = int("max_tags", 0, 1000, 0),
      underscoresToSpaces = v.bool("underscores", true), escapeBrackets = v.bool("escape", true),
      appendTags = BooruTags.parseList(v.str("append_tags")),
      background = choice("background", BooruTags.Backgrounds, "Keep"), color = choice("color", BooruTags.Colors, "Keep"))
    val lora = Option.when(v.bool("lora_enabled")) {
      val lo = dbl("lora_min", -5, 5, 0.4)
      val hi = dbl("lora_max", -5, 5, 0.8)
      require(lo <= hi, "LoRA min weight must not exceed the max weight")
      val custom = v.str("lora_weights").split("[,\\s]+").map(_.trim).filter(_.nonEmpty).map(s =>
        s.toDoubleOption.filter(d => !d.isNaN && !d.isInfinite).getOrElse(throw new IllegalArgumentException(s"Bad LoRA weight: $s"))).toSeq
      LoraConfig(v.bool("lora_lock"), v.str("lora_folder").trim, int("lora_amount", 1, 20, 1), lo, hi, custom)
    }
    Config(site, random, refs, query, opts, if v.bool("mix") then int("mix_amount", 2, 10, 2) else 1,
      choice("chaos_mode", ChaosModes, "Off"), dbl("chaos_amount", 0, 1, 0.5), choice("prompt_position", Positions, "After prompt"),
      v.bool("same_prompt"), v.bool("same_seed"), v.bool("use_img2img"), dbl("denoising", 0, 1, 0.75),
      v.bool("crop_center", true), v.bool("last_image"), lora)

  /** Which posts feed image number `index`. */
  def select(cfg: Config, client: BooruClient, rnd: Random, index: Int, interrupted: () => Boolean = () => false): (Site, Seq[Post]) =
    if cfg.random then
      val posts = scala.collection.mutable.ArrayBuffer[Post]()
      var tries = 0
      while posts.size < cfg.mix && tries < cfg.mix * 3 do
        tries += 1
        try posts += client.random(cfg.query, rnd, posts.map(_.id).toSet, interrupted)
        catch case e: IllegalArgumentException if posts.nonEmpty && e.getMessage.startsWith("No ") => tries = Int.MaxValue / 2
      (cfg.site, posts.toSeq)
    else
      val picked = if cfg.mix > 1 then cfg.refs else Seq(cfg.refs(index % cfg.refs.size))
      val fetched = picked.map { raw =>
        val (linkSite, ref) = PostRef.parse(raw)
        val site = linkSite.getOrElse(cfg.site)
        site -> client.post(site, ref, cfg.query.creds)
      }
      (fetched.head._1, fetched.map(_._2))

  /** Prompt pieces of one selection. */
  final case class Composed(positive: Seq[String], negative: Seq[String], removedFromNegative: Seq[String], posts: Seq[Post])

  def compose(cfg: Config, posts: Seq[Post], negativePrompt: String, rnd: Random): Composed =
    val tags = BooruTags.tags(posts, cfg.tags, rnd)
    require(tags.nonEmpty, s"No tags are left for post ${posts.map(_.id).mkString(", ")} after the filters")
    val existing = negativePrompt.split(",").map(_.trim).filter(_.nonEmpty).toSeq
    val (pos, neg, back) = BooruTags.chaos(cfg.chaosMode, cfg.chaosAmount, tags, existing, rnd)
    Composed(pos, neg, back, posts)

  private val Hosts = "(?i)^(\\[[0-9a-f:]+\\]|[0-9.]+|localhost)$".r

  /** Downloads an image post's picture as a PNG temp file (https and public host names only). */
  def download(post: Post, transport: BooruTransport, ctx: PluginContext): Path =
    val url = post.sampleUrl.orElse(post.fileUrl).getOrElse(throw new IllegalArgumentException(s"Post ${post.id} has no image file (login may be required)"))
    val uri = new URI(url)
    require(uri.getScheme == "https" && uri.getHost != null && Hosts.findFirstIn(uri.getHost).isEmpty,
      s"Refusing to download from ${uri.getHost}: only https links to named hosts are fetched")
    val bytes = transport.bytes(url)
    val image: BufferedImage = Option(ImageIO.read(new java.io.ByteArrayInputStream(bytes))).getOrElse(
      throw new IllegalArgumentException(s"Post ${post.id}: the picture format (${post.ext}) cannot be read; use a post with a png/jpg picture"))
    val rgb = new BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_RGB)
    val g = rgb.createGraphics()
    g.drawImage(image, 0, 0, java.awt.Color.WHITE, null)
    g.dispose()
    Support.writeTemp(ctx, rgb)

/** Booru prompt tools: tags of booru posts become the prompt (Ranbooru, booru2prompt, Gelbooru Prompt, e621 Prompt and
  * Gelbooru Prompt Randomizer).
  */
class BooruPromptPlugin(transport: BooruTransport = JdkTransport, appRoot: () => Path = () => sdfx.core.config.AppHome.detect().root) extends Plugin:
  import BooruPromptPlugin.*
  def id = Id
  def name = "Booru prompt"
  def kind = PluginKind.Script
  override def description = "Takes the tags of a random or chosen booru post (Danbooru, e621, Gelbooru, Rule34, Safebooru, Konachan, Yande.re and more) as the prompt."
  @volatile private var lastLoras: Seq[(String, Double)] = Nil

  override lazy val controls: Seq[Control] = Seq(
    Note("help", "Fetches posts from the booru's public API and turns their tags into the prompt text. Random post: a random result of the search " +
      "tags (every image of the batch gets its own post unless 'Same prompt for all images'). Specific post(s): links, ids or md5 hashes, " +
      "one per image in turn. The resolved prompt is saved in the PNG, so a later replay needs no network; the Booru, Booru posts and Booru search " +
      "fields record where it came from. Tags describe other people's pictures: mind the booru's rules and each picture's licence."),
    Choice("booru", "Booru", Sites.names, "Danbooru"),
    Choice("mode", "Source", Seq("Random post", "Specific post(s)"), "Random post"),
    Text("search", "Tags to search (pre)", placeholder = "1girl, solo, blue_eyes  (booru query syntax such as order:score also works)"),
    Text("search_file", "Search tags file", placeholder = "name of a .txt file in booru/search (optional)"),
    Text("post", "Post link, id or md5 (specific post mode)", multiline = true, placeholder = "https://danbooru.donmai.us/posts/123456  or  id:123456  or an md5-named file"),
    Section("filters", "Search filters"),
    Choice("rating", "Rating", Ratings, "General"),
    IntField("max_pages", "Max pages (100 posts each)", 1, 1000, 10),
    Choice("order", "Score preference", Orders, "Random"),
    Check("exclude_animated", "Skip animated posts", true),
    Check("use_cache", "Use cache (reuse result pages between runs)"),
    Section("tags", "Tags", expanded = false),
    Check("cat_general", "General tags", true), Check("cat_character", "Character tags", true), Check("cat_copyright", "Copyright tags", true),
    Check("cat_artist", "Artist tags"), Check("cat_species", "Species tags (e621)", true), Check("cat_meta", "Meta tags"),
    Check("cat_lore", "Lore tags (e621)"), Check("cat_rating", "Rating tag (rating:safe...)"),
    Note("cat_note", "Categories exist on Danbooru, AIBooru and e621 only; the other boorus give one list of tags."),
    Text("artist_prefix", "Prefix for artist tags", placeholder = "by "),
    Check("remove_bad", "Remove bad tags (watermark, text, censor, commentary...)", true),
    Text("remove_tags", "Tags to remove (post)", placeholder = "1girl, *hair  (* matches any characters)"),
    Text("remove_file", "Remove tags file", placeholder = "name of a .txt file in booru/remove (optional)"),
    Check("shuffle", "Shuffle tags"),
    IntField("limit_percent", "Limit tags (% of the tags)", 1, 100, 100),
    IntField("max_tags", "Max tags (0 = all)", 0, 1000, 0),
    Check("underscores", "Convert _ to spaces", true),
    Check("escape", "Escape brackets ( ) [ ]", true),
    Text("append_tags", "Always add these tags", placeholder = "masterpiece, best quality"),
    Choice("background", "Change background", BooruTags.Backgrounds, "Keep"),
    Choice("color", "Change colours", BooruTags.Colors, "Keep"),
    Section("mixing", "Mixing and negative prompt", expanded = false),
    Check("mix", "Mix prompts (tags of several posts)"),
    IntField("mix_amount", "Mix amount (posts)", 2, 10, 2),
    Choice("chaos_mode", "Chaos mode", ChaosModes, "Off"),
    Slider("chaos_amount", "Chaos amount", 0, 1, 0.05, 0.5),
    Note("chaos_note", "Chaos moves that share of the booru tags to the negative prompt and, in Chaos (not Less chaos), the same share of " +
      "the negative prompt's own tags to the positive prompt. Negative mode moves all booru tags to the negative prompt."),
    Choice("prompt_position", "Add the tags", Positions, "After prompt"),
    Check("same_prompt", "Use the same prompt for all images"),
    Check("same_seed", "Use the same seed for all images"),
    Section("img2img", "img2img from the post's picture", expanded = false),
    Check("use_img2img", "Use the picture as the init image"),
    Slider("denoising", "Denoising strength", 0, 1, 0.01, 0.75),
    Check("crop_center", "Crop to the centre (otherwise stretch)", true),
    Check("last_image", "Use the first picture for all images"),
    Section("loranado", "LoRAnado (random LoRAs)", expanded = false),
    Check("lora_enabled", "Add random LoRAs"),
    Check("lora_lock", "Lock previous LoRAs"),
    Text("lora_folder", "LoRA subfolder (empty = all)"),
    IntField("lora_amount", "LoRAs amount", 1, 20, 1),
    Slider("lora_min", "Min LoRA weight", -2, 2, 0.05, 0.4),
    Slider("lora_max", "Max LoRA weight", -2, 2, 0.05, 0.8),
    Text("lora_weights", "Custom LoRA weights", placeholder = "0.2, 0.3, 0.5"),
    Section("account", "Account (optional)", expanded = false),
    Text("user", "Login / user id"),
    Text("api_key", "API key"),
    Note("account_note", "Gelbooru and Rule34 need a user id and an API key; Danbooru, e621 and AIBooru accept a login and API key and then " +
      "allow more tags per search. The key is stored with the plugin settings on this computer and is never written into images.")
  )

  override def enabledBy: Map[String, String] = Map(
    "mix_amount" -> "mix", "denoising" -> "use_img2img", "crop_center" -> "use_img2img", "last_image" -> "use_img2img",
    "lora_lock" -> "lora_enabled", "lora_folder" -> "lora_enabled", "lora_amount" -> "lora_enabled", "lora_min" -> "lora_enabled",
    "lora_max" -> "lora_enabled", "lora_weights" -> "lora_enabled")

  private def cfg(v: Values, forceSpecific: Boolean = false) = config(v, appRoot(), forceSpecific)

  private val actionClient = () => new BooruClient(transport)

  override val promptActions: Seq[PromptAction] = Seq(
    PromptAction("fetch_post", "Add the tags of the post above", false, v => oneShot(v, random = false)),
    PromptAction("fetch_random", "Add tags of a random post", false, v => oneShot(v, random = true))
  )

  private def oneShot(v: Values, random: Boolean): String =
    val c = cfg(v, forceSpecific = !random).copy(random = random)
    if !random then require(c.refs.nonEmpty, "Enter a post link, id or md5 first")
    val rnd = new Random()
    val (_, posts) = select(c, actionClient(), rnd, 0)
    val tags = BooruTags.tags(posts, c.tags, rnd)
    require(tags.nonEmpty, "No tags are left after the filters")
    tags.mkString(", ")

  private def pickLoras(c: LoraConfig, ctx: PluginContext, rnd: Random): Seq[(String, Double)] =
    if c.lockPrevious && lastLoras.nonEmpty then lastLoras
    else
      val all = ctx.choices(ChoiceSource.Loras)
      val folder = c.folder.replace('\\', '/').stripPrefix("/").stripSuffix("/")
      val pool = if folder.isEmpty then all else all.filter(n => n.replace('\\', '/').startsWith(folder + "/"))
      require(pool.nonEmpty, if folder.isEmpty then "No LoRAs are installed" else s"No LoRAs in the subfolder '${c.folder}'")
      val names = rnd.shuffle(pool).take(c.amount)
      val picked = names.zipWithIndex.map { (n, i) =>
        val w = if c.custom.nonEmpty then c.custom(i % c.custom.size)
        else math.round((c.min + rnd.nextDouble() * (c.max - c.min)) * 100) / 100.0
        n -> w
      }
      lastLoras = picked
      picked

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val c = cfg(v)
    val client = new BooruClient(transport)
    val seed = Support.fixSeed(p0.seed)
    val n = if c.samePrompt then 1 else p0.batchSize * p0.nIter
    val temps = scala.collection.mutable.ArrayBuffer[Path]()
    var firstImage: Option[Path] = None
    try
      val items = scala.collection.mutable.ArrayBuffer[(GenerationParams, String)]()
      var i = 0
      while i < n do
        if ctx.isInterrupted then return JobOutput(Nil)
        ctx.status(s"Booru prompt: fetching ${i + 1}/$n", i, n)
        val rnd = new Random(seed + i)
        val (site, posts) = select(c, client, rnd, i, () => ctx.isInterrupted)
        val comp = compose(c, posts, p0.negativePrompt, rnd)
        val loras = c.lora.map(pickLoras(_, ctx, rnd)).getOrElse(Nil)
        val loraText = loras.map((name, w) => LoraSyntax.tag(name, w)).mkString(" ")
        val addition = (comp.positive ++ comp.removedFromNegative.filterNot(comp.positive.contains)).distinct.mkString(", ")
        val withLoras = Seq(addition, loraText).filter(_.nonEmpty).mkString(" ")
        val prompt = c.position match
          case "Before prompt"  => BooruTags.prepend(p0.prompt, withLoras)
          case "Replace prompt" => withLoras
          case _                => BooruTags.join(p0.prompt, withLoras)
        val keptNegative = p0.negativePrompt.split(",").map(_.trim).filter(_.nonEmpty).filterNot(comp.removedFromNegative.toSet).mkString(", ")
        val negative = BooruTags.join(keptNegative, comp.negative.mkString(", "))
        val infoPosts = comp.posts.map(_.id).mkString(" ")
        val extra = p0.extra ++ Seq("Booru" -> site.name, "Booru posts" -> infoPosts) ++
          Option.when(c.random && c.query.tags.nonEmpty)("Booru search" -> c.query.tags.mkString(" "))
        var p = p0.copy(prompt = prompt, negativePrompt = negative, extra = extra,
          seed = if c.sameSeed then seed else seed + i)
        if !c.samePrompt then p = p.copy(batchSize = 1, nIter = 1)
        if c.img2img then
          val image = firstImage.filter(_ => c.lastImage).getOrElse {
            ctx.status(s"Booru prompt: downloading picture ${i + 1}/$n", i, n)
            val f = download(comp.posts.head, transport, ctx)
            temps += f
            if firstImage.isEmpty then firstImage = Some(f)
            f
          }
          val base = p0.img2img.getOrElse(Img2ImgParams(initImage = ""))
          p = p.copy(img2img = Some(base.copy(initImage = image.toString, denoisingStrength = c.denoising, resizeMode = if c.cropCenter then 1 else 0)))
        ctx.log(s"Booru prompt ${i + 1}/$n: ${site.name} ${infoPosts}")
        items += p -> infoPosts
        i += 1
      val out = scala.collection.mutable.ArrayBuffer[OutputImage]()
      val it = items.zipWithIndex.iterator
      var running = true
      while running && it.hasNext do
        val ((p, _), idx) = it.next()
        ctx.status(s"Booru prompt ${idx + 1}/${items.size}", idx, items.size)
        Support.step(ctx)(ctx.generate(p)) match
          case Some(o) => out ++= o
          case None    => running = false
      JobOutput(out.toSeq)
    finally Support.deleteQuietly(temps.toSeq*)
