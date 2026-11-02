# Corridor Crawler Outpainting (#25) verification

Index 108, target `corridor_crawler`. Native Script `corridor_crawler` (img2img tab, MCP `run_script`). Pure Scala
(`CorridorCrawlerPlugin`); no engine, hook or Python change.

## Source

- brick2face/corridor-crawler-outpainting, default branch `main`, revision
  `680aed7e96fd2785387949222acbb25dee16b332`. Licence GNU AGPL-3.0 (`LICENSE`, sha256 `8486a10c…07ef`; the
  verbatim text is `licenses/brick2face__corridor-crawler-outpainting.txt`).
- Read for behaviour only: `README.md` (sha256 `bcec09b5…e467`) and `scripts/corridor-crawler-outpainting.py`
  (sha256 `8a8df146…358e`). No code, text or image was copied. The upstream `default-mask.png` and the three prefab
  PNGs (AGPL assets) are not bundled: the mask is derived from the window rectangle and the built-in prefab is drawn
  by NUI (`brickCorridor`).

## What the source does

img2img script for the "Inpaint upload" tab. The user supplies a start image and `default-mask.png`. Each of N steps
copies a 512x512 prefab, `thumbnail()`s the previous picture to 256x256 and pastes it at (127, 81), runs the normal
inpaint on that composite (the mask protects the 256x256 centre, everything around is repainted) and feeds the result
to the next step. The model is meant to be `512-depth-ema` (depth-to-image), which turns the prefab into hallway
geometry. Optionally it builds a GIF: for each pair of steps the newer picture is doubled to 1024x1024, the older one
is pasted at (254, 162), and a crop box shrinks from the whole canvas to the older picture over `frames` frames; the
frame list of all pairs is saved to a path from a text box.

## Behaviour

| Area | Source | NUI |
|---|---|---|
| Tab | img2img "Inpaint upload" only | `tabs = Img2Img`. A plain init image is used; txt2img is rejected. |
| Mask | Hand-selected `default-mask.png` (white outside a 256x256 black centre at x 127..382, y 83..338) | Built automatically from the window (the source lists "make mask selection automatic" as a TODO). White = repaint, black = the window. A user mask, "Only masked" and mask inversion are rejected (they would contradict the built mask). Mask blur, masked content (fill), denoising strength, sampler, steps, CFG are the user's img2img settings, unchanged. |
| Window | Thumbnail size 1..768 and position 1..768 px, tuned for a 512 canvas | Percent of the canvas: width/height 10..90 (default 49.6 = 254 px at 512), left 0..90 (24.8 = 127), top 0..90 (15.8 = 81). The defaults reproduce the source box on 512x512 and scale with any canvas, so SDXL sizes work. |
| Pasting | `thumbnail()` keeps the aspect, never enlarges, anchors top-left | The previous picture is stretched to the window (a window with the canvas aspect, the default, is identical). |
| Prefab | A picture chosen from `prefabs/` or any image, shown as a 512x512 control | `Brick corridor (built in)` (one-point perspective corridor drawn at the canvas size around the window) or `Custom image` (any file, stretched to the canvas, transparency flattened onto grey). |
| Steps | 1..30 | 1..30 |
| Seeds | The same `p` every step: a random seed is fixed once, so every step uses one seed | The same: the seed is fixed once and reused for every step. |
| Outputs | Normal `process_images` saves each step; the UI shows the last | Every step is saved as an img2img output and returned in order; a grid of the steps (up to 4 columns) is added when there are two or more (switch `make_grid`). |
| Infotext | None | Adds `Corridor Crawler steps`, `Corridor Crawler step` (i/N), `Corridor Crawler prefab`, `Corridor Crawler window` (x,y,w,h) and, when used, `Corridor Crawler mask shrink`. |
| Animation | Optional; frames per step 0..120 (0 = slideshow, but the code divides by `frames - 1`), ms per frame 1..1000, size 1..1024, save path in a text box; the start image is not part of the walk (one step gives no animation) and the end frame of a pair is repeated by the next pair; only valid for the default window | `animation` checkbox, frames per step 0..120, ms per frame 10..1000 (the GIF clock is 10 ms), size 64..1024 (longest side, never enlarged). Works for any window. The start image is the last frame of the walk, so one step already animates; an end frame is not repeated; 0 = slideshow of the pictures, newest to oldest. The GIF loops forever and is written beside the last step as `<name>-corridor.gif`. Refused up front when plaintext video is not allowed (image scrambling session). |
| Cancellation | None | Checked before every step; the finished steps are returned and no GIF is written; the temporary composite and mask files are deleted. |
| Batch | Only the first image is chained | Batch size and count must be 1 (rejected, not silently ignored). |

Additions of NUI: kept-area shrink (0..64 px) that lets the repainted ring overlap the window border, per-step infotext,
grid, and a prefab that does not need any file.

## Not provided

