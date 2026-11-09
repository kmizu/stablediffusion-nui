package sdfx.plugins

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*

/** Prompt travel by weight blending (own design; no upstream code). Between two key prompts A and B the frame at position t uses
  * `(A:1-t), (B:t)` with a fixed seed, so frames change smoothly. This is NOT latent/conditioning-space interpolation. */
object PromptTravel:
  private def w(x: Double) = f"$x%.2f".replace(',', '.')

  /** Key prompts, one per line (blank lines ignored). Returns the frame prompts: `steps` frames per segment, ending on the last key. */
  def frames(keys: Seq[String], steps: Int): Vector[String] =
    require(keys.size >= 2, "Give at least two key prompts (one per line)")
    require(steps >= 1, "Steps per segment must be at least 1")
    val out = Vector.newBuilder[String]
    out += keys.head
    for (a, b) <- keys.zip(keys.tail); k <- 1 to steps do
      val t = k.toDouble / steps
      out += (if k == steps then b else s"(${a}:${w(1 - t)}), (${b}:${w(t)})")
    out.result()

class PromptTravelPlugin extends Plugin:
  def id = "prompt_travel"
  def name = "Prompt travel"
  def kind = PluginKind.Script
  override def description = "Generates frames that move from one key prompt to the next by blending their weights ((A:0.67), (B:0.33) ...) " +
    "with one fixed seed. Weight blending, not latent interpolation. One key prompt per line."
  override def controls: Seq[Control] = Seq(
    Text("keys", "Key prompts (one per line, at least two)", "", multiline = true),
    IntField("steps", "Frames between keys", 1, 64, 4)
  )

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val keys = v.str("keys").linesIterator.map(_.trim).filter(_.nonEmpty).toSeq
    val prompts = PromptTravel.frames(keys, v.int("steps", 4))
    val seed = if p0.seed >= 0 then p0.seed else scala.util.Random.nextInt(Int.MaxValue).toLong
    val outs = scala.collection.mutable.ArrayBuffer[OutputImage]()
    var running = true; val it = prompts.iterator.zipWithIndex
    while running && it.hasNext do
      val (p, i) = it.next()
      ctx.status(s"Prompt travel ${i + 1}/${prompts.size}", i, prompts.size)
      Support.step(ctx)(ctx.generate(p0.copy(prompt = (if p0.prompt.trim.isEmpty then p else p0.prompt.trim.stripSuffix(",") + ", " + p), seed = seed, batchSize = 1, nIter = 1,
        extra = p0.extra + ("Script" -> "Prompt travel") + ("Prompt travel frame" -> s"${i + 1}/${prompts.size}")))) match
        case Some(o) => outs ++= o
        case None    => running = false
    JobOutput(outs.toSeq)
