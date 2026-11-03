package sdfx.core.panorama

import java.util.concurrent.{Executors, ThreadFactory}
import scala.util.control.NonFatal

/** One render at a time and at most one waiting frame; new frames replace the waiting one. */
final class LatestFrame[A](render: A => Unit, onError: Throwable => Unit = _ => ()) extends AutoCloseable:
  private val executor = Executors.newSingleThreadExecutor(new ThreadFactory:
    def newThread(r: Runnable): Thread =
      val t = new Thread(r, "panorama-video-frame")
      t.setDaemon(true)
      t
  )
  private var pending: Option[A] = None
  private var running = false
  private var closed = false

  def submit(frame: A): Unit = synchronized {
    if !closed then
      pending = Some(frame)
      if !running then
        running = true
        executor.execute(() => drain())
  }

  private def drain(): Unit =
    var continue = true
    while continue do
      val next = synchronized {
        val value = if closed then None else pending
        pending = None
        if value.isEmpty then running = false
        value
      }
      next match
        case Some(frame) =>
          try render(frame)
          catch case NonFatal(e) =>
            try onError(e)
            catch case NonFatal(_) => ()
        case None => continue = false

  override def close(): Unit = synchronized {
    closed = true
    pending = None
    executor.shutdownNow()
  }
