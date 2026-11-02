# Character Searcher verification

Ledger target `core:chara_searcher` (index 331, `NON906/chara-searcher`, MIT, pinned `dee047567fd2a4c9adc8deca81cb6a4510002c57`).
Issue kmizu/stablediffusion-nui#10. Date of the runs: 2026-11-02.

## What was built

A native **Character Searcher** main tab. Data preparation runs in the engine (`python/sdfx/chara_searcher.py`, request
type `chara_searcher`, actions `import` and `embed`); the search, the Exclude / Threshold clicks and the export run in
Scala without the engine (`core/.../charasearch`, `ui/.../CharaSearcherVM`, `CharaSearcherTab`), so a search over thousands
of images needs no round trip. Dataset folders use the upstream layout: cut-out PNGs, one `<name>.txt` of tags each,
`embedding.npz` (key `embedding`, float32 `[N, 512]`) and `embedding.json` (`{"files": [{"path", "width", "height"}]}`).

| Extension feature | Native implementation |
|---|---|
| Upload Images Directory / Upload Video File | file chooser buttons plus a path box; folders are walked recursively; videos give one frame every N seconds |
| Character extraction, background removed | anime person detector (deepghs, YOLOv8, MIT) for the boxes, ISNet anime segmentation (SkyTNT, Apache-2.0) for the matte, only regions connected to a detection kept, touching characters split by box ownership |
| Tagging | WD ViT tagger v3 (SmilingWolf, Apache-2.0), threshold 0.35, underscores to spaces, parentheses escaped, ratings excluded (the upstream caption format) |
| Embedding | CLIP ViT-B/32 (the model behind sentence-transformers `clip-ViT-B-32`) |
| Target Datas | multi-select list of the dataset folders |
| Search Image / Search Image Threshold | CLIP cosine similarity, sorted, hidden below the threshold |
| Search Tags / Negative Tags | comma separated, every search tag required, no negative tag allowed; case, underscores and escapes ignored |
| Click on the Image: Exclude / Threshold, Reset Excluded Images | same two click modes and reset |
| Export: directory, Additional Tags, Exclude Tags | copies every displayed image as `NNNNN_<name>.png` with a caption `.txt` |

Models are not bundled. Each file is downloaded on first use from a pinned Hugging Face revision into
`SDFX_CHARA_CACHE` (default `~/.cache/stablediffusion-nui/chara_searcher`) and its size and SHA-256 are checked before it is
used (total about 1.26 GB: detector 103,459,671, matte 176,069,933, tagger 378,536,310 + 308,468 tag list, CLIP 605,247,071 + 4,186 bytes).
All models run on the CPU through ONNX Runtime / torch, so the GPU stays with the loaded checkpoint.

## Licence and provenance

- `NON906/chara-searcher` is MIT at the pinned commit (`licenses/NON906__chara-searcher.txt`, SHA-256 `d94e1eee...`). Its LICENSE
  says the licence does not cover the submodules `CartoonSegmentation` and `wd14-tagger-standalone`; neither is used or
  copied. The README, `segmentation.py`, `tagging.py`, `calc_embedding.py` and `frame_extraction.py` were read to learn the
  pipeline and the file formats; the code was written independently.
- Models: deepghs/anime_person_detection (card: MIT), skytnt/anime-seg (card: Apache-2.0), SmilingWolf/wd-vit-tagger-v3 (card: Apache-2.0),
  openai/clip-vit-base-patch32 (the card states no licence; the code repository is MIT). All are listed in `THIRD_PARTY_NOTICES.md`
  with their pinned revisions and digests. The upstream CartoonSegmentation checkpoint (`rtmdetl_e60.ckpt`, MIT) was not used.

## Differences from the extension (recorded in `SCOPE_NOTES`)

1. Upstream cuts characters with CartoonSegmentation, an mmdet / mmcv instance segmentation. Those packages cannot be shipped with this app
   (they need a compiler toolchain and pinned CUDA builds). The replacement finds boxes with a detector and cuts them out with a
   salient-object matte. Where two characters overlap, the pixels of the overlap are given to the character whose box centre is nearer;
   an arm in front of the neighbour can therefore be cut off (visible in the SDXL pairs below). An instance model would do better.
