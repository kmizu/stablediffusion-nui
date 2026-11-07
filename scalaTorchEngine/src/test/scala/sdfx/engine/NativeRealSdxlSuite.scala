package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,LoraRef}
import sdfx.core.png.PngMetadata
import sdfx.core.protocol.FileEntry
import java.nio.{ByteBuffer,ByteOrder}
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

/** Opt-in acceptance of the full SDXL model through the NUI provider. */
class NativeRealSdxlSuite extends munit.FunSuite:
  override val munitTimeout: Duration=10.minutes
  test("tiny SDXL block-weight LoRA runs through NUI Generator on CUDA") {
    val model=sys.env.get("SCALATORCH_TINY_SDXL_MODEL")
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    assume(model.nonEmpty && cp.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"tiny SDXL and CUDA worker required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-sdxl-lbw"))
    val lora=home.root.resolve("mid.safetensors")
    val name="unet.mid_block.attentions.0.transformer_blocks.0.attn1.to_q"
    val entries=Vector((s"$name.lora_down.weight",Vector(1,64),Array.tabulate(64)(i=>(math.sin(i*.31)*.2).toFloat)),
      (s"$name.lora_up.weight",Vector(64,1),Array.tabulate(64)(i=>(math.cos(i*.23)*30).toFloat)))
    var size=0
    val header=entries.map { (key,dims,values)=>
      val start=size;size+=values.length*4
      s"\"$key\":{\"dtype\":\"F32\",\"shape\":[${dims.mkString(",")}],\"data_offsets\":[$start,$size]}"
    }.mkString("{",",","}").getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val bytes=ByteBuffer.allocate(8+header.length+size).order(ByteOrder.LITTLE_ENDIAN)
    bytes.putLong(header.length.toLong).put(header);entries.foreach((_,_,values)=>values.foreach(bytes.putFloat));Files.write(lora,bytes.array())
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start();assertEquals((hello.python,hello.device),("","cuda:0"))
      services.loadCheckpoint(root.toString,remember=false)
      services.catalog.set(services.catalog.snapshot.copy(loras=Seq(FileEntry("mid",lora.toString))))
      val base=GenerationParams(prompt="a red cat",width=64,height=64,steps=4,seed=42,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"))
      def pixels(params: GenerationParams) =
        val img=PngMetadata.readImage(services.generator.generate(params).head.path)
        img.getRGB(0,0,64,64,null,0,64).toVector
      val plain=pixels(base);val full=pixels(base.copy(loras=Seq(LoraRef("mid"))))
      assertNotEquals(full,plain)
      assertEquals(pixels(base.copy(loras=Seq(LoraRef("mid",lbw=Some("MIDD"))))),full)
      assertEquals(pixels(base.copy(prompt="a red cat <lora:mid:1:lbw=NONE>")),plain)
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
  test("official SDXL runs through Generator without Python") {
    val model=sys.env.get("SCALATORCH_SDXL_MODEL")
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    assume(model.nonEmpty && cp.nonEmpty,"SDXL model and worker classpath required")
    val root=Path.of(model.get).toRealPath()
    val home=AppHome(Files.createTempDirectory("native-sdxl-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=cp,scalaTorchModel=Some(root.toString),scalaTorchUnetDevice="cuda",
      scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      assert(services.catalog.findCheckpoint(root.getFileName.toString).nonEmpty)
      services.loadCheckpoint(root.toString,remember=false)
      val result=services.generator.generate(GenerationParams(prompt="a red apple",width=64,height=64,steps=1,seed=42)).head
      val image=PngMetadata.readImage(result.path)
      assertEquals(image.getWidth,64)
      assertEquals(image.getHeight,64)
      assert(!Files.exists(home.pythonDir))
    finally services.shutdown()
  }
