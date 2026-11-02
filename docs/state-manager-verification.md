# State Manager history and category restore (#23, partial)

Upstream: SenshiSentou/sd-webui-state-manager, MIT, pinned `2af62ecd5287438bbc309f7e840d34aa5c8e3cb6`
(LICENSE SHA256 recorded in `scripts/third_party.py`). Independent implementation; no upstream code or assets copied.

## Added on top of v0.03 named states

- Automatic per-mode history of the full form (same snapshot as a named state) on every Generate/Queue, newest 50 per
  mode, identical-newest dedupe, on/off flag, 100 px thumbnail of the job's first image, delete / multi-select delete /
  Clear history / Keep as named state. Stored in `config/generation-history` (new collection ids `history-<mode>` in the
  preset JSON whitelist; separate store from `generation-states`).
- Category-selective restore (6 categories derived from field keys) for named states and history entries; the resource
  preflight only covers the selected fields.

## Verification

| Check | Result |
|---|---|
| `GenerationHistorySuite` (append/dedupe/cap+eviction, disk store record/list/delete/clear/mode isolation/thumbnail 300x150 -> 100x50, autosave flag, category restore changing only chosen categories) and the existing `NamedGenerationStatesSuite` | 9 + 9 pass |
| Real MainWindow + real engine, SD1.5 `animekawa_v10` 512x512, 8/9 steps (`ui/Test/runMain sdfx.ui.RealGenerationHistoryCheck`, receipts `outputs/history-real-sd15`) | passed |
| Same, SDXL `waiIllustriousSDXL_v170`, 832x832 (square; not 832x1216) | passed |

Each real run exercises: default-on recording and a 100x100 thumbnail (8067 / 9439 colours) after the first Generate;
a second entry shown newest first; regenerating an identical newest state adds no entry; prompts-only restore of the
older entry changes only the prompt (seed/steps/CFG unchanged); full restore reproduces `GenerationParams` exactly and
the regenerated image's pixels are identical to the first image; with recording off Generate adds no entry;
Keep as named state adds the entry to the named list; multi-select Delete removes two entries and their thumbnail files.
Images were viewed: SD1.5 and SDXL both show the red-kimono girl (entry A), the blue-armour knight at a castle (entry B,
identical on the repeat), and the History tab with thumbnail and prompt text in the screenshots.

## Findings and notes

- First full-restore attempt in the real run was blocked by the existing preflight: the img2img Hires Fix plugin's
  default upscaler "R-ESRGAN 4x+" is not in this machine's catalog. This is the long-standing named-state rule
  (`NamedGenerationStatesSuite` "without weakening live catalog checks" asserts it), so I did NOT relax it. The check
  instead provides a placeholder `R-ESRGAN 4x+.pth` in a temp upscaler dir so that name is in the catalog (Hires stays
  off; the file is never loaded). Users without that upscaler need a category-subset restore or to install it.
- The check script's own first run failed on a script bug (window used before assignment); fixed, no assertion changed.
- SDXL ran at 832x832 not 832x1216.

## NOT provided

Upstream IndexedDB / shared .txt storage import and format parity, the `/version` and other HTTP API endpoints, the docked
side-drawer / modal browser UI, ctrl/shift range multi-select driving bulk apply, per-single-setting apply (granularity
is the six categories), a favourites flag (use Keep as named state), MCP exposure of the history. Issue stays open (`Refs #23`).
