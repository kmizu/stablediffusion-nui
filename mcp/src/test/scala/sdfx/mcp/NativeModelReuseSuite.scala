package sdfx.mcp

import java.nio.file.{Files,Path}
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.engine.AppServices

class NativeModelReuseSuite extends munit.FunSuite:
  test("MCP same-model selection retains the native model token") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"native CUDA fixture required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("mcp-native-model-reuse"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      scalaTorchUnetDevice="cuda",lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      val loaded=services.loadCheckpoint(root.toString,remember=false)
      assert(loaded.modelToken.nonEmpty)
      val server=new McpServer(new McpTools(services).all)
      val reply=server.handle(TestEnv.callReq("set_model",ujson.Obj("name"->root.toString)),_=>()).get("result")
      assert(!reply("isError").bool,reply.render())
      assertEquals(services.supervisor.loadedModel.flatMap(_.modelToken),loaded.modelToken)
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
