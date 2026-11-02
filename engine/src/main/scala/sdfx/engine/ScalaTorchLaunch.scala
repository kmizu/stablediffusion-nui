package sdfx.engine

import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

/** Explicit development launch of the Python-free worker, shared by desktop and MCP. */
object ScalaTorchLaunch:
  private val keys=Set("SDFX_SCALATORCH_CLASSPATH_FILE","SDFX_SCALATORCH_MODEL",
    "SDFX_SCALATORCH_SCHEDULER","SDFX_SCALATORCH_JAVA")
  def requested(env: Map[String,String]=sys.env): Boolean=keys.exists(env.contains)
  def fromEnvironment(env: Map[String,String]=sys.env): Option[EngineLaunch]=
    if !requested(env) then None
    else
      def required(name: String): Path=
        val value=env.getOrElse(name,"").trim
        require(value.nonEmpty,s"native engine requires $name")
        Path.of(value).toAbsolutePath.normalize()
      val cpFile=required("SDFX_SCALATORCH_CLASSPATH_FILE")
      val model=required("SDFX_SCALATORCH_MODEL")
      val config=required("SDFX_SCALATORCH_SCHEDULER")
      require(Files.isRegularFile(cpFile) && Files.size(cpFile)<=1048576,"native classpath file is missing or too large")
      require(Files.isDirectory(model),"native model directory is missing")
      require(Files.isRegularFile(config),"native scheduler config is missing")
      val classpath=Files.readString(cpFile).trim
      require(classpath.nonEmpty && !classpath.contains('\n') && !classpath.contains('\r'),"native classpath must be one nonempty line")
      classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1).foreach { entry =>
        require(entry.nonEmpty && Path.of(entry).isAbsolute && Files.exists(Path.of(entry)),s"native classpath entry missing or not absolute: $entry")
      }
      val javaExe=if env.contains("SDFX_SCALATORCH_JAVA") then required("SDFX_SCALATORCH_JAVA")
        else Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
      require(Files.isRegularFile(javaExe),"native Java executable is missing (Java 25 required)")
      Some(EngineLaunch(Seq(javaExe.toString,"--enable-native-access=ALL-UNNAMED","-cp",classpath,
        "scalatorch.models.worker.NativeWorkerMain",model.toString,config.toString),model,helloTimeout=30.seconds))
