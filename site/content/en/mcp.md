---
title: Using SDNUI from AI agents (MCP)
nav: MCP
description: Connect Claude Code and other MCP clients to Stable Diffusion NUI over stdio or Streamable HTTP to list models, generate and edit images, track jobs and replay PNG metadata.
---

Stable Diffusion NUI includes an MCP server. An AI agent such as Claude Code can use it to look up the models and
features of your installation, pick a checkpoint, generate and edit images, follow progress, fetch the results,
and cancel jobs.

## Choose a transport

There are two ways to connect. Both offer the same tools.

| | stdio (headless) | Streamable HTTP (inside the GUI) |
|---|---|---|
| Starts | A separate server with its own engine, no window | Inside the running desktop app |
| Jobs | Run in the server's own queue | Go through the GUI queue and show up in its preview and gallery |
| Address | The client launches the process | `http://127.0.0.1:7861/mcp` |
| Token | Not used | Optional bearer token |

Each server configuration starts its own model engine. Use one configuration per GPU so you do not load the same
models twice.

## Headless server (stdio)

The client starts SDNUI as a child process and talks to it over stdin/stdout. The server answers `initialize`
right away. Tools wait until the engine has started; the `*_async` tools return at once with the status
`waiting_for_engine`.

### Start commands

Use the command that matches how you installed SDNUI:

- Windows package: `C:/apps/SDNUI/SDNUI.exe --mcp --home C:/apps/SDNUI`. It uses the bundled Java runtime. Set up
  the Python environment and model folders in that home first.
- Source checkout: `run.bat --mcp`. The launcher prints nothing to stdout and starts `sdfx.mcp.McpMain` without
  initialising JavaFX.
- Jar: `java -cp target/stablediffusion-nui.jar sdfx.mcp.McpMain --home <dir>`.

Replace `C:/apps/SDNUI` with your installation folder.

### Options

| Option | Meaning |
|---|---|
| `--home DIR` | App root (the folder with `python/`, `models/` and `config/`). |
| `--dry-run` | Start the engine without loading torch/diffusers. Generation returns placeholder images, so you can try the tools without a GPU or models. |
| `--http [PORT]` | Serve Streamable HTTP instead of stdio. Without a port, the `mcp_http_port` setting is used (default 7861). |
| `--token TOKEN` | Bearer token for `--http`. The environment variable `SDFX_MCP_TOKEN` does the same and is preferred because it does not appear in the process list. If neither is given, the `mcp_token` setting is used. |
| `--allow-dir DIR` | Add a folder that image paths may point to. Repeat for several folders. |
| `--drain-seconds N` | When stdin closes, give running calls this many seconds to finish before exiting. Default 2. |

Unknown options, such as the `--mcp` passed on by the launcher, are ignored.

!!! note "Keep stdout clean"
    In stdio mode, stdout carries only JSON-RPC messages and every log line goes to stderr. If you write your own
    launcher, keep it silent (`@echo off`, no banner, no `pause`) and start the JVM with
    `-Xlog:disable -Xlog:all=warning:stderr -Djava.awt.headless=true` so JVM warnings cannot reach stdout. If your
    MCP client reports JSON parse errors, check for stray output first.

## HTTP server inside the GUI

Use this when you want the agent to work with the app you already have open.

1. Open **Settings** and select the **MCP** tab.
2. Turn on **Enable MCP HTTP server**.
3. Leave **Port** at 7861 or pick another port (1024 to 65535).
4. Optionally enter a **Token**. Clients must then send it as a bearer token.
5. Press **OK**. The setting applies immediately.

![MCP tab in Settings](shots/settings-mcp.png)

While the server is running, the top bar shows a **⇄ MCP :7861** chip. The endpoint is
`http://127.0.0.1:7861/mcp` (with your port number). The settings are stored as `mcp_http_enabled`,
`mcp_http_port` and `mcp_token` in `settings.json`.

