package sdfx.plugins

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ArrayBuffer
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values

class AestheticScoreSuite extends munit.FunSuite:
  private val plugin = new AestheticScorePlugin
  private val extras = new AestheticScoreExtrasPlugin
  private def enabled(extra: (String, String)*) =
    Values(Values.defaults(plugin.controls).map ++ Map("enabled" -> "true") ++ extra)

  test("defaults score with the improved predictor only and record the settings"):
    assert(!plugin.isActive(Values.defaults(plugin.controls)))
    val params = plugin.process(GenerationParams(), enabled())
    val hook = params.hooks(plugin.id)
    assertEquals(hook("improved").bool, true)
    assertEquals(hook("simulacra").str, "off")
    assertEquals(hook("png_chunks").bool, true)
    assertEquals(hook("hash").bool, false)
    assert(!hook.obj.contains("enabled"))
    assertEquals(params.extra.keySet, Set(AestheticScorePlugin.SettingsKey))
    assertEquals(plugin.requiresEngineFeatures, Seq("aesthetic_score"))
    assertEquals(StandardPlugins.alwaysOn.count(_.id == plugin.id), 1)
    assertEquals(StandardPlugins.extras.count(_.id == extras.id), 1)
    assertEquals(plugin.controls.map(_.key).distinct.size, plugin.controls.size)
    assertEquals(extras.controls.map(_.key).distinct.size, extras.controls.size)

  test("every control reaches the hook and the settings round-trip through the infotext"):
    val values = Seq("improved" -> "false", "simulacra" -> "ViT-B/16", "cafe_aesthetic" -> "true", "cafe_style" -> "true",
      "cafe_waifu" -> "true", "png_chunks" -> "false", "generation_fields" -> "true", "hash" -> "true", "force_cpu" -> "true")
    val params = plugin.process(GenerationParams(), enabled(values*))
    val hook = params.hooks(plugin.id)
    assertEquals(hook("simulacra").str, "vit-b-16")
    assertEquals(hook("improved").bool, false)
    assertEquals(hook("force_cpu").bool, true)
    val restored = AestheticScorePlugin.replay(params.extra).get
    assertEquals(restored.map("simulacra"), "ViT-B/16")
    for (k, v) <- values do assertEquals(restored.map(k), v, k)
    assertEquals(restored.map("enabled"), "true")
    // the default settings restore too
    val defaults = AestheticScorePlugin.replay(plugin.process(GenerationParams(), enabled()).extra).get
    assertEquals(defaults.map("improved"), "true")
    assertEquals(defaults.map("simulacra"), "Off")

  test("an invalid simulacra choice is rejected"):
    intercept[IllegalArgumentException](plugin.process(GenerationParams(), enabled("simulacra" -> "ViT-B/32")))

  test("PNGs from the original extensions enable the scorers whose score is present"):
    val fromTsngo = AestheticScorePlugin.replay(Map("aesthetic_score" -> "5.9", "Steps" -> "20")).get
    assertEquals(fromTsngo.map("enabled"), "true")
    assertEquals(fromTsngo.map("improved"), "true")
    assertEquals(fromTsngo.map("simulacra"), "Off")
    val fromResults = AestheticScorePlugin.replay(Map("Aesthetic Scorer ViT-B/16" -> "5.12", "Cafe waifu" -> "waifu 0.91")).get
    assertEquals(fromResults.map("improved"), "false")
    assertEquals(fromResults.map("simulacra"), "ViT-B/16")
    assertEquals(fromResults.map("cafe_waifu"), "true")
    assertEquals(fromResults.map("cafe_style"), "false")
    // no key at all: the plugin is switched off; `hash` alone is not a score
    assertEquals(AestheticScorePlugin.replay(Map("Steps" -> "20", "hash" -> "abc")).get.map, Map("enabled" -> "false"))

  test("malformed settings restore nothing"):
    for bad <- Seq("{", "[]", """{"improved":"yes"}""", """{"simulacra":"ViT-B/32"}""", """{"unknown":true}""", """{"simulacra":true}""") do
      assertEquals(AestheticScorePlugin.replay(Map(AestheticScorePlugin.SettingsKey -> bad)), None, bad)
    assertEquals(AestheticScorePlugin.replay(Map(AestheticScorePlugin.SettingsKey -> ("{" + "\"improved\":true," * 400 + "}"))), None)

  private def toolContext(info: ujson.Obj, files: ArrayBuffer[Path], calls: ArrayBuffer[ujson.Obj]): FakeContext =
    new FakeContext():
      override def engineCall(kind: String, params: ujson.Obj): ujson.Value =
        assertEquals((kind, params("tool").str), ("image_tool", "aesthetic_score"))
        calls += params
        val main = Paths.get(initImage(10, 10))
        files ++= Seq(Paths.get(params("image_path").str), main)
        ujson.Obj("path" -> main.toString, "outputs" -> ujson.Obj(), "info" -> info)

  test("Extras returns the same image with the scores in the infotext and removes temporary files"):
    val files = ArrayBuffer[Path](); val calls = ArrayBuffer[ujson.Obj]()
    val image = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val info = ujson.Obj("aesthetic_score" -> "5.9", "Cafe aesthetic" -> "aesthetic 0.9731", "ignored" -> "x")
    val out = extras.transform(image, GenerationParams(), Values.defaults(extras.controls), toolContext(info, files, calls))
    assertEquals(out.size, 1)
    assert(out.head._1 eq image)
    assertEquals(out.head._2, Seq("aesthetic_score" -> "5.9", "Cafe aesthetic" -> "aesthetic 0.9731"))
    val call = calls.head
    assertEquals(call("improved").bool, true)
    assertEquals(call("cafe_aesthetic").bool, true)
    assertEquals(call("cafe_style").bool, false)
    assertEquals(call("simulacra").str, "off")
    assert(files.forall(f => !Files.exists(f)), s"leaked: ${files.filter(Files.exists(_))}")

  test("Extras needs at least one scorer and a known simulacra choice"):
    val base = Values.defaults(extras.controls).map
    intercept[IllegalArgumentException](extras.toolParams(Values(base ++ Map("improved" -> "false", "cafe_aesthetic" -> "false"))))
    intercept[IllegalArgumentException](extras.toolParams(Values(base ++ Map("simulacra" -> "ViT-L/16"))))
    assertEquals(extras.toolParams(Values(base ++ Map("improved" -> "false", "cafe_aesthetic" -> "false", "simulacra" -> "ViT-L/14"))).toMap.apply("simulacra").str, "vit-l-14")
