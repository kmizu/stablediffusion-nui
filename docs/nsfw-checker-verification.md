# NSFW checker verification (#91, ledger indices 238, 83 and 231)

All three entries of issue #91 map to the ledger target `nsfw_checker`. They are one AlwaysOn plugin (`nsfw_checker`, shown as **NSFW Checker**) with one checkbox per checker, and one Extras plugin (`nsfw_checker_extras`) with a **Checker** choice, so a single hook orders them and a single infotext/replay path restores them. NudeNet (index 238) is on by default inside the plugin, as before; the SD safety checker (index 83) and the NSFWJS filter (index 231) are off by default.

## NudeNet NSFW Censor (index 238)

Source: w-e-w/sd-webui-nudenet-nsfw-censor `1fb21cf9c3f72915ea1b5091bd51aedf91eae9d0` (AGPL-3.0, like NUI), ported from its source. The detector weight is that commit's `nudenet/best.onnx`, the NudeNet 320n model from notAI-tech/NudeNet (AGPL-3.0; the extension's `nudenet/LICENSE.md` cites NudeNet `b5e7fa859f43594f72a13a34c60a06d8343105f5`).

### Behaviour

- `python/sdfx/nsfw_checker.py` is shared by generation, live preview, Extras and the `image_tool` request (tool `nsfw_checker`).
- Detection follows `pil_nude_detector.py`: RGB, resized to fit and centred on a white 320x320 canvas, a cached ONNX Runtime CPU session, 18 labels in model order, a box is kept when its best score is above its category's threshold (1 disables a category), optional `cv2.dnn.NMSBoxes` before expansion (1 disables NMS), coordinates restored for landscape and portrait images, then horizontal / vertical expansion around the box centre. Defaults: the four exposed categories (anus, female breast, female genitalia, male genitalia) at 0.25, all others 1, multipliers 1.
- Masks: Ellipse, Rectangle, Rounded rectangle (radius in (0, 1] is a fraction of half the shorter side, above 1 a unit scaled by the box diagonal, below 0 absolute pixels; drawn twice as upstream), Entire image (when anything is detected), and Entire image (always) in Extras.
- Filters: Variable blur, Gaussian Blur, Pixelate, Fill color, No censor and Disable. Blur and blend radii scale with `sqrt((w^2 + h^2) / 524288)`. Variable blur gives each grey level of the blended mask the radius `(level / 255) ^ ((12 - 2c) / c) * radius` (curve 0 = none, above 6 = uniform); it equals upstream's tree composite pixel for pixel, but uses at most 4 worker threads instead of upstream's 1,024.
- Generation (`NsfwCheckerPlugin`, AlwaysOn `nsfw_checker`): runs in `Engine.finish_batch` after ADetailer, post-processing (soft inpainting, face restore) and img2img Hires Fix, immediately before the PNG is written, so no later stage reintroduces uncensored pixels. With a detection it optionally keeps a `-before-censor` copy (default on) and the blended `-censor-mask` (default off). The engine returns them as `auxiliary_images` (`output_suffix`, `auxiliary_of`); `Generator` saves them just before their image through `OutputNaming.nextFile` with the same infotext and scrambling session, logs them, and does not add them to job results, galleries or grids. Transient generations also save them. A WD batch keeps only censored images and warns. Live previews are censored (live preview filter, blend radius chosen by the generation filter as upstream) before `ctx.preview`; a failure drops that preview instead of showing it uncensored.
- Settings are validated and the model is loaded and hash-checked before sampling, so a missing or altered model fails the job instead of saving uncensored images. Without onnxruntime, OpenCV or the model file, the engine does not report the `nsfw_checker` feature and the plugin is unavailable.
- Extras (`NsfwCheckerExtrasPlugin`, `nsfw_checker_extras`) and `image_tool`: NudeNet auto-detection (can be off) and an optional custom mask image (`ImageFile`, grey level = strength) are combined by union; the custom mask is kept when nothing is detected (upstream's 2024-08-29 fix). Extras uses upstream's override settings: blur radius in pixels, mask blend scaled, NMS 1, Variable blur. It can also output the mask, or only the mask. `image_tool` without values uses upstream's settings defaults (blur radius scaled, NMS 0.5). The custom mask path is decoded through the scrambled-input handling like other tool inputs.
- Infotext: upstream writes none. NUI writes `NudeNet NSFW Censor` as JSON of the generation filter plus every setting that differs from the defaults. PNG Restore and MCP `inspect_generation_metadata` restore and enable the plugin from it; PNGs without the key leave the current setting alone, because upstream keeps it in global settings rather than per image.
- Adaptations: AlwaysOn is opt-in (upstream enables it by default); NUI's uploaded mask replaces the Gradio sketch canvas; the engine / MCP / Extras routes replace `/nudenet/censor` (no A1111 HTTP endpoint); the ONNX provider is always CPU (upstream's recommendation); upstream's "falsy means default" API quirk (e.g. `blur_radius: 0`) is not reproduced. Intermediate images saved by other plugins (Save intermediate images, Steps animation) are not censored.

