package sdfx.engine

import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration.*

/** Cross-process NUI protocol smoke; does not load a model or launch Python. */
class NativeCasImageToolSuite extends munit.FunSuite:
  test("EngineClient calls native CAS before loading a checkpoint") {
    val cp=sys.env.get("SCALATORCH_WORKER_CLASSPATH_FILE").orElse(sys.props.get("scalatorch.worker.classpath"))
    val model=sys.env.get("SCALATORCH_TINY_MODEL").orElse(sys.props.get("scalatorch.tiny.model"))
    assume(cp.nonEmpty && model.nonEmpty,"native worker distribution and tiny model required")
    val temp=Files.createTempDirectory("nui-native-cas")
    val scheduler=temp.resolve("scheduler.json");Files.writeString(scheduler,"{}")
    val image=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB)
    for y <- 0 until 2; x <- 0 until 3 do image.setRGB(x,y,0xff000000 | (x*80<<16) | (y*120<<8) | 32)
    val input=temp.resolve("input.png");ImageIO.write(image,"png",input.toFile)
    val supervisor=new EngineSupervisor(() => NativeTestLaunch(cp.get,Path.of(model.get),scheduler),maxRestarts=0)
    try
      assertEquals(supervisor.start().python,"")
      val client=new EngineClient(supervisor)
      val schedules=Await.result(client.raw("list_samplers",ujson.Obj()),20.seconds)("schedulers").arr.map(_.str).toSet
      assert(Set("Normal","Simple","SGM Uniform").subsetOf(schedules))
      val response=Await.result(client.raw("image_tool",ujson.Obj("tool"->"cas_upscale","image_path"->input.toString,
        "output_dir"->temp.toString,"scale"->2,"sharpness"->0.5)),20.seconds)
      val output=ImageIO.read(Path.of(response("path").str).toFile)
      assertEquals((output.getWidth,output.getHeight),(6,4))
      assertEquals(response("outputs").obj.size,0)
      assertEquals(response("info").obj.size,0)
      val loop=Await.result(client.raw("image_tool",ujson.Obj("tool"->"loopback_scaler","image_path"->input.toString,
        "output_dir"->temp.toString,"sharpness"->1.4,"brightness"->0.8,"detail"->"Low")),20.seconds)
      val finished=ImageIO.read(Path.of(loop("path").str).toFile)
      assertEquals((finished.getWidth,finished.getHeight),(3,2))
      assertEquals(loop("outputs").obj.size,0)
      val enhance=Await.result(client.raw("image_tool",ujson.Obj("tool"->"color_enhance","image_path"->input.toString,
        "output_dir"->temp.toString,"strength"->1.0,"percentile"->99.5)),20.seconds)
      val enhanced=ImageIO.read(Path.of(enhance("path").str).toFile)
      assertEquals((enhanced.getWidth,enhanced.getHeight),(3,2))
      assert(enhance("info").obj.contains("chroma_gain"))
      val colorReference=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB)
      for y <- 0 until 2; x <- 0 until 3 do colorReference.setRGB(x,y,0xff000000 | (32<<16) | (x*80<<8) | (y*100))
      val referencePath=temp.resolve("reference.png");ImageIO.write(colorReference,"png",referencePath.toFile)
      for method <- Seq("A1111","AdaIN","Wavelet") do
        val color=Await.result(client.raw("image_tool",ujson.Obj("tool"->"color_correction","image_path"->input.toString,
          "reference_path"->referencePath.toString,"output_dir"->temp.toString,"method"->method,"strength"->1.0)),20.seconds)
        val corrected=ImageIO.read(Path.of(color("path").str).toFile)
        assertEquals((corrected.getWidth,corrected.getHeight),(3,2))
        assertEquals(color("info")("method").str,method)
        assert(corrected.getRGB(0,0,3,2,null,0,3).toVector!=image.getRGB(0,0,3,2,null,0,3).toVector)
      val all=Await.result(client.raw("image_tool",ujson.Obj("tool"->"color_correction","image_path"->input.toString,
        "reference_path"->referencePath.toString,"output_dir"->temp.toString,"method"->"A1111",
        "strength"->1.0,"all_methods"->true)),20.seconds)
      assertEquals(all("outputs").obj.keySet.toSet,Set("AdaIN","Wavelet"))
      all("outputs").obj.foreach { (_,path) =>
        val comparison=ImageIO.read(Path.of(path.str).toFile)
        assertEquals((comparison.getWidth,comparison.getHeight),(3,2))
      }
    finally
      supervisor.close()
      val files=Files.walk(temp)
      try files.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p)) finally files.close()
  }
