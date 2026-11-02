package sdfx.ui

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import sdfx.ui.vm.{ModelSelectionQueue, VaeSelectionRevision, ModelSelectionPolicy}

class ModelSelectionOrderingSuite extends munit.FunSuite:
  private def forceLaterSelectionAhead(firstAction: () => Unit, secondAction: () => Unit): Unit =
    val queue = new ModelSelectionQueue(Fx.bg)
    def submit(task: () => Unit): Future[Unit] = queue.submit(task())
    val firstStarted = new CountDownLatch(1)
    val releaseFirst = new CountDownLatch(1)
    val secondRan = new CountDownLatch(1)
    val first = submit(() => {
      firstStarted.countDown()
      require(releaseFirst.await(5, TimeUnit.SECONDS), "first operation not released")
      firstAction()
    })
    assert(firstStarted.await(5, TimeUnit.SECONDS), "first operation did not start")
    val second = submit(() => { secondAction(); secondRan.countDown() })
    // On the current cached pool, the second can run while the first is delayed.
    // An ordered implementation may defer it until the first is released.
    secondRan.await(1, TimeUnit.SECONDS)
    releaseFirst.countDown()
    Await.result(first, 10.seconds); Await.result(second, 10.seconds)

  test("later checkpoint selection remains the final worker model") {
    val worker = new AtomicReference("A")
    forceLaterSelectionAhead(() => worker.set("B"), () => worker.set("C"))
    assertEquals(worker.get(), "C")
  }

  test("a later checkpoint wins over an earlier extended style load") {
    val worker = new AtomicReference("A")
    forceLaterSelectionAhead(() => worker.set("style-C"), () => worker.set("checkpoint-D"))
    assertEquals(worker.get(), "checkpoint-D")
  }

  test("a later VAE selection wins over an earlier style VAE") {
    val workerVae = new AtomicReference("old")
    forceLaterSelectionAhead(() => workerVae.set("style-VAE"), () => workerVae.set("chosen-VAE"))
    assertEquals(workerVae.get(), "chosen-VAE")
  }

  test("a failed selection does not prevent the next operation") {
    val queue = new ModelSelectionQueue(Fx.bg)
    val failed = queue.submit(throw new IllegalStateException("load failed"))
    val later = queue.submit("next")
    intercept[IllegalStateException](Await.result(failed, 5.seconds))
    assertEquals(Await.result(later, 5.seconds), "next")
  }

  test("late style VAE display update cannot overwrite a newer VAE selection") {
    val revisions = new VaeSelectionRevision
    revisions.begin() // style's own planned VAE change
    val style = revisions.current
    assert(revisions.unchanged(style, "A", "A"))
    revisions.begin() // newer VAE request, even if the selected value is unchanged
    assert(!revisions.unchanged(style, "A", "A"))
    assert(!revisions.unchanged(style, "A", "B"))
  }

  test("a style rechecks the worker after an earlier selection fails") {
    val queue = new ModelSelectionQueue(Fx.bg)
    val worker = new AtomicReference("A")
    val firstStarted = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val failed = queue.submit { firstStarted.countDown(); require(release.await(5, TimeUnit.SECONDS)); throw new IllegalStateException("B failed") }
    assert(firstStarted.await(5, TimeUnit.SECONDS))
    val style = queue.submit { if ModelSelectionPolicy.needsCheckpoint("B", Some(worker.get())) then worker.set("B") }
    release.countDown()
    intercept[IllegalStateException](Await.result(failed, 5.seconds))
    Await.result(style, 5.seconds)
    assertEquals(worker.get(), "B")
  }

  test("generation is not admitted while model selections are pending") {
    assert(!ModelSelectionPolicy.canGenerate(pending = true, disposed = false))
    assert(!ModelSelectionPolicy.canGenerate(pending = false, disposed = true))
    assert(ModelSelectionPolicy.canGenerate(pending = false, disposed = false))
  }

  test("closing does not block on active work and prevents queued work from starting") {
    val queue = new ModelSelectionQueue(Fx.bg)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val ran = new java.util.concurrent.atomic.AtomicBoolean(false)
    val first = queue.submit { entered.countDown(); require(release.await(5, TimeUnit.SECONDS)) }
    assert(entered.await(5, TimeUnit.SECONDS))
    val second = queue.submit { ran.set(true) }
    queue.close()
    release.countDown()
    Await.result(first, 5.seconds)
    intercept[java.util.concurrent.CancellationException](Await.result(second, 5.seconds))
    assert(!ran.get())
    intercept[java.util.concurrent.CancellationException](Await.result(queue.submit(()), 5.seconds))
  }

  test("VAE identity uses its full path and an older worker is treated as unknown") {
    val a = "C:/models/one/same.safetensors"
    val b = "C:/models/two/same.safetensors"
    assert(!ModelSelectionPolicy.needsVae(Some(a), Some("same"), Some(a)))
    assert(ModelSelectionPolicy.needsVae(Some(a), Some("same"), Some(b)))
    assert(ModelSelectionPolicy.needsVae(Some(a), Some("same"), None))
    assert(!ModelSelectionPolicy.needsVae(None, None, None))
    assert(ModelSelectionPolicy.needsVae(None, Some("same"), None))
  }

  test("a task not admitted before close never begins even if already scheduled") {
    val scheduled = new java.util.concurrent.LinkedBlockingQueue[Runnable]()
    val executor = new scala.concurrent.ExecutionContext {
      def execute(task: Runnable): Unit = scheduled.add(task)
      def reportFailure(t: Throwable): Unit = throw t
    }
    val queue = new ModelSelectionQueue(executor)
    val ran = new java.util.concurrent.atomic.AtomicBoolean(false)
    val result = queue.submit { ran.set(true) }
    val task = scheduled.poll(5, TimeUnit.SECONDS)
    assert(task != null)
    queue.close()
    task.run()
    intercept[java.util.concurrent.CancellationException](Await.result(result, 5.seconds))
    assert(!ran.get())
  }

  test("a failed previous selection forces style to reload even when cached checkpoint name matches") {
    val queue = new ModelSelectionQueue(Fx.bg)
    val worker = new AtomicReference[String](null) // a failed load freed the real worker model
    val cached = Some("A") // supervisor bookkeeping can still describe the old model
    val failed = queue.submit(throw new IllegalStateException("load failed after free"))
    intercept[IllegalStateException](Await.result(failed, 5.seconds))
    val style = queue.submit {
      if ModelSelectionPolicy.needsCheckpoint("A", cached, queue.isUncertain) then worker.set("A")
    }
    Await.result(style, 5.seconds)
    assertEquals(worker.get(), "A")
  }
