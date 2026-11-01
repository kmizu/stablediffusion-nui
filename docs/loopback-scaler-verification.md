# Loopback Scaler (#76) verification

Index 183, target `loopback_scaler`. Native Script `loopback_scaler` (img2img tab, MCP `run_script`).

## Source

- Elldreth/loopback_scaler, default branch `main`, revision `184a53e6d22579c578c6ee1bf55d821479213c49`
  (2023-06-23). MIT licence, "Copyright (c) 2023 Elldreth".
- Read for behaviour only: `README.md` (sha256 `315ec8e7…ef26925`), `scripts/loopback_scaler.py`
  (sha256 `013bb82e…aff1156`), `LICENSE` (sha256 `d664856e…e86747a`). No source code, help text or assets were
  copied. The size and denoising arithmetic is restated in Scala, and the final filters call Pillow's own
  `ImageEnhance` / `ImageFilter`, which the source also uses.
- The notice and the ledger entry are not updated yet. `scripts/third_party.py` and `THIRD_PARTY_NOTICES.md` need the
  MIT entry when the target is accepted.

## Behaviour

| Area | Source | NUI |
|---|---|---|
| Tab | img2img only (`show(is_img2img)`) | `tabs = Img2Img`; txt2img is rejected ("needs an img2img init image"). |
| Controls and defaults | Dimension Increase (13 easings, Linear), Loops 1..32 = 4, Denoise Change 0.9..1.1 = 1, Max width/height 512..4096 step 64 = 1024, Use Scale off, Scale 0.5..4 step 0.1 = 1, Add Detail / Add Blur / Smoothing None..High = None, Contour off, Sharpness / Brightness / Color / Contrast 0.1..2 step 0.01 = 1, Info accordion | Same keys (upstream argument names), ranges and defaults. The Info accordion is a collapsed section of notes written for NUI. Scale is greyed out while Use Scale is off. |
| Start size | `p.width` x `p.height` (the img2img target size, not the init image size) | The same: generation width/height. |
| Final size | `floor(start * scale)` with Use Scale, otherwise max width/height | The same. |
| Per-loop size | `e = easing(i / loops)`; `calc = int(diff * e + start)`; the last loop uses the final size; height-driven when `start_h / start_w >= final_h / final_w`, otherwise width-driven; `resize_to_nearest_multiple_of_m(8)` (shorter side rounded up to 8, the other side follows the ratio with Python `round` and is rounded up to 8) | The same, including half-to-even rounding and the source's fourth power for "Ease In: Quint". One deliberate change in the width-driven branch (below). |
| Denoising | First loop uses the img2img value; after every loop `min(max(d * factor, 0.1), 1)` | The same. |
| Seeds | `fix_seed`; after every loop `seed = processed.seed + 1` | The same (random seed fixed once; subseed fixed once when variation strength is above 0). |
| Batches | batch count repeats the chain from the original init image; seed and denoising are not reset between batches; batch size forced to 1 | Batch count and carry-over are the same. Batch size other than 1 is rejected rather than silently replaced. |
| Prompt and other params | Unchanged every loop (resize mode, sampler, steps, CFG, mask...) | The same; each loop is a normal `ctx.generate`, so AlwaysOn plugins apply to every loop. |
| Final image filters | On the last loop of every batch: Sharpness, Brightness, Color, Contrast enhancers, then DETAIL, SMOOTH, BLUR repeated 0..3 times, then CONTOUR | Engine `image_tool` `loopback_scaler` (`python/sdfx/loopback_scaler.py`) applies exactly that Pillow chain to an RGB copy. |
| Outputs | Every loop is saved by `process_images` (with sample saving on). The filtered final is saved again as `img2img`, with the last loop's infotext. Only the filtered finals are returned to the gallery. | Every loop is a saved img2img output. The filtered final is saved as a new img2img image with the last loop's params and infotext. Only the finals are returned. With all filters neutral (the identity in Pillow), the last loop output itself is returned instead of a pixel-identical second file. |
| Infotext | `extra_generation_params` = Denoising strength change factor, Dimension increment factor, Add Detail, Add Blur, Smoothing, Contour, Sharpness, Brightness, Color Strength, Contrast | The same keys and values, merged into the existing extra fields (the source replaced the dict) on every loop. |
| Grid | `do_not_save_grid` | No grid. |
| Cancellation | No interrupt check of its own | Checked before every loop and around the final filters. Interrupting stops the chain. Finished batches are returned; loop images already saved stay on disk. |
| img2img color correction | Applied when A1111's global `img2img_color_correction` option is on (off by default) | NUI has no such global option, so this is the default-off path. Use the Color correction extra for the same effect. |

### Deliberate change: width-driven size

