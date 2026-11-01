---
title: Install and first launch
nav: Install
description: How to download and extract the Windows release of Stable Diffusion NUI, set up its Python environment and make your models available.
---

## Requirements

| Item | Details |
|---|---|
| OS | The download is for Windows x64. On Linux and macOS, build from source |
| Java | The download includes a Java runtime, so you do not need to install Java |
| Python | 3.10–3.12. Python 3.13 and later are not supported. Python and torch are not included |
| GPU | An NVIDIA GPU with a suitable driver is recommended. torch is installed as the CUDA 12.8 build |
| Models | Not included. Bring your own SD1.5 or SDXL checkpoints |
| Disk | Setting up the Python environment downloads several GB of PyTorch and diffusers |

Without a GPU you can still try the interface in dry-run mode (see [Dry-run mode](#dry-run)).

## Download and extract

1. Download the latest `SDNUI-v0.05-windows-x64.zip` from [GitHub Releases](https://github.com/kmizu/stablediffusion-nui/releases/latest).
2. Extract the ZIP to a folder you can write to, for example under your user profile.
3. Run `SDNUI.exe` inside the extracted `SDNUI` folder.

!!! warning "Do not run it from inside the ZIP"
    Settings, generated images and the Python environment are stored in the extracted folder, so you need to extract it to a writable location.

The extracted folder contains `app`, `runtime`, `python` and `models` next to `SDNUI.exe`. Keep them together. SDNUI is a portable app, not an installer: deleting the extracted folder removes it together with the data stored inside that folder.

## First launch and Python setup

When you are not in dry-run mode and no Python environment has been chosen yet (`python/.venv` does not exist), the **SDNUI — first start** setup window opens on the first launch. It also opens when `python/requirements*.txt` has changed.

The window has three buttons:

| Button | What it does |
|---|---|
| **⤓ Install** | Creates `python/.venv` and installs PyTorch (CUDA build) and diffusers. Needs Python 3.10–3.12 on PATH |
| **Use dry-run** | Starts with a stand-in engine that returns placeholder images. No GPU or downloads needed |
| **Choose Python…** | Picks `python.exe` from an existing environment that already has torch and diffusers |

When you choose **⤓ Install**, the following happens in order:

1. `python/.venv` is created with the system Python (it looks for `py -3.12`, `py -3.10`, `python3` and `python`).
2. `python/requirements-torch.txt` (torch / torchvision) is installed from `https://download.pytorch.org/whl/cu128`.
3. `python/requirements.txt` (diffusers and others) is installed.

This downloads several GB and takes about 5–20 minutes. Progress appears in the window's log, and the window closes by itself when it is done. Press **Cancel** to stop. After a cancellation or failure the button changes to **Retry**; press it to resume.

The Python you pick with **Choose Python…** is saved as `python_exe` in the settings file. To change it later, set **Python** on the **Engine** tab of Settings.

To set up the environment by hand, run these commands in the `python` folder:

```
cd python
python -m venv .venv
.venv/Scripts/pip install -r requirements-torch.txt --index-url https://download.pytorch.org/whl/cu128
.venv/Scripts/pip install -r requirements.txt
```

## Check that it started

The right side of the top bar shows the engine state. When it changes from **● Starting…** to **● Ready**, the app is ready. **● Ready** is followed by the device in use. While a model loads, it shows **● Loading**.

If the engine does not start, see [Troubleshooting](troubleshooting.html).

## Add your models

Put models under `models` in the app folder (the extracted `SDNUI` folder for the download), one subfolder per type. Subfolders are searched too.

| Folder | Contents |
|---|---|
| `models/checkpoints/` | Checkpoints (`.safetensors`, `.ckpt`) |
| `models/vae/` | VAE |
| `models/lora/` | LoRA |
| `models/controlnet/` | ControlNet. IP-Adapter, ControlNet-LLLite and Fooocus inpaint patches also go here |
| `models/embeddings/` | Textual Inversion |
| `models/upscalers/` | ESRGAN / SwinIR / SCUNet / DAT upscalers |
| `models/face_restore/` | GFPGAN / CodeFormer (for Restore faces) |
| `models/svd/` | Stable Video Diffusion |
| `models/zero123/` | Zero123 models (diffusers folders) |
| `models/photomaker/` | PhotoMaker v1 adapter (`photomaker-v1.bin`) |
| `models/taesd/` | TAESD (for live previews, optional) |

Models downloaded from Civitai and similar sites work when you place them in the matching folder. After adding files, press **⟳** (Rescan model folders) in the top bar to update the lists. Respect the licence of each model you use.

The following are downloaded from Hugging Face the first time they are needed:

- Base configurations and tokenizers for loading single-file SD1.5 / SDXL checkpoints
- The image encoders for Revision and IP-Adapter, BLIP, LDSR and Marigold

Once they are downloaded, you can work offline (see [Troubleshooting](troubleshooting.html#offline)).

### Use an existing A1111 / reForge install

If you already use A1111 or reForge, SDNUI can read its model folders directly, without copying anything. The folders are used read-only.

1. Press **⚙** (Settings) at the top right.
2. Open the **Model folders** tab.
3. Press **Add A1111/reForge install…** and select the A1111 / reForge install folder (for example `D:\stable-diffusion-webui-reForge`).
4. Press **OK**.

These folders are added in one step: `models/Stable-diffusion`, `models/VAE`, `models/Lora` and `models/LyCORIS`, `models/ControlNet`, `embeddings`, and `models/ESRGAN` and `models/RealESRGAN`.

You can also add or remove folders for each type. Select the type in the list on the left, then press **＋ Add folder…** or **－ Remove**. When you change folders and press **OK**, the model lists are reloaded.

![The Model folders tab in Settings](shots/settings.png)

## Dry-run mode {#dry-run}

In dry-run mode, torch and diffusers are not loaded, and the engine returns a placeholder image based on the seed. Without models or a GPU you can try generation, progress, preview, the gallery and PNG Info end to end.

For the download, start it from Command Prompt or PowerShell:

```
SDNUI.exe --dry-run
```

!!! note "Dry-run still needs Python"
    The download's dry-run mode still uses a working Python interpreter.

While dry-run is active, the top bar shows **DRY RUN**. The same applies when you choose **Use dry-run** in the setup window.

## Run from source

To use SDNUI on Linux or macOS, or to develop it, build it from source. You need:

- JDK 17 or later (`JAVA_HOME` or `java` on `PATH`)
- sbt (not needed if a built jar already exists)
- Python 3.10–3.12

```
# Build (creates target/stablediffusion-nui.jar)
scripts\build.bat          # Linux / macOS: scripts/build.sh

# Start
run.bat                    # Linux / macOS: ./run.sh
```

`run.bat` / `run.sh` look for Java 17 or later, build the jar with sbt if it is missing, and then start the app. The main options are:

| Option | Meaning |
|---|---|
| `--dry-run` | Start in dry-run mode |
| `--home DIR` | Set the app folder (the folder that contains `python/`, `models/` and `config/`). Usually not needed |
| `--mcp` | Run as an MCP server (stdio) without the GUI |

JavaFX jars for the current OS are bundled at build time. Build on each OS you want to run on.
