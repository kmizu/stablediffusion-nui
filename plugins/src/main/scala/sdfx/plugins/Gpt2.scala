package sdfx.plugins

import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.util.Random

/** GPT-2 family inference on the JVM (own implementation from the published architecture; no Python, no torch).
  * Reads a Hugging Face folder: model.safetensors (F32), vocab.json, merges.txt. Tested with distilgpt2 (Apache-2.0). */
object Gpt2:
  /** Byte-level BPE as used by GPT-2. */
  final class Tokenizer(vocab: Map[String, Int], merges: Vector[(String, String)]):
    private val rank = merges.zipWithIndex.toMap
    private val inv = vocab.map(_.swap)
    private val (b2u, u2b) =
      val bs = ((33 to 126) ++ (161 to 172) ++ (174 to 255)).toVector
      val extra = (0 to 255).filterNot(bs.contains)
      val all = bs.map(b => b -> b) ++ extra.zipWithIndex.map((b, i) => b -> (256 + i))
      (all.toMap.map((b, c) => b -> c.toChar.toString), all.toMap.map((b, c) => c.toChar -> b))
    private val pat = java.util.regex.Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")

    private def bpe(word: Vector[String]): Vector[String] =
      var w = word
      var go = w.size > 1
      while go do
        val pairs = w.zip(w.tail)
        val best = pairs.filter(rank.contains).minByOption(rank)
        best match
          case None => go = false
          case Some((a, b)) =>
            val out = Vector.newBuilder[String]; var i = 0
            while i < w.size do
              if i < w.size - 1 && w(i) == a && w(i + 1) == b then { out += a + b; i += 2 } else { out += w(i); i += 1 }
            w = out.result(); go = w.size > 1
      w

    def encode(text: String): Vector[Int] =
      val m = pat.matcher(text); val ids = Vector.newBuilder[Int]
      while m.find() do
        val chars = m.group().getBytes(StandardCharsets.UTF_8).toVector.map(b => b2u(b & 255))
        bpe(chars).foreach(t => ids += vocab.getOrElse(t, throw new IllegalArgumentException(s"token not in vocab: $t")))
      ids.result()

    def decode(ids: Seq[Int]): String =
      val bytes = ids.flatMap(i => inv.getOrElse(i, "").map(c => u2b.getOrElse(c, 63))).map(_.toByte).toArray
      new String(bytes, StandardCharsets.UTF_8)

  object Tokenizer:
    def load(dir: Path): Tokenizer =
      val v = ujson.read(Files.readString(dir.resolve("vocab.json"))).obj.map((k, x) => k -> x.num.toInt).toMap
      val m = Files.readAllLines(dir.resolve("merges.txt")).toArray(Array.empty[String]).iterator
        .filter(l => l.nonEmpty && !l.startsWith("#version")).map { l => val p = l.split(" "); (p(0), p(1)) }.toVector
      new Tokenizer(v, m)

  private final class Weights(file: Path):
    private val ch = FileChannel.open(file, StandardOpenOption.READ)
    private val size = ch.size()
    private val hdrLen = { val b = java.nio.ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN); ch.read(b, 0); b.flip(); b.getLong().toInt }
    private val hdr =
      val b = java.nio.ByteBuffer.allocate(hdrLen); ch.read(b, 8); ujson.read(new String(b.array(), StandardCharsets.UTF_8)).obj
    private val base = 8L + hdrLen
    def get(name: String): Array[Float] =
      val e = hdr.getOrElse(name, hdr.getOrElse("transformer." + name, throw new IllegalArgumentException(s"missing tensor $name")))
      require(e("dtype").str == "F32", s"only F32 weights are supported, $name is ${e("dtype").str}")
      val Seq(s, t) = e("data_offsets").arr.map(_.num.toLong).toSeq
      val buf = ch.map(FileChannel.MapMode.READ_ONLY, base + s, t - s)
      val out = new Array[Float](((t - s) / 4).toInt)
      buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out); out
    def close(): Unit = ch.close()

  private final class Layer(val ln1w: Array[Float], val ln1b: Array[Float], val attnW: Array[Float], val attnB: Array[Float],
      val projW: Array[Float], val projB: Array[Float], val ln2w: Array[Float], val ln2b: Array[Float],
      val fcW: Array[Float], val fcB: Array[Float], val mpW: Array[Float], val mpB: Array[Float])

  final class Model private (cfg: ujson.Obj, w: Weights):
    private val nEmbd = cfg("n_embd").num.toInt
    private val nHead = cfg("n_head").num.toInt
    val nLayer: Int = cfg("n_layer").num.toInt
    val nCtx: Int = cfg("n_positions").num.toInt
    private val eps = cfg.value.get("layer_norm_epsilon").map(_.num.toFloat).getOrElse(1e-5f)
    private val wte = w.get("wte.weight"); private val wpe = w.get("wpe.weight")
    private val lnfW = w.get("ln_f.weight"); private val lnfB = w.get("ln_f.bias")
    private val layers = (0 until nLayer).map { i =>
      def g(n: String) = w.get(s"h.$i.$n")
      new Layer(g("ln_1.weight"), g("ln_1.bias"), g("attn.c_attn.weight"), g("attn.c_attn.bias"), g("attn.c_proj.weight"), g("attn.c_proj.bias"),
        g("ln_2.weight"), g("ln_2.bias"), g("mlp.c_fc.weight"), g("mlp.c_fc.bias"), g("mlp.c_proj.weight"), g("mlp.c_proj.bias"))
    }
    w.close()
    val vocabSize: Int = wte.length / nEmbd

    private def layerNorm(x: Array[Float], g: Array[Float], b: Array[Float]): Array[Float] =
      val n = x.length; var mu = 0.0; x.foreach(mu += _); mu /= n
      var v = 0.0; x.foreach(a => v += (a - mu) * (a - mu)); v /= n
      val inv = (1.0 / math.sqrt(v + eps)).toFloat
      Array.tabulate(n)(i => ((x(i) - mu).toFloat * inv) * g(i) + b(i))

    /** y = x W + b for a Conv1D weight stored [in, out]. */
    private def linear(x: Array[Float], wt: Array[Float], b: Array[Float], out: Int): Array[Float] =
      val y = b.clone(); var i = 0
      while i < x.length do
        val xi = x(i); val off = i * out; var j = 0
        while j < out do { y(j) += xi * wt(off + j); j += 1 }
        i += 1
      y

    private def gelu(x: Float): Float = (0.5 * x * (1.0 + math.tanh(0.7978845608028654 * (x + 0.044715 * x * x * x)))).toFloat

    /** KV cache: per layer, per position, K and V of size nEmbd. */
    final class Session:
      private val ks = Array.fill(nLayer)(new scala.collection.mutable.ArrayBuffer[Array[Float]])
      private val vs = Array.fill(nLayer)(new scala.collection.mutable.ArrayBuffer[Array[Float]])
      def pos: Int = ks(0).size

      /** Feeds one token and returns the logits for the next one. */
      def step(token: Int): Array[Float] =
        val p = pos; require(p < nCtx, "context length exceeded")
        var x = Array.tabulate(nEmbd)(i => wte(token * nEmbd + i) + wpe(p * nEmbd + i))
        val hd = nEmbd / nHead
        for (l, li) <- layers.zipWithIndex do
          val qkv = linear(layerNorm(x, l.ln1w, l.ln1b), l.attnW, l.attnB, 3 * nEmbd)
          ks(li) += qkv.slice(nEmbd, 2 * nEmbd); vs(li) += qkv.slice(2 * nEmbd, 3 * nEmbd)
          val att = new Array[Float](nEmbd)
          for h <- 0 until nHead do
            val o = h * hd
            val sc = Array.tabulate(p + 1) { t => var s = 0f; var d = 0; val k = ks(li)(t); while d < hd do { s += qkv(o + d) * k(o + d); d += 1 }; s / math.sqrt(hd).toFloat }
            val mx = sc.max; var sum = 0.0; val e = sc.map(s => { val z = math.exp(s - mx); sum += z; z.toFloat })
            for t <- 0 to p do
              val a = (e(t) / sum).toFloat; val v = vs(li)(t); var d = 0
              while d < hd do { att(o + d) += a * v(o + d); d += 1 }
          val proj = linear(att, l.projW, l.projB, nEmbd)
          x = Array.tabulate(nEmbd)(i => x(i) + proj(i))
          val hid = linear(layerNorm(x, l.ln2w, l.ln2b), l.fcW, l.fcB, 4 * nEmbd).map(gelu)
          val mlp = linear(hid, l.mpW, l.mpB, nEmbd)
          x = Array.tabulate(nEmbd)(i => x(i) + mlp(i))
        val f = layerNorm(x, lnfW, lnfB)
        Array.tabulate(vocabSize) { t => var s = 0f; val off = t * nEmbd; var d = 0; while d < nEmbd do { s += f(d) * wte(off + d); d += 1 }; s }

    def session(): Session = new Session

  object Model:
    def load(dir: Path): Model =
      val cfg = ujson.read(Files.readString(dir.resolve("config.json"))).obj
      new Model(cfg, new Weights(dir.resolve("model.safetensors")))

  /** Samples `maxNew` tokens after `prompt` (top-k, temperature; temperature 0 = greedy). Stops early at `stop`. */
  def generate(m: Model, tok: Tokenizer, prompt: String, maxNew: Int, temperature: Double, topK: Int, rnd: Random,
      stop: Int => Boolean = _ => false): String =
    val s = m.session()
    val ids = tok.encode(prompt); require(ids.nonEmpty, "empty prompt")
    var logits = Array.empty[Float]
    ids.foreach(i => logits = s.step(i))
    val out = Vector.newBuilder[Int]; var n = 0; var done = false
    while n < maxNew && !done && s.pos < m.nCtx do
      val next =
        if temperature <= 0 then logits.indices.maxBy(logits)
        else
          val idx = logits.indices.sortBy(i => -logits(i)).take(math.max(1, topK))
          val mx = idx.map(logits).max
          val w = idx.map(i => math.exp((logits(i) - mx) / temperature))
          var r = rnd.nextDouble() * w.sum; var k = 0
          while k < w.size - 1 && r >= w(k) do { r -= w(k); k += 1 }
          idx(k)
      if next == 50256 || stop(next) then done = true
      else { out += next; logits = s.step(next); n += 1 }
    tok.decode(out.result())
