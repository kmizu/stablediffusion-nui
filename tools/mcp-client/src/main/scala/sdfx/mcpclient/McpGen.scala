package sdfx.mcpclient

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.{ServerParameters, StdioClientTransport}
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import java.nio.file.{Files, Path}
import java.time.Duration
import scala.jdk.CollectionConverters.*

/** Starts the NUI MCP server over stdio, loads a checkpoint and runs one txt2img through the official Java SDK.
  *
  * usage: McpGen --checkpoint NAME --prompt TEXT --out DIR [--backend scala-torch] [--models DIR] [--jar PATH]
  *   [--java PATH] [--side N] [--steps N] [--seed N] [--sampler S] [--scheduler S] [--negative T]
  *   [--lora name:weight,...] [--vae FILE]
  */
object McpGen:
  private def options(args: Array[String]): Map[String, String] =
    // Values may be split across several tokens (shell or sbt word splitting); join them until the next --key.
    val result = scala.collection.mutable.LinkedHashMap.empty[String, String]
    var key = ""
    args.foreach { a =>
      if a.startsWith("--") then { key = a.drop(2); result(key) = "" }
      else if key.nonEmpty then result(key) = (if result(key).isEmpty then a else result(key) + " " + a)
    }
    result.view.mapValues(_.stripPrefix("'").stripSuffix("'")).toMap

  def main(argv: Array[String]): Unit =
    val o = options(argv)
    def req(k: String): String = o.getOrElse(k, sys.error(s"missing --$k"))
    val backend = o.getOrElse("backend", "scala-torch")
    val checkpoint = req("checkpoint")
    val out = Path.of(req("out")).toAbsolutePath.normalize()
    val models = Path.of(o.getOrElse("models", "D:/stable-diffusion-webui-reForge/models"))
    val root = Path.of(o.getOrElse("root", ".")).toAbsolutePath.normalize()
    val jar = o.getOrElse("jar", root.resolve("target/stablediffusion-nui.jar").toString)
    val javaExe = o.getOrElse("java", sys.env.getOrElse("SDFX_SCALATORCH_JAVA", "java"))
    val home = out.resolve("app-home")
    Files.createDirectories(home.resolve("config")); Files.createDirectories(out.resolve("images"))
    def q(p: Path): ujson.Str = ujson.Str(p.toString.replace('\\', '/'))
    val settings = ujson.Obj(
      "output_dir" -> q(out.resolve("images")), "last_model" -> checkpoint,
      "model_dirs" -> ujson.Obj("checkpoints" -> ujson.Arr(q(models.resolve("Stable-diffusion"))),
        "lora" -> ujson.Arr(q(models.resolve("Lora")))),
      "vram_mode" -> "auto", "dtype" -> "fp16", "engine_backend" -> backend)
    Files.writeString(home.resolve("config/settings.json"), ujson.write(settings))
    val env = new java.util.HashMap[String, String](System.getenv())
    if backend == "scala-torch" then
      env.put("SDFX_ENGINE_BACKEND", "scala-torch")
      env.put("SDFX_SCALATORCH_MODEL", models.resolve("Stable-diffusion").resolve(s"$checkpoint.safetensors").toString)
      env.put("SDFX_SCALATORCH_JAVA", javaExe)
      sys.env.get("TOK").foreach(env.put("SDFX_SCALATORCH_TOKENIZER", _))
      env.putIfAbsent("SDFX_SCALATORCH_UNET_DEVICE", "cuda"); env.putIfAbsent("SDFX_SCALATORCH_VAE_DEVICE", "cuda")
    val params = ServerParameters.builder(javaExe)
      .args("-Xlog:disable", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", jar, "sdfx.mcp.McpMain",
        "--home", home.toString, "--drain-seconds", "5")
      .env(env).build()
    val transport = new StdioClientTransport(params, McpJsonDefaults.getMapper())
    val client = McpClient.sync(transport).requestTimeout(Duration.ofMinutes(30)).build()
    try
      client.initialize()
      def call(name: String, values: Map[String, Object]): Boolean =
        val r = client.callTool(new CallToolRequest(name, values.asJava))
        val text = r.content().asScala.map(_.toString).mkString("\n")
        println(s"$name isError=${r.isError} structured=${Option(r.structuredContent()).map(_.toString.take(400)).getOrElse("-")}")
        if text.nonEmpty then println(text.take(600))
        !java.lang.Boolean.TRUE.equals(r.isError)
      val tools = client.listTools().tools().asScala.map(_.name()).toSet
      require(Set("txt2img", "img2img", "list_models", "set_model").subsetOf(tools), s"missing tools: $tools")
      val setModel: Map[String, Object] = Map("name" -> checkpoint) ++ o.get("vae").map(v => "vae" -> (v: Object))
      require(call("set_model", setModel), "set_model failed")
      val side = Int.box(o.getOrElse("side", "512").toInt)
      val gen = scala.collection.mutable.Map[String, Object](
        "prompt" -> req("prompt"), "negative_prompt" -> o.getOrElse("negative", "lowres, bad anatomy, worst quality"),
        "width" -> side, "height" -> side,
        "steps" -> Int.box(o.getOrElse("steps", "20").toInt), "seed" -> Int.box(o.getOrElse("seed", "173").toInt),
        "sampler_name" -> o.getOrElse("sampler", "Euler a"), "scheduler" -> o.getOrElse("scheduler", "Automatic"),
        "return_images" -> java.lang.Boolean.FALSE)
      o.get("lora").foreach { l =>
        gen("loras") = l.split(",").toList.map { x =>
          val p = x.split(":")
          Map[String, Object]("name" -> p(0), "weight" -> Double.box(p.lift(1).map(_.toDouble).getOrElse(0.8))).asJava
        }.asJava
      }
      if !call("txt2img", gen.toMap) then sys.exit(1)
    finally client.closeGracefully()
