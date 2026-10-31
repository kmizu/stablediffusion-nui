package sdfx.engine

import sdfx.core.plugins.api.Values
import sdfx.core.model.{ControlNetUnit, GenerationParams}

class FastloadJobPlanSuite extends munit.FunSuite:
  test("job plan snapshots enabled settings and rejects unsupported modes") {
    val values = Values(Map("enabled" -> "true", "mode" -> "load_save",
      "priority" -> "current_first", "path" -> "example.cni", "save_format" -> "embedded"))
    val plan = FastloadJobPlan.fromValues(values).get
    assertEquals(plan.mode, "load_save")
    assertEquals(plan.priority, "current_first")
    assertEquals(plan.path, "example.cni")
    assertEquals(plan.saveFormat, "embedded")
    assertEquals(FastloadJobPlan.fromValues(Values.empty), None)
    intercept[IllegalArgumentException] {
      FastloadJobPlan.fromValues(values.updated("mode", "unknown"))
    }
  }

  test("private preparation keeps original units and rejects unsupported active units") {
    val raw = ujson.Obj("type" -> "list", "items" -> ujson.Arr())
    val p = GenerationParams(prompt = "test")
    val plan = FastloadJobPlan("load_save", "loaded_first", "saved.cni", "embedded")
    val calls = scala.collection.mutable.ArrayBuffer.empty[String]
    def worker(input: ujson.Obj): ujson.Value =
      val operation = input("operation").str
      calls += operation
      if operation == "from_native" then ujson.Obj("units" -> raw)
      else ujson.Obj("units" -> raw, "save" -> true, "load_skipped" -> false,
        "forms" -> ujson.Arr(ujson.Obj("unapplied" -> ujson.Arr("future_option"),
          "params" -> ujson.Obj("enabled" -> true))))
    intercept[IllegalArgumentException] {
      FastloadGeneration.prepare(p, plan, None, worker, _ => "unused")
    }
    assertEquals(calls.toSeq, Seq("prepare_generation"))
  }

  test("current priority retains the original generation params without staging") {
    val raw = ujson.Obj("type" -> "list", "items" -> ujson.Arr())
    val p = GenerationParams(prompt = "test")
    val plan = FastloadJobPlan("load_save", "current_first", "saved.cni", "embedded")
    def worker(input: ujson.Obj): ujson.Value =
      if input("operation").str == "from_native" then ujson.Obj("units" -> raw)
      else ujson.Obj("units" -> raw, "save" -> true, "load_skipped" -> true)
    val result = FastloadGeneration.prepare(p, plan, None, worker, _ => fail("unexpected staging"))
    assertEquals(result.params, p)
    assert(result.save)
    assertEquals(result.toString, "FastloadGeneration.Prepared([private])")
  }

  test("all active units validate before any image is staged") {
    val raw = ujson.Obj("type" -> "list", "items" -> ujson.Arr())
    val p = GenerationParams(prompt = "test")
    val plan = FastloadJobPlan("load_only", "loaded_first", "saved.cni", "embedded")
    val valid = ujson.Obj("params" -> ujson.Obj("enabled" -> true, "model" -> "m", "preprocessor" -> "none",
      "weight" -> 1, "guidance_start" -> 0, "guidance_end" -> 1, "preprocessor_args" -> ujson.Obj()),
      "image" -> "AQ==", "mask" -> ujson.Null, "unapplied" -> ujson.Arr())
    val invalid = ujson.Obj("params" -> ujson.Obj("enabled" -> true), "unapplied" -> ujson.Arr("future_option"))
    def worker(input: ujson.Obj): ujson.Value =
      if input("operation").str == "from_native" then ujson.Obj("units" -> raw)
      else ujson.Obj("units" -> raw, "save" -> false, "load_skipped" -> false, "forms" -> ujson.Arr(valid, invalid))
    var staged = false
    intercept[IllegalArgumentException] {
      FastloadGeneration.prepare(p, plan, None, worker, _ => { staged = true; "unused" })
    }
    assert(!staged)
  }

  test("loaded-first does not read an unused stale current input") {
    val raw = ujson.Obj("type" -> "list", "items" -> ujson.Arr())
    val p = GenerationParams(controlnet = Seq(ControlNetUnit(enabled = true, image = Some("missing-current-image.png"))))
    val plan = FastloadJobPlan("load_only", "loaded_first", "saved.cni", "embedded")
    def worker(input: ujson.Obj): ujson.Value =
      assertEquals(input("operation").str, "prepare_generation")
      ujson.Obj("units" -> raw, "save" -> false, "load_skipped" -> false, "forms" -> ujson.Arr())
    val result = FastloadGeneration.prepare(p, plan, None, worker, _ => fail("unexpected staging"))
    assertEquals(result.params.controlnet, Seq.empty)
  }

  test("protected legacy saving is rejected before worker or file access") {
    val session = new sdfx.core.png.ImageScrambleSession
    session.unlock("test-password")
    val p = GenerationParams(controlnet = Seq(ControlNetUnit(enabled = true, image = Some("missing.png"))))
    val plan = FastloadJobPlan("load_save", "loaded_first", "source.cni", "embedded")
    var called = false
    intercept[IllegalArgumentException] {
      FastloadGeneration.prepare(p, plan, session.snapshot, _ => { called = true; ujson.Null }, _ => "unused")
    }
    assert(!called)
  }

  test("disabled unsupported units remain in private saved data and do not affect sampling") {
    val raw = ujson.Obj("type" -> "list", "items" -> ujson.Arr("opaque-private-unit"))
    val plan = FastloadJobPlan("load_save", "loaded_first", "source.cni", "embedded")
    def worker(input: ujson.Obj): ujson.Value =
      ujson.Obj("units" -> raw, "save" -> true, "load_skipped" -> false,
        "forms" -> ujson.Arr(ujson.Obj("params" -> ujson.Obj("enabled" -> false))))
    val prepared = FastloadGeneration.prepare(GenerationParams(), plan, None, worker, _ => fail("unexpected staging"))
    assertEquals(prepared.units, raw)
    assertEquals(prepared.params.controlnet.map(_.enabled), Seq(false))
    assert(prepared.save)
  }
