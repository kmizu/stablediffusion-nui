package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.png.PngMetadata
import sdfx.core.protocol.EngineEvent
import java.nio.file.{Files,Path,StandardCopyOption}
import scala.concurrent.duration.*
import scala.concurrent.Await
import scala.util.Using

/** Opt-in real checkpoint acceptance through NUI's native backend provider. */
class NativeSd15CkptSuite extends munit.FunSuite:
  override val munitTimeout: Duration=20.minutes
  test("NUI discovers and generates from an SD1 ckpt without Python") {
    val config=Vector(Path.of(".superpowers/sd15-ckpt-test.properties"),
      Path.of("../.superpowers/sd15-ckpt-test.properties")).find(Files.isRegularFile(_))
    assume(config.nonEmpty,"real checkpoint, native worker and CUDA required")
    val properties=new java.util.Properties()
    Using.resource(Files.newInputStream(config.get))(properties.load)
    val checkpoint=Path.of(properties.getProperty("checkpoint")).toRealPath()
    val cp=properties.getProperty("classpath")
    val tokenizer=Path.of(properties.getProperty("tokenizer"))
    val output=Path.of(properties.getProperty("output"))
    val tinyPreview=Option(properties.getProperty("previewMethod")).contains("taesd")
    val expectPreviewFallback=Option(properties.getProperty("expectPreviewFallback")).contains("true")
    val home=AppHome(Files.createTempDirectory("nui-sd15-ckpt"))
    val tokenizerDir=Files.createDirectories(home.root.resolve("scala-torch/tokenizer"))
    Vector("vocab.json","merges.txt").foreach(name=>Files.copy(tokenizer.resolve(name),tokenizerDir.resolve(name)))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      previewEvery=2,previewMethod=if tinyPreview then "taesd" else "approx",
      scalaTorchClasspath=Some(cp),scalaTorchModel=Some(checkpoint.toString),
      scalaTorchJava=Option(properties.getProperty("java")),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",
      scalaTorchUnetOffloadWeights=true,scalaTorchVaeOffloadWeights=true,
      modelDirs=ModelDirs(checkpoints=Seq(checkpoint.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      val listed=services.catalog.findCheckpoint(checkpoint.getFileName.toString).getOrElse(fail("checkpoint missing from NUI catalog"))
      assert(Files.isSameFile(Path.of(listed.path),checkpoint))
      services.loadCheckpoint(checkpoint.toString,remember=false)
      val previews=new java.util.concurrent.ConcurrentLinkedQueue[EngineEvent.Preview]()
      val logs=new java.util.concurrent.ConcurrentLinkedQueue[String]()
      val listener=new GenerationListener:
        override def onPreview(value: EngineEvent.Preview): Unit = {previews.add(value);()}
        override def onLog(value: String): Unit = {logs.add(value);()}
      val generated=services.generator.generateWith(GenerationParams(prompt="a photograph of a red apple on a wooden table",
        width=256,height=256,steps=8,seed=42,cfgScale=7.5,samplerName="Euler",scheduler="Uniform"),listener).head
      val image=PngMetadata.readImage(generated.path)
      assertEquals((image.getWidth,image.getHeight),(256,256))
      assert(!Files.exists(home.pythonDir))
      Files.createDirectories(output)
      Files.copy(generated.path,output.resolve("nui-sd15-ckpt.png"),StandardCopyOption.REPLACE_EXISTING)
      if tinyPreview then
        import scala.jdk.CollectionConverters.*
        assertEquals(previews.size(),3)
        assertEquals(logs.asScala.exists(_.contains("TAESD preview unavailable")),expectPreviewFallback)
        val preview=java.util.Base64.getDecoder.decode(previews.peek().jpegB64)
        assert(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(preview))!=null)
        Files.write(output.resolve("nui-taesd-preview.jpg"),preview)
      Option(properties.getProperty("replacementVae")).foreach { replacement =>
        val params=GenerationParams(prompt="a red apple",width=64,height=64,steps=1,seed=42,
          cfgScale=7.5,samplerName="Euler",scheduler="Uniform")
        def pixels() =
          val generated=services.generator.generate(params).head
          PngMetadata.readImage(generated.path).getRGB(0,0,64,64,null,0,64).toVector
        val original=pixels()
        Await.result(services.client.setVae(Some(replacement)),3.minutes)
        Await.result(services.client.setVae(None),3.minutes)
        assertEquals(pixels(),original,"restoring a ckpt VAE must reproduce the original output")
      }
      Option(properties.getProperty("alternate")).foreach { alternate =>
        val alternatePath=Path.of(alternate).toRealPath()
        services.loadCheckpoint(alternatePath.toString,remember=false)
        val params=GenerationParams(prompt="a red apple",width=64,height=64,steps=1,seed=42,
          cfgScale=7.5,samplerName="Euler",scheduler="Uniform")
        def pixels() =
          val output=services.generator.generate(params).head
          val decoded=PngMetadata.readImage(output.path)
          decoded.getRGB(0,0,64,64,null,0,64).toVector
        val before=pixels()
        val invalid=Files.write(home.root.resolve("invalid.ckpt"),Array[Byte](1,2,3))
        intercept[Exception](services.loadCheckpoint(invalid.toString,remember=false))
        assertEquals(pixels(),before,"failed model switch must preserve the active model")
        services.loadCheckpoint(checkpoint.toString,remember=false)
        assertEquals(pixels().size,64*64)
      }
    finally services.shutdown()
  }
