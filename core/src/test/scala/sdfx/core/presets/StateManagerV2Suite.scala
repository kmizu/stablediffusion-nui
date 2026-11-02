package sdfx.core.presets

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPOutputStream

class StateManagerV2Suite extends munit.FunSuite:
  private def gzip(s: String): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val stream = new GZIPOutputStream(out)
    stream.write(s.getBytes(UTF_8)); stream.close(); out.toByteArray

  private val fixture = """{"defaults":{"d1":{"txt2img/Prompt":"default prompt","txt2img/Sampling steps":20,"customscript/seed.py/txt2img/Seed":9007199254740993,"txt2img/Unsupported thing":"retain this"}},"entries":{"1700000000000":{"saveVersion":"2.0","createdAt":1700000000000,"name":"Cherry scene","type":"txt2img","defaults":"d1","groups":["history","favourites"],"quickSettings":{"CLIP_stop_at_last_layers":2,"future_option":"keep me"},"componentSettings":{"txt2img/Prompt":"chosen prompt"},"preview":null}}}"""
  private def field(s: String) = FieldKey(s.split('.').toVector)

  test("gzip storage merges saved defaults and overrides without losing large integer seeds or unknown fields") {
    val imported = StateManagerV2.decode(gzip(fixture)).toOption.get
    assertEquals(imported.size, 1)
    val state = imported.head
    assertEquals(state.mode, PresetMode.Txt2Img)
    assertEquals(state.sourceName, Some("Cherry scene"))
    assertEquals(state.groups, Set("history", "favourites"))
    assertEquals(state.mapped(field("generation.prompt")), PresetAtom.Text("chosen prompt"))
    assertEquals(state.mapped(field("generation.steps")), PresetAtom.Integer(20))
    assertEquals(state.mapped(field("generation.seed")), PresetAtom.Integer(9007199254740993L))
    assertEquals(state.mapped(field("model.clipSkip")), PresetAtom.Integer(2))
    assert(state.unmapped.contains("txt2img/Unsupported thing"))
    assert(state.unmapped.contains("quickSettings/future_option"))
    assert(state.originalEntry.text.contains("keep me"))
    assert(state.originalDefaults.text.contains("retain this"))
  }

  test("reject malformed, unsupported, missing-default and oversized inputs rather than silently dropping data") {
    assert(StateManagerV2.decode(Array[Byte](1, 2, 3)).isLeft)
    assert(StateManagerV2.decode(gzip(fixture).dropRight(5)).isLeft)
    assert(StateManagerV2.decode(gzip(fixture.replace("\"defaults\":\"d1\"", "\"defaults\":\"missing\""))).isLeft)
    assert(StateManagerV2.decode(gzip(fixture.replace("\"type\":\"txt2img\"", "\"type\":\"other\""))).isLeft)
    assert(StateManagerV2.decode(gzip("{" + "\"x\":0," * 200 + "\"x\":1}" )).isLeft)
    assert(StateManagerV2.decode(gzip(fixture), PresetLimits(maxBytes = 200)).isLeft)
  }

  test("reads a synthetic fixture made with the upstream Web Streams gzip API") {
    val in = getClass.getResourceAsStream("/fixtures/state-manager-v2-webstreams.gz")
    assert(in != null, "Web Streams fixture missing")
    val bytes = try in.readAllBytes() finally in.close()
    val state = StateManagerV2.decode(bytes).toOption.get.head
    assertEquals(state.mapped(field("generation.prompt")), PresetAtom.Text("web streams fixture"))
    assert(state.unmapped.contains("txt2img/Unknown"))
  }
