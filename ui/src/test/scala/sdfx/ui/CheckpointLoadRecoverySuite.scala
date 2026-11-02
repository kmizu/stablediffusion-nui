package sdfx.ui

import sdfx.core.protocol.{EngineError, Protocol}
import sdfx.ui.vm.CheckpointLoadRecovery

class CheckpointLoadRecoverySuite extends munit.FunSuite:
  private def refusal = EngineError(Protocol.ErrorCodes.Unsupported,
    "fp8_storage needs the 'gpu' VRAM mode; auto cannot confirm it with the current free VRAM. Unload the current model and retry auto, or explicitly request gpu mode.").markWorkerResponse()

  test("a VAE operation does not supersede the pending checkpoint refusal") {
    val gate = new CheckpointLoadRecovery
    val attempt = gate.beginCheckpoint()
    // A VAE request is not a checkpoint selection and does not advance this gate.
    assert(gate.shouldRestore(attempt, "B", "B", refusal))
  }

  test("an older failure does not overwrite a newer checkpoint selection") {
    val gate = new CheckpointLoadRecovery
    val old = gate.beginCheckpoint()
    gate.beginCheckpoint()
    assert(!gate.shouldRestore(old, "B", "B", refusal))
    val latest = gate.beginCheckpoint()
    assert(!gate.shouldRestore(latest, "B", "C", refusal))
  }

  test("ordinary or unconfirmed failures do not claim the old worker model survived") {
    val gate = new CheckpointLoadRecovery
    val attempt = gate.beginCheckpoint()
    assert(!gate.shouldRestore(attempt, "B", "B", EngineError("load_failed", "failed after freeing the model").markWorkerResponse()))
    assert(!gate.shouldRestore(attempt, "B", "B", EngineError(Protocol.ErrorCodes.Unsupported,
      "fp8_storage needs the 'gpu' VRAM mode; auto cannot confirm it with the current free VRAM.")))
    assert(!gate.shouldRestore(attempt, "B", "B", new RuntimeException("failed")))
  }

  test("an extended style checkpoint load invalidates an older rejected selection") {
    val gate = new CheckpointLoadRecovery
    val old = gate.beginCheckpoint()
    assert(gate.beginStyleCheckpoint("B", "C"))
    assert(!gate.shouldRestore(old, "B", "B", refusal))
  }

  test("a style without a checkpoint leaves the pending selection current") {
    val gate = new CheckpointLoadRecovery
    val old = gate.beginCheckpoint()
    assert(!gate.beginStyleCheckpoint("B", ""))
    assert(gate.shouldRestore(old, "B", "B", refusal))
  }

  test("a style explicitly selecting the same label is still a newer selection") {
    val gate = new CheckpointLoadRecovery
    val old = gate.beginCheckpoint()
    assert(!gate.beginStyleCheckpoint("B", "B"))
    assert(!gate.shouldRestore(old, "B", "B", refusal))
  }

  test("a stale extended-style failure cannot overwrite a newer checkpoint") {
    val gate = new CheckpointLoadRecovery
    assert(gate.beginStyleCheckpoint("A", "C"))
    val style = gate.currentEpoch
    gate.beginCheckpoint()
    assert(!gate.canRestoreStyle(style, "A", "D"))
    assert(!gate.canRestoreStyle(style, "A", "A"))
  }

  test("style failure restores only while its original selection is unchanged") {
    val gate = new CheckpointLoadRecovery
    assert(gate.beginStyleCheckpoint("A", "C"))
    val style = gate.currentEpoch
    assert(gate.canRestoreStyle(style, "A", "A"))
    assert(!gate.canRestoreStyle(style, "A", "D"))
  }

  test("an older successful style cannot apply its checkpoint over a newer selection") {
    val gate = new CheckpointLoadRecovery
    assert(gate.beginStyleCheckpoint("A", "C"))
    val style = gate.currentEpoch
    gate.beginCheckpoint()
    assert(!gate.canApplyStyle(style, "A", "D"))
    assert(!gate.canApplyStyle(style, "A", "A"))
  }

  test("a current successful style may apply its checkpoint") {
    val gate = new CheckpointLoadRecovery
    assert(gate.beginStyleCheckpoint("A", "C"))
    assert(gate.canApplyStyle(gate.currentEpoch, "A", "A"))
  }
