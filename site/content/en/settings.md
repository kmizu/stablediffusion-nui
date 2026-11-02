---
title: Settings and folders
nav: Settings
description: Reference for Stable Diffusion NUI's Settings dialog, settings file keys, app and output folders, VRAM modes, keyboard shortcuts and environment variables.
---

## The Settings dialog

Press **⚙** at the top right to open Settings. Changes are saved when you press **OK** and discarded with **Cancel**.

![The Settings dialog](shots/settings.png)

| Tab | Main settings |
|---|---|
| **Model folders** | Search folders for each model type, **Add A1111/reForge install…** |
| **General** | Output folder, theme, live preview |
| **Engine** | Python for the engine, precision, VRAM mode, VAE tiling, offline mode |
| **MCP** | The MCP HTTP server built into the GUI |
| **WD v3 (partial)** | Model and tag files for the WD v3 batch in img2img |
| **Hires** | Which controls the Hires. fix section shows |
| **Language** | Interface language, bilingual display and language packs; see [Interface language](localization.html) |

### Model folders

Select a model type in the list on the left, then add or remove search folders for that type in the list on the right. There are ten types: **Checkpoints**, **VAE**, **LoRA**, **ControlNet**, **Embeddings**, **Upscalers**, **Face restore**, **SVD**, **Zero123** and **PhotoMaker**.

- **＋ Add folder…**: adds a folder for the selected type.
- **－ Remove**: removes the selected folder from the list.
- Double-click a folder to edit it. Relative paths are relative to the app folder.
- **Add A1111/reForge install…**: select an A1111 / reForge / Forge install to add all of its model folders, read-only.

When you change folders and press **OK**, the model lists are reloaded.

### General

| Setting | Details |
|---|---|
| **Output folder** | Where generated images are saved. Relative paths are inside the app folder |
| **Hires output folder** | An optional separate folder for Hires. fix results. Empty uses **Output folder**. If you have changed **Output folder** from its default, that takes priority |
| **Named NSFW previews default** | The default display of model preview images (**Blur**, **Hide**, **Show**). It applies from the next app session |
| **Theme** | `dark` or `light` |
| **Live preview** | Send a live preview every N sampling steps. 0 turns it off |
| **Preview method** | `approx` (instant, rough colours) or `taesd` (better quality, small extra cost) |

### Engine

