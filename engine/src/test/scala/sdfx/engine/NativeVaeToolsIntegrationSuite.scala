package sdfx.engine

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}

class NativeVaeToolsIntegrationSuite extends munit.FunSuite:
  override val munitTimeout: Duration=15.minutes
  test("NUI service runs Full VAE Tools through the Python-free worker") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_VAE_MODEL").orElse(sys.env.get("SCALATORCH_TINY_MODEL"))
    assume(cp.nonEmpty && model.nonEmpty,"native worker and model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("nui-native-vae-tools"))
    val scheduler=if Files.isRegularFile(root.resolve("euler-explicit.json")) then "euler-explicit.json" else "euler-demo.json"
    val schedulerPath=sys.env.get("SCALATORCH_VAE_SCHEDULER").map(Path.of(_)).getOrElse(root.resolve(scheduler))
    val baseLaunch=NativeTestLaunch(cp.get,root,schedulerPath)
    val heapOption=sys.env.get("SCALATORCH_VAE_WORKER_HEAP").map(heap=>s"-Xmx$heap")
      .getOrElse("-XX:MaxRAMPercentage=50.0")
    val launch=baseLaunch.copy(command=baseLaunch.command.head +: heapOption +: baseLaunch.command.tail)
    val settings=Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq.empty,launchOverride=Some(()=>launch),
      persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assert(hello.features.contains("vae_tools"),"native VAE Tools implementation must be advertised to the UI")
      val placement=Await.result(services.client.systemStatus(),10.seconds)("runtime")("vae_device").str
      val expected=if sys.env.get("SDFX_SCALATORCH_VAE_DEVICE").contains("cuda") then "cuda:0" else "cpu"
      assertEquals(placement,expected)
      services.loadCheckpoint(root.toString,remember=false)
      val source=home.root.resolve("source.png")
      val size=sys.env.get("SCALATORCH_VAE_TEST_SIZE").map(_.toInt).getOrElse(16)
      val original=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB)
      for y<-0 until size; x<-0 until size do
        original.setRGB(x,y,((x*255/(size-1))<<16)|((y*255/(size-1))<<8)|128)
      ImageIO.write(original,"png",source.toFile)
      val report=Await.result(new VaeToolsService(services).run(Seq(source),"encode+decode","L","Full",home.root,seed=42,tiled=true),10.minutes)
      assertEquals(report("results")(0)("status").str,"complete")
      val output=Path.of(report("results")(0)("path").str)
      assert(Files.isRegularFile(output))
      val image=ImageIO.read(output.toFile)
      assertEquals((image.getWidth,image.getHeight),(size,size))
      var total=0L
      for y<-0 until size; x<-0 until size; shift<-Seq(0,8,16) do
        total+=math.abs(((original.getRGB(x,y)>>>shift)&255)-((image.getRGB(x,y)>>>shift)&255))
      val error=total.toDouble/(size*size*3)
      println(s"NUI native VAE reconstruction RGB mean absolute error: $error")
      if size>=512 then assert(error<40)
      val cheap=Await.result(new VaeToolsService(services).run(Seq(source),"encode+decode","L","Approx cheap",home.root,seed=42),10.minutes)
      assertEquals(cheap("encoding_approximation").str,"Full")
      assert(cheap("warnings").arr.exists(_.str.contains("cannot encode")))
      val cheapItem=cheap("results")(0)
      assertEquals(cheapItem("status").str,"complete")
      val dimensions=cheapItem("statistics")("shape").arr.map(_.num.toInt)
      val cheapImage=ImageIO.read(Path.of(cheapItem("path").str).toFile)
      assertEquals((cheapImage.getWidth,cheapImage.getHeight),(dimensions(3),dimensions(2)))
      sys.env.get("SCALATORCH_APPROX_NN_WEIGHTS").foreach { weights =>
        val started=System.nanoTime()
        val nn=Await.result(new VaeToolsService(services).run(Seq(source),"encode+decode","L","Approx NN",home.root,
          seed=42,approxNnPath=weights),10.minutes)
        assertEquals(nn("encoding_approximation").str,"Full")
        val nnItem=nn("results")(0)
        assertEquals(nnItem("status").str,"complete")
        val nnImage=ImageIO.read(Path.of(nnItem("path").str).toFile)
        assertEquals((nnImage.getWidth,nnImage.getHeight),(dimensions(3)*2,dimensions(2)*2))
        println(s"NUI Approx NN encode/decode seconds=${(System.nanoTime()-started)/1e9}")
      }
      if sys.env.get("SCALATORCH_TEST_APPROX_DOWNLOAD").contains("1") then
        val downloaded=Await.result(new VaeToolsService(services).run(Seq(source),"encode+decode","L","Approx NN",home.root,
          seed=42,allowDownload=true),10.minutes)
        assertEquals(downloaded("results")(0)("status").str,"complete")
        assert(Files.isRegularFile(home.root.resolve("models/vae-tools/vae-approx/model.pt")))
      if sys.env.get("SCALATORCH_TEST_TINY_DOWNLOAD").contains("1") then
        val service=new VaeToolsService(services)
        intercept[sdfx.core.protocol.EngineError] {
          Await.result(service.run(Seq(source),"encode+decode","L","TAESD",home.root),10.minutes)
        }
        val downloaded=Await.result(service.run(Seq(source),"encode+decode","L","TAESD",home.root,allowDownload=true),10.minutes)
        assertEquals(downloaded("results")(0)("status").str,"complete")
        assert(Files.isRegularFile(home.root.resolve("models/vae-tools/taesd/diffusion_pytorch_model.safetensors")))
        val cached=Await.result(service.run(Seq(source),"encode+decode","L","TAESD",home.root),10.minutes)
        assertEquals(cached("results")(0)("status").str,"complete")
      val cmyk=Await.result(new VaeToolsService(services).run(Seq(source),"encode","CMYK","Approx cheap",home.root,seed=42),10.minutes)
      val cmykItem=cmyk("results")(0)
      assertEquals(cmykItem("status").str,"complete")
      val cmykPath=Path.of(cmykItem("path").str)
      assert(cmykPath.toString.endsWith(".tiff") && Files.isRegularFile(cmykPath))
      val decoded=Await.result(new VaeToolsService(services).run(Seq(cmykPath),"decode","CMYK","Approx cheap",home.root),10.minutes)
      assertEquals(decoded("results")(0)("status").str,"complete")
      val cmykPreview=ImageIO.read(Path.of(decoded("results")(0)("path").str).toFile)
      assertEquals((cmykPreview.getWidth,cmykPreview.getHeight),(dimensions(3),dimensions(2)))
      val session=new sdfx.core.png.ImageScrambleSession
      session.unlock("native-vae-test-password")
      val secret=session.snapshot
      val protectedSource=home.root.resolve("protected-source.png")
      sdfx.core.png.ScrambledPng.write(original,protectedSource,Seq.empty,Some("native-vae-test-password"))
      val service=new VaeToolsService(services)
      intercept[sdfx.core.protocol.EngineError] {
        Await.result(service.run(Seq(protectedSource),"encode","RGBA","Full",home.root,imageSecret=secret),10.minutes)
      }
      val protectedReport=Await.result(service.run(Seq(protectedSource),"encode","RGBA","Full",home.root,
        allowPlaintextLatent=true,imageSecret=secret),10.minutes)
      val protectedItem=protectedReport("results")(0)
      assertEquals(protectedItem("status").str,"complete")
      val protectedLatent=Path.of(protectedItem("path").str)
      assert(sdfx.core.png.ScrambledPng.read(protectedLatent,Some("native-vae-test-password")).scrambled)
      val protectedDecode=Await.result(service.run(Seq(protectedLatent),"decode","RGBA","Full",home.root,imageSecret=secret),10.minutes)
      assertEquals(protectedDecode("results")(0)("status").str,"complete")
      val protectedImage=Path.of(protectedDecode("results")(0)("path").str)
      assert(sdfx.core.png.ScrambledPng.read(protectedImage,Some("native-vae-test-password")).scrambled)
      session.unlock("wrong-password")
      val wrong=Await.result(service.run(Seq(protectedLatent),"decode","RGBA","Full",home.root,imageSecret=session.snapshot),10.minutes)
      assertEquals(wrong("results")(0)("status").str,"error")
      assert(!Files.exists(home.pythonDir))
    finally services.shutdown()
  }
