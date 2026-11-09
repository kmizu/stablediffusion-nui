# Image Browser (ledger index 24, target `core:images_browser`) verification

Issue: kmizu/stablediffusion-nui #142. Upstream: <https://github.com/AlUlkesh/stable-diffusion-webui-images-browser>,
pinned at `12a951318b27abd0d9099451e92cbf1de508ee4f` (default branch `main`, last push 2026-10-26).

## Licence check and what was used

- `gh api repos/AlUlkesh/stable-diffusion-webui-images-browser/license` returns 404 and the repository root
  (`.gitignore`, `README.md`, `install.py`, `javascript/`, `preload.py`, `scripts/`, `style.css`) has no licence file.
  The code is therefore unlicensed: **no upstream code was copied or translated.**
- Used: the README (updates list, keybinding table) and, to enumerate every control, option and tab,
  `scripts/image_browser.py` (2085 lines, `create_tab`, `on_ui_settings`, the keybinding and component lists). That
  reading produced only the feature table below; the implementation is an independent design.
- Where the README and the visible UI are silent, the behaviour is the app's own and is listed under
  "Deliberate differences".
- `scripts/third_party.py` records the repository as `reimplemented` / `no license file published at pinned source`.

## Where the code is

| Part | File |
|---|---|
| Settings, tabs, queries, sorting, paging, metadata, file operations, store, maintenance (no JavaFX) | `core/src/main/scala/sdfx/core/imagebrowser/*.scala` |
| View models (tab state, selection, favourites, delete plan, keys, info rows, maintenance messages) | `ui/src/main/scala/sdfx/ui/vm/ImageBrowserVM.scala` |
| JavaFX tab and settings dialog | `ui/src/main/scala/sdfx/ui/view/ImageBrowserTab.scala` |
| Wiring (tab, right pane hidden while open, send to Extras) | `ui/src/main/scala/sdfx/ui/MainWindow.scala`, `UiContext.scala` |
| Persistent data | `config/image-browser.json` (options, rankings with content hash, saved directories), `config/image-browser-cache.json` (metadata cache) |
| Tests | `core/src/test/scala/sdfx/core/ImageBrowserSuite.scala`, `ui/src/test/scala/sdfx/ui/ImageBrowserVMSuite.scala` |
| Real JavaFX check | `ui/src/test/scala/sdfx/ui/RealImageBrowserCheck.scala` |

The old "gallery" (the strip under the preview) is unchanged and still serves the current session; the Image Browser is
the dedicated, persistent, folder-based browser. The two share nothing except the image files and the existing
send-to / restore code paths.

## Feature mapping

Status: **Implemented** = built natively and verified (V = unit/VM tests, R = real JavaFX check run, S = screenshot
inspected); **Equivalent** = covered by an existing or different mechanism; **N/A** = does not apply to a native app;
**Not provided** = a gap, not hidden.

### Tabs

| # | Upstream | Status | NUI |
|---|---|---|---|
| T1 | txt2img, img2img, Extras tabs | Implemented (V R S) | `outputs/txt2img`, `img2img`, `extras` (engine layout `<kind>/<date>/NNNNN-seed.png`, scanned recursively). |
| T2 | txt2img-grids, img2img-grids | Implemented (V R) | Both list `outputs/grids`; NUI has a single grid folder for both modes (deliberate difference D1). |
| T3 | Favorites tab with "Favorites path from settings" | Implemented (V R S) | Lists the favourites folder; the path is shown on the tab and is an option. No favourites button on this tab, as upstream. |
| T4 | Others tab (folder input) | Implemented (V R S) | Folder field, Browse, Load. |
| T5 | All tab | Implemented (V R S) | Union of the standard folders and favourites, de-duplicated. |
| T6 | Maintenance tab | Implemented (V R S) | See M1-M9. |
| T7 | Custom tabs (folder paths in "active tabs") | Implemented (V) | `BrowserTabs.parseList` handles quoting; each path becomes a tab. Not driven in the real check. |
| T8 | Active tabs option | Implemented (V R) | Settings dialog, text field; rebuild verified live (9 -> 4 tabs). |
| T9 | Initial sort / state per tab | Implemented (V R) | Each tab keeps its own query, page and selection while the app runs. |

