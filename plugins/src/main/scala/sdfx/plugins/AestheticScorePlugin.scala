package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import java.awt.image.BufferedImage

/** Settings and infotext keys of the `aesthetic_score` target: tsngo/stable-diffusion-webui-aesthetic-image-scorer
  * (index 95, `aesthetic_score`), vladmandic/sd-extension-aesthetic-scorer (index 96, PNG chunk `score`) and
  * p1atdev/stable-diffusion-webui-cafe-aesthetic (index 97). The engine side is python/sdfx/aesthetic_score.py.
  */
object AestheticScorePlugin:
  /** Records the settings of a PNG so it can be replayed; the score keys below are results. */
  val SettingsKey = "Aesthetic score settings"
  val ImprovedKey = "aesthetic_score"
  val ScorerKeys: Seq[String] = Seq("Aesthetic Scorer ViT-L/14", "Aesthetic Scorer ViT-B/16")
  val CafeKeys: Seq[(String, String)] = Seq("cafe_aesthetic" -> "Cafe aesthetic", "cafe_style" -> "Cafe style", "cafe_waifu" -> "Cafe waifu")
  val HashKey = "hash"
  val ResultKeys: Seq[String] = Seq(ImprovedKey) ++ ScorerKeys ++ CafeKeys.map(_._2)
  /** Keys that identify a PNG as scored (`hash` alone is too generic to). */
  val TriggerKeys: Seq[String] = SettingsKey +: ResultKeys
  val InfotextKeys: Seq[String] = TriggerKeys :+ HashKey

  /** Control label to the engine's spelling. */
  val Simulacra: Seq[(String, String)] = Seq("Off" -> "off", "ViT-L/14" -> "vit-l-14", "ViT-B/16" -> "vit-b-16")
  private val Bools = Seq("improved", "cafe_aesthetic", "cafe_style", "cafe_waifu", "png_chunks", "generation_fields", "hash", "force_cpu")
  private val BoolDefaults = Map("improved" -> true, "cafe_aesthetic" -> false, "cafe_style" -> false, "cafe_waifu" -> false,
    "png_chunks" -> true, "generation_fields" -> false, "hash" -> false, "force_cpu" -> false)

  private def engineSimulacra(v: Values): String =
    val label = v.str("simulacra", "Off")
    Simulacra.collectFirst { case (`label`, engine) => engine }.getOrElse(
      throw new IllegalArgumentException(s"Aesthetic score: simulacra must be one of ${Simulacra.map(_._1).mkString(", ")} (got '$label')"))

  def scorerFields(v: Values): Seq[(String, ujson.Value)] =
    Bools.map(k => k -> ujson.Bool(v.bool(k, BoolDefaults(k)))) :+ ("simulacra" -> ujson.Str(engineSimulacra(v)))

  /** Compact JSON of the settings (booleans and the simulacra choice). */
  def encode(o: ujson.Obj): String =
    ujson.Obj.from(Bools.flatMap(k => o.value.get(k).map(k -> _)) ++ o.value.get("simulacra").map("simulacra" -> _)).render()

  /** PNG / MCP restoration. The settings key restores exactly; a PNG that has only score keys (written by the
    * original extensions or by an older run) enables the scorers whose key is present. Without any of them the
    * plugin is switched off. A malformed settings value restores nothing. */
  def replay(fields: Map[String, String]): Option[Values] =
    fields.get(SettingsKey) match
      case Some(raw) =>
        scala.util.Try(ujson.read(raw)).toOption.collect { case o: ujson.Obj if raw.length <= 2048 => o }.flatMap { o =>
          val keys = o.value.keySet
          if !keys.subsetOf((Bools :+ "simulacra").toSet) then None
          else
            val bools = o.value.toSeq.collect { case (k, ujson.Bool(b)) if k != "simulacra" => k -> b.toString }
            val simulacra = o.value.get("simulacra") match
              case None => Some(Nil)
              case Some(ujson.Str(s)) => Simulacra.collectFirst { case (label, `s`) => Seq("simulacra" -> label) }
              case _ => None
            val boolsValid = o.value.forall { case ("simulacra", _) => true; case (_, _: ujson.Bool) => true; case _ => false }
            if !boolsValid then None
            else simulacra.map { s =>
              val base = Values.defaults(new AestheticScorePlugin().controls).map
              Values(base ++ bools ++ s ++ Map("enabled" -> "true"))
            }
        }
      case None =>
        val present = ResultKeys.filter(fields.contains)
        if present.isEmpty then Some(Values(Map("enabled" -> "false")))
        else
          val base = Values.defaults(new AestheticScorePlugin().controls).map
          val simulacra =
            if fields.contains(ScorerKeys(1)) && !fields.contains(ScorerKeys.head) then "ViT-B/16"
            else if fields.contains(ScorerKeys.head) then "ViT-L/14" else "Off"
          Some(Values(base ++ Map("enabled" -> "true", "improved" -> fields.contains(ImprovedKey).toString, "simulacra" -> simulacra,
            "hash" -> fields.contains("hash").toString) ++ CafeKeys.map((k, label) => k -> fields.contains(label).toString)))

