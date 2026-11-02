package sdfx.ui.vm

import sdfx.core.protocol.{EngineError, Protocol}

/** Tracks checkpoint selections separately from unrelated VAE and style loads. */
private[ui] final class CheckpointLoadRecovery:
  private var epoch = 0L

  def beginCheckpoint(): Long =
    epoch += 1
    epoch

  /** Reserve a newer checkpoint epoch only when an extended style will load another checkpoint. */
  def beginStyleCheckpoint(current: String, target: String): Boolean =
    val changesCheckpoint = target.nonEmpty && current != target
    if changesCheckpoint then beginCheckpoint()
    changesCheckpoint

  def currentEpoch: Long = epoch

  /** A late style failure must not overwrite a newer checkpoint selection. */
  def canRestoreStyle(attempt: Long, originalSelection: String, selected: String): Boolean =
    attempt == epoch && originalSelection == selected

  /** A late style success is subject to the same selection ordering as a failure. */
  def canApplyStyle(attempt: Long, originalSelection: String, selected: String): Boolean =
    canRestoreStyle(attempt, originalSelection, selected)

  /** Only this confirmed worker refusal is known to occur before the old model is freed. */
  def shouldRestore(attempt: Long, requested: String, selected: String, failure: Throwable): Boolean =
    attempt == epoch && requested == selected && (failure match
      case err: EngineError => err.workerResponseConfirmed && err.code == Protocol.ErrorCodes.Unsupported &&
        err.message.startsWith("fp8_storage needs the 'gpu' VRAM mode; auto cannot confirm")
      case _ => false)
