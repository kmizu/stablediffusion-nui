package sdfx.engine

import java.nio.{ByteBuffer,ByteOrder}
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.png.PngMetadata

class NativeModelMergeSuite extends munit.FunSuite:
  override val munitTimeout: Duration=15.minutes
  test("NUI sends checkpoint conversion to the native worker") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val refs=sys.env.get("SCALATORCH_MERGE_REFERENCE_DIR")
    assume(cp.nonEmpty && model.nonEmpty && refs.nonEmpty,"native worker and fixtures required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-convert-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      val input=Path.of(refs.get).resolve("a.safetensors")
      val output=home.root.resolve("converted.safetensors")
      val result=services.generator.engineCall("convert_model",ujson.Obj("model"->input.toString,"output"->output.toString,
        "precision"->"fp16","ema"->"no-ema","fix_clip"->true,"drop_junk"->true,"bake_vae"->ujson.Null,"overwrite"->false))
      assertEquals(result("keys").num.toInt,6)
      assert(Files.isRegularFile(output))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
  test("NUI merges checkpoints with the native worker without loading Python or a model") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val refs=sys.env.get("SCALATORCH_MERGE_REFERENCE_DIR")
    assume(cp.nonEmpty && model.nonEmpty && refs.nonEmpty,"native worker and merge fixtures required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-merge-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-demo.json").toString),
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      val a=Path.of(refs.get).resolve("a.safetensors")
      val b=Path.of(refs.get).resolve("b.safetensors")
      val c=Path.of(refs.get).resolve("c.safetensors")
      val out=home.root.resolve("merged.safetensors")
      val result=services.generator.engineCall("merge_models",ujson.Obj("model_a"->a.toString,"model_b"->b.toString,
        "model_c"->c.toString,"output"->out.toString,"mode"->"Triple sum","alpha"->0.35,"beta"->0.2,
        "block_weights"->"preset:GRAD_V","vae_source"->"B","precision"->"fp32","overwrite"->false))
      assertEquals(result("keys").num.toInt,6)
      def tensor(path: Path,key: String): (String,Vector[Int],Vector[Byte]) =
        val data=Files.readAllBytes(path)
        val length=ByteBuffer.wrap(data,0,8).order(ByteOrder.LITTLE_ENDIAN).getLong.toInt
        val header=ujson.read(new String(data,8,length,java.nio.charset.StandardCharsets.UTF_8))
        val entry=header(key);val offsets=entry("data_offsets").arr.map(_.num.toInt)
        (entry("dtype").str,entry("shape").arr.map(_.num.toInt).toVector,
          data.slice(8+length+offsets(0),8+length+offsets(1)).toVector)
      val expected=Path.of(refs.get).resolve("triple-sum-fp32.safetensors")
      for key<-Vector("model.diffusion_model.input_blocks.0.conv.weight","first_stage_model.conv.weight","integer","only_a") do
        assertEquals(tensor(out,key),tensor(expected,key),key)
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }

  test("real SD1 checkpoint is merged and its result generates on CUDA without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val tiny=sys.env.get("SCALATORCH_TINY_MODEL")
    val model=sys.env.get("SCALATORCH_MERGE_REAL_MODEL")
    val tokenizer=sys.env.get("SCALATORCH_TEST_TOKENIZER")
    val output=sys.env.get("SCALATORCH_MERGE_REAL_OUTPUT")
    val imageOutput=sys.env.get("SCALATORCH_MERGE_REAL_IMAGE")
    assume(cp.nonEmpty && tiny.nonEmpty && model.nonEmpty && tokenizer.nonEmpty && output.nonEmpty && imageOutput.nonEmpty &&
      sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"real checkpoint and CUDA fixtures required")
    val input=Path.of(model.get).toRealPath()
    val target=Path.of(output.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("native-real-merge"))
    val tinyRoot=Path.of(tiny.get).toRealPath()
    val base=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(tinyRoot.toString),scalaTorchScheduler=Some(tinyRoot.resolve("euler-demo.json").toString),
      lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(input.getParent.toString)))
    val first=new AppServices(home,base,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(first.start().python,"")
      val result=first.generator.engineCall("merge_models",ujson.Obj("model_a"->input.toString,"model_b"->input.toString,
        "mode"->"Weight sum","alpha"->0.5,"precision"->"fp16","output"->target.toString,"overwrite"->true))
      assert(result("keys").num>1000)
      assert(Files.size(target)>1000000000L)
    finally first.shutdown()
    val tokenDir=Files.createDirectories(home.root.resolve("scala-torch/tokenizer"))
    for name<-Vector("vocab.json","merges.txt") do Files.copy(Path.of(tokenizer.get).resolve(name),tokenDir.resolve(name))
    val next=base.copy(scalaTorchModel=Some(target.toString),scalaTorchScheduler=None,scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      modelDirs=ModelDirs(checkpoints=Seq(target.getParent.toString)))
    val second=new AppServices(home,next,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      assertEquals(second.start().python,"")
      second.loadCheckpoint(target.toString,remember=false)
      val result=second.generator.generate(GenerationParams(prompt="a red fox in snow",width=64,height=64,steps=1,seed=42,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"))).head
      val image=PngMetadata.readImage(result.path)
      assertEquals((image.getWidth,image.getHeight),(64,64))
      Files.copy(result.path,Path.of(imageOutput.get))
      assert(!Files.exists(home.pythonDir))
    finally
      second.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
