# Incantations verification (#63)

Source: v0xie/sd-webui-incantations `980d63838a2b8c8659a01b242e55551870619d6a` (GPL-3.0, LICENSE SHA-256 `230184f60bae2feaf244f10a8bac053c8ff33a183bcc365b4d8b876d2b7f4809`), all of `scripts/` and the README, read as a behaviour reference; NUI re-implements it (`python/sdfx/incantations.py`, `plugins/src/main/scala/sdfx/plugins/IncantationsPlugin.scala`). Ledger index 302, target `incantations`.

## Feature inventory and native mapping

The source's `incantation_base.py` runs these submodules in this order, then a CFG combiner (`cfg_combiner.py`):

| Source submodule | Controls (defaults) | NUI |
| --- | --- | --- |
| Smoothed Energy Guidance (`smoothed_energy_guidance.py`) | Active, SEG Blur Sigma 11 (0-11, step 0.5), Start Step 0, End Step 150 | New, in the `incantations` hook. NUI has no SEG. The native SAG plugin (diffusers SAG) is a different method and is not reused. |
| S-CFG (`scfg.py`) | Active, SCFG Scale 1.0, SCFG R 4 (1-16), Min Rate 0.8, Max Rate 3.0, Clamp Rate 0.0, Start Step 0, End Step 150 | New. |
| Perturbed Attention Guidance (`pag.py`) | Active, Use Saliency-Adaptive Noise Fusion, PAG Scale 0 (0-20, step 0.5), Start Step 0, End Step 150 | New. The native PAG plugin (diffusers PAG) is not reused: it perturbs every middle self-attention through `to_out`, has no step range and no SANF, while the source returns the value projection of the first middle transformer block only (no `to_out`). |
| CFG Scheduler (`pag.py`) | Enable CFG Scheduler, CFG Schedule Type Constant (17 types), CFG Noise Interval Low 0, High 100 (step 0.1) | New. The native CFG Schedule plugin (a start/end ramp over progress) has different schedules and no noise interval, so it is not reused. |
| Multi-Concept T2I-Zero (`t2i_zero.py`) | Active, Step Start 1, Step End 25, Tokens "", CbS Window Size 2, CbS Score Threshold 0.5, CbS Correction Strength 0.0, CTNMS Alpha 0.1, EMA Smoothing Factor 0.0; a hidden, unused Attention Regulation box | New. |
| Seek for Incantations (`incant.py`) | Active, Append Generated Caption, Deepbooru Interrogate, Delimiter BREAK, Word Replacement -, Gamma 0.2, hidden Coarse Step | Not provided (see Rejected). |
| Save attention maps (`save_attn_maps.py`) | only with the `INCANT_DEBUG` environment variable | Not provided (debug tool). |

The source has no tokenizer tool; T2I-0's token indices use the prompt's A1111 token count.

## Behaviour

