package sdfx.engine

import sdfx.core.config.{AppHome,Settings}

/** Engine implementation boundary. UI, Generator and MCP depend on EngineClient only. */
private[engine] sealed trait EngineBackend:
  def name: String
  def launch(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]): EngineLaunch

private[engine] object EngineBackend:
  private object Python extends EngineBackend:
    val name="python"
    def launch(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]): EngineLaunch =
      PythonEnv.engineLaunch(home,settings,dryRun)
  private object ScalaTorch extends EngineBackend:
    val name="scala-torch"
    def launch(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]): EngineLaunch =
      require(!dryRun,"Scala native engine does not implement dry-run mode")
      ScalaTorchLaunch.fromEnvironment(environment).getOrElse(
        throw new IllegalArgumentException("scala-torch backend requires its classpath, model and scheduler configuration"))

  /** Python stays the default; explicit Scala selection is one replaceable module boundary.
    * The preexisting Scala launch variables remain a backwards-compatible opt-in.
    */
  def select(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]=sys.env): EngineBackend =
    environment.get("SDFX_ENGINE_BACKEND").map(_.trim.toLowerCase(java.util.Locale.ROOT)) match
      case Some("python") => Python
      case Some("scala-torch") => ScalaTorch
      case Some(other) => throw new IllegalArgumentException(s"unsupported engine backend: $other")
      case None => if ScalaTorchLaunch.requested(environment) then ScalaTorch else Python

  /** Whether startup should skip Python setup for the selected non-Python backend. */
  def usesNativeBackend(settings: Settings,environment: Map[String,String]): Boolean =
    settings.engineBackend=="scala-torch" || ScalaTorchLaunch.requested(environment) ||
      environment.get("SDFX_ENGINE_BACKEND").exists(_.trim.equalsIgnoreCase("scala-torch"))

  def launch(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]=sys.env): EngineLaunch =
    val configured0=if environment.contains("SDFX_ENGINE_BACKEND") || settings.engineBackend=="auto" then environment
      else environment.updated("SDFX_ENGINE_BACKEND",settings.engineBackend)
    val saved=Seq(
      "SDFX_SCALATORCH_CLASSPATH_FILE"->settings.scalaTorchClasspath,
      "SDFX_SCALATORCH_MODEL"->settings.scalaTorchModel,
      "SDFX_SCALATORCH_SCHEDULER"->settings.scalaTorchScheduler,
      "SDFX_SCALATORCH_JAVA"->settings.scalaTorchJava,
      "SDFX_SCALATORCH_UNET_DEVICE"->Some(settings.scalaTorchUnetDevice),
      "SDFX_SCALATORCH_VAE_DEVICE"->Some(settings.scalaTorchVaeDevice),
      "SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS"->Some(settings.scalaTorchUnetOffloadWeights.toString),
      "SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS"->Some(settings.scalaTorchVaeOffloadWeights.toString))
    val configured=saved.foldLeft(configured0) { case (env,(key,value)) =>
      if env.contains(key) then env else value.map(v => env.updated(key,v)).getOrElse(env)
    }
    select(home,settings,dryRun,configured).launch(home,settings,dryRun,configured)

/** Startup capability exposed to the UI without exposing backend implementations. */
object EngineSelection:
  def usesNativeBackend(settings: Settings,environment: Map[String,String]=sys.env): Boolean =
    EngineBackend.usesNativeBackend(settings,environment)
