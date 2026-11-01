---
title: LoRA and embeddings
nav: LoRA and embeddings
description: How to use LoRA and textual-inversion embeddings in Stable Diffusion NUI, and how to use the Embedding Inspector, Embedding Merge and Embedding Editor tabs.
---

This page explains how to use LoRA and textual-inversion embeddings (embeddings for short) in your prompts, and the tabs for inspecting and creating embeddings.

## Where the files go

| Kind | Folder |
|---|---|
| LoRA | `models/lora/` |
| Embeddings | `models/embeddings/` |

Subfolders are searched too. If you already use A1111 or reForge, open Settings → **Model folders** → **Add A1111/reForge install...** and choose the install folder. `models/Lora`, `models/LyCORIS`, `embeddings` and the other model folders are added read-only in one step. You do not need to copy anything.

## LoRA

### Adding a LoRA to the prompt

Write a `<lora:name:weight>` tag in the prompt, the same way as in A1111. The name is the file name without its extension.

```
1girl, garden <lora:myStyle:0.8>
```

Instead of typing the tag, you can use the toolbar under the prompt boxes:

- **✚ LoRA** opens a searchable list of LoRAs. Picking one inserts `<lora:name:1>` (weight 1) at the caret. If the list says "No LoRAs found", put files in `models/lora` or add folders in Settings.
- **Networks…** opens a browser with preview images (next section).

### Tag syntax

A tag can carry more arguments than the weight.

| Syntax | Meaning |
|---|---|
| `<lora:name:0.8>` | Weight 0.8 (text encoder and U-Net) |
| `<lora:name:0.8:0.5>` | Text encoder 0.8, U-Net 0.5 |
| `<lora:name:te=0.8:unet=0.5>` | The same as above, with named arguments |
| `<lora:name:1:lbw=OUTALL>` | Per-block weights (LoRA Block Weight, below) |
| `<lora:name:1:start=5:stop=15>` | Range of steps the LoRA applies to |
| `<lyco:name:0.8>` | Treated the same as `<lora:...>` |

If the same LoRA appears twice, its weights are added together.

### Networks browser

**Networks…** opens the **Models and networks** window, which lists models as cards with preview images.

- Filter with the kind selector (**all**, **checkpoint**, **lora**, **embedding**, **vae**, **controlnet**, **hypernetwork**) and the **Search name or path** box.
- LoRA and embedding cards have an **Insert** button. It inserts into the prompt box you opened the browser from, at the caret: a `<lora:name:1>` tag for a LoRA, the name for an embedding. If you change the prompt or move the caret after opening the browser, inserting is refused; open **Networks…** again.
- Checkpoint cards have a **Select** button that loads the model.
- Preview images are found automatically next to the model file, for example `name.preview.png` or `name.png`. **Choose preview…** picks a different image and **Remove override** removes that choice. **Refresh previews** reloads the list.
- The **Named NSFW previews** menu chooses whether previews whose file reference contains "nsfw" are shown, blurred or hidden. The choice lasts until you quit the app.
- **Inspect-only folders…** adds folders that are only browsed, one `kind=folder` per line. It does not change the model folders used for generation.

### Manual LoRAs

When restored parameters, for example from a PNG, contain LoRA selections separate from the prompt tags, they are listed under the prompt as **Manual LoRAs (kept when Hires removes prompt tags)**. Each entry shows the name and the text encoder and U-Net weights, and **Remove** drops it. These LoRAs stay even when **Remove first-pass network tags from Hires** is on in Hires Fix Tweaks.

### LoRA Block Weight

The **LoRA Block Weight** AlwaysOn plugin weights each U-Net block of a LoRA separately and can limit a LoRA to a range of steps.

1. Near the bottom of the generation tab, expand the **LoRA Block Weight** section and tick the checkbox in its header.
2. In **Rules**, write one rule per line: `LoRA name : preset or weights [: start=N] [: stop=N]`. Use `*` as the name to apply the rule to every LoRA.

