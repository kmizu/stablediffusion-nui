---
title: Troubleshooting and FAQ
nav: Troubleshooting
description: How to diagnose and fix common Stable Diffusion NUI problems, such as the engine not starting, running out of VRAM or models not being listed, plus answers to frequent questions.
---

## Check these first

### The engine status

The right side of the top bar shows the engine state.

| Label | Meaning |
|---|---|
| **● Starting…** | The Python engine is starting |
| **● Ready** | Ready to generate. Hover over it to see the engine, Python, torch and diffusers versions |
| **● Loading** | A model is loading |
| **● Busy** | The engine is working |
| **● Engine crashed** | The engine stopped unexpectedly. Hover over it to see the error |
| **● Stopped** | The engine is not running |

When it shows **● Engine crashed** or **● Stopped**, a **⟲ Restart** button appears next to it. Press it to restart the engine.

### Read the log

Press **☰ Log** at the bottom right to open the engine and job log. The cause of an error usually appears here. **Copy** copies the whole log, which is useful when you report a problem. **Clear** empties the log.

### The System Info tab

The **System Info** tab shows the runtime, memory and the model and network inventory. Press **Refresh data** to update it and **Export JSON…** to save it to a file. Nothing on this tab is sent anywhere automatically.

![The System Info tab](shots/system-info.png)

### Isolate the problem with dry-run

To tell whether a problem is in the interface or in the engine and models, start in dry-run mode. Dry-run does not use models or a GPU and returns placeholder images.

```
SDNUI.exe --dry-run
```

When running from source, use `run.bat --dry-run` (`./run.sh --dry-run` on Linux / macOS). If the problem does not occur in dry-run, the cause is likely the Python environment, the GPU or a model.

## The engine does not start

1. Check the log with **☰ Log**.
2. Check your Python version. Use 3.10–3.12. Python 3.13 and later are not supported.
3. If you set **Python** on the **Engine** tab of Settings, check that the path is correct. When the chosen Python does not work, SDNUI reports an error instead of switching to another Python. Leave it empty to use the app's `python/.venv`.
4. To rebuild the Python environment, delete `python/.venv` in the app folder and start the app. The setup window opens; run **⤓ Install** again.

If installation fails in the setup window, it shows "Installation failed" with the reason, and the button changes to **Retry**. Check your network connection and similar issues, then press **Retry**.

## torch fails to install / CUDA is not available

SDNUI installs the CUDA 12.8 build of torch (`cu128`). If your NVIDIA driver is too old, the GPU is not recognised.

- Run `nvidia-smi` to check the driver and GPU.
- If you need a different CUDA build, install it by hand in the `python` folder:

```
.venv/Scripts/pip install -r requirements-torch.txt --index-url <another index>
```

## Out of VRAM (out of memory)

Try these in order:

- Lower **Width** and **Height**, or the **size** under **Batch**. Size is the number of images generated in parallel, so a larger value uses more VRAM.
- Turn on **VAE tiling** on the **Engine** tab of Settings. It saves VRAM when the VAE decodes large images.
- Set **VRAM mode** to **Model offload (~8 GB VRAM)**, and if that is not enough, to **Sequential offload (lowest VRAM)**. Sequential offload is the slowest but uses the least VRAM.

VRAM settings take effect after an engine restart. Choose **Yes** when SDNUI asks after you press **OK**.

The top bar shows the GPU memory used by the engine as "VRAM used / total GB". To estimate how much VRAM a generation needs, use the **VRAM Estimator** tab. Turning on the **Never OOM** AlwaysOn section in the generation form lowers peak VRAM for that job without restarting the engine.

## Models are not listed {#models}

When no checkpoints are found, the preview area shows "No checkpoints found.".

1. Press **⟳** (Rescan model folders) in the top bar to scan the model folders again. When it finishes, it shows how many checkpoints, LoRAs and VAEs it found.
2. Check that the files are in the folder for their type. Checkpoints go in `models/checkpoints/`, and only `.safetensors` and `.ckpt` files are treated as checkpoints.
3. In the download, `models` is inside the extracted `SDNUI` folder. To use models stored elsewhere, add their folders on the **Model folders** tab of Settings. Relative paths are relative to the app folder.
4. To use models from A1111 / reForge, select the install with **Add A1111/reForge install…**.

## Cannot reach Hugging Face / working offline {#offline}

The first time you load a single-file checkpoint, SDNUI downloads the SD1.5 / SDXL base configuration and tokenizer from Hugging Face (`stable-diffusion-v1-5/stable-diffusion-v1-5`, `stabilityai/stable-diffusion-xl-base-1.0`).

Once they are loaded and cached online, turn on **Offline mode** on the **Engine** tab of Settings to run with `HF_HUB_OFFLINE=1`. The image encoders for Revision and IP-Adapter, BLIP, LDSR and Marigold are also downloaded on first use, so use them once before you go offline.

## The MCP client reports a JSON parse error

With stdio MCP, the client cannot read the stream if anything other than MCP messages reaches standard output.

- `SDNUI.exe --mcp` and `run.bat --mcp` themselves print nothing else to standard output.
- If you use your own launcher script, do not add `echo` or `pause`.
- To keep JVM warnings off standard output, start Java with `-Xlog:disable -Xlog:all=warning:stderr -Djava.awt.headless=true`.

## Problems when running from source

- **Java is missing or too old**: `run.bat` prints the reason and exits. Install JDK 17 or later and set `JAVA_HOME`.
- **An `Unsupported JavaFX configuration` warning at startup**: this appears because the fat jar is started from the classpath. It does not affect how the app works.

## FAQ

### Do I need A1111 or reForge installed?

No. SDNUI works on its own. If you already use A1111 / reForge, SDNUI can read its model folders, read-only.

### Do I need an internet connection?

Yes, to set up the Python environment and the first time a model is loaded. Once the files you need are downloaded, you can work offline with **Offline mode**.

### Can I use it without a GPU?

An NVIDIA GPU is recommended for real generation. Without a GPU you can still try the interface in dry-run mode.

### How do I see the parameters of a generated image?

They appear in the text box below the image. For a saved PNG, load it in the **PNG Info** tab to see them and send them to a generation tab.

### How do I uninstall it?

The download does not use an installer, so delete the extracted `SDNUI` folder. Settings, generated images and the Python environment inside it are deleted too. Copy any images you want to keep somewhere else first.