In the width-driven branch the source computes the height as `get_height_from_ratio(calc_height, ratio)`, so it uses
the height schedule where the width schedule is meant. That contradicts the documented behaviour: resolution grows
each loop, stays inside the maximum box and keeps the aspect ratio. Examples from the literal source arithmetic:

- 768x512 to max 1024x1024 (Linear, 4 loops) gives 776x344, 848x432, 896x512, 1040x688. The first loop shrinks and
  squashes the image.
- 512x512 to max 1024x2048 gives 512x512, 640x896, 768x1280, 1024x2048. The square image is stretched to 1:2.

NUI uses `calc_width` there: 768x512, 848x560, 904x600, 1040x688 (the mirror of the 512x768 portrait schedule) and
512x512, 640x640, 768x768, 1024x1024. The height-driven branch, which covers every portrait or square start inside a
square box, is unchanged and matches the source exactly.

### Kept source quirks

- The final size can exceed the maximum by up to 15 px, because both sides are rounded up to multiples of 8 (512x768
  to max 1024 ends at 688x1040).
- "Ease In: Quint" is the source's `perc^4`.
- Denoising and seeds carry on across batches instead of restarting.

### Rejected explicitly (never silently ignored or clamped)

- txt2img (no init image); batch size other than 1.
- Out-of-range, non-numeric, non-integer (loops, max width/height) or non-finite control values; unknown easing or
  filter strength names; non-boolean checkbox values. These come from MCP or edited presets. The source's Gradio
  sliders could not send them, and its helpers silently fell back to Linear or None.
- Any loop size outside the engine's 64..4096 range, for example 1024x1536 with scale 4, or 512x768 with a 4096 box,
  which rounds to 2736x4112. The error is raised before the first generation.

## Evidence

- Scala `sdfx.plugins.LoopbackScalerSuite` (14 tests) uses a fake `PluginContext`. It checks every easing formula;
  the multiple-of-8 rounding; the height-driven schedule against a statement-by-statement restatement of the source
  and against tables produced by a separate Python restatement (all 13 easings, Use Scale up and down, one loop);
  and the width-driven fix against both the corrected restatement and the literal-source numbers. The run tests
  cover per-loop size, denoising (including clamps), seed, prompt, resize mode and init chaining, the infotext keys,
  batch-count restart with seed and denoising carry-over, random seed and subseed fixing, Use Scale, filter calls
  once per batch with the exact parameters and the last loop's infotext, temp cleanup, interruption between loops
  and during the filters, every rejection before generation, and registration and defaults.
- Python `tests/test_loopback_scaler.py` checks that defaults are pixel-identical, that the output equals the literal
  Pillow chain for six mixed settings, that the order matters, the Low, Medium and High repeat counts, RGBA input
  becoming RGB, rejection of invalid values, `image_tool` dispatch, the dry-run engine request writing the processed
  PNG, and the feature in `FakeEngine.features()` and `Engine.features()`.
- Not run here: GPU generation, the real app and real SD1.5 or SDXL models. Images were not inspected.

## Real-model checks (2026-11-01)

`scripts/dev/check_loopback_scaler_real.py` drives the official MCP Python SDK 2.3.0 against the real engine
(receipts `outputs/loopback-real-sd15/receipt.json` and `outputs/loopback-real-sdxl/receipt.json`). SD1.5
`animekawa_v10` from 512x768 (max 1024) and SDXL `waiIllustriousSDXL_v170` from 832x1216 (max 1536), img2img
Crop and resize, DDIM 30 steps, CFG 7, denoising 0.3, fixed seed. Both passed every assertion:

- Defaults return only the final image. SD1.5 loop outputs were 512x768, 560x848, 600x904 and 688x1040
  (the source's overshoot past the 1024 maximum is kept); SDXL ended at 1056x1552.
- The landscape start mirrored portrait: SD1.5 768x512 ended at 1040x688, SDXL at 1552x1056.
- Ease Out: Cubic with 6 loops and factor 0.95, Use Scale 1.5 with Ease In: Sine (768x1152 on SD1.5,
  1248x1824 on SDXL), and the filter set all produced outputs; contour on and off differ from each other and from the
  unfiltered final.
- Batch count 2 returned two finals.
- Cancelling during loop 2 of 4 ended the job as cancelled, and the next run reproduced the earlier default final
  exactly.

Images were inspected. SDXL kept its colours and detail through every loop. On SD1.5 each img2img pass at
denoising 0.3 lost saturation and contrast, starting with the first same-size pass, and small dark artefacts
appeared by the last loop. That comes from repeated img2img with this SD1.5 model and VAE, not from the schedule;
the source behaves the same with A1111's img2img colour correction off (its default).