### Model and notices

- `models/nudenet/best.onnx`: 12,125,773 bytes, Git blob `12f4f7808735fb2f8a0fb9beca635a1426bfdcad` (same as upstream), SHA-256 `9832f15515bdb06bcb5a77beb60bc8ea54439bd7ecbaac46dac3b760b3dd13cc`. The engine refuses any other size or digest. `scripts/package_windows.ps1` allows exactly this file in the payload and checks its SHA-256.
- `licenses/w-e-w__sd-webui-nudenet-nsfw-censor.txt` (SHA-256 `3918cb0f...`) and `licenses/notAI-tech__NudeNet.txt` (`8486a10c...`) are pinned in `scripts/third_party.py`, and both projects are listed in `THIRD_PARTY_NOTICES.md`.

### Evidence (2026-11-01, CPU)

- `python/tests/test_nsfw_checker.py`: label order and defaults, config validation, letterbox geometry, landscape / portrait restoration, strict per-category thresholds, expansion, NMS, all mask shapes and radius modes, every filter and Variable blur against a literal restatement of upstream's composite, curve endpoints, generation / preview / Extras / `image_tool` workflows (custom-mask union with and without detections, Entire image (always), default vs explicit blur radius), the bundled model hash, rejected models, and a real ONNX session (input 1x3x320x320, output 1x22x2100; a white image has no detection).
- `python/tests/test_nsfw_checker_engine.py`: censoring sees the img2img Hires Fix and post-processed images, auxiliary outputs and indices, no-detection and Disable paths, configuration and missing-model errors before sampling, censored live previews, and a failing preview detector that drops the preview without failing generation.
- Scala: `NsfwCheckerSuite` (defaults, JSON types, model-order arrays, collapsible alphabetical category controls, infotext round trip, replay rejection, Extras parameters, outputs and temp-file cleanup), `GeneratorSuite` (auxiliary naming, order, infotext, scrambling, unsafe-suffix rejection, transient jobs, protected custom-mask input) and `InspectGenerationMetadataSuite` (MCP candidate reproduces the hook).
- Real SD1.5 / SDXL checks are pending: `scripts/dev/check_nsfw_checker_real.py` (official MCP SDK). It gets a positive detection without explicit content by enabling the face categories on a clothed portrait, probing the face threshold through Extras, and covers generation filters, before-censor / mask files, defaults, disabled baseline recovery, PNG replay, hires, Extras auto / custom mask / union / Entire image (always) / no detection. Look at the images, not only the assertions.

### Real-model checks (2026-11-01)

`scripts/dev/check_nsfw_checker_real.py` drives the official MCP Python SDK 2.3.0 against the real engine with the
bundled model (SHA-256 `9832f155…13cc`); receipts `outputs/nsfw-real-sd15/receipt.json` (SD1.5 `animekawa_v10`,
512x768) and `outputs/nsfw-real-sdxl/receipt.json` (SDXL `waiIllustriousSDXL_v170`, 832x1216). No explicit content
was generated: a clothed portrait was censored by switching on the face categories (detected at threshold 0.25 on
both models). Both passed every assertion:

- Generation: Fill color, Gaussian Blur, Variable blur and Pixelate (rounded rectangle) covered the detected face;
  the before-censor copy and the mask were saved next to the result; defaults (exposed categories only) left the
  clothed image unchanged; disabling and a later plain run reproduced the baseline; PNG replay reproduced the censored
  image; a hires pass was censored after refinement with its before-censor copy kept.
- Extras: auto-detection with mask output, a custom mask alone, the custom mask united with detection, Entire image
  (always), and defaults reporting nothing detected with the image unchanged.

Images and masks inspected on contact sheets for both models.