```
* : OUTALL
myStyle : 1,0,0,0,1,1,1,1,1,1,1,1,0,0,0,0,0 : stop=0.5
```

- The built-in presets are NONE, ALL, ALL0.5, INS, IND, INALL, MIDD, OUTD, OUTS and OUTALL. Add your own in `config/lbwpresets.txt`, one `NAME:w1,w2,...` per line.
- SD1.x takes 17 weights (BASE IN01 IN02 IN04 IN05 IN07 IN08 MID OUT03 OUT04 OUT05 OUT06 OUT07 OUT08 OUT09 OUT10 OUT11) and SDXL takes 12 (BASE IN04 IN05 IN07 IN08 MID OUT00 OUT01 OUT02 OUT03 OUT04 OUT05). BASE is the text encoder.
- start and stop take a step number or a fraction between 0 and 1 (stop=0.4 means 40% of the steps).
- An `lbw=` argument you write in a prompt tag yourself takes priority over the rules.
- In X/Y/Z plot, the **LoRA block weight (all LoRAs)** axis compares presets.

### Composable LoRA / LyCORIS

With the **Composable LoRA / LyCORIS** AlwaysOn plugin enabled, each part of a prompt split by `AND` uses only the LoRAs written in that part.

```
a red fox <lora:Fox:0.8> AND a white owl <lora:Owl:0.7>
```

- `AND` combines image predictions; it does not assign regions of the image. Each extra part costs one more model evaluation.
- With **Composable LoRA with steps** on, you can switch LoRAs by step: `[<lora:A:1>::10]` ends after step 10, `[<lora:A:1>:10]` starts at step 10, and `[<lora:A:1>|<lora:B:1>]` alternates.
- **Plot effective LoRA weights** adds a graph of the weights actually applied to the gallery.
- While this plugin is on, LoRA Block Weight `lbw=` and `start=` / `stop=` arguments cannot be used and cause an error.

!!! note "One generation per LoRA"
    The **LoRA queue** script generates the prompt once for each selected LoRA and makes a grid. See [Extensions](extensions.html).

## Textual-inversion embeddings

To use an embedding, write its file name without the extension in the prompt or the negative prompt.

```
masterpiece, 1girl, myEmbedding
```

- An embedding is loaded the first time its name appears in a prompt.
- An SD1.5 embedding used with an SDXL model (or the other way round) is skipped with a warning. SDXL embeddings need both `clip_l` and `clip_g` vectors.
- You can also insert the name with **Insert** in the Networks browser.

## Embedding Inspector tab

![Embedding Inspector tab](shots/embedding-inspector.png)

The **Embedding Inspector** tab inspects the vectors of tokens and embeddings in the loaded model, and mixes vectors into a new embedding. Load a checkpoint first.

### Inspecting a vector

1. In the top box, enter a token, a token ID with `#`, or an embedding name.
2. Click **Inspect**.

The result shows, for each vector, its magnitude, minimum and maximum, and the nearest vocabulary tokens with their scores. The graph shows up to 16 vectors of the selected encoder; for SDXL, switch between `clip_l` and `clip_g` with **Graph encoder**.

- **List embeddings** lists loaded embeddings separately from those that are in the catalog but not loaded.
- **Export vector…** writes the vector values to a UTF-8 text file.

### Mini tokenizer

Enter a short prompt in the second box and click **Tokenize** to split it into tokens, without special tokens. **Send token IDs to mixer** puts those token IDs into the mixer as rows.

### Making a new embedding with the mixer

1. In the **Mixer** box, write one token or embedding name per line. Add a weight after `|`, as in `cat|0.5`. For SDXL, pair the IDs of both encoders as `#clip_l ID/#clip_g ID`.
2. If needed, tick **Concatenate vectors** or **Combine as one vector**.
3. Choose the file with **Output file…** (`.safetensors` or `.pt`). You can record a training step in **Optional training step**.
4. Click **Save mixed**. To replace an existing file, tick **Allow overwrite**.

The saved embedding is loaded into the active model and the model list is refreshed. A file saved outside the configured embedding folders stays active only for this session. **Reset mixer** clears the mixer rows and options.

