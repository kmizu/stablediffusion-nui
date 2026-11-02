package sdfx.ui.vm

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success
import java.util.concurrent.CancellationException
import java.nio.file.Paths

/** Starts UI model-selection operations in admission order without blocking the FX thread. */
private[ui] final class ModelSelectionQueue(executor: ExecutionContext):
  private var tail: Future[Unit] = Future.successful(())
  private var closed = false
  private var uncertain = false

  def isUncertain: Boolean = synchronized { uncertain }
  def close(): Unit = synchronized { closed = true }
  // Linearization point: after this succeeds the operation is admitted/in-flight;
  // close does not block on or interrupt it. Tasks not yet admitted are cancelled.
  private def claimStart(): Boolean = synchronized { !closed }

  def submit[A](operation: => A): Future[A] = synchronized {
    val next = tail.transformWith(_ => Future {
      if !claimStart() then throw new CancellationException("model selection queue closed")
      operation
    }(using executor))(using ExecutionContext.parasitic)
    tail = next.transform { result =>
      synchronized { uncertain = result.isFailure }
      Success(())
    }(using ExecutionContext.parasitic)
    next
  }

/** Tracks user VAE choices independently of checkpoint selections. Access from the FX thread. */
private[ui] final class VaeSelectionRevision:
  private var revision = 0L
  def begin(): Long =
    revision += 1
    revision
  def current: Long = revision
  def unchanged(attempt: Long, original: String, selected: String): Boolean =
    attempt == revision && original == selected

private[ui] object ModelSelectionPolicy:
  def needsCheckpoint(target: String, loaded: Option[String], uncertain: Boolean = false): Boolean =
    def norm(path: String) = Paths.get(path).toAbsolutePath.normalize()
    uncertain || !loaded.exists(path => norm(path) == norm(target))
  def needsVae(target: Option[String], loadedName: Option[String], loadedPath: Option[String]): Boolean =
    target match
      case None => loadedName.nonEmpty || loadedPath.nonEmpty
      case Some(path) => needsCheckpoint(path, loadedPath) // unknown old-worker paths trigger a safe reload
  def canGenerate(pending: Boolean, disposed: Boolean): Boolean = !pending && !disposed
