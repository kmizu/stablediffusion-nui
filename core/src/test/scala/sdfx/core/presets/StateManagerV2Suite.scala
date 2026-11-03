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

  test("imports img2img denoising strength without confusing txt2img hires settings") {
    def sample(mode: String, default: String, changed: String) = gzip(s"""{"defaults":{"d":{"$mode/Denoising strength":$default}},"entries":{"1700000000000":{"saveVersion":"2.0","type":"$mode","defaults":"d","groups":["history"],"componentSettings":{"$mode/Denoising strength":$changed},"quickSettings":{}}}}""")
    val img = StateManagerV2.decode(sample("img2img", "0.75", "0.35")).toOption.get.head
    assertEquals(img.mapped(field("generation.denoisingStrength")), PresetAtom.Decimal(BigDecimal("0.35")))
    assert(!img.unmapped.contains("img2img/Denoising strength"))
    val wrong = StateManagerV2.decode(sample("img2img", "0.75", "\"invalid\"")).toOption.get.head
    assert(!wrong.mapped.contains(field("generation.denoisingStrength")))
    assert(wrong.unmapped.contains("img2img/Denoising strength"))
    val txt = StateManagerV2.decode(sample("txt2img", "0.75", "0.35")).toOption.get.head
    assert(!txt.mapped.contains(field("generation.denoisingStrength")))
    assert(txt.unmapped.contains("txt2img/Denoising strength"))
  }

  test("reads a synthetic fixture made with the upstream Web Streams gzip API") {
    val in = getClass.getResourceAsStream("/fixtures/state-manager-v2-webstreams.gz")
    assert(in != null, "Web Streams fixture missing")
    val bytes = try in.readAllBytes() finally in.close()
    val state = StateManagerV2.decode(bytes).toOption.get.head
    assertEquals(state.mapped(field("generation.prompt")), PresetAtom.Text("web streams fixture"))
    assert(state.unmapped.contains("txt2img/Unknown"))
  }

  test("reads data produced by pinned upstream JavaScript with simulated A1111 dependencies") {
    val in = getClass.getResourceAsStream("/fixtures/state-manager-v2-upstream-stubbed.gz")
    assert(in != null, "upstream-produced fixture missing")
    val bytes = try in.readAllBytes() finally in.close()
    val entry = StateManagerV2.decode(bytes).toOption.get.head
    assertEquals(entry.sourceId, "1700000000123")
    assertEquals(entry.groups, Set("history"))
    assertEquals(entry.mapped(field("generation.prompt")), PresetAtom.Text("actual upstream saved prompt"))
    assertEquals(entry.mapped(field("generation.negativePrompt")), PresetAtom.Text("blur"))
    assertEquals(entry.mapped(field("generation.steps")), PresetAtom.Integer(20))
    assertEquals(entry.mapped(field("generation.seed")), PresetAtom.Integer(123))
    assertEquals(entry.mapped(field("model.clipSkip")), PresetAtom.Integer(2))
    assert(entry.unmapped.contains("txt2img/Unknown extension"))
  }

  test("original State Manager export preserves validated entry and defaults exactly") {
    val in = getClass.getResourceAsStream("/fixtures/state-manager-v2-upstream-stubbed.gz")
    val sourceBytes = try in.readAllBytes() finally in.close()
    val entry = StateManagerV2.decode(sourceBytes).toOption.get.head
    val original = PresetJson.readSource(s"{\"entry\":${entry.originalEntry.text},\"defaults\":${entry.originalDefaults.text}}".getBytes(UTF_8), PresetLimits()).toOption.get
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(
      (entry.mode.toString + "\u0000" + entry.sourceId + "\u0000" + entry.originalEntry.text + "\u0000" + entry.originalDefaults.text).getBytes(UTF_8))
    val buffer = java.nio.ByteBuffer.wrap(digest)
    val record = PresetRecord(new java.util.UUID(buffer.getLong(), buffer.getLong()), "renamed in NUI", entry.mapped,
      Some(PresetSource("state-manager-v2", "source", Some(entry.mode), original)), Set.empty, Vector.empty, Vector.empty)
    val exported = StateManagerV2.exportOriginal(Vector(record)).toOption.get
    val recovered = StateManagerV2.decode(exported).toOption.get.head
    assertEquals(recovered.sourceId, entry.sourceId)
    assertEquals(recovered.originalEntry.text, entry.originalEntry.text)
    assertEquals(recovered.originalDefaults.text, entry.originalDefaults.text)
    assert(StateManagerV2.exportOriginal(Vector(record.copy(source = None))).isLeft)
    assert(StateManagerV2.exportOriginal(Vector(record.copy(id = java.util.UUID.randomUUID()))).isLeft)
    assert(StateManagerV2.exportOriginal(Vector(record, record)).isLeft)
    val changedEntry = entry.originalEntry.text.replace("1700000000123", "1700000000124")
    val changedDefaults = entry.originalDefaults.text.replace("upstream default", "different default")
    val changedOriginal = PresetJson.readSource(s"{\"entry\":$changedEntry,\"defaults\":$changedDefaults}".getBytes(UTF_8), PresetLimits()).toOption.get
    val changedHash = java.security.MessageDigest.getInstance("SHA-256").digest(
      (entry.mode.toString + "\u0000" + "1700000000124" + "\u0000" + changedEntry + "\u0000" + changedDefaults).getBytes(UTF_8))
    val changedBuffer = java.nio.ByteBuffer.wrap(changedHash)
    val changed = record.copy(id = new java.util.UUID(changedBuffer.getLong(), changedBuffer.getLong()),
      source = Some(PresetSource("state-manager-v2", "source", Some(entry.mode), changedOriginal)))
    assert(StateManagerV2.exportOriginal(Vector(record, changed)).isLeft, "conflicting defaults must not be merged")
    val withoutTime = entry.originalEntry.text.replace(",\"createdAt\":1700000000123", "")
    val missingTime = PresetJson.readSource(s"{\"entry\":$withoutTime,\"defaults\":${entry.originalDefaults.text}}".getBytes(UTF_8), PresetLimits()).toOption.get
    assert(StateManagerV2.exportOriginal(Vector(record.copy(source = Some(PresetSource("state-manager-v2", "source", Some(entry.mode), missingTime))))).isLeft)
    val dir = java.nio.file.Files.createTempDirectory("sdfx-state-export-")
    val occupied = dir.resolve("occupied.gz")
    val sentinel = Array[Byte](4, 3, 2, 1)
    java.nio.file.Files.write(occupied, sentinel)
    assert(StateManagerV2.exportOriginalToNewFile(Vector(record), occupied).isLeft)
    assertEquals(java.nio.file.Files.readAllBytes(occupied).toVector, sentinel.toVector)
    val link = dir.resolve("existing-link.gz")
    val symlinkCreated = try { java.nio.file.Files.createSymbolicLink(link, occupied); true }
      catch case _: UnsupportedOperationException | _: java.io.IOException | _: SecurityException => false
    if symlinkCreated then
      assert(StateManagerV2.exportOriginalToNewFile(Vector(record), link).isLeft)
      assert(java.nio.file.Files.isSymbolicLink(link))
      assertEquals(java.nio.file.Files.readAllBytes(occupied).toVector, sentinel.toVector)
    val fresh = dir.resolve("new.gz")
    assert(StateManagerV2.exportOriginalToNewFile(Vector(record), fresh).isRight)
    assert(StateManagerV2.decode(java.nio.file.Files.readAllBytes(fresh)).isRight)
    val collision = dir.resolve("race.gz")
    val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
    val start = new java.util.concurrent.CountDownLatch(1)
    val jobs = Vector.fill(2)(pool.submit(new java.util.concurrent.Callable[Either[PresetError, Unit]]:
      def call(): Either[PresetError, Unit] = { start.await(); StateManagerV2.exportOriginalToNewFile(Vector(record), collision) }))
    start.countDown()
    val outcomes = jobs.map(_.get(20, java.util.concurrent.TimeUnit.SECONDS))
    pool.shutdown()
    assertEquals(outcomes.count(_.isRight), 1)
    assertEquals(outcomes.count(_.isLeft), 1)
    assert(StateManagerV2.decode(java.nio.file.Files.readAllBytes(collision)).isRight)
  }
