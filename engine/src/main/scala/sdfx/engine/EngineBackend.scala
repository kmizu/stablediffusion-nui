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

  def launch(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]=sys.env): EngineLaunch =
    val configured=if environment.contains("SDFX_ENGINE_BACKEND") || settings.engineBackend=="auto" then environment
      else environment.updated("SDFX_ENGINE_BACKEND",settings.engineBackend)
    select(home,settings,dryRun,configured).launch(home,settings,dryRun,configured)