| Setting | Details |
|---|---|
| **Python** | The Python used by the engine. Empty uses the app venv (`python/.venv`), then the system Python |
| **Precision** | Model weight precision: `fp16`, `bf16` or `fp32`. Use `fp16` for most GPUs |
| **VRAM mode** | See [VRAM modes](#vram) below |
| **VAE tiling** | Decodes large images in tiles to save VRAM |
| **Offline mode** | Never downloads from Hugging Face |
| **Allow custom code** | Lets the Custom code script run Python in the engine, like A1111's `--allow-code` |

When you set **Python**, only that Python is used. If it does not work, SDNUI reports an error instead of switching to another Python.

If you change Python, precision, VRAM mode, VAE tiling or offline mode and press **OK**, SDNUI asks "Engine settings changed. Restart the engine now?". Press **Yes** to restart the engine and apply the change.

### MCP

| Setting | Details |
|---|---|
| **Enable MCP HTTP server** | Lets AI agents drive this app over MCP (HTTP) |
| **Port** | The local port of the MCP HTTP server. The default is 7861 |
| **Token** | A bearer token that clients must send (optional, but recommended) |

These settings apply as soon as you press **OK**. While the server is running, the top bar shows a label such as **⇄ MCP :7861**. For how to connect, see the [MCP page](mcp.html).

![The MCP tab in Settings](shots/settings-mcp.png)

### WD v3 (partial) and Hires

On the **WD v3 (partial)** tab, select a local WD v3 model (**Model (.onnx)**) and tag file (**Tags (.csv)**). No models are downloaded automatically. Restart the engine after selecting them.

The **Hires** tab chooses which controls the Hires. fix section shows. Hidden controls keep their values.

## VRAM modes {#vram}

**VRAM mode** on the **Engine** tab chooses how models are placed on the GPU.

| Label | Value | Details |
|---|---|---|
| **GPU (fastest)** | `gpu` | Everything stays on the GPU. The fastest option |
| **Model offload (~8 GB VRAM)** | `model_offload` | Whole models move to the GPU when they are used |
| **Sequential offload (lowest VRAM)** | `sequential_offload` | Moves layer by layer. The slowest option, with the lowest VRAM use |

The older values `medvram` (becomes `model_offload`) and `lowvram` and `cpu` (become `sequential_offload`) are converted automatically when the settings are loaded.

## Settings file

Settings are stored in `config/settings.json`. The file is created on the first launch, and you can edit it from the Settings dialog.

| Key | Default | Details |
|---|---|---|
| `model_dirs` | The `models/*` folders | Search folders for each type |
| `output_dir` | `outputs` | Output folder (relative paths are relative to the app folder) |
| `python_exe` | `null` | Python for the engine. Empty uses `python/.venv`, then the system Python |
| `dtype` | `fp16` | `fp16` / `bf16` / `fp32` |
| `vram_mode` | `gpu` | VRAM mode |
| `vae_tiling` | `false` | Tile the VAE decode of large images |
| `preview_every` / `preview_method` | `2` / `approx` | Live preview interval (0 = off) / `approx` or `taesd` |
| `last_model` / `last_vae` | `null` | The model and VAE used last |
| `mcp_http_enabled` / `mcp_http_port` / `mcp_token` | `false` / `7861` / `null` | The MCP HTTP server built into the GUI |
| `disabled_plugins` / `plugin_values` | `[]` / `{}` | Disabled plugins and the values of each plugin's controls |
| `hf_offline` | `false` | Do not access Hugging Face (`HF_HUB_OFFLINE=1`) |
| `theme` | `dark` | `dark` / `light` |
| `allow_custom_code` | `false` | Allow the Custom code script. It is never available over MCP |

## The app folder

SDNUI uses the folder that contains `python/`, `models/` and `config/` as its app folder.

- **The download**: uses the extracted `SDNUI` folder, even if the current directory or the `SDFX_HOME` environment variable points elsewhere. Only `--home DIR` overrides it.
- **Running from source**: looks at `-Dsdfx.home`, the `SDFX_HOME` environment variable, the current directory and the jar's location (the folder containing `python/sdfx`), in that order.

These files and folders are created in the app folder:

| Location | Contents |
|---|---|
| `config/settings.json` | Settings |
| `config/ui-state.json` | Window and panel state |
| `config/styles.csv` | Styles (A1111 compatible) |
| `config/lbwpresets.txt` | LoRA Block Weight user presets (optional, one `NAME:w1,w2,...` per line) |
| `python/.venv` | The engine's Python environment |
| `outputs/` | Generated images |

### Output folder layout

| Location | Contents |
|---|---|
| `outputs/txt2img/<date>/` and similar | Generated images by kind and date (named `<number>-<seed>.png`) |
| `outputs/grids/` | Grid images |
| `outputs/intermediates/` | In-progress images recorded by **Save intermediate images** and similar features |
| `outputs/.tmp` | Temporary files |

## Keyboard shortcuts

| Key | Action |
|---|---|
| Ctrl+Enter | Generate (also works while typing a prompt) |
| Ctrl+Shift+Enter | Add to the queue |
| Esc | Interrupt the running generation |
| Ctrl+V | Paste an image from the clipboard into the img2img / Inpaint image area |
| Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) | Undo / redo the Inpaint mask |
| Ctrl+↑ / Ctrl+↓ | Raise / lower the emphasis weight of the selected prompt text |
| ← / → | Previous / next image in the gallery (when the preview has focus) |
| Delete | Delete the selected gallery image |

In the preview, Ctrl + mouse wheel zooms, dragging pans, and double-clicking switches between fit-to-window and 100%. On macOS, use Cmd instead of Ctrl.

## Environment variables

| Variable | Details |
|---|---|
| `SDFX_HOME` | Sets the app folder (when running from source; the download prefers its extracted folder) |
| `SDFX_MCP_TOKEN` | The token for the headless MCP server. Preferred over `--token`, which is visible in the process list |
| `SDFX_MCP_ALLOWED_DIRS` | Folders MCP may read from, separated by `;` on Windows and `:` elsewhere. Also applies to the GUI's HTTP server |

When **Offline mode** is on, the engine runs with `HF_HUB_OFFLINE=1`.
