package sdfx.plugins

import sdfx.core.infotext.{ImageFacts, Infotext}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class CompileSuite extends munit.FunSuite:
  private val plugin = new CompilePlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("defaults are off, registered once, and need the engine feature"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    assertEquals(plugin.requiresEngineFeatures, Seq("compile"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)
    assert(plugin.process(GenerationParams(), enabled()).hooks.contains("compile"))

  test("Inductor writes backend and mode only"):
    val params = plugin.process(GenerationParams(), enabled("mode" -> "reduce-overhead"))
    assertEquals(params.hooks("compile")("backend").str, "inductor")
    assertEquals(params.extra, Map("Compile backend" -> "Inductor", "Compile mode" -> "reduce-overhead"))

  test("TensorRT writes block size and optimization level"):
    val params = plugin.process(GenerationParams(), enabled("backend" -> "TensorRT", "min_block_size" -> "8",
      "optimization_level" -> "4"))
    assertEquals(params.hooks("compile")("backend").str, "tensorrt")
    assertEquals(params.extra, Map("Compile backend" -> "TensorRT", "Compile min block size" -> "8",
      "Compile optimization level" -> "4"))

  test("PNG infotext round trip restores the controls"):
    for values <- Seq(enabled("backend" -> "TensorRT", "min_block_size" -> "8", "optimization_level" -> "4"),
        enabled("mode" -> "max-autotune-no-cudagraphs")) do
      val params = plugin.process(GenerationParams(), values)
      val fields = Infotext.parse(Infotext.write(params, ImageFacts(seed = 4))).fields
      val back = CompilePlugin.replay(fields).get
      assertEquals(back.str("enabled"), "true")
      assertEquals(back.str("backend"), values.str("backend", "Inductor"))
      if values.str("backend") == "TensorRT" then
        assertEquals(back.int("min_block_size"), 8)
        assertEquals(back.int("optimization_level"), 4)
      else assertEquals(back.str("mode"), "max-autotune-no-cudagraphs")

  test("replay switches off without the key and rejects bad values"):
    assertEquals(CompilePlugin.replay(Map("Steps" -> "20")).get.str("enabled"), "false")
    assertEquals(CompilePlugin.replay(Map("Compile backend" -> "ONNX")), None)
    assertEquals(CompilePlugin.replay(Map("Compile backend" -> "Inductor", "Compile mode" -> "fast")), None)
    assertEquals(CompilePlugin.replay(Map("Compile backend" -> "TensorRT", "Compile min block size" -> "0")), None)
    assertEquals(CompilePlugin.replay(Map("Compile backend" -> "TensorRT", "Compile optimization level" -> "6")), None)