The server listens on 127.0.0.1 only. It rejects requests whose `Origin` or `Host` header is not a loopback
address, which blocks web pages from calling it. When a token is set, every request needs
`Authorization: Bearer <token>`.

!!! warning "Set a token on shared machines"
    Without a token, any local process or other user on the same machine can generate images, switch the model,
    interrupt or clear the GUI queue, and read images from the allowed folders.

## Register SDNUI in Claude Code

Headless, Windows package:

```sh
claude mcp add stablediffusion-nui -- C:/apps/SDNUI/SDNUI.exe --mcp --home C:/apps/SDNUI
```

Headless, source checkout or jar:

```sh
claude mcp add stablediffusion-nui -- C:/path/to/stablediffusion-nui/run.bat --mcp
claude mcp add stablediffusion-nui -- java -cp C:/path/to/stablediffusion-nui/target/stablediffusion-nui.jar sdfx.mcp.McpMain --home C:/path/to/stablediffusion-nui
```

Try the tools without a GPU:

```sh
claude mcp add stablediffusion-nui-dry -- C:/path/to/stablediffusion-nui/run.bat --mcp --dry-run
```

The running GUI over HTTP, without and with a token:

```sh
claude mcp add --transport http stablediffusion-nui-gui http://127.0.0.1:7861/mcp
claude mcp add --transport http stablediffusion-nui-gui http://127.0.0.1:7861/mcp --header "Authorization: Bearer <token>"
```

### Project `.mcp.json`

To share the setup with a project, copy `.mcp.json.example` from the repository to `.mcp.json` in your project and
change the installation path:

```json
{
  "mcpServers": {
    "stablediffusion-nui": {
      "command": "C:/apps/SDNUI/SDNUI.exe",
      "args": ["--mcp", "--home", "C:/apps/SDNUI"]
    }
  }
}
```

Other MCP hosts work the same way: give them the same executable and arguments, or the HTTP endpoint.

## Tools

Every tool rejects unknown arguments and values of the wrong type with an error that lists the allowed
arguments. The client's `tools/list` response carries the full JSON schema of each tool.

### Discovery

| Tool | What it does |
|---|---|
| `get_status` | Engine state, device, loaded model, current job, queue length and outputs folder. |
| `list_models` | Checkpoints with their kind (`sd15`, `sdxl` and so on), size and which one is loaded, plus VAEs. `refresh: true` rescans the model folders first. |
| `list_samplers` | Samplers, schedule types, hires upscalers, upscalers and saved styles. |
| `list_loras` | LoRAs with a ready-made `<lora:...>` tag. `query` filters by name. |
| `list_controlnets` | ControlNet models, preprocessors and control modes accepted by `controlnet` units. |
| `list_always_on` | Enabled AlwaysOn extensions for `tab` (`txt2img` or `img2img`), with their controls, defaults and a typed `values_schema` for `always_on`. |
| `list_scripts` | Script plugins for `run_script` (X/Y/Z plot, prompt matrix, loopback, SD upscale and others) with their controls. |

### Models

| Tool | What it does |
|---|---|
| `set_model` | Load a checkpoint by `name` (name, title, path or hash prefix from `list_models`) and optionally a `vae` (`Automatic`, `None` or a VAE name). Only checkpoints in the configured folders can be loaded. |
| `model_keyword_lookup` | Read the trigger keywords for a checkpoint or LoRA (`kind`, `name`), the legacy hash and which mapping file matched. Without `name`, it uses the loaded checkpoint. |
| `model_presets` | Model-bound preset libraries. `action` is `get`, `save`, `share`, `resolve`, `fetch` or `thumbnail`. `save` and `fetch` need `expected_revision` from `get`. `fetch` sends the checkpoint's SHA256 to Civitai. No generation starts. |

### Generation and jobs