### Eval expressions

Choose an **Eval preset** (Boost, Digitize, Binary, Randomize, Sine, Comb, Crop_high, Crop_low) or write a Python expression to apply it to the vectors when saving. **Save all eval presets** saves one file per preset.

!!! warning "Eval runs arbitrary Python"
    Eval expressions run as arbitrary Python code with your user permissions. To use them, turn on **Allow custom code** in Settings and restart the engine. Do not run expressions you do not trust.

## Embedding Merge tab

![Embedding Merge tab](shots/embedding-merge.png)

The **Embedding Merge** tab combines the CLIP vectors of prompts and embeddings with expressions and saves the result as a new embedding.

### Inspecting an expression

1. In the input box, enter a prompt, an expression such as `'red hair' * 0.7 + 'blue hair' * 0.3`, or pasted PNG infotext. **Use current prompt** copies the current positive prompt.
2. Choose a grouping (**none**, **comma**, **parts**, **words**, **tokens**, **vectors**; the default is **parts**).
3. Click **Inspect / restore**.

The result appears as a table in one tab per encoder, with the text, tokens, minimum, maximum, sum, absolute sum, norm and standard deviation of each row. If you pasted PNG infotext, the expressions in it are restored.

### Expression syntax

- Put text and embedding names in quotes.
- `+` and `-` combine rows; `*` and `/` scale.
- `:N` crops or pads; `:+N` and `:-N` rotate.
- Prefix a modifier with `=` to apply it to the accumulated result. For SDXL, add `L` or `G` at the end to choose the encoder.
- Commas append text or token IDs (`00` pad, `000` end, `0000` start).

You can also use expressions in generation prompts as `<'expression'>` or `{'expression'}`. **Use in prompt** inserts the current expression into the active positive prompt.

### Saving

1. Enter a new file name. Existing files are never overwritten.
2. Check the output folder. The default is `embedding_merge` inside your embeddings folder.
3. **Safetensors + SD1/SDXL conversion** is on by default. Turn it off to save `.pt`. Converted copies go into `sd1/` or `sdxl/`.
4. Click **Save new embedding**.

After saving, only the primary file that matches the current model is activated. **Cancel** stops the running operation, but not once the save has been committed.

## Embedding Editor tab

![Embedding Editor tab](shots/embedding-editor.png)

The **Embedding Editor** tab edits each value of an embedding's vectors with sliders.

1. Choose an embedding file from the list, or enter the name of a loaded embedding. If the list is out of date, click **Refresh list**; unsaved edits are kept.
2. Click **Open / refresh**. Opening a file that is not loaded yet loads it into the current model.
3. Choose what to edit with **Encoder**, **Vector** and **Page**. Each page shows 24 values; drag a slider or type a number.
4. **Preview** queues an image that uses your unsaved edits, with the settings of the generation tab you used last. Preview is not available while a script or WD v3 batch is selected.
5. To save, tick **Overwrite the loaded embedding file** and click **Save**. A backup of the original file is made before it is overwritten, and the embedding is reloaded. Reopen it to keep editing.

- Edits stay in memory until you save. **Reset vector** discards edits to the vector on screen; **Reset all** discards every edit.
- Write `word:#RRGGBB, word:#RRGGBB` in the **Guidance** box and click **Update guidance** to show the position of each word's first token as a colored marker under the sliders. Your edits are kept.

## Model Keywords and Model Presets

Two more features handle keywords and presets per model. This is only an overview; see [Tools](tools.html) for details.

- The **Model Keywords** tab checks, saves and deletes the keywords (separated by `|`) for a checkpoint or LoRA. The **Model Keyword** AlwaysOn plugin adds those keywords to the prompt when you generate. The plugin is on by default.

![Model Keywords tab](shots/model-keywords.png)

- The **Model Presets** tab (**Model Preset Manager**) keeps generation presets, trigger words, a model page URL and a thumbnail for each checkpoint.

![Model Presets tab](shots/model-presets.png)
