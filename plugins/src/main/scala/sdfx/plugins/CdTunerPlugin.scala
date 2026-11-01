package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

/** Settings, prompt syntax and infotext of hako-mikan/sd-webui-cd-tuner (`CDT`, `CDTC`), so its PNGs restore and
  * NUI PNGs paste back into it.
  */
object CdTunerPlugin:
  /** The source's identifiers, in its positional order (`<cdt:1;0;4>` sets d1, d2, con1). */
  val Identifiers: Seq[String] =
    Seq("d1", "d2", "con1", "con2", "bri", "col1", "col2", "col3", "hd1", "hd2", "hrs", "st1", "st2", "dis", "sat", "sat2")
  /** `<cdtc:...>` identifiers and the hook fields they set. */
  val MapIdentifiers: Seq[String] = Seq("sp", "md", "cols", "stc", "str")
  val MapKeys: Seq[String] = Seq("split_ratio", "split_by", "colors", "map_stop_step", "map_strength")
  val Unset: Seq[Double] = Seq.fill(11)(0.0) ++ Seq(-1.0, -1.0, 0.0, 0.0, 0.0)
  val Presets: Seq[(String, String)] = Seq(
    "Cyan" -> "-5, 0, 0", "Magenda" -> "0, -5, 0", "Yellow" -> "0, 0, -5", "Red" -> "5, -5, -5", "Green" -> "0, 5, 0",
    "Blue" -> "0, 0, 5", "VeryLightBlue" -> "-5, -5, 0", "YellowGreen" -> "-5, 0, -5", "Orange" -> "0, -5, -5",
    "Malachite" -> "-5, 5, 0", "BrightCyan" -> "-5, 0, 5", "VioretPink" -> "0, -5, 5", "DeepPink" -> "5, -5, 0",
    "GuardsmanRed" -> "5, 0, -5", "DeepGreen" -> "0, 5, -5", "Black" -> "5, 5, 0", "BrightNeonPink" -> "5, 0, 5",
    "NeonBlue" -> "0, 5, 5", "GreenYellow" -> "-5, -5, -5", "LightBlue" -> "-5, -5, 5", "LimeGreen" -> "-5, 5, -5",
    "DeepBrown" -> "5, 5, -5", "VioletBlue" -> "5, 5, 5")
  /** Slider control keys for the 16 values (hrs is a checkbox). */
  private val SliderKeys = Identifiers.filterNot(_ == "hrs")

  /** A value and the text the source prints for it (Python `str`: UI numbers as sent, prompt values as floats). */
  final case class Num(value: Double, text: String)
  object Num:
    def ui(d: Double): Num = Num(d, Values.fmt(d))
    def py(d: Double): Num = Num(d, pyRepr(d))

  /** Python `repr(float)`. */
  def pyRepr(d: Double): String =
    if d == 0 then (if 1 / d < 0 then "-0.0" else "0.0")
    else
      val bd = java.math.BigDecimal(java.lang.Double.toString(d)).stripTrailingZeros
      val digits = bd.unscaledValue.abs.toString
      val exp = digits.length - 1 - bd.scale
      val sign = if d < 0 then "-" else ""
      if exp >= -4 && exp < 16 then
        val plain = bd.abs.toPlainString
        sign + (if plain.contains('.') then plain else plain + ".0")
      else
        val mantissa = if digits.length == 1 then digits else digits.head.toString + "." + digits.tail
        sign + mantissa + "e" + (if exp < 0 then "-" else "+") + f"${math.abs(exp)}%02d"

  private val PyFloat = """[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""".r
  private def pyFloat(s: String, what: String): Double =
    val t = s.trim
    if !PyFloat.matches(t) then throw new IllegalArgumentException(s"CD Tuner: $what '$s' is not a number")
    t.toDouble

  private val Tag = """<(\w+):([^>]+)>""".r

  /** The first `<cdt:...>` / `<cdtc:...>` tag of the prompt, as the source reads it (`None` = not given). */
  def fromPrompt(prompt: String): (Seq[Option[Num]], Seq[Option[String]]) =
    val tags = Tag.findAllMatchIn(prompt).toSeq
    def first(name: String) = tags.find(_.group(1) == name).map(_.group(2).split(":", -1).head)
    val detail = Array.fill[Option[Num]](Identifiers.size)(None)
    val map = Array.fill[Option[String]](MapIdentifiers.size)(None)
    def pairs(text: String, tag: String): Seq[(String, String)] = text.split(";", -1).toSeq.filter(_.trim.nonEmpty).map { part =>
      part.split("=", -1).map(_.trim) match
        case Array(k, v) => k -> v
        case _ => throw new IllegalArgumentException(s"CD Tuner: '<$tag:$text>' needs name=value parts separated by ';'")
    }
    val disabled = first("cdt").exists { text =>
      if Identifiers.exists(text.contains) then
        val kv = pairs(text, "cdt")
        kv.foreach((k, _) => if !Identifiers.contains(k) then throw new IllegalArgumentException(s"CD Tuner: unknown <cdt> setting '$k'"))
        // the source stops at `dis=` (any value): every value becomes 0 and the <cdtc> tag is not read
        val stop = kv.indexWhere(_._1 == "dis")
        (if stop < 0 then kv else kv.take(stop)).foreach((k, v) => detail(Identifiers.indexOf(k)) = Some(Num.py(pyFloat(v, k))))
        stop >= 0
      else
        val values = text.split(";", -1).toSeq
        if values.size > Identifiers.size then
          throw new IllegalArgumentException(s"CD Tuner: <cdt> takes at most ${Identifiers.size} values")
        values.zipWithIndex.foreach((v, i) => detail(i) = Some(Num.py(pyFloat(v, Identifiers(i)))))
        false
    }
    if disabled then return (Seq.fill(Identifiers.size)(Some(Num(0, "0"))), Seq.fill(MapIdentifiers.size)(None))
    first("cdtc").foreach { text =>
      if MapIdentifiers.exists(text.contains) then
        pairs(text, "cdtc").foreach { (k, v) =>
          val i = MapIdentifiers.indexOf(k)
          if i < 0 then throw new IllegalArgumentException(s"CD Tuner: unknown <cdtc> setting '$k'")
          map(i) = Some(v)
        }
      else
        val values = text.split(";", -1).toSeq
        if values.size > MapIdentifiers.size then
          throw new IllegalArgumentException(s"CD Tuner: <cdtc> takes at most ${MapIdentifiers.size} values")
        values.zipWithIndex.foreach((v, i) => map(i) = Some(v))
    }
    detail(10) = detail(10).map(n => if n.value != 0 then Num(1, "1") else n)
    (detail.toSeq, map.toSeq.map(_.map { case "H" => "Horizontal"; case "V" => "Vertical"; case s => s }))

  /** What the source computes for one generation: the 16 values, the colour map texts and its two infotext keys. */
  final case class Effective(detail: Seq[Num], map: Seq[Either[Num, String]], detailActive: Boolean, mapActive: Boolean):
    def mapText(i: Int): String = map(i).fold(_.text, identity)
    def infotext: Seq[(String, String)] =
      (if detailActive then Seq("CDT" -> detail.map(_.text).mkString(",")) else Nil) ++
        (if mapActive then Seq("CDTC" -> MapKeys.indices.map(mapText).mkString("_")) else Nil)
    def active: Boolean = detailActive || mapActive

  def effective(v: Values, prompt: String): Effective =
    val ui = Identifiers.map {
      case "hrs" => Num.ui(if v.bool("hrs") then 1 else 0)
      case k => Num.ui(v.double(k, Unset(Identifiers.indexOf(k))))
    }
    val preset = Presets.toMap.get(v.str("add_preset", "None"))
    val colors = preset.fold(v.str("colors"))(p => if v.str("colors").isEmpty then p else v.str("colors") + ";" + p)
    val uiMap: Seq[Either[Num, String]] = Seq(Right(v.str("split_ratio", "1,1")), Right(v.str("split_by", "Horizontal")),
      Right(colors), Left(Num.ui(v.double("map_stop_step", 2))), Left(Num.ui(v.double("map_strength", 1))))
    val (pd, pm) = fromPrompt(prompt)
    val detail = ui.zip(pd).map((u, o) => o.getOrElse(u))
    val map = uiMap.zip(pm).map((u, o) => o.map(Right(_)).getOrElse(u))
    val colorText = map(2).fold(_.text, identity)
    // a strength typed in the prompt is text in the source, so it never equals 0 there
    val zeroStrength = map(4).left.exists(_.value == 0)
    val nothing = detail.map(_.value) == Unset && (zeroStrength || colorText.isEmpty)
    val detailActive = !nothing && !(detail.take(10) ++ detail.slice(14, 16)).forall(_.value == 0)
    Effective(detail, map, detailActive, !nothing && colorText.nonEmpty)

  private def numbers(text: String): Option[Seq[String]] =
    val parts = text.split(",", -1).toSeq.map(_.trim)
    Option.when(parts.forall(p => PyFloat.matches(p) && p.toDouble.isFinite))(parts)

  /** `CDT` -> control values by the writer's order; older lengths are read as the source's earlier layouts. */
  private def detailValues(text: String): Option[Map[String, String]] =
    val parts = text.split(",", -1).toSeq.map(_.trim)
    def num(p: String) = Option.when(PyFloat.matches(p) && p.toDouble.isFinite)(Values.fmt(p.toDouble))
    val keys = parts.size match
      case 16 => Some(Identifiers)
      case 15 => Some(Identifiers.take(15))
      case 14 => Some(Identifiers.take(14))
      case 13 => Some(Identifiers.take(13))
      case 9 => Some(Seq("d1", "d2", "con1", "col1", "col2", "col3", "hd1", "hd2", "hrs")) // before Brightness/Contrast 2
      case _ => None
    keys.flatMap { ks =>
      val pairs = ks.zip(parts).map {
        case ("hrs", p) => Some("hrs" -> (p == "1").toString) // the source compares the text with "1"
        case (k, p) => num(p).map(k -> _)
      }
      Option.when(pairs.forall(_.isDefined))(pairs.flatten.toMap)
    }

  private def mapValues(text: String): Option[Map[String, String]] =
    text.split("_", -1).toSeq match
      case Seq(ratio, mode, colors, stop, strength) if numbers(stop).exists(_.size == 1) && numbers(strength).exists(_.size == 1) =>
        val by = if mode.contains("Ver") then "Vertical" else if mode.contains("Hor") then "Horizontal" else "Vertical"
        Some(Map("split_ratio" -> ratio, "split_by" -> by, "colors" -> colors,
          "map_stop_step" -> Values.fmt(stop.trim.toDouble), "map_strength" -> Values.fmt(strength.trim.toDouble)))
      case _ => None

  /** PNG restore. Other PNGs switch the plugin off; a malformed value gives `None`. */
  def replay(fields: Map[String, String]): Option[Values] =
    val cdt = fields.get("CDT")
    val cdtc = fields.get("CDTC")
    if cdt.isEmpty && cdtc.isEmpty then Some(Values(Map("enabled" -> "false")))
    else
      val detail = cdt.map(detailValues)
      val map = cdtc.map(mapValues)
      if detail.contains(None) || map.contains(None) then None
      else
        val defaults = Values.defaults(new CdTunerPlugin().controls).map
        Some(Values(defaults ++ detail.flatten.getOrElse(Map.empty) ++ map.flatten.getOrElse(Map("colors" -> "")) ++
          Map("enabled" -> "true")))

