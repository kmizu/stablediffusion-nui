# CLIP Interrogator (ledger index 98, target `core:clip_interrogator`)

Status: partial. The Prompt, Analyze and Batch workflows, the MCP tool and the UI are implemented and verified on real
models; the items under "Not provided" are not.

## Sources and licences

| Source | Pinned commit | Licence | Use |
|---|---|---|---|
| pharmapsychotic/clip-interrogator-ext | `0f1a4591f82b93859a1b854afb79494f39384662` | MIT (LICENSE SHA-256 `882ca262...a6f4`) | UI tabs, batch output formats and API behaviour re-implemented |
| pharmapsychotic/clip-interrogator | `bc07ce62c179d3aab3053a623d96a071101d11cb` | MIT (LICENSE SHA-256 `7e1e37e5...677b`) | algorithms re-implemented; the five label lists are bundled byte for byte (`python/sdfx/data/clip_interrogator`, 82,438 / 1,804,274 / 1,606 / 2,683 / 418 bytes, a test checks the sizes) |

Models (downloaded from Hugging Face on first use, never bundled): the transformers conversions of OpenAI CLIP (model
cards state no licence) and LAION CLIP (MIT), Salesforce BLIP captioning base/large (BSD-3-Clause), microsoft/git-large-coco
(MIT), Salesforce BLIP-2 (MIT). Notices are in `THIRD_PARTY_NOTICES.md` via `scripts/third_party.py`.

## What was built

- Engine request `clip_interrogator` (`python/sdfx/clip_interrogator.py`): operations `models`, `prompt`, `analyze`,
  `batch`, `unload`; modes best / fast / classic / negative / caption; progress events; interruption between steps; models
  released after each call unless `keep_loaded`; low-VRAM (12 GB or less) defaults of the original (blip-base, smaller
  chunks, caption/CLIP offloading). Label embeddings are cached in the upstream safetensors layout (`<model>_<table>.safetensors`
  with the same SHA-256 label hash), so a cache written by the original is reused and a changed list invalidates it.
- Scala: `Protocol.Types.ClipInterrogator`, `EngineClient.clipInterrogatorOwned` (scoped cancellation that never touches other
  jobs), `Interrogator` tab (Prompt / Analyze / Batch / About, send to txt2img / img2img / inpaint / Extras, a negative
  result goes to the negative prompt, Keep loaded, Unload, Cancel), MCP tool `clip_interrogate`.
- Protected (scrambled) image sessions are refused in the tab and the tool.

## Deviations from upstream

- CLIP runs through `transformers.CLIPModel` on the Hugging Face conversions, not open_clip. Ten open_clip names have a
  conversion and are offered; the others (RN50 family, ConvNeXt, EVA, SigLIP, ...) are not.
- `LabelTable.rank` is an exact global top-k; upstream keeps a per-chunk shortlist.
- Chain ties are broken deterministically (sorted phrases) instead of by set order.
- Batch sorts the file list, never overwrites existing `.txt`, `batch.txt` or `batch.csv` unless `overwrite`, and reports
  unreadable files and skipped outputs; upstream overwrites silently and returns nothing.
- Out of memory is an error, not a prompt string. Upstream's `/interrogator/*` REST endpoints are replaced by the MCP tool.

## Verification

All numbers are from this worktree.

- Python: `tests/test_clip_interrogator.py`, 29 tests with a deterministic fake CLIP and captioner (label construction,
  ranking and reverse ranking, every mode, chain stops, truncation, caption-prefix rule of classic, analyze, cache layout /
  reuse / invalidation / corruption, session reuse and release, batch formats, no-overwrite, interruption, offload, input
  errors). The full Python suite (CPU) passed (exit 0).
- Scala: `McpToolsSuite` (29, includes the new tool: schema, bad operation / mode, out-of-policy image and folder, protected
  session) and `ClipInterrogatorCancellationSuite` (2) pass; `ui/Test/compile` and `ui/assembly` build.
- Real MCP check, official MCP SDK client over stdio, real engine and the built jar (`scripts/dev/check_clip_interrogator_real.py`):
  - SD1.5 `animekawa_v10` 512x512 and SDXL `waiIllustriousSDXL_v170` 832x1216 (sequential offload) both generate a source image,
    then ViT-L/14 + BLIP-large interrogate it in all five modes, Analyze, repeated call (identical prompt), base64 input
    (identical prompt), batch over a folder in all three output formats with an unreadable file, a hand-written `.txt` kept,
    overwrite refused / allowed, `unload` (loaded model becomes none), bad inputs failing without breaking the engine, and
    a final txt2img that reproduces the first image bit for bit. Passed on both (`outputs/clip-real-sd15`, `outputs/clip-real-sdxl`).
  - Similarity of the prompt to the image (SD1.5 / SDXL): caption 0.273 / 0.292, classic 0.340 / 0.337, fast 0.362 / 0.367,
    best 0.430 / 0.426, negative -0.182 / -0.149. best is never below caption or fast, as the algorithm guarantees.
  - Timing: first call 47.6 s / 63.2 s (model download or load, 100k label embeddings), later fast/classic 0.3 s, best 9.6 s.
  - ViT-bigG-14 (laion2b_s39b_b160k) with blip-base on SD1.5: prompt similarity 0.462, own cache files written.
  - Looked at the images: the SD1.5 and SDXL sources show a red fox in a snowy forest. Feeding the fast prompt back gives a fox in
    snow in a children's-book style (the SD1.5 anime checkpoint adds a fox-eared girl; SDXL gives a clean fox on a rock). The
    SDXL best prompt contains junk terms (`fox news`, `andrew tate`) that the model renders as text on the image; this is how
    the upstream best mode behaves, not a defect of the port. The negative prompt is made of unrelated photographic terms, as intended.
- Real UI check (`ui/Test/runMain sdfx.ui.RealClipInterrogatorCheck`, real MainWindow, real engine, `outputs/clip-real-ui`):
  model list filled from the engine, fast and best prompts, send to txt2img (positive) and negative result to the negative prompt only,
  Analyze tables (5 rows each, sorted), Batch of 3 images, Unload, Cancel in the middle of a best run (no prompt produced,
  `[interrupted]` status) followed by a successful classic run. Screenshots inspected.
- Caption models, direct engine call on the SD1.5 source, fast mode: `blip-base` caption "a painting of a fox in the snow" (prompt similarity 0.345) and the GIT caption model `git-large-coco` caption "fox in the snow - watercolor" (0.329) both load, run and rank on the GPU.

## Not provided or not verified

- BLIP-2 caption models (`blip2-2.7b`, `blip2-flan-t5-xl`, 15 GB each) are selectable but were not run.
- open_clip models without a transformers conversion, and the A1111 REST endpoints.
- The Interrogator layout was only inspected at 1484x881; the top control row was changed to wrap after that screenshot and was
  built but not re-photographed.
- Not run on a GPU with 12 GB or less, so the offload path is covered by unit tests only.