- One AlwaysOn plugin `incantations` (hook `incantations`). Its header toggle enables the plugin; each section has the source's Active box, so nothing runs until a submodule is active. Hook fields are the source's `p.*` attribute names (`seg_blur_sigma`, `cfg_interval_low`, ...).
- Steps are A1111's `state.sampling_step`, which the source receives in its callbacks: it lags the model evaluation by one (0, 0, 1, ..., N-2), as the source's own comment notes. Evaluations are counted by timestep changes, so PAG's extra evaluation shares its step. Each pass counts from zero.
- SEG: on every middle-block self-attention (`attn1` of all middle transformer blocks: 1 for SD1.x, 10 for SDXL) the query is blurred on the image-aspect grid with a Gaussian of sigma `2**SEG Blur Sigma` (kernel `ceil(6s)+1` made odd, limited by the grid width, reflect padding); above 10.5 it is replaced by its spatial mean (the default 11). The source blurs the second half of the batch; in A1111's [cond, uncond] layout that is the unconditional half, so NUI blurs the unconditional rows and CFG guides away from the smoothed prediction. Blur Sigma 0 disables SEG and writes no infotext.
- S-CFG: during each in-range evaluation the head-averaged `to_q` (and, for cross-attention, `to_k`) outputs of the first image's conditional row are kept for every attention layer; self-attention uses `q` as key. Scores are `softmax(q k^T)` without scaling (global max subtracted, float32). Per resolution, smallest first and at most R of them: `(sa + sa^2 + ... + sa^R) / R`, applied to the cross maps, bilinear resize to latent/R, the source's 3x3 kernel (`exp(-((x-1)/(2*0.5))^2)`, normalised) with reflect padding, spatial normalisation; masks are the per-pixel arg-max token and "BOS is not the maximum" foreground, nearest-upscaled by R. The rate per pixel is the foreground mean norm over the token region's mean norm of the CFG difference, times SCFG Scale, clamped to [Min, Max], then to Clamp Rate / CFG when Clamp Rate > 0, smoothed by the same kernel.
- PAG: in range and with PAG Scale > 0, one extra evaluation of [cond, cond] with the first middle transformer block's `attn1` returning its `to_v` output (the source's identity hook, skipping `to_out`); the conditioning is the one from before T2I-0's correction, as the source clones it first. Guidance `PAG Scale * (cond - perturbed)` is added to CFG; with SANF each element takes CFG's or PAG's term, whichever has the larger channel softmax of its blurred magnitude (3x3, sigma 1).
- CFG Scheduler: the noise interval is snapped to the nearest steps of a rho=3 Karras ladder from 80 to 0.002 over the base pass steps; for a schedule other than Constant, CFG becomes the schedule value (base CFG as w0, base steps) inside the interval and 1.0 outside it. Constant keeps the base CFG everywhere and ignores the interval. Kept quirk: `Interval` compares the step index with 0.25..5.42 (steps 1 to 5).
- Combination: everything is combined in denoised (x0) space as the source's combiner: `uncond + rate * cfg * (cond - uncond)` then `+ pag`, or the SANF mix; NUI writes the equivalent conditional noise (or v) prediction so diffusers' `u + g (c - u)` returns it. Unchanged evaluations are left bit-identical (PAG Scale 0, Constant schedule, out-of-range steps, SCFG Scale 0).
- T2I-0 CbS: from Step Start to Step End, each positive conditioning row's tokens are blended with `Correction Strength` toward the similarity-thresholded (quantile `1 - e^(-10 t)`), max-normalised, windowed sum. Kept quirk: the Tokens field is shifted past BOS twice (once at setup, once in CbS); empty Tokens means tokens 1..76.
- T2I-0 CTNMS (when CTNMS Alpha > 0): on every cross-attention output, the value-projected scores of the selected tokens (Tokens shifted once, or tokens 1 to count-1 of the prompt) are blurred (3x3, sigma 1); each position takes the value row whose index is the winner's position in the selected list (kept quirk), times the output, blended by Alpha, optionally through the EMA (`factor / (1 + call)`). Its step counts module calls (PAG's evaluation included) from the base pass into the hires pass, with End Step 0 meaning no end; positions are laid out from the base image size, so hires layers whose size does not fit are left as they are, like the source.
- State per batch: base pass = the source's `process_batch`; each hires run continues a copy of its base pass state; other passes start anew. ADetailer passes skip the plugin (ADetailer's default script list does not include it).
- Infotext uses the source keys and values (`SEG Active`, `SCFG R`, `PAG SANF`, `CFG Interval Schedule`, `T2I-0 Tokens`, ...), written per submodule only when it runs. PNG Restore and MCP `inspect_generation_metadata` restore the plugin; other PNGs switch it off in the desktop form.
- X/Y/Z: the source's 29 axes (`[SEG] ...`, `[SCFG] ...`, `[PAG] ...`, `[T2I-0] ...`) with its activation rules: field axes switch their submodule on unless an Active axis already set it; `[PAG] CFG Schedule Type` also enables the scheduler; the noise interval axes do not. Unlike the source, axes apply only while the plugin's header toggle is on.

## Rejected or not provided

