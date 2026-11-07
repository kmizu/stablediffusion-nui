# Stable Diffusion NUI — Windows v0.1 package

These instructions describe the v0.1 package. Earlier
v0.01 / v0.02 assets retain their original names (for example,
`SDFX-v0.02-windows-x64.zip` and `SDFX/SDFX.exe`). Internal Python/Scala `sdfx`
names, `SDFX_HOME`, `SDFX_MCP_*` and existing configuration files remain compatible.
See the [repository releases](https://github.com/kmizu/stablediffusion-nui/releases)
for the assets actually published; the checks below do not assert publication.

## Use

Extract `SDNUI-v0.1-windows-x64.zip` to a writable folder (for example, under your user profile), then run `SDNUI\SDNUI.exe`. The bundled Java runtime needs no separately installed Java. Keep the extracted directory together: `app`, `runtime`, `python`, and `models` sit next to the executable in the regular package. The native-only variant below omits `python` and includes `scala-torch` instead. The launcher defaults the app home to this extracted SDNUI directory, even if the current directory or `SDFX_HOME` points to another checkout. An explicit `--home DIR` overrides that default. Do not run directly inside the ZIP. A writable location is needed for settings and outputs.

Python 3.10–3.12 is needed for the engine; Python and torch are not included. On the first normal launch, the setup dialog can create a virtual environment and download dependencies, or use a Python environment you choose. The torch download is large and uses the CUDA 12.8 wheel index. `SDNUI.exe --dry-run` exercises the UI with placeholder generation but still needs a working Python interpreter. NVIDIA GPU and appropriate drivers are recommended for real generation.

### Optional scala-torch package (Python is not required at runtime)

The Windows packager can include a relocatable scala-torch worker and a separate Java 25 runtime. Build the scala-torch distribution first with `sbt 'models/workerDistribution'`, then pass that directory and a Java 25 x64 JDK to `scripts/package_windows.ps1`:

```powershell
pwsh scripts/package_windows.ps1 `
  -JdkHome 'C:/path/to/jdk-21' `
  -Jar target/stablediffusion-nui.jar `
  -Version 0.1 `
  -ScalaTorchWorkerDistribution 'C:/path/to/scala-torch/target/out/jvm/scala-3.3.8/scala-torch-models/worker-distribution' `
  -ScalaTorchJdkHome 'C:/path/to/jdk-25' `
  -PreferScalaTorch
```

`-PreferScalaTorch` selects scala-torch and skips Python bootstrap for that app image, while retaining the Python provider and sources. On first use, open Settings → Engine and choose a local Diffusers model directory. NUI uses that model's `scheduler/scheduler_config.json` by default. The native feature gaps and model-quality limitations documented in `docs/scala-torch-native-engine.md` still apply.

### Native-only package (Python provider and source omitted)

To produce a package that does not contain the Python provider or Python source,
build the matching native-only jar first. The ordinary sbt build includes both
backends; `SDFX_NATIVE_ONLY=true` removes `pythonEngine` from the runtime graph.
The package script rejects jars with a Python provider, then omits the Python
source and requirements payload:

```powershell
sbt shutdown
$env:SDFX_NATIVE_ONLY = 'true'
try {
  sbt -batch 'ui/assembly'
  if ($LASTEXITCODE -ne 0) { throw 'Native-only assembly failed.' }
} finally {
  Remove-Item Env:SDFX_NATIVE_ONLY -ErrorAction SilentlyContinue
  sbt shutdown
}
pwsh scripts/package_windows.ps1 `
  -JdkHome 'C:/path/to/jdk-21' `
  -Jar target/stablediffusion-nui.jar `
  -Version 0.1 `
  -ScalaTorchWorkerDistribution 'C:/path/to/scala-torch/target/out/jvm/scala-3.3.8/scala-torch-models/worker-distribution' `
  -ScalaTorchJdkHome 'C:/path/to/jdk-25' `
  -NativeOnly
```

The native-only package selects scala-torch at startup, hides Python backend
controls, and bundles its worker manifest and Java 25 runtime. It still requires
a local Diffusers model and has the same native feature and quality limitations.

To use existing Civitai downloads, place the model files in the corresponding `models` subdirectories. To use an existing reForge installation, open Settings → Model folders → **Add A1111/reForge install...** and select the installation; see the main project README for the supported model folders. Image-generation checkpoints are not included. The NudeNet NSFW Censor detector is included at `models/nudenet/best.onnx`; its pinned hash is verified during packaging. Preserve the model creators' respective licences.

`LICENSE`, `THIRD_PARTY_NOTICES.md`, `DISTRIBUTION_NOTICES.md` and the referenced `licenses/*.txt` cover the app and listed dependencies; the bundled runtime's own notices are in `runtime/legal`. This is a portable app-image, not an installer; deleting its extracted folder removes its local data as well.


The full user manual (Japanese and English) is at <https://kmizu.github.io/stablediffusion-nui/en/>.

## Use from AI agents (MCP)

`SDNUI.exe --mcp` starts a headless MCP server over stdio with the bundled Java runtime, without opening the window. For the regular package, set up Python and model folders first (in the GUI or in `<home>/config/settings.json`). The native-only package needs only its scala-torch model settings. Then register it with your MCP client, for example:

```powershell
claude mcp add stablediffusion-nui -- C:/apps/SDNUI/SDNUI.exe --mcp --home C:/apps/SDNUI
```

`.mcp.json.example` shows the same connection for `.mcp.json`. Add `--dry-run` to try it without a GPU, or `--http [PORT]` to serve Streamable HTTP instead of stdio. To let agents drive the running GUI instead, enable Settings → MCP → **Enable MCP HTTP server** and connect to `http://127.0.0.1:7861/mcp`. Tools, options and limits: `mcp/README.md` and the manual's MCP page.

## WD v3 batch interrogation (partial)

This optional Img2Img workflow tags each input image using a local WD v3 model before generating. It does not implement all backends or options of the original batch interrogator. Models are not bundled or downloaded automatically.

1. In the **same Python environment selected in Settings → Engine**, install the optional ONNX inspection package and ensure ONNX Runtime is installed. For the app-created Windows environment, from the extracted `SDNUI` folder run:

   ```powershell
   & .\python\.venv\Scripts\python.exe -m pip install onnx==1.21.0 onnxruntime==1.23.2
   ```

   If Settings specifies a different Python executable, use that executable instead. `onnxruntime==1.23.2` is also in the standard requirements; the explicit command above installs or verifies both packages. Installing them requires network access or locally supplied wheels.
2. Obtain the two files from the fixed official revision of `SmilingWolf/wd-vit-tagger-v3`: [model.onnx](https://huggingface.co/SmilingWolf/wd-vit-tagger-v3/resolve/7f6b584d0bd3f55c4531f14ba3d4761b2bccdc0f/model.onnx) and [selected_tags.csv](https://huggingface.co/SmilingWolf/wd-vit-tagger-v3/resolve/7f6b584d0bd3f55c4531f14ba3d4761b2bccdc0f/selected_tags.csv). The accepted SHA-256 values are:

   - `model.onnx`: `35f23693620b668f4d53fd3c62bf65e40af739bc52c7eb0fbc49258b58d065b6`
   - `selected_tags.csv`: `298633d94d0031d2081c0893f29c82eab7f0df00b08483ba8f29d1e979441217`

   These hashes identify the locally reviewed candidate; they have not been independently authenticated against the publisher. In PowerShell, check each file with `Get-FileHash -Algorithm SHA256 <path>`. The worker checks both hashes at startup.
3. Keep both as regular local files in ordinary folders. Hugging Face cache snapshot paths can be symbolic links; copy the target bytes into normal `.onnx` and `.csv` files and select those copies. Paths containing links or junctions are rejected.
4. In **Settings → WD v3 (partial)** select the two files and restart the engine. Reopen Settings to see whether the worker reported WD v3 configured at its last start. Then open **Img2Img → WD v3 batch (partial)**, enable it, choose an image folder and a new output folder name, and submit. Existing output folders are not replaced.

The configured status confirms the files and dependencies passed startup checks; it does not guarantee that ONNX Runtime can load or execute on the machine. Scripts, other extensions, LoRA, ControlNet, masks and hires are unsupported for this batch mode.

## Build the distribution

Build on Windows x64 with Git, PowerShell and a Windows x64 JDK 21 or 25. First assemble the final, verified Windows JavaFX fat JAR from the intended source revision (`sbt -batch ui/assembly`); packaging does not build or download anything. Then, from any working directory:

```powershell
& 'path\to\stablediffusion-nui\scripts\package_windows.ps1' -Version '0.1' -JdkHome 'C:\Program Files\Java\jdk-21' -Jar 'path\to\stablediffusion-nui\target\stablediffusion-nui.jar' -OutputRoot 'path\to\release-output'
```

`-Version` accepts two or three numeric components of one to three digits each; `-JdkHome` defaults to `JAVA_HOME`; `-Jar` defaults to this checkout's `target/stablediffusion-nui.jar`; `-OutputRoot` defaults to `dist/windows-v<Version>` in this checkout. Paths with spaces are supported. Each invocation creates a fresh `build-<UUID>` directory inside the output root and retains its staging files. Its versioned ZIP and `SHA256SUMS.txt` are the release assets. Tracked Python source and selected data files, model README skeletons, the pinned NudeNet detector and tracked `licenses/*.txt` files are copied, along with the requirement files and app and JVM distribution notices; no image-generation checkpoints, venv, settings or outputs are copied. Review the source checkout and JAR before packaging; this script does not verify their provenance.

jpackage creates a classpath app-image and its default linked runtime; the release tag and ZIP use `v0.1`. Passing `-Version` changes the jpackage version, output directory default and ZIP name; it does not modify the Git tag. Refer to [Oracle's JDK 21 packaging overview](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html). This script does not sign binaries or publish a release.

## Release verification

Check `SHA256SUMS.txt` against the ZIP, inspect its contents and licences, and extract it to a clean writable path containing spaces. From a different working directory, with Java removed from PATH, including tests where the working directory and `SDFX_HOME` point to another valid checkout, run:

```powershell
& 'C:\path with spaces\SDNUI\SDNUI.exe' --dry-run --screenshot 'C:\path with spaces\smoke.png' --exit-after 15
```

Confirm the process exits successfully and inspect the screenshot; use a working Python for dry-run. Also test the normal first-run setup in an isolated extracted copy and run actual generation with a supported Python environment, GPU and real model. Test model discovery and generation using the user's existing reForge/Civitai files through the native app. Verify the final release source revision, ZIP hash and tag before publication. These are release checks to perform, not claims that they have already passed.
