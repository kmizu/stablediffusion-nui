# Tag category markers: partial #24 verification

This is the historical verification of the fixed-marker implementation. Subsequent configurable
per-source colours and their separate verification are described in `tag24-colormap-verification.md`.

Numeric `type` / `category` from tag and extra-tag CSVs and numeric chant `color` are retained in
suggestions. Categories 0, 1, 3, 4 and 5 use fixed coloured markers; other numbers remain visible
without a marker. These colours identify numbers only: their meanings depend on the source data.
Missing or nonnumeric categories remain unmarked. If both CSV columns exist, a numeric `category`
takes precedence and a numeric `type` is used if `category` is empty or invalid. Inserted prompt
text and ranking remain unchanged; category numbers are also supplied in the accessible cell text.
The upstream README documents per-file configurable colours; these are not implemented here.
Upstream `DominikDoom/a1111-sd-webui-tagcomplete` is MIT, README revision
`4170882f90b47be130a0ff9314f663c230b9153d`; no code or tag data was copied.

- CPU: focused completion, CSV and wildcard tests passed; category/type fallback was independently
  reproduced failing and then passed (`outputs/codex-tag24-category-fallback-{red,green}.log`).
  Dark and light real JavaFX popup checks cover known and unknown categories, chant markers,
  selected text and accessible category labels (`outputs/codex-tag24-category-ready.log`).
  Popup images were inspected (`outputs/tag24-category-verified/`).
- Real MainWindow with real SD1.5 `animekawa_v10` and SDXL `waiIllustriousSDXL_v170`: 30 checks
  each passed, including a translated tag with category 4, chant category 1, insertion and a
  generated PNG with wildcard-expanded prompt (`outputs/codex-tag24-category-real.log`).
  The inspected SD1.5 image is a blue-haired fox-eared person; the SDXL image has the same subject
  with a strong black/red mesh-like pattern. These are 8-step checks of the exercised path, not
  general image-quality validation.
- The extracted Windows package GUI and physical Japanese IME were not tested for this change.
  Per-file/custom colours, other remaining upstream functions and issue #24 remain open; see
  `tag-autocomplete-acceptance-24.md`.
