package sdfx.plugins

import sdfx.core.model.{GenerationParams, PromptStyle, Styles}
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Style selector for SDXL-style template files (`[{"name","prompt","negative_prompt"}]` JSON or A1111 CSV).
  * No style data is bundled: the upstream repository ships no confirmed licence file. */
object StyleSelector:
  def parseJson(text: String): Vector[PromptStyle] =
    ujson.read(text) match
      case ujson.Arr(items) =>
        items.iterator.zipWithIndex.map {
          case (o: ujson.Obj, i) =>
            def s(k: String) = o.value.get(k).collect { case ujson.Str(v) => v }.getOrElse("")
            val name = s("name").trim
            require(name.nonEmpty, s"Style #${i + 1} has no name")
            PromptStyle(name, s("prompt"), s("negative_prompt"))
          case (_, i) => throw new IllegalArgumentException(s"Style #${i + 1} is not an object")
        }.toVector
      case _ => throw new IllegalArgumentException("Style JSON must be an array of {name, prompt, negative_prompt}")

  def load(path: String): Vector[PromptStyle] =
    require(path.trim.nonEmpty, "A styles file (.json or .csv) is required")
    val f = Paths.get(path.trim)
    require(Files.isRegularFile(f) && Files.size(f) <= 16L * 1024 * 1024, s"Cannot read $path (missing or larger than 16 MiB)")
    val text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).stripPrefix("﻿")
    val styles = if f.getFileName.toString.toLowerCase.endsWith(".json") then parseJson(text) else Styles.parseCsv(text)
    val dup = styles.groupBy(_.name).collect { case (n, v) if v.size > 1 => n }
    require(dup.isEmpty, s"Duplicate style names: ${dup.mkString(", ")}")
    styles

  /** Matches names case-insensitively and by unique prefix-free name; unknown names are an error, never ignored. */
  def pick(all: Seq[PromptStyle], names: Seq[String]): Seq[PromptStyle] =
    names.map { n =>
      all.find(_.name == n).orElse(all.find(_.name.equalsIgnoreCase(n)))
        .getOrElse(throw new IllegalArgumentException(s"Style '$n' not found"))
    }

class StyleSelectorPlugin extends Plugin:
  import StyleSelector.*
  def id = "style_selector"
  def name = "Style selector"
  def kind = PluginKind.Script
  override def description = "Applies named styles from a JSON (sdxl_styles.json layout) or CSV file to the prompt, in the order given. " +
    "{prompt} marks where your prompt goes. Optionally generates one image per style."
  override def controls: Seq[Control] = Seq(
    Text("styles_file", "Styles file (.json or .csv)", ""),
    Text("styles", "Styles (comma or newline separated)", "", multiline = true),
    Choice("mode", "Mode", Seq("Apply all selected styles to one image", "One image per style"), "Apply all selected styles to one image"),
    Check("list_only", "Only list the styles in the log (no generation)")
  )

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val all = load(v.str("styles_file"))
    if v.bool("list_only") then
      ctx.log(s"Styles (${all.size}): ${all.map(_.name).mkString(", ")}")
      return JobOutput(Nil)
    val names = v.str("styles").split("[,\n]").map(_.trim).filter(_.nonEmpty).toSeq
    require(names.nonEmpty, "Select at least one style")
    val chosen = pick(all, names)
    val jobs: Seq[(String, Seq[PromptStyle])] =
      if v.str("mode").startsWith("One image") then chosen.map(s => s.name -> Seq(s)) else Seq(chosen.map(_.name).mkString(" + ") -> chosen)
    val outs = scala.collection.mutable.ArrayBuffer[OutputImage]()
    var running = true
    val it = jobs.iterator.zipWithIndex
    while running && it.hasNext do
      val ((label, ss), i) = it.next()
      val (pos, neg) = ss.foldLeft((p0.prompt, p0.negativePrompt)) { case ((p, n), s) => s.applyTo(p, n) }
      ctx.status(s"Style selector ${i + 1}/${jobs.size}: $label", i, jobs.size)
      val p = p0.copy(prompt = pos, negativePrompt = neg, batchSize = 1, nIter = 1,
        extra = p0.extra + ("Script" -> "Style selector") + ("Style selector" -> label))
      Support.step(ctx)(ctx.generate(p)) match
        case Some(o) => outs ++= o
        case None    => running = false
    JobOutput(outs.toSeq)
