# Python-free scala-torch distribution

## Intent

Build a Windows NUI distribution that can start and generate images without a
Python interpreter, Python source payload, or PyTorch provider module. Keep the
existing Python-capable build available and keep backend implementations behind
the existing `EngineBackendProvider` SPI so either provider can be replaced.

## Goals

- Add an opt-in sbt build profile that excludes `pythonEngine` from MCP/UI runtime
  dependencies, root aggregation and the assembled jar.
- Make the profile's Windows package select scala-torch and bundle its worker
  manifest and Java 25 runtime.
- Omit Python source, requirements, and Python launch/setup choices from this
  package; retain legal notices and non-Python assets that the native app needs.
- Fail packaging if the jar still advertises the Python provider or has no
  scala-torch provider.
- Preserve today's default build and existing `-PreferScalaTorch` behavior.

## Non-goals

- Removing Python support from the repository or the regular dual-backend jar.
- Claiming full feature parity with the Python backend.
- Changing which inference backend the regular development build selects.

## Approaches

1. **Recommended: conditional SBT runtime graph plus explicit native-only
   package mode.** The native-only property removes `pythonEngine` at assembly
   time; package mode verifies the provider service entry, omits Python payload,
   and selects scala-torch. This makes the artifact itself independent of
   Python while preserving the existing distribution.
2. **Only omit Python files during packaging.** This still leaves the Python
   provider in the jar and can cause bootstrap or selection to require Python.
3. **Make scala-torch the default for every build.** This changes existing user
   behavior and does not establish that the native jar can be built without the
   Python provider.

## Architecture and constraints

The shared `engine` module continues to expose only the generic SPI and process
protocol. SBT's `SDFX_NATIVE_ONLY=true` environment variable controls only runtime module
composition and must be present when the SBT server starts; stop a server with
different settings before building. The native-only app image starts with
`-Dsdfx.engine.backend=scala-torch`, bundles the relative worker classpath and
Java 25 runtime, and contains no `python/` directory or `PythonBackendProvider`.
The package may still contain ordinary application data and legal notices.

## Acceptance

- Regular `ui/assembly` still includes both provider modules.
- Native-only root `compile` aggregation excludes `pythonEngine`.
- Native-only assembly contains the scala-torch service provider and not the
  Python provider or Python engine classes.
- Native-only packaging omits Python sources and requirements, includes bundled
  worker/JRE files, and rejects an incompatible jar before producing an image.
- The extracted native-only app launches MCP without a Python executable or
  Python-related environment variables and completes a local CUDA generation.
- NUI changes remain on `feat/scala-torch-native-engine`; no commit is created.

## Implementation calls

- The build profile uses a process-scoped environment variable because sbt 2's
  thin client does not reliably forward JVM properties to its background server.
- `-NativeOnly` implies native backend selection and requires the existing
  scala-torch distribution and JDK 25 arguments.
- The regular package keeps its current Python payload behavior.