### Gallery and paging

| # | Upstream | Status | NUI |
|---|---|---|---|
| G1 | First Page / Prev Page / Next Page / End Page | Implemented (V R) | Buttons disabled at the ends. |
| G2 | Page Index number + "Page Index (n/N)" label | Implemented (V R) | Text field with Enter; label text identical. |
| G3 | Refresh button | Implemented (V R) | `⟳` and key `R`. Switching to an inner tab also rescans (cache makes it cheap). |
| G4 | Thumbnail gallery, columns x rows per page | Implemented (V R S) | `TilePane`; 6 x 6 default. |
| G5 | Size tooltip for thumbnails | Implemented (V) | Tooltip with dimensions, file size, time and the first 300 characters of the generation text. Tooltip content is unit-tested; hovering was not driven. |
| G6 | Thumbnail size, crop to square, tooltips on/off, progress indicator | Implemented (V R S) | Options `thumbnailSize`, `thumbnailCrop` (screenshot `browser-10-cropped`), `imgTooltips`, `showProgress` ("Loading..." text). |
| G7 | Optimised images in the thumbnail interface | Equivalent | Thumbnails are always decoded down to 2x the thumbnail size, off the UI thread, and cached (500 entries). |
| G8 | Pages per load, automatic gallery height | N/A | Gradio gallery transport options; the grid is a virtualised-free local control and page size is rows x columns. |
| G9 | Preload images at startup for the first tab | Implemented (R) | Off: no scan until the tab is shown. On: first listing tab scanned when the tab object is built. |
| G10 | png-cache ("scanning a directory for the first time can take minutes") | Implemented (V R) | Metadata cache keyed by (path, mtime, size), persisted to JSON, invalidated by file changes. |
| G11 | Swipe left/right to the next image | Implemented (R, synthetic events only) | `SwipeEvent` handlers; verified with synthetic events, **no touch hardware was available**. |
| G12 | Video support (list videos, "Show video frame", player size/position options) | Implemented (V) | v0.11: selecting a video shows an in-app preview (first frame, Play/Pause, seek bar) with JavaFX Media (`VideoPreviewPane`); undecodable files show a message and keep "Open video". Real check `RealVideoPreviewCheck` with an H.264 mp4: ready, playing advances 1 ms -> 2000 ms, still image hides the pane, corrupt mp4 falls back. The three `video_*` option names are N/A (size follows the pane). Formats beyond what the JavaFX decoder plays still open in the system player. |
| G13 | JPEG / WebP / GIF support | Implemented (V) | `.png .jpg .jpeg .webp .gif`; EXIF `UserComment` (A1111 layout) read from JPEG and WebP, tested with a synthetic TIFF block. |
| G14 | ".txt file information" | Implemented (V R) | Side-car `name.txt` used when the image has no embedded text; verified with a real JPEG + .txt in the real check. |

### Sorting, filtering, search

| # | Upstream | Status | NUI |
|---|---|---|---|
| S1 | Sort by: path name, date, aesthetic_score, random, cfg scale, steps, seed, sampler, size, model, model hash, ranking | Implemented (V R) | All 12 keys; the real check sorts real images by each key and compares with the file data. |
| S2 | Sort order flip | Implemented (V R) | ▲ / ▼ button. Default newest first. |
| S3 | Filename keyword search | Implemented (V R) | Case-insensitive on the whole path. |
| S4 | EXIF keyword search | Implemented (V R) | Case-insensitive on the generation text; images without text never match. |
| S5 | Search negative prompt: No / Yes / Only | Implemented (V R) | |
| S6 | regex search (`^(?!.*Hires).*$`) | Implemented (V R) | DOTALL; invalid regex shows "Regex error: ..." and is ignored. |
| S7 | Ranking filter: All, 1-5, None, Min-max with min/max | Implemented (V R) | Min/max spinners enabled only for Min-max; swapped bounds accepted. |
| S8 | Aesthetic score minimum / maximum | Implemented (V R) | Real `aesthetic_score` values 5.5 / 5.9 / 6.1 used in the check. Images without a score are excluded while a bound is set. |
| S9 | Additional sorting and filtering by EXIF data incl. .txt | Implemented (V R) | The jpg's .txt supplies seed 31337 / cfg 4 in the check. |

