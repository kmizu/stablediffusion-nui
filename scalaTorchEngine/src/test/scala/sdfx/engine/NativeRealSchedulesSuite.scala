package sdfx.engine

import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.GenerationParams
import sdfx.core.png.PngMetadata
import java.nio.file.{Files,Path,StandardCopyOption}
import scala.concurrent.duration.*

class NativeRealSchedulesSuite extends munit.FunSuite:
  override val munitTimeout: Duration=20.minutes
  test("real SD1.5 CUDA generation supports NUI Euler schedules without Python") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE")
    val model=sys.env.get("SCALATORCH_SD15_MODEL")
    val out=sys.env.get("SCALATORCH_SAMPLE_OUTPUT_DIR")
    assume(cp.nonEmpty && model.nonEmpty && out.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),
      "CUDA worker, SD1.5 model and sample output directory required")
    val root=Path.of(model.get).toAbsolutePath
    val outputs=Files.createTempDirectory(Path.of(out.get),"sd15-schedules-")
    val home=AppHome(Files.createTempDirectory("native-sd15-schedules"))
    val settings=Settings(dtype="fp32",vramMode="cpu",engineBackend="scala-torch",scalaTorchClasspath=cp,
      scalaTorchModel=Some(root.toString),scalaTorchScheduler=Some(root.resolve("euler-explicit.json").toString),
      scalaTorchUnetDevice="cuda",scalaTorchVaeDevice="cuda",scalaTorchUnetOffloadWeights=true,
      scalaTorchVaeOffloadWeights=true,lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Nil,persistSettings=false,installShutdownHook=false)
    var failure: Throwable=null
    try
      val hello=services.start()
      assertEquals((hello.python,hello.device),("","cuda:0"))
      services.loadCheckpoint(root.toString,remember=false)
      val base=GenerationParams(prompt="a red fox sitting in a snowy forest, detailed natural photograph",
        negativePrompt="blurry, low quality",width=512,height=512,steps=4,seed=42,cfgScale=7.5,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"))
      val images=Vector("Normal","Simple","SGM Uniform").map { schedule =>
        val result=services.generator.generate(base.copy(scheduler=schedule)).head
        val image=PngMetadata.readImage(result.path)
        assertEquals((image.getWidth,image.getHeight),(512,512),schedule)
        val destination=outputs.resolve(s"${schedule.replace(' ','-')}.png")
        Files.copy(result.path,destination,StandardCopyOption.COPY_ATTRIBUTES)
        println(s"$schedule output: $destination")
        image.getRGB(0,0,512,512,null,0,512).toVector
      }
      assert(images.distinct.size>=2,"distinct schedules should affect real-model output")
      assert(!Files.exists(home.pythonDir))
    catch case error: Throwable =>
      failure=error
      throw error
    finally
      try
        services.shutdown()
        val paths=Files.walk(home.root)
        try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(path => {
          require(path.toAbsolutePath.normalize().startsWith(home.root.toAbsolutePath.normalize()))
          Files.delete(path);()
        }) finally paths.close()
      catch case cleanup: Throwable =>
        if failure!=null then failure.addSuppressed(cleanup) else throw cleanup
  }