- **The depth-to-image model.** The engine has no depth2img pipeline, so `512-depth-ema.ckpt` / SD 2 depth
  checkpoints are not supported. The script runs on any SD1.5 or SDXL checkpoint through the normal inpaint path. A
  non-depth model does not get the prefab geometry from a depth map; it only sees the prefab through the masked
  content and denoising strength. Use denoising 0.8-0.95 and "original" masked content so the prefab layout carries
  into the ring. At denoising 1.0 with "original" the prefab is fully re-noised and the corridor only follows the
  window edge. This was not run with a depth model, so the geometry quality that model gives is unverified.
- The shipped prefab and mask images (see Source), the free-text save path (the GIF goes beside the last step), a
  batch size or count above 1, and the "Inpaint upload" requirement (a plain init image is used instead).

## Verification

All numbers below are measured. Images were opened and looked at.

### Scala

- `CorridorCrawlerSuite`, 22 tests, pass: default window 127,81,254,254 on 512x512, scaling and rejection of windows
  that leave the canvas, mask polarity and shrink, composite pasting only into the window, built-in prefab has
  distinct ceiling/floor/wall colours, custom prefab stretch and transparency, chaining (the second composite's
  window holds the first output; the ring is the prefab; the corner of the window shows the first output's ring and
  the centre the start image), the user's denoising/fill/blur kept and temporary files deleted, a random seed fixed
  once, grid presence, mask shrink and infotext, custom prefab naming and a missing file, 12 rejections (txt2img,
  batch size/count, own mask, only masked, inversion, bad numbers), interruption after two of four steps with no GIF,
  scrambled sessions refused before any generation, zoom frames moving from the newer picture towards the older one,
  the walk length `frames x pairs + 1`, slideshow order, size fitting, a real GIF written with ImageIO and read back
  (3 frames, delay 7 for 70 ms, loop extension, first frame red, last blue) and a full run writing a 7-frame GIF
  beside the last step.
- Full `sbt test` and `ui/assembly` pass (see the commit message for the counts at commit time).

### Real runs through the official MCP SDK (`scripts/dev/check_corridor_crawler_real.py`)

`mcp==2.3.0` client, the app jar built from this branch, engine `python/.venv-smoke`, seed 4242, DDIM 24 steps,
denoising 0.9, mask blur 4 (default). Start picture from txt2img with a dungeon-hallway prompt.

| | SD1.5 `animekawa_v10` 512x512 (model_offload) | SDXL `waiIllustriousSDXL_v170` 832x1216 (sequential_offload) |
|---|---|---|
| 3 steps, defaults | 3 images + 1 grid | 3 images + 1 grid |
| window of step k+1 vs previous picture shrunk (mean abs diff 0..255) / same-size wrong region | 2.83 / 39.5, 2.10 / 24.9, 1.75 / 21.0 | 2.67 / 41.0, 2.90 / 34.8, 2.79 / 32.7 |
| repeat of the same run | all 3 pixel-identical | all 3 pixel-identical |
| `mask_shrink` 16, 1 step | differs from the default step 1; centre kept (diff < 40) | same |
| custom prefab (own PIL drawing), window 40% at (30%, 30%), 2 steps | 2 images; window diff 1.75 | 2 images; window diff 3.19 |
| GIF, 2 steps, 6 frames/step, 60 ms, size 200 | 13 frames, 200x200, 60 ms, loop 0 | 13 frames, 137x200, 60 ms, loop 0 |
| slideshow GIF (frames 0), 2 steps | 3 frames | 3 frames |
| batch size 2 | error "batch size and batch count must be 1" | same |
| 40 steps | schema error "steps must be in 1..30" | same |
| cancel in step 2 (SD1.5) / step 1 (SDXL) of an 8-step job, then the default job again | cancelled; the repeat is pixel-identical to the first run | same |

What the images showed:

- SD1.5: the 512x512 start (a stone hallway with a torch) becomes the far end of a wide pillared hall with a red
  carpet in step 1; step 2 shows the same hall pulled back, and step 3 a long colonnade. Each earlier picture is
  recognisable inside the next one. With the custom prefab (blue-grey vaults) the ring takes the prefab's colours and
  the window shows the previous picture. The GIF starts on the newest hall and ends on the start picture.
- SDXL: the start (brick corridor with a red carpet and wall torches) is extended into longer corridors with the same
  carpet and torches. Quality is clearly better than SD1.5.
- Known artefact: a thin rectangle outline marks the window border in several steps (SDXL more than SD1.5), and the
  repainted ring is sometimes slightly different in tone from the window. It was not removed; whether a larger
  `mask_shrink` or mask blur hides it was not measured.

Output folders: `outputs/corridor-real-sd15` and `outputs/corridor-real-sdxl` (not committed; each has `receipt.json`).

### Not verified

- Any depth-to-image checkpoint (not supported by the engine).
- The JavaFX form was not driven; the controls are the generic plugin form shared by all scripts (the script
  dropdown and its controls are exercised by the existing UI suites).
- Windows package contents: no new payload file (the prefab is drawn in code), so `scripts/package_windows.ps1` needs
  no allowlist change.
