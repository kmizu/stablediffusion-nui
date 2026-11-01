---
title: Stable Diffusion NUI
nav: Home
description: Stable Diffusion NUI is a desktop app that runs SD1.5 and SDXL models on your own GPU. This page summarises its features and where to start in the manual.
---

## What you can do with SDNUI

### Three generation modes

You create images in three tabs: **txt2img**, **img2img** and **Inpaint**. In Inpaint you paint the mask directly, with undo / redo and pasting images from the clipboard.

- **Hires. fix**: generate at the base size, then upscale and refine.
- **Styles**: save prompts you use often and apply them again. Styles are stored in the A1111-compatible `config/styles.csv`.
- **Variation seed**, batch generation and a job queue (**＋ Queue**).
- A live preview shows the image while it is being generated.
- Prompts accept the A1111 `(emphasis:1.2)` syntax and `BREAK`, and are handled in 75-token chunks. Tags can be completed from a local CSV file.

### ControlNet and ControlNet Fastload

In the **ControlNet** section of each generation mode, you choose a ControlNet model and a preprocessor (canny, depth and others) to guide the composition. You can use up to three units, and set **Control mode** to **Balanced**, **My prompt is more important** or **ControlNet is more important**.

**ControlNet Fastload** saves the settings of your ControlNet units with an image and loads them again later. It saves them as a trailer appended to the PNG, as a `.cni` file, or both. Use the **ControlNet Fastload** tab to inspect a saved file and the **ControlNet Fastload Filter** tab to browse saved files.

### LoRA and embedding tools

- Use **✚ LoRA** next to the prompt to search for a LoRA and insert a `<lora:name:weight>` tag. **Networks…** lists them with preview images.
- The **Embedding Inspector**, **Embedding Merge** and **Embedding Editor** tabs let you inspect, combine and edit Textual Inversion embeddings.
- The **Model Keywords** tab links keywords to checkpoints and LoRAs, and the **Model Presets** tab manages presets bound to a model.
- The **Metadata Utils** tab reads and edits the metadata of a checkpoint or LoRA `.safetensors` file without loading its weights, and saves the result as a copy.

### A1111 extensions built in

Every built-in script and built-in extension of reForge has been rewritten and is included. In addition, 125 of the 353 extensions in the official A1111 / reForge extension index are built in.

Extensions come in three kinds:

| Kind | How you use it | Examples |
|---|---|---|
| Script | Pick one in the **Script** box; it runs the whole job | X/Y/Z plot, Prompt matrix, Loopback, SD upscale |
| AlwaysOn | Turn each one on or off in its section of the generation form; it applies to every generation | FreeU, Dynamic Thresholding, PAG, IP-Adapter, Never OOM |
| Extras | Post-processes an image | Upscale, Restore faces, Caption (BLIP / WD14) |

You can add your own plugins by placing their jar files in the `plugins-ext/` folder.

### Control from AI agents over MCP

SDNUI includes an MCP server, so AI agents such as Claude Code can generate images with it. An agent can list and switch models, run txt2img / img2img, check progress, cancel jobs, and use scripts and AlwaysOn extensions.

There are two ways to connect: stdio, which starts its own engine without the GUI, and HTTP, which connects to the running app. Jobs sent over HTTP go through the GUI's queue, so they also appear in the preview and the gallery.

### Image tools

| Tab | What it does |
|---|---|
| **PNG Info** | Reads the generation parameters embedded in an image and sends them to a generation tab |
| **Extras** | Upscales images and corrects colours |
| **Comparison** | Compares two images with a wipe |
| **VAE Tools** | Encodes and decodes images with the selected checkpoint's VAE (or TAESD) |
| **Tokenizer** | Splits text into tokens using the CLIP vocabulary of the loaded model |
| **Temporal Lab** | Stabilises image sequences (DFI), removes flicker, fuses sequences and extracts frames from video |
| **Image Scrambling** | Scrambles images, one at a time or in batches |
| **System Info** | Shows the runtime, memory and model inventory, and runs benchmarks |
| **VRAM Estimator** | Measures and shows how much VRAM a generation is likely to need |

![SDNUI after generating an SDXL image in txt2img](shots/txt2img.png)

## Where to go next

- [Install and first launch](install.html): get the ZIP, set up the Python environment and configure model folders.
- [Generate your first image](first-image.html): a step-by-step walkthrough of creating one image in txt2img.
- [Use SDNUI from AI agents over MCP](mcp.html): drive SDNUI from Claude Code and other MCP clients.
