package sdfx.pythonengine

import munit.FunSuite
import sdfx.core.config.{AppHome, Settings}
import sdfx.engine.EngineBackendProvider
import java.nio.file.Files

class PythonEnvSuite extends FunSuite:
  test("Python backend is registered through the engine provider SPI") {
    val providers = java.util.ServiceLoader.load(classOf[EngineBackendProvider]).iterator()
    assert(providers.hasNext)
    assertEquals(providers.next().name, "python")
  }

  test("bootstrap installs torch requirements from its dedicated CUDA index") {
    val home = AppHome(Files.createTempDirectory("python-engine-test"))
    try
      Files.createDirectories(home.pythonDir)
      Files.writeString(home.pythonDir.resolve(PythonEnv.TorchRequirements), "torch\n")
      Files.writeString(home.pythonDir.resolve(PythonEnv.Requirements), "numpy\n")
      val commands = PythonEnv.bootstrapCommands(home)
      assertEquals(commands.size, 3)
      assert(commands(1).containsSlice(Seq("-r", PythonEnv.TorchRequirements, "--index-url", PythonEnv.TorchIndexUrl)))
      assert(commands(2).containsSlice(Seq("-r", PythonEnv.Requirements)))
      assert(commands.forall(_.contains("--progress-bar")))
    finally
      val stream = Files.walk(home.root)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally stream.close()
  }

  test("Python engine launch owns Python environment variables and flags") {
    val home = AppHome(Files.createTempDirectory("python-engine-launch"))
    try
      val launch = PythonEnv.engineLaunch(home, Settings(allowCustomCode = true), dryRun = true)
      assert(launch.env.get("PYTHONUNBUFFERED").contains("1"))
      assert(launch.env.get("PYTHONUTF8").contains("1"))
      assert(launch.env.get("SDFX_ALLOW_CODE").contains("1"))
      assert(launch.command.takeRight(3) == Seq("-m", "sdfx", "--dry-run"))
    finally Files.deleteIfExists(home.root)
  }
