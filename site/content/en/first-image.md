---
title: Generate your first image
nav: First image
description: A step-by-step walkthrough of choosing a checkpoint in Stable Diffusion NUI's txt2img tab, generating an image, and finding the saved image and its parameters.
---

This page walks you through generating one image in the **txt2img** tab. Before you start, complete [Install and first launch](install.html) and make sure at least one checkpoint is available.

![The txt2img generation form](shots/txt2img-form.png)

## 1. Choose a checkpoint

1. Open the **txt2img** tab.
2. In the top bar, press the button to the right of **Checkpoint** (it reads **Select checkpoint…** when nothing is selected).
3. A list opens. Type part of a name to filter it, then click the checkpoint or press Enter.

The model starts loading and the status on the right changes to **● Loading**. When it returns to **● Ready**, you can generate. The button shows the model type next to its name. To choose while looking at preview images, press **Models…**.

If no checkpoints are listed, press **⟳** (Rescan model folders) to scan the model folders again. If they still do not appear, see [Troubleshooting](troubleshooting.html#models).

The top bar also has these settings:

| Setting | Details |
|---|---|
| **VAE** | **Automatic** uses the VAE included in the checkpoint |
| **Clip skip** | 1 is the normal setting. 2 is common for anime models |

## 2. Write a prompt

The **Prompt** card has two boxes:

- The upper box (prompt): describe what you want to see.
- The lower box (negative prompt): describe what to avoid.

The top right of each box shows the approximate number of CLIP tokens and the limit of the current 75-token chunk. If brackets are unbalanced, a warning appears at the bottom right.

Select some words and press Ctrl+↑ / Ctrl+↓ to raise or lower their emphasis weight, as in `(words:1.1)`. To use a LoRA, press **✚ LoRA**, search for it, and insert a `<lora:name:1>` tag at the cursor.

## 3. Set up sampling

Set these in the **Sampling** card:

| Setting | Default | Guidance |
|---|---|---|
| **Sampler** | DPM++ 2M | Only samplers the engine supports are listed. The box on the right is the noise schedule; **Automatic** uses the sampler's default schedule |
| **Steps** | 20 | The number of denoising steps. 20–30 is typical |
| **CFG Scale** | 7 | How strongly the prompt is followed. 5–8 is typical |

## 4. Choose the image size

Set **Width** and **Height** in the **Size** card. Both are multiples of 8.

- Press **⇅** to swap width and height.
- Press **▦** to pick a common size. SD1.5 works best at 512–768, SDXL at about one megapixel (1024 × 1024, 832 × 1216 and so on).

Under **Batch**, **count** is the number of batches run one after another and **size** is the number of images generated in parallel. A larger size uses more VRAM. **Total** shows how many images you will get. Leave both at 1 for your first image.

## 5. Choose a seed

Set **Seed** to -1 to use a random seed each time. Generating with the same seed and settings reproduces the same image.

- Press **⚄** to set it back to -1 (random).
- Press **♻** to reuse the seed of the last image you generated.

## 6. Generate

Press **▶ Generate** in the bottom bar, or press Ctrl+Enter. Ctrl+Enter also works while you are typing a prompt.

While it runs, the button changes to **■ Interrupt**. To stop early, press it or press Esc. The bar to the right of the button and the step counter show the progress.

To queue another job with the same settings, press **＋ Queue** (Ctrl+Shift+Enter).

### Live preview

While generating, the preview area shows the image in progress with **LIVE PREVIEW** at the top left. You can change how often it updates and how it is drawn with **Live preview** and **Preview method** on the **General** tab of Settings. Set **Live preview** to 0 to turn it off.

| Preview method | Details |
|---|---|
| `approx` | Instant, with rough colours |
| `taesd` | Better quality, at a small extra cost |

## 7. Look at the result

When generation finishes, the preview area shows the final image.

- Ctrl + mouse wheel zooms in and out.
- Drag to pan.
- Double-click to switch between fit-to-window and 100%.

### The parameter box

Below the image you see the file name and a text box with the image's generation parameters (prompt, seed, sampler and so on). You can copy text from this box.

The buttons below it act on the image shown:

| Button | What it does |
|---|---|
| **⇢ img2img** | Sends the image and its parameters to the img2img tab |
| **⇢ Inpaint** | Sends the image and its parameters to the Inpaint tab |
| **↺ Restore** | Restores the image's generation parameters into the current tab |
| **Send to Comparison** | Compares in the **Comparison** tab. Hidden by default |
| **⧉** | Copies the image to the clipboard |
| **▤** | Opens the folder that contains the image |
| **🗑** | Deletes the image (asks first) |

With **↺ Restore**, if the checkpoint recorded in the image is found and differs from the current one, SDNUI switches to it. If it is not found, the current checkpoint stays and only the other parameters are restored. The VAE is handled the same way: it is switched if found, and the current choice is kept if not.

### Gallery

Generated images are listed in the gallery.

- Type words from a prompt or a file name in the search box to filter the list (only images that match every word remain).
- When the preview has focus, ← / → move to the previous or next image.
- Press Delete to delete the selected image.
- The right-click menu has **Send to img2img**, **Send to Inpaint**, **Restore parameters**, **Copy image**, **Copy parameters**, **Show in folder**, **Delete…** and more.
- Choose one generation from this session in the **All images** box, then press **Save ZIP…** to save all of its images to a ZIP file.

## 8. Where the image is saved

Every image is saved automatically. Images go under the output folder (`outputs` in the app folder by default), split by kind and date:

```
outputs/txt2img/2026-11-01/00000-1234567890.png
```

The file name is a running number followed by the seed. Other kinds of images are saved in their own folders in the same way. To change the output folder, set **Output folder** on the **General** tab of Settings.

The generation parameters are embedded in the PNG as a text entry named `parameters`, the same as A1111. Load the image in the **PNG Info** tab later to see the parameters and send them to a tab with **⇢ txt2img**, **⇢ img2img** or **⇢ Inpaint**. PNGs made with A1111 or reForge can be loaded the same way.

!!! note "Before you share an image"
    The PNG contains the generation parameters, including the prompt. Check what it contains before you give it to someone else.

## Next steps

- To redraw part of an image, send it to the Inpaint tab with **⇢ Inpaint**, paint a mask and generate.
- For generation modes, Hires. fix and more, see the [generation guide](generate.html).
