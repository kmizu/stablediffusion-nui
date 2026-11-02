---
title: Generation basics
nav: Generating images
description: How to use txt2img, img2img, Inpaint, Hires. fix, batches, seeds, samplers, styles, the queue, named generation states and PNG parameter restore in Stable Diffusion NUI.
---

SDNUI has three generation tabs: **txt2img**, **img2img** and **Inpaint**. Each tab has the settings form on the
left and the preview and gallery on the right. You start, queue and interrupt jobs from the bar at the bottom of the
window. This page covers the settings the three tabs share, then the settings specific to each mode.

![The txt2img tab](shots/txt2img.png)

## Layout of a generation tab

From top to bottom, the form contains:

| Section | What it holds |
|---|---|
| **Prompt** | Prompt, negative prompt, and the LoRA and style toolbar |
| **Init image** / **Inpaint** | img2img and Inpaint only. Input image and mask settings |
| **Sampling** | Sampler, scheduler, steps and CFG Scale |
| **Size** | Width, height, batch count and batch size |
| **Seed** | Seed and variation seed |
| **Named generation states…** | Save and restore a named snapshot of all settings |
| **Settings presets** | Settings presets (see [Tools](tools.html)) |
| **Hires. fix** | txt2img only. A second pass that upscales and refines |
| **ControlNet** | Guide the composition with control images (see [ControlNet](controlnet.html)) |
| **Script** | Scripts such as X/Y/Z plot (see [Extensions](extensions.html)) |
| AlwaysOn plugins | Extensions such as FreeU, each switched on or off on its own |

When something is missing, a message marked `⚠` appears at the top of the form, for example when img2img has no
init image or Inpaint has no painted mask.

![The full txt2img form, including the ControlNet and extension sections](shots/txt2img-form.png)

## Prompt

The upper box is the prompt and the lower box is the negative prompt. The A1111 `(emphasis:1.2)` syntax and `BREAK`
work as in A1111.

- The counter at the top right shows the approximate number of CLIP tokens and the current 75-token chunk limit.
- A warning appears at the bottom right when brackets do not match.
- Select text and press **Ctrl+Up** / **Ctrl+Down** to raise or lower its attention weight. With no selection, the
  word under the caret is changed.
- **Tags…** sets up suggestions from local files: a tag CSV (with aliases), a translation CSV (search tags in your own language), an extra tag CSV, a chants JSON file. Press Enter or Tab to complete what you are typing. Type `__` to complete wildcard files, `<` for LoRA, embeddings and chants (`<l:` / `<lora:` / `<lyco:` for LoRA, `<e:` for embeddings, `<c:` for chants). A LoRA row shows its preview picture and adds the trigger words from its `.json` file. The dialog also sets the number of rows, underscore and parenthesis handling, a trailing comma and alias display.

For **✚ LoRA** and **Networks…** in the toolbar, see [LoRA and embeddings](lora-embeddings.html).

## Styles

A style is a named prompt and negative prompt pair that you use often. Styles are stored in `config/styles.csv`,
which is compatible with A1111.

1. Pick a style from the **Styles** list below the prompt boxes.
2. Click **⤓** (Apply the selected style to the prompts) to apply it to the prompts.
3. To save the current prompts as a style, click **✎** (Save the current prompts as a style) and enter a name. If
   the name exists, you are asked whether to replace it.

**Styles…** opens the **Styles** tab, where you can edit styles, sort them into categories and manage backups (see
[Tools](tools.html)).

When restored parameters include styles that are applied at generation time, their order is shown under
**Styles applied at generation:**. Click **Clear styles** to remove them.

![The Styles tab](shots/styles.png)

## Sampling

| Setting | What it does |
|---|---|
| **Sampler** | Sampling method. Only samplers the engine supports are listed (the default is `DPM++ 2M`) |
| Scheduler | To the right of the sampler. The noise schedule; **Automatic** uses the sampler's default |
| **Steps** | Number of denoising steps (1 to 150). 20 to 30 is typical |
| **CFG Scale** | How strongly the prompt is followed (1 to 30). 5 to 8 is typical |