2. The detector also fires on animals and other drawn figures (a lion and a tiger picture gave three cut-outs) and misses some close-up
   faces (2 of the 10 reused pictures of the UI check gave no cut-out). The thresholds are the Instance and Mask fields.
3. Upstream tags with the wd14-tagger-standalone model of your choice (default moat v2); this app uses the pinned WD ViT v3 only.
4. Video frames are sampled by time with OpenCV (default every 4 s) instead of ffmpeg key frames at 0.25 fps.
5. The mask curve is the ISNet probability with a soft edge of width 0.3 around the Mask threshold (upstream stretches the upper range of its
   refine net output).
6. Not provided: the DuckDuckGo image-search branch, the standalone Gradio UI, choosing the tagger model, the K-means script `classification.py`.
7. No MCP tool: the feature is an interactive tab. The engine request `chara_searcher` is internal.

## Tests

| Suite | Result |
|---|---|
| `python/tests/test_chara_searcher.py` (22: pins, option validation, letterbox, NMS, YOLO decoding, crop, mask curve, instance isolation, box ownership, tag selection and escaping, CLIP preprocessing, source collection, video sampling, import layout, incremental import, name collisions, unreadable files, interruption, request validation, dry-run engine over pipes) | 22 passed |
| Neighbouring Python suites (`test_server_dryrun`, `test_aesthetic_score_engine`, `test_prompt_fusion_features`, `test_nsfw_checker_engine`) | passed together with the above |
| `core` `CharaSearcherSuite` (11: npz reader against a numpy-written fixture and both header versions, malformed arrays, tag normalisation, dataset loading and problems, search AND / NOT, similarity order and threshold, excluded keys, captions, export numbering and no-overwrite, vanished files) | 11 passed |
| `engine` `CharaSearcherServiceSuite` (4: request shape and progress events through the fake engine, embedding result, argument validation, cancellation) | 4 passed |
| `ui` `CharaSearcherVMSuite` (5: dataset list and selection, search narrowing, click modes, folder names, export) | 5 passed |
| `NativePresetsCoverageSuite` (checks the generated ledger against the sources) | passed after regenerating `docs/EXTENSIONS.md` |
| Full `sbt test` | see "Full regression" below |

## Real-model verification

### Fixtures (real SD1.5 and SDXL through the official MCP SDK)

`scripts/dev/check_chara_searcher_real.py` generated, through the packaged MCP server and the official `mcp` client, five
pictures per model: two girls side by side (red/blue hair, blonde/black hair), a girl with silver hair, a girl with green hair and a
boy. SD1.5 `animekawa_v10` 512x512 (24 steps, CFG 6, Euler, `model_offload`) and SDXL `waiIllustriousSDXL_v170` 832x1216
(`sequential_offload`). Both runs held `outputs/.gpu.lock` only for the generation. The pictures were looked at: the SD1.5 boy prompt gave a front and
a back view (two figures), the SDXL two-girl pictures show girls standing shoulder to shoulder with overlapping arms.

### Library level (`scripts/dev/check_chara_searcher_data.py`, CPU, real detector / matte / tagger / CLIP)

10 generated pictures plus a 48 s video made from three of them (frame every 4 s -> 6 frames):

- 25 cut-outs from 11 sources in 69 s on the CPU (including model loading). All four two-girl pictures (2 SD1.5, 2 SDXL) gave exactly 2 cut-outs; the six
  single-character pictures gave 1, except the SD1.5 boy picture, which really contains two figures (2). The video gave 10 cut-outs from 6 frames.
- Every cut-out is RGBA with a transparent corner and a non-empty caption. The captions name the right things: `1boy` on the boy,
  `red hair` and `blue hair` across the red/blue pair, `blonde hair` and `black hair` across the other pair.
- Image queries: the picture of a single character finds its own cut-out first with similarity 0.978 (SD1.5 silver), 0.955 (SDXL green),
  0.923 (SDXL boy) and 0.773 (SD1.5 boy, where the second best, the other boy of the same picture, has 0.763).
- Tag filters on the captions: `1boy` 7 of 25, `1girl` 19 of 25; the SDXL single girls are in `1girl` and not in `1boy`.
  The tagger put both `1boy` and `1girl` on the androgynous SDXL boy; this is the tagger's output.
