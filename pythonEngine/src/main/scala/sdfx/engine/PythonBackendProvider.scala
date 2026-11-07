package sdfx.engine

import sdfx.core.config.{AppHome, Settings}
import sdfx.pythonengine.PythonEnv
import sdfx.engine.EngineSetupTask

/** Python and its PyTorch/diffusers environment are isolated in this backend module. */
final class PythonBackendProvider extends EngineBackendProvider:
  val name = "python"
  def requested(environment: Map[String, String]): Boolean = false
  def usesNativeBackend(settings: Settings, environment: Map[String, String]): Boolean = false
  def launch(home: AppHome, settings: Settings, dryRun: Boolean,
      environment: Map[String, String]): EngineLaunch = PythonEnv.engineLaunch(home, settings, dryRun)
  override def needsSetup(home: AppHome, settings: Settings): Boolean = PythonEnv.needsBootstrap(home)
  override def setup(home: AppHome, settings: Settings, log: String => Unit): EngineSetupTask =
    val task = PythonEnv.bootstrapAsync(home, log, settings)
    new EngineSetupTask(task.result, () => task.cancel(), () => task.isCancelled)
