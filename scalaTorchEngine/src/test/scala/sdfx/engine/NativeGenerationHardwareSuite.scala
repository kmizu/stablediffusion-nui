package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,Img2ImgParams}
import sdfx.core.png.PngMetadata
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

class NativeGenerationHardwareSuite extends munit.FunSuite:
  override val munitTimeout: Duration=3.minutes
  test("native txt2img and img2img save hardware metadata in NUI PNGs") {
    val model=sys.env.get("SCALATORCH_TEST_MODEL")
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    assume(model.nonEmpty && cp.nonEmpty,"native model and worker classpath required")
    val root=Path.of(model.get).toRealPath()
    val appHome=AppHome(Files.createTempDirectory("native-generation-hardware-nui"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",
      scalaTorchClasspath=cp,scalaTorchModel=Some(root.toString),
      scalaTorchScheduler=Some(root.resolve("scheduler/scheduler_config.json").toString),
      hardwareInfoEnabled=true,hardwareInfoRemark="native generation test",
      modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(appHome,settings,dryRun=false,builtins=Nil,
      persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      services.loadCheckpoint(root.toString,remember=false)
      val params=GenerationParams(prompt="apple",width=64,height=64,steps=1,seed=42,batchSize=2)
      val images=services.generator.generate(params)
      assertEquals(images.size,2)
      def check(path: Path): Unit =
        val metadata=PngMetadata.readTexts(path)(PngMetadata.ParametersKey)
        assert(metadata.contains("Hardware Info:"))
        assert(metadata.contains("Time taken:"))
        assert(metadata.contains("native generation test"))
      images.foreach(image=>check(image.path))
      val edited=services.generator.generate(params.copy(batchSize=1,
        img2img=Some(Img2ImgParams(images.head.path.toString,denoisingStrength=0.5)))).head
      check(edited.path)
      assert(!Files.exists(appHome.pythonDir))
    finally services.shutdown()
  }
