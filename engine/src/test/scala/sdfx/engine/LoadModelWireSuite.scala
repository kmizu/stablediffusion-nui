package sdfx.engine

import sdfx.core.protocol.LoadModelRequest

final class LoadModelWireSuite extends munit.FunSuite:
  private val added = Set("vae_slicing", "fp8_storage", "vae_auto", "vae_fp16")

  test("default-valued keys added after protocol v1 are not sent, so older workers still load") {
    val js = LoadModelWire.json(LoadModelRequest("m.safetensors")).obj
    assertEquals(added.filter(js.contains), Set.empty[String])
    assertEquals(js("path").str, "m.safetensors")
    assert(js.contains("vae_tiling") && js.contains("vram_mode"))
  }

  test("a non-default value is still sent so an engine that cannot honour it says so") {
    val js = LoadModelWire.json(LoadModelRequest("m.safetensors", fp8Storage = true, vaeAuto = false,
      vaeFp16 = true, vaeSlicing = true)).obj
    assertEquals(added.filter(js.contains), added)
    assertEquals(js("vae_auto").bool, false)
    assertEquals(js("fp8_storage").bool, true)
  }