### Selection, info, file facts

| # | Upstream | Status | NUI |
|---|---|---|---|
| I1 | Generation Info text | Implemented (V R S) | Read-only text area. |
| I2 | Formatted display (Key/Value table), display order option | Implemented (V R S) | `infoFormat`, `infoOrder`; rows follow the configured order. |
| I3 | Additional Generation Info | Implemented (V R) | Fields outside the configured order; shown when `infoAdd` is on. |
| I4 | File Name, File Time | Implemented (V R S) | Full path; `yyyy-MM-dd HH:mm:ss`. |
| I5 | Open Folder button | Implemented (V) | Selects the file in Explorer (the existing `ImageActions.openFolder`). Not clicked in the real check (it launches Explorer). |
| I6 | Large preview of the selected image | Implemented (R S) | Right panel. |
| I7 | File size / dimensions | Implemented (R) | "419.2 KB - 512 x 512". |
| I8 | Hidable UI components (15 names) | Implemented (V R) | Same 15 names; one hidden in the real check ("Ranking Bar"); the mechanism is shared. |

### Ranking and favourites

| # | Upstream | Status | NUI |
|---|---|---|---|
| R1 | Current ranking + Set ranking to 1-5 / None | Implemented (V R S) | Radio buttons; badge of stars on the thumbnail. |
| R2 | Keys 0-5 | Implemented (V R) | Digit and numpad keys. |
| R3 | Ranking persistence | Implemented (V R) | `config/image-browser.json`, with the file's SHA-256. |
| R4 | Save ranking in image's pnginfo | Implemented for PNG (V R) | `ranking` tEXt chunk written by a chunk-level rewrite; image decodes unchanged. JPEG/WebP rankings live in the store only. |
| R5 | Favorites button, Move/Copy to favorites | Implemented (V R) | Label follows the copy option; `F` key; ranking and `.txt` follow. |
| R6 | "Move buttons copy instead of move" | Implemented (V R) | |
| R7 | Move/Copy/Delete matching .txt files | Implemented (V R) | |
| R8 | Copy/Move to directory panel (path, saved directories, button) | Implemented (V R) | |
| R9 | Add/Remove from saved directories via buttons | Implemented (V R) | On the Others tab. |

### Delete

| # | Upstream | Status | NUI |
|---|---|---|---|
| D-1 | Delete button, "delete next N", "also delete off-screen images" | Implemented (V R) | Plan -> confirmation -> delete. |
| D-2 | Recycle bin option | Implemented (V R) | `Desktop.moveToTrash`, falling back to `<outputs>/.trash/<timestamp>/`. The real check confirms the files left the folder and the confirmation names the recycle bin. Verified only that files disappear; the Windows recycle bin contents were not inspected. |
| D-3 | Delete messages to the console | Implemented (R) | `[image-browser] Deleted 2 images to the recycle bin` on stdout. |
| D-4 | `Delete` key | Implemented (V R) | Opens the same confirmation (never deletes silently). |

### Send to

| # | Upstream | Status | NUI |
|---|---|---|---|
| X1 | Send to txt2img | Implemented (R) | Restores parameters through the existing Fastload/infotext path; prompt verified in the form. |
| X2 | Send to img2img / inpaint | Implemented (R) | Input image and parameters (existing `sendTo`). |
| X3 | Send to extras | Implemented (R) | Sets the Extras input image. |
| X4 | Send to txt2img / img2img ControlNet + ControlNet number | Implemented (R) | Sets only the image of the chosen unit (existing `applyFastloadTarget`), other units untouched (verified). |
| X5 | Send to openOutpaint | N/A | openOutpaint is another web UI extension that NUI does not have. |

