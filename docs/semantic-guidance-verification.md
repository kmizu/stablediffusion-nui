# Semantic Guidance verification (#115)

Source: v0xie/sd-webui-semantic-guidance `bdbd03c92ab5e2d3926cedd7364da61ed40e48d1` (GPL-3.0), read as a behaviour reference; NUI re-implements it.

## Behaviour

- AlwaysOn plugin `semantic_guidance` with the source controls and defaults: prompt, negative prompt, warmup 10, edit guidance scale 1.0, tail percentage threshold 0.05, momentum scale 0.3, momentum beta 0.6.
- Like the source (and unlike the SEGA paper), it edits the text conditioning. From the warmup step on, every model evaluation adds `scale * strength * (concept - uncond)` to the positive conditioning wherever that magnitude exceeds the concept's mean + z(1 - tail) * std (unbiased std over batch, tokens and channels). Concepts are split on commas; each concept's first A1111 attention segment gives its text and strength, and negative-prompt concepts get negative strength. Concepts are encoded like the main prompt (clip skip, empty-chunk padding).
- The source's momentum update multiplies by a velocity that starts at zero, so momentum never changes the result. NUI keeps that, so the momentum controls have no effect.
- SDXL also edits the pooled embedding, thresholded over the pooled embedding plus the size embedding, as the source's `vector`.
- Steps are counted per change of timestep, so several evaluations at one timestep (PAG) share a step; each pass (base, hires) counts from zero. With CFG the unconditional rows are the batch's own; without CFG the pipeline's negative embedding is used. CFG+PAG edits both positive groups.
- Infotext uses the source keys (`SEGA Active`, `SEGA Prompt`, ...). PNG Restore and MCP `inspect_generation_metadata` restore the plugin; other PNGs switch it off in the desktop form.
- XYZ axes use the source names. Unlike the source, they apply only while the plugin is enabled.
- Rejected: Neutral Prompt or Composable LoRA branch generation and PhotoMaker (no prompt embeddings).

## Evidence (2026-11-01)

- CPU: the summed edit equals a literal restatement of the source `sega_routine_batch` (including the zero-velocity momentum and warmup gate); concept parsing, tail edges, hook warmup counting, positive-row-only edits, padding, no-CFG and PAG layouts, SDXL pooled thresholding and rejected combinations (`python/tests/test_semantic_guidance.py`, 10 tests). Scala: controls, hook fields, source infotext keys, PNG round trip with commas, replay rejection and XYZ axes (`SemanticGuidanceSuite`).
- Real models through the official MCP SDK (`scripts/dev/check_semantic_guidance_real.py`; receipts `outputs/sega-real-sd15-s1.0`, `outputs/sega-real-sd15-s0.3`, `outputs/sega-real-sdxl`): SD1.5 `animekawa_v10` 512 px and SDXL `waiIllustriousSDXL_v170` 832x1216. Edit scale 0 reproduced the baseline exactly; adding a concept, removing one and warmup 0 each changed the image; PNG replay through `inspect_generation_metadata` reproduced the image exactly; a hires pass completed.
- Images inspected. SD1.5 at scale 1.0 added a red scarf while keeping the composition; warmup 0 changed the composition; removing "trees" weakened the trees with visible artifacts; scale 0.3 changes were subtle. Scale 3.0 destroyed the images. On the real SD1.5 text encoder the source formula at scale 1.0 edits about 15% of the elements with an edit norm of about 85% of the conditioning, so strong effects follow from the source method itself. SDXL at scale 1.0 with warmup 5 changed details subtly; warmup 0 added a clear red scarf; removing "trees" turned them into odd shapes.

## Limits

- Not compared against a running A1111 with the extension.
- Momentum controls have no effect, as in the source.
