package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import sdfx.plugins.creaprompt.CreaData
import sdfx.plugins.creaprompt.CreaData.{Category, Library}

import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.charset.StandardCharsets
import scala.util.Random

object CreaPromptPlugin:
  val Id = "creaprompt"
  /** Upstream infotext keys: `CreaPrompt` for collection / manual prompts, the other for random category prompts. */
  val CollectionKey = "CreaPrompt"
  val CategoriesKey = "CreaPrompt random From categories"
  val InfotextKeys: Seq[String] = Seq(CollectionKey, CategoriesKey)
  val ModeCategories = "Random from categories"
  val ModeManual = "Manual (choose per category)"
  val ModeCollection = "Prompt collection"
  val Modes: Seq[String] = Seq(ModeCategories, ModeManual, ModeCollection)
  val Positions: Seq[String] = Seq("Replace prompt", "After prompt", "Before prompt")
  val NoPreset = "(use the checked categories)"
  /** The categories checked when the plugin is first opened (upstream's "Woman" preset). */
  private val DefaultOn = Set("Woman", "haircuts_Thomas_Buyle", "haircolors_Thomas_Buyle", "image_quality", "Cameras", "lighting",
    "shotstyle", "Artists")

  def controlKey(name: String): String = "cat_" + name.replaceAll("[^A-Za-z0-9]+", "_")

  private def controlKeys(lib: Library): Vector[(String, Category)] =
    val seen = scala.collection.mutable.Set[String]()
    lib.categories.map { c =>
      var k = controlKey(c.name)
      var i = 2
      while !seen.add(k) do
        k = controlKey(c.name) + "_" + i
        i += 1
      k -> c
    }

  /** Categories to draw from: the preset when one is chosen (names match without regard to case; names that match no
    * category are ignored), otherwise the checked ones. Always in the library (file name) order.
    */
  def selectCategories(lib: Library, v: Values, keys: Seq[(String, Category)]): (Vector[Category], Vector[String]) =
    val preset = v.str("preset", NoPreset).trim
    if preset.isEmpty || preset == NoPreset then (keys.collect { case (k, c) if v.bool(k, DefaultOn.contains(c.name)) => c }.toVector, Vector.empty)
    else
      val names = lib.preset(preset).getOrElse(throw new IllegalArgumentException(s"Unknown preset: $preset"))
      val chosen = lib.categories.filter(c => names.exists(_.equalsIgnoreCase(c.name)))
      (chosen, names.filterNot(n => lib.categories.exists(_.name.equalsIgnoreCase(n))))

  /** Manual picks: `Category: entry` or `Category: random` per line; categories not listed contribute nothing.
    * The result keeps library order, like upstream's dropdown row. `None` = a random entry for every image.
    */
  def parseManual(lib: Library, text: String): Vector[(Category, Option[String])] =
    val picks = scala.collection.mutable.LinkedHashMap[String, (Category, Option[String])]()
    text.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).foreach { line =>
      val at = line.indexOf(':')
      require(at > 0, s"Manual line must read 'Category: entry' or 'Category: random': $line")
      val cat = lib.category(line.take(at)).getOrElse(throw new IllegalArgumentException(s"Unknown category in the manual list: ${line.take(at).trim}"))
      val value = line.drop(at + 1).trim
      require(!picks.contains(cat.name), s"Category listed twice in the manual list: ${cat.name}")
      if value.equalsIgnoreCase("none") || value.isEmpty then ()
      else if value.equalsIgnoreCase("random") then picks(cat.name) = cat -> None
      else
        val hit = cat.entries.find(e => e.equalsIgnoreCase(value) || CreaData.clean(e).equalsIgnoreCase(CreaData.clean(value)))
        picks(cat.name) = cat -> Some(hit.getOrElse(throw new IllegalArgumentException(s"'$value' is not an entry of ${cat.name}")))
    }
    lib.categories.flatMap(c => picks.get(c.name))

  private def join(parts: Seq[String]): String = parts.map(CreaData.clean).filter(_.nonEmpty).mkString(", ")

  def fromCategories(cats: Seq[Category], rnd: Random): String =
    require(cats.nonEmpty, "Select at least one category (or choose a preset)")
    join(cats.map(c => CreaData.pick(c.entries, rnd)))

  def fromManual(picks: Seq[(Category, Option[String])], rnd: Random): String =
    require(picks.nonEmpty, "Choose an entry or 'random' for at least one category")
    join(picks.map((c, e) => e.getOrElse(CreaData.pick(c.entries, rnd))))

  def fromCollection(lib: Library, rnd: Random): String =
    require(lib.collection.nonEmpty, "The prompt collection is empty")
    CreaData.pick(lib.collection, rnd)

  def wrap(core: String, prefix: String, suffix: String): String = join(Seq(prefix, core, suffix))

  def place(position: String, existing: String, generated: String): String = position match
    case "After prompt"  => join(Seq(existing, generated))
    case "Before prompt" => join(Seq(generated, existing))
    case _               => generated

