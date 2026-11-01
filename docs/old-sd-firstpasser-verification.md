# Old SD firstpasser (#94, ledger 303) verification

Upstream: light-and-ray/sd-webui-old-sd-firstpasser at `74eb6b965bc5dbb65be99999b4a86bfbb0ee2b13`. The repository has no licence file, so
nothing but its README was used (the behaviour: firstpass with an SD 1.x / SDXL checkpoint, upscale, main-checkpoint pass, Hires fix
supported; the README screenshot was viewed to name the controls). No upstream code was read or copied, which is recorded in
`THIRD_PARTY_NOTICES.md`.

## What was built

A native txt2img Script, `old_sd_firstpasser` (`OldSdFirstpasserPlugin.scala`), with no engine hook. The host's per-generation
`checkpoint` field does the model switching, and two small host additions support it (`PluginContext.checkpointInfo` and
`loadCheckpoint`).

1. The whole batch is generated with the firstpass checkpoint (`Firstpass steps`, size = 512 px for SD 1.x / 1024 px for SDXL on the
   shorter side, or `Firstpass resolution`), as a transient generation.
2. Each image is upscaled to the target size with the chosen upscaler (when it is smaller than the target; otherwise the engine resizes).
3. The main checkpoint refines it as img2img with `Firstpass denoising` and the request's steps, sampler, CFG and seed.
4. Hires fix, when requested, is one more img2img pass on the main checkpoint (hires steps, sampler, scheduler, CFG, prompts,
   denoising, upscaler; latent upscalers resize instead).
5. The last pass is saved once, with the request's own infotext (Steps, Size, Seed, Hires fields) plus the `Old SD firstpasser ...`
   keys, so PNG info, the GUI restore and `inspect_generation_metadata` can replay it.

Choices where the README is silent are listed in `scripts/extensions_matrix.py` (`SCOPE_NOTES["old_sd_firstpasser"]`): firstpass size,
Auto network type (the other family than the main checkpoint, LoRA metadata is not read), LoRA/ControlNet routing (firstpass only
unless `LoRAs in the main pass`), AlwaysOn plugins on both passes, main checkpoint reloaded after an interrupted run. Not provided: the
img2img-tab variant with an empty init image, the LoRA-metadata type guess, and the upstream infotext key names (undocumented).

## Tests

- `OldSdFirstpasserSuite` (19 tests): sizes, upscale factor, network choice, settings rejection, the pass sequence on a fake host
  (checkpoints, VAE, sizes, steps, seeds, denoising), LoRA and ControlNet routing, batches (one firstpass call, then one main pass per
  image), SDXL pass on an SD 1.5 main model, hires pass, latent hires upscaler, infotext and form replay round trip, interruption and
  checkpoint reload, temporary file cleanup.
- `OldSdFirstpasserReplaySuite` (GUI form restore) and an `InspectGenerationMetadataSuite` case (typed script candidate, keys consumed).
- Full `sbt test`: see the commit message. No Python code changed.

## Real models, official MCP SDK (`scripts/dev/check_old_sd_firstpasser_real.py`, stdio, model_offload)

Every run: baseline `txt2img`, then `run_script` cases, each asserted on decoded RGB bytes.

| run | main | firstpass | size | result |
|---|---|---|---|---|
| `outputs/ofp-real-sd15` | SD1.5 `animekawa_v10` | SD1.5 `aiomonstergirls_v4`, network SD1 | 640x768 | success |
| `outputs/ofp-real-sdxl` | SDXL `waiIllustriousSDXL_v170` | SD1.5 `animekawa_v10`, network Auto | 832x1216 | success |
| `outputs/ofp-real-sdxlpass` | SD1.5 `animekawa_v10` | SDXL `waiIllustriousSDXL_v170`, network SDXL | 640x768 | success |

Asserted in each run:

- the script output has the requested size and differs from the plain main-checkpoint image;
- the same request twice gives identical pixels; image 0 of a `n_iter: 2` batch equals the single-image result and image 1 differs;
- denoising 0.25 / 0.55 / 0.85 give three different images; a different firstpass resolution changes the image; with the upscaler set
  to `None` the image changes when the firstpass is smaller than the target (SD1.5 -> 640x768, SDXL 832x1216) and is identical when the
  firstpass is larger (the SDXL pass on an SD 1.5 model: no upscaler runs);
- `inspect_generation_metadata` returns the script candidate (`steps` 16, `denoising` 0.55) with no `Old SD firstpasser` key left in
  `unapplied_fields`, and `run_script` with that candidate plus `generation_args` reproduces the PNG pixel for pixel;
- Hires fix (`scale` 1.5 / 1.25, Lanczos, 10 steps, 0.45): the output has the hires size (960x1152, 1040x1520, 960x1152), the PNG records
  the base Size/Steps and the Hires fields, and its replay is pixel-identical;
- an async `run_script` cancelled at about 5 to 6 % progress (during the firstpass) ends as `cancelled`, and the next `txt2img` without
  a `checkpoint` argument is pixel-identical to the baseline, i.e. the main checkpoint was reloaded.

Images were looked at as contact sheets (`outputs/ofp-real-*/sheet*.jpg`):

- SD1.5 + SD1.5 firstpass: coherent anime portraits; the firstpass composition (straw hat, dark blue dress, sunflowers) carries into the
  result, a higher denoising drifts away from it, and the baseline (a different image) shows the firstpass really steers the result. The
  results are lower in contrast than the plain `animekawa_v10` image; the same colour loss was noted for img2img loops in
  `loopback-scaler-verification.md`, and was not investigated further here.
- SDXL main + SD1.5 firstpass: clean, sharp SDXL-quality images (no SD1.5 artefacts); the firstpass pose and palette (blue dress, flower
  field) carry over at 0.25 and 0.55 and change at 0.85; the hires image keeps the content at 1040x1520.
- SD1.5 main + SDXL firstpass: coherent and hazy at 0.55 like the first case; `after_cancel` shows the baseline image again.

## Caveats

- Loading both checkpoints one after the other costs time (an SDXL main checkpoint is reloaded for the main pass; the SDXL runs took
  several minutes for the 14 generations of the check).
- AlwaysOn plugins apply to both passes because the host applies them to every generation of a script; family-specific plugins
  (IP-Adapter, ControlLLLite, PhotoMaker) are therefore not meaningful in a cross-family run.
- The firstpass infotext (its LoRA hashes and embeddings) is copied into the saved infotext, but ControlNet units are not restored
  from the PNG, as for every other script.
