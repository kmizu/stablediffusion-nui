# Agent Attention verification (#4)

Source: v0xie/sd-webui-agentattention `8c2fd24ed5203e68450100064a49fa7873eea0bb` (GPL-3.0), read as a behaviour
reference; NUI re-implements it without copying code. Its `agentsd` package says it comes from
LeapLabTHU/Agent-Attention (`207ce3eb357b22ef8a15f8fa21cd1f14697bbaba`, no repository licence; only its detection
subfolder has one), which builds on dbolya/tomesd (MIT, `27a14a372beecaa85c101fb588631643f379b8ce`). Method:
arXiv:2312.08874.

## Behaviour

- AlwaysOn plugin and hook `agent_attention` (`python/sdfx/agent_attention.py`, `AgentAttentionPlugin`) with the source
  controls and defaults: Active, Apply to Hires. Fix Only, Use FP32 Precision, Use Second Pass, Second Pass Step 20,
  Max Downsample 1 (choices 1/2/4/8), first pass sx 4, sy 4, Ratio 0.4, Agent Ratio 0.95, second pass sx 2, sy 2,
  Ratio 0.4, Agent Ratio 0.5. The source's fixed `k_scale2` 0.3, `k_shortcut` 0.075, random destinations,
  self-attention-only merging (no cross-attention or MLP merging) are kept.
- Each UNet transformer block whose token grid is downsampled at most Max Downsample times from the latent
  (`ceil(sqrt(latent tokens // block tokens))`, as in the source) is patched. One destination per full sy x sx window
  (random when the block batch is even, else top-left), sources matched to the most cosine-similar destination
  measured on the block input before norm1; `int(N * ratio)` sources are averaged into their destinations for the
  self-attention input and `int(N * agent_ratio)` the same way for the agent tokens (both capped at the source
  count). When agents * 2 < merged tokens the attention is `softmax(Q A^T s^0.3) softmax(A K^T s) V + 0.075 V`
  (A = to_q(agents), s = head_dim^-0.5); otherwise the layer's own processor runs on the merged tokens. Merged sources
  take their destination's output. Cross-attention, feed-forward and unpatched layers keep NUI's processors.
- Schedule as the source's CFG-denoiser callback: the first-pass settings are patched at sampling step 0; at Second
  Pass Step the patch is removed and re-applied with the second-pass settings only when Use Second Pass is on. So
  without Use Second Pass the effect stops at Second Pass Step (20 by default); Second Pass Step 0 means the second
  pass (or nothing) from the start. Steps count changes of timestep; each pass counts from zero.
- Hires: like the source's `before_hr`, the patch applies in the first pass only, or with Apply to Hires. Fix Only in
  the hires pass only (a warning is shown when there is no hires pass). ADetailer inpainting passes never run it (the
  source is not in ADetailer's default script list).
- Use FP32 Precision computes the two agent-attention logit products and softmaxes in float32 (the source disables
  autocast there); without it the products stay in the model dtype and softmax runs in float32, as under A1111's
  autocast.
- SD1.5 and SD2 patch the full-resolution blocks at Max Downsample 1. SDXL has no transformer blocks at downsample 1,
  so Max Downsample 1 changes nothing there (as in the source, whose README asks for values > 1); NUI warns.
- Infotext uses the source keys (`AgAt Active`, `AgAt Use Second Pass`, ..., `AgAt Apply to Hires. Fix Only`) and value
  spellings (`True`/`False`, integers without decimals). As in the source, `AgAt Active` being present enables the
  plugin on PNG restore; missing keys take the defaults. A1111's infotext key pattern (shared by NUI) excludes ".",
  so `AgAt Apply to Hires. Fix Only` parses as `Fix Only`; the source therefore never restores that checkbox, while
  NUI reads either spelling. PNG Restore and MCP `inspect_generation_metadata` restore the plugin; other PNGs switch
  it off in the desktop form.
- XYZ axes use the source names (`[AgentAttention] Active`, `... Use Second Pass`, `... Second Pass Step`,
  `... First Pass sx/sy/Ratio/Agent Ratio`, `... Second Pass sx/sy/Ratio/Agent Ratio`, `... Use FP32`,
  `... Max Downsample`). Boolean axes compare the lowercased value with `true`, like the source. The Active axis can
  switch the plugin off; unlike the source, axes apply only while the plugin is enabled (the source's numeric axes
  also turn it on).

## Differences and rejections

- The random destinations come from a generator seeded with the pass's hook seed and reset at every patch (step 0 and
  Second Pass Step). The source forks the device's global RNG at those points, so its images cannot be reproduced
  pixel-exactly; NUI's are reproducible from PNG.