| Tool | What it does |
|---|---|
| `txt2img` | Generate from a prompt and wait for the result. |
| `img2img` | Transform `init_image`, or inpaint with `mask`, and wait for the result. |
| `txt2img_async` / `img2img_async` | Queue the same request and return `{job_id}` at once. Use these when your client has short tool timeouts. |
| `get_job` | Status, progress (0 to 1) and, when finished, the images of an async job. `wait_seconds` (up to 300) blocks until the job ends or the time passes. |
| `cancel_job` | Cancel a queued MCP job, or interrupt it if it is running. Works only on jobs submitted over MCP. |
| `interrupt` | Stop the running generation, whatever started it. `clear_queue: true` also removes all pending jobs. |
| `interruption_protection` | Read or set (`enabled`) interruption protection for this engine process. Runtime only, off by default, shared with the desktop toggle. |
| `run_script` | Run a script plugin by `script_id` with control `values` and generation `params`. `async: true` returns a `job_id`. |

### Images and other utilities

| Tool | What it does |
|---|---|
| `list_recent` | Newest images in the outputs folder with their infotext. `limit` (default 10, up to 200) and `query` filter the list. |
| `get_image` | Return one image from the outputs folder or an allowed folder, with its infotext. `max_side` (default 1024, 0 = original) downscales the preview. Reads `.png`, `.jpg` and `.webp`. |
| `inspect_generation_metadata` | Read the parameters in a PNG and return `txt2img` arguments, AlwaysOn replay candidates and the fields it could not apply. Nothing runs. |
| `controlnet_fastload` | `operation: "inspect"` reads the ControlNet settings saved in a Fastload image or `.cni` file; `operation: "version"` reports format versions. |
| `clip_interrogate` | Work out a prompt for an image with CLIP Interrogator. `operation` is `prompt` (with `mode` best, fast, classic, negative or caption), `analyze` (top five medium, artist, movement, trending and flavor labels), `batch` (writes `.txt` files, `batch.txt` or `batch.csv` for an allowed `folder`, and never overwrites unless `overwrite: true`), `models` or `unload`. Models are released after each call unless `keep_loaded: true`. The first call downloads models from Hugging Face. |
| `controlnet_preprocess` | Run a ControlNet preprocessor on an image or a folder without generating (the Extras step of the ControlNet Preprocessor extension). `preprocessor` is a module from `operation=list` (canny, invert, recolor_*, tile_*, blur_gaussian, depth, Marigold, detectors); `processor_res` (64 to 2048) or `pixel_perfect` applies to canny and the detectors; `args` holds the module sliders. Results are saved as `<name>-<preprocessor>.png` with a `ControlNet Preprocessor: "{...}"` parameters entry and never overwrite. |
| `vae_tools` | Encode, decode or round-trip local files (`input_paths`, up to 64) with the loaded VAE. Optional TAESD or Approx NN weights are downloaded only with `allow_download: true`. |

## Generation arguments

`txt2img` needs `prompt`; `img2img` also needs `init_image`. The other arguments are optional.

- Basics: `negative_prompt`, `width`, `height` (64 to 4096, multiples of 8), `steps` (1 to 150, default 20),
  `cfg_scale` (default 7), `sampler_name` (default `DPM++ 2M`), `scheduler` (default `Automatic`), `seed`
  (`-1` = random), `batch_size`, `n_iter`, `clip_skip`, `eta`.
- Model: `checkpoint` loads that model first if it is not the current one; `vae` sets the VAE.
- Prompt helpers: `styles` (saved style names), `loras` (`[{name, weight}]`, added to the prompt as
  `<lora:name:weight>` tags), and `model_keyword` (turn Model Keyword on or off for this request).
- Variation: `subseed` and `subseed_strength`.
- img2img only: `denoising_strength` (default 0.75), `resize_mode`, `mask`, `inpainting_fill`,
  `inpaint_full_res`, `inpaint_full_res_padding`, `inpainting_mask_invert`, `mask_blur`.
