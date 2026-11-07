# ControlNet Preprocessor in Extras (ledger index 276, target `core:controlnet_preprocess_extras`) verification

Issue: kmizu/stablediffusion-nui #18. Upstream: <https://github.com/light-and-ray/sd-webui-cn-in-extras-tab>, pinned at
`857262300ae8b849cfa0ed2c2802e93bf160ebd0` (default branch `master`, last push 2024-06-12).

Status: **partial**. The Extras card, the folder run and the MCP tool are implemented and verified on real SD1.5 and SDXL
output; the items under "Not provided" are not.

The later selected-file batch and its separate evidence are documented in
`controlnet-selected-batch-verification.md`; statements below describe the earlier folder-only version.

## Licence check and what was used

- `gh api repos/light-and-ray/sd-webui-cn-in-extras-tab --jq .license` returns nothing and the tree at the pinned commit
  holds only `.gitignore`, `README.md`, `images/img1.jpg`, `metadata.ini` and `scripts/cn_in_extras_tab.py`. The code is
  unlicensed: **no upstream code was copied or translated.**
- Used: the README and its screenshot, and, only to enumerate the controls, the `grep` of `scripts/cn_in_extras_tab.py`
  for its widget names (Enable, Module, Pixel Perfect, Preprocessor resolution, Threshold A, Threshold B, the single /
  batch / directory Extras pipeline). The implementation is an independent design on this app's own preprocessors
  (`python/sdfx/controlnet.py`).
- `scripts/third_party.py` records the repository as `reimplemented` / `no license file published at pinned source`.

## What the upstream extension does

