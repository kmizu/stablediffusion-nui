package sdfx.plugins

import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

object ClipIoPlugin:
  val Id = "clip_io"
  val InputPositiveKey = "Clip Input positive"
  val InputNegativeKey = "Clip Input negative"
  val OutputKey = "Clip Output"
  val OutputNegativeKey = "Clip Output negative"
  val OutputFormatKey = "Clip Output format"
  val OutputOptionsKey = "Clip Output options"
  val InfotextKeys: Seq[String] =
    Seq(InputPositiveKey, InputNegativeKey, OutputKey, OutputNegativeKey, OutputFormatKey, OutputOptionsKey)
  val Modes: Seq[String] = Seq("Off", "Simple", "Directive")

  /** (control key, label in the infotext options list) */
  val Flags: Seq[(String, String)] = Seq("transpose" -> "transpose", "no_special_tokens" -> "no special tokens",
    "ignore_emphasis" -> "ignore emphasis", "bypass_normalization" -> "bypass normalization",
    "token_strings" -> "token strings", "overwrite" -> "overwrite")

  private def mode(text: String): Option[String] = Modes.find(_.equalsIgnoreCase(text.trim))

  /** PNG restore: the Clip Input / Clip Output keys enable the matching half; any other PNG switches the plugin off.
    * An unknown mode, format or option makes the whole replay `None` instead of guessing.
    */
  def replay(fields: Map[String, String]): Option[Values] =
    if !InfotextKeys.exists(fields.contains) then Some(Values(Map("enabled" -> "false")))
    else
      var out = Values.defaults(new ClipIoPlugin().controls).map ++ Map("enabled" -> "true")
      var ok = true
      def set(entries: (String, String)*): Unit = out = out ++ entries
      val hasInput = fields.contains(InputPositiveKey) || fields.contains(InputNegativeKey)
      set("input_enabled" -> hasInput.toString, "positive_mode" -> "Off", "negative_mode" -> "Off")
      fields.get(InputPositiveKey).foreach(v => mode(v) match { case Some(m) => set("positive_mode" -> m); case None => ok = false })
      fields.get(InputNegativeKey).foreach(v => mode(v) match { case Some(m) => set("negative_mode" -> m); case None => ok = false })
      val hasOutput = fields.contains(OutputKey) || fields.contains(OutputNegativeKey)
      set("output_enabled" -> hasOutput.toString)
      fields.get(OutputKey).foreach(v => set("output_name" -> v))
      fields.get(OutputNegativeKey).foreach(v => set("negative_output_name" -> v))
      fields.get(OutputFormatKey).foreach(v => v.trim.toLowerCase match
        case f @ ("csv" | "pt") => set("format" -> f)
        case _ => ok = false)
      fields.get(OutputOptionsKey).foreach: v =>
        val names = v.split(",").map(_.trim).filter(_.nonEmpty).toSeq
        if names.forall(n => Flags.exists(_._2 == n)) then Flags.foreach((key, label) => set(key -> names.contains(label).toString))
        else ok = false
      Option.when(ok)(Values(out))

/** Clip I/O (Filexor/Clip_IO, index 164): export the CLIP conditioning of the prompts to CSV / PT files, and feed
  * conditioning files (and CLIP prompt pieces) to the sampler in place of a prompt.
  */
class ClipIoPlugin extends HookPlugin:
  import ClipIoPlugin.*
  def id = Id
  def name = "Clip I/O"
  def hook = Id
  override def description =
    "Clip Output writes the CLIP conditioning of the prompts to CSV / PT files for investigation. Clip Input skips CLIP: " +
    "the Positive / Negative prompt boxes name conditioning files (and CLIP-encoded pieces) that are concatenated and " +
    "sent to the sampler. Files are in the folder $SDFX_CLIP_IO_DIR, by default ~/.cache/stablediffusion-nui/clip_io/conditioning."

  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Check("input_enabled", "Clip Input: use the prompts as conditioning file lists"),
    Choice("positive_mode", "Positive prompt mode", Modes, "Simple"),
    Choice("negative_mode", "Negative prompt mode", Modes, "Off"),
    Note("syntax", "Simple: space separated file names (quote names with spaces); a name without an extension also tries .csv " +
      "then .pt; text in triple quotes (\"\"\"like this\"\"\") is encoded by CLIP; everything is concatenated along the " +
      "token axis. Directive adds ?prompt(\"\"\"text\"\"\", clip_skip=2, no_padding=False) and ?eval(expression) / " +
      "?eval_ORDER(expression) (arithmetic on i, o, t, d). Prompt editing, AND and BREAK are not parsed here."),
    Check("output_enabled", "Clip Output: write the conditioning of the prompts while generating"),
    Text("output_name", "Output name (positive prompt)", placeholder = "e.g. cat or cat.pt"),
    Text("negative_output_name", "Output name (negative prompt, optional)", placeholder = "e.g. cat_negative"),
    Choice("format", "Format for names without an extension", Seq("csv", "pt"), "csv"),
    Check("transpose", "Transpose matrix (token axis in columns)"),
    Check("no_special_tokens", "Don't add bos / eos / pad tokens"),
    Check("ignore_emphasis", "Ignore emphasis"),
    Check("bypass_normalization", "Bypass conditioning normalization"),
    Check("token_strings", "Add token strings"),
    Check("overwrite", "Overwrite existing files"))

  def hookFields(v: Values): ujson.Obj =
    def lower(key: String, default: String) = v.str(key, default).trim.toLowerCase
    ujson.Obj("input_enabled" -> v.bool("input_enabled"), "positive_mode" -> lower("positive_mode", "simple"),
      "negative_mode" -> lower("negative_mode", "off"), "output_enabled" -> v.bool("output_enabled"),
      "output_name" -> v.str("output_name").trim, "negative_output_name" -> v.str("negative_output_name").trim,
      "format" -> lower("format", "csv"),
      "transpose" -> v.bool("transpose"), "no_special_tokens" -> v.bool("no_special_tokens"),
      "ignore_emphasis" -> v.bool("ignore_emphasis"), "bypass_normalization" -> v.bool("bypass_normalization"),
      "token_strings" -> v.bool("token_strings"), "overwrite" -> v.bool("overwrite"))

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] =
    def flag(k: String) = o.value.get(k).contains(ujson.Bool(true))
    def text(k: String) = o.value.get(k).collect { case ujson.Str(s) => s }.getOrElse("")
    def shown(k: String) = mode(text(k)).getOrElse("Off")
    val input =
      if flag("input_enabled") then
        Seq(InputPositiveKey -> shown("positive_mode"), InputNegativeKey -> shown("negative_mode")).filter(_._2 != "Off")
      else Nil
    val names = Seq(OutputKey -> text("output_name"), OutputNegativeKey -> text("negative_output_name")).filter(_._2.nonEmpty)
    val output =
      if flag("output_enabled") && names.nonEmpty then
        names ++ Seq(OutputFormatKey -> text("format")) ++
          Option(Flags.filter((key, _) => flag(key)).map(_._2).mkString(", ")).filter(_.nonEmpty).map(OutputOptionsKey -> _)
      else Nil
    input ++ output