### Limits

- Not compared against a running A1111 with the extension.
- NudeNet is trained on photographs; detection on illustrations is weaker, so thresholds may need tuning.
- Censoring adds CPU detection time per final image and per live preview.

## SD safety checker (index 83)

Source: AUTOMATIC1111/stable-diffusion-webui-nsfw-censor `c21cc99b731ff295039b0531e73968d1bf384570` (2022-12-10, the only commit). The repository has **no licence**, so nothing was copied; NUI re-implements the observed behaviour of `scripts/censor.py`: an always-visible script without controls whose `postprocess_batch` passes the decoded batch (float, 0..1) through diffusers' `StableDiffusionSafetyChecker`, with `AutoFeatureExtractor` and weights loaded once from `CompVis/stable-diffusion-safety-checker` and kept on the CPU, and replaces every flagged image with a black image (the README: "Replaces non-worksafe images with black squares"). It runs for txt2img and img2img; it does not touch live previews, writes no infotext and has no API of its own.

### Model

- `CompVis/stable-diffusion-safety-checker`, pinned to Hugging Face revision `cb41f3a270d63d454d385fc2e4f571c487c253c5` (2022-11-25; the weights date from `4bb648a`, and this is the revision upstream would have loaded when it was written). Files and SHA-256: `config.json` 4,549 bytes `c879a63e…7977e0e`; `preprocessor_config.json` 342 bytes `2a1da83b…a7a0a2` (CLIP: shortest side 224 bicubic, centre crop 224, CLIP mean/std); `pytorch_model.bin` 1,216,067,303 bytes `64b8393f1afd5a0c1ed2aa5f341fa7c08286839a48f3743162a76a2835c808bd` (Hugging Face LFS digest).
- Licence: the model card's licence line reads "More information needed"; no licence is stated. NUI therefore does **not bundle** it. Like upstream, it is downloaded from Hugging Face on first use, but only from the pinned revision: `python/sdfx/nsfw_safety.py` downloads each file to `SDFX_NSFW_CHECKER_CACHE` (default `~/.cache/stablediffusion-nui/nsfw_checker/CompVis__stable-diffusion-safety-checker/<revision>/`), checks size and SHA-256 before publishing the file atomically, and refuses a cached file that does not match. `SDFX_NSFW_CHECKER_OFFLINE=1` (or `HF_HUB_OFFLINE=1`) forbids the download; the pinned files can then be placed there by hand. The weight is a PyTorch pickle; transformers loads it with `torch.load(weights_only=True)` (torch 2.7.1), after the hash check.

### Behaviour in NUI

- Generation (`safety_checker`, off by default): each final image is checked with `StableDiffusionSafetyChecker.forward` (diffusers 0.33.1, the same concept and special-care thresholds and 0.01 adjustment), in float32 on the CPU, and a flagged image is replaced with an opaque black image of the same size before it is saved; the engine log records a warning. The model is downloaded/verified and loaded before sampling, so a missing or altered model fails the job instead of saving unchecked images (`SD safety checker: …`, `unsupported`).
- Adaptation: upstream checks the batch right after decoding, before face restoration and other post-processing scripts; NUI checks the final image in `Engine.finish_batch`, after ADetailer, post-processing and img2img Hires Fix, so later stages cannot reintroduce unchecked pixels. Images are checked one at a time (the checker has no cross-image interaction). With NudeNet also on, a black image gives NudeNet nothing to detect, so no before-censor copy is written.
- Live previews: not checked, as upstream, unless **SD safety checker: also black out flagged live previews** (NUI addition, off by default) is on; it is slow on the CPU. Without it, enable NudeNet or NSFWJS for previews or turn live previews off.
- Extras / `image_tool` (NUI addition; upstream has neither): `checker: "safety_checker"` checks one image and returns a black image when flagged, or keeps it with `report_only`; `info.is_nsfw` and the infotext `SD safety checker Extras` (`not NSFW`, `NSFW (black image)`, `NSFW (reported only)`) report the verdict. MCP reaches it through `run_script` with `nsfw_checker_extras`.
- Infotext: upstream writes none. NUI writes `SD safety checker: final images` (or `final images and live previews`) when it is on; PNG Restore and MCP `inspect_generation_metadata` re-enable it from that key.

## NSFWJS filter (index 231)