- Tokens are normalized in float32 and their similarity product runs in the model dtype, as A1111's autocast does
  with the source; ties are broken by token position (stable sort), where the source's sort is unstable. Unpatched attention keeps NUI's processor instead of the source's plain
  einsum attention (same maths, different fp16 rounding).
- The source's "Remove Patch" button is not needed: NUI removes the patch after every pass, also on error or
  interruption.
- Rejected with an explicit error (the source crashes or the combination cannot be faithful): sx or sy 0 and Ratio 0
  for an applied pass; a layer where Ratio merges no token or the stride exceeds the token grid; token counts that do
  not map to the latent grid (for example some Kohya HRFix scales); attention masks or contexts on self-attention;
  non-standard transformer blocks or processors; combinations with PAG, SAG, HyperTile, Token Downsampling (factor
  above 1), Reference-only and ControlNet-LLLite; unknown hook settings.

## Evidence (2026-11-01, CPU only)

- `python/tests/test_agent_attention.py` (30 tests): the processor output on a real diffusers `Attention` equals an
  independent token-by-token restatement of the source maths (window destinations, cosine matching, mean merge, agent
  and plain paths, odd batch without randomness, ratio 1 capping); FP32 precision keeps the maths and reduces bfloat16
  error; destination windows on odd grids; unmerge mapping; downsample levels on odd latents; schedule and pass
  gating; setting validation; on a tiny `UNet2DConditionModel`: the patch changes the output, is removed at Second Pass
  Step (output equals the baseline), re-patches with a fresh generator, Max Downsample selects 3 or 7 blocks,
  processors are restored after success and after in-forward errors; unsupported processors; engine install gating,
  warnings and rejected combinations; registration in `KNOWN_HOOKS`, `EXTENSION_HOOKS` and the dry-run features.
- Scala: `AgentAttentionSuite` (defaults, source infotext keys and spellings, PNG round trip, upstream PNG text with
  missing keys, malformed values, XYZ axis names and the Active axis) and an MCP `InspectGenerationMetadataSuite` case.

## Real-model checks (2026-11-01)

`scripts/dev/check_agent_attention_real.py` drives the official MCP Python SDK 2.3.0 against the real engine
(receipts `outputs/agat-real-sd15/receipt.json`, `outputs/agat-real-sdxl/receipt.json`), model offload.

- SD1.5 `animekawa_v10` 512 px, 24 steps: defaults, second pass at step 10, FP32 and Max Downsample 2 each changed
  the image; PNG replay through `inspect_generation_metadata` reproduced the default image exactly and left no `AgAt`
  field unapplied; with Hires 1.5x, defaults (base pass only) and Apply to Hires. Fix Only both differed from the hires
  baseline and from each other; cancelling an 80-step run mid-way and generating again reproduced the plain baseline
  exactly, so attention processors were restored.
- SDXL `waiIllustriousSDXL_v170` 1024 px: Max Downsample 2 and 4 with a second pass changed the image, Max Downsample
  1 reproduced the baseline exactly (with the warning), replay was exact, hires and cancel/recovery as above.
- Images inspected: every variant keeps a coherent picture; Max Downsample 2 on SD1.5 changes composition and colour
  the most.
- The first real run found that PNG replay was not exact: CUDA `scatter_reduce` adds are non-deterministic and the
  difference grew over the steps (mean 4.3/255). Merging now uses a one-hot matrix product with the same mean, and
  replay became pixel-exact.
- One hires-only run stalled while another process held about 20 GB of the 24 GB GPU; the same job completed in
  seconds when run again, and with blocking CUDA launches. Low free VRAM can make the hires pass extremely slow on
  Windows (system-memory fallback); it is not specific to this plugin.
