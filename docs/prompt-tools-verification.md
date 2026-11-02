# Prompt Tools (ledger indices 149, 294, 307, 326, target `core:prompt-tools`) verification

Issue: kmizu/stablediffusion-nui #22. Status: **partial** (the commit says `Refs #22`): three of the four extensions are
covered in full except where listed; Prompt All in One is covered only for its offline features.

## Licences and what was used

All four upstreams are MIT. Pinned commits and the SHA-256 of each `LICENSE` at that commit are in
`scripts/third_party.py` (`PINNED_LICENSES`), the texts are in `licenses/` and the table in `THIRD_PARTY_NOTICES.md`.

| Index | Upstream | Pinned commit | Used |
|---|---|---|---|
| 149 | Physton/sd-webui-prompt-all-in-one | `e66d417c37097e6383f81b78d4fd49fdb0081f10` | README feature list only |
| 294 | Haoming02/sd-webui-easy-tag-insert | `f94333651b8742d67ac8297c1a4c3972ee0151a8` | README and the file names of its `examples` folder (`<category>/<name>.tag`) |
| 307 | da2el-ai/sd-d2-prompt-selector | `8f020b4e85f35b74d6fcd928be28a6b42cb28ce8` | README (YAML layout, click / random / Ctrl / right-click / search / `__config__.yml`) |
| 326 | camdenFletcher03/sd-webui-Multi-Prompt-Manager | `9e6ac83e22fba63d3b433cfed1420291e47975b6` | README and its single 90-line script, read for the `prompts.json` format and save/use/delete/copy behaviour |

Nothing was copied; the implementation is an own design. The YAML reader is an own small subset parser (no new
dependency).

## Where the code is

| Part | File |
|---|---|
| Tag splitting, insert, remove, weights, blacklist, dedupe (pure) | `core/src/main/scala/sdfx/core/prompttools/PromptText.scala` |
| Saved prompts (`prompts.json`) | `.../PromptLibrary.scala` |
| History and favourites | `.../PromptHistory.scala` |
| YAML subset reader | `.../MiniYaml.scala` |
| Tag selector files (nested groups, sort, search, random) | `.../TagSelector.scala` |
| Tag cards (`.tag` files, name validation) | `.../TagCards.scala` |
| Storage service | `.../PromptToolsService.scala`, `AppServices.promptTools` |
| View model (no JavaFX controls) | `ui/src/main/scala/sdfx/ui/vm/PromptToolsVM.scala` |
| JavaFX tab | `ui/src/main/scala/sdfx/ui/view/PromptToolsTab.scala` |
| Wiring (tab, history record on every submitted job) | `ui/.../MainWindow.scala`, `UiContext.scala` |
| Tests | `core/src/test/scala/sdfx/core/PromptToolsSuite.scala` (17), `ui/src/test/scala/sdfx/ui/PromptToolsVMSuite.scala` (5) |
| Real check | `ui/src/test/scala/sdfx/ui/RealPromptToolsCheck.scala` |

Data lives in `config/prompt-tools/` (`prompts.json`, `history.json`, `blacklist.txt`, `tag-folder.txt`,
`tag-cards/`, `tag-selector/`). No data file ships in the package: the sample tag file is written by the code, so the
Windows payload allow-list needed no change.

## Feature mapping

| Extension feature | Status |
|---|---|
| 326 save / delete / switch prompts, positive-negative flag, Use, Copy from Active Prompt, auto name `prompt N`, `prompts.json` | Implemented (tests, real check). Added: rename, import with merge, export |
| 307 YAML tag files as tabs, nested groups, list and mapping forms | Implemented (tests, real check) |
| 307 click inserts, group button inserts a random tag, Ctrl-click negative, right-click removes | Implemented (real check with synthetic mouse events) |
| 307 search by tag or button name, hover text, `__config__.yml` sort, any folder, reload | Implemented. Hover text is shown below the list and as a tooltip |
| 307 real-time search option, "save original prompt to pnginfo" option | Not provided (search is always live; the prompt that was generated with is what is stored) |
| 294 `.tag` cards in category folders, grouped by category, insert into positive or negative, editor (add, edit, delete) | Implemented (tests, real check) |
| 294 Extra Networks card size and custom card style, table editor | Not provided (native cards and fields instead) |
| 149 history of prompt changes, favourites | Implemented as a history of the prompt pairs that were generated with (not every keystroke), plus favourites |
| 149 quick tag adjustments: move, weight up and down, delete | Implemented. Drag and drop is not provided (Up/Down buttons) |
| 149 prompt blacklist | Implemented (applied on demand, not silently on every generation) |
| 149 batch operations on several tags | Not provided (one tag at a time; Remove duplicates works on all) |
| 149 disabling a tag, bilingual display, translation, ChatGPT, keyword highlight and colours, themes, thousands-of-tags vocabulary | **Not provided** |

## Tests

