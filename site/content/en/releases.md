---
title: Release notes
nav: Release notes
description: What each release of Stable Diffusion NUI (formerly SDFX) added or changed, with the SHA-256 of each download.
---

Each release can be downloaded from its GitHub release page. Every release bundles a Java runtime; Python and model files must be supplied separately.

## v0.1

Published 2026-11-02. [GitHub release page](https://github.com/kmizu/stablediffusion-nui/releases/tag/v0.1)

The built-in Japanese language pack and [language guide](localization.html) are included. Translation is partial: 74.1% of distinct strings observed in the current UI. Panorama, compile, Prompt Tools, State Manager, tag completion and Corridor Crawler are partial implementations.

Extract **SDNUI-v0.1-windows-x64.zip** and run **SDNUI/SDNUI.exe**. SHA-256: `3ea05b8e5b0fec575cc4282459d03f3766766617f681a2927e081e1c79ef91b3`

## v0.06

Published 2026-11-02. [GitHub release page](https://github.com/kmizu/stablediffusion-nui/releases/tag/v0.06)

### Added

- **Aesthetic Score**, **AnimateDiff**, **Booru Prompt**, **Character Searcher** and **CLIP IO**.
- **Partial** (documented gaps, the issues stay open): Aesthetic Gradients, Batch Face Swap, CLIP Interrogator, CN Animation (video-to-video only), ControlNet Preprocessor in Extras, interface Localization (built-in Japanese pack) and the native Image Browser tab. Details are on the [extensions page](extensions.html) and in the GitHub release notes.

### Getting it

Download **SDNUI-v0.06-windows-x64.zip**, extract it, and run **SDNUI/SDNUI.exe**. SHA-256: `e12d6a7f1e0240a8cffe90015d8bdf2cccec27550137f12ecdf00e7d64fcaa4c`

### Verification

- The full Scala and Torch CPU test suites were run, and each feature was checked with real SD1.5 and SDXL models over MCP with the images inspected.
- The packaged `SDNUI.exe` was run on both models, and the downloaded ZIP's SHA-256 was checked against the build.

## v0.05

Published 2026-11-02. [GitHub release page](https://github.com/kmizu/stablediffusion-nui/releases/tag/v0.05)

Ten more extensions added since v0.04.

### Added and changed

- **Guidance and attention**: Semantic Guidance (#115), Agent Attention (#4), CD Tuner (#9), Characteristic Guidance (#11) and Incantations (#63).
- **Sampling and upscaling**: Sonar (#118), LLuL (#75), Loopback Scaler (#76) and Old SD firstpasser (#94).
- **Safety**: NSFW checker (#91) with NudeNet censoring, live preview, Extras, and the SD safety checker and NSFWJS filters.

### Getting it

Download **SDNUI-v0.05-windows-x64.zip**, extract it, and run **SDNUI/SDNUI.exe**. SHA-256: `fbdb3ba845bf66b37a3dc006d8b80b7e3525b0f15e1b24b2010910ae10920523`

### Verification

- The full Scala and Torch CPU test suites were run.
- Each feature was checked with real SD1.5 and SDXL models over MCP, and the images were inspected. The packaged `SDNUI.exe` was also run on both models.

## v0.04

Published 2026-11-01. [GitHub release page](https://github.com/kmizu/stablediffusion-nui/releases/tag/v0.04)

Ten more issues resolved since v0.03, plus workflows for using the app from AI agents over MCP.

### Added and changed

- **ControlNet Fastload (#17)**: load ControlNet units from a Fastload image or a `.cni` file before generating, and save the units with each image (embedded, as a sidecar file, or both). The format is compatible with the A1111 extension's. The built-in ControlNet now also has control modes, canny processor resolution and pixel perfect.
- **Embeddings**: Embedding Inspector (#46), Embedding Merge (#47) and Embedding Editor (#45).
- **Generation**: img2img Hires Fix (#61), Conditioning Highres Fix (#16), Model Presets (#87) and Model Keyword (#85).
- **Files**: Metadata Utils for checkpoint and LoRA metadata (#82), and image scrambling compatible with the upstream extension (#48). Image scrambling is not secure encryption.
- **MCP**: agents can discover models, ControlNets and AlwaysOn controls, configure extensions per job, generate and edit images, follow progress, inspect and replay PNG metadata, and cancel jobs, over both stdio and Streamable HTTP. See [MCP](mcp.html).

### Getting it

Download **SDNUI-v0.04-windows-x64.zip**, extract it, and run **SDNUI/SDNUI.exe**. To run it as an MCP server, start `SDNUI.exe --mcp`. Existing `sdfx` configuration keys remain compatible.

### Verification

- The full Scala and Torch CPU test suites were run.
- Each feature was checked with real SD1.5 and SDXL models, and the generated images were inspected.
- The official MCP Python SDK was used over both stdio and HTTP with SD1.5 and SDXL.
- The extracted package's `SDNUI.exe` was checked, and its bundled source files and jar matched the release commit.

Each feature's limits are recorded in its `docs/*-verification.md` file in the repository.

ZIP SHA-256: `b58c4a8dab854598f3e5072408ba24c37cf15996f8766d40ccc798ef62d38cb7`

## v0.03

Published 2026-10-27. [GitHub release page](https://github.com/kmizu/stablediffusion-nui/releases/tag/v0.03)

Stable Diffusion FX was renamed **Stable Diffusion NUI**.

### Added and changed

- **Local tag completion**: load your own CSV, select a suggestion with the keyboard, and replace only the current tag. Works in both positive and negative prompts, and leaves text alone while the IME is composing.
- **Named generation settings**: save, inspect, restore and delete complete per-mode settings, separately from selective presets. If a required resource is missing, restoring stops and explains why.
- **WD batch PNG metadata**: the effective prompts, actual settings and seeds, and the WD identity now survive PNG Info and restore in a fresh app session (#141).
- **Batch workflow**: all images generated together appear in the live preview. Each generation has its own gallery and ZIP export.

### Getting it

Download **SDNUI-v0.03-windows-x64.zip**, extract it, and run **SDNUI/SDNUI.exe**. Existing `sdfx` configuration keys remain compatible.

### Verification and limits

Checks covered the native UI, core SD1.5 and SDXL generation and reopening PNGs, generation and unloading with representative VRAM settings, and the extracted Windows EXE. Keyboard and IME checks used synthetic JavaFX events; not every combination with a physical Japanese IME was tested.

This release is the agreed native subset, not full parity with the upstream extensions. #23 and #24 remain open for their broader features; other interrogators and LoRA, ControlNet and script integration remain under #140. See `docs/V003_RELEASE_SCOPE.md` in the repository for scope, evidence and limits.

ZIP SHA-256: `75fca7e13167e0e5846d78904653bdc41710ebd04863a6c84fb82e577c5ea396`

## v0.02

Published 2026-10-27. [GitHub release page](https://github.com/kmizu/stablediffusion-nui/releases/tag/v0.02)

### Added and changed

- img2img has a WD v3 batch. It tags each image in a folder, generates from it, and shows the results in the gallery.
- Cancelling a batch now affects only that job, and a problem where generated images failed to register on Windows was fixed.
- The Width and Height fields are aligned. Add models from the gear icon → **Model folders**.

### Getting it

Extract the ZIP (`SDFX-v0.02-windows-x64.zip`) and run `SDFX/SDFX.exe`.

### Verification and limits

The core flow of submitting two images from the real screen was checked with both SD1.5 and SDXL.

The WD batch works with a local WD v3 model and tag CSV that you specify. Where to get them, extra dependencies, and how to configure and restart are described in `WINDOWS_RELEASE.md` inside the ZIP. WD support in this release was limited: other interrogators, combining it with LoRA, ControlNet or scripts, and saving WD metadata into generated PNGs were still to come.

ZIP SHA-256: `057d0128942fbeb8979d4379688094d8963dff218d41c0323d9507432ef59451`

## v0.01

Published 2026-10-27. [GitHub release page](https://github.com/kmizu/stablediffusion-nui/releases/tag/v0.01)

The SDFX Windows preview.

### Contents

- A native Windows x64 application with a bundled Java runtime.
- Model-card previews with **Show**, **Blur** and **Hide** controls.

### Getting it

Extract the ZIP (`SDFX-v0.01-windows-x64.zip`) and run `SDFX.exe`. Python 3.10 to 3.12 and the image-generation dependencies are set up separately on first launch, or you can select an existing compatible Python environment. Model files are not included; point the app at your existing local model folders.

### Verification and limits

The packaged application was checked with a local SD1.5 checkpoint, and the model-card feature was also checked with SDXL. This is an early preview; remaining extension-index work is tracked in issues.

ZIP SHA-256: `6e4a182ff136f44f4be289d39ef61b4078a23e0d651ddf263a6a2d8a69942f8b`