Source: aria1th/sd-webui-nsfw-filter `ab49c733c86ad24358417b5bff4adf4b3803baf8` (2023-10-26, "add safety level argument"). The repository has **no licence**, so nothing was copied; NUI re-implements the observed behaviour of `scripts/main.py`, `predict.py`, `config.py`, `api.py` and `preload.py`:

- Classification: the image is converted to RGB and resized to 224x224 with nearest-neighbour (aspect ratio not kept), scaled to 0..1 as float32 NHWC, and run through the ONNX model (input `input_1`, output `dense_3`, classes `drawings, hentai, neutral, porn, sexy`). The top class decides: safety level `safe` (default) flags `hentai`, `porn` and `sexy`; `questionable` flags `hentai` and `porn`; `explicit` flags nothing. Upstream sets the level with the `--safety-level` command-line option; NUI has it as a per-job setting.
- Censoring: a flagged image is blurred whole with `ImageFilter.GaussianBlur(20)` (a fixed radius of 20 pixels, independent of image size).
- Where it applies upstream: `postprocess_image` of every generated image, `on_before_image_saved` of every saved image (so a saved generation result is checked twice and blurred again if the blurred image is still flagged), and a wrapper around `shared.state.assign_current_image` (live previews, one pass). It is on whenever the extension is installed; it writes no infotext. `scripts/api.py` adds `POST /safety/predict` (form field `image`, base64) returning `{"is_nsfw": bool}`, behind `--api-auth` when set.

### Model

