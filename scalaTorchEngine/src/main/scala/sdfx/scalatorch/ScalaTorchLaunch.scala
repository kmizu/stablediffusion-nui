package sdfx.engine.scalatorch

import sdfx.engine.{EngineBackendProvider,EngineLaunch}

import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

/** Launch adapter for the Python-free worker, shared by desktop and MCP. */
object ScalaTorchLaunch:
  private val keys=Set("SDFX_SCALATORCH_CLASSPATH_FILE","SDFX_SCALATORCH_MODEL",
    "SDFX_SCALATORCH_SCHEDULER","SDFX_SCALATORCH_JAVA")
  def requested(env: Map[String,String]=sys.env): Boolean=keys.exists(env.contains) ||
    sys.props.get("sdfx.engine.backend").exists(_.trim.equalsIgnoreCase("scala-torch"))
  private def managedClasspathEntries(): Option[String] =
    val resource="scalatorch/models/worker/NativeWorkerMain.class"
    val loader=Option(getClass.getClassLoader).getOrElse(ClassLoader.getSystemClassLoader)
    if loader.getResource(resource)==null then None
    else
      val paths=scala.collection.mutable.LinkedHashSet.empty[Path]
      def addUrl(url: java.net.URL): Unit =
        if url!=null && "file".equalsIgnoreCase(url.getProtocol) then
          try
            val p=Path.of(url.toURI).toAbsolutePath.normalize()
            if Files.exists(p) then paths+=p
          catch case _: Throwable => ()
      var current: ClassLoader=loader
      while current!=null do
        current match
          case ucl: java.net.URLClassLoader => ucl.getURLs.foreach(addUrl)
          case _ => ()
        current=current.getParent
      Option(System.getProperty("java.class.path")).getOrElse("").trim
        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1).iterator
        .map(_.trim).filter(_.nonEmpty).foreach { raw =>
          try
            val p=Path.of(raw).toAbsolutePath.normalize()
            if Files.exists(p) then paths+=p
          catch case _: Throwable => ()
        }
      Option.when(paths.nonEmpty)(paths.map(_.toString).mkString(java.io.File.pathSeparator))

  def fromEnvironment(env: Map[String,String]=sys.env,appHome: Option[Path]=None): Option[EngineLaunch]=
    if !requested(env) then None
    else
      def required(name: String): Path=
        val value=env.getOrElse(name,"").trim
        require(value.nonEmpty,s"native engine requires $name")
        Path.of(value).toAbsolutePath.normalize()
      def configured(name: String,default: Option[Path]): Path=env.get(name) match
        case Some(value) =>
          require(value.trim.nonEmpty,s"native engine requires $name")
          Path.of(value.trim).toAbsolutePath.normalize()
        case None => default.filter(Files.exists(_)).map(_.toAbsolutePath.normalize()).getOrElse(
          throw IllegalArgumentException(s"native engine requires $name"))
      val model=required("SDFX_SCALATORCH_MODEL")
      val defaultCpFile=appHome.map(_.resolve("scala-torch/worker-classpath.txt")).filter(Files.exists(_))
      val explicitCpFile=if env.contains("SDFX_SCALATORCH_CLASSPATH_FILE") || defaultCpFile.nonEmpty then
        Some(configured("SDFX_SCALATORCH_CLASSPATH_FILE",defaultCpFile))
      else None
      val singleFile=Files.isRegularFile(model) && Vector(".safetensors",".ckpt").exists(
        model.getFileName.toString.toLowerCase(java.util.Locale.ROOT).endsWith)
      val config=if singleFile && !env.contains("SDFX_SCALATORCH_SCHEDULER") then None else
        Some(configured("SDFX_SCALATORCH_SCHEDULER",Some(model.resolve("scheduler/scheduler_config.json"))))
      explicitCpFile.foreach { cpFile =>
        require(Files.isRegularFile(cpFile) && Files.size(cpFile)<=1048576,"native classpath file is missing or too large")
      }
      require(Files.isDirectory(model) || singleFile,"native model directory or checkpoint file is missing")
      require(config.forall(Files.isRegularFile(_)),"native scheduler config is missing")
      val tokenizer=if !singleFile then None else
        val explicit=env.get("SDFX_SCALATORCH_TOKENIZER").map { value =>
          require(value.trim.nonEmpty,"native tokenizer path is empty")
          Path.of(value.trim).toAbsolutePath.normalize()
        }
        val candidates=explicit.toSeq ++ appHome.toSeq.map(_.resolve("scala-torch/tokenizer")) ++
          Seq(model.getParent.resolve("tokenizer"))
        val selected=candidates.find(path => Files.isRegularFile(path.resolve("vocab.json")) &&
          Files.isRegularFile(path.resolve("merges.txt")))
        require(selected.nonEmpty,"single-file SD1 requires tokenizer/vocab.json and tokenizer/merges.txt")
        require(explicit.isEmpty || selected.contains(explicit.get),"configured native tokenizer is missing vocabulary or merges")
        selected
      val classpathEntries=explicitCpFile match
        case Some(cpFile) =>
          val classpath=Files.readString(cpFile).trim
          require(classpath.nonEmpty && !classpath.contains('\n') && !classpath.contains('\r'),"native classpath must be one nonempty line")
          classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1).map { entry =>
            require(entry.nonEmpty,s"native classpath contains an empty entry: $entry")
            val path=Path.of(entry)
            val resolved=(if path.isAbsolute then path else cpFile.getParent.resolve(path)).toAbsolutePath.normalize()
            require(Files.exists(resolved),s"native classpath entry missing: $resolved")
            resolved.toString
          }.mkString(java.io.File.pathSeparator)
        case None =>
          managedClasspathEntries().getOrElse(
            throw IllegalArgumentException("native engine requires SDFX_SCALATORCH_CLASSPATH_FILE or scala-torch-models on the classpath"))
      val bundledJava=appHome.map(_.resolve("scala-torch/jre/bin/").resolve(
        if sys.props("os.name").startsWith("Windows") then "java.exe" else "java"))
      val javaExe=env.get("SDFX_SCALATORCH_JAVA").map { value =>
        require(value.trim.nonEmpty,"native engine requires SDFX_SCALATORCH_JAVA")
        Path.of(value.trim).toAbsolutePath.normalize()
      }.orElse(bundledJava.filter(Files.isRegularFile(_))).getOrElse(
        Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java"))
      require(Files.isRegularFile(javaExe),"native Java executable is missing (Java 25 required)")
      Some(EngineLaunch(Seq(javaExe.toString,"-XX:MaxRAMPercentage=50.0","--enable-native-access=ALL-UNNAMED","-cp",classpathEntries,
        "scalatorch.models.worker.NativeWorkerMain",model.toString,config.fold("-")(_.toString)),
        if singleFile then model.getParent else model,
        env=env.filter((key,_) => key.startsWith("SDFX_SCALATORCH_")) ++
          tokenizer.map(path => "SDFX_SCALATORCH_TOKENIZER" -> path.toString),helloTimeout=30.seconds))

final class ScalaTorchBackendProvider extends EngineBackendProvider:
  val name="scala-torch"
  def requested(environment: Map[String,String]): Boolean=ScalaTorchLaunch.requested(environment)
  def usesNativeBackend(settings: sdfx.core.config.Settings,environment: Map[String,String]): Boolean =
    environment.get("SDFX_ENGINE_BACKEND") match
      case Some(value) => value.trim.equalsIgnoreCase("scala-torch")
      case None => settings.engineBackend match
        case "scala-torch" => true
        case "python" => false
        case _ => ScalaTorchLaunch.requested(environment)
  def launch(home: sdfx.core.config.AppHome,settings: sdfx.core.config.Settings,dryRun: Boolean,
    environment: Map[String,String]): EngineLaunch =
    require(!dryRun,"Scala native engine does not implement dry-run mode")
    val saved=Seq(
      "SDFX_SCALATORCH_CLASSPATH_FILE"->settings.scalaTorchClasspath,
      "SDFX_SCALATORCH_MODEL"->settings.scalaTorchModel,
      "SDFX_SCALATORCH_SCHEDULER"->settings.scalaTorchScheduler,
      "SDFX_SCALATORCH_JAVA"->settings.scalaTorchJava,
      "SDFX_SCALATORCH_UNET_DEVICE"->Some(settings.scalaTorchUnetDevice),
      "SDFX_SCALATORCH_VAE_DEVICE"->Some(settings.scalaTorchVaeDevice),
      "SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS"->Some(settings.scalaTorchUnetOffloadWeights.toString),
      "SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"->Some(settings.scalaTorchVaeOffloadWeights.toString))
    val configured=saved.foldLeft(environment) { case (env,(key,value)) =>
      if env.contains(key) then env else value.map(v => env.updated(key,v)).getOrElse(env)
    }
    ScalaTorchLaunch.fromEnvironment(configured,Some(home.root)).getOrElse(
      throw new IllegalArgumentException("scala-torch backend requires its classpath, model and scheduler configuration"))
