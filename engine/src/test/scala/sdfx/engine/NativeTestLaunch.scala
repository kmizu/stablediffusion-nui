package sdfx.engine

import java.nio.file.{Files,Path}
import scala.concurrent.duration.*

/** Test-only direct worker launch; keeps the engine project independent of the optional backend module. */
private[engine] object NativeTestLaunch:
  def apply(classpathFile: String,model: Path,scheduler: Path): EngineLaunch =
    val cpFile=Path.of(classpathFile).toAbsolutePath.normalize()
    val entries=Files.readString(cpFile).trim.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)
      .map(entry => { val p=Path.of(entry); (if p.isAbsolute then p else cpFile.getParent.resolve(p)).toAbsolutePath.normalize().toString })
      .mkString(java.io.File.pathSeparator)
    val javaExe=Path.of(sys.props("java.home"),"bin",if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
    EngineLaunch(Seq(javaExe.toString,"-XX:MaxRAMPercentage=50.0","--enable-native-access=ALL-UNNAMED","-cp",entries,
      "scalatorch.models.worker.NativeWorkerMain",model.toAbsolutePath.normalize().toString,
      scheduler.toAbsolutePath.normalize().toString),model.toAbsolutePath.normalize(),helloTimeout=30.seconds)