/** CD (Color/Detail) Tuner (hako-mikan/sd-webui-cd-tuner): detail, contrast, colour and saturation from the sampler. */
class CdTunerPlugin extends HookPlugin:
  import CdTunerPlugin.*
  def id = "cd_tuner"
  def name = "CD Tuner"
  def hook = "cd_tuner"
  override def description =
    "Adjusts the amount of detail, contrast, brightness, colour tone and saturation while sampling, and can steer the " +
    "colour of image regions (Color Map). Prompt tags <cdt:...> and <cdtc:...> override the values."
  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Section("detail", "Color/Detail"),
    Slider("d1", "Detail(d1)", -10, 10, 0.1, 0),
    Slider("d2", "Detail 2(d2)", -10, 10, 0.1, 0),
    Slider("hd1", "hr-Detail(hd1)", -10, 10, 0.1, 0),
    Slider("hd2", "hr-Detail 2(hd2)", -10, 10, 0.1, 0),
    Slider("con1", "Contrast(con1)", -20, 20, 0.1, 0),
    Slider("con2", "Contrast 2(con2)", -20, 20, 0.1, 0),
    Slider("bri", "Brightness(bri)", -20, 20, 0.1, 0),
    Slider("sat", "saturation(sat)", -20, 20, 0.1, 0),
    Slider("col1", "Cyan-Red(col1)", -20, 20, 0.1, 0),
    Slider("col2", "Magenta-Green(col2)", -20, 20, 0.1, 0),
    Slider("col3", "Yellow-Blue(col3)", -20, 20, 0.1, 0),
    Slider("sat2", "saturation2(sat2)", -20, 20, 0.1, 0),
    Check("hrs", "hr-scaling(hrs)"),
    Slider("st1", "Stop Step", -1, 20, 1, -1),
    Slider("st2", "Hr-Stop Step", -1, 20, 1, -1),
    Slider("dis", "Contrast 2 step offset (dis, 14th <cdt> value)", -150, 150, 1, 0),
    Check("apply_once", "Apply once"),
    Section("color_map", "Color Map"),
    Choice("split_by", "Split by", Seq("Horizontal", "Vertical"), "Horizontal"),
    Text("split_ratio", "Split Ratio", "1,1"),
    Text("colors", "colors", placeholder = "e.g. 5 0 0;0 0 5 (Cyan-Red, Magenta-Green, Yellow-Blue per region)"),
    Choice("add_preset", "add from presets", "None" +: Presets.map(_._1), "None"),
    Slider("map_stop_step", "Mapping Stop Step", 0, 150, 1, 2),
    Slider("map_strength", "Strength", 0, 2, 0.1, 1),
    Note("syntax", "Prompt: <cdt:d1=2;col1=-3> or <cdt:2;0;1> (d1,d2,con1,con2,bri,col1,col2,col3,hd1,hd2,hrs,st1,st2," +
      "dis,sat,sat2), <cdtc:sp=1,1;md=H;cols=5 0 0|-5 0 0;stc=2;str=1>. Non-zero values take effect automatically; " +
      "Stop Steps of -1 never stop.")
  )
  override def enabledBy: Map[String, String] = controls.collect { case c if c.hasValue && c.key != "enabled" => c.key -> "enabled" }.toMap

  def hookFields(v: Values): ujson.Obj = fields(effective(v, ""), v.bool("apply_once"))

  private def fields(e: Effective, applyOnce: Boolean): ujson.Obj =
    val detail = Identifiers.zip(e.detail).map {
      case ("hrs", n) => "hrs" -> (ujson.Bool(n.value != 0): ujson.Value)
      case (k, n) => k -> (ujson.Num(n.value): ujson.Value)
    }
    val map = MapKeys.zip(e.map).map((k, m) => k -> m.fold(n => ujson.Num(n.value), s => ujson.Str(s)))
    ujson.Obj.from(detail ++ map :+ ("apply_once" -> ujson.Bool(applyOnce)))

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] =
    val v = Values(o.value.toMap.map((k, x) => k -> (x match
      case ujson.Bool(b) => b.toString
      case other => Hooks.num(other))))
    effective(v, "").infotext

  /** The hook is always sent while enabled (the engine also removes the tags from the prompt); the infotext keys
    * only when the source would write them.
    */
  override def process(p: GenerationParams, v: Values): GenerationParams =
    val e = effective(v, p.prompt)
    val (p2, _) = Hooks(p, hook, fields(e, v.bool("apply_once")))
    p2.copy(extra = p2.extra ++ e.infotext)
