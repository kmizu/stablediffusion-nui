package sdfx.engine

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import scala.concurrent.duration.*
import sdfx.core.config.{AppHome,Settings}
import sdfx.core.png.PngMetadata

class NativePreprocessGeneratorSuite extends munit.FunSuite:
  test("NUI Generator previews and saves native preprocess results with metadata") {
    val cp=sys.props.get("scalatorch.worker.classpath").orElse(sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE"))
    val model=sys.props.get("scalatorch.tiny.model").orElse(sys.env.get("SCALATORCH_TINY_MODEL"))
    assume(cp.nonEmpty && model.nonEmpty,"native worker and tiny model required")
    val root=Path.of(model.get).toAbsolutePath
    val home=AppHome(Files.createTempDirectory("nui-native-preprocess"))
    val config=home.root.resolve("scheduler.json");Files.writeString(config,"{}")
    val services=new AppServices(home,Settings(),dryRun=false,builtins=Nil,
      launchOverride=Some(()=>NativeTestLaunch(cp.get,root,config)),persistSettings=false,installShutdownHook=false)
    try
      assertEquals(services.start().python,"")
      val input=home.root.resolve("input.png")
      val image=new BufferedImage(32,24,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 24; x <- 0 until 32 do image.setRGB(x,y,((x*7)<<16)|((y*9)<<8)|64)
      PngMetadata.write(image,input,Seq("parameters"->"original prompt"))
      val preview=services.generator.preprocess(input,"invert")
      assertEquals(PngMetadata.readImage(preview).getRGB(0,0)&0xffffff,0xffffbf)
      val result=services.generator.preprocessExtras(input,"invert",Map.empty,64,false)
      val texts=PngMetadata.readTexts(result)
      assert(texts("parameters").contains("ControlNet Preprocessor"))
      assert(texts("parameters").contains("'module': 'invert'"))
      assertEquals(texts("Source parameters"),"original prompt")
      val edges=services.generator.preprocessExtras(input,"canny",Map("low_threshold"->80.0,"high_threshold"->160.0),64,false)
      val edgeImage=PngMetadata.readImage(edges)
      assertEquals((edgeImage.getWidth,edgeImage.getHeight),(85,64))
      assert(PngMetadata.readTexts(edges)("parameters").contains("'module': 'canny'"))
      assert(!Files.exists(home.pythonDir))
    finally
      services.shutdown()
      val paths=Files.walk(home.root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.deleteIfExists(_)) finally paths.close()
  }
