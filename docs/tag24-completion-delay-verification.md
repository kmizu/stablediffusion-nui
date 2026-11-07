# Completion delay: partial #24 verification

The Tags dialog now offers an optional 0–5000 ms delay before querying after the last text,
caret or selection change. Zero preserves the previous immediate behaviour. Pending queries hide
old rows and are cancelled by Escape, focus loss, detaching the scene or IME composition. The
documented upstream setting also describes a millisecond debounce; the upper bound and zero default
are native choices. No upstream code was copied. The behaviour reference is the MIT upstream README
revision `4170882f90b47be130a0ff9314f663c230b9153d`.

- `outputs/codex-tag24-delay-red.log` reproduces immediate searches instead of waiting; the green
  focused checks (`outputs/codex-tag24-delay-green.log` and `outputs/codex-tag24-delay-focus-ime.log`)
  exercise rapid edits, latest-only lookup, Escape, focus loss, synthetic IME composition, old
  settings and the existing completion modes. They do not measure physical IME behaviour.
- Final focused tests and `ui/assembly` passed (`outputs/codex-tag24-delay-final.log`); JAR SHA-256
  `cde99796581ea527f9ecef8636e199bc301ac5feaa525ff26c8f8bb3d3d7189d`.
- A real MainWindow with fake CPU worker revealed that the scene-level Escape filter interrupted
  generation before the pending completion could cancel (`outputs/codex-tag24-delay-escape-red.log`).
  The fix and a running generation test cover positive and negative prompts, moving between them,
  dismissing both pending completions and generation finishing
  (`outputs/codex-tag24-delay-negative-green2.log`).
- Real MainWindow with SD1.5 `animekawa_v10` and SDXL `waiIllustriousSDXL_v170` passed 38 checks
  each: the Tags dialog saves 250 ms, results do not appear immediately and do appear after waiting.
  The harness then switches delay to zero for its existing completion and generation checks
  (`outputs/codex-tag24-delay-real.log`). The SD1.5 result shows a blue-haired fox-eared person;
  the SDXL result has the same subject obscured by a strong black/red mesh pattern. These are
  8-step checks, not a general quality assessment.
- The Japanese settings screenshot was inspected (`outputs/tag24-delay-ja/settings-ja.png`). The
  extracted Windows package, full application restart, physical Japanese IME and generation while
  a nonzero delay remains active have not been verified. Issue #24 remains open; see
  `tag-autocomplete-acceptance-24.md`.
