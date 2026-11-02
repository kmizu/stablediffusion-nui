---
title: Character Searcher
nav: Character Searcher
description: How to cut characters out of images and videos, find them by image or tags and export them as a training set with the Character Searcher tab of Stable Diffusion NUI.
---

The **Character Searcher** tab collects the characters of many images or videos into a *dataset*, finds them again
by a search image and by tags, and exports the result as a folder of images with caption files, ready for LoRA
training. It is the native counterpart of the A1111 extension "chara-searcher". While the tab is open the preview
and gallery on the right of the main window are hidden so the grid can use the full width.

![Character Searcher tab](shots/character-searcher.png)

## Import

1. Type a name in **Import** (a new dataset, or an existing one to add to it).
2. Press **Upload Images…**, **Upload Images Directory…** (sub folders are included) or **Upload Video File…**. The
   paths are listed in the box; you can also type or paste them, one per line.
3. Press **Import**. The first run downloads the models once (about 1.3 GB in total, checked against pinned hashes);
   later runs start at once. **Cancel** stops at the next image.

For every image the app finds each character, cuts it out with a transparent background, saves it as
`<folder>_<image>_<n>.png`, tags it into `<same name>.txt` and stores a CLIP embedding for the similarity search.
For a video one frame every **Video s** seconds is used (4 by default). Two characters in one picture become two images.

| Option | Meaning |
|---|---|
| **Mask** | How sure the cut-out has to be that a pixel belongs to the character (0.6). Raise it for a tighter outline. |
| **Instance** | Minimum score of a detected character (0.3). Lower it to find small or partly hidden characters. |
| **Padding** | Margin around the character as a fraction of its size (0.1). |
| **Tags** | Tagging threshold (0.35). |
| **Video s** | Seconds between the frames taken from a video (4). |
| Exclude box | Tags that are never written to the captions. |

Importing into an existing dataset keeps what is there and adds only the new cut-outs. Files that cannot be read are
listed in the status line and skipped. The datasets live in the `chara-searcher` folder of the application, one folder
each, in the same layout as the extension (`embedding.npz`, `embedding.json`, the PNGs and `.txt` files).

## Search

Select one or more datasets under **Target Datas** (Ctrl-click for several).

- **Search Image…** takes any picture; the result is sorted by similarity and everything below **Threshold** is hidden.
  **Clear** removes it.
- **Search Tags** lists tags that every result must have; **Negative Tags** lists tags that no result may have.
  Comma separated. Case, underscores and escaping backslashes do not matter (`long_hair` finds `long hair`). Press
  **Search** or Enter.
- **Click on the Image** chooses what a click on a thumbnail does: **Exclude** hides that image (see **Reset Excluded
  Images** to show them again) and **Threshold** sets the threshold to the similarity of the clicked image, hiding
  everything less similar. Double-click shows an image in the main preview. Hover for the tags.

The grid shows the first 300 hits; the summary above it gives the real count. Export always takes every hit.

## Export

Choose a folder, optionally add **Additional Tags** (written first in every caption) and **Exclude Tags** (removed from
every caption), and press **Export**. Every displayed image is copied as `NNNNN_<name>.png` with a `NNNNN_<name>.txt`
caption. Existing files are never overwritten.

## Differences from the extension

The extension cuts characters out with an instance segmentation model that needs mmdet and mmcv. SDNUI ships neither;
it finds the characters with an anime person detector (YOLOv8, MIT), cuts them out with the ISNet anime segmentation
(Apache-2.0) and keeps only the pixels connected to each detection, so neighbours are not mixed up. Tags come from the WD
ViT tagger v3 and embeddings from CLIP ViT-B/32, as in the extension. Video frames are taken by time, not from key
frames. The DuckDuckGo image-search branch of the extension is not part of the app. All processing runs on the CPU.
