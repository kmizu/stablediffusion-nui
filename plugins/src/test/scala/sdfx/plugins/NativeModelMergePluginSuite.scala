package sdfx.plugins

import java.nio.{ByteBuffer,ByteOrder}
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*
import scala.concurrent.Await
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.protocol.ModelEntry
import sdfx.core.plugins.api.{ChoiceSource,Values}
import sdfx.engine.{AppServices,EngineLaunch,Job}

class NativeModelMergePluginSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds
  test("model merge plugin invokes the Python-free worker") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val refs=sys.env.get("SCALATORCH_MERGE_REFERENCE_DIR")
    assume(cp.nonEmpty && model.nonEmpty && refs.nonEmpty,"worker and merge fixtures required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-merge-plugin"))
    val sourceDir=Files.createDirectory(home.root.resolve("models"))
    for name<-Vector("a","b","c") do Files.copy(Path.of(refs.get).resolve(name+".safetensors"),sourceDir.resolve(name+".safetensors"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(sourceDir.toString)))
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry => { val path=Path.of(entry);(if path.isAbsolute then path else cpFile.getParent.resolve(path)).normalize().toString })
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",root.toString,root.resolve("euler-demo.json").toString),root,helloTimeout=30.seconds)
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,launchOverride=Some(()=>launch),
      persistSettings=false,installShutdownHook=false)
    val ctx=new FakeContext():
      override def engineCall(kind: String,params: ujson.Obj): ujson.Value=services.generator.engineCall(kind,params)
      override def modelPath(source: ChoiceSource,name: String): Option[String]=Some(sourceDir.resolve(name).toString)
    try
      assertEquals(services.start().python,"")
      val values=Values(Map("model_a"->"a.safetensors","model_b"->"b.safetensors","model_c"->"c.safetensors",
        "mode"->"Triple sum","alpha"->"0.35","beta"->"0.2","use_blocks"->"true","block_preset"->"GRAD_V",
        "precision"->"fp32","vae_source"->"B","output_name"->"result","generate"->"false"))
      new ModelMergePlugin().run(GenerationParams(),values,ctx)
      val out=sourceDir.resolve("result.safetensors");assert(Files.isRegularFile(out))
      def tensor(path: Path,key: String): Vector[Byte] =
        val data=Files.readAllBytes(path);val length=ByteBuffer.wrap(data,0,8).order(ByteOrder.LITTLE_ENDIAN).getLong.toInt
        val header=ujson.read(new String(data,8,length,java.nio.charset.StandardCharsets.UTF_8));val offsets=header(key)("data_offsets").arr.map(_.num.toInt)
        data.slice(8+length+offsets(0),8+length+offsets(1)).toVector
      val expected=Path.of(refs.get).resolve("triple-sum-fp32.safetensors")
      for key<-Vector("model.diffusion_model.input_blocks.0.conv.weight","first_stage_model.conv.weight","integer","only_a") do
        assertEquals(tensor(out,key),tensor(expected,key),key)
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      for path<-Vector(home.root,ctx.dir) do
        val paths=Files.walk(path)
        try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }

  test("queued model merge preserves the published checkpoint after job completion") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val refs=sys.env.get("SCALATORCH_MERGE_REFERENCE_DIR")
    assume(cp.nonEmpty && model.nonEmpty && refs.nonEmpty,"worker and merge fixtures required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-merge-queue"))
    val sourceDir=Files.createDirectory(home.root.resolve("models"))
    for name<-Vector("a","b") do Files.copy(Path.of(refs.get).resolve(name+".safetensors"),sourceDir.resolve(name+".safetensors"))
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry => { val path=Path.of(entry);(if path.isAbsolute then path else cpFile.getParent.resolve(path)).normalize().toString })
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",root.toString,root.resolve("euler-demo.json").toString),root,helloTimeout=30.seconds)
    val settings=Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(sourceDir.toString)))
    val plugin=new ModelMergePlugin
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq(plugin),launchOverride=Some(()=>launch),
      persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.catalog.set(services.catalog.snapshot.copy(checkpoints=Vector("a","b").map(name =>
        ModelEntry(name+".safetensors",sourceDir.resolve(name+".safetensors").toString,"safetensors"))))
      val values=Values(Map("model_a"->"a.safetensors","model_b"->"b.safetensors","mode"->"Weight sum",
        "alpha"->"0.5","precision"->"fp32","vae_source"->"merge","output_name"->"queued-result","generate"->"false"))
      Await.result(services.queue.submit(Job(GenerationParams()).copy(script=Some(plugin->values))),90.seconds)
      assert(Files.isRegularFile(sourceDir.resolve("queued-result.safetensors")),"published model was removed after job cleanup")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
