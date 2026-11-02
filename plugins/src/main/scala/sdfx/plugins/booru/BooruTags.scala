package sdfx.plugins.booru

import scala.util.Random

/** Tag clean-up that turns booru posts into prompt text. Everything here is pure and seeded. */
object BooruTags:

  /** Settings that shape the tag list of one prompt. */
  final case class Options(
      categories: Set[Cat] = Set(Cat.General, Cat.Character, Cat.Copyright),
      artistPrefix: String = "",
      removeBad: Boolean = true,
      removePatterns: Seq[String] = Nil,
      shuffle: Boolean = false,
      limitPercent: Int = 100,
      maxTags: Int = 0,
      underscoresToSpaces: Boolean = true,
      escapeBrackets: Boolean = true,
      appendTags: Seq[String] = Nil,
      background: String = "Keep",
      color: String = "Keep")

  val Backgrounds: Seq[String] = Seq("Keep", "Remove", "Add simple background", "Add white background", "Add black background")
  val Colors: Seq[String] = Seq("Keep", "Remove", "Randomize")

  /** Watermarks, text, censoring and similar tags that rarely help a prompt. */
  val BadTags: Seq[String] = Seq(
    "watermark", "artist_name", "signature", "web_address", "username", "logo", "dated", "copyright_name", "character_name",
    "text", "english_text", "korean_text", "chinese_text", "japanese_text", "text_focus", "speech_bubble", "translated",
    "translation_request", "commentary", "commentary_request", "english_commentary", "censored", "mosaic_censoring",
    "bar_censor", "convenient_censoring", "censored_nipples", "light_censor", "heavily_censored", "tagme", "bad_id",
    "bad_pixiv_id", "bad_twitter_id", "bad_link", "bad_source", "bad_tumblr_id", "bad_nicoseiga_id", "bad_deviantart_id", "md5_mismatch", "paid_reward_available", "patreon_username", "twitter_username",
    "pixiv_id", "check_commentary", "check_translation", "has_bad_revision")

  private val ColorWords = Seq("red", "blue", "green", "yellow", "orange", "purple", "pink", "brown", "black", "white", "grey",
    "gray", "silver", "blonde", "aqua", "cyan", "magenta", "multicolored", "gradient", "two-tone")
  private val RandomColors = Seq("red", "blue", "green", "yellow", "orange", "purple", "pink", "brown", "black", "white", "grey",
    "silver", "aqua")
  private val ColorTagSuffix = Seq("hair", "eyes", "dress", "shirt", "skirt", "ribbon", "bow", "jacket", "legwear", "thighhighs",
    "gloves", "bikini", "swimsuit", "shorts", "pants", "footwear", "scarf", "hat", "kimono", "sweater", "uniform", "nails", "fur", "tail", "ears", "wings")
  private val BackgroundTags = Set("indoors", "outdoors", "sky", "cloud", "clouds", "day", "night", "sunset", "sunrise", "tree", "trees",
    "forest", "building", "city", "cityscape", "scenery", "landscape", "room", "bedroom", "classroom", "street", "beach", "ocean",
    "water", "mountain", "grass", "flower_field", "field", "starry_sky", "night_sky", "blue_sky", "window", "curtains", "bed",
    "stairs", "road", "bridge", "garden", "sun", "moon", "star_(sky)", "rain", "snow", "park", "kitchen", "bathroom")

  /** Tags are compared lower-case with spaces folded to underscores. */
  def norm(tag: String): String = tag.trim.toLowerCase.replaceAll("\\s+", "_")

  /** A tag list typed by the user: commas / newlines separate tags; a line without commas that holds several words is
    * read as a space separated booru query (`1girl solo`).
    */
  def parseList(text: String): Seq[String] =
    val lines = text.split("[\\r\\n]+").map(_.trim).filter(_.nonEmpty).toSeq
    lines.flatMap { line =>
      if line.contains(',') then line.split(",").map(_.trim).filter(_.nonEmpty).toSeq
      else if text.contains(',') || lines.size > 1 then Seq(line)
      else line.split("\\s+").toSeq
    }.map(_.replaceAll("\\s+", "_"))

  /** `*` matches any run of characters; the whole tag must match. */
  def globMatches(pattern: String, tag: String): Boolean =
    val p = norm(pattern)
    val t = norm(tag)
    if !p.contains('*') then p == t
    else
      val re = p.split("\\*", -1).map(java.util.regex.Pattern.quote(_)).mkString(".*")
      t.matches(re)

  private def isBackground(tag: String): Boolean =
    val t = norm(tag)
    t.contains("background") || BackgroundTags.contains(t)

  private def isColored(tag: String): Boolean =
    val parts = norm(tag).split("_").toSeq
    parts.size >= 2 && parts.exists(p => ColorWords.contains(p)) && ColorTagSuffix.exists(parts.contains) ||
      norm(tag).endsWith("_hair") && parts.exists(ColorWords.contains)

  private def recolor(tag: String, rnd: Random): String =
    val parts = norm(tag).split("_").toSeq
    val i = parts.indexWhere(ColorWords.contains)
    val current = parts(i)
    val next = RandomColors.filterNot(_ == current)(rnd.nextInt(RandomColors.size - 1))
    parts.updated(i, next).mkString("_")

  /** Emoticon tags such as `^_^` or `>_<` keep their underscores. */
  private def keepUnderscores(tag: String): Boolean = tag.length <= 4 && !tag.exists(_.isLetter) || tag.matches("[a-z0-9]_[a-z0-9]")

  def format(tag: String, o: Options): String =
    val spaced = if o.underscoresToSpaces && !keepUnderscores(tag) then tag.replace('_', ' ') else tag
    if o.escapeBrackets then spaced.replaceAll("([()\\[\\]])", "\\\\$1") else spaced

  /** Final tag list of the given posts (one post, or several when mixing prompts). */
  def tags(posts: Seq[Post], o: Options, rnd: Random): Seq[String] =
    val seen = scala.collection.mutable.LinkedHashMap[String, Cat]()
    posts.foreach { p =>
      val withRating = if o.categories.contains(Cat.Rating) && p.rating != "unknown" then p.tags :+ (Cat.Rating -> ("rating:" + p.rating)) else p.tags
      val categoryAware = Sites.all.find(_.name == p.site).exists(_.hasCategories)
      withRating.foreach { (cat, tag) =>
        val keep = !categoryAware || cat == Cat.Rating || o.categories.contains(cat)
        if keep && tag.nonEmpty then seen.getOrElseUpdate(tag, cat)
      }
    }
    var out: Seq[(String, Cat)] = seen.toSeq
    if o.removeBad then out = out.filterNot((t, _) => BadTags.contains(norm(t)))
    if o.removePatterns.nonEmpty then out = out.filterNot((t, _) => o.removePatterns.exists(globMatches(_, t)))
    o.background match
      case "Remove" | "Add simple background" | "Add white background" | "Add black background" =>
        out = out.filterNot((t, _) => isBackground(t))
      case _ => ()
    o.color match
      case "Remove"    => out = out.filterNot((t, _) => isColored(t))
      case "Randomize" => out = out.map((t, c) => (if isColored(t) then recolor(t, rnd) else t, c))
      case _           => ()
    if o.shuffle then out = rnd.shuffle(out)
    if o.limitPercent < 100 then out = out.take(math.ceil(out.size * math.max(0, o.limitPercent) / 100.0).toInt)
    if o.maxTags > 0 then out = out.take(o.maxTags)
    o.background match
      case "Add simple background" => out = out :+ ("simple_background" -> Cat.General)
      case "Add white background"  => out = out ++ Seq("simple_background" -> Cat.General, "white_background" -> Cat.General)
      case "Add black background"  => out = out ++ Seq("simple_background" -> Cat.General, "black_background" -> Cat.General)
      case _ => ()
    val names = out.map((t, c) => if c == Cat.Artist && o.artistPrefix.nonEmpty then (o.artistPrefix + format(t, o)) -> c else format(t, o) -> c)
    val formatted = names.map(_._1) ++ o.appendTags.map(format(_, o))
    formatted.distinctBy(_.toLowerCase)

  /** Chaos mode: moves `amount` (0..1) of the booru tags into the negative prompt. `full` also moves the same share of
    * the tags already in the negative prompt into the positive one. Negative mode moves everything.
    * Returns (positive tags, negative tags, tags taken from the negative prompt).
    */
  def chaos(mode: String, amount: Double, booru: Seq[String], negative: Seq[String], rnd: Random): (Seq[String], Seq[String], Seq[String]) =
    mode match
      case "Negative mode" => (Nil, booru, Nil)
      case "Chaos" | "Less chaos" =>
        val share = math.max(0.0, math.min(1.0, amount))
        val moveCount = math.round(booru.size * share).toInt
        val toNegative = rnd.shuffle(booru.indices).take(moveCount).toSet
        val stay = booru.indices.filterNot(toNegative).map(booru)
        val moved = booru.indices.filter(toNegative).map(booru)
        if mode == "Chaos" then
          val back = rnd.shuffle(negative.indices).take(math.round(negative.size * share).toInt).toSet
          (stay ++ back.toSeq.sorted.map(negative), moved, back.toSeq.sorted.map(negative))
        else (stay, moved, Nil)
      case _ => (booru, Nil, Nil)

  /** Appends `add` to a comma separated prompt without doubling separators. */
  def join(existing: String, add: String): String =
    val a = existing.stripTrailing()
    if add.isEmpty then existing
    else if a.isEmpty then add
    else if a.endsWith(",") then s"$a $add"
    else s"$a, $add"

  def prepend(existing: String, add: String): String =
    val a = existing.stripLeading()
    if add.isEmpty then existing
    else if a.isEmpty then add
    else if a.startsWith(",") then s"$add$a"
    else s"$add, $a"
