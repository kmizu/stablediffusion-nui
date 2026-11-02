# Batch Face Swap verification (#6, partial)

Source: kex0/batch-face-swap `942ebae6937d9ad7964f285a790e7721f712b9ec` (ledger index 59, target `batch_face_swap`). The repository has no licence file (GitHub reports none) and its README says the project is no longer maintained and recommends ADetailer / ReActor. Only the README was read: "Automatically detects faces and replaces them", enable it in the txt2img / img2img accordion, img2img takes one image or a folder, and "override options only affect face generation" (a different prompt, or a different model, for the faces). No upstream code was read or copied; the README documents no option names or infotext keys, so the controls and the `Batch Face Swap` infotext key are NUI's own. Recorded as `PINNED_UNLICENSED` in `scripts/third_party.py`. The detector weights come from Bingsu/adetailer (model card: Apache-2.0, pinned `53cc19de...`) and need the optional `ultralytics` package (AGPL-3.0), both recorded as dependencies.

## Behaviour

- One AlwaysOn plugin `batch_face_swap` (hook `batch_face_swap`). After ADetailer (and after Hires / img2img Hires Fix paths, per image) every detected face becomes a mask and is redrawn through the existing ADetailer inpaint path (`Engine._adetailer` with one generated unit, `python/sdfx/batch_face_swap.py`); the inpaint passes skip the hook.
- Settings: detector (`face_yolov8n/s/m.pt`, `face_yolov9c.pt`, `opencv_face`, or a custom file name / path), confidence, redraw only the k largest faces, mask shape (ellipse / box) and size (times the detected box), mask blur, crop padding, face area resolution (0 = the generation size), denoising strength, face prompt and negative prompt (`[PROMPT]`, `[SEP]` per face left to right), steps / CFG / sampler overrides (0 or empty = main), colour match. Out-of-range or unknown settings are rejected before the first image is drawn.
- `ADetailer` gained optional unit keys `mask_shape` / `mask_scale` (face-shaped masks built from the boxes); without them its behaviour is unchanged.
- PNG: the `Batch Face Swap` key (compact JSON of the detector and non-default settings); PNG Restore and MCP `inspect_generation_metadata` restore it, PNGs without the key switch the plugin off. Five XYZ axes (denoising strength, confidence, mask size, mask blur, prompt).

## Not provided

- The override checkpoint for the face pass (README: "generate the initial image with one model and faceswap with another"). The engine keeps one loaded model per job.
- The img2img folder input (README: path to a folder of images). There is no folder img2img in this app; use one image at a time through the GUI or MCP `img2img`.
- mediapipe detection (the upstream's detector; not installed, not verified). `opencv_face` is the no-download alternative; it finds few anime faces and was not exercised on real images beyond unit tests.
- The upstream option names and infotext (not documented in the README).
- XYZ / UI checks beyond the Scala tests (the JavaFX form was not opened by hand).

## Verification

- Python (torch CPU): `python/tests/test_batch_face_swap.py` (settings and rejections, unit mapping, shaped masks, detector stubbed through the real `Engine.txt2img` orchestration: both faces redrawn left to right with `[SEP]` prompts, overrides reach the inpaint call, hook not inherited by the inpaint pass, k-largest, nothing detected leaves the image alone, runs after ADetailer, bad settings fail before any image, feature advertised). Full suite: 2760 passed, 2 skipped.
- Scala: `BatchFaceSwapSuite` (defaults, custom detector, infotext, round trip, rejection of malformed values, axes), `InspectGenerationMetadataSuite` Batch Face Swap case; full `sbt test` exit 0; `ui/assembly` built.
- Real models through the official MCP SDK (`scripts/dev/check_batch_face_swap_real.py`, stdio, packaged jar, seed 2468, Euler, prompt "2girls, standing side by side, upper body, park, ..."): SD1.5 `animekawa_v10` 512x512 24 steps (`outputs/bfs-real-sd15`) and SDXL `waiIllustriousSDXL_v170` 832x1216 20 steps, sequential offload (`outputs/bfs-real-sdxl`). Both: success.
  - Nine settings change the image (default YOLOv8n face, prompt override, strong denoising, big box mask, resolution 768, steps / CFG / sampler override, no colour match, largest face only, YOLOv8s); the changes stay local to the faces: 0.5 to 1.9 % of the pixels on SD1.5 (change bounding boxes about 180..357 x 155..251 around the two faces), 3.9 to 16.9 % on SDXL. "Largest only" changes less than "all faces" on both.
  - Confidence 1.0 (nothing detected) and `enabled: false` are byte-identical to the baseline.
  - PNG round trip: `inspect_generation_metadata` restores the plugin with the key consumed, and replaying it reproduces the image byte for byte (both models). img2img of a finished image with the hook differs from img2img without it. `confidence: 3` is rejected before drawing. A cancelled job leaves no hook behind (the next run equals the baseline).
  - Images inspected on face crops: the faces are redrawn in place (similar identity, slightly different eyes and shading); the prompt override "closed eyes, big smile" gives closed smiling eyes on SD1.5 and a smile on SDXL; with "largest only" only the larger face changes (the left face on SD1.5, the right face on SDXL); the strong setting visibly changes the face more; the big box mask touches a wider area. No seams or colour jumps were visible at the normal sizes; the SD1.5 faces are small (about 50 px) and soft either way.