Samplers include `DPM++ 2M`, `DPM++ SDE`, `DPM++ 2M SDE`, `Euler a`, `Euler`, `DDIM`, `UniPC` and `LCM`. Schedulers
include `Automatic`, `Karras`, `Exponential`, `SGM Uniform`, `Align Your Steps` and `Beta`. Parameters that use
older A1111 names such as `DPM++ 2M Karras` are read as the matching sampler and schedule.

## Size and batches

- **Width** / **Height**: image size, in multiples of 8. **⇅** swaps width and height. **▦** offers size presets
  such as 512×512, 832×1216 and 1024×1024. Use 512 to 768 for SD1.5 and about one megapixel for SDXL.
- **Batch** **count**: how many batches run one after another (1 to 100).
- **Batch** **size**: how many images each batch makes in parallel (1 to 16). Larger sizes use more VRAM.
- **Total** shows the total, for example "4 at once × 5 runs = 20 images".

## Seed and variation seed

A **Seed** of `-1` picks a random seed every time. **⚄** sets it back to `-1`, and **♻** reuses the seed of the last
image.

Open **Variation seed** to mix a second seed into the main one. This makes images that differ a little while
keeping the composition.

| Setting | What it does |
|---|---|
| **Enable variation** | Use the variation seed |
| **Var. seed** | The seed to mix in. **⚄** sets it to `-1` (random) |
| **Strength** | 0 uses only the main seed, 1 only the variation seed |

The values stay in place when you turn **Enable variation** off and are used again when you turn it back on.

## img2img

The **img2img** tab makes a new image from an input image.

![The img2img tab](shots/img2img.png)

1. Drag an image onto **Init image**, paste it with Ctrl+V, or click to open a file. You can also send a generated
   image with **⇢ img2img** in the preview.
2. Under **Resize mode**, choose how the input is fitted to the output size: **Just resize**, **Crop and resize**
   (crop to fit) or **Resize and fill** (fit inside and fill the rest).
3. Set **Denoising** to control how much the image may change. 0 keeps the input and 1 ignores it.