- Results: `return_images` (default true) and `max_image_side` (default 1024) control the inline previews. The
  saved files are always full size.

`init_image` and `mask` accept a file path in an allowed folder, base64 PNG/JPEG, or a `data:` URI.

### `hires` (txt2img only)

An object with `enabled`, `scale` (1 to 4, default 2), `upscaler` (default `Latent`), `steps` (0 = same as the
first pass), `denoising_strength` (default 0.55), `resize_x` and `resize_y`. You can also give the second pass its
own `prompt`, `negative_prompt`, `cfg_scale`, `sampler_name` and `scheduler`.

```json
{
  "prompt": "a lighthouse at dusk",
  "width": 512,
  "height": 512,
  "hires": { "enabled": true, "scale": 2, "upscaler": "Latent", "denoising_strength": 0.5 }
}
```

### `manual_loras`

Up to 32 structured LoRA entries: `name` (required), `weight`, `unet`, `lbw`, `start`, `stop` (0 to 1) and `dyn`.
Names are checked against the live catalog. The older `loras` argument stays available for simple prompt tags.

### `controlnet`

Up to 8 units. Each unit has `enabled`, `model`, `image`, `preprocessor`, `weight` (0 to 2), `guidance_start`,
`guidance_end`, `preprocessor_args` (numbers only), `control_mode`, `processor_res` (-1 or 64 to 2048) and
`pixel_perfect`. Use `list_controlnets` for valid model, preprocessor and control mode values. For `txt2img` each
enabled unit needs an `image`; `img2img` may fall back to `init_image`.

### `always_on`

Per-job settings for AlwaysOn extensions, keyed by plugin id. Call `list_always_on` to see which ids exist and the
typed values each accepts. Values you leave out use the plugin's declared defaults, not your desktop settings, and
the desktop settings are not changed.

```json
{
  "prompt": "a ceramic teapot on a wooden table",
  "seed": 173,
  "steps": 20,
  "always_on": {
    "freeu": { "enabled": true, "b1": 1.15, "b2": 1.2 }
  }
}
```

Use either the `model_keyword` boolean or `always_on.model_keyword`, not both. ControlNet Fastload's load and save
during generation also go through `always_on`, for example
`{"controlnet_fastload": {"enabled": true, "mode": "load_only", "path": "..."}}`.

## What a generation returns

A generation result contains one image block per image (downscaled to `max_image_side`, unless
`return_images: false`) and a text block with each saved path and its infotext. The structured result is
`{job_id, images: [{path, seed, infotext, grid}]}`.

If the client sends `_meta.progressToken`, the server reports progress with `notifications/progress`. Sending
`notifications/cancelled` interrupts the job.

`get_job` returns a `status` of `waiting_for_engine`, `queued`, `running`, `done`, `failed` or `cancelled`. A
cancelled job is returned with `isError: true`.

## Example flow

A short session as an agent would run it. Each block shows the arguments of one tool call; use the model names that
`list_models` returns on your machine.

List the models:

```json
{ "name": "list_models", "arguments": {} }
```

Load an SD1.5 checkpoint from the list:

```json
{ "name": "set_model", "arguments": { "name": "animekawa_v10" } }
```

Generate an image and wait for it:

```json
{
  "name": "txt2img",
  "arguments": {
    "prompt": "a red fox sitting in fresh snow, morning light",
    "negative_prompt": "blurry, low quality",
    "width": 512,
    "height": 512,
    "steps": 20,
    "sampler_name": "DPM++ 2M",
    "seed": 42
  }
}
```

The result lists the saved file under `images[0].path`. Fetch it at a smaller size:

```json
{ "name": "get_image", "arguments": { "path": "<images[0].path from txt2img>", "max_side": 512 } }
```

For long jobs, call `txt2img_async` with the same arguments, then call `get_job` with the returned `job_id` and
`wait_seconds` until the status is `done`, `failed` or `cancelled`. Use `cancel_job` to stop that job; `interrupt`
acts on the whole queue.

