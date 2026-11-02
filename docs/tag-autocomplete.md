# Native tag autocomplete (#24)

Stable Diffusion NUI suggests tags, wildcards, extra networks and chants in both prompt editors.
Everything comes from local files or the model lists of the app; nothing is downloaded and no
tag data ships with the app. Behaviour reference: DominikDoom/a1111-sd-webui-tagcomplete README at
`4170882f90b47be130a0ff9314f663c230b9153d` (MIT). Independent native implementation; no upstream code,
tag lists or translation files were copied.
https://github.com/DominikDoom/a1111-sd-webui-tagcomplete/tree/4170882f90b47be130a0ff9314f663c230b9153d

## Use

Click **Tags…** beside the prompt. The dialog has four file rows (type a path or **Browse…**; an empty path
turns that source off) and the options. **Save** applies them, **Reload** rereads the files and the wildcard
folders, **Disable** clears the tag CSV path only (other sources stay as configured).

Up/Down select, plain Enter or Tab inserts, a click inserts, Escape closes. Ctrl+Enter stays the generation
shortcut when no popup or IME composition is active. Only the active token is replaced, so weights and the
text around it stay.

| You type | You get |
| --- | --- |
| `blue h` | tags (and aliases / translations) from the tag CSV, ranked by count |
| `__` / `__col` | wildcard files (nested folders as `colors/warm`); inserts `__colors/warm__` and replaces an existing closing `__` |
| `<` | LoRA, embeddings and chants mixed |
| `<l:`, `<lora:`, `<lyco:` | LoRA only (`<lyco:` inserts `<lyco:name:1.0>`); an existing `:weight` is kept |
| `<e:` | embeddings; inserts the plain name |
| `<c:`, `<chant:` | chants; inserts the content |

Wildcard folders: `wildcards/` and `models/wildcards/` of the app home, plus the Dynamic Prompts plugin's
"Extra wildcard folders". LoRA and embedding names come from the model lists.

The **Completion delay (ms)** setting waits after the last edit or caret move before querying
suggestions. Its default is 0 (immediate); the supported range is 0–5000 ms. A delayed query hides
outdated rows and is cancelled by Escape, focus loss or IME composition.

Wildcard Tab completion has three modes in the Tags settings. The default, **To first difference**,
extends the shared prefix and an arrow-key selection followed by Tab chooses that file. **To next
folder** follows the highlighted candidate to its next `/`; **Always fully** chooses the highlighted
file immediately. Enter always chooses the highlighted file. Partial completion leaves text to
the right of the caret untouched.
After a file is chosen, its lines appear as a second list. Choose a line to insert its text, or press Escape
to keep the `__name__` reference. When there are more lines than the configured maximum, use PageUp/PageDown
or the Previous/Next buttons to change pages. Files are limited to 1 MiB and the first 5,000 non-comment,
non-empty lines; the popup warns when later lines are omitted.

## Files

- **Tag CSV**: `name,type,count,"alias1,alias2"`, optional count/aliases, UTF-8, quoted fields, or a header row
  (including WD CSVs with `tag_id,name,category,count`). Up to 32 MiB / 300,000 rows.
- **Translation CSV**: `english,translation` (or the old 3-column format with the second column skipped).
  A tag is found by the translation of its name or alias and the translation is shown in the row. The
  insertion is always the English tag.
- **Extra tag CSV**: same layout as the tag CSV, but column 3 is the grey meta text (default "Custom tag").
  Shown after (default) or before the normal tags.
- **Chants JSON**: `[{"name": ..., "terms": ..., "content": ..., "color": ...}]`. `name` and `terms` are searched,
  `content` is inserted as written; numeric `color` is shown as a category marker.
- **LoRA trigger words**: the A1111 `<model>.json` sidecar field `activation text`; inserted as
  `<lora:name:1.0>, words`. Turn off in the dialog.
- **Previews**: `<model>.preview.png`, `<model>.png`, `.jpg`, `.jpeg`, `.webp`, `.gif` next to the model file, shown in
  a second popup beside the list while a LoRA or embedding row is selected.

A failed file is reported and the other sources keep working; no replacement is downloaded.

## Options

Maximum results (1-50), replace `_` with a space, escape parentheses, append `, ` after a completion (not after
`<lora:...>` tags), search by alias, show only the alias (otherwise `alias ➝ tag`), switches for wildcards,
extra networks, chants, trigger words and previews.

## Category markers

The numeric `type` / `category` field in tag and extra-tag CSV files and the `color` field of a
chant are shown as category numbers. Categories 0, 1, 3, 4 and 5 also have coloured markers;
other numbers remain visible without a marker. These colours identify numbers only: category
meanings vary by data source. A missing or non-numeric category has no marker. The upstream
extension's source-keyed JSON colour maps can be entered in **Tags…** as, for example,
`{"danbooru":{"4":["lightgreen","darkgreen"]}}`. The source key is the CSV file name
without its extension; the pair is the dark-theme and light-theme colour. CSS colour names and
hex values are accepted. Main tags and chants use the main CSV's map; extra tags use the extra
CSV's map. Two files with the same stem share a map. If a source has a map but a category is
absent, the category has no coloured marker. Sources without a map keep the fixed defaults.
For example, `{"danbooru":{"4":["lightgreen","darkgreen"]}}` leaves category 0 uncoloured
for `danbooru`, while an unlisted source keeps the defaults. Invalid JSON or colour names are
rejected without replacing the saved settings. An invalid map introduced by editing the settings
file is reported and the default markers are used; the invalid value is not rewritten.

## Scope

Provided: everything above plus the v0.03 behaviour (positive and negative prompt, keyboard and mouse insertion,
IME composition guards). Not provided: hypernetworks (`<h:` offers nothing, the app has no hypernetwork loader),
embedding v1/v2 filter, the model-keyword trigger lists, the upstream next-folder and always-full Tab modes,
Umi AI wildcards, live translation preview, wiki links, remappable hotkeys,
per-model black/whitelist, built-in danbooru/e621 lists. A physical Japanese IME was not
exercised (synthetic composition events only). See [tag-autocomplete-verification.md](tag-autocomplete-verification.md).
