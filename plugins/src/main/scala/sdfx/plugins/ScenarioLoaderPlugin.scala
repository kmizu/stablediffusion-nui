package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import sdfx.core.prompttools.MiniYaml
import sdfx.core.prompttools.YNode.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Scenario files: a base prompt, variables and a list of scenes, one image per scene (own format; no upstream code). */
object Scenario:
  final case class Scene(name: String, prompt: String, negative: String, fields: Map[String, String])

  private val Var = "\\$\\{([A-Za-z0-9_]+)\\}".r

  def subst(text: String, vars: Map[String, String]): String =
    Var.replaceAllIn(text, m =>
      java.util.regex.Matcher.quoteReplacement(vars.getOrElse(m.group(1), throw new IllegalArgumentException("Unknown variable ${" + m.group(1) + "}"))))

  private def strs(n: sdfx.core.prompttools.YNode): Map[String, String] = n match
    case YMap(es) => es.collect { case (k, YStr(v)) => k -> v }.toMap
    case _        => Map.empty

  private def join(a: String, b: String) = Seq(a, b).map(_.trim.stripSuffix(",").trim).filter(_.nonEmpty).mkString(", ")

  /** Scenes with `base` / `negative` merged in front and `${var}` replaced (scene fields override global variables). */
  def parse(yaml: String): Vector[Scene] =
    val root = MiniYaml.parse(yaml)
    val top = strs(root)
    val globals = root match
      case YMap(es) => es.collectFirst { case ("variables", n) => strs(n) }.getOrElse(Map.empty)
      case _        => Map.empty[String, String]
    val scenes = root match
      case YMap(es) => es.collectFirst { case ("scenes", YSeq(items)) => items }.getOrElse(Vector.empty)
      case _        => Vector.empty
    require(scenes.nonEmpty, "The scenario needs a `scenes:` list")
    scenes.zipWithIndex.map { (n, i) =>
      val f = strs(n)
      val vars = globals ++ f
      Scene(f.getOrElse("name", s"scene ${i + 1}"),
        subst(join(top.getOrElse("base", ""), f.getOrElse("prompt", "")), vars),
        subst(join(top.getOrElse("negative", ""), f.getOrElse("negative", "")), vars), f)
    }

  def apply(p: GenerationParams, s: Scene): GenerationParams =
    val f = s.fields
    p.copy(prompt = s.prompt, negativePrompt = s.negative, batchSize = 1, nIter = 1,
      steps = f.get("steps").flatMap(_.toIntOption).getOrElse(p.steps),
      cfgScale = f.get("cfg").flatMap(_.toDoubleOption).getOrElse(p.cfgScale),
      seed = f.get("seed").flatMap(_.toLongOption).getOrElse(p.seed),
      width = f.get("width").flatMap(_.toIntOption).getOrElse(p.width),
      height = f.get("height").flatMap(_.toIntOption).getOrElse(p.height),
      samplerName = f.getOrElse("sampler", p.samplerName),
      extra = p.extra + ("Script" -> "Scenario loader") + ("Scenario scene" -> s.name))

class ScenarioLoaderPlugin extends Plugin:
  def id = "scenario_loader"
  def name = "Scenario loader"
  def kind = PluginKind.Script
  override def description = "Reads a scenario YAML (base, negative, variables, scenes[]) and generates one image per scene. " +
    "A scene may set prompt, negative, steps, cfg, seed, width, height, sampler; ${name} is replaced by a variable."
  override def controls: Seq[Control] = Seq(
    Text("file", "Scenario YAML file", ""),
    Text("only", "Only these scenes (comma separated names, empty = all)", "")
  )

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val path = v.str("file").trim
    require(path.nonEmpty, "A scenario file path is required")
    val f = Paths.get(path)
    require(Files.isRegularFile(f) && Files.size(f) <= 4L * 1024 * 1024, s"Cannot read $path (missing or larger than 4 MiB)")
    val all = Scenario.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))
    val only = v.str("only").split(",").map(_.trim).filter(_.nonEmpty).toSet
    val scenes = if only.isEmpty then all else all.filter(s => only(s.name))
    require(scenes.nonEmpty, "No scene matches")
    val outs = scala.collection.mutable.ArrayBuffer[OutputImage]()
    var running = true
    val it = scenes.iterator.zipWithIndex
    while running && it.hasNext do
      val (s, i) = it.next()
      ctx.status(s"Scenario ${i + 1}/${scenes.size}: ${s.name}", i, scenes.size)
      Support.step(ctx)(ctx.generate(Scenario(p0, s))) match
        case Some(o) => outs ++= o
        case None    => running = false
    JobOutput(outs.toSeq)
