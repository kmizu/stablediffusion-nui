package sdfx.plugins

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.core.model.{GenerationParams,HiresParams,Img2ImgParams}
import sdfx.core.plugins.api.Values
import sdfx.core.png.PngMetadata
import sdfx.engine.{AppServices,EngineLaunch,Job,JobEvent}

class NativeInpaintDifferenceIntegrationSuite extends munit.FunSuite:
  override val munitTimeout: Duration=180.seconds
  test("difference plugin uses native CUDA worker through the NUI queue") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE");val model=sys.env.get("SCALATORCH_TINY_MODEL")
    assume(cp.nonEmpty && model.nonEmpty && sys.env.get("SCALATORCH_CUDA_TESTS").contains("1"),"CUDA worker and tiny model required")
    val root=Path.of(model.get).toAbsolutePath;val home=AppHome(Files.createTempDirectory("nui-difference-plugin"))
    val cpFile=Path.of(cp.get).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry=>{val path=Path.of(entry);(if path.isAbsolute then path else cpFile.getParent.resolve(path)).normalize().toString})
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    val launch=EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
      "scalatorch.models.worker.NativeWorkerMain",root.toString,root.resolve("euler-demo.json").toString),root,
      env=Map("SDFX_SCALATORCH_UNET_DEVICE"->"cuda","SDFX_SCALATORCH_VAE_DEVICE"->"cuda",
        "SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS"->"true","SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"->"true"),helloTimeout=30.seconds)
    val plugin=new InpaintDifferencePlugin
    val thermal=new TemperatureGuardPlugin
    val rescale=new RescaleCfgPlugin
    val schedule=new CfgSchedulePlugin
    val resharpen=new ReSharpenPlugin
    val mirroring=new LatentMirroringPlugin
    val grading=new ColorGradingPlugin
    val antiBurn=new AntiBurnPlugin
    val vectorscope=new VectorscopeCcPlugin
    val detail=new DetailDaemonPlugin
    val settings=Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,modelDirs=ModelDirs(checkpoints=Seq(root.getParent.toString)))
    val services=new AppServices(home,settings,dryRun=false,builtins=Seq(plugin,thermal,rescale,schedule,resharpen,mirroring,grading,antiBurn,vectorscope,detail),launchOverride=Some(()=>launch),persistSettings=false,installShutdownHook=false)
    try
      val hello=services.start()
      assertEquals(hello.python,"");assertEquals(hello.device,"cuda:0")
      assert(hello.features.contains("inpaint_difference"));assert(services.registry.unsupported(hello.features).isEmpty)
      assert(hello.features.contains("gpu_temperature_guard"))
      assert(hello.features.contains("rescale_cfg"))
      assert(hello.features.contains("cfg_schedule"))
      assert(hello.features.contains("resharpen"))
      assert(hello.features.contains("latent_mirroring"))
      assert(hello.features.contains("color_grading"))
      assert(hello.features.contains("anti_burn"))
      assert(hello.features.contains("vectorscope_cc"))
      assert(hello.features.contains("detail_daemon"))
      services.loadCheckpoint(root.toString,remember=false)
      val base=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      val altered=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
      val mask=new BufferedImage(64,64,BufferedImage.TYPE_BYTE_GRAY)
      for y <- 0 until 64; x <- 0 until 64 do
        base.setRGB(x,y,0x203040)
        altered.setRGB(x,y,if x>=32 then 0xe0a040 else 0x203040)
      val basePath=home.root.resolve("base.png");val alteredPath=home.root.resolve("altered.png");val maskPath=home.root.resolve("mask.png")
      ImageIO.write(base,"png",basePath.toFile);ImageIO.write(altered,"png",alteredPath.toFile)
      val params=GenerationParams(prompt="a red cat",width=64,height=64,steps=2,seed=42,cfgScale=1,
        samplerName="Euler",scheduler="Uniform",extra=Map("rng"->"cpu"),
        img2img=Some(Img2ImgParams(alteredPath.toString,denoisingStrength=.5,maskBlur=0)))
      for contours <- Vector(false,true) do
        for y <- 0 until 64; x <- 0 until 64 do
          val active=if contours then x==32 && y>0 && y<63 else x>=32
          mask.getRaster.setSample(x,y,0,if active then 255 else 0)
        ImageIO.write(mask,"png",maskPath.toFile)
        val direct=params.copy(img2img=Some(Img2ImgParams(alteredPath.toString,mask=Some(maskPath.toString),denoisingStrength=.5,maskBlur=0)))
        val expected=Await.result(services.queue.submit(Job(direct)),120.seconds).images.head
        val values=Values(Map("enabled"->"true","base_path"->basePath.toString,"contours"->contours.toString))
        val result=Await.result(services.queue.submit(Job(params).copy(alwaysOn=Seq(plugin->values))),120.seconds).images.head
        val a=PngMetadata.readImage(expected.path);val b=PngMetadata.readImage(result.path)
        assertEquals(b.getRGB(0,0,64,64,null,0,64).toVector,a.getRGB(0,0,64,64,null,0,64).toVector,s"contours=$contours")
      val messages=new java.util.concurrent.CopyOnWriteArrayList[String]()
      val removeListener=services.queue.addListener {
        case JobEvent.Log(_,message) => messages.add(message);()
        case _ => ()
      }
      val guarded=Values(Map("enabled"->"true","provider"->"NVIDIA","sleep_temperature"->"-40","wake_temperature"->"-50","interval"->"60","sleep_time"->"0.1","max_sleep"->"1"))
      val guardedResult=try Await.result(services.queue.submit(Job(params).copy(alwaysOn=Seq(thermal->guarded))),120.seconds)
        finally removeListener()
      assertEquals(guardedResult.images.size,1)
      assert(messages.stream().anyMatch(_.contains("GPU temperature pause")))
      assert(messages.stream().anyMatch(_.contains("GPU temperature timeout")))
      val cfg=params.copy(cfgScale=7.5,img2img=None)
      val unscaled=Await.result(services.queue.submit(Job(cfg)),120.seconds).images.head
      val scaled=Await.result(services.queue.submit(Job(cfg).copy(alwaysOn=Seq(rescale->Values(Map("enabled"->"true","multiplier"->"0.7"))))),120.seconds).images.head
      val first=PngMetadata.readImage(unscaled.path);val second=PngMetadata.readImage(scaled.path)
      assert(!first.getRGB(0,0,64,64,null,0,64).sameElements(second.getRGB(0,0,64,64,null,0,64)),"rescale must affect guided output")
      val imageCfg=params.copy(cfgScale=7.5)
      val imagePlain=Await.result(services.queue.submit(Job(imageCfg)),120.seconds).images.head
      val imageScaled=Await.result(services.queue.submit(Job(imageCfg).copy(alwaysOn=Seq(rescale->Values(Map("enabled"->"true","multiplier"->"0.7"))))),120.seconds).images.head
      val plainPixels=PngMetadata.readImage(imagePlain.path).getRGB(0,0,64,64,null,0,64)
      val scaledPixels=PngMetadata.readImage(imageScaled.path).getRGB(0,0,64,64,null,0,64)
      assert(plainPixels.sameElements(scaledPixels),"SD1 img2img ignores guidance_rescale like Diffusers")
      val scheduleValues=Values(Map("enabled"->"true","start_cfg"->"12","end_cfg"->"2","curve"->"cosine"))
      val scheduled=Await.result(services.queue.submit(Job(cfg).copy(alwaysOn=Seq(schedule->scheduleValues))),120.seconds).images.head
      val scheduledPixels=PngMetadata.readImage(scheduled.path).getRGB(0,0,64,64,null,0,64)
      assert(!first.getRGB(0,0,64,64,null,0,64).sameElements(scheduledPixels),"CFG schedule must affect output")
      val combined=Await.result(services.queue.submit(Job(cfg).copy(alwaysOn=Seq(schedule->scheduleValues,
        rescale->Values(Map("enabled"->"true","multiplier"->"0.7"))))),120.seconds).images.head
      assert(!scheduledPixels.sameElements(PngMetadata.readImage(combined.path).getRGB(0,0,64,64,null,0,64)))
      val unguided=Await.result(services.queue.submit(Job(params).copy(alwaysOn=Seq(schedule->scheduleValues))),120.seconds).images.head
      val unmodified=Await.result(services.queue.submit(Job(params)),120.seconds).images.head
      assert(PngMetadata.readImage(unguided.path).getRGB(0,0,64,64,null,0,64).sameElements(
        PngMetadata.readImage(unmodified.path).getRGB(0,0,64,64,null,0,64)),"CFG schedule must be ignored when CFG is off")
      val sharpParams=cfg.copy(steps=4)
      val baseline=Await.result(services.queue.submit(Job(sharpParams)),120.seconds).images.head
      val sharpened=Await.result(services.queue.submit(Job(sharpParams).copy(alwaysOn=Seq(resharpen->
        Values(Map("enabled"->"true","sharpness"->"1.5","scaling"->"Cos"))))),120.seconds).images.head
      assert(!PngMetadata.readImage(baseline.path).getRGB(0,0,64,64,null,0,64).sameElements(
        PngMetadata.readImage(sharpened.path).getRGB(0,0,64,64,null,0,64)),"resharpen must affect output")
      val mirrored=Await.result(services.queue.submit(Job(sharpParams).copy(alwaysOn=Seq(mirroring->
        Values(Map("enabled"->"true","style"->"90 Degree Rotation","mode"->"Blend Average","max_step"->"1","strength"->"0.8"))))),120.seconds).images.head
      assert(!PngMetadata.readImage(baseline.path).getRGB(0,0,64,64,null,0,64).sameElements(
        PngMetadata.readImage(mirrored.path).getRGB(0,0,64,64,null,0,64)),"latent mirroring must affect output")
      val graded=Await.result(services.queue.submit(Job(sharpParams).copy(alwaysOn=Seq(grading->
        Values(Map("enabled"->"true","recenter"->"true","r"->"0.4","g"->"-0.3","b"->"0.7","normalize"->"true"))))),120.seconds).images.head
      assert(!PngMetadata.readImage(baseline.path).getRGB(0,0,64,64,null,0,64).sameElements(
        PngMetadata.readImage(graded.path).getRGB(0,0,64,64,null,0,64)),"latent color grading must affect output")
      val progress=new java.util.concurrent.atomic.AtomicInteger()
      val stoppedJob=Job(sharpParams).copy(alwaysOn=Seq(antiBurn->Values(Map("enabled"->"true","skip_last"->"2","average"->"3"))))
      val unsubscribe=services.queue.addListener {
        case p: JobEvent.Progress if p.jobId==stoppedJob.id => progress.incrementAndGet();()
        case _ => ()
      }
      val early=try Await.result(services.queue.submit(stoppedJob),120.seconds).images.head finally unsubscribe()
      assertEquals(progress.get(),2)
      assert(!PngMetadata.readImage(baseline.path).getRGB(0,0,64,64,null,0,64).sameElements(
        PngMetadata.readImage(early.path).getRGB(0,0,64,64,null,0,64)),"anti-burn must affect output")
      val vectorValues=Values(Map("enabled"->"true","brightness"->"0.4","contrast"->"-0.7","saturation"->"0.8",
        "r"->"0.2","g"->"-0.3","b"->"0.6","method"->"N.Random","scaling"->"Cos","strength"->"1.3"))
      val vectorImage=Await.result(services.queue.submit(Job(sharpParams).copy(alwaysOn=Seq(vectorscope->vectorValues))),120.seconds).images.head
      val vectorReplay=Await.result(services.queue.submit(Job(sharpParams).copy(alwaysOn=Seq(vectorscope->vectorValues))),120.seconds).images.head
      val vectorPixels=PngMetadata.readImage(vectorImage.path).getRGB(0,0,64,64,null,0,64)
      assert(!vectorPixels.sameElements(PngMetadata.readImage(baseline.path).getRGB(0,0,64,64,null,0,64)))
      assert(vectorPixels.sameElements(PngMetadata.readImage(vectorReplay.path).getRGB(0,0,64,64,null,0,64)))
      val detailValues=Values(Map("enabled"->"true","mode"->"cond","amount"->"0.5","start_offset"->"0.3","end_offset"->"0.3"))
      val detailed=Await.result(services.queue.submit(Job(sharpParams).copy(alwaysOn=Seq(detail->detailValues))),120.seconds).images.head
      assert(!PngMetadata.readImage(baseline.path).getRGB(0,0,64,64,null,0,64).sameElements(
        PngMetadata.readImage(detailed.path).getRGB(0,0,64,64,null,0,64)),"detail daemon must affect output")
      val vectorBatch=sharpParams.copy(batchSize=2,hires=HiresParams(enabled=true,scale=2,secondPassSteps=3,denoisingStrength=.75))
      def vectorRun()=Await.result(services.queue.submit(Job(vectorBatch).copy(alwaysOn=Seq(vectorscope->vectorValues))),120.seconds).images
      val batch=vectorRun();val replayBatch=vectorRun()
      assertEquals(batch.size,2);assertEquals(replayBatch.size,2)
      for i <- batch.indices do
        val firstBatch=PngMetadata.readImage(batch(i).path);val secondBatch=PngMetadata.readImage(replayBatch(i).path)
        assertEquals((firstBatch.getWidth,firstBatch.getHeight),(128,128))
        assert(firstBatch.getRGB(0,0,128,128,null,0,128).sameElements(secondBatch.getRGB(0,0,128,128,null,0,128)))
      val hires=sharpParams.copy(hires=HiresParams(enabled=true,scale=2,secondPassSteps=3,denoisingStrength=.75))
      val plainHires=Await.result(services.queue.submit(Job(hires)),120.seconds).images.head
      val sharpValues=Values(Map("enabled"->"true","sharpness"->"1.5","scaling"->"Cos"))
      val sharpJob=Job(hires).copy(alwaysOn=Seq(resharpen->sharpValues))
      val sharpHires=Await.result(services.queue.submit(sharpJob),120.seconds).images.head
      val replay=Await.result(services.queue.submit(Job(hires).copy(alwaysOn=Seq(resharpen->sharpValues))),120.seconds).images.head
      val plainImage=PngMetadata.readImage(plainHires.path);val sharpImage=PngMetadata.readImage(sharpHires.path)
      assertEquals((sharpImage.getWidth,sharpImage.getHeight),(128,128))
      val sharpPixels=sharpImage.getRGB(0,0,128,128,null,0,128)
      assert(!sharpPixels.sameElements(plainImage.getRGB(0,0,128,128,null,0,128)))
      assert(sharpPixels.sameElements(PngMetadata.readImage(replay.path).getRGB(0,0,128,128,null,0,128)),"resharpen state must reset between jobs")
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
