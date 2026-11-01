# Aesthetic Score verification (#3)

Ledger target `aesthetic_score`, indices 95, 96 and 97. One AlwaysOn plugin `aesthetic_score` (hook `aesthetic_score`) and one Extras plugin `aesthetic_score_extras`; engine side `python/sdfx/aesthetic_score.py`, Scala side `plugins/.../AestheticScorePlugin.scala`.

## Sources and licences (checked at pinned commits)

| index | upstream | pinned | licence | how used |
|---|---|---|---|---|
| 95 | tsngo/stable-diffusion-webui-aesthetic-image-scorer | 1c3ab7a | MIT | source read; behaviour re-implemented, nothing copied |
| 96 | vladmandic/sd-extension-aesthetic-scorer | b60c3c8 | none published | README only; its script and weights were not read or used |
| 97 | p1atdev/stable-diffusion-webui-cafe-aesthetic | d1c7ee8 | AGPL-3.0 | README and the cafeai model cards only; nothing copied |

Models (never bundled; pinned size and SHA-256 are checked before loading; `.pth`/`.bin` load with `weights_only`): improved-aesthetic-predictor weight (Apache-2.0, commit 6934dd8), crowsonkb simulacra heads (MIT, 701f767; the same files vladmandic ships), CLIP ViT-L/14 and ViT-B/16 conversions (openai/CLIP is MIT; the Hugging Face cards state no licence), cafeai aesthetic / style / waifu (AGPL-3.0 per model card). Notices are in `THIRD_PARTY_NOTICES.md` and `licenses/`.

## Behaviour

- Scores the final image (after ADetailer, hires, censoring, upscale) just before it is saved; pixels never change.
- `aesthetic_score` = MLP on the L2-normalised CLIP ViT-L/14 embedding, one decimal (infotext `aesthetic_score` and PNG chunk, as in the MIT source). Simulacra: linear head on the normalised embedding times sqrt(dim), two decimals, infotext `Aesthetic Scorer ViT-L/14` or `ViT-B/16` and PNG chunk `score`. Cafe: softmax probabilities; infotext `Cafe aesthetic|style|waifu: <top class> <p>`, PNG chunk `cafe_*` holds all classes.
- Optional: sampler, cfg_scale, sd_model_hash, seed chunks; experimental md5 `hash`. The hash uses the job seed (identical for every image of a batch, as upstream's `p.seed`) and this app's sampler names, so it does not match the web UI's.
- Settings are recorded in `Aesthetic score settings` and restored from PNG and by MCP `inspect_generation_metadata`; a PNG carrying only the original extension's `aesthetic_score` also enables the improved scorer.
- Extras plugin: same scorers on one image, result in the infotext; Extras batch from a directory scores a folder (replaces cafe-aesthetic's batch tab).
- Models wait in RAM and visit the GPU only while an image is scored; Force CPU keeps them on the CPU.

## Not provided

Windows property tags and categories (pywin32), EXIF comments, XYZ axes, a dedicated cafe-aesthetic tab (Extras covers it), CLIP ViT-B/32 simulacra head. The dry-run engine scores its synthetic images with the real models (meaningless scores, only for UI testing).

## Verification run

- Python: 19 new tests (tiny random CLIP/BEiT models through the real loaders, formulas, formats, hash checks, engine control flow); full CPU suite 2741 passed, 0 failed. Scala: `AestheticScoreSuite` 7, MCP inspect test, Generator transport test; full `sbt test` passed in every module.
- CPU smoke with the real models on the simulacra repo's demo images: bad 4.0 / 4.1, great 6.2 / 5.7 (improved predictor); simulacra L/14 3.8 / 4.39 vs 6.81 / 6.43.
- Real runs through the official MCP SDK with the built jar (`scripts/dev/check_aesthetic_score_real.py`, receipts in `outputs/aes-real-{sd15,sdxl}`): SD1.5 `animekawa_v10` 512x512 and SDXL `waiIllustriousSDXL_v170` 832x1216 (`sequential_offload`; the first SDXL attempt stalled on the baseline because other processes held 18 GB of VRAM and was rerun), batch 2, 24 steps.
  - Scored images are byte-identical to the unscored baseline; PNG chunks equal the infotext; all scorers, generation fields, hash, ViT-B/16, cafe-only without chunks and Force CPU work; CPU and GPU `aesthetic_score` agree (SD1.5 6.1 / 5.9, SDXL 6.9 / 6.9); invalid settings are rejected; PNG replay reproduces the pixels and every score; hires fix scores the final upscaled image; cancel then a normal run matches the baseline.
  - Example all-scorer SD1.5: aesthetic_score 6.1, simulacra 7.46, aesthetic 0.9770, anime 0.9737, waifu 0.9205. SDXL: 6.9, 7.97, 0.9911, anime 0.9672, waifu 0.8095. B/16: SD1.5 6.99 / 6.74, SDXL 6.84 / 6.78.
  - `scripts/dev/check_aesthetic_score_tool_real.py` (engine `image_tool`, the Extras path) re-scored the saved PNGs from disk: all values equal the recorded ones for both models; an empty selection is rejected.
- Images were viewed (contact sheets: baseline, default, all, B/16, cafe, replay, hires, after-cancel): SD1.5 and SDXL pictures are visually identical across scored runs; the hires image differs by design (latent upscale, denoise 0.5, a paler look on SD1.5).

Not verified: the JavaFX form itself was not driven by hand (plugin controls and replay are unit-tested), SD2/inpaint models, and the score's meaning as a quality measure.
