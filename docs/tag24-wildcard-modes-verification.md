# Wildcard Tab completion modes: partial #24 verification

The Tags dialog now offers the three path modes documented by upstream
`DominikDoom/a1111-sd-webui-tagcomplete` (MIT, README revision
`4170882f90b47be130a0ff9314f663c230b9153d`). The default preserves the existing first-difference
behaviour. Next-folder follows the selected candidate to its next slash; always-full chooses the
highlighted file immediately. Enter continues to choose a file, and partial completion does not
overwrite text after the caret. If a saved mode is unknown, the dialog identifies and preserves it;
completion falls back to first-difference until the user chooses a known mode. No upstream code or
data was copied.

- `outputs/codex-tag24-wildcard-modes-red.log` reproduces the previously absent next-folder mode.
  `outputs/codex-tag24-wildcard-modes-green.log` and `outputs/codex-tag24-wildcard-modes-extra.log`
  cover native JavaFX input, selected candidates, case, complete filenames, Enter, suffix protection,
  old settings and round-trip. Existing wildcard line selection remains covered.
- `outputs/codex-tag24-wildcard-unknown-{red,green}.log` checks that an unsupported stored value is
  shown and preserved, and changes only when a known choice is selected. The Japanese settings label
  and default mode were checked in a real MainWindow with fake CPU worker and visually inspected
  (`outputs/tag24-wildcard-modes-ja/settings-ja.png`).
- After rebasing onto `567354a`, 14 completion tests, three native JavaFX checks and `ui/assembly`
  passed (`outputs/codex-tag24-wildcard-rebase.log`). The JAR SHA-256 is
  `cb67de09744af7d84d3fd428d5cce8c9f004358cd6170ede079df67d0cbacadd`.
- Real MainWindow with SD1.5 `animekawa_v10` and SDXL `waiIllustriousSDXL_v170` passed 35 checks
  each, including saving next-folder mode, completing `__co` to `__colors/`, and generating a PNG
  (`outputs/codex-tag24-wildcard-modes-real.log`). The visually inspected SD1.5 image shows a
  blue-haired fox-eared character; the SDXL image shows a similar subject obscured by a strong
  black/red mesh pattern. These are 8-step checks, not general image-quality validation.
- Always-full and selected-branch next-folder were checked with native JavaFX controls and synthetic
  events, not a real-model MainWindow. The extracted Windows package and physical Japanese IME were
  not tested. Issue #24 has additional open scope; see `tag-autocomplete-acceptance-24.md`.
