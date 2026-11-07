package sdfx.engine

import java.nio.file.{Files,Path}
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.model.GenerationParams
import sdfx.core.protocol.{GenerationRequest,LoadModelRequest}

class NativeCpuReferenceSuite extends munit.FunSuite:
  override val munitTimeout: Duration=90.seconds
  test("NUI CPU RNG image matches independent tiny PyTorch reference") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    val fixture=sys.env.get("SCALATORCH_CPU_REFERENCE")
    assume(cp.nonEmpty && model.nonEmpty && fixture.nonEmpty,"native worker, model and CPU reference required")
    val root=Path.of(model.get).toAbsolutePath
    val output=Files.createTempDirectory("nui-cpu-reference")
    val supervisor=new EngineSupervisor(()=>NativeTestLaunch(cp.get,root,root.resolve("euler-demo.json")),maxRestarts=0)
    try
      assertEquals(supervisor.start().python,"")
      val client=new EngineClient(supervisor)
      Await.result(client.loadModel(LoadModelRequest(root.toString,dtype="fp32",vramMode="cpu")),30.seconds)
      val params=GenerationParams(prompt="a red cat",width=16,height=16,steps=3,seed=42,cfgScale=1,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"))
      val result=Await.result(client.generate(GenerationRequest(params,output.toString),GenerationMode.Txt2Img,_=>()).result,45.seconds)
      val image=javax.imageio.ImageIO.read(Path.of(result.images.head.path).toFile)
      val reference=ujson.read(Files.readString(Path.of(fixture.get)))("image").arr.map(_.num).toVector
      assertEquals((image.getWidth,image.getHeight),(16,16))
      assertEquals(reference.length,16*16*3)
      val errors=for pixel <- 0 until 256; channel <- 0 until 3 yield
        val actual=(image.getRGB(pixel%16,pixel/16) >>> (16-8*channel)) & 255
        val expected=math.rint(reference(channel*256+pixel)*255).toInt
        math.abs(actual-expected)
      assert(errors.max<=1,s"maximum channel error ${errors.max}")
    finally
      supervisor.close()
      val paths=Files.walk(output)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
