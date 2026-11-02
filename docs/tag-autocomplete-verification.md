# Tag autocomplete extras: verification (#24)

Scope and usage: [tag-autocomplete.md](tag-autocomplete.md). Upstream: DominikDoom/a1111-sd-webui-tagcomplete, MIT,
README read at the pinned commit `4170882f90b47be130a0ff9314f663c230b9153d` (licence text already in `licenses/`).
No upstream code, tag lists or translation files were used. No Python code and no non-.py payload file changed
(nothing to add to the `scripts/package_windows.ps1` allowlist).

## Automated

- `CompletionEngineSuite` (13 tests, new) and `TagCompletionSuite` (existing): wildcard triggers (nested names, closing `__`
  replaced, finished wildcard does not reopen, `foo__ha` is not a trigger), the `<` network triggers (mixed list, `<e:`, `<l:`,
  `<lora:`, `<lyco:`, `<c:`, `<h:`, unknown head, caret in the weight or in the head gives nothing), weight kept when a LoRA tag
  is replaced, trigger words from `<model>.json`, preview lookup, tag options (underscores, parentheses, comma, max results,
  alias display, alias search off), translations (2-column, legacy 3-column, search by translation, invalid files),
  extra file (meta text, before/after order), chants JSON validation, an old `settings.json` without `tag_complete` loading with
  defaults and the new settings round-tripping.
- Full `sbt test` on this branch: all suites passed (per module 96, 298, 223 with 1 skipped, 263, 117 tests; the ui suite is the
  263). `ui/assembly` built.

## Real application (real MainWindow, real engine, no mocks)

`RealTagAutocompleteExtrasCheck` (ui test sources) starts the engine, opens the real **Tags...** dialog, types paths into its
fields and presses **Save**, then drives the real prompt editors with key events, and finally presses the ordinary Generate.
Fixtures written by the check: tag CSV (3 tags with an alias), translation CSV (Japanese), extra tag CSV, chants JSON,
`wildcards/` (animals, hair, colors/warm) and, for SDXL, a copy of the real LoRA `alraune_v1_illustrious_b-000007.safetensors`
with a `.json` sidecar (`activation text: "sidecar trigger"`) and a generated 96x64 `.preview.png` (left half red, right half blue).
Dynamic Prompts is selected for generation so the inserted `__animals__` is resolved by the real plugin.

| Run | Checkpoint | Checks | Result |
| --- | --- | --- | --- |
| SD1.5 | animekawa_v10, 512x512, 8 steps, seed 4242 | 23 | passed |
| SDXL | waiIllustriousSDXL_v170, 832x1216, 8 steps, seed 4242, with the real LoRA | 31 | passed |

Checks include: the dialog exposes every new option; Save persists the paths; `mast` offers the extra-file tag first and inserts
it; typing Japanese `青い髪` offers `blue_hair` and inserts `blue hair`; `<c:high` inserts the chant content; `__ani` inserts
`__animals__`; `<l:alrau` lists the real LoRA, the second popup shows the preview picture (decoded; red left, blue right
pixels verified) and accepting inserts `<lora:alraune_v1_illustrious_b-000007:1.0>, sidecar trigger`; in the negative prompt the
alias `azure` shows as `azure_hair ➝ blue_hair` and inserts `blue hair`, `__hai` + Tab inserts `__hair__`, `<h:` offers nothing;
the generated PNG's prompt contains the completed text with the wildcard resolved (`a red fox`, no `__animals__`); on SDXL the PNG
prompt carries the LoRA tag and trigger words and the same prompt generated without the LoRA tag differs from it by a mean of
86.3/255 per channel (the LoRA is applied through the completed tag).

Images and screenshots were looked at. SD1.5: a blue-haired girl with fox ears and tail (blue hair and the fox wildcard are visible;
8 steps, so noisy). SDXL: with the LoRA a blue-haired fox-eared character on a red motorbike in a hatched style; without it a different
picture (blue hair, fox ears). Dialog screenshot: all labels readable.

Problems found and fixed while verifying: the check boxes treated `_` as a mnemonic marker, so the label showed `Complete _wildcards_`
(fixed with mnemonic parsing off); the first preview screenshot was black because the picture loads in the background, so the check
now waits for the decoded image and verifies its pixels. One assertion of the check was changed: the first version expected a
`Lora hashes` field in the PNG parameters, but this app does not write one (the tag stays in the prompt); the assertion now checks
the tag and the trigger words, and the pixel-difference check was not loosened.

## Not verified / not provided

- Embedding completion (`<e:`) and the `<lyco:` form: unit tests only; no embedding file was loaded in a real run.
- A physical Japanese IME (synthetic composition events only, as in v0.03). Japanese translation search was typed as committed text.
- Not provided: hypernetworks, embedding v1/v2 filter, model-keyword trigger lists, choosing a line inside a wildcard file, Tab partial
  path completion, Umi AI, live translation preview, wiki links, completion delay, remappable hotkeys, category colours, per-model
  black/whitelist, built-in danbooru/e621 lists, chant colours. The sidecar `.json` trigger words are the only trigger source.
- Large tag lists (hundreds of thousands of rows) were not timed.
