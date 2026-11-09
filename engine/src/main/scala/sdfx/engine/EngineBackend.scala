package sdfx.engine

import sdfx.core.config.{AppHome,Settings}
import scala.jdk.CollectionConverters.*

/** Engine implementation boundary. UI, Generator and MCP depend on EngineClient only. */
private[engine] trait EngineBackend:
  def name: String
  def launch(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]): EngineLaunch

private[engine] object EngineBackend:
  private lazy val providers=java.util.ServiceLoader.load(classOf[EngineBackendProvider]).iterator().asScala.toVector
  def availableProviders: Vector[String] = providers.map(_.name).distinct.sorted
  def defaultProvider: String =
    if availableProviders.contains("python") then "python"
    else if availableProviders.size==1 then availableProviders.head
    else throw new IllegalArgumentException("no unambiguous default engine backend is installed")
  private def backend(value: EngineBackendProvider): EngineBackend = new EngineBackend:
    val name=value.name
    def launch(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]): EngineLaunch =
      value.launch(home,settings,dryRun,environment)
  private def provider(name: String)=backend(providers.find(_.name==name).getOrElse(
    throw new IllegalArgumentException(s"engine backend module is not installed: $name")))

  /** Backend modules are discovered through the generic provider SPI. */
  def select(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]=sys.env): EngineBackend =
    environment.get("SDFX_ENGINE_BACKEND").orElse(sys.props.get("sdfx.engine.backend"))
      .map(_.trim.toLowerCase(java.util.Locale.ROOT)) match
      case Some("python") => provider("python")
      case Some("scala-torch") => provider("scala-torch")
      case Some(other) => throw new IllegalArgumentException(s"unsupported engine backend: $other")
      case None => providers.find(_.requested(environment)).map(backend).getOrElse(provider(defaultProvider))

  /** Whether startup should skip Python setup for the selected non-Python backend. */
  def usesNativeBackend(settings: Settings,environment: Map[String,String]): Boolean =
    environment.get("SDFX_ENGINE_BACKEND") match
      case Some(value) => value.trim.equalsIgnoreCase("scala-torch")
      case None => settings.engineBackend match
        case "scala-torch" => true
        case "python" => false
        case _ => sys.props.get("sdfx.engine.backend") match
          case Some(value) => value.trim.equalsIgnoreCase("scala-torch")
          // A classpath with no backend module installed (for example the engine module's own tests) has no
          // native backend to start; only ask for the default when at least one backend is installed.
          case None => providers.exists(_.usesNativeBackend(settings,environment)) ||
            (providers.nonEmpty && defaultProvider=="scala-torch")

  /** dtype and VRAM mode to send with `load_model`. The native worker executes in fp16 (CUDA half precision) or fp32; it has no bf16, so
    * bf16 is sent as fp16 (the closest half precision). U-Net / VAE placement is configured separately there, `vram_mode` is not used. */
  def loadModes(settings: Settings,environment: Map[String,String]=sys.env): (String,String) =
    if !usesNativeBackend(settings,environment) then (settings.dtype,settings.vramMode)
    else (settings.dtype.trim.toLowerCase(java.util.Locale.ROOT) match
      case "fp32" | "float32" => "fp32"
      case _ => "fp16", settings.vramMode)

  def launch(home: AppHome,settings: Settings,dryRun: Boolean,environment: Map[String,String]=sys.env): EngineLaunch =
    val configured0=if environment.contains("SDFX_ENGINE_BACKEND") || settings.engineBackend=="auto" then environment
      else environment.updated("SDFX_ENGINE_BACKEND",settings.engineBackend)
    select(home,settings,dryRun,configured0).launch(home,settings,dryRun,configured0)

/** Startup capability exposed to the UI without exposing backend implementations. */
object EngineSelection:
  /** Backend implementations present in this application distribution. */
  def availableBackends: Vector[String] = EngineBackend.availableProviders

  def usesNativeBackend(settings: Settings,environment: Map[String,String]=sys.env): Boolean =
    EngineBackend.usesNativeBackend(settings,environment)

/** Setup operations supplied by the selected engine backend. */
object EngineSetup:
  private def selected(settings: Settings, environment: Map[String,String]): EngineBackendProvider =
    val name = environment.get("SDFX_ENGINE_BACKEND").orElse(sys.props.get("sdfx.engine.backend"))
      .map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty)
      .orElse(Option.when(settings.engineBackend != "auto")(settings.engineBackend))
      .getOrElse(EngineBackend.defaultProvider)
    java.util.ServiceLoader.load(classOf[EngineBackendProvider]).iterator().asScala
      .find(_.name == name).getOrElse(throw new IllegalArgumentException(s"engine backend module is not installed: $name"))

  def needsSetup(home: AppHome, settings: Settings, environment: Map[String,String]=sys.env): Boolean =
    selected(settings, environment).needsSetup(home, settings)

  def start(home: AppHome, settings: Settings, log: String => Unit,
      environment: Map[String,String]=sys.env): EngineSetupTask =
    selected(settings, environment).setup(home, settings, log)
