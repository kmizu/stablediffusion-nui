# Tag category colour settings: partial #24 verification

The settings dialog accepts source-stem keyed dark/light colours, preserving the existing numeric
markers when a source is absent. A configured source leaves omitted categories uncoloured. Tags
and chants use the selected main CSV stem; extra tags use the extra CSV stem. This extra-source
distinction differs from upstream's shared selected CSV palette. Same-stem files share a palette.
The format follows the documented behaviour of the MIT-licensed upstream tag autocomplete; no
upstream code or tag data was copied.

- Focused CPU tests cover parsing, invalid input, source separation, dark/light values and settings
  compatibility. `outputs/codex-tag24-colormap-final.log` records 25 passing tests before the last
  wildcard error-handling changes; after rebasing onto `ffdb2e6`, 16 focused tests, the MainWindow
  check and `ui/assembly` passed (`outputs/codex-tag24-colormap-rebase{,2}.log`). The assembled
  JAR SHA-256 is `b2a7f2ba85d3666e9a5393c8fdf8b252785585b0c10b6ebbecbfb8a47e89037a`.
- Real MainWindow with a fake CPU worker covers Japanese settings, saving and disk reload,
  injected write failure and recovery hold, invalid stored JSON, distinct colours in both themes,
  and chant mapping. Japanese settings and dark/light popup screenshots in
  `outputs/tag24-colormap-ja/` were visually inspected.
- `outputs/codex-tag24-colormap-reload-red4.log` reproduces a synchronous reload failure after
  save. `outputs/codex-tag24-colormap-rebase2.log` verifies that save reports the committed
  state correctly, unrelated tag CSV loading continues, wildcard line selection and manual Reload
  report errors, and a new wildcard created during failure appears after correcting the path.
  These are injected invalid-path
  checks in the real window, not a general filesystem race guarantee. As before this change,
  failures in an already-started asynchronous wildcard scan are not reported to the user.
- Real MainWindow with SD1.5 `animekawa_v10` and SDXL `waiIllustriousSDXL_v170` passed 33
  checks each, including configured main-tag, chant and extra-tag popup colours, insertion and PNG
  generation (`outputs/codex-tag24-colormap-real.log`). Both generated images were inspected: SD1.5
  shows a blue-haired fox-eared character; SDXL shows a similar character obscured by a strong
  black/red mesh pattern. These 8-step checks do not establish general image quality.
- The settings file was reloaded from disk in the running test process; a full application process
  restart has not been exercised for this change. The extracted Windows package and physical Japanese
  IME have not been tested. The synthetic IME check passed. Issue #24 remains open; see
  `tag-autocomplete-acceptance-24.md`.
