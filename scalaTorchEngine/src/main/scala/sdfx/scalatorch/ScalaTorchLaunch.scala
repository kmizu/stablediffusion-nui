package sdfx.engine.scalatorch

import sdfx.engine.{EngineBackendProvider,EngineLaunch}

import java.nio.file.{Files,Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Launch adapter for the Python-free worker, shared by desktop and MCP. */
object ScalaTorchLaunch:
  private def exeName: String = if sys.props("os.name").startsWith("Windows") then "java.exe" else "java"
  private val keys=Set("SDFX_SCALATORCH_CLASSPATH_FILE","SDFX_SCALATORCH_MODEL",
    "SDFX_SCALATORCH_SCHEDULER")
  /** `scala-torch` folder of the installation that contains the running jar (`<install>/app/<jar>` -> `<install>/scala-torch`).
    * Needed because the user data home (`--home`, MCP) can be anywhere while the bundled worker pieces stay with the program. */
  lazy val installScalaTorch: Option[Path] =
    try
      val jar=Path.of(classOf[ScalaTorchLaunchMarker].getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath.normalize()
      Option(jar.getParent).flatMap(a => Option(a.getParent)).map(_.resolve("scala-torch")).filter(Files.isDirectory(_))
    catch case _: Throwable => None

  /** True when an NVIDIA driver is installed (nvcuda.dll on Windows, libcuda.so on Linux). */
  lazy val cudaAvailable: Boolean =
    if sys.props.getOrElse("os.name","").startsWith("Windows") then
      Files.isRegularFile(Path.of(sys.env.getOrElse("SystemRoot","C:\\Windows"),"System32","nvcuda.dll"))
    else Seq("/usr/lib/x86_64-linux-gnu/libcuda.so.1","/usr/lib64/libcuda.so.1","/usr/lib/libcuda.so.1").exists(p => Files.exists(Path.of(p)))

  /** Model the worker starts with when none is configured: the last used checkpoint, else the first checkpoint in the model folders.
    * The model can be changed later through load_model. */
  def defaultModel(settings: sdfx.core.config.Settings,home: Option[Path]=None): Option[String] =
    val dirs=(settings.modelDirs.checkpoints.map(Path.of(_)) ++ home.toSeq.map(_.resolve("models").resolve("Stable-diffusion"))).filter(Files.isDirectory(_))
    def files(d: Path)=try Files.walk(d,3).iterator().asScala.filter(f => Files.isRegularFile(f) &&
      Vector(".safetensors",".ckpt").exists(f.getFileName.toString.toLowerCase(java.util.Locale.ROOT).endsWith)).toVector.sortBy(_.toString)
      catch case _: Throwable => Vector.empty[Path]
    val all=dirs.flatMap(files)
    settings.lastModel.flatMap(n => all.find(f => f.getFileName.toString==n || f.getFileName.toString.stripSuffix(".safetensors").stripSuffix(".ckpt")==n))
      .orElse(all.headOption).map(_.toString)

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
      val bundled=appHome.toSeq.map(_.resolve("scala-torch")) ++ installScalaTorch.toSeq
      val defaultCpFile=bundled.map(_.resolve("worker-classpath.txt")).find(Files.exists(_))
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
        val candidates=explicit.toSeq ++ bundled.map(_.resolve("tokenizer")) ++
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
      val bundledJava=bundled.map(_.resolve("jre/bin").resolve(exeName)).find(Files.isRegularFile(_))
      val javaExe=env.get("SDFX_SCALATORCH_JAVA").map { value =>
        require(value.trim.nonEmpty,"native engine requires SDFX_SCALATORCH_JAVA")
        val p=Path.of(value.trim).toAbsolutePath.normalize()
        // a folder (for example a JAVA_HOME or its bin) is accepted by looking for the executable inside it
        if Files.isDirectory(p) then
          Seq(p.resolve(exeName),p.resolve("bin").resolve(exeName)).find(Files.isRegularFile(_)).getOrElse(p)
        else p
      }.orElse(bundledJava).getOrElse(
        Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java"))
      require(Files.isRegularFile(javaExe),"native Java executable is missing (Java 25 required)")
      Some(EngineLaunch(Seq(javaExe.toString,"-XX:MaxRAMPercentage=50.0","--enable-native-access=ALL-UNNAMED","-cp",classpathEntries,
        "scalatorch.models.worker.NativeWorkerMain",model.toString,config.fold("-")(_.toString)),
        if singleFile then model.getParent else model,
        env=env.filter((key,_) => key.startsWith("SDFX_SCALATORCH_")) ++
          tokenizer.map(path => "SDFX_SCALATORCH_TOKENIZER" -> path.toString),helloTimeout=30.seconds))

private final class ScalaTorchLaunchMarker

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
    def device(value: String): String =
      if value != "auto" then value else if ScalaTorchLaunch.cudaAvailable then "cuda" else "cpu"
    val model=settings.scalaTorchModel.orElse(ScalaTorchLaunch.defaultModel(settings,Some(home.root)))
    if model.isEmpty && !environment.contains("SDFX_SCALATORCH_MODEL") then
      throw new IllegalArgumentException("No checkpoint found for the scala-torch engine. Add a model folder in Settings > Model folders " +
        "(or copy a .safetensors checkpoint into " + home.root.resolve("models").resolve("Stable-diffusion") + "), then press Restart.")
    val saved=Seq(
      "SDFX_SCALATORCH_CLASSPATH_FILE"->settings.scalaTorchClasspath,
      "SDFX_SCALATORCH_MODEL"->model,
      "SDFX_SCALATORCH_SCHEDULER"->settings.scalaTorchScheduler,
      "SDFX_SCALATORCH_JAVA"->settings.scalaTorchJava,
      "SDFX_SCALATORCH_UNET_DEVICE"->Some(device(settings.scalaTorchUnetDevice)),
      "SDFX_SCALATORCH_VAE_DEVICE"->Some(device(settings.scalaTorchVaeDevice)),
      "SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS"->Some(settings.scalaTorchUnetOffloadWeights.toString),
      "SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"->Some(settings.scalaTorchVaeOffloadWeights.toString),
      // memory budgets verified on SD1.5 and SDXL (the worker's 2 GiB session default is too small for SDXL)
      "SDFX_SCALATORCH_UNET_MAX_BYTES"->Some((8L<<30).toString),
      "SDFX_SCALATORCH_VAE_MAX_BYTES"->Some((8L<<30).toString),
      "SDFX_SCALATORCH_MAX_ATTENTION_SCORE_ELEMENTS"->Some((1L<<30).toString))
    val configured=saved.foldLeft(environment) { case (env,(key,value)) =>
      if env.contains(key) then env else value.map(v => env.updated(key,v)).getOrElse(env)
    }
    ScalaTorchLaunch.fromEnvironment(configured,Some(home.root)).getOrElse(
      throw new IllegalArgumentException("scala-torch backend requires its classpath, model and scheduler configuration"))
