package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext, ManualLoraMetadata}
import sdfx.core.model.{GenerationParams, Img2ImgParams, LoraSyntax}
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import java.nio.file.Path
import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

/** Old SD firstpasser (light-and-ray/sd-webui-old-sd-firstpasser, no licence file), re-implemented from the README
  * only: a txt2img script that first generates the image with an SD 1.x (or SDXL) checkpoint, upscales it, and lets
  * the main checkpoint finish it as img2img, so SD 1.x LoRAs, embeddings and ControlNets can steer an SDXL model.
  * Every design choice that the README leaves open is marked in the documentation of the member that makes it.
  */
object OldSdFirstpasserPlugin:
  val Id = "old_sd_firstpasser"
  val Networks: Seq[String] = Seq("Auto", "SD1", "SDXL")
  /** Shorter side of a firstpass image when "Firstpass resolution" is 0: what each family was trained at. */
  val NativeSide: Map[String, Int] = Map("SD1" -> 512, "SDXL" -> 1024)
  val MinSide = 64
  val MaxLongSide = 2048
  /** Engine checkpoint kinds per family (`models.detect_kind`). */
  def familyOf(kind: String): Option[String] = kind match
    case "sd15" | "sd15-inpaint" => Some("SD1")
    case "sdxl" | "sdxl-inpaint" => Some("SDXL")
    case _                       => None

  // Infotext keys (the upstream key names are not documented in its README, these are this app's own).
  val Key = "Old SD firstpasser"
  val StepsKey = "Old SD firstpasser steps"
  val DenoisingKey = "Old SD firstpasser denoising"
  val UpscalerKey = "Old SD firstpasser upscaler"
  val CheckpointKey = "Old SD firstpasser checkpoint"
  val HashKey = "Old SD firstpasser model hash"
  val SizeKey = "Old SD firstpasser size"
  val ResolutionKey = "Old SD firstpasser resolution"
  val MainLorasKey = "Old SD firstpasser main LoRAs"
  val InfotextKeys: Seq[String] = Seq(Key, StepsKey, DenoisingKey, UpscalerKey, CheckpointKey, HashKey, SizeKey, ResolutionKey, MainLorasKey)

  /** "Auto": the other family than the main checkpoint's (SD 1.x pass for SDXL, SDXL pass otherwise). The README
    * guesses from the LoRA metadata; this app does not read LoRA metadata here, so choose the type yourself when the
    * LoRAs and the checkpoints disagree.
    */
  def resolveNetwork(choice: String, mainKind: Option[String]): String = choice match
    case "SD1" | "SDXL" => choice
    case "Auto" =>
      mainKind.flatMap(familyOf) match
        case Some("SDXL") => "SD1"
        case Some(_)      => "SDXL"
        case None         => throw new IllegalArgumentException("Old SD firstpasser: the main checkpoint's type is unknown; choose SD1 or SDXL as the network type")
    case other => throw new IllegalArgumentException(s"Old SD firstpasser: network type must be one of ${Networks.mkString(", ")} (got '$other')")

  private def round8(x: Double): Int = math.max(MinSide, (math.round(x / 8.0) * 8).toInt)

  /** The firstpass image size: the shorter side of the target scaled to `side` (0 = the family's native side), the
    * aspect ratio kept, multiples of 8, the longer side capped at 2048.
    */
  def firstpassSize(width: Int, height: Int, network: String, side: Int): (Int, Int) =
    val s = (if side > 0 then side else NativeSide(network)).toDouble / math.min(width, height)
    val cap = math.min(1.0, MaxLongSide.toDouble / (math.max(width, height) * s))
    (round8(width * s * cap), round8(height * s * cap))

  /** Upscale factor that makes the firstpass image cover the target; at most 1 means no upscaler is needed (the
    * engine resizes the img2img init image to the target anyway).
    */
  def upscaleFactor(fw: Int, fh: Int, tw: Int, th: Int): Double = math.max(tw.toDouble / fw, th.toDouble / fh)

  final case class Settings(steps: Int, denoising: Double, upscaler: String, sd1: String, sdxl: String, network: String,
      side: Int, mainLoras: Boolean)

  object Settings:
    private def flag(v: Values, key: String): Boolean = v.map.get(key).map(_.trim.toLowerCase) match
      case None | Some("") | Some("false") | Some("0") | Some("no") => false
      case Some("true") | Some("1") | Some("yes")                   => true
      case Some(s) => throw new IllegalArgumentException(s"Old SD firstpasser: $key must be true or false (got '$s')")
    private def number(v: Values, key: String, default: Double, min: Double, max: Double): Double =
      v.map.get(key).map(_.trim) match
        case None | Some("") => default
        case Some(s) =>
          val d = s.toDoubleOption.filter(x => !x.isNaN && !x.isInfinite)
            .getOrElse(throw new IllegalArgumentException(s"Old SD firstpasser: $key must be a number (got '$s')"))
          if d < min - 1e-9 || d > max + 1e-9 then
            throw new IllegalArgumentException(s"Old SD firstpasser: $key must be in ${Values.fmt(min)}..${Values.fmt(max)} (got $s)")
          d
    def parse(v: Values): Settings =
      val steps = number(v, "steps", 20, 1, 150)
      if steps != math.rint(steps) then throw new IllegalArgumentException(s"Old SD firstpasser: steps must be a whole number (got ${Values.fmt(steps)})")
      val side = number(v, "resolution", 0, 0, MaxLongSide)
      if side != math.rint(side) || (side != 0 && side < 256) then
        throw new IllegalArgumentException(s"Old SD firstpasser: resolution must be 0 (native) or a whole number in 256..$MaxLongSide (got ${Values.fmt(side)})")
      val network = v.str("network_type", "Auto")
      if !Networks.contains(network) then throw new IllegalArgumentException(s"Old SD firstpasser: network type must be one of ${Networks.mkString(", ")} (got '$network')")
      Settings(steps.toInt, number(v, "denoising", 0.55, 0, 1), v.str("upscaler", "Lanczos"), v.str("sd1_checkpoint").trim,
        v.str("sdxl_checkpoint").trim, network, side.toInt, flag(v, "main_loras"))

  private def nonePath(s: String): Boolean = s.isEmpty || s == "None"

  /** `Values` for the form from the infotext of an image this script produced. */
  def replay(fields: Map[String, String]): Option[Values] =
    for
      network <- fields.get(Key).filter(Set("SD1", "SDXL"))
      steps <- fields.get(StepsKey).flatMap(_.trim.toIntOption).filter(n => n >= 1 && n <= 150)
      denoising <- fields.get(DenoisingKey).flatMap(_.trim.toDoubleOption).filter(d => d >= 0 && d <= 1)
      checkpoint = fields.get(CheckpointKey).getOrElse("")
    yield Values(Map(
      "network_type" -> network,
      "steps" -> steps.toString,
      "denoising" -> Values.fmt(denoising),
      "upscaler" -> fields.getOrElse(UpscalerKey, "Lanczos"),
      "sd1_checkpoint" -> (if network == "SD1" then checkpoint else ""),
      "sdxl_checkpoint" -> (if network == "SDXL" then checkpoint else ""),
      "resolution" -> fields.get(ResolutionKey).flatMap(_.trim.toIntOption).filter(n => n == 0 || (n >= 256 && n <= MaxLongSide)).getOrElse(0).toString,
      "main_loras" -> fields.get(MainLorasKey).map(_.trim.equalsIgnoreCase("true")).getOrElse(false).toString))

