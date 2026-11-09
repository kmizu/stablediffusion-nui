package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import sdfx.core.prompttools.MiniYaml
import sdfx.core.prompttools.YNode.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Prompt-set library in the Prompt Gallery YAML layout (avatars.yaml / tags.yaml); format taken from the upstream README. */
object PromptGallery:
  final case class Entry(path: Vector[String], value: String, negative: String, param: Map[String, String]):
    def name: String = path.mkString("/")

  private def fields(m: Vector[(String, sdfx.core.prompttools.YNode)]): Map[String, String] =
    m.collect { case (k, YStr(v)) => k -> v }.toMap

  /** A node is a prompt-set when it has a string `value`; otherwise it is a category and is searched recursively. */
  def parseSets(yaml: String): Vector[Entry] =
    def walk(path: Vector[String], n: sdfx.core.prompttools.YNode): Vector[Entry] = n match
      case YMap(es) =>
        val f = fields(es)
        if f.contains("value") then Vector(Entry(path, f("value"), f.getOrElse("negative", ""), parseParam(f.getOrElse("param", ""))))
        else es.flatMap((k, c) => walk(path :+ k, c))
      case _ => Vector.empty
    walk(Vector.empty, MiniYaml.parse(yaml))

  /** `Steps: 30, Sampler: Euler, CFG scale: 11.5, Size: 512x768` -> lower-cased key / value pairs. */
  def parseParam(s: String): Map[String, String] =
    s.split(",").iterator.map(_.trim).filter(_.contains(":")).map { kv =>
      val i = kv.indexOf(':')
      kv.take(i).trim.toLowerCase -> kv.drop(i + 1).trim
    }.toMap

  /** Finds a set by full path ("Figure/Hair/smile") or by a unique leaf name. */
  def find(sets: Seq[Entry], key: String): Entry =
    val k = key.trim
    sets.find(_.name == k).orElse {
      val hits = sets.filter(_.path.last == k)
      require(hits.size <= 1, s"Prompt-set '$k' is ambiguous: ${hits.map(_.name).mkString(", ")}")
      hits.headOption
    }.getOrElse(throw new IllegalArgumentException(s"Prompt-set '$k' not found"))

  private def join(parts: Seq[String]) = parts.map(_.trim.stripSuffix(",").trim).filter(_.nonEmpty).mkString(", ")

  def compose(avatar: Option[Entry], chosen: Seq[Entry], base: String, baseNegative: String): (String, String) =
    (join(avatar.map(_.value).toSeq ++ chosen.map(_.value) :+ base),
      join(avatar.map(_.negative).toSeq ++ chosen.map(_.negative) :+ baseNegative))

class PromptGalleryPlugin extends Plugin:
  import PromptGallery.*
  def id = "prompt_gallery"
  def name = "Prompt gallery"
  def kind = PluginKind.Script
  override def description = "Combines a character (avatars.yaml) with prompt-sets (tags.yaml) and generates the result, or one image " +
    "per prompt-set for previews. Applies a set's Steps and CFG scale; other `param` fields are ignored."
  override def controls: Seq[Control] = Seq(
    Text("avatars_file", "avatars.yaml path (optional)", ""),
    Text("tags_file", "tags.yaml path", ""),
    Text("avatar", "Avatar name (optional)", ""),
    Text("sets", "Prompt-sets (comma separated, full path or unique name)", "", multiline = true),
    Choice("mode", "Mode", Seq("Combine selected into one image", "One image per prompt-set"), "Combine selected into one image"),
    Check("use_param", "Apply Steps / CFG scale from a set's param (combined mode: the last set that has them)", true)
  )

  private def read(path: String): String =
    require(path.trim.nonEmpty, "A YAML file path is required")
    val f = Paths.get(path.trim)
    require(Files.isRegularFile(f) && Files.size(f) <= 8L * 1024 * 1024, s"Cannot read $path (missing or larger than 8 MiB)")
    new String(Files.readAllBytes(f), StandardCharsets.UTF_8)

  private def withParam(p: GenerationParams, e: Seq[Entry]): GenerationParams =
    val par = e.flatMap(_.param).toMap
    p.copy(steps = par.get("steps").flatMap(_.toIntOption).getOrElse(p.steps),
      cfgScale = par.get("cfg scale").flatMap(_.toDoubleOption).getOrElse(p.cfgScale))

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val sets = parseSets(read(v.str("tags_file")))
    val avatar = Option(v.str("avatar").trim).filter(_.nonEmpty).map(a => find(parseSets(read(v.str("avatars_file"))), a))
    val keys = v.str("sets").split("[,\n]").map(_.trim).filter(_.nonEmpty).toSeq
    require(keys.nonEmpty, "Select at least one prompt-set")
    val chosen = keys.map(find(sets, _))
    val usePar = v.bool("use_param", true)
    val jobs: Seq[(String, Seq[Entry])] =
      if v.str("mode").startsWith("One image") then chosen.map(e => e.name -> Seq(e))
      else Seq(chosen.map(_.name).mkString(" + ") -> chosen)
    ctx.log(s"Prompt gallery will create ${jobs.size} generation(s)")
    val outs = scala.collection.mutable.ArrayBuffer[OutputImage]()
    var running = true
    val it = jobs.iterator.zipWithIndex
    while running && it.hasNext do
      val ((label, es), i) = it.next()
      val (pos, neg) = compose(avatar, es, p0.prompt, p0.negativePrompt)
      val base = p0.copy(prompt = pos, negativePrompt = neg, batchSize = 1, nIter = 1,
        extra = p0.extra + ("Script" -> "Prompt gallery") + ("Prompt gallery set" -> label))
      ctx.status(s"Prompt gallery ${i + 1}/${jobs.size}: $label", i, jobs.size)
      Support.step(ctx)(ctx.generate(if usePar then withParam(base, es) else base)) match
        case Some(o) => outs ++= o
        case None    => running = false
    JobOutput(outs.toSeq)