### Keybindings

| # | Upstream | Status | NUI |
|---|---|---|---|
| K1 | `0-5`, `F`, `R`, `Delete` | Implemented (V R) | |
| K2 | `Ctrl+Left/Right` page turning | Implemented (V R) | |
| K3 | Ctrl -> Shift, or Ctrl+Shift (options) | Implemented (V) | `BrowserKeys.resolve`; the real check uses the default Ctrl. |

### Maintenance

| # | Upstream | Status | NUI |
|---|---|---|---|
| M1 | Rebuild exif cache | Implemented (V R S) | |
| M2 | Delete 0-entries from exif cache | Implemented (V R) | |
| M3 | Update directory names in database (path_recorder, exif_data, ranking; From/to) | Implemented (V) | Core operation and VM wrapper tested; the real check only exercises the empty-input message. |
| M4 | Recreate hash for existing files | Implemented (V R) | |
| M5 | Reapply ranking after moving files | Implemented (V R) | Unit test moves a ranked file outside the app. |
| M6 | Rebuild ranking from exif info | Implemented (V R) | |
| M7 | Restart debug / Get javascript logs / debug level | N/A | Debugging aids for the Gradio JavaScript front-end. |
| M8 | `--image-browser-tmp-db` command-line option | N/A | NUI keeps JSON files in `config/`, not an SQLite database on the web UI's disk. |
| M9 | Status / Last message | Implemented (R S) | Buttons are disabled while one runs. |

### Settings

| Upstream option | Status |
|---|---|
| active_tabs, hidden_components, with_subdirs, preload, copy_image, delete_message, txt_files, delete_recycle, scan_exif, mod_shift, mod_ctrl_shift, swipe, ranking_pnginfo, page_columns, page_rows, thumbnail_size, thumbnail_crop, img_tooltips, show_progress, info_format, info_order, info_add | Implemented, all in the Settings dialog (R: dialog driven for active tabs, sub directories and a hidden component; the rest V or by code path) |
| pages_perload, height_auto, use_thumbnail | N/A / Equivalent (G7, G8) |
| debug_level | N/A (M7) |
| video_pos, video_x, video_y | N/A (G12) |

## Deliberate differences (D)

- D1: one `grids` folder serves both grid tabs.
- D2: images that lack the sort field are listed after the others in both directions; they are never dropped.
- D3: the `size` sort orders by pixel count; names use a natural (numeric-aware) order for text fields, while "path name"
  is plain string order.
- D4: rankings are stored in the app and optionally in PNG tEXt, with a content hash so moved files are found again.
- D5: Favorites and the other listing tabs follow "Include images in sub directories"; only Others has an explicit depth.
- D6: a confirmation is always shown before deleting, even when moving to the recycle bin.
- D7: while the tab is open the preview/gallery column is hidden.

## Not provided

