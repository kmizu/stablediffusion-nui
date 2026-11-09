package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Random

/** Prompt continuation by a tag-level Markov chain trained on a corpus of prompts the user supplies.
  * This is NOT a GPT-2 model: it only reuses tag orders seen in the corpus (own design; no upstream code or weights). */
object PromptGenerator:
  type Chain = Map[String, Vector[String]]

  def tags(prompt: String): Vector[String] =
    prompt.split(",").iterator.map(_.trim).filter(_.nonEmpty).toVector

  /** Transition lists: tag -> tags that followed it in some corpus line (duplicates keep frequency). */
  def train(corpus: Iterable[String]): Chain =
    val m = scala.collection.mutable.Map[String, Vector[String]]().withDefaultValue(Vector.empty)
    for line <- corpus; ts = tags(line); (a, b) <- ts.zip(ts.drop(1)) do m(a.toLowerCase) = m(a.toLowerCase) :+ b
    m.toMap

  /** Appends up to `n` tags after the seed tags; stops when the chain has no continuation. Never repeats a tag. */
  def extend(chain: Chain, seed: Vector[String], n: Int, rnd: Random): Vector[String] =
    val out = scala.collection.mutable.ArrayBuffer.from(seed)
    val seen = scala.collection.mutable.Set.from(seed.map(_.toLowerCase))
    var last = seed.lastOption
    var k = 0
    while k < n && last.isDefined do
      val next = chain.getOrElse(last.get.toLowerCase, Vector.empty).filterNot(t => seen(t.toLowerCase))
      if next.isEmpty then last = None
      else
        val t = next(rnd.nextInt(next.size))
        out += t; seen += t.toLowerCase; last = Some(t); k += 1
    out.toVector

class PromptGeneratorPlugin extends Plugin:
  import PromptGenerator.*
  def id = "prompt_generator"
  def name = "Prompt generator"
  def kind = PluginKind.Script
  override def description = "Extends the prompt, one image per variant. Method GPT-2: continues the prompt with a local GPT-2 model " +
    "folder (distilgpt2 or a promptgen-style fine-tune; model.safetensors in F32, vocab.json, merges.txt, config.json) run on the JVM. " +
    "Method Markov: tag chain trained on a corpus file (one prompt per line)."
  override def controls: Seq[Control] = Seq(
    Choice("method", "Method", Seq("GPT-2 (model folder)", "Markov (corpus file)"), "GPT-2 (model folder)"),
    Text("model_dir", "GPT-2 model folder", ""),
    IntField("max_tokens", "GPT-2: new tokens", 1, 256, 40),
    Slider("temperature", "GPT-2: temperature (0 = greedy)", 0, 2, 0.05, 0.9),
    IntField("top_k", "GPT-2: top-k", 1, 1000, 50),
    Text("corpus", "Corpus file (one prompt per line)", ""),
    IntField("new_tags", "Tags to add", 1, 64, 8),
    IntField("count", "Variants (images)", 1, 32, 4),
    IntField("seed", "Generator seed (-1 = random)", -1, 2147483647, -1)
  )

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    if v.str("method").startsWith("GPT-2") then return gpt2(p0, v, ctx)
    val path = v.str("corpus").trim
    require(path.nonEmpty, "A corpus file path is required")
    val f = Paths.get(path)
    require(Files.isRegularFile(f) && Files.size(f) <= 16L * 1024 * 1024, s"Cannot read $path (missing or larger than 16 MiB)")
    val chain = train(new String(Files.readAllBytes(f), StandardCharsets.UTF_8).linesIterator.toSeq)
    val seedTags = tags(p0.prompt)
    require(seedTags.nonEmpty, "Write a base prompt to extend")
    val gs = v.int("seed", -1)
    val rnd = if gs >= 0 then new Random(gs.toLong) else new Random()
    val n = v.int("count", 4)
    val outs = scala.collection.mutable.ArrayBuffer[OutputImage]()
    var running = true
    var i = 0
    while running && i < n do
      val prompt = extend(chain, seedTags, v.int("new_tags", 8), rnd).mkString(", ")
      ctx.status(s"Prompt generator ${i + 1}/$n", i, n)
      Support.step(ctx)(ctx.generate(p0.copy(prompt = prompt, batchSize = 1, nIter = 1,
        extra = p0.extra + ("Script" -> "Prompt generator")))) match
        case Some(o) => outs ++= o
        case None    => running = false
      i += 1
    JobOutput(outs.toSeq)

  private def gpt2(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val d = v.str("model_dir").trim
    require(d.nonEmpty, "A GPT-2 model folder is required")
    val dir = Paths.get(d)
    require(Files.isRegularFile(dir.resolve("model.safetensors")), s"$d has no model.safetensors")
    require(p0.prompt.trim.nonEmpty, "Write a base prompt to extend")
    val tok = Gpt2.Tokenizer.load(dir); val model = Gpt2.Model.load(dir)
    val gs = v.int("seed", -1)
    val rnd = if gs >= 0 then new Random(gs.toLong) else new Random()
    val n = v.int("count", 4)
    val outs = scala.collection.mutable.ArrayBuffer[OutputImage]()
    var running = true; var i = 0
    while running && i < n do
      ctx.status(s"Prompt generator (GPT-2) ${i + 1}/$n", i, n)
      val cont = Gpt2.generate(model, tok, p0.prompt.trim, v.int("max_tokens", 40), v.double("temperature", 0.9), v.int("top_k", 50), rnd)
      val line = cont.linesIterator.nextOption().getOrElse("")
      val prompt = (p0.prompt.trim.stripSuffix(",") + (if line.startsWith(",") || line.startsWith(" ") then "" else " ") + line).replaceAll("[ \t]+", " ").trim
      ctx.log(s"Prompt generator: $prompt")
      Support.step(ctx)(ctx.generate(p0.copy(prompt = prompt, batchSize = 1, nIter = 1,
        extra = p0.extra + ("Script" -> "Prompt generator (GPT-2)")))) match
        case Some(o) => outs ++= o
        case None    => running = false
      i += 1
    JobOutput(outs.toSeq)
