package sdfx.plugins

import java.nio.file.{Files,Path,StandardCopyOption}
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.plugins.api.Values
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices,EngineLaunch,Job}

class NativeRealLatentUpscaleSuite extends munit.FunSuite:
  override val munitTimeout: Duration=20.minutes
  test("real SD1.5 Latent Upscale plugin generates through CUDA without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_SD15_MODEL")
    val input=sys.env.get("SCALATORCH_UPSCALE_SOURCE")
    val output=sys.env.get("SCALATORCH_SAMPLE_OUTPUT_DIR")
    assume(cp.nonEmpty && model.nonEmpty && input.nonEmpty && output.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),
      "CUDA worker, SD1.5, input image and output directory required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("nui-real-latent-upscale"))
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry => { val path=Path.of(entry);(if path.isAbsolute then path else cpFile.getParent.resolve(path)).normalize().toString })
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",root.toString,root.resolve("euler-explicit.json").toString),root,
      env=Map("SDFX_SCALATORCH_UNET_DEVICE"->"cuda","SDFX_SCALATORCH_VAE_DEVICE"->"cuda",
        "SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS"->"true","SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"->"true"),helloTimeout=30.seconds)
    val settings=Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val plugin=new LatentUpscalePlugin
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq(plugin),launchOverride=Some(()=>launch),
      persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals((hello.python,hello.device),("","cuda:0"))
      assert(hello.features.contains("latent_upscale"))
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="a red fox sitting in a snowy forest, detailed natural photograph",
        negativePrompt="blurry, low quality",width=512,height=512,steps=3,seed=42,cfgScale=7.5,
        samplerName="Euler",scheduler="Normal",extra=Map("rng"->"cpu"),
        img2img=Some(Img2ImgParams(input.get,denoisingStrength=.5)))
      val result=Await.result(services.queue.submit(Job(params).copy(script=Some(plugin->Values(Map("method"->"nearest-exact","scheduler"->"normal"))))),15.minutes)
      val image=PngMetadata.readImage(result.images.head.path)
      assertEquals((image.getWidth,image.getHeight),(512,512))
      val dir=Files.createTempDirectory(Path.of(output.get),"sd15-latent-upscale-")
      val destination=dir.resolve("fox.png")
      Files.copy(result.images.head.path,destination,StandardCopyOption.COPY_ATTRIBUTES)
      println(s"Real latent upscale output: $destination")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