/** The script itself; see [[OldSdFirstpasserPlugin]]. Flow per job:
  *  1. every image of the batch is generated with the firstpass checkpoint at the firstpass size, `steps` steps, with
  *     the request's prompt (LoRA tags and manual LoRAs included), ControlNet units and AlwaysOn plugins;
  *  2. each image is upscaled to the target size with the chosen upscaler;
  *  3. the main checkpoint refines it as img2img (same seed, sampler, CFG and steps as the request; LoRAs and
  *     ControlNets only when "LoRAs in the main pass" is ticked; AlwaysOn plugins as the host applies them to every
  *     generation of a script);
  *  4. Hires fix, when requested, runs afterwards as one more img2img pass on the main checkpoint.
  * The finished image is saved once, with the infotext of the request, so replaying it re-runs the script.
  */
class OldSdFirstpasserPlugin extends Plugin:
  import OldSdFirstpasserPlugin.*

  def id = Id
  def name = "Old SD firstpasser"
  def kind = PluginKind.Script
  override def tabs: Set[Tab] = Set(Tab.Txt2Img)
  override def description = "Generates the first pass with an SD 1.x (or SDXL) checkpoint, upscales it and lets the " +
    "main checkpoint finish it, so SD 1.x LoRAs, embeddings and ControlNets work with SDXL. txt2img only."

  override def controls: Seq[Control] = Seq(
    IntField("steps", "Firstpass steps", 1, 150, 20),
    Slider("denoising", "Firstpass denoising", 0, 1, 0.01, 0.55),
    DynamicChoice("upscaler", "Firstpass upscaler", ChoiceSource.Upscalers, "Lanczos", allowNone = true),
    DynamicChoice("sd1_checkpoint", "Checkpoint for SD 1.x pass", ChoiceSource.Checkpoints, "", allowNone = true),
    DynamicChoice("sdxl_checkpoint", "Checkpoint for SDXL pass", ChoiceSource.Checkpoints, "", allowNone = true),
    Choice("network_type", "Firstpass network type", Networks, "Auto"),
    Note("network_help", "Auto means the other family than the main checkpoint (an SD 1.x pass for an SDXL main model). " +
      "LoRA metadata is not read, so choose the type when it differs from that."),
    IntField("resolution", "Firstpass resolution (shorter side, 0 = native)", 0, MaxLongSide, 0),
    Check("main_loras", "LoRAs in the main pass"),
    Section("info", "Info - Old SD firstpasser", expanded = false),
    Note("help_flow", "The firstpass checkpoint generates the image first (at 512 px for SD 1.x, 1024 px for SDXL on the shorter side " +
      "unless Firstpass resolution says otherwise), then the main checkpoint refines the upscaled image with the denoising above. " +
      "Firstpass steps is the step count of the first pass; the main pass uses the Sampling steps field."),
    Note("help_lora", "LoRA tags in the prompt, manual LoRAs, embeddings and ControlNet units act in the firstpass. The main pass " +
      "gets the prompt without LoRA tags unless LoRAs in the main pass is ticked (then ControlNet units are kept there too). " +
      "AlwaysOn plugins apply to both passes."),
    Note("help_hires", "Hires fix is run after the main pass on the main checkpoint. Latent hires upscalers resize the image instead."),
    Note("help_models", "Loading the checkpoints one after the other takes time: an SDXL main checkpoint is reloaded for the main pass.")
  )

  private def fail(msg: String): Nothing = throw new IllegalArgumentException(s"$name: $msg")

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    if p0.img2img.isDefined then fail("works in the txt2img tab only")
    val s = Settings.parse(v)
    val mainRequested = p0.checkpoint.map(_.trim).filter(n => n.nonEmpty && n != "None")
    val main = ctx.checkpointInfo(mainRequested).getOrElse(
      fail(mainRequested.fold("load a main checkpoint first")(n => s"main checkpoint not found: $n")))
    val network = resolveNetwork(s.network, Some(main.kind))
    val chosen = if network == "SD1" then s.sd1 else s.sdxl
    if nonePath(chosen) then fail(s"choose the ${if network == "SD1" then "SD 1.x" else "SDXL"} firstpass checkpoint")
    val first = ctx.checkpointInfo(Some(chosen)).getOrElse(fail(s"firstpass checkpoint not found: $chosen"))
    if familyOf(first.kind).forall(_ != network) then
      fail(s"checkpoint ${first.name} is ${first.kind}, not an ${if network == "SD1" then "SD 1.x" else "SDXL"} model; choose the matching checkpoint or network type")
    val (fw, fh) = firstpassSize(p0.width, p0.height, network, s.side)
    val factor = upscaleFactor(fw, fh, p0.width, p0.height)
    val usePixel = !nonePath(s.upscaler) && factor > 1.0 + 1e-9
    val seed = Support.fixSeed(p0.seed)
    val noHires = p0.hires.copy(enabled = false)
    val extras = ListMap(
      Key -> network, StepsKey -> s.steps.toString, DenoisingKey -> Values.fmt(s.denoising), UpscalerKey -> s.upscaler,
      CheckpointKey -> first.name, SizeKey -> s"${fw}x$fh", ResolutionKey -> s.side.toString, MainLorasKey -> (if s.mainLoras then "True" else "False"))
    val total = math.max(1, p0.imagesPerJob) * (if p0.hires.enabled then 3 else 2)
    ctx.log(s"$name: ${first.name} (${network}) ${fw}x$fh -> ${main.name} ${p0.width}x${p0.height}" +
      (if usePixel then s", upscaler ${s.upscaler} x${Values.fmt(math.round(factor * 100) / 100.0)}" else ", resized by the engine"))
    // 1. firstpass, the whole batch on the firstpass checkpoint
    val firstParams = p0.copy(seed = seed, width = fw, height = fh, steps = s.steps, hires = noHires, img2img = None,
      checkpoint = Some(first.name), vae = None)
    ctx.status(s"$name: firstpass", 0, total)
    var onMain = true // false while the firstpass checkpoint is the loaded one
    val firstImages: Seq[OutputImage] =
      try
        onMain = false
        Support.step(ctx)(ctx.generateTransient(firstParams)).getOrElse(Nil)
      catch
        case e: Throwable =>
          restoreMain(ctx, main.name, p0.vae)
          throw e
    val temps = ArrayBuffer[Path]()
    temps ++= firstImages.map(_.path)
    val finals = ArrayBuffer[OutputImage]()
    var done = firstImages.size
    try
      var running = firstImages.nonEmpty
      val it = firstImages.iterator
      while running && it.hasNext do
        val f = it.next()
        // 2. upscale
        val init =
          if usePixel then Support.step(ctx)(ctx.upscale(f.path, s.upscaler, factor)).map { p => temps += p; p }
          else Some(f.path)
        init match
          case None => running = false
          case Some(initPath) =>
            // 3. main pass
            val keep = s.mainLoras
            def clean(t: String) = if keep then t else LoraSyntax.extract(t).prompt
            val mainParams = p0.copy(seed = f.seed, subseedStrength = 0, batchSize = 1, nIter = 1, hires = noHires,
              prompt = clean(p0.prompt), negativePrompt = clean(p0.negativePrompt),
              loras = if keep then p0.loras else Nil, controlnet = if keep then p0.controlnet else Nil,
              checkpoint = Some(main.name), extra = p0.extra ++ extras ++ Option(hashOf(f.infotext)).filter(_.nonEmpty).map(HashKey -> _),
              img2img = Some(Img2ImgParams(initPath.toString, denoisingStrength = s.denoising, resizeMode = 0)))
            ctx.status(s"$name: main pass ${done - firstImages.size + 1}/${firstImages.size}", done, total)
            onMain = false
            Support.step(ctx)(ctx.generateTransient(mainParams).headOption).flatten match
              case None => running = false
              case Some(m) =>
                onMain = true
                temps += m.path
                done += 1
                // 4. hires fix
                val last =
                  if p0.hires.enabled then
                    ctx.status(s"$name: hires pass", done, total)
                    hiresPass(p0, mainParams, m, keep, ctx, temps)
                  else Some(m)
                last match
                  case None => running = false
                  case Some(l) =>
                    done += 1
                    finals += finish(p0, mainParams, f, l, ctx)
      if !running then ctx.log(s"$name interrupted; returning ${finals.size} finished image(s)")
      JobOutput(finals.toSeq)
    finally
      Support.deleteQuietly(temps.toSeq*)
      if !onMain then restoreMain(ctx, main.name, p0.vae)

  /** After an interrupted or failed run the firstpass checkpoint would stay loaded for the next generation. */
  private def restoreMain(ctx: PluginContext, main: String, vae: Option[String]): Unit =
    try ctx.loadCheckpoint(main, vae)
    catch case e: Exception => ctx.log(s"$name: could not reload the main checkpoint $main: ${e.getMessage}")

  /** The firstpass checkpoint's model hash, from its own infotext. */
  private def hashOf(infotext: String): String = Infotext.parse(infotext).get("Model hash").getOrElse("")

  /** Hires fix as an img2img pass on the main checkpoint: the upscaler (a latent one resizes instead) takes the main
    * pass output to the hires size, the hires steps, sampler, scheduler, CFG, prompts and denoising apply.
    */
  private def hiresPass(p0: GenerationParams, main: GenerationParams, m: OutputImage, keep: Boolean, ctx: PluginContext,
      temps: ArrayBuffer[Path]): Option[OutputImage] =
    val hr = p0.hires
    val (tw, th) = hr.targetSize(p0.width, p0.height)
    val factor = upscaleFactor(p0.width, p0.height, tw, th)
    val pixel = hr.upscaler.nonEmpty && hr.upscaler != "None" && !hr.upscaler.startsWith("Latent") && factor > 1.0 + 1e-9
    val init =
      if pixel then Support.step(ctx)(ctx.upscale(m.path, hr.upscaler, factor)).map { p => temps += p; p }
      else Some(m.path)
    init.flatMap { initPath =>
      def clean(t: String) = if keep then t else LoraSyntax.extract(t).prompt
      val hp = main.copy(width = tw, height = th,
        steps = if hr.secondPassSteps > 0 then hr.secondPassSteps else main.steps,
        samplerName = hr.samplerName.getOrElse(main.samplerName), scheduler = hr.scheduler.getOrElse(main.scheduler),
        cfgScale = hr.cfgScale.getOrElse(main.cfgScale),
        prompt = if hr.prompt.nonEmpty then clean(hr.prompt) else main.prompt,
        negativePrompt = if hr.negativePrompt.nonEmpty then clean(hr.negativePrompt) else main.negativePrompt,
        img2img = Some(Img2ImgParams(initPath.toString, denoisingStrength = hr.denoisingStrength, resizeMode = 0)))
      Support.step(ctx)(ctx.generateTransient(hp).headOption).flatten.map { o => temps += o.path; o }
    }

  /** Saves the last pass once, with the request's own infotext (Steps, Size, Hires fields) and the script keys. */
  private def finish(p0: GenerationParams, main: GenerationParams, firstpass: OutputImage, last: OutputImage,
      ctx: PluginContext): OutputImage =
    val firstText = Infotext.parse(firstpass.infotext)
    val lastText = Infotext.parse(last.infotext)
    val request = p0.copy(seed = last.seed, img2img = None)
    val base = Infotext.fields(request, ImageFacts(seed = last.seed, subseed = p0.subseed))
    val overridden = Seq("Steps", "Size", "Seed", "Denoising strength", "Variation seed", "Variation seed strength", "Batch size",
      "Batch count", "Resize mode", ManualLoraMetadata.key, "Version") ++ lastText.fields.keys.filter(_.startsWith("Hires "))
    val kept = lastText.fields.filterNot((k, _) => overridden.contains(k))
    val fromRequest = base.filter((k, _) => Seq("Steps", "Size", "Seed", "Denoising strength", "Variation seed",
      "Variation seed strength", ManualLoraMetadata.key).contains(k) || k.startsWith("Hires "))
    val fromFirst = firstText.fields.filter((k, _) => k == "Lora hashes" || k == "TI hashes")
    val scriptKeys = ListMap.from(InfotextKeys.flatMap(k => main.extra.get(k).map(k -> _)))
    val fields = ListMap.from(kept.toSeq.filterNot((k, _) => fromFirst.contains(k) || scriptKeys.contains(k))) ++ fromRequest ++
      fromFirst ++ scriptKeys ++ ListMap.from(lastText.fields.get("Version").map("Version" -> _))
    val text = Infotext.render(firstText.prompt, firstText.negativePrompt, fields)
    val params = firstpass.params.copy(width = p0.width, height = p0.height, steps = p0.steps, seed = last.seed,
      hires = p0.hires, img2img = None, checkpoint = main.checkpoint, vae = p0.vae, controlnet = p0.controlnet,
      extra = main.extra)
    val image = Support.readImage(ctx, last.path)
    ctx.saveImage(image, "txt2img", params, text)