* `PromptToolsSuite` (core, 17 tests): bracket-aware splitting incl. full-width commas and unclosed brackets, insert with
  duplicate skipping and separators, remove, weights (floor 0, cap 5, networks untouched), dedupe, blacklist, move;
  library save/rename/merge and the upstream JSON round trip incl. missing `is_negative`; history order, dedupe, limit;
  favourites; YAML subset incl. errors with line numbers and 40-deep nesting rejected; tag files (sort order, broken
  file kept with its error, search, random picks cover all leaves); sample created only for a missing folder; cards
  (save, list, category folders, empty folders removed, 14 unsafe names rejected, BOM, oversized and foreign files
  ignored); service persistence and the tag folder setting.
* `PromptToolsVMSuite` (ui, 5 tests): insert / remove / negative routing, random insertion, saved prompts, history and
  favourites restore, tag editor operations.

Mistakes in my own first draft of the tests (wrong expected values for `(cat)` + 0.1 which is 1.2, a wrong index into the
parsed YAML, a control character that `trim` strips, an off-by-one clock value) were corrected in the tests, not in the
code; no assertion was loosened. The one defect found by the real check was in the product: the History list did not
refresh while the Prompt Tools tab stayed open during generation. It now refreshes when a job is submitted
(`PromptToolsTab.historyChanged`).

Full Scala suite: see the commit message and the final section for the counts and for one environment flake
(`ModelBrowserStateSuite` "Local drive probe timed out" in the first full run, 17/17 on rerun, same flake as recorded in
the encrypt verification). No Python code changed; `python/tests/test_third_party*.py` and the ledger tests were run.

## Real application check

`ui/Test/runMain sdfx.ui.RealPromptToolsCheck <python> <checkpoint> <output>` starts the real engine, opens the real
`MainWindow`, selects the Prompt Tools tab and drives its controls by id, generating with the prompts they build.
SD1.5 `animekawa_v10` 512x512 and SDXL `waiIllustriousSDXL_v170` 768x768, Euler, 20 steps, CFG 6, seed 4242. Receipts
`outputs/prompt-tools-real-sd15/native-report.json` and `outputs/prompt-tools-real-sdxl/native-report.json` (both with
11 checks passed):

1. Tag files appear in `__config__.yml` order (`scene`, then `broken (!)`); the broken file is flagged, not hidden.
2. A saved prompt is saved and used: the prompt field equals it.
3. Tag buttons: click inserts; a second click does not duplicate; a group button inserts a random member
   (`cold blue moonlight` in the final SD1.5 run, `golden hour light` in the final SDXL run; the pick is random); Ctrl-click sends to the negative prompt; right-click removes (positive field
   by default, negative field when the target box is ticked); search `moon` finds the button `cold`.
4. Generation A from the built prompt: the PNG's prompt and negative prompt are exactly the field texts.
5. History holds the pair; after clearing both fields **Restore** brings it back and the regenerated image is
   **pixel-identical** to A (SD1.5 md5 `5388b2` twice, SDXL `a2d382` twice). The same pair is not duplicated in history.
6. A history entry is bookmarked as a favourite.
7. Tag editor: three weight steps and Up on `detailed` give `(detailed:1.3), ...`; the image differs from A (SD1.5
   `9b43d6`, SDXL `c35e8e`).
8. Blacklist `natural light` removes the tag; `sharp focus, Sharp Focus` collapses to one.
9. A tag card `Quality\Extra` / `crisp` is saved to `tag-cards/Quality/Extra/crisp.tag` and inserted.
10. Final generation D matches the prompt field; history has 3 entries for 4 generations (A and its replay are one).

Images looked at (contact sheets `outputs/prompt-tools-real-*/j_sheet.jpg`, 4 images each): the SD1.5 final run gives a
blue-haired anime astronaut with headphones in front of a moon (the random pick was the moonlight); images 1 and 2 are the
same picture, image 3 (weighted `detailed`) keeps the moon and suit but changes the face, hair colour and adds a bubble
helmet, image 4 (`crisp edges, 8k`, no `natural light`) is a closer portrait with a clear helmet and black bob hair.
The SDXL final run gives a profile portrait of a woman in a space helmet against an orange sunset in all four; 1 and 2 are
the same, 3 and 4 change the helmet and framing. No broken or garbled images. Screenshots of each page
(`tag-buttons.png`, `history.png`, `tag-editor.png`, `tag-cards.png`, `saved-prompts.png`) were inspected: layout fits
the 1484x881 window, the group buttons are orange, the tag-file tabs show `scene` and `broken (!)`.

Earlier runs of the check: the first SD1.5 run failed at "history entry" because the History list was not refreshed
while the tab stayed open (fixed in the product, see above); the second pair of runs passed but took the History,
Tag editor and Cards screenshots of the txt2img tab because Generate switches tabs (fixed in the check, which now
re-selects the Prompt Tools tab before screenshots); the final runs above use the final code.

## Not verified

* Windows file-name rules were exercised only by the validation tests, on Windows (the real check wrote one card).
* Import of an `prompts.json` written by the real Multi-Prompt Manager was tested with a hand-written equal-format file,
  not a file from the extension itself.
* D2 and Easy Tag Insert example files from the upstream repositories were not loaded; the formats were taken from the
  READMEs.
* The file chooser dialogs (Folder…, Import…, Export…) and the confirmation dialogs were not driven (native dialogs).
* The packaged Windows build was not rebuilt for this change.