- Seek for Incantations: it doubles the batch count, interrogates each first image with A1111's BLIP caption model and OpenAI CLIP ViT-L/14 ranking (or DeepDanbooru) and rewrites the second image's prompt. NUI has neither A1111 interrogator nor DeepDanbooru (its WD14 tagger is a different model), and the source marks the method as semi-functional. Its controls are not shown; `INCANT ...` keys stay in MCP's `unapplied_fields`. The `[Incant] ...` axes are not provided.
- Errors: SEG, S-CFG, PAG or the scheduler without CFG (scale 1 or less: no unconditional prediction to work on); any submodule with the native PAG or SAG plugins, Reference-only (it runs its own model evaluation inside NUI's) or Neutral Prompt / Composable LoRA branches; PAG, S-CFG or the scheduler with APG, Mahiro, Latent Modifier, Dynamic Thresholding, Rescale CFG, CFG Schedule, CD Tuner, Detail Daemon, CADS, Semantic Guidance or MultiDiffusion; SEG or S-CFG with HyperTile, Token Downsampling, Agent Attention or MultiDiffusion; CbS with Semantic Guidance. Where the source crashes, NUI raises a clear error: CTNMS with fewer than two prompt tokens, the T2I-0 EMA meeting an evaluation of another size (hires), S-CFG with a latent width not divisible by R when the height is, SEG or S-CFG token grids that do not fit the image aspect.
- Unknown schedule names (the source's unreachable V-Shape / A-Shape) are rejected.

## Differences

- CbS computes in float32; the source's `torch.quantile` cannot run on half-precision conditioning.
- With prompts of different lengths A1111 evaluates cond and uncond separately, where the source's SEG halves split the wrong batch (and fail at batch 1); NUI always pads them together, so SEG always blurs the unconditional rows.
- The source's paste ticks SANF whenever `PAG SANF` is present (also for `False`); NUI restores the written value so replays are exact.
- Token counts follow NUI's prompt handling: `AND` is plain text in NUI, so it is not split into sub-prompts.
- For second-order samplers (Heun, DPM2) the lagged step can differ by one from A1111.
- With a separate hires CFG, the scheduler's w0 is still the base CFG (as the source's `p.cfg_scale`), plain CFG uses the hires CFG.
- The S-CFG statistics the source logs at debug level are not kept.

## Evidence (CPU, 2026-11-01)

- `python/tests/test_incantations.py` (134 tests): noise ladder, step alignment and all 17 schedules equal literal restatements of the source; the scheduled CFG equals the source's callback for every schedule and interval case; SEG equals the source's `seg_to_q_hook` (finite and infinite blur, square and both aspect grids) and leaves the conditional half untouched; the Gaussian helper matches torchvision; S-CFG masks equal a restatement of `get_mask` (R 1, 2, 4, 8, rectangular latents) and rates equal `scfg_combine_denoised` (scale, clamp, rescaled masks); the combiner equals `new_combine_denoised` for CFG, scheduled CFG, PAG, SANF, out-of-range PAG and S-CFG with PAG at batch 2; CbS equals `correction_by_similarities`; CTNMS equals the source hook over successive calls with and without EMA and with chosen tokens; token counts equal A1111 `tokenize_line` (BREAK, comma backtrack, long prompts). On a small UNet: the lagged steps, SEG changing only unconditional rows, PAG equal to an identity-attention reference in denoised space (with and without SANF), unchanged output for no-op settings, CFG off outside the interval, S-CFG maps from the first conditional row, CbS on positive rows only with PAG using the uncorrected conditioning, per-batch state with hires copies, rejections. A diffusers pipeline run with every submodule is deterministic, differs from the baseline, removes every hook, and each submodule alone changes the image.
- `IncantationsSuite` (6 tests): defaults, gating (SEG sigma 0), source keys per submodule and their order, PNG round trip of every control, source PNG restore by key presence, malformed replay, the 29 axes and their activation rules. MCP: `InspectGenerationMetadataSuite` restores `incantations` from the source keys, consumes them and leaves `INCANT Active` unapplied.
- Totals: Torch CPU 2,553 passed / 1 skipped; Scala `plugins/testFull` 212, `mcp/testFull` 91, `ui/testFull` 420, all passed (`sbt test` passes).

## Real-model checks (2026-11-02)

`scripts/dev/check_incantations_real.py` drives the official MCP Python SDK 2.3.0 against the real engine (receipts `outputs/inc-real-sd15/receipt.json`, `outputs/inc-real-sdxl/receipt.json`). SD1.5 `animekawa_v10` 512x512 and SDXL `waiIllustriousSDXL_v170` 832x1216, fixed seed. Both passed every assertion:

- Every case in `CASES` (SEG, S-CFG, PAG, PAG with SANF, CFG scheduler with PCS and noise interval, T2I-Zero CbS and CTNMS) changed the image.
- The identity cases (PAG scale 0, constant schedule, SEG sigma 0) reproduced the baseline pixel for pixel.
- PNG replay of the combined settings was pixel-exact. Hires runs completed and changed the image, and the T2I-Zero EMA failed in the hires pass like the source.
- Cancelling a running job ended as cancelled, and the next baseline reproduced exactly.

Images were inspected on contact sheets. The extreme PCS interval (low 0.28, high 5.42) washes the picture out heavily (SDXL almost blank), which is what those bounds do; moderate settings keep the subject and change style and detail. A first SDXL run stalled with "no progress for 10 minutes" under memory pressure; the rerun with a free GPU passed.

## Limits

- Not compared against a running A1111 with the extension.
- S-CFG is as memory hungry as in the source (the README notes batch 4 at 1024 px fills 24 GB).
