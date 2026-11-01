---
title: ControlNet
description: How to set up ControlNet units in Stable Diffusion NUI, and how ControlNet Fastload loads, saves and browses ControlNet settings stored in images and .cni files.
---

ControlNet guides the composition of an image with a control image such as line art, a depth map or a pose. In
SDNUI you use it from the **ControlNet** section of the txt2img, img2img and Inpaint tabs.

The second half of this page covers **ControlNet Fastload**, which saves ControlNet settings in images or `.cni`
files and loads them again.

## Before you start

Put ControlNet models in `models/controlnet/`. If you added an existing A1111 / reForge install under Settings →
**Model folders**, its `models/ControlNet` folder is used as well.

These preprocessors work with the standard setup: `none`, `invert`, `inpaint`, `recolor_luminance`,
`recolor_intensity`, `tile_resample`, `tile_colorfix`, `blur_gaussian`, `canny`, `depth`, `depth_marigold` and
`normal_marigold`. `openpose`, `lineart`, `lineart_anime`, `softedge_hed`, `mlsd` and `normal_bae` appear only when
the optional `controlnet_aux` package is installed (it is listed as a comment in `python/requirements.txt`).

## Unit settings

The **ControlNet** section has three tabs, **Unit 1**, **Unit 2** and **Unit 3**, so you can use up to three units at
once. The tab of an enabled unit is marked `●`, and the section header shows how many units are on, for example
`2 active`.

![The txt2img form with the ControlNet and extension sections](shots/txt2img-form.png)

1. Open the tab of the unit you want and turn on **Enable**.
2. Drop, paste or click to open a control image. In img2img and Inpaint, leave it empty to use the init image as
   the control image.
3. Choose a ControlNet model under **Model**.
4. Under **Preprocessor**, choose how the control image is turned into a control map. Choose `none` if the image
   already is a line drawing or depth map.
5. Adjust **Weight**, **Start**, **End** and **Control mode** if needed, then generate.

| Setting | What it does |
|---|---|
| **Enable** | Use this unit |
| **Model** | ControlNet model (from `models/controlnet`) |
| **Preprocessor** | Turns the control image into a control map. `none` uses the image as it is |
| **Resize mode** | How the control image fits the output size: **Just resize**, **Crop and resize** or **Resize and fill** |
| **ControlNet-compatible processing** | Process the unit the way the A1111 ControlNet extension does (see below) |
| **Control mode** | Whether the prompt or ControlNet leads (see below) |
| **Processor res** | Input resolution for canny (see below) |
| **Pixel perfect** | Derive the canny input resolution from the output size |
| **Weight** | Control strength (0 to 2) |
| **Start** | Fraction of the steps where control starts (0 to 1) |
| **End** | Fraction of the steps where control ends (0 to 1) |

You cannot generate, and a warning appears at the top of the form, when an enabled unit has no model, when a
txt2img unit has no control image, or when **Start** is greater than **End**.

### Control mode

| Mode | Effect |
|---|---|
| **Balanced** | The default. Prompt and ControlNet are weighted evenly |
| **My prompt is more important** | Softens the control so the prompt leads |
| **ControlNet is more important** | Applies control only to the conditional (prompt) pass, which strengthens ControlNet |

On SDXL, both non-Balanced modes weaken the effect on the composition considerably. This follows the same
calculation as the A1111 ControlNet extension.

### Canny resolution and Pixel perfect

**Processor res** and **Pixel perfect** apply when the preprocessor is `canny`. `none` and `invert` ignore them, as
in A1111, and the fields are disabled for the other preprocessors.

- **Processor res** is `-1` or 64 to 2048. `-1` processes at full size, or at 512 when **ControlNet-compatible
  processing** is on.
- **Pixel perfect** derives the input resolution from the output size.

### ControlNet-compatible processing

Turn this on to process the unit the way the A1111 ControlNet extension does: control maps are interpolated and
edges thinned as in A1111, canny runs at the **Processor res**, and canny and MLSD control is softened in the Hires.
fix second pass. When it is off, SDNUI keeps its earlier processing. Units loaded by ControlNet Fastload always use
this processing.

### Preview the preprocessor

Click **◉ Preview preprocessor** to run the preprocessor on the current control image. The result appears as
**Preprocessor result**, so you can check the detected lines or depth before generating. The preview is cleared
when you change the control image or the preprocessor.

## ControlNet Fastload

ControlNet Fastload stores the ControlNet units and control images used for a generation, so you can load them later
and reproduce the same setup. It re-implements the behaviour of the A1111 extension sd-webui-controlnet-fastload,
and the files it saves use that extension's format.

You use it in three places:

- The AlwaysOn plugin **ControlNet Fastload** in the generation tabs loads and saves units automatically on every
  generation.
- The **ControlNet Fastload** tab shows what a file contains and sends units to a generation tab.
- The **ControlNet Fastload Filter** tab filters the images in a folder by their ControlNet metadata.

