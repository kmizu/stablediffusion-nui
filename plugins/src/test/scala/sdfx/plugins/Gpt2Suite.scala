package sdfx.plugins

import java.nio.file.{Files, Path, Paths}
import scala.util.Random
import sdfx.core.plugins.api.*

/** Reference values come from transformers 4.51 (GPT2LMHeadModel / GPT2TokenizerFast) on distilgpt2. The model files are not in the repo:
  * tests are skipped unless `models/distilgpt2` (or SDFX_GPT2_DIR) exists. */
class Gpt2Suite extends munit.FunSuite:
  private val dir: Path = Paths.get(sys.env.getOrElse("SDFX_GPT2_DIR", "models/distilgpt2")).toAbsolutePath
  private def have = Files.isRegularFile(dir.resolve("model.safetensors")) && Files.isRegularFile(dir.resolve("vocab.json"))
  private lazy val tok = Gpt2.Tokenizer.load(dir)
  private lazy val model = Gpt2.Model.load(dir)

  test("tokenizer matches the reference ids (punctuation, apostrophes, unicode, emoji)") {
    assume(have, "distilgpt2 files not available")
    assertEquals(tok.encode("Hello world, it's  ünï 😀 123!"), Vector(15496, 995, 11, 340, 338, 220, 6184, 120, 77, 26884, 30325, 222, 17031, 0))
    assertEquals(tok.encode("masterpiece, 1girl, silver hair"), Vector(9866, 12239, 11, 352, 15219, 11, 8465, 4190))
    assertEquals(tok.decode(tok.encode("Hello world, it's  ünï 😀 123!")), "Hello world, it's  ünï 😀 123!")
  }

  test("last-position logits give the reference top-5 and greedy continuation") {
    assume(have, "distilgpt2 files not available")
    val s = model.session()
    var lg = Array.empty[Float]
    tok.encode("masterpiece, 1girl, silver hair").foreach(i => lg = s.step(i))
    val top = lg.indices.sortBy(i => -lg(i)).take(5)
    assertEquals(top, Vector(11, 290, 13, 198, 1370))
    assertEqualsDouble(lg(11), -20.885, 0.01)
    val out = Gpt2.generate(model, tok, "masterpiece, 1girl, silver hair", 12, 0.0, 1, new Random(0))
    assertEquals(out, ", and a black eye.\n\n\n\n\n\n")
  }

  test("seeded sampling is reproducible") {
    assume(have, "distilgpt2 files not available")
    val a = Gpt2.generate(model, tok, "1girl,", 8, 0.9, 40, new Random(3))
    assertEquals(a, Gpt2.generate(model, tok, "1girl,", 8, 0.9, 40, new Random(3)))
  }

  test("prompt_generator with the GPT-2 method continues the prompt and generates one image per variant") {
    assume(have, "distilgpt2 files not available")
    val ctx = new FakeContext(); val p = new PromptGeneratorPlugin
    val v = Values(Values.defaults(p.controls).map ++ Map("model_dir" -> dir.toString, "count" -> "2", "seed" -> "4", "max_tokens" -> "10"))
    p.run(sdfx.core.model.GenerationParams(prompt = "1girl, silver hair"), v, ctx)
    assertEquals(ctx.generated.size, 2)
    assert(ctx.generated.forall(_.prompt.startsWith("1girl, silver hair")))
  }
