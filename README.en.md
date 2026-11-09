# Stable Diffusion NUI

**English** | [日本語](README.md)

Your local models, in a native UI. From image generation to batch preview, the gallery and ZIP export.

Repository: [kmizu/stablediffusion-nui](https://github.com/kmizu/stablediffusion-nui). Formerly named Stable Diffusion FX / SDFX.

**Manual: <https://kmizu.github.io/stablediffusion-nui/en/>** (Japanese: <https://kmizu.github.io/stablediffusion-nui/>)

![The native SDNUI window. Prompt and generation settings on the left; the generated image, a 20-image gallery and a ZIP save button on the right.](docs/images/sdnui-desktop.png)

*An actual SDNUI screen: the result of a batch of 4 images at a time x 5 runs.*

---

## What is this?

A desktop app for Stable Diffusion that runs locally.

- UI: Scala 3 + JavaFX 21 (the `ui` module)
- MCP server: lets AI agents call image generation (the `mcp` module)
- Inference engine: Python + diffusers (`python/sdfx`). It is launched as a child process by the UI/MCP and communicates over stdin/stdout using JSON Lines
- Build: sbt 2.0.12 + sbt-assembly (a single fat jar, `target/stablediffusion-nui.jar`)

It is self-contained in this folder. It does not depend on the parent directory's reForge.

## Features

- txt2img / img2img / Inpaint (mask drawing, undo/redo, clipboard paste)
- Hires. fix
- LoRA (`<lora:name:weight>` tags, LoRA picker), VAE selection, Clip skip
- ControlNet (choose the model and preprocessor)
- Styles, variation seeds, batches, a queue, and a live preview during generation (approx / taesd)
- Gallery, PNG Info (reads the generation parameters embedded in an image and sends them to each tab), Extras (upscaling)
- Prompt editing: A1111's `(emphasis:1.2)` syntax, `BREAK`, 75-token chunks, weight adjustment with Ctrl+Up/Down
- [Tag autocomplete](docs/tag-autocomplete.md): shows suggestions from a local CSV and completes only the tag being typed with Enter / Tab
- [Named generation states](docs/generation-states.md): save, list, restore and delete per mode. Stored separately from settings presets
- Dark/light theme
- Plugins (see below)
- MCP server (stdio / HTTP)

### WD v3 batch (partial support)

The Img2Img **WD v3 batch (partial)** creates WD v3 tags for each image in the input folder and then runs img2img. The model is neither bundled nor downloaded automatically. The Python environment used in Settings needs `onnx==1.21.0` and `onnxruntime==1.23.2`. For the pinned versions of the model and tag CSV, the hashes, and the setup and restart steps, see the [Windows release guide](docs/WINDOWS_RELEASE.md#wd-v3-batch-interrogation-partial). Not every feature of the original batch extension is supported.

### Temporal Lab

Temporal Lab provides DFI, which stabilizes an already generated image sequence against a reference image, as well as Deflicker, Fuse for image sequences, and frame extraction from video. It refers to the screens and algorithms of [Abysz LAB](https://github.com/AbyszOne/Abysz-LAB-Ext/tree/deff0d4b4a208f862299b14b93e088f613373153); the licence and sources are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Exact numerical agreement with the original implementation is not guaranteed. Inter Blur is applied to processing in this app. Video extraction uses timestamps based on frame numbers, and results are saved to a new folder without overwriting existing output. Processing has limits such as image size and frame count.

### Standard plugins

All of reForge's standard scripts and built-in extensions have been rewritten and bundled. See [docs/PLUGINS.md](docs/PLUGINS.md) for the mapping table.

- **Script** (choose one): X/Y/Z plot, Prompt matrix, Prompts from file, Loopback, SD upscale, Poor man's outpainting,
  Outpainting mk2, img2img alternative test, Stable Video Diffusion, Zero123, Custom code (only when allowed in settings)
- **Extras** (process the initial image, or each generated image): Upscale, Restore faces, Caption (BLIP / WD14), Flipped copies,
  Split oversized, Auto-sized crop, Focal point crop
- **AlwaysOn** (always applied, individually switchable ON/OFF): FreeU, Dynamic Thresholding, APG, Mahiro, RescaleCFG, PAG, SAG, HyperTile,
  Kohya HRFix (Deep Shrink), MultiDiffusion, Advanced Model Sampling, Latent Modifier, Random Resolutions,
  Reference only, Revision, IP-Adapter, ControlNet-LLLite, PhotoMaker, Soft Inpainting, Fooocus Inpaint, Restore faces,
  Never OOM
- **Core features**: LoRA, ControlNet and its preprocessing (canny / depth / Marigold / recolor / tile / inpaint / controlnet_aux family),
  upscalers such as LDSR, SwinIR and SCUNet, and prompt bracket checking

External plugins can be added just by putting a jar in `plugins-ext/` (see below).

## Requirements

- JDK 17 or later (`java` from `JAVA_HOME` or `PATH`)
- Python 3.10-3.12 (a dedicated venv is created automatically on first run)
- An NVIDIA GPU is recommended (torch is installed as a CUDA 12.8 build). Without a GPU you can still try the UI with `--dry-run`
- sbt if you build it yourself (not needed if a jar already exists)
- Windows / Linux / macOS (JavaFX pulls in the per-OS jar at build time. Build a jar for another OS on that OS)

## Windows release v0.1

Once you have the Windows x64 `SDNUI-v0.1-windows-x64.zip`, extract it to a writable folder and launch `SDNUI\SDNUI.exe`.
The Java runtime is bundled. Python 3.10-3.12 is required separately; on the first normal launch you can set up a dedicated environment and the dependencies. Checkpoints for image generation are not bundled. The detection model for NudeNet NSFW Censor is bundled. You can reuse an existing model folder via Settings → Model folders → **Add A1111/reForge install...**. For detailed steps, see [Windows release](docs/WINDOWS_RELEASE.md).

**The Windows package since v0.11 starts on the Python-free scala-torch engine.** No environment variables are needed: it starts with the last used checkpoint (or the first one in your model folders) and uses an NVIDIA GPU automatically when present. The Python engine can still be selected in Settings.

You can also build a dedicated package that omits the Python provider and Python sources and starts with scala-torch only. For the steps and the current feature limitations, see [Windows distribution without Python](docs/WINDOWS_RELEASE.md#native-only-package-python-provider-and-source-omitted).

The older v0.01 / v0.02 releases keep the former name SDFX. For compatibility, the Python module `sdfx`, `SDFX_HOME` / `SDFX_MCP_*`, `-Dsdfx.home` and the existing settings file names have not been changed.

## Quick start

```
# 1. Build (produces target/stablediffusion-nui.jar)
scripts\build.bat          # Linux/macOS: scripts/build.sh   (it runs sbt -batch ui/assembly)

# 2. Launch
run.bat                    # Linux/macOS: ./run.sh
```

`run.bat` / `run.sh` look for Java 17 or later and, if there is no jar, build it with sbt before launching.
Options:

| Option | Meaning |
|---|---|
| `--dry-run` | Starts the Python engine with `--dry-run`. It does not load torch/diffusers and returns placeholder images. For checking the UI and for CI |
| `--home DIR` | Specifies the app root (the folder containing `python/` `models/` `config/`). Normally unnecessary (`run.*` passes it automatically) |
| `--mcp` | Runs as an MCP server (stdio) without starting the GUI (see below) |

Hidden development flags: `--screenshot <png>` (saves the screen after launch), `--exit-after <seconds>`, `--autogen`, `--demo-mask`, `--tab <name>`.

### First launch (Python setup)

If it is not dry-run, `settings.json` has no `python_exe`, and `python/.venv` has not been created (or
`python/requirements*.txt` has changed), a setup dialog opens.

1. Creates `python/.venv` (looks for the system's `py -3.12` / `py -3.10` / `python3` / `python`)
2. Installs `python/requirements-torch.txt` (torch / torchvision) from `https://download.pytorch.org/whl/cu128`
3. Installs `python/requirements.txt` (diffusers etc.)

torch is large, so this takes time. The dialog also lets you choose "Continue in dry-run" or "Specify your own Python"
(if you specify one, it is saved as `python_exe`). To do it manually:

```
cd python
python -m venv .venv
.venv/Scripts/pip install -r requirements-torch.txt --index-url https://download.pytorch.org/whl/cu128
.venv/Scripts/pip install -r requirements.txt     # on Linux/macOS use .venv/bin/pip
```

### Dry-run mode

`run.bat --dry-run` needs neither a GPU nor models. The Generate button produces a placeholder PNG with a fixed seed,
and progress, preview, gallery and even PNG Info all work end to end. With `--mcp --dry-run`, MCP works the same way.

## Where to put models

Place them by type under `models/` (searched recursively).

```
models/checkpoints/   Checkpoints
models/vae/           VAE
models/lora/          LoRA
models/controlnet/    ControlNet
models/embeddings/    Textual Inversion
models/upscalers/     ESRGAN / SwinIR / SCUNet / DAT-family upscalers (LDSR is downloaded automatically)
models/face_restore/  GFPGAN / CodeFormer (Restore faces)
models/svd/           Stable Video Diffusion (a single file or a diffusers folder)
models/zero123/       Zero123 family (diffusers folder)
models/photomaker/    PhotoMaker v1 adapter (photomaker-v1.bin)
models/taesd/         TAESD (for preview, optional)
```

As in Forge, IP-Adapter, ControlNet-LLLite and Fooocus inpaint patches go in `models/controlnet/`.
The image encoders for Revision and IP-Adapter, BLIP, LDSR and Marigold are fetched from Hugging Face on first use.
Only if you use PhotoMaker, additionally run `python/.venv/Scripts/python -m pip install --no-deps "git+https://github.com/TencentARC/PhotoMaker.git"`.

### Using an existing A1111 / reForge models folder

No copying is needed (it is referenced read-only). Click the gear at the top right → Settings → **Model folders** tab → **Add A1111/reForge install...**
and choose the install location (e.g. `D:\stable-diffusion-webui-reForge`); the following folders are added together:

`models/Stable-diffusion`, `models/VAE`, `models/Lora` and `models/LyCORIS`, `models/ControlNet`, `embeddings`,
`models/ESRGAN` and `models/RealESRGAN`

You can also add or remove folders per type. They are stored in `model_dirs` in `config/settings.json` (absolute paths, or paths
relative to the app root).

When loading with `from_single_file`, the SD1.5 / SDXL base configuration and tokenizer are fetched from Hugging Face (first time only).
Once fetched, you can run offline (see Troubleshooting).

## Settings file

`config/settings.json` (created on first launch, not tracked by git). It can be edited from the Settings dialog.

| Key | Default | Description |
|---|---|---|
| `model_dirs` | the `models/*` above | Search folders per type |
| `output_dir` | `outputs` | Output destination (relative paths are based on the app root) |
| `python_exe` | `null` | Python for the engine. If empty, `python/.venv`, or else the system Python |
| `dtype` | `fp16` | `fp16` / `bf16` / `fp32` |
| `vram_mode` | `gpu` | VRAM mode: `gpu` / `model_offload` / `sequential_offload` / `auto` (see Troubleshooting) |
| `vae_tiling` | `false` | Tile the VAE decode of large images |
| `vae_slicing` | `false` | VAE-decode a batch one image at a time (lowers peak VRAM for batch > 1. Pixels are identical) |
| `vae_auto` | `true` | Automatically slice/tile only when the VAE decode does not fit in free VRAM |
| `vae_fp16` | `false` | Decode SDXL-family (force-upcast) VAEs in fp16 (falls back to fp32 if non-finite) |
| `fp8_storage` | `false` | Keep U-Net weights in float8 and upcast per layer (GPU VRAM mode only) |
| `preview_every` / `preview_method` | `2` / `approx` | Live preview interval (0 disables) / `approx` or `taesd` |
| `last_model` / `last_vae` | `null` | Previously used models |
| `mcp_http_enabled` / `mcp_http_port` / `mcp_token` | `false` / `7861` / `null` | The MCP HTTP server built into the GUI |
| `disabled_plugins` / `plugin_values` | `[]` / `{}` | Disabled plugins and the value of each control |
| `hf_offline` | `false` | Do not access Hugging Face (`HF_HUB_OFFLINE=1`) |
| `theme` | `dark` | `dark` / `light` |
| `allow_custom_code` | `false` | Allow the Custom code script (equivalent to A1111's `--allow-code`. Never allowed from MCP) |

Other state: `config/ui-state.json` (UI state), `config/styles.csv` (Styles), and `outputs/` (generated images,
organized by kind such as `txt2img/<date>/`; temporary files go in `outputs/.tmp`). None of these are tracked by git.

## Keyboard shortcuts

| Key | Action |
|---|---|
| Ctrl+Enter | Generate (works while typing in the prompt) |
| Ctrl+Shift+Enter | Add to the queue |
| Esc | Interrupt while generating |
| Ctrl+V | Paste an image from the clipboard in the img2img / Inpaint image area |
| Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) | Undo / redo of the Inpaint mask |
| Ctrl+Up / Ctrl+Down | Increase/decrease the emphasis weight of the selection in the prompt |
| Left / Right | Previous/next image in the gallery (when the preview has focus) |
| Delete | Delete the image selected in the gallery |

(On macOS, Ctrl becomes Cmd.)

## MCP (use from AI agents)

Image generation is exposed as MCP tools. Tools: `txt2img` `img2img` `txt2img_async` `img2img_async` `get_job` `cancel_job`
`list_models` `set_model` `list_loras` `list_samplers` `list_recent` `get_image` `get_status` `interrupt`
`list_scripts` `run_script` (details in [mcp/README.md](mcp/README.md)).

### stdio (headless)

Starts a dedicated engine without the GUI. `run.bat --mcp` launches `sdfx.mcp.McpMain` directly without initializing JavaFX,
and writes only JSON-RPC to stdout (logs go to stderr).

```
claude mcp add stablediffusion-nui -- D:/path/to/stablediffusion-nui/run.bat --mcp
# Using the jar directly
claude mcp add stablediffusion-nui -- java -cp D:/path/to/stablediffusion-nui/target/stablediffusion-nui.jar sdfx.mcp.McpMain --home D:/path/to/stablediffusion-nui
# Try it without a GPU
claude mcp add stablediffusion-nui-dry -- D:/path/to/stablediffusion-nui/run.bat --mcp --dry-run
```

If you write it in `.mcp.json`, see `.mcp.json.example`. Options of `McpMain`: `--home DIR`, `--dry-run`,
`--http [PORT]` (HTTP instead of stdio), `--token TOKEN` (the environment variable `SDFX_MCP_TOKEN` is recommended), `--allow-dir DIR` (repeatable),
`--drain-seconds N`.

### HTTP (connect to a running GUI)

In Settings → MCP, enable **Enable MCP HTTP server** (default port 7861, token optional) and press OK; it starts immediately (no restart needed).
The endpoint is `http://127.0.0.1:7861/mcp` (127.0.0.1 only). Jobs go through the GUI queue, so they appear in the preview and gallery.

```
claude mcp add --transport http stablediffusion-nui-gui http://127.0.0.1:7861/mcp
claude mcp add --transport http stablediffusion-nui-gui http://127.0.0.1:7861/mcp --header "Authorization: Bearer <token>"
```

On a shared machine, always set a token (without one, other processes on the same machine can generate, operate the queue and read images from allowed folders).

### Folders allowed for reading

File paths accepted by `init_image` / `mask` / `get_image` are limited to the output folder and the allowed folders.
Add allowed folders with `--allow-dir <dir>` (headless) or the environment variable `SDFX_MCP_ALLOWED_DIRS` (separated by `;` on Windows, `:` elsewhere;
it also applies to the GUI's HTTP server). Paths are checked by their real path, so escaping through symbolic links is not possible, and UNC paths are always rejected.
base64 / `data:` URIs can always be used.

## Architecture

```
 +------------------------ target/stablediffusion-nui.jar (sbt assembly) -------------------------+
 |                                                                                               |
 |   ui (Scala 3 + JavaFX 21)            mcp (stdio / Streamable HTTP, sdfx.mcp.McpMain)         |
 |     sdfx.ui.Launcher                    |                                                      |
 |         |                               |                                                      |
 |         +---------------+---------------+                                                      |
 |                         v                                                                      |
 |                 plugins (X/Y/Z, FreeU, ...)   <-- plugins-ext/*.jar (ServiceLoader)           |
 |                         v                                                                      |
 | engine (AppServices, Generator, PluginRegistry, backend SPI) | pythonEngine (Python/PyTorch) | scalaTorchEngine |
 |                         v                                                                      |
 |                 core (Settings, Protocol, PNG/infotext, plugin API)                            |
 +-------------------------------------------|-----------------------------------------------+
                                             | stdin / stdout : JSON Lines (one JSON object per line)
                                             v
                       python -m sdfx [--dry-run]   (python/sdfx, cwd = python/, venv = python/.venv)
                         server.py -> engine.py (diffusers: txt2img, img2img, inpaint, hires, LoRA, ControlNet)
                                   -> dryrun.py (placeholder engine)
```

- Dependencies run `core <- engine <- plugins <- mcp <- ui`. The UI and MCP use the same `AppServices`.
- Communication with the engine is JSON Lines, one JSON object per line. ping / interrupt / shutdown are received on a dedicated thread, and generation is processed in order by a single worker queue.
  Progress, previews and logs flow as events. The contract is `core/src/main/scala/sdfx/core/protocol/Protocol.scala`.
- Only protocol lines go to stdout. Python-side logs and library output go to stderr.
- App root detection order: `-Dsdfx.home`, the environment variable `SDFX_HOME`, the current directory, then the jar's location (the folder containing `python/sdfx`).

## Development

```
sbt test                         # all modules
sbt core/test engine/test        # per module (core, engine, plugins, mcp, ui)
sbt ui/run -- --dry-run          # launch the GUI from sbt (fork)
sbt "mcp/runMain sdfx.mcp.McpMain --dry-run"
sbt "engine/runMain sdfx.engine.tools.Smoke"   # integration check that runs the real Python package in dry-run
cd python && python -m pip install -r requirements-dev.txt && python -m pytest   # no torch needed
```

Scripts that run everything at once (they exit non-zero on failure):

| Script | Contents |
|---|---|
| `scripts/smoke.ps1` / `smoke.sh` | Smoke (dry-run) + pytest |
| `scripts/test-all.ps1` / `test-all.sh` | `sbt test` (all modules) + pytest |
| `scripts/build.bat` / `build.sh` | `sbt -batch ui/assembly` |

pytest uses `python/.venv-test` if it exists; otherwise it installs pytest / pillow / numpy into a throwaway venv.

## Writing an external plugin

Implement `sdfx.core.plugins.api.Plugin`, register it with `java.util.ServiceLoader`, and put the jar in `plugins-ext/` (loaded on restart).
A Plugin is either `AlwaysOn` (intervenes in every generation) or `Script` (runs the whole job). The UI components declared in `controls` are rendered in the GUI
and also exposed to MCP as a JSON schema.

```scala
package example

import sdfx.core.model.GenerationParams
import sdfx.core.plugins.api.*

final class SuffixPlugin extends Plugin:
  def id = "suffix"
  def name = "Prompt suffix"
  override def description = "Appends text to every prompt."
  def kind = PluginKind.AlwaysOn
  override def controls = Seq(
    Control.Check("enabled", "Enabled"),
    Control.Text("text", "Suffix", defaultValue = ", masterpiece")
  )
  override def process(p: GenerationParams, v: Values): GenerationParams =
    p.copy(prompt = p.prompt + v.str("text"))
```

Service file `src/main/resources/META-INF/services/sdfx.core.plugins.api.Plugin`:

```
example.SuffixPlugin
```

For `kind = PluginKind.Script`, implement `run(p, v, ctx)` (generate with `ctx.generate`, save with `ctx.saveImage`, plus `ctx.status` / `ctx.isInterrupted` and so on).
When compiling, put `sdfx-core` on the classpath (an sbt project depending on this repository's `core`, or a jar built with `sbt core/package`).
The Scala runtime is included in the fat jar (the `ui` jar), so you normally do not need to include it in the plugin jar (`plugins-ext/` is loaded in the same ClassLoader hierarchy).

## Troubleshooting

- **Java not found / too old**: `run.bat` prints the reason to stderr and exits with code 1. Install JDK 17 or later and set `JAVA_HOME`.
- **A `Unsupported JavaFX configuration` warning appears at startup**: This warning appears because the fat jar is launched on the classpath; it does not affect operation.
- **torch installation fails / CUDA is unavailable**: Delete `python/.venv` and restart to set it up again.
  It uses CUDA 12.8 wheels (`cu128`), so an old driver will not recognize the GPU. Check with `nvidia-smi`, and if you need a different CUDA,
  run `pip install -r requirements-torch.txt --index-url <another index>` manually.
  Python 3.13 and later are not supported (use 3.10-3.12).
- **I want to use my own Python**: Specify the executable in Settings → Engine → Python (`python_exe`). If specified, only that one is used,
  and if it does not work it reports an error instead of silently switching to another Python.
- **Cannot reach Hugging Face / want to use it offline**: The first `from_single_file` fetches the SD1.5 / SDXL base configuration and tokenizer
  (`stable-diffusion-v1-5/stable-diffusion-v1-5`, `stabilityai/stable-diffusion-xl-base-1.0`). After loading online once and caching them,
  enable **Offline mode** (`hf_offline`) in Settings → Engine and it runs with `HF_HUB_OFFLINE=1`.
- **Out of VRAM (out of memory)**: Reduce the size/batch, enable `vae_tiling`, or change `vram_mode`.
  The values the engine (Python) accepts are `gpu` (everything on the GPU) / `model_offload` / `sequential_offload` (lowest memory, slowest).
  You can choose them under VRAM mode in Settings → Engine (the old `medvram` / `lowvram` / `cpu` values are converted automatically on load).
- **JSON parse error in an MCP client**: Check that no logs are mixed into stdout. `run.bat --mcp` itself prints nothing.
  Do not put `echo` or `pause` in your own launch scripts. To suppress JVM warnings use `-Xlog:disable -Xlog:all=warning:stderr -Djava.awt.headless=true`.
- **The engine does not start**: Open the Log at the bottom right of the window to check. Running `run.bat --dry-run` to check only the UI helps isolate the problem.

## Licence

AGPL-3.0 (`LICENSE`). This is because processing from the AUTOMATIC1111 web UI, Forge and reForge (AGPL-3.0) and from ComfyUI and Fooocus (GPL-3.0) has been ported.
The licences of each port source, re-implementation source and dependency are in `THIRD_PARTY_NOTICES.md` and `licenses/` (regenerate with `python scripts/third_party.py`).