- `deepghs/imgutils-models` file `nsfw/nsfwjs.onnx`, pinned to Hugging Face revision `35e0ed8f35ce0dbea674568161a48b31ba7d2a99` (last change 2023-07-03, the revision upstream's unpinned `hf_hub_download` resolved to when it was written): 10,318,774 bytes, SHA-256 `510dce55edcd1cf8281cbe906dedb3f4963f8814bc1edfbd42d3bf261148df2d`. The model card licence is MIT; the model is the ONNX conversion of NSFWJS (infinitered/nsfwjs, MIT). It is downloaded on first use from the pinned revision, size/SHA-256 checked like the safety checker (cache directory `deepghs__imgutils-models/<revision>/nsfwjs.onnx`), and not bundled. ONNX Runtime runs it on the CPU (upstream uses CUDA when available) with full graph optimisation.

### Behaviour in NUI

- Generation (`nsfwjs`, off by default; `nsfwjs_safety_level` `safe` / `questionable` / `explicit`): each final image gets upstream's two passes, the `postprocess_image` pass before NudeNet and the save-time pass after it, and NudeNet's `-before-censor` copy and `-censor-mask` get the save-time pass too because upstream's save hook sees every saved file. The model is downloaded/verified and loaded before sampling (`NSFWJS filter: …`). `explicit` never flags, so NUI does not load or run the model for it.
- Live previews: one pass before the preview is emitted; a failure drops the preview instead of showing it unchecked.
- Order with the other checkers on one final image: SD safety checker, NSFWJS (first pass), NudeNet, NSFWJS (save pass on the image and its auxiliary files). Previews: SD safety checker (only with its preview option), NSFWJS, NudeNet's live preview filter.
- Extras / `image_tool`: `checker: "nsfwjs"` with `safety_level` applies one pass, as upstream's save hook does for an Extras save; with `report_only` (Extras **Only report**) the image is kept, which is NUI's equivalent of `/safety/predict`. `info` returns `is_nsfw`, `label` and the five `score_*` values; the infotext `NSFWJS filter Extras` reads e.g. `safe; NSFW (blurred); top class porn`, `questionable; not NSFW; top class drawings` or `explicit; not classified`. MCP reaches it through `run_script` with `nsfw_checker_extras`; there is no A1111 HTTP route and no HTTP Basic auth.
- Infotext: NUI writes `NSFWJS filter: <level>` when it is on; PNG Restore and MCP `inspect_generation_metadata` restore it.
- Adaptations: opt-in instead of always on; the level is per job instead of a launch option; CPU instead of CUDA; grids are composed from already checked images and are not classified again; images that other plugins save themselves (Save intermediate images, Steps animation) are not checked, as for NudeNet.

## Settings, infotext and replay of the combined plugin

- Hook fields: `nudenet` (default true), `safety_checker`, `safety_checker_live_preview`, `nsfwjs` (default false) and `nsfwjs_safety_level` (default `safe`), next to the NudeNet settings. Hooks without them (older settings, MCP calls) behave as before: NudeNet only.
- Infotext: each enabled checker writes its own key (`NudeNet NSFW Censor`, `SD safety checker`, `NSFWJS filter`). Replay: an image without any of the three keys leaves the current setting alone; otherwise each checker is on exactly when its key is present, so images written before indices 83 / 231 restore NudeNet only. A malformed value restores nothing. The MCP inspector consumes all three keys.

## Evidence for indices 83 and 231 (2026-11-01, CPU)

- `python/tests/test_nsfw_safety.py` (37 tests, one of which runs only with the cached real NSFWJS model): pinned repositories, revisions, sizes and hashes; downloads through a fake opener (verified atomic publish, cache reuse, wrong hash or size rejected and removed, altered cached file refused, offline refusal, network failure); NSFWJS input geometry (nearest 224, NHWC, 0..1), the named input/output, every class at the `safe` and `questionable` levels, `explicit` not classified, radius-20 blur per pass with the second pass skipped when the first does not flag; the SD safety checker on a tiny random CLIP model through diffusers' own `forward` (flag all / none, the 0.01 special-care adjustment, black RGBA output), loading only verified pinned files with `from_pretrained`; the force variable; and the `nsfw_checker` glue: defaults, `job_config` loading exactly the enabled models and naming the failing checker, the per-image order, the black-out before other checks, unchanged unflagged images, previews (safety checker only with its option, one NSFWJS pass), JPEG preview round trip, and the Extras / `image_tool` verdicts with `report_only`.
- The real pinned `nsfwjs.onnx` (downloaded to a scratch cache for this check, SHA-256 verified) ran on the CPU: input `input_1`, output `dense_3`, five probabilities summing to 1, a white image not flagged (top class `drawings`).
- `python/tests/test_nsfw_checker_engine.py`: in the real `Engine.txt2img` control flow (sampling stubbed), the safety checker model loads before sampling and both saved images are black; NSFWJS runs twice per saved image; a model failure fails the job before sampling; a flagged live preview is blurred before it is emitted.
- Scala: `NsfwCheckerSuite` (opt-in defaults and controls, per-checker infotext keys and values, replay of every combination including NudeNet-only images from before, malformed values, Extras parameters and rejection of custom masks / mask-only with whole-image checkers, Extras verdict infotexts and temp-file cleanup) and `InspectGenerationMetadataSuite` (an image with the safety checker and NSFWJS but no NudeNet reproduces the hook and consumes the keys).
- No real SD safety checker weight was downloaded for these CPU checks (1.2 GB); its loading path was exercised with a tiny model saved in the same file layout.

## Real-model checks for indices 83 and 231 (2026-11-01)

`scripts/dev/check_nsfw_classifiers_real.py` drives the official MCP SDK in two server runs (receipts
`outputs/cls-real-sd15/receipt.json`, `outputs/cls-real-sdxl/receipt.json`) on SD1.5 `animekawa_v10` and SDXL
`waiIllustriousSDXL_v170`; both passed. The first run downloaded and hash-checked the pinned safety checker
(`pytorch_model.bin`, 1,216,067,303 bytes) and `nsfwjs.onnx` into a scratch cache.

- Normal run: a clothed portrait stayed unchanged through generation at every NSFWJS level, the safety checker, all
  checkers together, live previews, Extras **Only report** and PNG replay; a plain run afterwards reproduced the
  baseline.
- Forced run (`SDFX_NSFW_CHECKER_FORCE_FLAGGED`, which can only strengthen censoring): the safety checker returned a
  black image, NSFWJS blurred the final and saved files, the explicit level left the image alone, both together and
  with NudeNet combined correctly, PNG replay reproduced the black image, and the Extras paths blurred or blacked out
  as expected.

Images inspected on contact sheets for both models.

### Limits of indices 83 and 231

- Not compared against a running A1111 with either extension; classifier verdicts on real explicit content were not exercised (no explicit content was generated), only the clean path and the forced-flag path.
- The safety checker model's licence is not stated; users download it from Hugging Face themselves on first use, as with upstream.
- Both classifiers have known false negatives and false positives (upstream 231 warns it is mainly trained on 3D/real images); none of the checkers is a guarantee.
- The safety checker adds a CLIP ViT-L/14 pass on the CPU per final image (and per preview with its option); NSFWJS is small but runs up to twice per saved file.