1. (v0.11) Video playback now exists in-app for formats the JavaFX decoder supports; other formats open in the system player.
2. "Send to openOutpaint" (no such extension).
3. Rankings in JPEG/WebP files (store only).
4. Real touch swipe was not exercised, only synthetic `SwipeEvent`s.
5. Not driven in the real check: Open Folder (starts Explorer on the user's desktop), the Shift and Ctrl+Shift key modifier options and the
   Maintenance rename operation with real data (unit-tested). Driven since v0.11 (`RealGalleryExtrasCheck`, real MainWindow, real files):
   the thumbnail tooltip content ("still.png 512 x 512  617.1 KB <time>" plus the generation text; the installed Tooltip is read, the hover timer
   itself is not simulated), a custom folder tab added to the active tabs (lists its 2 files), and the real Windows recycle bin (the app's
   `SystemTrash` moved a file there; it was listed by `Shell.Application` and then removed from the bin again).

## Verification

Environment: Windows 11, JDK 21, sbt 2.0.12, dry-run engine (no model is needed: the browser reads files only). Source
images were real generations from earlier runs of this repository (`outputs/inc-real-sd15`, `cls-real-sd15`,
`aes-real-sd15`, `inpaint-native-final-sd15`, `cdt-real-sdxl`: SD1.5 and SDXL PNGs with their A1111 parameter text,
including real `aesthetic_score` values), copied into an isolated outputs folder together with a JPEG + `.txt` and a
placeholder video. No GPU or network was used.

- Scala unit tests: `ImageBrowserSuite` (core, 19 tests) and `ImageBrowserVMSuite` (ui, 11 tests) cover settings
  sanitising and persistence (including a corrupt file), tab resolution, every sort key and direction, natural order,
  filename/EXIF/negative/regex search, ranking filters, aesthetic bounds, paging arithmetic, depth rules, hidden folders,
  side-car text, JPEG/WebP EXIF decoding, the PNG ranking chunk (pixels preserved), favourites move/copy with ranking and
  `.txt` follow, never overwriting, trash vs permanent delete, delete plan limited to the page, maintenance operations and
  the metadata cache across restarts; plus selection, neighbour navigation across pages, keybindings and the Others tab.
- `sbt test`, the Python suite and `ui/assembly`: see the result lines at the end of this file.
- Real application check: `ui/Test/runMain sdfx.ui.RealImageBrowserCheck <python> outputs/ib-real <source-outputs>`
  mounts the real `MainWindow` (dry-run engine), selects the Image Browser tab and drives the real controls
  (button `fire`, mouse-click events on tiles, key events, modal dialogs, the settings dialog) over 19 groups: paging,
  thumbnails/info, 12 sort keys, filters, ranking, favourites, copy/move, send-to (txt2img, img2img, inpaint, Extras,
  ControlNet unit 1 in both modes), delete with Cancel and OK, img2img/Extras/grids/All tabs, Others with depth/saved
  directories/sub directories, Maintenance, swipe, the settings dialog, preload. It exits non-zero and writes a failure
  screenshot on any failed assertion. The screenshots were opened and looked at: `browser-02-selected-info.png`
  (selection highlight, large preview, Key/Value info, file facts), `browser-03-ranking.png` (3-star badge, ranked item
  first), `browser-06-all.png` (img2img + Extras + txt2img merged), `browser-07-others.png` (depth 2, sub directory
  `pics`), `browser-08-maintenance.png`, `browser-10-cropped.png` (square thumbnails). Two layout defects found this way
  (cramped split pane, "_" swallowed by mnemonic parsing in the checkbox labels) were fixed and re-checked.

### Results

- `sbt -batch test` (Java 21, sbt 2.0.12): all suites pass. First run: core 89 passed (incl. `ImageBrowserSuite`, 19 tests), ui 194 with
  the ledger gate `NativePresetsCoverageSuite` failing and plugins 207 with `BuiltinsSuite` failing, both because the first
  target name (`images_browser`) was not a registered plugin; those two suites require native, non-plugin features to
  use the `core:` prefix, so the ledger target is **`core:images_browser`** (a deliberate departure from the bare name in
  the issue). After the rename the failed suites passed on re-run and the build ended `[success]`; sbt 2 skips unchanged
  suites on later runs.
- `ui/assembly` succeeded (`target/stablediffusion-nui.jar`, 142 MB, contains the new classes).
- `python/tests/test_third_party.py` (the only Python test that reads the generated ledger/notice data): 6 passed.
- Full Python suite (`.venv-smoke`, CPU): about 80 % of the suite passed with no failure before it was stopped, because the
  remaining CPU-torch tests were taking hours; no Python file is changed by this work.
- `python scripts/extensions_matrix.py` and `python scripts/third_party.py` were run; the licence files' CRLF-only
  diffs were reverted; only the new notice line was kept.
- The Real check is final: `IMAGE BROWSER CHECK PASSED (19 groups)` on the last build of the sources, after which only
  the header status text on the Maintenance tab was changed.