class CreaPromptPlugin(userDir: () => Option[Path] = () => Some(sdfx.core.config.AppHome.detect().root.resolve("creaprompt").resolve("csv"))) extends Plugin:
  import CreaPromptPlugin.*
  def id = Id
  def name = "CreaPrompt"
  def kind = PluginKind.Script
  override def description = "Builds the prompt from random entries of word lists (people, places, styles, artists...) or from a collection of ready prompts."

  private lazy val library: Library = CreaData.load(userDir(), _ => ())
  private lazy val keys: Vector[(String, Category)] = controlKeys(library)
  private val manualNone = "(none)"
  private val manualRandom = "(random)"

  /** Re-read presets so one saved from the UI can be selected without restarting the application. */
  private def withCurrentPresets: Library = library.copy(presets = CreaData.load(userDir()).presets)
  def presetNames: Vector[String] = withCurrentPresets.presets.map(_._1)

  /** Save the effective category selection without overwriting another preset. */
  def savePreset(v: Values, rawName: String): Path =
    val name = Option(rawName).getOrElse("").trim.stripSuffix(".config").trim
    require(name.nonEmpty && name.length <= 120 && name != "." && name != ".." &&
      !name.endsWith(".") && !name.exists(c => c < ' ' || "\\/:*?\"<>|".contains(c)) &&
      !name.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$"), "Invalid preset name")
    require(!CreaData.load(None).presets.exists(_._1.equalsIgnoreCase(name)), "A built-in preset already has this name")
    val selected = selectCategories(withCurrentPresets, v, keys)._1
    val dir = userDir().getOrElse(throw new IllegalStateException("No user CreaPrompt folder is configured"))
    Files.createDirectories(dir)
    val dest = dir.resolve(name + ".config")
    if withCurrentPresets.presets.exists(_._1.equalsIgnoreCase(name)) then
      throw new java.nio.file.FileAlreadyExistsException(dest.toString)
    Files.writeString(dest, selected.map(_.name).mkString("", "\n", if selected.nonEmpty then "\n" else ""),
      StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    dest

  /** Preview fixed entries and random placeholders without consuming a generation seed. */
  def manualPreview(v: Values): String =
    val (picks, _, _) = prepare(v.updated("mode", ModeManual))
    picks.map { (category, entry) => entry.map(CreaData.clean).getOrElse(s"Random: ${category.name}") }.mkString(", ")

  override lazy val controls: Seq[Control] =
    val checks = keys.map((k, c) => Check(k, c.name, DefaultOn.contains(c.name)))
    Seq[Control](
      Note("help", "Replaces the prompt with a random one built from word lists. Random from categories: one random line of every checked " +
        "category, in file-name order. Manual: pick an entry (or random) per category in the list below. Prompt collection: a random ready-made prompt. " +
        "With 'Different prompt for each image' every image of the batch gets its own prompt (seeded, so the same seed gives the same prompts); the " +
        "finished prompt is saved in the PNG, so a replay needs no lists. Put your own .csv files (one entry per line) in creaprompt/csv inside the " +
        "app folder and restart to add categories."),
      Choice("mode", "Mode", Modes, ModeCategories),
      Check("per_image", "Different prompt for each image", true),
      Choice("position", "Use the prompt", Positions, "Replace prompt"),
      Text("prefix", "Prefix of the prompt", placeholder = "added in front (optional)"),
      Text("suffix", "Suffix of the prompt", placeholder = "added at the end (optional)"),
      Choice("preset", "Category preset", NoPreset +: library.presets.map(_._1), NoPreset),
      Note("preset_note", "A preset replaces the checked categories below; names in a preset that match no category are ignored."),
      Section("categories", "Categories (random mode)", expanded = false)
    ) ++ checks ++ Seq[Control](
      Section("manual", "Manual picks", expanded = false),
      Note("manual_note", "Choose an entry, (random), or (none) for each category. Manual picks below are also accepted; do not select the same category twice.")
    ) ++ keys.map { (key, category) =>
      Choice("manual_" + key, category.name, Vector(manualNone, manualRandom) ++ category.entries, manualNone)
    } ++ Seq[Control](Text("manual_list", "Additional manual picks", multiline = true, placeholder = "Woman: random\nlighting: random"))

  private def build(v: Values, rnd: Random, picks: Vector[(Category, Option[String])], cats: Vector[Category]): String =
    val core = v.str("mode", ModeCategories) match
      case ModeManual     => fromManual(picks, rnd)
      case ModeCollection => fromCollection(library, rnd)
      case _              => fromCategories(cats, rnd)
    wrap(core, v.str("prefix"), v.str("suffix"))

  private def prepare(v: Values): (Vector[(Category, Option[String])], Vector[Category], Vector[String]) =
    val mode = v.str("mode", ModeCategories)
    require(Modes.contains(mode), s"Unknown mode: $mode")
    mode match
      case ModeManual =>
        val selected = keys.flatMap { (key, category) =>
          val value = v.str("manual_" + key, manualNone)
          if value == manualNone then None
          else if value == manualRandom then Some(category -> None)
          else Some(category -> Some(category.entries.find(_ == value).getOrElse(
            throw new IllegalArgumentException(s"'$value' is not an entry of ${category.name}"))))
        }
        val typed = parseManual(library, v.str("manual_list"))
        val selectedNames = selected.map(_._1.name).toSet
        require(!typed.exists(p => selectedNames.contains(p._1.name)), "A category cannot be selected both in the dropdown and manual list")
        val all = (selected ++ typed).toMap
        (library.categories.flatMap(c => all.get(c).map(c -> _)), Vector.empty, Vector.empty)
      case ModeCategories =>
        val (cats, unknown) = selectCategories(withCurrentPresets, v, keys)
        (Vector.empty, cats, unknown)
      case _ => (Vector.empty, Vector.empty, Vector.empty)

  override val promptActions: Seq[PromptAction] = Seq(
    PromptAction("add_random", "Add a random prompt", false, v => oneShot(v)),
    PromptAction("replace_random", "Replace with a random prompt", true, v => oneShot(v))
  )

  private def oneShot(v: Values): String =
    val (picks, cats, _) = prepare(v)
    build(v, new Random(), picks, cats)

  /** Infotext keys recorded for a prompt (upstream spelling). */
  def infotext(v: Values, cats: Seq[Category]): Seq[(String, String)] = v.str("mode", ModeCategories) match
    case ModeManual     => Seq(CollectionKey -> "manual from categories")
    case ModeCollection => Seq(CollectionKey -> "Collection")
    case _              => Seq(CategoriesKey -> cats.map(_.name).mkString(", "))

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val (picks, cats, unknown) = prepare(v)
    if unknown.nonEmpty then ctx.log(s"CreaPrompt: preset names without a category were ignored: ${unknown.mkString(", ")}")
    val position = v.str("position", "Replace prompt")
    require(Positions.contains(position), s"Unknown position: $position")
    val seed = Support.fixSeed(p0.seed)
    val total = p0.batchSize * p0.nIter
    val perImage = v.bool("per_image", true) && total > 1
    val used = if v.str("mode", ModeCategories) == ModeManual then picks.map(_._1) else cats
    val extra = p0.extra ++ infotext(v, used)
    def params(prompt: String) = p0.copy(prompt = place(position, p0.prompt, prompt), extra = extra)
    if !perImage then
      val prompt = build(v, new Random(seed), picks, cats)
      ctx.log(s"CreaPrompt: $prompt")
      JobOutput(Support.step(ctx)(ctx.generate(params(prompt).copy(seed = seed))).getOrElse(Nil))
    else
      val out = scala.collection.mutable.ArrayBuffer[OutputImage]()
      var i = 0
      while i < total && !ctx.isInterrupted do
        ctx.status(s"CreaPrompt ${i + 1}/$total", i, total)
        val prompt = build(v, new Random(seed + i), picks, cats)
        ctx.log(s"CreaPrompt ${i + 1}/$total: $prompt")
        Support.step(ctx)(ctx.generate(params(prompt).copy(seed = seed + i, batchSize = 1, nIter = 1))) match
          case Some(o) => out ++= o; i += 1
          case None    => i = total
      JobOutput(out.toSeq)
