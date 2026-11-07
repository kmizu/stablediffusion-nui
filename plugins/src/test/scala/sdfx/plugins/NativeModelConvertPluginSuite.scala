package sdfx.plugins

import java.nio.{ByteBuffer,ByteOrder}
import java.nio.file.{Files,Path}
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.Values
import sdfx.engine.{AppServices,EngineLaunch,Job}

class NativeModelConvertPluginSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds
  test("queued converter discovers inputs and retains the Python-free result") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val refs=sys.env.get("SCALATORCH_CONVERT_REFERENCE_DIR")
    assume(cp.nonEmpty && model.nonEmpty && refs.nonEmpty,"worker and conversion fixtures required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-convert-queue"))
    val models=Files.createDirectory(home.root.resolve("models"))
    val vaes=Files.createDirectory(home.root.resolve("vaes"))
    Files.copy(Path.of(refs.get).resolve("source.safetensors"),models.resolve("source.safetensors"))
    Files.copy(Path.of(refs.get).resolve("vae.pt"),vaes.resolve("vae.pt"))
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry => { val path=Path.of(entry);(if path.isAbsolute then path else cpFile.getParent.resolve(path)).normalize().toString })
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",root.toString,root.resolve("euler-demo.json").toString),root,helloTimeout=30.seconds)
    val settings=Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(models.toString),vae=Seq(vaes.toString)))
    val plugin=new ModelConverterPlugin
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq(plugin),launchOverride=Some(()=>launch),
      persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      assert(services.catalog.checkpoints.exists(_.name=="source.safetensors"))
      assert(services.catalog.vaes.exists(_.name=="vae"))
      val values=Values(Map("model"->"source.safetensors","precision"->"fp16","ema"->"ema-only",
        "fix_clip"->"true","drop_junk"->"true","bake_vae"->"vae","output_name"->"converted"))
      Await.result(services.queue.submit(Job(GenerationParams()).copy(script=Some(plugin->values))),90.seconds)
      val output=models.resolve("converted.safetensors")
      assert(Files.isRegularFile(output),"published checkpoint was removed during job cleanup")
      def tensors(path: Path): Map[String,(String,Vector[Int],Vector[Byte])] =
        val bytes=Files.readAllBytes(path)
        val n=ByteBuffer.wrap(bytes,0,8).order(ByteOrder.LITTLE_ENDIAN).getLong.toInt
        val header=ujson.read(new String(bytes,8,n,java.nio.charset.StandardCharsets.UTF_8)).obj
        header.toVector.filterNot(_._1=="__metadata__").map { (key,value) =>
          val offsets=value("data_offsets").arr.map(_.num.toInt)
          key->(value("dtype").str,value("shape").arr.map(_.num.toInt).toVector,
            bytes.slice(8+n+offsets(0),8+n+offsets(1)).toVector)
        }.toMap
      assertEquals(tensors(output),tensors(Path.of(refs.get).resolve("fp16-ema-only-1.safetensors")))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
