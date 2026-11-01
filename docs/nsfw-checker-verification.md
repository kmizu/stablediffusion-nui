# NudeNet NSFW Censor verification (#91, ledger index 238)

Source: w-e-w/sd-webui-nudenet-nsfw-censor `1fb21cf9c3f72915ea1b5091bd51aedf91eae9d0` (AGPL-3.0, like NUI), ported from its source. The detector weight is that commit's `nudenet/best.onnx`, the NudeNet 320n model from notAI-tech/NudeNet (AGPL-3.0; the extension's `nudenet/LICENSE.md` cites NudeNet `b5e7fa859f43594f72a13a34c60a06d8343105f5`). Issue #91 also lists index 83 (AUTOMATIC1111 nsfw-censor) and index 231 (aria1th NSFWJS blurrer), which are different extensions and are not ported here.

## Behaviour

- `python/sdfx/nsfw_checker.py` is shared by generation, live preview, Extras and the `image_tool` request (tool `nsfw_checker`).
- Detection follows `pil_nude_detector.py`: RGB, resized to fit and centred on a white 320x320 canvas, a cached ONNX Runtime CPU session, 18 labels in model order, a box is kept when its best score is above its category's threshold (1 disables a category), optional `cv2.dnn.NMSBoxes` before expansion (1 disables NMS), coordinates restored for landscape and portrait images, then horizontal / vertical expansion around the box centre. Defaults: the four exposed categories (anus, female breast, female genitalia, male genitalia) at 0.25, all others 1, multipliers 1.
- Masks: Ellipse, Rectangle, Rounded rectangle (radius in (0, 1] is a fraction of half the shorter side, above 1 a unit scaled by the box diagonal, below 0 absolute pixels; drawn twice as upstream), Entire image (when anything is detected), and Entire image (always) in Extras.
- Filters: Variable blur, Gaussian Blur, Pixelate, Fill color, No censor and Disable. Blur and blend radii scale with `sqrt((w^2 + h^2) / 524288)`. Variable blur gives each grey level of the blended mask the radius `(level / 255) ^ ((12 - 2c) / c) * radius` (curve 0 = none, above 6 = uniform); it equals upstream's tree composite pixel for pixel, but uses at most 4 worker threads instead of upstream's 1,024.
- Generation (`NsfwCheckerPlugin`, AlwaysOn `nsfw_checker`): runs in `Engine.finish_batch` after ADetailer, post-processing (soft inpainting, face restore) and img2img Hires Fix, immediately before the PNG is written, so no later stage reintroduces uncensored pixels. With a detection it optionally keeps a `-before-censor` copy (default on) and the blended `-censor-mask` (default off). The engine returns them as `auxiliary_images` (`output_suffix`, `auxiliary_of`); `Generator` saves them just before their image through `OutputNaming.nextFile` with the same infotext and scrambling session, logs them, and does not add them to job results, galleries or grids. Transient generations also save them. A WD batch keeps only censored images and warns. Live previews are censored (live preview filter, blend radius chosen by the generation filter as upstream) before `ctx.preview`; a failure drops that preview instead of showing it uncensored.
- Settings are validated and the model is loaded and hash-checked before sampling, so a missing or altered model fails the job instead of saving uncensored images. Without onnxruntime, OpenCV or the model file, the engine does not report the `nsfw_checker` feature and the plugin is unavailable.
- Extras (`NsfwCheckerExtrasPlugin`, `nsfw_checker_extras`) and `image_tool`: NudeNet auto-detection (can be off) and an optional custom mask image (`ImageFile`, grey level = strength) are combined by union; the custom mask is kept when nothing is detected (upstream's 2024-08-29 fix). Extras uses upstream's override settings: blur radius in pixels, mask blend scaled, NMS 1, Variable blur. It can also output the mask, or only the mask. `image_tool` without values uses upstream's settings defaults (blur radius scaled, NMS 0.5). The custom mask path is decoded through the scrambled-input handling like other tool inputs.
- Infotext: upstream writes none. NUI writes `NudeNet NSFW Censor` as JSON of the generation filter plus every setting that differs from the defaults. PNG Restore and MCP `inspect_generation_metadata` restore and enable the plugin from it; PNGs without the key leave the current setting alone, because upstream keeps it in global settings rather than per image.
- Adaptations: AlwaysOn is opt-in (upstream enables it by default); NUI's uploaded mask replaces the Gradio sketch canvas; the engine / MCP / Extras routes replace `/nudenet/censor` (no A1111 HTTP endpoint); the ONNX provider is always CPU (upstream's recommendation); upstream's "falsy means default" API quirk (e.g. `blur_radius: 0`) is not reproduced. Intermediate images saved by other plugins (Save intermediate images, Steps animation) are not censored.

## Model and notices

- `models/nudenet/best.onnx`: 12,125,773 bytes, Git blob `12f4f7808735fb2f8a0fb9beca635a1426bfdcad` (same as upstream), SHA-256 `9832f15515bdb06bcb5a77beb60bc8ea54439bd7ecbaac46dac3b760b3dd13cc`. The engine refuses any other size or digest. `scripts/package_windows.ps1` allows exactly this file in the payload and checks its SHA-256.
- `licenses/w-e-w__sd-webui-nudenet-nsfw-censor.txt` (SHA-256 `3918cb0f...`) and `licenses/notAI-tech__NudeNet.txt` (`8486a10c...`) are pinned in `scripts/third_party.py`, and both projects are listed in `THIRD_PARTY_NOTICES.md`.

## Evidence (2026-11-01, CPU)

- `python/tests/test_nsfw_checker.py`: label order and defaults, config validation, letterbox geometry, landscape / portrait restoration, strict per-category thresholds, expansion, NMS, all mask shapes and radius modes, every filter and Variable blur against a literal restatement of upstream's composite, curve endpoints, generation / preview / Extras / `image_tool` workflows (custom-mask union with and without detections, Entire image (always), default vs explicit blur radius), the bundled model hash, rejected models, and a real ONNX session (input 1x3x320x320, output 1x22x2100; a white image has no detection).
- `python/tests/test_nsfw_checker_engine.py`: censoring sees the img2img Hires Fix and post-processed images, auxiliary outputs and indices, no-detection and Disable paths, configuration and missing-model errors before sampling, censored live previews, and a failing preview detector that drops the preview without failing generation.
- Scala: `NsfwCheckerSuite` (defaults, JSON types, model-order arrays, collapsible alphabetical category controls, infotext round trip, replay rejection, Extras parameters, outputs and temp-file cleanup), `GeneratorSuite` (auxiliary naming, order, infotext, scrambling, unsafe-suffix rejection, transient jobs, protected custom-mask input) and `InspectGenerationMetadataSuite` (MCP candidate reproduces the hook).
- Real SD1.5 / SDXL checks are pending: `scripts/dev/check_nsfw_checker_real.py` (official MCP SDK). It gets a positive detection without explicit content by enabling the face categories on a clothed portrait, probing the face threshold through Extras, and covers generation filters, before-censor / mask files, defaults, disabled baseline recovery, PNG replay, hires, Extras auto / custom mask / union / Entire image (always) / no detection. Look at the images, not only the assertions.

## Real-model checks (2026-11-01)

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

## Limits

- Not compared against a running A1111 with the extension.
- NudeNet is trained on photographs; detection on illustrations is weaker, so thresholds may need tuning.
- Censoring adds CPU detection time per final image and per live preview.
