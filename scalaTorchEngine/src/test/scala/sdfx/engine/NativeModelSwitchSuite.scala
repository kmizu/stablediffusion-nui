package sdfx.engine

import java.nio.file.{Files,Path}
import java.util.concurrent.{CountDownLatch,TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.Await
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.protocol.{EngineError,EngineEvent}

class NativeModelSwitchSuite extends munit.FunSuite:
  override val munitTimeout: Duration=10.minutes
  test("NUI forwards the latest recorded OOM to the native CUDA estimator") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker fixture required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-vram-oom-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val estimator=new VramEstimatorService(services)
      val current=Await.result(estimator.profile(),30.seconds)
      def profile(name: String,rows: ujson.Arr)=ujson.Obj("version"->1,"status"->"stopped_after_error",
        "identity"->current("current_identity"),"fingerprint"->current("current_fingerprint"),"profile_path"->name,"rows"->rows)
      val old=profile("old",ujson.Arr(ujson.Obj("mode"->"txt2img","status"->"complete","pixels"->65536,
        "batch_size"->1,"reserved_delta_mb"->100)))
      val oom=profile("oom",ujson.Arr(ujson.Obj("mode"->"txt2img","status"->"oom","pixels"->262144,"batch_size"->1)))
      estimator.remember(old);estimator.remember(oom)
      val selected=VramEstimatorHistory.newestMeasured(estimator.history(),current("current_fingerprint").str).get
      assertEquals(selected("profile_path").str,"oom")
      val result=Await.result(estimator.estimate(selected,"txt2img",512,512,1),30.seconds)
      assertEquals(result("available").bool,false)
      assertEquals(result("reason").str,"measured_oom")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
  test("real SD1 CUDA benchmark completes and recovers after interruption") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_BENCHMARK_REAL_MODEL")
    val tokenizer=sys.env.get("SCALATORCH_TEST_TOKENIZER")
    assume(cp.nonEmpty && model.nonEmpty && tokenizer.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),
      "real SD1 model and CUDA required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-benchmark-real"))
    val tokenDir=Files.createDirectories(home.root.resolve("scala-torch/tokenizer"))
    for name<-Vector("vocab.json","merges.txt") do Files.copy(Path.of(tokenizer.get).resolve(name),tokenDir.resolve(name))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=None,scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val params=ujson.Obj("level"->"quick","steps"->"turbo","width"->256,"height"->256,"warmup"->false)
      val report=Await.result(services.client.systemBenchmark(params),8.minutes)
      assertEquals(report("status").str,"complete",report.render())
      assert(report("results")(0)("iterations_per_second").num>0)
      val entered=new CountDownLatch(1)
      val running=services.client.systemBenchmark(params,{ event => event match
        case EngineEvent.Log(_,message) if message.contains("System benchmark batch") => entered.countDown()
        case _ => ()
      })
      assert(entered.await(30,TimeUnit.SECONDS),"benchmark did not start")
      services.client.interrupt()
      val failure=intercept[EngineError](Await.result(running,3.minutes))
      assert(failure.isInterrupted,s"unexpected interruption result: $failure")
      val generated=services.generator.generate(GenerationParams(prompt="a red fox",width=64,height=64,steps=1,seed=42,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu")))
      assertEquals(generated.size,1)
      assert(Files.isRegularFile(generated.head.path))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
  test("NUI system benchmark measures native CUDA generation") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker fixture required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-system-benchmark-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      vaeTiling= !sys.env.get("SCALATORCH_TEST_UNTILED").contains("1"),lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val report=Await.result(services.client.systemBenchmark(ujson.Obj("level"->"quick","steps"->"turbo",
        "width"->256,"height"->256,"warmup"->false)),5.minutes)
      assertEquals(report("status").str,"complete",report.render())
      assertEquals(report("config")("batch_execution").str,"sequential")
      assertEquals(report("results").arr.size,1)
      assertEquals(report("results")(0)("batch_execution").str,"sequential")
      assert(report("results")(0)("iterations_per_second").num>0)
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
  test("NUI client receives native CUDA device memory") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker fixture required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-memory-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      val memory=Await.result(services.client.memory(),30.seconds)
      assert(memory.totalMb>0)
      assert(memory.deviceUsedMb.exists(_>=0))
      if sys.props("os.name").startsWith("Windows") then assert(memory.ramUsedMb>0)
      val info=Await.result(new SystemInfoService(services).full(hashes=true),30.seconds)
      assert(info("networks")("checkpoints").arr.nonEmpty)
      assert(info("memory")("gpu")("total_mb").num>0)
      assert(info("native")("java").str.nonEmpty)
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
  test("NUI discovers and switches two models in one Python-free worker") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val models=sys.env.get("SCALATORCH_SWITCH_MODELS")
    assume(cp.nonEmpty && models.nonEmpty,"worker classpath and two models required")
    val roots=models.get.split(java.io.File.pathSeparator).map(Path.of(_).toRealPath())
    assertEquals(roots.length,2)
    val home=AppHome(Files.createTempDirectory("native-switch-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(roots(0).toString),scalaTorchScheduler=Some(roots(0).resolve("euler-demo.json").toString),
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=roots.map(_.toString).toSeq))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      assertEquals(services.catalog.checkpoints.map(_.path).toSet,roots.map(_.toString).toSet)
      for root <- roots do
        services.loadCheckpoint(root.toString,remember=false)
        val images=services.generator.generate(GenerationParams(prompt="a cat",width=64,height=64,steps=1,seed=42,
          samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu")))
        assertEquals(images.size,1)
        assert(Files.isRegularFile(images.head.path))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }

  test("NUI switches real SD1 checkpoints and generates on CUDA") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val models=sys.env.get("SCALATORCH_REAL_SWITCH_MODELS")
    val tokenizer=sys.env.get("SCALATORCH_TEST_TOKENIZER")
    assume(cp.nonEmpty && models.nonEmpty && tokenizer.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),
      "real models, tokenizer and CUDA required")
    val roots=models.get.split(java.io.File.pathSeparator).map(Path.of(_).toRealPath())
    assertEquals(roots.length,2)
    val home=AppHome(Files.createTempDirectory("native-real-switch-nui"))
    val modelDir=Files.createDirectory(home.root.resolve("checkpoints"))
    val links=roots.zipWithIndex.map { (source,index) =>
      val target=modelDir.resolve(s"model-$index.safetensors")
      Files.createLink(target,source).toRealPath()
    }
    val tokenDir=Files.createDirectories(home.root.resolve("scala-torch/tokenizer"))
    for name<-Vector("vocab.json","merges.txt") do Files.copy(Path.of(tokenizer.get).resolve(name),tokenDir.resolve(name))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(links(0).toString),scalaTorchScheduler=None,
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(modelDir.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      assertEquals(services.catalog.checkpoints.map(_.path).toSet,links.map(_.toString).toSet)
      for root <- links do
        services.loadCheckpoint(root.toString,remember=false)
        assert(Await.result(services.client.memory(),30.seconds).deviceUsedMb.nonEmpty)
        val images=services.generator.generate(GenerationParams(prompt="a red fox in snow",width=64,height=64,steps=1,seed=42,
          samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu")))
        assertEquals(images.size,1)
        assert(Files.isRegularFile(images.head.path))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
