package sdfx.engine

import sdfx.core.config.Settings
import sdfx.core.protocol.*

import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration.*

class CharaSearcherServiceSuite extends munit.FunSuite:
  import FakeEngine.*

  private def withServices(body: (AppServices, java.nio.file.Path) => Unit): Unit =
    val home = tempHome()
    val services = new AppServices(home, Settings(lastModel = Some("foo")), dryRun = true, builtins = Nil,
      launchOverride = Some(() => launch()), persistSettings = false, installShutdownHook = false)
    try
      services.start()
      body(services, home.root)
    finally services.shutdown()

  test("import sends the dataset, sources and options to the engine and reports progress") {
    withServices { (services, root) =>
      val source = Files.createTempFile("chara-src", ".png")
      try
        val events = new java.util.concurrent.CopyOnWriteArrayList[EngineEvent]()
        val service = new CharaSearcherService(services)
        val handle = service.importSources(root.resolve("chara-searcher/set1"), Seq(source),
          service.ImportOptions(maskThreshold = 0.5, tagThreshold = 0.4, excludeTags = "solo"), e => events.add(e))
        val value = Await.result(handle.result, 30.seconds)
        assertEquals(value("created").arr.map(_.str).toList, List("a_0.png"))
        assert(events.toArray.exists { case _: EngineEvent.Progress => true; case _ => false })
        val sent = Await.result(services.client.raw("get_state"), 10.seconds)("last_extra")
        assertEquals(sent("type").str, "chara_searcher")
        val params = sent("params")
        assertEquals(params("action").str, "import")
        assertEquals(params("options")("mask_threshold").num, 0.5)
        assertEquals(params("options")("tag_threshold").num, 0.4)
        assertEquals(params("options")("exclude_tags").str, "solo")
        assertEquals(params("paths").arr.map(_.str).toList, List(source.toAbsolutePath.toString))
        assert(Files.isDirectory(root.resolve("chara-searcher/set1")))
      finally Files.deleteIfExists(source)
    }
  }

  test("embed returns the vector of the query image") {
    withServices { (services, _) =>
      val image = Files.createTempFile("chara-query", ".png")
      try
        val handle = new CharaSearcherService(services).embed(image)
        assertEquals(CharaSearcherService.embeddingOf(Await.result(handle.result, 30.seconds)).toList, List(0.0f, 1.0f, 0.5f))
      finally Files.deleteIfExists(image)
    }
  }

  test("invalid input is rejected before the engine is involved") {
    withServices { (services, root) =>
      val service = new CharaSearcherService(services)
      intercept[IllegalArgumentException](service.importSources(root.resolve("x"), Nil))
      intercept[IllegalArgumentException](service.importSources(root.resolve("x"), Seq(root.resolve("missing.png"))))
      intercept[IllegalArgumentException](service.embed(root.resolve("missing.png")))
    }
  }

  test("a queued request can be cancelled without breaking the engine") {
    val sup = new EngineSupervisor(() => launch())
    try
      Await.result(sup.startAsync(), 20.seconds)
      val client = new EngineClient(sup)
      val handle = client.charaSearcherOwned(ujson.Obj("action" -> "embed", "image_path" -> "x"))
      val decision = Await.result(handle.interrupt(), 10.seconds)
      assert(decision.accepted)
      Await.ready(handle.result, 20.seconds)
      assert(Await.result(sup.request("ping"), 10.seconds) != null)
    finally sup.close()
  }
