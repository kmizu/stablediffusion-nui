# Aesthetic weight guidance verification

The Aesthetic Embeddings tab and the Aesthetic Gradients controls now advise starting SDXL
with weight 0.3–0.5 and adjusting to the result. This is guidance, not a guarantee: earlier
real SDXL observations, including a poor result at the 0.9 default, are recorded in
`aesthetic-gradients-verification.md`. The default is unchanged; no existing user value is
overwritten. The generation-side note is displayed when the Aesthetic Gradients section is open.

- `AestheticGradientsSuite`: 7 passed, including default and explicit weights, PNG replay,
  and the note's lack of an input value (`outputs/codex-aesthetic-guidance-green.log`).
- `AlwaysOnFilePolicySuite`: 7 passed, including MCP input schema and explicit weight checks
  (`outputs/codex-aesthetic-guidance-gui3.log`). MCP control discovery also reports a note;
  it is not an input field.
- Real MainWindow with the fake CPU worker: both guidance labels and English/Japanese language
  switching checked, `Use in generation` preserves an explicitly set weight of 0.37, and the
  existing create/cancel/recovery/replay/protected-session checks passed
  (`outputs/codex-aesthetic-guidance-gui7.log`). Japanese creation and generation screenshots
  were inspected (`outputs/aesthetic-guidance-gui7/aesthetic-guidance-creation-ja.png`,
  `outputs/aesthetic-guidance-gui7/aesthetic-guidance-plugin-ja.png`).

This change adds guidance only. No new GPU image-generation or extracted-package GUI test was
run for it; the numerical advice comes from the previously recorded real-model runs, not a new
benchmark or a guarantee for every model and reference image.
