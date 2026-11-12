ThisBuild / scalaVersion := "3.3.6"
ThisBuild / version := "0.1"
ThisBuild / organization := "sdfx"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-encoding", "utf8")

val upickleV = "4.1.0"
val munitV = "1.1.1"
val fxV = "21.0.6"

lazy val fxOs: String = {
  val n = System.getProperty("os.name").toLowerCase
  val arch = System.getProperty("os.arch")
  if (n.contains("win")) "win"
  else if (n.contains("mac")) { if (arch == "aarch64") "mac-aarch64" else "mac" }
  else "linux"
}
lazy val fxDeps = Seq("base", "graphics", "controls", "swing", "media").map(m => "org.openjfx" % s"javafx-$m" % fxV classifier fxOs)

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "com.lihaoyi" %% "upickle" % upickleV,
    "com.twelvemonkeys.imageio" % "imageio-webp" % "3.12.0",
    "org.scalameta" %% "munit" % munitV % Test
  ),
  // sbt 2 puts jars on the classpath by default; tests read fixture resources as files.
  exportJars := false,
  Test / fork := true,
  Test / baseDirectory := (ThisBuild / baseDirectory).value,
  Test / parallelExecution := false,
  Compile / run / fork := true,
  Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
  run / connectInput := true
)

lazy val core = project.settings(commonSettings, name := "sdfx-core", Test / javaOptions ++= Seq("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8"))

lazy val engine = project.dependsOn(core).settings(commonSettings, name := "sdfx-engine")

lazy val pythonEngine = project.dependsOn(engine).settings(commonSettings, name := "sdfx-python-engine")

lazy val scalaTorchEngine = project.dependsOn(engine).settings(
  commonSettings,
  name := "sdfx-scala-torch-engine",
  resolvers += "scala-torch-gh" at "https://raw.githubusercontent.com/scala-ml/scala-torch/maven-repo/",
  libraryDependencies += "com.github.kmizu" %% "scala-torch-models" % "0.1.3-gh51"
)

val nativeOnlyBuild = sys.env.get("SDFX_NATIVE_ONLY").exists(_.trim.equalsIgnoreCase("true"))

lazy val plugins = project.dependsOn(core, engine).settings(commonSettings, name := "sdfx-plugins",
  libraryDependencies ++= Seq("com.google.zxing" % "core" % "3.5.3", "com.google.zxing" % "javase" % "3.5.3"))

lazy val mcp = project
  .dependsOn((Seq[ClasspathDep[ProjectReference]](core, engine, plugins, scalaTorchEngine % Runtime) ++
    (if (nativeOnlyBuild) Seq.empty else Seq[ClasspathDep[ProjectReference]](pythonEngine % "runtime->compile")) )*)
  .settings(commonSettings, name := "sdfx-mcp")

/** Dev-only JVM client built on the official MCP Java SDK; replaces the Python scripts for acceptance runs. */
lazy val mcpClient = project
  .in(file("tools/mcp-client"))
  .settings(
    name := "sdfx-mcp-client",
    scalaVersion := (core / scalaVersion).value,
    libraryDependencies ++= Seq(
      "io.modelcontextprotocol.sdk" % "mcp" % "1.1.5",
      "com.lihaoyi" %% "upickle" % upickleV
    ),
    exportJars := false
  )

lazy val ui = project
  .dependsOn((Seq[ClasspathDep[ProjectReference]](core, engine, plugins, mcp, scalaTorchEngine % Runtime) ++
    (if (nativeOnlyBuild) Seq.empty else Seq[ClasspathDep[ProjectReference]](pythonEngine % Runtime)) )*)
  .settings(
    commonSettings,
    name := "sdfx-ui",
    libraryDependencies ++= fxDeps,
    Compile / mainClass := Some("sdfx.ui.Launcher"),
    run / javaOptions ++= Seq("-Dprism.allowhidpi=true", "-Xmx2g", "-Dfile.encoding=UTF-8"),
    Test / javaOptions ++= Seq("-Djava.awt.headless=true"),
    assembly / mainClass := Some("sdfx.ui.Launcher"),
    assembly / assemblyJarName := "stablediffusion-nui.jar",
    assembly / assemblyOutputPath := (ThisBuild / baseDirectory).value / "target" / "stablediffusion-nui.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)              => MergeStrategy.discard
      case x if x.endsWith("module-info.class")  => MergeStrategy.discard
      case _                                     => MergeStrategy.first
    }
  )

lazy val root = (project in file("."))
  .aggregate((Seq[ProjectReference](core, engine, scalaTorchEngine, plugins, mcp, ui) ++
    (if (nativeOnlyBuild) Seq.empty else Seq[ProjectReference](pythonEngine)) )*)
  .settings(name := "stablediffusion-nui", publish / skip := true)
