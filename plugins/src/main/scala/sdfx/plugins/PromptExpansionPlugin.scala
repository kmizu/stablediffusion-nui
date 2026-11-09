package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.nio.file.{Files, Paths}
import scala.util.Random

/** Prompt expansion with a user-supplied GPT-2 model folder (e.g. a Fooocus-style expansion fine-tune stored as F32 safetensors);
  * the model is not bundled. The expanded prompt replaces the prompt for one generation and is recorded in the infotext. */
class PromptExpansionPlugin extends Plugin:
  def id = "prompt_expansion"
  def name = "Prompt expansion"
  def kind = PluginKind.Script
  override def description = "Expands the prompt with a local GPT-2 model folder (model.safetensors in F32, vocab.json, merges.txt, config.json), " +
    "then generates one image. The seed makes the expansion reproducible. No model is bundled."
  override def controls: Seq[Control] = Seq(
    Text("model_dir", "GPT-2 model folder", ""),
    IntField("max_tokens", "New tokens", 1, 256, 48),
    Slider("temperature", "Temperature (0 = greedy)", 0, 2, 0.05, 0.8),
    IntField("top_k", "Top-k", 1, 1000, 50),
    Check("keep_original", "Keep the original prompt in front of the expansion", true)
  )

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val d = v.str("model_dir").trim
    require(d.nonEmpty, "A GPT-2 model folder is required")
    val dir = Paths.get(d)
    require(Files.isRegularFile(dir.resolve("model.safetensors")), s"$d has no model.safetensors")
    require(p0.prompt.trim.nonEmpty, "Write a prompt to expand")
    val rnd = if p0.seed >= 0 then new Random(p0.seed) else new Random()
    val cont = Gpt2.generate(Gpt2.Model.load(dir), Gpt2.Tokenizer.load(dir), p0.prompt.trim, v.int("max_tokens", 48),
      v.double("temperature", 0.8), v.int("top_k", 50), rnd).linesIterator.nextOption().getOrElse("")
    val base = p0.prompt.trim
    val expanded =
      if v.bool("keep_original", true) then (base.stripSuffix(",") + (if cont.startsWith(",") || cont.startsWith(" ") then "" else " ") + cont).replaceAll("[ \t]+", " ").trim
      else cont.trim.stripPrefix(",").trim
    require(expanded.nonEmpty, "The model returned no text")
    ctx.log(s"Prompt expansion: $expanded")
    Support.step(ctx)(ctx.generate(p0.copy(prompt = expanded, extra = p0.extra + ("Script" -> "Prompt expansion") + ("Prompt expansion original" -> base))))
      .fold(JobOutput(Nil))(o => JobOutput(o))
