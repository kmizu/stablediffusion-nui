# Python-free scala-torch distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce an opt-in Windows NUI package whose jar and payload do not depend on Python.

**Architecture:** An SBT property removes `pythonEngine` from the MCP/UI runtime graph and sets scala-torch as the package backend. The package script verifies the provider service list, omits Python source/requirements, and retains the bundled worker and Java 25 runtime.

**Tech Stack:** Scala 3, sbt-assembly, PowerShell, jpackage, Java 25.

**Spec:** `docs/superpowers/specs/2026-11-03-native-only-distribution-design.md`

## Global Constraints

- Keep shared engine code independent of backend implementation modules.
- Keep the default dual-backend build and package behavior intact.
- Keep NUI work on `feat/scala-torch-native-engine`; preserve unrelated dirty files and do not commit.
- Do not add or run automated tests; build and inspect the generated artifact.

## Review Focus

- Native-only service loading must still discover exactly the scala-torch provider; inspect the assembled service descriptor.
- Existing Python-selected settings in a native-only artifact must fail explicitly without trying Python; inspect the generic SPI and UI backend choices.
- Package validation must reject missing or wrong provider entries before jpackage runs; inspect the validation order and messages.
- The regular package must retain Python source payload; compare its existing branch and the new conditional path.
- Relocated worker manifest and Java 25 runtime must remain included; inspect extracted package paths.

---

### Task 1: Optional provider composition and UI choices

**Files:**
- Modify: `build.sbt`
- Modify: `engine/src/main/scala/sdfx/engine/EngineBackend.scala`
- Modify: `ui/src/main/scala/sdfx/ui/view/SettingsDialog.scala`

- [x] Add an SBT `SDFX_NATIVE_ONLY=true` build switch and conditionally exclude `pythonEngine` from root aggregation, `mcp` and `ui` runtime dependencies while retaining the ordinary graph by default.
- [x] Expose installed provider names from the generic SPI and populate the backend selector from installed providers, preserving `auto` only when more than one provider exists.
- [x] Build the regular assembly and native-only assembly; inspect native-only `META-INF/services/sdfx.engine.EngineBackendProvider` and confirm no Python provider/classes are present.

### Task 2: Python-free Windows package mode

**Files:**
- Modify: `scripts/package_windows.ps1`
- Modify: `docs/WINDOWS_RELEASE.md`

- [x] Add `-NativeOnly`; require the Scala-Torch worker distribution and Java 25 JDK, set scala-torch selection, and validate the jar's provider service descriptor before creating staging output.
- [x] In native-only mode, omit tracked `python/` source and requirements payload while preserving notices, model assets, app image, worker classpath, and bundled JRE; leave regular packaging unchanged.
- [x] Build and extract the package; inspect jar provider entries and payload to prove no `python/` files or Python provider are present.
- [x] Launch extracted MCP with Python-related environment variables removed and a missing Python executable; generate with the local CUDA SD1.5 model and inspect the PNG.

### Task 3: User-facing build and acceptance record

**Files:**
- Modify: `README.md`
- Modify: `docs/scala-torch-native-engine.md`

- [x] Document the native-only assembly and packaging commands, their Java requirements, and the distinction from the dual-backend distribution.
- [x] Record package hash, provider/payload inspection, and extracted runtime generation evidence; retain explicit full-parity limitations.

## Review

After building and inspecting the artifact, review only the changed build/package paths for unintended Python dependencies and confirm regular package behavior remains unchanged.

## Execution ledger

- Ruling: use `SDFX_NATIVE_ONLY=true` in the process environment instead of an SBT JVM property — the sbt 2 thin client did not reliably forward JVM flags to its background server — cost if wrong: callers must set the documented environment variable for the server's lifetime.
- `sbt -batch "ui/compile"`: passed with the ordinary dual-provider graph.
- Native-only `ui/compile` and `ui/assembly`: passed with `SDFX_NATIVE_ONLY=true`; jar service descriptor contains only `sdfx.engine.scalatorch.ScalaTorchBackendProvider`, with zero Python engine classes.
- Native-only root aggregation omits `pythonEngine`; ordinary root aggregation keeps it.
- Ordinary `ui/assembly`: passed; service descriptor contains Python and scala-torch providers and Python engine classes.
- PowerShell parser: `scripts/package_windows.ps1` parsed without errors.
- `-NativeOnly` package: ZIP SHA256 `d48a4a598a6bc75fb7f5535a6ce0473403e2f08939b88230dd811ee2b9510a5f`; 828 entries, zero Python paths/requirements, worker manifest and bundled Java 25 present. The regular package also retained `python/sdfx/__main__.py` and all three requirements files.
- Extracted package MCP: 26 tools, local SD1.5 discovery/load, 256x256 four-step CUDA generation, Python and SDFX environment variables removed, PATH restricted to Windows system directories, launcher exit 0.
- Review found that the native jar guard did not match arbitrary Python-named classes. The final guard rejects the Python module package, the provider class, and Python-named `.class`/`.tasty` entries; the rebuilt final package passed this check.
- The build switch is read when the SBT server loads `build.sbt`; package instructions shut down an existing server before the native-only build and shut it down afterward.
- Final review: a read-only reviewer found the initial class-name guard too narrow; it was widened and the native-only package was rebuilt and rechecked. No second reviewer pass was requested.
- Automated tests were not added or run; validation used compile, assembly, package inspection and extracted-package runtime generation.
