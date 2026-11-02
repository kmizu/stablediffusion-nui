package sdfx.mcp

import java.nio.file.Files
import sdfx.core.plugins.api.*

class AlwaysOnFilePolicySuite extends munit.FunSuite:
  private val plugin = new Plugin:
    def id = "file-fixture"
    def name = "File fixture"
    def kind = PluginKind.AlwaysOn
    override def controls = Seq(Control.Text("head", "Head file"), Control.Text("image_encoder", "Encoder"))

  test("aesthetic embedding names and paths all go through the local file policy") {
    val aesthetic = new sdfx.plugins.AestheticGradientsPlugin
    var checked = Vector.empty[String]
    val input = new AlwaysOnInputs(() => Seq(aesthetic), _ => Nil, (_, _) => "image",
      (v, _) => { checked :+= v; if v == "aesthetic_embeddings/sunset.pt" then "resolved-file" else throw ToolError("outside roots") })
    assertEquals(input.validate(aesthetic, ujson.Obj("embedding" -> "sunset")).str("embedding"), "resolved-file")
    assertEquals(input.validate(aesthetic, ujson.Obj("embedding" -> "sunset.pt")).str("embedding"), "resolved-file")
    intercept[ToolError] { input.validate(aesthetic, ujson.Obj("embedding" -> "../../secret.pt")) }
    intercept[ToolError] { input.validate(aesthetic, ujson.Obj("embedding" -> "\\\\server\\share\\secret.pt")) }
    assertEquals(checked, Vector("aesthetic_embeddings/sunset.pt", "aesthetic_embeddings/sunset.pt", "../../secret.pt", "\\\\server\\share\\secret.pt"))
  }

  test("relative model file paths cannot bypass the file policy; HF encoder identifiers remain identifiers") {
    var checked = Vector.empty[String]
    val input = new AlwaysOnInputs(() => Seq(plugin), _ => Nil, (_, _) => "image",
      (value, _) => { checked :+= value; throw ToolError("outside roots") })
    intercept[ToolError] { input.parse(ujson.Obj("file-fixture" -> ujson.Obj("head" -> "secret.pth")), Tab.Txt2Img) }
    intercept[ToolError] { input.parse(ujson.Obj("file-fixture" -> ujson.Obj("head" -> "../../private.pth")), Tab.Txt2Img) }
    assertEquals(checked, Vector("secret.pth", "../../private.pth"))
    val hf = input.parse(ujson.Obj("file-fixture" -> ujson.Obj("image_encoder" -> "h94/IP-Adapter")), Tab.Txt2Img)
    assertEquals(hf.head._2.str("image_encoder"), "hf://h94/IP-Adapter")
    assertEquals(checked.size, 2)
  }

  test("allowed model directories remain bounded by real-path policy") {
    val root = Files.createTempDirectory("mcp-allowed-")
    val nested = Files.createDirectory(root.resolve("encoder"))
    val outside = Files.createTempDirectory("mcp-outside-")
    val policy = new PathPolicy(() => Seq(root))
    try
      assertEquals(policy.resolveDirectory(nested.toString, "encoder", root), nested.toRealPath())
      intercept[ToolError] { policy.resolveDirectory(outside.toString, "encoder", root) }
      intercept[ToolError] { policy.resolveDirectory("\\\\server\\share", "encoder", root) }
    finally
      Files.deleteIfExists(nested); Files.deleteIfExists(root); Files.deleteIfExists(outside)
  }

  test("paths returned as real paths remain usable when an allowed root has a filesystem alias") {
    val root = Files.createTempDirectory("mcp-root-alias-")
    val file = Files.writeString(root.resolve("fixture.pt"), "fixture")
    try
      val policy = new PathPolicy(() => Seq(root))
      assertEquals(policy.resolve(file.toRealPath().toString, "embedding", root), file.toRealPath())
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(root)
  }

  test("multiple image values keep actual newline separators") {
    val multi = new Plugin:
      def id = "multi"
      def name = "Multiple images"
      def kind = PluginKind.AlwaysOn
      override def controls = Seq(Control.ImageFile("images", "References", multiple = true))
    val input = new AlwaysOnInputs(() => Seq(multi), _ => Nil, (v, _) => "ok:" + v, (v, _) => v)
    assertEquals(input.validate(multi, ujson.Obj("images" -> "one\ntwo")).str("images"), "ok:one\nok:two")
  }

  test("Fastload ignores an unused source before applying file policy") {
    val fastload = new Plugin:
      def id = "controlnet_fastload"
      def name = "Fastload"
      def kind = PluginKind.AlwaysOn
      override def controls = Seq(Control.Text("path", "Source"),
        Control.Choice("mode", "Mode", Seq("load_only", "save_only", "load_save"), "load_save"),
        Control.Choice("priority", "Priority", Seq("current_first", "loaded_first"), "current_first"))
    val input = new AlwaysOnInputs(() => Seq(fastload), _ => Nil, (_, _) => fail("must not read"),
      (_, _) => throw ToolError("not allowed"), activeControlNet = true)
    assertEquals(input.validate(fastload, ujson.Obj("mode" -> "save_only", "path" -> "missing.cni")).str("path"), "")
    assertEquals(input.validate(fastload, ujson.Obj("priority" -> "current_first", "path" -> "missing.cni")).str("path"), "")
    intercept[ToolError] { input.validate(fastload, ujson.Obj("priority" -> "loaded_first", "path" -> "missing.cni")) }
  }
