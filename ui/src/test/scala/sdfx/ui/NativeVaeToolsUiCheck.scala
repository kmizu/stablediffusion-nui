package sdfx.ui

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.image.ImageView
import javafx.stage.Stage
import java.awt.image.BufferedImage
import java.nio.file.{Files,Path}
import java.util.concurrent.{Callable,CountDownLatch,FutureTask,TimeUnit}
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import sdfx.core.config.{AppHome,ModelDirs,Settings}
import sdfx.engine.{AppServices,EngineLaunch}
import sdfx.plugins.StandardPlugins
import sdfx.ui.vm.UiState

/** Opt-in native VAE Tools GUI integration; no Python engine. */
object NativeVaeToolsUiCheck:
  private def fx[A](body: => A): A =
    val task=new FutureTask[A](new Callable[A]:
      def call(): A=body)
    Platform.runLater(task)
    task.get(30,TimeUnit.SECONDS)
  private def find(root: Node,id: String): Option[Node] =
    if root==null then None
    else if root.getId==id then Some(root)
    else
      val children: Seq[Node]=root match
        case pane: TabPane => pane.getTabs.asScala.flatMap(t=>Option(t.getContent)).toSeq
        case pane: TitledPane => Option(pane.getContent).toSeq
        case pane: ScrollPane => Option(pane.getContent).toSeq
        case parent: javafx.scene.Parent => parent.getChildrenUnmodifiable.asScala.toSeq
        case _ => Nil
      children.iterator.flatMap(child=>find(child,id)).take(1).toSeq.headOption
  def main(args: Array[String]): Unit =
    require(args.length==2,"worker classpath file and model directory required")
    val cpFile=Path.of(args(0)).toAbsolutePath
    val model=Path.of(args(1)).toAbsolutePath
    val classpath=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry=>{val p=Path.of(entry);(if p.isAbsolute then p else cpFile.getParent.resolve(p)).normalize().toString})
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin","java.exe")
    val scheduler=if Files.exists(model.resolve("euler-explicit.json")) then "euler-explicit.json" else "euler-demo.json"
    val launch=EngineLaunch(Seq(javaExe.toString,"-XX:MaxRAMPercentage=50.0","--enable-native-access=ALL-UNNAMED",
      "-cp",classpath,"scalatorch.models.worker.NativeWorkerMain",model.toString,model.resolve(scheduler).toString),model,
      env=Map("SDFX_SCALATORCH_VAE_DEVICE"->"cuda","SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"->"true"),helloTimeout=30.seconds)
    val home=AppHome(Files.createTempDirectory("nui-vae-gui"))
    val services=new AppServices(home,Settings(dtype="fp32",vramMode="cpu",lastModel=None,lastVae=None,
      modelDirs=ModelDirs(checkpoints=Seq(model.getParent.toString))),false,StandardPlugins.withOwnAxes,
      launchOverride=Some(()=>launch),persistSettings=false,installShutdownHook=false)
    val started=new CountDownLatch(1)
    Platform.startup(()=>{Platform.setImplicitExit(false);started.countDown()})
    require(started.await(30,TimeUnit.SECONDS))
    var window: MainWindow=null
    try
      require(services.start().python.isEmpty)
      services.loadCheckpoint(model.toString,remember=false)
      val source=home.root.resolve("source.png")
      val size=sys.env.get("SCALATORCH_VAE_UI_SIZE").map(_.toInt).getOrElse(16)
      require(size>=8 && size<=1024)
      val input=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB)
      for y<-0 until size; x<-0 until size do
        input.setRGB(x,y,((x*255/(size-1))<<16)|((y*255/(size-1))<<8)|128)
      ImageIO.write(input,"png",source.toFile)
      window=fx { val w=new MainWindow(new Stage(),services,UiState(selectedTab="vae_tools"),CliOptions());w.show();w }
      def node[A <: Node](id: String): A=find(window.root,id).getOrElse(throw IllegalStateException(s"Missing $id")).asInstanceOf[A]
      fx {
        node[TextArea]("vae-tools-input").setText(source.toString)
        node[TextField]("vae-tools-output").setText(home.root.toString)
        node[ComboBox[String]]("vae-tools-operation").setValue("encode+decode")
        node[Button]("vae-tools-run").fire()
      }
      val deadline=System.nanoTime()+120.seconds.toNanos
      while fx(node[TextArea]("vae-tools-report").getText.isEmpty) && System.nanoTime()<deadline do Thread.sleep(100)
      val report=fx(ujson.read(node[TextArea]("vae-tools-report").getText))
      require(report("results")(0)("status").str=="complete",report.render())
      val preview=Path.of(report("results")(0)("preview_path").str)
      require(Files.isRegularFile(preview))
      val output=ImageIO.read(preview.toFile)
      require(output.getWidth==size && output.getHeight==size,"preview dimensions differ")
      val imageDeadline=System.nanoTime()+30.seconds.toNanos
      while fx(node[ImageView]("vae-tools-preview").getImage==null) && System.nanoTime()<imageDeadline do Thread.sleep(100)
      require(fx(node[ImageView]("vae-tools-preview").getImage!=null),"preview not displayed")
      sys.env.get("SCALATORCH_APPROX_NN_WEIGHTS").foreach { weights =>
        fx {
          node[TextArea]("vae-tools-report").clear()
          node[ComboBox[String]]("vae-tools-approximation").setValue("Approx NN")
          node[TextField]("vae-tools-approx-weights").setText(weights)
          node[Button]("vae-tools-run").fire()
        }
        val nnDeadline=System.nanoTime()+120.seconds.toNanos
        while fx(node[TextArea]("vae-tools-report").getText.isEmpty) && System.nanoTime()<nnDeadline do Thread.sleep(100)
        val nn=fx(ujson.read(node[TextArea]("vae-tools-report").getText))
        require(nn("results")(0)("status").str=="complete",nn.render())
        require(nn("encoding_approximation").str=="Full",nn.render())
        val shape=nn("results")(0)("statistics")("shape").arr.map(_.num.toInt)
        val image=ImageIO.read(Path.of(nn("results")(0)("path").str).toFile)
        require(image.getWidth==shape(3)*2 && image.getHeight==shape(2)*2,"Approx NN output size mismatch")
        fx(node[ComboBox[String]]("vae-tools-approximation").setValue("Full"))
        println("Native Approx NN VAE Tools UI passed")
      }
      val protectedSource=home.root.resolve("protected-source.png")
      val pass="native-ui-test-password"
      sdfx.core.png.ScrambledPng.write(input,protectedSource,Seq.empty,Some(pass))
      fx {
        services.imageScrambleSession.unlock(pass);window.ctx.imageAccessChanged()
        node[TextArea]("vae-tools-report").clear()
        node[TextArea]("vae-tools-input").setText(protectedSource.toString)
        node[ComboBox[String]]("vae-tools-operation").setValue("encode")
        node[ComboBox[String]]("vae-tools-representation").setValue("RGBA")
        node[CheckBox]("vae-tools-plaintext-latent").setSelected(false)
        node[Button]("vae-tools-run").fire()
      }
      val deniedDeadline=System.nanoTime()+120.seconds.toNanos
      while !fx(node[Label]("vae-tools-status").getText.toLowerCase.contains("consent")) && System.nanoTime()<deniedDeadline do Thread.sleep(100)
      require(fx(node[Label]("vae-tools-status").getText.toLowerCase.contains("consent")),"missing consent rejection")
      fx {
        node[CheckBox]("vae-tools-plaintext-latent").setSelected(true)
        node[Button]("vae-tools-run").fire()
      }
      val protectedDeadline=System.nanoTime()+120.seconds.toNanos
      while fx(node[TextArea]("vae-tools-report").getText.isEmpty) && System.nanoTime()<protectedDeadline do Thread.sleep(100)
      val protectedReport=fx(ujson.read(node[TextArea]("vae-tools-report").getText))
      require(protectedReport("results")(0)("status").str=="complete",protectedReport.render())
      require(!fx(node[CheckBox]("vae-tools-plaintext-latent").isSelected),"consent not reset")
      val protectedPath=Path.of(protectedReport("results")(0)("path").str)
      require(sdfx.core.png.ScrambledPng.read(protectedPath,Some(pass)).scrambled,"output is not protected")
      val protectedPreview=Path.of(protectedReport("results")(0)("preview_path").str)
      require(sdfx.core.png.ScrambledPng.read(protectedPreview,Some(pass)).scrambled,"preview is not protected")
      val expectedPreview=sdfx.core.png.ScrambledPng.read(protectedPreview,Some(pass)).image
      def previewMatches: Boolean=fx {
        val shown=node[ImageView]("vae-tools-preview").getImage
        if shown==null || shown.getPixelReader==null then false
        else
          val actual=javafx.embed.swing.SwingFXUtils.fromFXImage(shown,null)
          actual.getWidth==expectedPreview.getWidth && actual.getHeight==expectedPreview.getHeight &&
            actual.getRGB(0,0,actual.getWidth,actual.getHeight,null,0,actual.getWidth).sameElements(
              expectedPreview.getRGB(0,0,expectedPreview.getWidth,expectedPreview.getHeight,null,0,expectedPreview.getWidth))
      }
      val protectedPreviewDeadline=System.nanoTime()+30.seconds.toNanos
      while !previewMatches && System.nanoTime()<protectedPreviewDeadline do Thread.sleep(100)
      require(previewMatches,"protected preview not displayed correctly")
      fx {services.imageScrambleSession.lock();window.ctx.imageAccessChanged()}
      val lockDeadline=System.nanoTime()+30.seconds.toNanos
      while fx(node[ImageView]("vae-tools-preview").getImage!=null) && System.nanoTime()<lockDeadline do Thread.sleep(100)
      require(fx(node[ImageView]("vae-tools-preview").getImage==null),"locked preview was not cleared")
      println(s"Native protected VAE Tools UI passed; output=$protectedPath")
      require(!Files.exists(home.pythonDir))
      println(s"Native VAE Tools UI passed; preview=$preview")
    finally
      if window!=null then fx {window.dispose();window.stage.close()}
      services.shutdown();Platform.exit()