## Replay a PNG from its metadata

1. Call `inspect_generation_metadata` with `image` set to a PNG path in an allowed folder, base64 PNG or a PNG
   `data:` URI.
2. Read the response: `generation_args` (typed `txt2img` arguments), `always_on` (replay candidates for supported
   extensions), `unapplied_fields` and `warnings`.
3. Check the checkpoint and LoRAs against `list_models` and `list_loras`. The tool does not choose or load a model.
4. Submit `txt2img` with the reviewed arguments and `always_on` values. For img2img or inpaint, add the source image
   and mask yourself; they cannot be recovered from the PNG text.

Supported candidates include native Hires settings, variation, eta, manual LoRAs, Conditioning Highres Fix,
img2img Hires Fix, Token Downsampling, Model Keyword, Hires seed tweaks, Composable LoRA, grid numbers, FreeU,
HyperTile and PAG. Anything else is returned under `unapplied_fields` and is never executed. Replay is not
guaranteed to be pixel-identical: model files, embeddings, adapters, runtime versions and inputs may differ.

## Security and limits

### Which image paths are accepted

`init_image`, `mask`, `get_image`, `run_script` `params.init_image` and other path inputs accept only files inside
the outputs folder (including its `.tmp`) and folders you allow:

- Headless: `--allow-dir <dir>`, repeatable.
- Both transports: the environment variable `SDFX_MCP_ALLOWED_DIRS`, separated by `;` on Windows and `:` elsewhere.

Paths are resolved to real paths, so symbolic links and junctions cannot escape. Relative paths are resolved
against the outputs folder. UNC and network paths (`\\host\share`, `//host`) are always refused. The error is the
same for a missing file and a file that is not allowed. Base64 and `data:` URIs are always accepted.

### What MCP cannot do

- Protected (scrambled) PNGs are refused as input. MCP has no image-password session.
- The `custom_code` script never runs over MCP, and `model_merge` and `model_converter` are GUI only because they
  write model files.
- MCP requests carry their own settings. They do not use unsaved controls in the GUI.
- These desktop workbenches have no MCP equivalent: Model Keyword mapping editor, Embedding Inspector and Merge,
  Image Comparison, Styles editor, Metadata Utils, Temporal Lab, Extras, Tokenizer, System Info and VRAM Estimator.

### Limits

| Item | Limit |
|---|---|
| Request body or stdio line | 64 MB |
| Concurrent tool calls | 32 (more get error -32000 "server busy") |
| Input image | 4096 x 4096 |
| Width, height and hires target | 4096 per side |
| `width * height * batch_size` | 16.7 megapixels |
| Total output per job | 64 megapixels |
| Images per call | 16 |
| Pending MCP jobs | 32 |
| Inline previews per response | 20 MB of base64; further images are returned as paths for `get_image` |
| HTTP sessions | Expire after 30 minutes idle; at 64 sessions only idle ones are removed |

## What has been verified

The MCP server was tested with the official `mcp` Python SDK (version 2.3.0, protocol `2025-06-18`) running as a
separate client against the packaged `SDNUI.exe` and its bundled runtime. Real-model runs used SD1.5
(`animekawa_v10`, 512 px) and SDXL (`waiIllustriousSDXL_v170`, 768 px) at 12 steps, over both stdio and HTTP.
All four runs passed. Each covered:

- discovery of tools, models, samplers, scripts and AlwaysOn extensions
- model selection and `txt2img` with progress notifications up to 100
- PNG image content in the response
- replay of an ordinary PNG and a FreeU PNG, with exact RGB matches
- `img2img` and mask inpainting
- async polling and completion
- cancellation, followed by a successful generation

The HTTP runs used a separate headless HTTP server, not the server inside the GUI. A passing run does not prove
that every extension combination works; model compatibility and prompt quality still depend on the model.