/** Scores every final image (after censoring and any upscale, just before saving) and writes the scores to the
  * generation parameters text and, optionally, PNG info chunks. */
class AestheticScorePlugin extends HookPlugin:
  import AestheticScorePlugin.*
  def id = "aesthetic_score"
  def name = "Aesthetic Score"
  def hook = "aesthetic_score"
  override def description =
    "Scores every final image and writes the score to its metadata. Aesthetic Image Scorer: CLIP ViT-L/14 with the " +
    "improved aesthetic predictor (Apache-2.0), 0 to 10, written as aesthetic_score with one decimal. Aesthetic " +
    "Scorer: the simulacra linear predictor (MIT) on CLIP ViT-L/14 or ViT-B/16, 0 to 10, also written as the PNG info " +
    "chunk score. cafe-aesthetic: the cafeai aesthetic, style (anime, real_life, 3d, manga_like, other) and waifu " +
    "classifiers (AGPL-3.0); the most probable class and its probability go to the infotext. Models download on " +
    "first use (CLIP ViT-L/14 1.7 GB, ViT-B/16 0.6 GB, each cafe classifier 0.4 GB) and are checked against pinned " +
    "hashes; they wait in RAM and use the GPU only while an image is scored. Windows tags/categories and EXIF are not " +
    "provided; scores are a taste model, not a quality guarantee."
  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Check("improved", "Aesthetic Image Scorer: improved predictor on CLIP ViT-L/14 (aesthetic_score)", true),
    Choice("simulacra", "Aesthetic Scorer: simulacra predictor on CLIP", Simulacra.map(_._1), "Off"),
    Check("cafe_aesthetic", "cafe-aesthetic: aesthetic / not_aesthetic"),
    Check("cafe_style", "cafe-aesthetic: style (anime, real_life, 3d, manga_like, other)"),
    Check("cafe_waifu", "cafe-aesthetic: waifu / not_waifu"),
    Check("png_chunks", "Also save the scores as PNG info chunks (aesthetic_score, score, cafe_*)", true),
    Check("generation_fields", "Also save sampler, cfg_scale, sd_model_hash and seed (PNG chunks)"),
    Check("hash", "Also save hash: md5 of the generation parameters (experimental, as upstream)"),
    Check("force_cpu", "Force CPU")
  )

  def hookFields(v: Values): ujson.Obj = ujson.Obj.from(scorerFields(v))

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] = Seq(SettingsKey -> encode(o))

/** Aesthetic scores for Extras (and so for Extras batch from a directory): the image is returned unchanged and the
  * scores are written to its infotext. */
class AestheticScoreExtrasPlugin extends ExtrasPlugin:
  import AestheticScorePlugin.*
  def id = "aesthetic_score_extras"
  def name = "Aesthetic Score"
  override def description =
    "Scores the image with the chosen scorers and writes the result to the infotext; the pixels are not changed. " +
    "Use Extras batch from a directory to score a folder (cafe-aesthetic's batch tab). The models are the ones of the " +
    "Aesthetic Score generation plugin and download on first use."
  override def controls: Seq[Control] = Seq(
    Check("improved", "Aesthetic Image Scorer: improved predictor on CLIP ViT-L/14 (aesthetic_score)", true),
    Choice("simulacra", "Aesthetic Scorer: simulacra predictor on CLIP", Simulacra.map(_._1), "Off"),
    Check("cafe_aesthetic", "cafe-aesthetic: aesthetic / not_aesthetic", true),
    Check("cafe_style", "cafe-aesthetic: style (anime, real_life, 3d, manga_like, other)"),
    Check("cafe_waifu", "cafe-aesthetic: waifu / not_waifu"),
    Check("force_cpu", "Force CPU")
  )

  private[plugins] def toolParams(v: Values): Seq[(String, ujson.Value)] =
    val params = Seq("improved", "cafe_aesthetic", "cafe_style", "cafe_waifu", "force_cpu").map(k => k -> ujson.Bool(v.bool(k, k == "improved" || k == "cafe_aesthetic"))) :+
      ("simulacra" -> ujson.Str(Simulacra.collectFirst { case (label, engine) if label == v.str("simulacra", "Off") => engine }.getOrElse(
        throw new IllegalArgumentException(s"Aesthetic score: simulacra must be one of ${Simulacra.map(_._1).mkString(", ")}"))))
    if !params.exists { case (k, ujson.Bool(true)) => k != "force_cpu"; case (_, ujson.Str(s)) => s != "off"; case _ => false } then
      throw new IllegalArgumentException("Aesthetic score: enable at least one scorer")
    params

  def transform(img: BufferedImage, p: GenerationParams, v: Values, ctx: PluginContext): Seq[(BufferedImage, Seq[(String, String)])] =
    val r = ImageTools.run(ctx, "aesthetic_score", img, toolParams(v)*)
    try
      val fields = ResultKeys.flatMap(k => r.info.get(k).map(k -> _))
      ctx.log("Aesthetic score: " + fields.map((k, value) => s"$k=$value").mkString("; "))
      Seq((img, fields))
    finally Support.deleteQuietly(r.outputs.values.toSeq*)
