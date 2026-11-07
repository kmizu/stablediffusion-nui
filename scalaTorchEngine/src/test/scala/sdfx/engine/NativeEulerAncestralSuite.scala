package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

class NativeEulerAncestralSuite extends munit.FunSuite:
  override val munitTimeout: Duration=3.minutes
  test("NUI Generator exposes Euler a and runs deterministic txt2img and img2img without Python") {
    val model=sys.env.get("SCALATORCH_TEST_MODEL")
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    assume(model.nonEmpty && cp.nonEmpty,"native model and worker classpath required")
    val root=Path.of(model.get).toRealPath()
    val appHome=AppHome(Files.createTempDirectory("native-euler-a-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=cp,scalaTorchModel=Some(root.toString),
      scalaTorchScheduler=Some(root.resolve("scheduler/scheduler_config.json").toString),
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(appHome,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      assert(services.catalog.samplers.samplers.exists(_.name=="Euler a"))
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="apple",width=64,height=64,steps=2,seed=42,
        samplerName="Euler a",scheduler="Uniform")
      val image=services.generator.generate(params).head
      val replay=services.generator.generate(params).head
      def pixels(path: Path): Vector[Int] =
        val value=PngMetadata.readImage(path)
        value.getRGB(0,0,value.getWidth,value.getHeight,null,0,value.getWidth).toVector
      assertEquals(pixels(image.path),pixels(replay.path))
      val edited=services.generator.generate(params.copy(img2img=Some(Img2ImgParams(image.path.toString,
        denoisingStrength=0.5)))).head
      assertEquals(PngMetadata.readImage(edited.path).getWidth,64)
      val metadata=PngMetadata.readTexts(image.path)(PngMetadata.ParametersKey)
      assert(metadata.contains("Euler a"))
      assert(!Files.exists(appHome.pythonDir))
    finally services.shutdown()
  }
