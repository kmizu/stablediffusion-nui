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

## Files

- **Tag CSV**: `name,type,count,"alias1,alias2"`, optional count/aliases, UTF-8, quoted fields, or a header row
  (including WD CSVs with `tag_id,name,category,count`). Up to 32 MiB / 300,000 rows.
- **Translation CSV**: `english,translation` (or the old 3-column format with the second column skipped).
  A tag is found by the translation of its name or alias and the translation is shown in the row. The
  insertion is always the English tag.
- **Extra tag CSV**: same layout as the tag CSV, but column 3 is the grey meta text (default "Custom tag").
  Shown after (default) or before the normal tags.
- **Chants JSON**: `[{"name": ..., "terms": ..., "content": ..., "color": ...}]`. `name` and `terms` are searched,
  `content` is inserted as written (`color` is accepted but not drawn).
- **LoRA trigger words**: the A1111 `<model>.json` sidecar field `activation text`; inserted as
  `<lora:name:1.0>, words`. Turn off in the dialog.
- **Previews**: `<model>.preview.png`, `<model>.png`, `.jpg`, `.jpeg`, `.webp`, `.gif` next to the model file, shown in
  a second popup beside the list while a LoRA or embedding row is selected.

A failed file is reported and the other sources keep working; no replacement is downloaded.

## Options

Maximum results (1-50), replace `_` with a space, escape parentheses, append `, ` after a completion (not after
`<lora:...>` tags), search by alias, show only the alias (otherwise `alias ➝ tag`), switches for wildcards,
extra networks, chants, trigger words and previews.

## Scope

Provided: everything above plus the v0.03 behaviour (positive and negative prompt, keyboard and mouse insertion,
IME composition guards). Not provided: hypernetworks (`<h:` offers nothing, the app has no hypernetwork loader),
embedding v1/v2 filter, the model-keyword trigger lists, choosing a line inside a wildcard file and Tab partial path
completion, Umi AI wildcards, live translation preview, wiki links, completion delay, remappable hotkeys,
category colours, per-model black/whitelist, built-in danbooru/e621 lists. A physical Japanese IME was not
exercised (synthetic composition events only). See [tag-autocomplete-verification.md](tag-autocomplete-verification.md).
