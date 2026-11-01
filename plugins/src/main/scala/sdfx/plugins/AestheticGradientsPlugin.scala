package sdfx.plugins

import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

import java.nio.file.Paths

/** Infotext keys of the Aesthetic Gradients extension, so its PNGs restore and NUI PNGs paste back into it. */
object AestheticGradientsPlugin:
  val EmbeddingKey = "Aesthetic embedding"
  val InfotextKeys: Seq[String] = Seq("Aesthetic LR", "Aesthetic weight", "Aesthetic steps", EmbeddingKey,
    "Aesthetic slerp", "Aesthetic text", "Aesthetic slerp angle", "Aesthetic text negative")

  /** The name shown in the infotext: the file name without directory and extension. */
  def embeddingName(spec: String): String =
    val name = Option(Paths.get(spec.trim).getFileName).map(_.toString).getOrElse(spec.trim)
    val lower = name.toLowerCase
    Seq(".pt", ".safetensors").find(lower.endsWith).fold(name)(ext => name.dropRight(ext.length))

  private def number(text: String): Option[Double] = text.trim.toDoubleOption.filter(_.isFinite)
  private def flag(text: String): Option[Boolean] = text.trim.toLowerCase match
    case "true" => Some(true)
    case "false" => Some(false)
    case _ => None

  /** PNG restore: a PNG with `Aesthetic embedding` enables the plugin, any other PNG switches it off. Values the
    * engine would reject (out of range, not a number) give `None`.
    */
  def replay(fields: Map[String, String]): Option[Values] =
    fields.get(EmbeddingKey).map(_.trim).filter(_.nonEmpty) match
      case None => Some(Values(Map("enabled" -> "false")))
      case Some(name) =>
        var out = Values.defaults(new AestheticGradientsPlugin().controls).map ++ Map("enabled" -> "true", "embedding" -> name)
        var ok = true
        def set(entries: (String, String)*): Unit = out = out ++ entries
        def bad(): Unit = ok = false
        fields.get("Aesthetic LR").foreach(v => number(v).filter(_ >= 0).fold(bad())(n => set("lr" -> Values.fmt(n))))
        fields.get("Aesthetic weight").foreach(v => number(v).filter(n => n >= 0 && n <= 1).fold(bad())(n => set("weight" -> Values.fmt(n))))
        fields.get("Aesthetic steps").foreach: v =>
          number(v).filter(n => n == math.rint(n) && n >= 0 && n <= 200).fold(bad())(n => set("steps" -> n.toInt.toString))
        fields.get("Aesthetic slerp").foreach(v => flag(v).fold(bad())(b => set("slerp" -> b.toString)))
        fields.get("Aesthetic text").foreach(v => set("text" -> v))
        fields.get("Aesthetic slerp angle").foreach(v => number(v).filter(n => n >= 0 && n <= 1).fold(bad())(n => set("slerp_angle" -> Values.fmt(n))))
        fields.get("Aesthetic text negative").foreach(v => flag(v).fold(bad())(b => set("negative" -> b.toString)))
        Option.when(ok)(Values(out))

/** Aesthetic Gradients (AUTOMATIC1111/stable-diffusion-webui-aesthetic-gradients, index 8): moves the CLIP text
  * encoder towards an aesthetic embedding (the mean CLIP embedding of a few pictures) for each prompt.
  */
class AestheticGradientsPlugin extends HookPlugin:
  import AestheticGradientsPlugin.*
  def id = "aesthetic_gradients"
  def name = "Aesthetic Gradients"
  def hook = "aesthetic_gradients"
  override def description =
    "Personalises the CLIP text encoder to an aesthetic embedding (the mean CLIP ViT-L/14 embedding of a few pictures, " +
    "a .pt file in an aesthetic_embeddings folder): for each prompt the encoder takes a few gradient steps towards the " +
    "embedding and the conditioning is blended with the original by the weight. SD 1.x and SDXL (its CLIP-L half) only. " +
    "Create an embedding with `python -m sdfx.aesthetic_gradients create NAME IMAGES...` or the aesthetic_embedding " +
    "engine request. CLIP ViT-L/14 (1.7 GB) is downloaded once on first use."

  override def controls: Seq[Control] = Seq(
    Check("enabled", "Enabled"),
    Text("embedding", "Aesthetic embedding (name or path of a .pt / .safetensors file)", placeholder = "e.g. sunset"),
    Slider("weight", "Aesthetic weight", 0, 1, 0.01, 0.9),
    IntField("steps", "Aesthetic steps", 0, 200, 20),
    Text("lr", "Aesthetic learning rate", "0.0001"),
    Check("slerp", "Slerp interpolation of the embedding with an aesthetic text"),
    Text("text", "Aesthetic text for the slerp", placeholder = "e.g. masterpiece, best quality"),
    Slider("slerp_angle", "Slerp angle", 0, 1, 0.01, 0.1),
    Check("negative", "Apply to the negative prompt instead of the positive prompt"))

  def hookFields(v: Values): ujson.Obj = ujson.Obj(
    "embedding" -> v.str("embedding").trim,
    "weight" -> v.double("weight", 0.9), "steps" -> v.int("steps", 20), "lr" -> v.double("lr", 0.0001),
    "slerp" -> v.bool("slerp"), "slerp_angle" -> v.double("slerp_angle", 0.1), "text" -> v.str("text"),
    "negative" -> v.bool("negative"))

  def infotextKeys(o: ujson.Obj): Seq[(String, String)] =
    def flag(k: String) = o.value.get(k).contains(ujson.Bool(true))
    val slerp = flag("slerp")
    Seq("Aesthetic LR" -> field(o, "lr"), "Aesthetic weight" -> field(o, "weight"), "Aesthetic steps" -> field(o, "steps"),
      EmbeddingKey -> embeddingName(field(o, "embedding")), "Aesthetic slerp" -> (if slerp then "True" else "False")) ++
      (if slerp then Seq("Aesthetic text" -> field(o, "text"), "Aesthetic slerp angle" -> field(o, "slerp_angle")) else Nil) ++
      (if flag("negative") then Seq("Aesthetic text negative" -> "True") else Nil)

  override def xyzAxes: Seq[XyzAxis] = Seq(
    numAxis("Aesthetic weight", "weight"), numAxis("Aesthetic steps", "steps"), numAxis("Aesthetic learning rate", "lr"),
    numAxis("Aesthetic slerp angle", "slerp_angle"),
    XyzAxis("Aesthetic embedding")((p, s) => Hooks.setOverride(p, hook, "embedding", ujson.Str(s.trim))),
    XyzAxis("Aesthetic text")((p, s) => Hooks.setOverride(p, hook, "text", ujson.Str(s.trim))))