- A second import of another picture added 2 cut-outs and left the 25 earlier embedding rows bit for bit identical.

The cut-out contact sheet (cut-outs on green) was inspected before and after the ownership fix. Before it, the touching SDXL girls had hard
vertical edges with a sliver of their neighbour; after it each girl is clean, with a straight cut where the two overlapped.

### Real application (`ui/Test/runMain sdfx.ui.RealCharaSearcherCheck`)

The real `MainWindow` with the engine process (`--dry-run`, so no checkpoint or GPU is touched; the models of this feature run for real), driven
through its controls with screenshots (inspected). Sources: 10 real generated pictures from earlier SD1.5 / SDXL runs and a video.

Ten control groups passed (second run, after the thumbnail cache and status fixes; the first run passed the same ten):

1. The tab opens empty (`No dataset selected`).
2. Importing the folder of 10 real pictures plus the video through the Import button gave **14 cut-outs** with `.txt` captions
   (`Imported 14 character image(s) from 11 source(s) into 'demo set' (14 in total).`). Two of the ten pictures gave no cut-out (the
   detector found no figure in them) and one lion-and-tiger picture gave three; the video gave four.
3. Selecting the dataset shows all 14 thumbnails, each decoded (`14 of 14 images`).
4. Search Tags `solo` keeps 13 of 14 and Negative Tags `solo` keeps the 1 remaining.
5. Search Image (a picture that is also a source): all 14 hits sorted by CLIP similarity (0.872 .. 0.433), the top hit is a cut-out of the query picture itself.
6. Threshold 0.652 leaves 4 of 14 (4 expected from the similarities).
7. Click on the Image: Exclude removes one image and Reset Excluded Images brings it back; Threshold mode set the threshold to 0.809, the similarity of the clicked image.
8. Export wrote 14 PNGs and 14 captions; every caption starts with the Additional Tags (`mychar, 1girl`) and none contains the excluded tag `solo`.
9. A second import into the same dataset kept the 14 and added 3 (14 -> 17).
10. A folder name containing `/` and a missing source file are refused with a message and create nothing.

Screenshots (`outputs/chara-ui-real2/chara-0*.png`) were inspected: thumbnails of the cut-outs on a gray tile, similarity and file name under each,
status line, progress bar, the Search group. The lion and tiger picture shows the limit of the box ownership rule: when one figure stands in front of another,
the line between their boxes cuts through both (the three cut-outs of that picture have straight edges). Anime pictures with side by side characters do not show this.

## Not verified

- Hundreds or thousands of images (the largest dataset tested has 27 cut-outs; the grid shows 300 and the search is linear, which is fine for tens of thousands of vectors but was not timed).
- Video formats other than H.264 MP4 written by OpenCV; frame counts above the default cap of 2000.
- GPU execution of the models (deliberately CPU only).
- Comparison with the upstream's own cut-outs, tags and similarity values; the upstream was not run (it needs mmdet / mmcv and conda).
- Hover tooltips, the file choosers and the double-click preview were not exercised through the automated UI check (the choosers are native dialogs).

## Full regression

- `sbt test` over all modules (one run, 865 s): the module totals were 91, 280 (1 skipped) and 296 tests passed, the ui module ran 233 with 1 failure and the core module
  had 3 failing suites. All four failures are environmental, none is caused by this change: `PixelScrambleSuite` needs the `python/.venv-test` junction that a fresh worktree lacks
  (created afterwards); `LocalPreviewFilesSuite` (2 s drive probes) and `ModelCardScannerNativeBulkSuite` (428 s) timed out while a real-model run was using the CPU;
  `NativePresetsCoverageSuite` correctly flagged that `docs/extensions-status.json` had not been regenerated yet. After the junction, the regenerated ledger and on an idle machine,
  `core/testOnly` of the first three (37 tests) and `ui/testOnly NativePresetsCoverageSuite CharaSearcherVMSuite` (10 tests) passed.
- Python (`python/.venv-smoke`, `CUDA_VISIBLE_DEVICES=-1`, whole `python/tests`): 2835 passed, 2 skipped, 0 failed.
- `sbt ui/assembly` builds `target/stablediffusion-nui.jar` with the final code.