!!! note "Reading files safely"
    Fastload data is stored as a pickle, but SDNUI never executes it. It reads only an allowed set of structures.

### Load and save while generating (AlwaysOn plugin)

The txt2img, img2img and Inpaint tabs each have a **ControlNet Fastload** section. Turn it on with the checkbox in
the section header and set:

| Setting | Choice | What it does |
|---|---|---|
| **Mode** | `load_only` | Load only. Load units from the given file before generating |
| | `save_only` | Save only. Save the units used with each generated image |
| | `load_save` | Load & Save. Do both |
| **Priority** | `current_first` | Current first. If the form has an enabled unit, use it and do not read the file |
| | `loaded_first` | Loaded first. Use the units loaded from the file |
| **Input image or .cni path** | | The Fastload image or `.cni` file to load |
| **Save format** | `embedded` | Append the data to the generated image |
| | `sidecar` | Write a `.cni` file with the same name next to the image |
| | `both` | Both embedded data and a `.cni` file |

When loading, non-empty data embedded in an image takes priority over a `.cni` file of the same name. Model names
stored with an A1111-style `[hash]` suffix are matched to the corresponding model in SDNUI's catalog.

### The ControlNet Fastload tab

The **ControlNet Fastload** tab shows what a Fastload image or `.cni` file contains. From there you can send units
to a generation tab or save a separate copy.

![The ControlNet Fastload tab](shots/controlnet-fastload.png)

1. Enter the path of a file in the top field, or choose it with **Browse**, then click **Inspect**. The saved
   settings appear in the box below, including any settings that cannot be applied.
2. Choose a unit number and click **Preview images** to see that unit's control image and mask.
3. Click **Send unit to txt2img** or **Send unit to img2img** to replace the unit with the same number in that tab.
   Units with settings the current ControlNet form cannot apply cannot be sent.

To pick up the units of a generation tab, click **Capture txt2img** or **Capture img2img**. The captured units are
kept in memory only. To save them as a new file, enter a destination in the **New .cni file** field and click
**Save .cni copy**. Existing files are never overwritten.

### The ControlNet Fastload Filter tab

The **ControlNet Fastload Filter** tab filters the images in a folder by their ControlNet metadata and sends them to
a generation tab.

1. Enter a folder under **Image directory**, or choose it with **Browse**, and click **Scan**.
2. Choose a **Filter field** and **Filter value** and click **Add filter**. You can add several filters; only images
   that match all of them are shown. To drop a filter, select it in the list and click **Remove filter**.
3. Results are shown as thumbnails, 36 per page. Move between pages with **First**, **Previous**, **Next** and
   **Last**, or enter a page number and click **Go**.
4. Select an image to see its preview. Then use:

| Button | What it does |
|---|---|
| **Inspect Fastload data** | Open the image's embedded data, or the `.cni` file of the same name, in the ControlNet Fastload tab |
| **Send to txt2img** / **Send to img2img** | Send the image and its metadata |
| **Send parameters to txt2img** / **Send parameters to img2img** | Apply the metadata without changing the input image |
| **Set ControlNet image (txt2img)** / **Set ControlNet image (img2img)** | Replace only the Unit 1 control image; its other values stay |

Symbolic links inside the folder and oversized images are skipped, and the status line reports how many.

!!! note "Images made by SDNUI cannot be filtered"
    Filter choices come only from images whose metadata contains A1111-style `ControlNet N:` entries, which A1111
    and Forge write. SDNUI does not write these entries, so its own images are not covered by the filters.

### Settings that are rejected

When a unit has settings that SDNUI cannot reproduce faithfully, SDNUI does not drop them silently. It rejects the
unit and names the settings involved. This applies to:

- Inpaint or CLIP masks
- Batch inputs
- Non-default advanced weighting or region masks
- Settings for only one Hires. fix pass (`hr_option` other than Both)
- Processor res or Pixel perfect for preprocessors other than `none`, `invert` and `canny`
- More than three units (SDNUI has three)
- Preprocessors that the engine does not have

Settings that do not change ordinary output, such as `low_vram` and `save_detected_map`, are accepted. Their values
are kept when SDNUI saves the loaded units again.

### Protected sessions

While image scrambling is turned on for the session in the **Image Scrambling** tab, Fastload data cannot be saved,
because Fastload files contain the original control images and masks in readable form. **Load only** still works
during such a session.

### Limitations

- SDNUI does not crop ControlNet inputs to the img2img **Only masked** region, as the A1111 ControlNet extension
  does.
- Compatibility has not been checked against files saved by a running A1111 or Forge. It has been checked with files
  SDNUI writes in the extension's format and with test files made by the extension's own code.

### From MCP

The MCP server's `controlnet_fastload` tool inspects files and reports format versions. To load or save units while
generating, pass `controlnet_fastload` settings in the generation tool's `always_on` argument. The
`list_controlnets` tool lists the available ControlNet models, preprocessors and control modes. See [MCP](mcp.html)
for details.
