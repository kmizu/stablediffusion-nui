package sdfx.mcp

import sdfx.core.model.ControlNetUnit
import sdfx.core.png.PngMetadata
import sdfx.engine.AppServices

import java.nio.file.{Files, Paths}
import java.util.Base64
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

class McpToolsSuite extends munit.FunSuite:
  import TestEnv.*
  given ExecutionContext = ExecutionContext.global

  override val munitTimeout: Duration = 180.seconds

  private lazy val home = tempHome()
  private lazy val services: AppServices =
    val s = TestEnv.services(home)
    s.start()
    s
  private lazy val tools = new McpTools(services)
  private lazy val server = new McpServer(tools.all)

  override def afterAll(): Unit =
    services.shutdown()
    deleteTree(home.root)

  test("VAE file inputs reach the engine without RGB decoding and retain their bytes") {
    val path = home.outputs(services.settings).resolve("encoded-latent.safetensors")
    Files.createDirectories(path.getParent)
    val original = Array[Byte](1, 17, -3, 61, 0, 4)
    Files.write(path, original)
    val response = call("vae_tools", ujson.Obj("input_paths" -> ujson.Arr(path.toString), "operation" -> "decode"))
    assert(response("isError").bool)
    assert(textOf(response).contains("real loaded VAE"), textOf(response))
    assert(java.util.Arrays.equals(Files.readAllBytes(path), original))
    val outside = Files.createTempFile("vae-outside-", ".safetensors")
    try
      val denied = call("vae_tools", ujson.Obj("input_paths" -> ujson.Arr(outside.toString)))
      assert(denied("isError").bool)
      assert(textOf(denied).contains("allowed folders"), textOf(denied))
    finally Files.deleteIfExists(outside)
  }

  private def call(tool: String, args: ujson.Value, rec: ujson.Value => Unit = _ => (), id: ujson.Value = ujson.Null, token: Option[ujson.Value] = None): ujson.Value =
    val r = server.handle(callReq(tool, args, id, token), rec).getOrElse(fail("no response"))
    assert(!r.obj.contains("error"), s"JSON-RPC error: $r")
    r("result")

  private def ok(tool: String, args: ujson.Value): ujson.Value =
    val r = call(tool, args)
    assert(!r("isError").bool, s"$tool failed: ${textOf(r)}")
    r

  test("prompt library MCP lists names and reads one prompt without modifying the library") {
    val descriptor = tools.all.find(_.name == "prompt_library").getOrElse(fail("prompt library tool missing")).descriptor
    assert(descriptor("annotations")("readOnlyHint").bool)
    val service = services.promptTools
    service.updateLibrary(_.save("quality", "sharp, detailed", false)._1.save("avoid", "blurry", true)._1)
    val file = service.libraryStore.file
    val before = Files.readAllBytes(file)
    val first = ok("prompt_library", ujson.Obj("action" -> "list", "limit" -> 1))("structuredContent")
    assertEquals(first("entries").arr.head("name").str, "quality")
    assertEquals(first("entries").arr.head("negative").bool, false)
    assertEquals(first("next_offset").num.toInt, 1)
    assert(!first.render().contains("sharp, detailed"))
    val next = ok("prompt_library", ujson.Obj("action" -> "list", "offset" -> 1))("structuredContent")
    assertEquals(next("entries").arr.head("name").str, "avoid")
    val item = ok("prompt_library", ujson.Obj("action" -> "get", "name" -> "avoid"))("structuredContent")
    assertEquals(item("prompt").str, "blurry")
    assertEquals(item("negative").bool, true)
    assert(call("prompt_library", ujson.Obj("action" -> "get", "name" -> "missing"))("isError").bool)
    assert(call("prompt_library", ujson.Obj("action" -> "delete", "name" -> "avoid"))("isError").bool)
    assert(call("prompt_library", ujson.Obj("action" -> "get", "name" -> "avoid", "limit" -> 1))("isError").bool)
    assert(java.util.Arrays.equals(Files.readAllBytes(file), before))
    val tooLong = ujson.Obj("large" -> ujson.Obj("prompt" -> ("x" * 100001), "is_negative" -> false))
    Files.writeString(file, tooLong.render())
    try
      assert(call("prompt_library", ujson.Obj("action" -> "get", "name" -> "large"))("isError").bool)
    finally Files.write(file, before)
    Files.write(file, Array.fill[Byte](sdfx.core.prompttools.PromptLibrary.MaxFileBytes + 1)(32))
    try
      assert(call("prompt_library", ujson.Obj("action" -> "list"))("isError").bool)
    finally Files.write(file, before)
    Files.delete(file)
    try
      val empty = ok("prompt_library", ujson.Obj("action" -> "list"))("structuredContent")
      assertEquals(empty("entries").arr.size, 0)
      assert(!Files.exists(file))
    finally Files.write(file, before)
  }

  test("generation history MCP lists entries and reads exact typed fields without source metadata") {
    import sdfx.core.presets.*
    val store = new PresetStore(home.configDir.resolve("generation-history"), PresetLimits(maxCollections = 6))
    val id = java.util.UUID.randomUUID()
    val fields = Map(FieldKey(Vector("generation", "seed")) -> PresetAtom.Integer(9007199254740993L),
      FieldKey(Vector("generation", "prompt")) -> PresetAtom.Text("private prompt"))
    val record = PresetRecord(id, "test history", fields, None, Set.empty, Vector.empty, Vector.empty)
    val library = PresetLibrary(1, Vector(PresetCollection("history-txt2img", fields.keySet, Vector.empty, Vector(record), Vector.empty)), Vector.empty)
    val before = store.load().toOption.get
    assert(store.save(before.revision, library).isRight)
    val listed = ok("generation_history", ujson.Obj("action" -> "list", "mode" -> "txt2img"))("structuredContent")
    assertEquals(listed("entries").arr.head("id").str, id.toString)
    assert(!listed.render().contains("private prompt"))
    val read = ok("generation_history", ujson.Obj("action" -> "get", "mode" -> "txt2img", "id" -> id.toString))("structuredContent")
    assertEquals(read("fields")("generation.seed")("value").str, "9007199254740993")
    assertEquals(read("fields")("generation.prompt")("value").str, "private prompt")
    assert(!read.obj.contains("source"))
    assertEquals(ok("generation_history", ujson.Obj("action" -> "list", "mode" -> "img2img"))("structuredContent")("entries").arr.size, 0)
    assertEquals(ok("generation_history", ujson.Obj("action" -> "list", "mode" -> "txt2img", "collection" -> "favourites"))("structuredContent")("entries").arr.size, 0)
    assert(call("generation_history", ujson.Obj("action" -> "get", "mode" -> "img2img", "id" -> id.toString))("isError").bool)
    assert(call("generation_history", ujson.Obj("action" -> "list", "mode" -> "../txt2img"))("isError").bool)
    assert(call("generation_history", ujson.Obj("action" -> "get", "mode" -> "txt2img"))("isError").bool)
    assert(call("generation_history", ujson.Obj("action" -> "delete", "mode" -> "txt2img"))("isError").bool)
    assertEquals(store.load().toOption.get.library, library)
  }

  test("generation history MCP searches saved fields and paginates without returning field values") {
    import sdfx.core.presets.*
    def rec(name: String, prompt: String, model: String) =
      PresetRecord(java.util.UUID.randomUUID(), name, Map(FieldKey(Vector("generation", "prompt")) -> PresetAtom.Text(prompt),
        FieldKey(Vector("model", "checkpoint")) -> PresetAtom.Text(model)), None, Set.empty, Vector.empty, Vector.empty)
    val first = rec("first", "Blue fox", "XL one")
    val second = rec("second", "blue forest", "XL two")
    val third = rec("third", "red fox", "SD one")
    val entries = Vector(first, second, third)
    val library = PresetLibrary(1, Vector(PresetCollection("history-txt2img", entries.flatMap(_.fields.keys).toSet, Vector.empty, entries, Vector.empty)), Vector.empty)
    val store = new PresetStore(home.configDir.resolve("generation-history"), PresetLimits(maxCollections = 6))
    assert(store.save(store.load().toOption.get.revision, library).isRight)
    val firstPage = ok("generation_history", ujson.Obj("action" -> "list", "mode" -> "txt2img", "query" -> " BLUE, xl", "limit" -> 1))("structuredContent")
    assertEquals(firstPage("total").num.toInt, 2)
    assertEquals(firstPage("entries").arr.head("id").str, second.id.toString)
    assertEquals(firstPage("next_offset").num.toInt, 1)
    assert(!firstPage.render().contains("blue forest"))
    val secondPage = ok("generation_history", ujson.Obj("action" -> "list", "mode" -> "txt2img", "query" -> "blue,xl", "limit" -> 1, "offset" -> 1))("structuredContent")
    assertEquals(secondPage("entries").arr.head("id").str, first.id.toString)
    assertEquals(secondPage("next_offset"), ujson.Null)
    assert(call("generation_history", ujson.Obj("action" -> "get", "mode" -> "txt2img", "id" -> first.id.toString, "query" -> "blue"))("isError").bool)
    assert(call("generation_history", ujson.Obj("action" -> "list", "mode" -> "txt2img", "limit" -> 0))("isError").bool)
    assertEquals(store.load().toOption.get.library, library)
  }

  test("generation history prepares a partial generation draft and reports omitted or invalid values") {
    import sdfx.core.presets.*
    def key(a: String, b: String) = FieldKey(Vector(a, b))
    val fields = Map(key("generation", "prompt") -> PresetAtom.Text("a blue fox"),
      key("generation", "negativePrompt") -> PresetAtom.Text("blur"),
      key("generation", "steps") -> PresetAtom.Integer(8),
      key("generation", "width") -> PresetAtom.Integer(512),
      key("generation", "height") -> PresetAtom.Integer(512),
      key("generation", "seed") -> PresetAtom.Integer(9007199254740993L),
      key("plugin", "untranslated") -> PresetAtom.Text("preserve"))
    val record = PresetRecord(java.util.UUID.randomUUID(), "draft", fields, None, Set.empty, Vector.empty, Vector.empty)
    val library = PresetLibrary(1, Vector(PresetCollection("history-txt2img", fields.keySet, Vector.empty, Vector(record), Vector.empty)), Vector.empty)
    val store = new PresetStore(home.configDir.resolve("generation-history"), PresetLimits(maxCollections = 6))
    assert(store.save(store.load().toOption.get.revision, library).isRight)
    val draft = ok("generation_history", ujson.Obj("action" -> "prepare", "mode" -> "txt2img", "id" -> record.id.toString))("structuredContent")
    assertEquals(draft("tool").str, "txt2img")
    assertEquals(draft("params")("prompt").str, "a blue fox")
    assertEquals(draft("params")("negative_prompt").str, "blur")
    assertEquals(draft("params")("steps").num.toInt, 8)
    assert(!draft("params").obj.contains("seed"))
    assert(draft("invalid_fields").arr.exists(_.str == "generation.seed"))
    assert(draft("omitted_fields").arr.exists(_.str == "plugin.untranslated"))
    assert(!draft("replay_guaranteed").bool)
    assertEquals(store.load().toOption.get.library, library)
    val generated = ok("txt2img", draft("params"))
    assert(generated("structuredContent").obj.contains("images"))
  }

  test("history draft identifies required images and rejects invalid dimensions and combined batch") {
    import sdfx.core.presets.*
    def key(s: String) = FieldKey(Vector("generation", s))
    val fields = Map(key("width") -> PresetAtom.Integer(513), key("batchSize") -> PresetAtom.Integer(8),
      key("batchCount") -> PresetAtom.Integer(4), key("subseed") -> PresetAtom.Integer(123))
    val r = PresetRecord(java.util.UUID.randomUUID(), "inpaint", fields, None, Set.empty, Vector.empty, Vector.empty)
    val result = HistoryGenerationDraft.build(r, "inpaint")
    assertEquals(result("tool").str, "img2img")
    assertEquals(result("required_inputs").arr.map(_.str).toSeq, Seq("init_image", "mask"))
    assertEquals(result("missing_fields").arr.map(_.str).toSeq, Seq("prompt", "init_image", "mask"))
    assertEquals(result("invalid_fields").arr.map(_.str).toSet, Set("generation.width", "generation.batchSize", "generation.batchCount"))
    assert(result("omitted_fields").arr.exists(_.str == "generation.subseed"))
    assert(!result("params").obj.contains("batch_size") && !result("params").obj.contains("n_iter"))
  }

  test("Aesthetic embedding creation is discoverable and refuses paths outside the permitted roots") {
    val ts = server.handle(request("tools/list"), _ => ()).get("result")("tools").arr
    val tool = ts.find(_("name").str == "aesthetic_embedding").getOrElse(fail("aesthetic embedding tool missing"))
    assert(tool("inputSchema")("properties").obj.contains("images"))
    val outside = Files.createTempFile("aesthetic-outside-", ".png")
    try
      val denied = call("aesthetic_embedding", ujson.Obj("operation" -> "create", "name" -> "style",
        "images" -> ujson.Arr(outside.toString)))
      assert(denied("isError").bool)
      assert(textOf(denied).contains("allowed folders"), textOf(denied))
    finally Files.deleteIfExists(outside)
  }

  test("Aesthetic embeddings submit owned image inputs and clean temporary files") {
    val result = ok("aesthetic_embedding", ujson.Obj("operation" -> "create", "name" -> "style",
      "images" -> ujson.Arr(pngBase64()), "overwrite" -> false))
    val data = result("structuredContent")
    assertEquals(data("name").str, "style")
    assertEquals(data("count").num.toInt, 1)
    val temporary = Paths.get(data("image_path").str)
    assert(!Files.exists(temporary), s"temporary image was not removed: $temporary")
    assert(data("output_dir").str.contains("aesthetic_embeddings"))
    assert(Files.exists(Paths.get(data("path").str)), s"fake worker did not save ${data("path").str}")
    val list = ok("aesthetic_embedding", ujson.Obj("operation" -> "list"))
    assertEquals(list("structuredContent")("output_dir").str, data("output_dir").str)
    val file = Paths.get(data("path").str)
    assertEquals(tools.pathPolicy.resolve(file.toString, "embedding", home.outputs(services.settings)), file.toRealPath())
    assertEquals(list("structuredContent")("embeddings").arr.map(_("name").str).toSeq, Seq("style.pt"))
  }

  test("Aesthetic embeddings reject protected sessions and unsafe output names") {
    val params = ujson.Obj("operation" -> "create", "name" -> "../style", "images" -> ujson.Arr(pngBase64()))
    assert(call("aesthetic_embedding", params)("isError").bool)
    services.imageScrambleSession.unlock("test-only-password")
    try
      val blocked = call("aesthetic_embedding", ujson.Obj("operation" -> "create", "name" -> "style",
        "images" -> ujson.Arr(pngBase64())))
      assert(blocked("isError").bool)
      assert(textOf(blocked).contains("protected image session"), textOf(blocked))
    finally services.imageScrambleSession.lock()
  }

  test("Aesthetic names resolve only uniquely in explicitly registered permitted directories") {
    val base = home.outputs(services.settings)
    val custom = Files.createDirectories(base.resolve("custom-aesthetic"))
    val created = ok("aesthetic_embedding", ujson.Obj("operation" -> "create", "name" -> "unique-replay",
      "images" -> ujson.Arr(pngBase64()), "output_dir" -> custom.toString))
    assert(!created("structuredContent").obj.contains("directory_registration_warning"))
    def generation(name: String, hash: Option[String] = None) = call("txt2img", ujson.Obj("prompt" -> "test", "steps" -> 1,
      "width" -> 64, "height" -> 64, "return_images" -> false,
      "always_on" -> ujson.Obj("aesthetic_gradients" -> ujson.Obj.from(Seq("enabled" -> ujson.Bool(true),
        "embedding" -> ujson.Str(name)) ++ hash.toSeq.map(h => "embedding_sha256" -> ujson.Str(h))))))
    val replay = generation("unique-replay")
    assert(!replay("isError").bool, textOf(replay))
    val managed = Files.createDirectories(base.resolve("aesthetic_embeddings"))
    val collision = Files.writeString(managed.resolve("unique-replay.pt"), "different")
    try
      val ambiguous = generation("unique-replay")
      assert(ambiguous("isError").bool && textOf(ambiguous).contains("ambiguous"), textOf(ambiguous))
      val expected = sdfx.core.aesthetic.AestheticEmbeddingLocator.fingerprint(Paths.get(created("structuredContent")("path").str))
      val byHash = generation("unique-replay", Some(expected))
      assert(!byHash("isError").bool, textOf(byHash))
      val state = Await.result(services.client.raw("get_state"), 10.seconds)
      val hook = state("last_generation")("request")("params")("hooks")("aesthetic_gradients")
      assertEquals(Paths.get(hook("embedding").str), Paths.get(created("structuredContent")("path").str).toRealPath())
      assertEquals(hook("embedding_sha256").str, expected)
      val wrong = generation("unique-replay", Some("0" * 64))
      assert(wrong("isError").bool, textOf(wrong))
      val explicit = generation(created("structuredContent")("path").str)
      assert(!explicit("isError").bool, textOf(explicit))
    finally Files.deleteIfExists(collision)
  }

  test("an existing permitted aesthetic file becomes discoverable after explicit generation use") {
    val base = home.outputs(services.settings)
    val dir = Files.createDirectories(base.resolve("existing-aesthetic"))
    val file = Files.writeString(dir.resolve("existing-replay.pt"), "pre-existing embedding")
    val generated = call("txt2img", ujson.Obj("prompt" -> "test", "steps" -> 1, "width" -> 64, "height" -> 64,
      "return_images" -> false, "always_on" -> ujson.Obj("aesthetic_gradients" -> ujson.Obj("enabled" -> true, "embedding" -> file.toString))))
    assert(!generated("isError").bool, textOf(generated))
    val hash = sdfx.core.aesthetic.AestheticEmbeddingLocator.fingerprint(file)
    val png = base.resolve("existing-aesthetic-replay.png")
    PngMetadata.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), png,
      Seq("parameters" -> s"cat\nSteps: 1, Seed: 1, Size: 64x64, Aesthetic embedding: existing-replay, Aesthetic embedding SHA256: $hash"))
    val inspected = ok("inspect_generation_metadata", ujson.Obj("image" -> png.toString))
    assertEquals(Paths.get(inspected("structuredContent")("always_on")("aesthetic_gradients")("embedding").str), file.toRealPath())
  }

  test("timed-out aesthetic creation retains temporary input until the worker terminates") {
    var materialized: Option[java.nio.file.Path] = None
    val tool = new AestheticEmbeddingTool(services, tools.pathPolicy, () => home.outputs(services.settings),
      suffix => {
        val p = Files.createTempFile(home.outputs(services.settings), "aesthetic-timeout-", suffix)
        materialized = Some(p)
        p
      }, _ => (), timeout = 100.millis)
    intercept[ToolError] {
      tool.call(ujson.Obj("operation" -> "create", "name" -> "slow", "images" -> ujson.Arr(pngBase64())), ToolContext.none)
    }
    val path = materialized.getOrElse(fail("temporary input was not made"))
    assert(Files.exists(path), "worker may still be using the input")
    val end = System.nanoTime() + 10.seconds.toNanos
    while Files.exists(path) && System.nanoTime() < end do Thread.sleep(25)
    assert(!Files.exists(path), "temporary input was not removed after the worker terminated")
  }

  test("interrupted aesthetic caller retains temporary input until the worker terminates") {
    val registered = new java.util.concurrent.CountDownLatch(1)
    val materialized = new java.util.concurrent.atomic.AtomicReference[java.nio.file.Path]()
    val thrown = new java.util.concurrent.atomic.AtomicReference[Throwable]()
    val tool = new AestheticEmbeddingTool(services, tools.pathPolicy, () => home.outputs(services.settings),
      suffix => {
        val p = Files.createTempFile(home.outputs(services.settings), "aesthetic-interrupt-", suffix)
        materialized.set(p)
        p
      }, _ => (), timeout = 30.seconds)
    val ctx = new ToolContext:
      def progress(progress: Double, total: Option[Double], message: String): Unit = ()
      def onCancel(f: () => Unit): Unit = registered.countDown()
      def isCancelled: Boolean = false
    val caller = new Thread(() => {
      try
        tool.call(ujson.Obj("operation" -> "create", "name" -> "slow", "images" -> ujson.Arr(pngBase64())), ctx)
        ()
      catch case e: Throwable => thrown.set(e)
    })
    caller.start()
    try
      assert(registered.await(10, java.util.concurrent.TimeUnit.SECONDS), "request was not registered")
      val path = materialized.get()
      assert(path != null && Files.exists(path), "temporary input was not made")
      caller.interrupt()
      caller.join(3000)
      assert(!caller.isAlive, "caller did not stop")
      assert(thrown.get().isInstanceOf[InterruptedException], s"unexpected result: ${thrown.get()}")
      assert(Files.exists(path), "worker may still be using the input")
      val end = System.nanoTime() + 10.seconds.toNanos
      while Files.exists(path) && System.nanoTime() < end do Thread.sleep(25)
      assert(!Files.exists(path), "temporary input was not removed after the worker terminated")
    finally
      if caller.isAlive then { caller.interrupt(); caller.join(3000) }
  }

  test("aesthetic embedding tools follow a changed output directory") {
    val before = services.settings
    val moved = home.root.resolve("moved-output")
    Files.createDirectories(moved)
    try
      services.updateSettings(_.copy(outputDir = moved.toString))
      val listed = ok("aesthetic_embedding", ujson.Obj("operation" -> "list"))
      assertEquals(Paths.get(listed("structuredContent")("output_dir").str), moved.resolve("aesthetic_embeddings").toRealPath())
      val embedding = Files.writeString(moved.resolve("aesthetic_embeddings/change.pt"), "fixture")
      val hash = sdfx.core.aesthetic.AestheticEmbeddingLocator.fingerprint(embedding)
      val image = moved.resolve("new.png")
      PngMetadata.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), image,
        Seq("parameters" -> s"cat\nSteps: 1, Seed: 1, Size: 64x64, Aesthetic embedding: change, Aesthetic embedding SHA256: $hash"))
      val inspected = ok("inspect_generation_metadata", ujson.Obj("image" -> "new.png"))
      assertEquals(Paths.get(inspected("structuredContent")("always_on")("aesthetic_gradients")("embedding").str), embedding.toRealPath())
    finally services.updateSettings(_ => before)
  }

  test("ControlNet Fastload is discoverable with an explicit operation schema") {
    val ts = server.handle(request("tools/list"), _ => ()).get("result")("tools").arr
    val tool = ts.find(_("name").str == "controlnet_fastload").getOrElse(fail("Fastload tool missing"))
    val schema = tool("inputSchema")
    assertEquals(schema("type").str, "object")
    assert(schema("required").arr.exists(_.str == "operation"))
    assert(schema("properties").obj.contains("path"))
    val version = ok("controlnet_fastload", ujson.Obj("operation" -> "version"))
    assertEquals(version("structuredContent")("version").num.toInt, 1)
    val rejected = call("controlnet_fastload", ujson.Obj("operation" -> "not-an-operation"))
    assert(rejected("isError").bool)
  }

  test("ControlNet Fastload inspect sends a temporary byte-exact snapshot to the worker") {
    val path = home.outputs(services.settings).resolve("source-fastload.cni")
    Files.createDirectories(path.getParent)
    val source = Array[Byte](17, 0, 42, -1)
    Files.write(path, source)
    val result = ok("controlnet_fastload", ujson.Obj("operation" -> "inspect", "path" -> path.toString))("structuredContent")
    val sent = Paths.get(result("test_input_path").str)
    assertNotEquals(sent, path)
    assertEquals(Base64.getDecoder.decode(result("test_input_base64").str).toSeq, source.toSeq)
    assert(!Files.exists(sent), "worker snapshot must be removed after the worker finishes")
    assertEquals(Files.readAllBytes(path).toSeq, source.toSeq)
  }

  test("timed-out Fastload inspection retains its snapshot until the worker finishes") {
    val input = home.outputs(services.settings).resolve("fastload-timeout-source.cni")
    Files.createDirectories(input.getParent)
    Files.write(input, Array[Byte](7, 8, 9))
    var staged: Option[java.nio.file.Path] = None
    val tool = new ControlNetFastloadTool(services, tools.pathPolicy, home.outputs(services.settings), _ => (),
      suffix => { val p = Files.createTempFile(input.getParent, "fastload-timeout-", suffix); staged = Some(p); p },
      timeout = 100.millis)
    intercept[java.util.concurrent.TimeoutException] {
      tool.call(ujson.Obj("operation" -> "inspect", "path" -> input.toString), ToolContext.none)
    }
    val path = staged.getOrElse(fail("no staged file"))
    assert(Files.exists(path), "worker has not finished reading")
    val deadline = System.nanoTime() + 10.seconds.toNanos
    while Files.exists(path) && System.nanoTime() < deadline do Thread.sleep(25)
    assert(!Files.exists(path), "staged file must be removed after worker completion")
  }

  test("Fastload rejects protected PNG before dispatch and removes its snapshot") {
    val input = home.outputs(services.settings).resolve("protected-fastload.png")
    Files.createDirectories(input.getParent)
    val pixels = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
    sdfx.core.png.ScrambledPng.write(pixels, input, Nil, Some("private-test-secret"))
    var staged: Option[java.nio.file.Path] = None
    val tool = new ControlNetFastloadTool(services, tools.pathPolicy, home.outputs(services.settings), _ => (),
      suffix => { val p = Files.createTempFile(input.getParent, "fastload-protected-", suffix); staged = Some(p); p })
    intercept[ToolError] {
      tool.call(ujson.Obj("operation" -> "inspect", "path" -> input.toString), ToolContext.none)
    }
    assert(staged.isEmpty, "protected bytes must be rejected before staging")
    assert(Files.exists(input))
  }

  test("CLIP Interrogator is discoverable and rejects bad input, out-of-policy files and protected sessions") {
    val ts = server.handle(request("tools/list"), _ => ()).get("result")("tools").arr
    val tool = ts.find(_("name").str == "clip_interrogate").getOrElse(fail("CLIP Interrogator tool missing"))
    val props = tool("inputSchema")("properties").obj
    assertEquals(props("mode")("enum").arr.map(_.str).toSeq, Seq("best", "fast", "classic", "negative", "caption"))
    assertEquals(props("operation")("enum").arr.map(_.str).toSeq, Seq("prompt", "analyze", "batch", "models", "unload"))
    assert(call("clip_interrogate", ujson.Obj("operation" -> "bogus"))("isError").bool)
    assert(call("clip_interrogate", ujson.Obj("operation" -> "prompt", "mode" -> "bogus", "image" -> "x"))("isError").bool)
    val outside = Files.createTempFile("clip-outside-", ".png")
    try
      val denied = call("clip_interrogate", ujson.Obj("operation" -> "prompt", "image" -> outside.toString))
      assert(denied("isError").bool)
      assert(textOf(denied).contains("allowed folders"), textOf(denied))
      val folder = call("clip_interrogate", ujson.Obj("operation" -> "batch", "folder" -> outside.getParent.toString))
      assert(folder("isError").bool)
      services.imageScrambleSession.unlock("private-test-password")
      try
        val blocked = call("clip_interrogate", ujson.Obj("operation" -> "prompt", "image" -> outside.toString))
        assert(blocked("isError").bool)
        assert(textOf(blocked).contains("protected image session"))
      finally services.imageScrambleSession.lock()
    finally Files.deleteIfExists(outside)
  }

  test("ControlNet Preprocessor is discoverable, lists modules and rejects bad input and protected sessions") {
    val ts = server.handle(request("tools/list"), _ => ()).get("result")("tools").arr
    val tool = ts.find(_("name").str == "controlnet_preprocess").getOrElse(fail("ControlNet Preprocessor tool missing"))
    val props = tool("inputSchema")("properties").obj
    assertEquals(props("operation")("enum").arr.map(_.str).toSeq, Seq("run", "list"))
    assertEquals(props("processor_res")("minimum").num.toInt, 64)
    assertEquals(props("processor_res")("maximum").num.toInt, 2048)
    val listed = ok("controlnet_preprocess", ujson.Obj("operation" -> "list"))
    assert(listed("structuredContent").obj.contains("preprocessors"))
    assert(call("controlnet_preprocess", ujson.Obj("operation" -> "bogus"))("isError").bool)
    val unknown = call("controlnet_preprocess", ujson.Obj("operation" -> "run", "preprocessor" -> "no-such-module", "image" -> "x"))
    assert(unknown("isError").bool)
    assert(textOf(unknown).contains("unknown preprocessor"), textOf(unknown))
    services.imageScrambleSession.unlock("private-test-password")
    try
      val blocked = call("controlnet_preprocess", ujson.Obj("operation" -> "run", "preprocessor" -> "canny", "image" -> "x"))
      assert(blocked("isError").bool)
      assert(textOf(blocked).contains("protected image session"), textOf(blocked))
    finally services.imageScrambleSession.lock()
  }

  test("ControlNet Fastload rejects protected sessions and out-of-policy files") {
    val outside = Files.createTempFile("fastload-outside-", ".cni")
    try
      val denied = call("controlnet_fastload", ujson.Obj("operation" -> "inspect", "path" -> outside.toString))
      assert(denied("isError").bool)
      services.imageScrambleSession.unlock("private-test-password")
      try
        val blocked = call("controlnet_fastload", ujson.Obj("operation" -> "inspect", "path" -> outside.toString))
        assert(blocked("isError").bool)
        assert(textOf(blocked).contains("protected image session"))
        assert(!textOf(blocked).contains("private-test-password"))
      finally services.imageScrambleSession.lock()
    finally Files.deleteIfExists(outside)
  }

  test("vae_tools rejects marked PNG before dispatch without exposing a desktop password") {
    val path = home.outputs(services.settings).resolve("mcp-protected-vae.png")
    Files.createDirectories(path.getParent)
    val image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
    sdfx.core.png.ScrambledPng.write(image, path, Nil, Some("private-password"))
    try
      val result = call("vae_tools", ujson.Obj("input_paths" -> ujson.Arr(path.toString)))
      assert(result("isError").bool)
      assert(textOf(result).contains("password"), textOf(result))
      assert(!textOf(result).contains("private-password"))
    finally Files.deleteIfExists(path)
  }

  test("tools/list: every tool has a JSON schema object with valid required fields") {
    val ts = server.handle(request("tools/list"), _ => ()).get("result")("tools").arr
    val names = ts.map(_("name").str).toSet
    for n <- Seq("txt2img", "img2img", "txt2img_async", "img2img_async", "get_job", "list_models", "set_model", "list_loras", "list_controlnets",
        "list_samplers", "list_recent", "get_image", "get_status", "interrupt", "list_scripts", "run_script", "vae_tools")
    do assert(names.contains(n), n)
    ts.foreach { t =>
      val s = t("inputSchema")
      assertEquals(s("type").str, "object", t("name").str)
      val props = s("properties").obj
      s.obj.get("required").foreach(_.arr.foreach(r => assert(props.contains(r.str), s"${t("name").str}: required ${r.str} not in properties")))
      props.values.foreach(p => assert(p.obj.contains("type") || p.obj.contains("oneOf"), s"${t("name").str}: untyped property $p"))
      assert(t("description").str.nonEmpty)
      t.obj.get("outputSchema").foreach(o => assertEquals(o("type").str, "object"))
    }
    val txt = ts.find(_("name").str == "txt2img").get
    assertEquals(txt("inputSchema")("required").arr.map(_.str).toSeq, Seq("prompt"))
    assert(txt.obj.contains("outputSchema"))
    val i2i = ts.find(_("name").str == "img2img").get
    assertEquals(i2i("inputSchema")("required").arr.map(_.str).toSet, Set("prompt", "init_image"))
  }

  test("documented nested AlwaysOn payload validates against the advertised JSON schema") {
    val ts = server.handle(request("tools/list"), _ => ()).get("result")("tools").arr
    val schema = ts.find(_("name").str == "txt2img").get("inputSchema")
    val documented = ujson.Obj("prompt" -> "a ceramic teapot on a wooden table", "seed" -> 173, "steps" -> 20,
      "always_on" -> ujson.Obj("freeu" -> ujson.Obj("enabled" -> true, "b1" -> 1.15, "b2" -> 1.2)))
    assertEquals(validate(documented, schema), Nil)
    val pluginSchema = schema("properties")("always_on")("additionalProperties")
    assertEquals(pluginSchema("type").str, "object")
    assertEquals(pluginSchema("additionalProperties")("type").arr.map(_.str).toSet, Set("string", "number", "boolean"))
    val scalarInsteadOfObject = ujson.read(documented.render()).obj
    scalarInsteadOfObject("always_on")("freeu") = true
    assert(validate(scalarInsteadOfObject, schema).nonEmpty)
  }

  test("model keyword lookup exposes checkpoint and LoRA provenance without arbitrary file access") {
    ok("set_model", ujson.Obj("name" -> "foo"))
    val checkpoint = ok("model_keyword_lookup", ujson.Obj())("structuredContent")
    assertEquals(checkpoint("kind").str, "checkpoint")
    assertEquals(checkpoint("filename").str, "foo.safetensors")
    assertEquals(checkpoint("hash").str, "deadbeef")
    assertEquals(checkpoint("keywords").arr.map(_.str).toSeq, Seq("fixture"))
    assertEquals(checkpoint("source").str, "default")
    assertEquals(checkpoint("model").str, "foo.safetensors")
    assertEquals(checkpoint("match_source").str, "model-keyword.txt")
    val lora = ok("model_keyword_lookup", ujson.Obj("kind" -> "lora", "name" -> "My Lora"))("structuredContent")
    assertEquals(lora("kind").str, "lora")
    assertEquals(lora("filename").str, "My Lora.safetensors")
    assert(!lora("default_catalog_available").bool)
    assert(call("model_keyword_lookup", ujson.Obj("kind" -> "lora"))("isError").bool)
    assert(call("model_keyword_lookup", ujson.Obj("name" -> "../../outside"))("isError").bool)
    assert(call("model_keyword_lookup", ujson.Obj("action" -> "delete"))("isError").bool)
  }

  test("MCP generation uses Model Keyword default and respects an explicit opt-out") {
    ok("set_model", ujson.Obj("name" -> "foo"))
    def lastHooks = Await.result(services.client.raw("get_state"), 10.seconds)("last_generation")("request")("params")("hooks").obj
    ok("txt2img", ujson.Obj("prompt" -> "cat", "steps" -> 1, "width" -> 64, "height" -> 64, "return_images" -> false))
    assert(lastHooks.contains("model_keyword"))
    ok("txt2img", ujson.Obj("prompt" -> "cat", "steps" -> 1, "width" -> 64, "height" -> 64,
      "return_images" -> false, "model_keyword" -> false))
    assert(!lastHooks.contains("model_keyword"))
    ok("txt2img", ujson.Obj("prompt" -> "cat", "steps" -> 1, "width" -> 64, "height" -> 64,
      "return_images" -> false, "model_keyword" -> true))
    assert(lastHooks.contains("model_keyword"))
  }

  test("extended generation fields reach the queued worker and temporary ControlNet input is cleaned") {
    val file = home.root.resolve("models/controlnet/test-control.safetensors")
    Files.createDirectories(file.getParent); Files.write(file, Array[Byte](1, 2, 3))
    services.catalog.refresh()
    val req = ujson.Obj("prompt" -> "base", "steps" -> 1, "width" -> 64, "height" -> 64, "return_images" -> false,
      "subseed" -> 4321, "subseed_strength" -> 0.4, "eta" -> 0.3,
      "manual_loras" -> ujson.Arr(ujson.Obj("name" -> "My Lora", "weight" -> 0.8, "unet" -> 0.5, "dyn" -> 2)),
      "controlnet" -> ujson.Arr(ujson.Obj("model" -> "test-control", "image" -> pngBase64(64, 64), "preprocessor" -> "canny",
        "preprocessor_args" -> ujson.Obj("low_threshold" -> 80), "guidance_start" -> 0.1, "guidance_end" -> 0.8)),
      "hires" -> ujson.Obj("enabled" -> true, "scale" -> 1, "prompt" -> "refined", "negative_prompt" -> "haze", "cfg_scale" -> 4.0,
        "sampler_name" -> "Euler a", "scheduler" -> "Karras"))
    ok("txt2img", req)
    val p = Await.result(services.client.raw("get_state"), 10.seconds)("last_generation")("request")("params")
    assertEquals(p("subseed").num, 4321.0); assertEquals(p("subseed_strength").num, 0.4)
    assertEquals(p("eta").num, 0.3); assertEquals(p("loras")(0)("dyn").num, 2.0)
    assertEquals(p("hires")("prompt").str, "refined"); assertEquals(p("hires")("cfg_scale").num, 4.0)
    assertEquals(p("controlnet")(0)("preprocessor_args")("low_threshold").num, 80.0)
    assert(!Files.exists(Paths.get(p("controlnet")(0)("image").str)))
    val tmp = home.tmpDir(services.settings)
    def files(): Set[String] =
      val stream = Files.list(tmp)
      try { import scala.jdk.CollectionConverters.*; stream.iterator().asScala.map(_.toString).toSet }
      finally stream.close()
    val before = files()
    val denied = call("txt2img", ujson.Obj("prompt" -> "base", "controlnet" -> ujson.Arr(
      ujson.Obj("model" -> "test-control", "image" -> pngBase64(64, 64)),
      ujson.Obj("model" -> "not-in-catalog"))))
    assert(denied("isError").bool)
    assertEquals(files(), before)
  }

  test("txt2img returns image + text + structuredContent and saves a PNG with parameters") {
    val r = ok("txt2img", ujson.Obj("prompt" -> "a red fox", "negative_prompt" -> "blurry", "seed" -> 42, "steps" -> 3, "width" -> 64, "height" -> 64,
      "loras" -> ujson.Arr(ujson.Obj("name" -> "My Lora", "weight" -> 0.7)), "max_image_side" -> 32))
    val content = r("content").arr
    val img = content.find(_("type").str == "image").get
    assertEquals(img("mimeType").str, "image/png")
    val bytes = Base64.getDecoder.decode(img("data").str)
    val preview = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))
    assertEquals(preview.getWidth, 32) // downscaled from 64
    val sc = r("structuredContent")("images").arr
    assertEquals(sc.size, 1)
    val path = Paths.get(sc(0)("path").str)
    assert(Files.isRegularFile(path))
    assert(path.startsWith(home.root.resolve("outputs")))
    assertEquals(sc(0)("seed").num.toLong, 42L)
    val params = PngMetadata.readParameters(path).get
    assert(params.startsWith("a red fox, <lora:My Lora:0.7>"), params)
    assert(params.contains("Negative prompt: blurry"))
    assert(params.contains("Seed: 42"))
    assert(textOf(r).contains(path.toString))
    assertEquals(sc(0)("infotext").str, params)
    assertEquals(javax.imageio.ImageIO.read(path.toFile).getWidth, 64) // full size on disk
  }

  test("txt2img return_images=false and batch") {
    val r = ok("txt2img", ujson.Obj("prompt" -> "x", "steps" -> 1, "width" -> 64, "height" -> 64, "batch_size" -> 2, "n_iter" -> 2, "return_images" -> false))
    assert(!r("content").arr.exists(_("type").str == "image"))
    assertEquals(r("structuredContent")("images").arr.size, 4)
  }

  test("invalid params produce isError results") {
    def err(args: ujson.Obj, needle: String) =
      val r = call("txt2img", args)
      assert(r("isError").bool, s"expected error for $args")
      assert(textOf(r).toLowerCase.contains(needle.toLowerCase), s"'${textOf(r)}' lacks '$needle'")
    err(ujson.Obj(), "prompt")
    err(ujson.Obj("prompt" -> "x", "steps" -> 0), "steps")
    err(ujson.Obj("prompt" -> "x", "width" -> 70), "width")
    err(ujson.Obj("prompt" -> "x", "steps" -> "many"), "steps")
    err(ujson.Obj("prompt" -> "x", "batch_size" -> 8, "n_iter" -> 4), "16")
    err(ujson.Obj("prompt" -> "x", "sampler_name" -> "Nope"), "Euler a")
    err(ujson.Obj("prompt" -> "x", "checkpoint" -> "missing_model"), "checkpoint not found")
    err(ujson.Obj("prompt" -> "x", "loras" -> ujson.Arr(ujson.Obj("name" -> "ghost"))), "LoRA not found")
    err(ujson.Obj("prompt" -> "x", "colour" -> "red"), "colour")
    val i2i = call("img2img", ujson.Obj("prompt" -> "x", "init_image" -> "not an image at all"))
    assert(i2i("isError").bool)
  }

  test("img2img with base64 input (and data URI mask) defaults to the init image size") {
    val b64 = pngBase64(96, 72)
    val r = ok("img2img", ujson.Obj("prompt" -> "paint it", "init_image" -> b64, "mask" -> s"data:image/png;base64,${pngBase64(96, 72)}",
      "denoising_strength" -> 0.4, "inpainting_fill" -> "original", "steps" -> 2, "seed" -> 7))
    val p = Paths.get(r("structuredContent")("images")(0)("path").str)
    assert(p.toString.contains("img2img"), p.toString)
    val params = PngMetadata.readParameters(p).get
    assert(params.contains("Size: 96x72"), params)
    assert(params.contains("Denoising strength: 0.4"), params)
    assert(r("content").arr.exists(_("type").str == "image"))
    // the temp input files were cleaned up
    assert(eventually()(!Files.list(home.root.resolve("outputs/.tmp")).toList.toString.contains("sdfx-")))
  }

  test("img2img folder queues allowed inputs and records original sources in saved PNGs") {
    val folder = Files.createDirectories(home.outputs(services.settings).resolve("folder-input-test"))
    val first = Files.write(folder.resolve("a.png"), Base64.getDecoder.decode(pngBase64(64, 64)))
    val second = Files.write(folder.resolve("b.png"), Base64.getDecoder.decode(pngBase64(64, 64)))
    val requested = if System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") then
      folder.getParent.resolve("FOLDER-INPUT-TEST") else folder
    val sub = ok("img2img_folder_async", ujson.Obj("prompt" -> "folder test", "image_folder" -> requested.toString,
      "width" -> 64, "height" -> 64, "steps" -> 1, "return_images" -> false))
    val id = sub("structuredContent")("job_id").str
    val done = ok("get_job", ujson.Obj("job_id" -> id, "wait_seconds" -> 60))("structuredContent")
    assertEquals(done("status").str, "done", done.toString)
    val images = done("images").arr
    assertEquals(images.size, 2)
    val recorded = images.map { image =>
      val png = Paths.get(image("path").str)
      sdfx.core.infotext.Infotext.parse(PngMetadata.readParameters(png).get).fields("NUI img2img source")
    }.toSet
    assertEquals(recorded, Set(first.toRealPath().toString, second.toRealPath().toString))
    val outside = Files.createTempDirectory("mcp-folder-outside-")
    try
      val denied = call("img2img_folder_async", ujson.Obj("prompt" -> "x", "image_folder" -> outside.toString))
      assert(denied("isError").bool, denied.toString)
    finally Files.delete(outside)
  }

  test("img2img folder cancellation keeps finished images and permits a later generation") {
    val folder = Files.createDirectories(home.outputs(services.settings).resolve("folder-cancel-test"))
    for name <- Seq("a.png", "b.png") do
      Files.write(folder.resolve(name), Base64.getDecoder.decode(pngBase64(64, 64)))
    val saved = scala.concurrent.Promise[Unit]()
    val remove = services.queue.addListener {
      case e: sdfx.engine.JobEvent.ImageSaved if services.queue.current.exists(_.id == e.jobId) => saved.trySuccess(()); ()
      case _ => ()
    }
    try
      val sub = ok("img2img_folder_async", ujson.Obj("prompt" -> "folder cancel", "image_folder" -> folder.toString,
        "width" -> 64, "height" -> 64, "steps" -> 30, "return_images" -> false,
        "extra" -> ujson.Obj("step_delay" -> "0.04")))
      val id = sub("structuredContent")("job_id").str
      Await.result(saved.future, 45.seconds)
      ok("cancel_job", ujson.Obj("job_id" -> id))
      val result = call("get_job", ujson.Obj("job_id" -> id, "wait_seconds" -> 45, "return_images" -> false))("structuredContent")
      assertEquals(result("status").str, "cancelled", result.toString)
      assertEquals(result("images").arr.size, 1, result.toString)
      assert(Files.isRegularFile(Paths.get(result("images")(0)("path").str)))
      ok("txt2img", ujson.Obj("prompt" -> "recovery", "width" -> 64, "height" -> 64, "steps" -> 1, "return_images" -> false))
    finally remove()
  }

  test("failed folder job reports already saved images, error and original provenance") {
    val folder = Files.createDirectories(home.outputs(services.settings).resolve("folder-failure-partial"))
    val first = folder.resolve("a.png")
    Files.write(first, Base64.getDecoder.decode(pngBase64(64, 64)))
    val second = folder.resolve("b.png")
    val pixels = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
    sdfx.core.png.ScrambledPng.write(pixels, second, Nil, Some("failure-test-secret"))
    val submission = ok("img2img_folder_async", ujson.Obj("prompt" -> "partial failure", "image_folder" -> folder.toString,
      "width" -> 64, "height" -> 64, "steps" -> 1, "return_images" -> false))
    val id = submission("structuredContent")("job_id").str
    val raw = call("get_job", ujson.Obj("job_id" -> id, "wait_seconds" -> 45, "return_images" -> false))
    assert(raw("isError").bool, raw.toString)
    val result = raw("structuredContent")
    assertEquals(result("status").str, "failed")
    assert(result("error").str.nonEmpty)
    assertEquals(result("images").arr.size, 1, result.toString)
    val output = Paths.get(result("images")(0)("path").str)
    assert(Files.isRegularFile(output))
    assertEquals(sdfx.core.infotext.Infotext.parse(PngMetadata.readParameters(output).get).fields("NUI img2img source"), first.toRealPath().toString)
    val next = ok("txt2img", ujson.Obj("prompt" -> "recovery", "width" -> 64, "height" -> 64, "steps" -> 1, "return_images" -> false))
    assertEquals(next("structuredContent")("images").arr.size, 1)
  }

  test("failed async folder does not return images saved by another queued job") {
    val folder = Files.createDirectories(home.outputs(services.settings).resolve("folder-failure-isolation"))
    Files.write(folder.resolve("a.png"), Base64.getDecoder.decode(pngBase64(64, 64)))
    val pixels = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
    sdfx.core.png.ScrambledPng.write(pixels, folder.resolve("b.png"), Nil, Some("failure-isolation-secret"))
    val first = ok("txt2img_async", ujson.Obj("prompt" -> "unrelated", "width" -> 64, "height" -> 64,
      "steps" -> 30, "extra" -> ujson.Obj("step_delay" -> "0.04"), "return_images" -> false))
    val firstId = first("structuredContent")("job_id").str
    assert(eventually()(services.queue.current.exists(_.id == firstId)))
    val second = ok("img2img_folder_async", ujson.Obj("prompt" -> "failure isolation", "image_folder" -> folder.toString,
      "width" -> 64, "height" -> 64, "steps" -> 1, "return_images" -> false))
    val firstResult = ok("get_job", ujson.Obj("job_id" -> firstId, "wait_seconds" -> 45, "return_images" -> false))("structuredContent")
    val raw = call("get_job", ujson.Obj("job_id" -> second("structuredContent")("job_id").str, "wait_seconds" -> 45, "return_images" -> false))
    assert(raw("isError").bool)
    val secondResult = raw("structuredContent")
    assertEquals(secondResult("status").str, "failed")
    assertEquals(secondResult("images").arr.size, 1, secondResult.toString)
    assert(firstResult("images")(0)("path").str != secondResult("images")(0)("path").str)
    assertEquals(sdfx.core.infotext.Infotext.parse(PngMetadata.readParameters(Paths.get(secondResult("images")(0)("path").str)).get).fields("NUI img2img source"), folder.resolve("a.png").toRealPath().toString)
  }

  test("img2img folder MCP does not process protected pixels without a password session") {
    val folder = Files.createDirectories(home.outputs(services.settings).resolve("folder-protected-test"))
    val input = folder.resolve("protected.png")
    val pixels = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
    sdfx.core.png.ScrambledPng.write(pixels, input, Nil, Some("mcp-folder-secret"))
    val submitted = ok("img2img_folder_async", ujson.Obj("prompt" -> "protected input", "image_folder" -> folder.toString,
      "width" -> 64, "height" -> 64, "steps" -> 1, "return_images" -> false))
    val result = call("get_job", ujson.Obj("job_id" -> submitted("structuredContent")("job_id").str,
      "wait_seconds" -> 30, "return_images" -> false))("structuredContent")
    assertEquals(result("status").str, "failed", result.toString)
    assertEquals(sdfx.core.png.PngMetadata.readTexts(input).get("Encrypt"), Some("pixel_shuffle_3"))
  }

  test("progress notifications are emitted when a progressToken is given") {
    val rec = new Recorder
    val r = call("txt2img", ujson.Obj("prompt" -> "p", "steps" -> 5, "width" -> 64, "height" -> 64, "extra" -> ujson.Obj("step_delay" -> "0.02")),
      rec, token = Some(ujson.Num(77)))
    assert(!r("isError").bool)
    val ps = rec.all.filter(_("method").str == "notifications/progress").map(_("params"))
    assert(ps.size >= 3, ps.toString)
    assert(ps.forall(_("progressToken").num == 77))
    val values = ps.map(_("progress").num)
    assertEquals(values, values.sorted.distinct)
    assertEquals(values.last, 100.0)
    assert(ps.exists(_.obj.get("message").exists(_.str.contains("step"))))
  }

  test("notifications/cancelled interrupts the job tied to the request") {
    val rec = new Recorder
    val f = Future(server.handle(callReq("txt2img", ujson.Obj("prompt" -> "slow", "steps" -> 100, "width" -> 64, "height" -> 64,
      "extra" -> ujson.Obj("step_delay" -> "0.05")), ujson.Str("cancel-me"), Some(ujson.Str("t"))), rec))
    assert(eventually()(rec.all.exists(_("params")("progress").num > 1)))
    assert(services.queue.current.exists(_.origin == "mcp"))
    server.handle(ujson.Obj("jsonrpc" -> "2.0", "method" -> "notifications/cancelled", "params" -> ujson.Obj("requestId" -> "cancel-me")), _ => ())
    assertEquals(Await.result(f, 30.seconds), None) // no reply for a cancelled request
    assert(eventually()(services.queue.isIdle))
  }

  test("async job flow: submit, poll with wait, images when done") {
    val sub = ok("txt2img_async", ujson.Obj("prompt" -> "async", "steps" -> 4, "width" -> 64, "height" -> 64, "extra" -> ujson.Obj("step_delay" -> "0.02")))
    val id = sub("structuredContent")("job_id").str
    val r = ok("get_job", ujson.Obj("job_id" -> id, "wait_seconds" -> 30))
    val s = r("structuredContent")
    assertEquals(s("status").str, "done")
    assertEquals(s("progress").num, 1.0)
    assertEquals(s("images").arr.size, 1)
    assert(Files.isRegularFile(Paths.get(s("images")(0)("path").str)))
    assert(r("content").arr.exists(_("type").str == "image"))
    val unknown = call("get_job", ujson.Obj("job_id" -> "nope"))
    assert(unknown("isError").bool)
  }

  test("async job can be cancelled") {
    val sub = ok("txt2img_async", ujson.Obj("prompt" -> "long", "steps" -> 100, "width" -> 64, "height" -> 64, "extra" -> ujson.Obj("step_delay" -> "0.05")))
    val id = sub("structuredContent")("job_id").str
    assert(eventually()(services.queue.current.exists(_.id == id)))
    ok("cancel_job", ujson.Obj("job_id" -> id))
    val r = call("get_job", ujson.Obj("job_id" -> id, "wait_seconds" -> 30))
    assertEquals(r("structuredContent")("status").str, "cancelled")
  }

  test("interrupt stops the running job") {
    val sub = ok("txt2img_async", ujson.Obj("prompt" -> "long", "steps" -> 100, "width" -> 64, "height" -> 64, "extra" -> ujson.Obj("step_delay" -> "0.05")))
    val id = sub("structuredContent")("job_id").str
    assert(eventually()(services.queue.current.exists(_.id == id)))
    val r = ok("interrupt", ujson.Obj())
    assert(r("structuredContent")("interrupted").bool)
    assertEquals(call("get_job", ujson.Obj("job_id" -> id, "wait_seconds" -> 30))("structuredContent")("status").str, "cancelled")
  }

  test("list_recent and get_image; path traversal is rejected") {
    ok("txt2img", ujson.Obj("prompt" -> "gallery cat", "steps" -> 1, "width" -> 64, "height" -> 64))
    val recent = ok("list_recent", ujson.Obj("limit" -> 3, "query" -> "gallery cat"))
    val items = recent("structuredContent")("images").arr
    assert(items.nonEmpty)
    assert(items(0)("infotext").str.contains("gallery cat"))
    val path = items(0)("path").str
    val img = ok("get_image", ujson.Obj("path" -> path, "max_side" -> 16))
    assert(img("content").arr.exists(_("type").str == "image"))
    val rel = home.root.resolve("outputs").relativize(Paths.get(path)).toString
    ok("get_image", ujson.Obj("path" -> rel))
    val secret = home.root.resolve("config/secret.png")
    Files.write(secret, Base64.getDecoder.decode(pngBase64()))
    for bad <- Seq(secret.toString, "../config/secret.png", home.root.resolve("outputs/../config/secret.png").toString, "C:\\Windows\\win.ini", "")
    do
      val r = call("get_image", ujson.Obj("path" -> bad))
      assert(r("isError").bool, s"$bad must be rejected")
  }

  test("model, LoRA, sampler, script listings and set_model") {
    val models = ok("list_models", ujson.Obj())
    val cks = models("structuredContent")("checkpoints").arr
    assertEquals(cks.map(_("name").str).toSet, Set("foo", "sdxl/Bar_XL"))
    assert(cks.find(_("name").str == "foo").get("loaded").bool)
    val set = ok("set_model", ujson.Obj("name" -> "Bar_XL"))
    assertEquals(set("structuredContent")("loaded")("name").str, "Bar_XL")
    assert(call("set_model", ujson.Obj("name" -> "nope"))("isError").bool)
    ok("set_model", ujson.Obj("name" -> "foo"))
    val loras = ok("list_loras", ujson.Obj())
    assertEquals(loras("structuredContent")("loras")(0)("tag").str, "<lora:My Lora:1.0>")
    val controlnets = ok("list_controlnets", ujson.Obj())("structuredContent")
    assertEquals(controlnets("control_modes").arr.map(_.str).toSeq, ControlNetUnit.ControlModes)
    assertEquals(controlnets("preprocessors")(0).str, "none")
    val samplers = ok("list_samplers", ujson.Obj())("structuredContent")
    assert(samplers("samplers").arr.exists(_("name").str == "Euler a"))
    assert(!samplers("samplers").arr.exists(_("name").str == "Weird"))
    assertEquals(samplers("styles").arr.map(_.str).toSeq, Seq("Pretty"))
    val scripts = ok("list_scripts", ujson.Obj())("structuredContent")("scripts").arr
    val xyz = scripts.find(_("id").str == "xyz_grid").get
    assert(xyz("controls").arr.exists(_("key").str == "x_type"))
    val status = ok("get_status", ujson.Obj())("structuredContent")
    assertEquals(status("engine_state").str, "ready")
    assertEquals(status("loaded_model")("name").str, "foo")
    assertEquals(status("engine")("device").str, "cpu")
  }

  test("worker VAE path stays out of MCP status and model responses") {
    ok("set_model", ujson.Obj("name" -> "foo"))
    val model = services.setVae(Some("vae-ft-mse"), remember = false)
    val privatePath = model.vaePath.getOrElse(fail("fake worker did not return VAE path"))
    try
      for tool <- Seq("get_status", "list_models") do
        val response = ok(tool, ujson.Obj())
        assert(!response.toString.contains("vae_path"), s"$tool exposed the worker field")
        assert(!response.toString.contains(privatePath.replace("\\", "\\\\")), s"$tool exposed the VAE path")
    finally services.setVae(None, remember = false)
  }

  test("structuredContent of txt2img / img2img conforms to the declared outputSchema") {
    val ts = server.handle(request("tools/list"), _ => ()).get("result")("tools").arr
    def schemaOf(n: String) = ts.find(_("name").str == n).get("outputSchema")
    val r = ok("txt2img", ujson.Obj("prompt" -> "schema", "steps" -> 1, "width" -> 64, "height" -> 64))
    assertEquals(validate(r("structuredContent"), schemaOf("txt2img")), Nil)
    // the JSON text block mirrors structuredContent for older clients
    assert(r("content").arr.exists(c => c("type").str == "text" && c("text").str.startsWith("{") && ujson.read(c("text").str) == r("structuredContent")))
    val i = ok("img2img", ujson.Obj("prompt" -> "schema", "init_image" -> pngBase64(64, 64), "steps" -> 1))
    assertEquals(validate(i("structuredContent"), schemaOf("img2img")), Nil)
  }

  test("init_image paths: outputs folder and allowed dirs only, UNC rejected, uniform errors") {
    val gen = ok("txt2img", ujson.Obj("prompt" -> "source", "steps" -> 1, "width" -> 64, "height" -> 64))
    val inOutputs = gen("structuredContent")("images")(0)("path").str
    ok("img2img", ujson.Obj("prompt" -> "from outputs", "init_image" -> inOutputs, "steps" -> 1))
    val rel = home.root.resolve("outputs").relativize(Paths.get(inOutputs)).toString
    ok("img2img", ujson.Obj("prompt" -> "relative", "init_image" -> rel, "steps" -> 1))

    val outside = Files.createTempDirectory("sdfx-outside-")
    try
      val secret = outside.resolve("secret.png")
      Files.write(secret, Base64.getDecoder.decode(pngBase64()))
      def denied(spec: String, needle: String) =
        val r = call("img2img", ujson.Obj("prompt" -> "x", "init_image" -> spec, "steps" -> 1))
        assert(r("isError").bool, s"$spec must be rejected")
        assert(textOf(r).contains(needle), textOf(r))
      denied(secret.toString, "allowed folders")
      denied(outside.resolve("missing.png").toString, "allowed folders") // same message: no existence oracle
      denied("../config/styles.csv", "allowed folders")
      denied("\\\\attacker.invalid\\share\\x.png", "UNC")
      denied("//attacker.invalid/share/x.png", "UNC")
      // an explicitly allowed folder works
      val extra = new McpServer(new McpTools(services, McpTools.waitForEngine(services), allowedDirs = Seq(outside)).all)
      val r = extra.handle(callReq("img2img", ujson.Obj("prompt" -> "allowed", "init_image" -> secret.toString, "steps" -> 1)), _ => ()).get("result")
      assert(!r("isError").bool, textOf(r))
      // get_image follows the same policy
      assert(call("get_image", ujson.Obj("path" -> secret.toString))("isError").bool)
      assert(!extra.handle(callReq("get_image", ujson.Obj("path" -> secret.toString)), _ => ()).get("result")("isError").bool)
    finally deleteTree(outside)
  }

  test("size caps: input image dimensions, hires target, pixels per job") {
    val big = call("img2img", ujson.Obj("prompt" -> "x", "init_image" -> pngBase64(4104, 8), "width" -> 64, "height" -> 64))
    assert(big("isError").bool && textOf(big).contains("too large"), textOf(big))
    val hires = call("txt2img", ujson.Obj("prompt" -> "x", "width" -> 2048, "height" -> 2048, "hires" -> ujson.Obj("scale" -> 4)))
    assert(hires("isError").bool && textOf(hires).contains("hires target"), textOf(hires))
    val batch = call("txt2img", ujson.Obj("prompt" -> "x", "width" -> 4096, "height" -> 4096, "batch_size" -> 2))
    assert(batch("isError").bool && textOf(batch).contains("too large"), textOf(batch))
    val total = call("txt2img", ujson.Obj("prompt" -> "x", "width" -> 2048, "height" -> 2112, "batch_size" -> 2, "n_iter" -> 8))
    assert(total("isError").bool && textOf(total).contains("in total"), textOf(total))
    assert(call("txt2img", ujson.Obj("prompt" -> "x", "max_image_side" -> 8192))("isError").bool)
  }

  test("pending MCP jobs are capped") {
    val slowArgs = ujson.Obj("prompt" -> "fill", "steps" -> 100, "width" -> 64, "height" -> 64, "return_images" -> false, "extra" -> ujson.Obj("step_delay" -> "0.05"))
    try
      val first = ok("txt2img_async", slowArgs)("structuredContent")("job_id").str
      assert(eventually()(services.queue.current.exists(_.id == first)))
      (1 to McpTools.MaxPendingJobs).foreach(_ => ok("txt2img_async", slowArgs))
      val over = call("txt2img_async", slowArgs)
      assert(over("isError").bool && textOf(over).contains("too many queued"), textOf(over))
    finally
      ok("interrupt", ujson.Obj("clear_queue" -> true))
      assert(eventually(30.seconds)(services.queue.isIdle))
  }

  test("async submit returns immediately while the engine is still starting") {
    val home2 = tempHome()
    val s2 = TestEnv.services(home2)
    val gate = scala.concurrent.Promise[Any]()
    try
      val srv = new McpServer(new McpTools(s2, McpTools.awaitStart(gate.future)).all)
      val t0 = System.nanoTime()
      val sub = srv.handle(callReq("txt2img_async", ujson.Obj("prompt" -> "early bird", "steps" -> 1, "width" -> 64, "height" -> 64)), _ => ()).get("result")
      assert((System.nanoTime() - t0) < 5.seconds.toNanos)
      assertEquals(sub("structuredContent")("status").str, "waiting_for_engine")
      val id = sub("structuredContent")("job_id").str
      val st = srv.handle(callReq("get_job", ujson.Obj("job_id" -> id)), _ => ()).get("result")("structuredContent")
      assertEquals(st("status").str, "waiting_for_engine")
      gate.completeWith(s2.startAsync())
      val done = srv.handle(callReq("get_job", ujson.Obj("job_id" -> id, "wait_seconds" -> 60)), _ => ()).get("result")("structuredContent")
      assertEquals(done("status").str, "done", done.toString)
    finally
      s2.shutdown()
      deleteTree(home2.root)
  }

  test("synchronous script failure returns saved partial images and remains an error") {
    val r = call("run_script", ujson.Obj("script_id" -> "xyz_grid",
      "values" -> ujson.Obj("x_type" -> "Size", "x_values" -> "64x64,8192x8192"),
      "params" -> ujson.Obj("prompt" -> "partial sync", "steps" -> 1, "width" -> 64, "height" -> 64),
      "return_images" -> false))
    assert(r("isError").bool, r.toString)
    assert(textOf(r).contains("job limit exceeded"), textOf(r))
    assertEquals(r("structuredContent")("images").arr.size, 1, r.toString)
    assert(Files.isRegularFile(Paths.get(r("structuredContent")("images")(0)("path").str)))
  }

  test("run_script obeys the strict job limits (X/Y/Z Size axis, cell count)") {
    val base = ujson.Obj("prompt" -> "limits", "steps" -> 1, "width" -> 64, "height" -> 64)
    val huge = call("run_script", ujson.Obj("script_id" -> "xyz_grid", "values" -> ujson.Obj("x_type" -> "Size", "x_values" -> "64x64, 8192x8192"),
      "params" -> base, "return_images" -> false))
    assert(huge("isError").bool, textOf(huge))
    assert(textOf(huge).contains("job limit exceeded"), textOf(huge))
    val many = call("run_script", ujson.Obj("script_id" -> "xyz_grid", "values" -> ujson.Obj("x_type" -> "Seed", "x_values" -> "1-70"),
      "params" -> base, "return_images" -> false))
    assert(many("isError").bool, textOf(many))
    assert(textOf(many).contains("64 generations"), textOf(many))
    // async path reports the same failure
    val sub = ok("run_script", ujson.Obj("script_id" -> "xyz_grid", "async" -> true, "values" -> ujson.Obj("x_type" -> "Size", "x_values" -> "8192x8192"),
      "params" -> base))
    val st = call("get_job", ujson.Obj("job_id" -> sub("structuredContent")("job_id").str, "wait_seconds" -> 30))("structuredContent")
    assertEquals(st("status").str, "failed")
    assert(st("error").str.contains("job limit exceeded"), st.toString)
  }

  test("run_script never runs custom code and checks image control paths against the allowed dirs") {
    val cc = call("run_script", ujson.Obj("script_id" -> "custom_code", "values" -> ujson.Obj("code" -> "result = 1")))
    assert(cc("isError").bool)
    assert(cc("content")(0)("text").str.contains("custom_code cannot be run over MCP"))
    val outside = java.nio.file.Files.createTempFile("outside", ".png")
    val r = call("run_script", ujson.Obj("script_id" -> "img2img_alt", "values" -> ujson.Obj("override_prompt" -> "x"),
      "params" -> ujson.Obj("init_image" -> outside.toString)))
    assert(r("isError").bool, "init_image outside the allowed dirs must be refused")
    val scripts = ok("list_scripts", ujson.Obj())("structuredContent")("scripts").arr
    assert(scripts.exists(_("id").str == "svd"))
    assert(scripts.exists(_("id").str == "focal_crop"))
  }

  test("run_script xyz_grid produces a grid and the images") {
    val r = ok("run_script", ujson.Obj("script_id" -> "xyz_grid", "values" -> ujson.Obj("x_type" -> "Seed", "x_values" -> "1,2", "include_sub_images" -> true),
      "params" -> ujson.Obj("prompt" -> "grid", "steps" -> 1, "width" -> 64, "height" -> 64), "max_image_side" -> 64))
    val imgs = r("structuredContent")("images").arr
    assert(imgs.exists(_("grid").bool), imgs.toString)
    assert(imgs.count(i => !i("grid").bool) >= 2)
    assert(call("run_script", ujson.Obj("script_id" -> "nope"))("isError").bool)
    assert(call("run_script", ujson.Obj("script_id" -> "xyz_grid", "values" -> ujson.Obj("bogus" -> 1)))("isError").bool)
  }
