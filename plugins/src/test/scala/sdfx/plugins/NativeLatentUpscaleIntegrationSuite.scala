package sdfx.plugins

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.plugins.api.Values
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices,EngineLaunch,Job}

class NativeLatentUpscaleIntegrationSuite extends munit.FunSuite:
  override val munitTimeout: Duration=240.seconds
  test("Latent Upscale plugin runs through native CUDA worker without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("nui-latent-upscale-plugin"))
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry => { val path=Path.of(entry);(if path.isAbsolute then path else cpFile.getParent.resolve(path)).normalize().toString })
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",root.toString,root.resolve("euler-demo.json").toString),root,
      env=Map("SDFX_SCALATORCH_UNET_DEVICE"->"cuda","SDFX_SCALATORCH_VAE_DEVICE"->"cuda",
        "SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS"->"true","SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"->"true"),helloTimeout=30.seconds)
    val settings=Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val plugin=new LatentUpscalePlugin
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq(plugin),launchOverride=Some(()=>launch),
      persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"")
      assertEquals(hello.device,"cuda:0")
      assert(hello.features.contains("latent_upscale"))
      assert(services.registry.unsupported(hello.features).isEmpty)
      services.loadCheckpoint(root.toString,remember=false)
      val image=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      val mask=new BufferedImage(64,64,BufferedImage.TYPE_BYTE_GRAY)
      for y <- 0 until 64; x <- 0 until 64 do
        image.setRGB(x,y,((x*4)<<16)|((y*4)<<8)|64)
        mask.setRGB(x,y,if x<32 then 0xff000000 else 0xffffffff)
      val input=home.root.resolve("source.png");val maskPath=home.root.resolve("mask.png")
      assert(ImageIO.write(image,"png",input.toFile));assert(ImageIO.write(mask,"png",maskPath.toFile))
      val base=GenerationParams(prompt="a red cat",width=128,height=128,steps=2,seed=42,cfgScale=1,
        samplerName="Euler",scheduler="Normal",extra=Map("rng"->"cpu"),
        img2img=Some(Img2ImgParams(input.toString,denoisingStrength=.5)))
      for method <- LatentUpscalePlugin.methods do
        val values=Values(Map("method"->method,"scheduler"->"simple"))
        val result=Await.result(services.queue.submit(Job(base).copy(script=Some(plugin->values))),180.seconds)
        assertEquals(result.images.size,1,method)
        val generated=result.images.head
        val out=PngMetadata.readImage(generated.path)
        assertEquals((out.getWidth,out.getHeight),(128,128),method)
        assertEquals(generated.params.extra("Latent upscale method"),method)
      for sampler <- Vector("Euler","Euler a","DPM++ 2M"); schedule <- LatentUpscalePlugin.schedules do
        val params=base.copy(samplerName=sampler)
        val values=Values(Map("method"->"nearest-exact","scheduler"->schedule))
        val result=Await.result(services.queue.submit(Job(params).copy(script=Some(plugin->values))),180.seconds)
        val generated=result.images.head
        val out=PngMetadata.readImage(generated.path)
        assertEquals((out.getWidth,out.getHeight),(128,128),s"$sampler / $schedule")
        assertEquals(generated.params.extra("Latent upscale scheduler"),schedule)
      val masked=base.copy(img2img=Some(Img2ImgParams(input.toString,mask=Some(maskPath.toString),
        denoisingStrength=.5,maskBlur=0,inpaintFullRes=true,inpaintFullResPadding=4)))
      val result=Await.result(services.queue.submit(Job(masked).copy(script=Some(plugin->Values(Map.empty)))),180.seconds)
      val full=PngMetadata.readImage(result.images.head.path)
      assertEquals((full.getWidth,full.getHeight),(64,64))
      assertEquals(full.getRGB(0,0)&0xffffff,image.getRGB(0,0)&0xffffff)
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