**WD v3 batch (partial)** tags each image in a folder with WD v3 and then runs img2img on it. Support is partial.
Configure the model and tag CSV on the **WD v3 (partial)** tab in Settings, then restart. It cannot be combined
with scripts, extensions, LoRA or ControlNet. See the
[Windows package notes](https://github.com/kmizu/stablediffusion-nui/blob/main/docs/WINDOWS_RELEASE.md#wd-v3-batch-interrogation-partial)
for details.

## Inpaint

The **Inpaint** tab redraws the part of an image that you mark with a mask.

![The Inpaint tab](shots/inpaint.png)

### Paint the mask

1. Drop or paste (Ctrl+V) an image, or open one with **…**.
2. Choose **✎ Brush** and drag with the left mouse button over the area to redraw.
3. To remove paint, use **⌫ Eraser** or drag with the right mouse button.

| Control | What it does |
|---|---|
| **↶** / **↷** | Undo (Ctrl+Z) / redo (Ctrl+Y) |
| **◑** | Invert the mask |
| **Clear** | Clear the whole mask |
| **Brush size** | Brush diameter. Ctrl+mouse wheel over the image also changes it |
| **Mask opacity** | How strongly the mask is shown. Display only; it does not affect generation |

Turn on **Create mask from image difference** to build the mask from the difference between a base image and an
altered image instead of painting it.

### Mask settings

| Setting | What it does |
|---|---|
| **Mask blur** | Feathering of the mask edge, in pixels |
| **Mask mode** | **Inpaint masked** redraws the painted area; **Inpaint not masked** redraws everything else |
| **Masked content** | What the masked area starts from. **original** keeps the colours and **latent noise** invents new content. **fill** and **latent nothing** are also available |
| **Inpaint area** | **Whole picture**, or **Only masked**: crop around the mask, generate at full resolution and paste back |
| **Padding** | Context pixels around the mask for **Only masked** |
| **Resize mode** | How the image is fitted to the output size (as in img2img) |
| **Denoising** | How much the image may change |

## Hires. fix

**Hires. fix** is available in txt2img only. It generates at the base size, then upscales the result and refines it
in a second pass, as Hires. fix does in A1111. Turn it on with the checkbox in the section header. The second
progress bar at the bottom of the window shows its progress.

| Setting | What it does |
|---|---|
| **Checkpoint** | Shows the checkpoint. The second pass uses the first-pass checkpoint |
| **Upscaler** | `Latent` upscalers stay in latent space; ESRGAN-type models upscale the decoded image |
| **Upscale by** | Scale factor (1 to 5). Ignored when **Resize W** / **Resize H** are set |
| **Hires steps** | Steps for the second pass. 0 uses the sampling steps |
| **Denoising** | How much the second pass may change the image |
| **Resize W** / **Resize H** | Explicit output size. 0 uses **Upscale by** |
| **Target** | Final size after the second pass, for example `512×768  →  1024×1536` |

The latent upscalers are `Latent`, `Latent (antialiased)`, `Latent (bicubic)`, `Latent (bicubic antialiased)`,
`Latent (nearest)` and `Latent (nearest-exact)`. Pixel upscalers include `Lanczos`, `Nearest`, `LDSR` and ESRGAN-type
models you put in `models/upscalers`.

### Second-pass settings

Open **Advanced** to set values that apply to the second pass only.

- **Sampler** / **Schedule**: **Use same sampler** / **Use same scheduler** keep the first-pass choice.
- **CFG**: CFG Scale for the second pass. 0 keeps the first-pass value.
- Hires prompt and Hires negative prompt: leave them empty to use the first-pass prompts.

### Hires Fix Tweaks

The Hires. fix section also contains **Hires Fix Tweaks**. With **Enable Hires tweaks** on, you can:

- **Hires batches and seed**: refine one first-pass result 1 to 16 times (**Hires batches**), with a separate Hires
  seed and variation seed. In the seed fields, `0` inherits the first-pass seed and `-1` is random. Buttons such as
  **Reuse last Hires seed** reuse the seeds of the last Hires image.
- **Hires prompt modes**: choose how the second-pass prompt is built: **Default**, **Append**, **Prepend** or
  **Prompt S/R**. **Remove first-pass network tags from Hires** drops the first-pass LoRA tags from the second pass.
- **Hires styles**: apply styles to the second pass only, in the order you select them. **Apply styles to Hires
  prompts** writes them into the Hires prompt boxes.

The **Hires** tab in Settings shows or hides these controls (**Show Hires CFG**, **Show Hires batches and seed** and
so on). Hidden controls keep their values. The same tab controls whether Hires prompt templates are saved in and
restored from image metadata. To save Hires results to a separate folder, set **Hires output folder** on the
**General** tab in Settings.

## img2img Hires Fix

**img2img Hires Fix** is an AlwaysOn plugin that works in the txt2img, img2img and Inpaint tabs. It takes each
finished image, upscales its pixels, re-encodes it with the VAE and refines it again. It is separate from the latent
Hires. fix in txt2img; when both are on, it runs after Hires. fix.

| Setting | What it does |
|---|---|
| **Pixel upscaler** | Upscaler to use (default `R-ESRGAN 4x+`, which needs a matching installed model) |
| **Hires steps (0 = inherit)** | Steps. 0 inherits the original value |
| **Denoising strength** | How much the image may change. 0 only resizes the pixels |
| **Upscale by** | Scale factor |
| **Resize width** / **Resize height** | When both are set, they override the scale factor |
| **Sampler** / **Schedule** / **CFG** | None or 0 inherits the original setting |
| **Refinement prompt** / **Refinement negative prompt** | Leave empty to use the first-pass prompts |

## Generating and the queue

Use the bar at the bottom of the window to run jobs.

| Control | Shortcut | What it does |
|---|---|---|
| **▶ Generate** | Ctrl+Enter | Generate with the current settings. Works while typing in a prompt |
| **■ Interrupt** | Esc | Generate turns into this button while a job runs; click it to stop |
| **＋ Queue** | Ctrl+Shift+Enter | Add a job with the current settings to the queue. The number waiting is shown, as in **＋ Queue (2)** |
| **⨯** | | Remove all pending jobs from the queue |

**▶ Generate** is not available while a job runs, so use **＋ Queue** to line up more work. Queued jobs run in order
with the settings they had when you added them.

While a job runs, the preview shows the image in progress (**LIVE PREVIEW**). You can change the preview interval
and method (`approx` or `taesd`) in Settings. Generated images are saved in per-mode folders such as
`outputs/txt2img/<date>/` and appear in the gallery.

### Prevent interruption

Turn on **Prevent interruption** to protect running work from Interrupt, cancellation through the API or MCP, and an
explicit engine restart. Use it when a long job must not be stopped by accident.

- The current state is shown next to it as **On** or **Off**.
- While it is on, you can still remove pending jobs from the queue and quit the application.
- The setting is not saved. It is **Off** each time the application starts.

## Named generation states

**Named generation states…** saves all settings of a mode under a name so you can restore them together later.
**Settings presets** overlay only the fields you choose; a named state restores everything it saved.

1. Set up txt2img, img2img or Inpaint and click **Named generation states…**. The window heading shows which mode
   you are saving.
2. Enter a name and click **Save named state**. If the name exists, you are asked before it is replaced. Each mode
   has its own names.
3. To restore, select an entry, click **Restore state** and confirm. The shared checkpoint, VAE and clip skip also
   change. Restoring does not start a generation.
4. To remove an entry, select it and click **Delete**.

A state holds the prompts, sampler, steps, CFG, size, batch counts, seeds, variation settings, checkpoint, VAE and
clip skip, Hires or img2img/Inpaint settings, script and extension controls (including disabled ones), and ControlNet
units with their image paths. The current init image and painted mask stay as they are and are not restored. Images
and models are not copied into the state.

Before restoring, SDNUI checks that the models and referenced files are available. If anything is missing, the whole
restore stops and the missing items are listed. Settings are never dropped silently.

The **History** tab records the form at every Generate or Queue click (newest 50 per mode, with a 100 px thumbnail of
the first image). Turn recording off with **Record every generation automatically**, delete entries, **Clear history**,
or **Keep as named state**. History is stored in `<app home>/config/generation-history`.

The check boxes under the tabs (Prompts; Sampling, size, seed, hires; Checkpoint / VAE / clip skip; ControlNet;
Script and extension controls; WD batch and layout) limit **Restore state** to those categories, for named states and
history entries alike. All checked restores everything.

States are stored in `<app home>/config/generation-states`. **Reload** reads changes made outside the app.
**Restore backup** returns the whole library, not only the selected entry or mode, to its previous saved version.

## Restore parameters from a PNG

PNGs made by SDNUI, A1111 and reForge carry their generation parameters. You can read them back and generate again
with the same settings.

![The PNG Info tab](shots/png-info.png)

- Drop a PNG on the **PNG Info** tab to see its parameters as text and as a table. **⇢ txt2img** restores them in
  txt2img; **⇢ img2img** and **⇢ Inpaint** send the image and its parameters to that tab.
- For the image shown in the preview, **↺ Restore** restores its parameters into the current tab. **⇢ img2img** and
  **⇢ Inpaint** send the image and its parameters to that tab.

If the recorded checkpoint is found, SDNUI switches to it. If the checkpoint or VAE is not found, the current
selection is kept and a notice tells you so.

## Keyboard shortcuts

| Key | Action |
|---|---|
| Ctrl+Enter | Generate (also while typing in a prompt) |
| Ctrl+Shift+Enter | Add to the queue |
| Esc | Interrupt the running job |
| Ctrl+V | Paste a clipboard image into the img2img / Inpaint image area |
| Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) | Undo / redo the Inpaint mask |
| Ctrl+Up / Ctrl+Down | Raise or lower the attention weight of the selection in a prompt |
| Left / Right | Previous or next gallery image (when the preview has focus) |
| Delete | Delete the selected gallery image |

On macOS, use Cmd instead of Ctrl.