It adds a "ControlNet Preprocessor" post-processing step to the A1111 Extras tab (single image, batch process, batch from
directory). Controls: Enable, Module, Pixel Perfect, and (per module) Preprocessor resolution, Threshold A and Threshold B;
it runs the sd-webui-controlnet preprocessor on the image after the other Extras steps and writes
`ControlNet Preprocessor: "{'module':..., 'processor_res':..., 'threshold_a':..., 'threshold_b':...}"` into the extras infotext.
It needs sd-webui-controlnet (or Forge's built-in).

## Mapping

| Upstream | Here |
|---|---|
| Enable | the **Run preprocessor** button of the card (a separate step, nothing runs implicitly) |
| Module | **Module** combo: the engine's preprocessor list without `none` and `inpaint` |
| Pixel Perfect | **Pixel perfect** (canny and the detector modules): resolution = short side of the image (target size = image size) |
| Preprocessor resolution | slider 64-2048, default 512; shown only for modules that have a resolution |
| Threshold A / B | the module's own sliders (canny low/high threshold, gamma, down sampling rate, variation, sigma, Marigold steps); table in `core/.../model/PreprocessorControls.scala` |
| runs after the other Extras steps | **Use the current result as input** (upscale / colour-corrected result) |
| Batch Process / Batch from Directory | **Run on folder**: input folder to output folder, name order, `<name>-<module>.png`, never overwrites, per-file failures reported, Cancel |
| extras infotext | PNG `parameters` = `ControlNet Preprocessor: "{...}", Size: WxH`; the input's parameters are kept as `Source parameters` |
| (none) | send to a txt2img / img2img ControlNet unit (unit 0-2; preprocessor set to `none`); `controlnet_preprocess` MCP tool |

## Where the code is

| Part | File |
|---|---|
| Engine: `standalone` preprocess request (`processor_res`, `pixel_perfect`, infotext PNG), feature `controlnet_preprocess_extras` | `python/sdfx/controlnet.py` (`standalone_preprocess`, `save_preprocessed`), `engine.py`, `dryrun.py`, `base.py`, `params.py` |
| Scala request and generator step (`preprocessExtras`, output naming) | `core/.../protocol/Protocol.scala`, `engine/.../Generator.scala` |
| Module controls table, validation | `core/src/main/scala/sdfx/core/model/PreprocessorControls.scala` |
| Extras card (JavaFX) | `ui/src/main/scala/sdfx/ui/view/ControlNetExtrasCard.scala`, `ToolTabs.scala` (`ExtrasTab`) |
| MCP tool | `mcp/src/main/scala/sdfx/mcp/ControlNetPreprocessTool.scala`, `McpTools.scala` |
| Tests | `python/tests/test_controlnet_preprocess_extras.py`, `core/.../PreprocessorControlsSuite.scala`, `ProtocolSuite`, `ui/.../ControlNetExtrasCardSuite.scala`, `McpToolsSuite` |
| Real checks | `scripts/dev/check_controlnet_preprocess_extras_real.py` (MCP), `ui/src/test/scala/sdfx/ui/RealControlNetExtrasCheck.scala` (real MainWindow) |

Single-image results are saved in `outputs/extras/` (the folder the Image Browser's Extras tab lists), folder runs in the
chosen output folder. Resolution rules follow the ControlNet unit: canny resizes the short side to the resolution, pads to
a multiple of 64, detects and crops, so with an explicit resolution the output has the scaled size (pixel perfect keeps the
image size). Modules without a resolution (invert, recolor, tile, blur, depth, Marigold) ignore it; the MCP tool says so in
`notes` and the card hides the controls.

## Verification (2026-11-02)

Jar built from this worktree; engine `python/.venv-smoke` (torch, CUDA 3090); GPU lock held only during the MCP runs.

### Unit tests
- `python/tests/test_controlnet_preprocess_extras.py`: 13 tests (canny resolution rules pixel perfect / slider / default /
  out of range, thresholds reach the detector and the info, defaults reported when no argument is given, resolution ignored
  with a note, first slider is threshold A, unknown module, PNG infotext and `Source parameters`, engine request via the
  dry-run engine, plain requests unchanged, feature advertised).
- Scala: `PreprocessorControlsSuite` (5), `ProtocolSuite` request round trip, `ControlNetExtrasCardSuite` (2),
  `McpToolsSuite` tool discovery / rejection / protected session. Full suites on the final tree: Python `pytest python/tests` (CPU) 2879 results, exit 0; Scala `sbt test` all modules green and `ui/assembly` built (`ModelCardCatalogSuite` and `NativePresetsCoverageSuite` failed once in a parallel full run, the second because the ledger was not regenerated yet, and passed on rerun).

### Real SD1.5 `animekawa_v10` 512x512 and SDXL `waiIllustriousSDXL_v170` 832x1216 through the official MCP SDK
(`outputs/cn-extras-real-{sd15,sdxl}/receipt.json`; both `success: true`).

- Source image generated, then `controlnet_preprocess`: canny with pixel perfect keeps the size (512x512, 832x1216) and the
  output is purely 0/255; `processor_res 256` gives 256x256 on SD1.5 and 256x374 on SDXL; the default is 512 on the short side.
- Thresholds: edge pixels low (20/60) / default (100/200) / high (220/250) = 41418 / 6510 / 2403 on SD1.5 and
  128721 / 50220 / 27943 on SDXL, strictly ordered; the info records `threshold_a/b` and `processor_res`.
- invert equals the exact complement of the source (bit exact); invert with a resolution returns the "no resolution" note;
  recolor_luminance matches Rec.709 luma within 2 levels and gamma 2 darkens it; tile_resample (rate 4) and blur_gaussian
  (sigma 12, gradient under 60 percent of the source) behave as documented; `depth` (DPT hybrid) and `depth_marigold`
  (2 steps) run on SD1.5 and return same-size greyscale with a spread of values.
- PNG parameters start with `ControlNet Preprocessor: "{'module': 'canny', 'processor_res': 512, ...}"`, contain `Size:` and the
  source's generation parameters as `Source parameters`.
- Folder run: 3 images + a text file: `a-canny.png`, `b-canny.png`, `c-canny.png`; pixel-identical to the single-image run;
  a second run adds `-1` files and overwrites nothing; a corrupt `d.png` fails the call naming `d.png`; the text file is untouched.
- Bad input refused without disturbing the engine: unknown module, `low_threshold` 0, an argument the module does not use,
  image and folder together, `processor_res` with `pixel_perfect`, resolution 32, missing file, a file outside the allowed
  folders; base64 input equals the path result. Regenerating the source afterwards is bit exact.
- Integration (SD1.5 only, `lllyasviel/control_v11p_sd15_canny` fp16): the preprocessed canny used as a ControlNet unit
  image (`preprocessor: none`) reproduces the generation made with a unit that runs its own canny (pixel perfect) on the
  source **exactly** (max difference 0); both differ from the plain generation (mean absolute difference 34.4).
- Images looked at (contact sheets of every output of both runs): the canny maps follow the lighthouse, cliff, island and
  clouds with thin clean lines; low thresholds are dense, high thresholds sparse; invert, luminance, gamma, tile and blur look
  as expected; the DPT depth is dark but correctly ordered (cliff nearest) and Marigold depth is a clean near = bright map;
  both ControlNet generations keep the source composition (cliff right, island centre) and are indistinguishable.

### Real MainWindow (`RealControlNetExtrasCheck`, real engine, software renderer, screenshots `outputs/cn-extras-ui`)
Passed: the module list has no `none` / `inpaint`; canny shows both sliders and the resolution, Pixel perfect disables the
resolution slider; a canny run keeps the 512x512 size and writes the infotext, result in `outputs/extras`; resolution 256 with
low threshold 40 gives a 256 px short side and `threshold_a 40`; recolor hides the resolution and Pixel perfect, shows
Gamma correction and honours gamma 2; invert shows no sliders; **Use the current result as input** runs blur on the invert
result (`Source parameters` contains the invert info); **Send to txt2img ControlNet** sets unit 0 image and `none`; folder run
`3 of 4 written ... 1 failed, first: d.png`, then a second run adds `-1` files; output folder equal to input is refused and
the input folder is untouched; a 60-image run cancelled after 6 images reports `(cancelled)` and a single run works after it.
Screenshots were inspected: the card is laid out as intended and the status and error toasts are readable.

## Not provided / deviations

- The set of modules is this app's, not every sd-webui-controlnet module. The detector modules (openpose, lineart,
  lineart_anime, softedge_hed, mlsd, normal_bae) are wired (resolution control, `resolution` argument) but need
  `controlnet_aux`, which is not installed here, so they were **not run**. The listed modules in the real runs were invert,
  recolor_luminance, recolor_intensity, tile_resample, tile_colorfix, blur_gaussian, canny, depth, depth_marigold,
  normal_marigold (the last, recolor_intensity and tile_colorfix only by the unit tests / listing, not run on images).
- Forge-only module sliders (such as depth_leres++ "Remove Near %" / "Remove Background %" in the README screenshot) do not exist
  in this app's modules.
- Not a checkbox in the Extras pipeline: the card is run explicitly, optionally on the current result. The A1111 Extras
  "Batch Process" (file list) and the companion video tab are not provided; a folder run covers batch from directory.
- SDXL ControlNet generation was not run (no SDXL ControlNet is available locally); the preprocessor itself was verified on
  SDXL-size images.
- Single results land in `outputs/extras/<name>-<module>.png`, not in a dated folder.
- Protected (scrambled) sessions: the card passes the session secret through the existing protected-input / protected-output path (the same code the Extras upscale uses) but this was **not exercised** with a real protected image; the MCP tool refuses (unit-tested).
