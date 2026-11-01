# Characteristic Guidance verification (#11)

Source: scraed/CharacteristicGuidanceWebUI `e2f9fdca460beed1366647e2eea8276a63f98484` (GPL-3.0, LICENSE SHA-256 `8b1ba204bb69a0ade2bfcf65ef294a920f6bb361b317dba43c7ef29d96332b9b`), `scripts/CHGextension.py`, `scripts/CharaIte.py`, `scripts/webui_CHG.py` and README, read as a behaviour reference; NUI re-implements the A1111 path (`python/sdfx/characteristic_guidance.py`, `plugins/src/main/scala/sdfx/plugins/CharacteristicGuidancePlugin.scala`).

## Behaviour

- AlwaysOn plugin `characteristic_guidance` with the source controls, labels, ranges and defaults: Regularization Strength 1 (0..10), Regularization Range Over Time 1 (0.01..10), Max Num. Characteristic Iteration 50 (1..50), Num. Basis for Correction 0 (0..10), CHG Start Step 0 (0..0.25), CHG End Step 1 (0.25..1); Advanced: Reuse Correction 1, Log 10 Tolerance -4 (-6..-2), Iteration Step Size 1, Regularization Annealing Speed 0.4, Regularization Annealing Strength 0.5, AA Iteration Memory Size 2; ControlNet Compatible Mode `More ControlNet`. Off until Enable is ticked.
- The correction (CHGDenoiser in the source). For CFG scale `s` the source looks for a latent correction `dx` with the conditional rows evaluated at `x + (s-1) dx` and the unconditional rows at `x + s dx`, such that `dx` equals the ridge projection of `g(dx) = (eps_u - eps_c) * sqrt(1 - abar) / c_in` (k-diffusion samplers; `x` in k-diffusion units, model input `c_in (x + shift)`) or `(eps_u - eps_c) * sqrt(1 - abar)` (A1111's timesteps samplers DDIM/PLMS/UniPC: z space, no `c_in`). V-prediction models use `sqrt(c_skip) * v` (k-diffusion; the source's common `-c_out x` term cancels in the difference) or the timesteps V denoiser's `sqrt(abar) v + sqrt(1 - abar) z` at the shifted z. NUI picks the timesteps path for its DDIM and UniPC samplers.
- The basis is `-g(0)`, split per channel into `n` quantile bands (`torch.quantile`, half-open bands, last closed) and normalised per channel and band, or, for Num. Basis 0, one direction per image (`n = 1`, normalised over the whole latent). The projection is `A (A^T A + reg I)^-1 A^T dx` with the inverse from `eigh` (eigenvalues floored at 1e-4).
- The fixed point is solved by the source's Anderson acceleration over the correction plus one extra coordinate holding `sqrt(reg * AStrength)`, with residual weight ASpeed towards the annealing target `RegS * (abar_999 / abar_t) ** (1 / RegR)`, starting from `max(5, RegS)`; memory AADim + 1 entries for Num. Basis >= 1 and 2 for Num. Basis 0, columns scaled by their norm (floored at 1e-4), least squares with the SVD pseudo-inverse fallback when it returns NaN, update with Iteration Step Size. Strength and Range above 5 are stretched like the source (`exp(0.8898 (v - 5)) / 0.8898 + 5 - 1 / 0.8898`); the tolerance is `10 ** Tol` on the per-image RMS of the residual including the regularisation coordinate.
- Each image keeps its best iterate while it has not converged; images that never converge in the step use plain CFG (`dx = 0`), and the loop stops when every image's best residual is below the tolerance. The step's correction, scaled by `sigma_t^2 / sigma_prev^2` (from abar) and Reuse, warm-starts the next CHG step.
- The model output of the step is the model evaluated at the shifted inputs with the sampler's timestep; in the CFG combination the skip terms of `x + shift` cancel, so the scheduler receives exactly the source's combined prediction for eps and v models on both paths. As in the source, the iterations evaluate at the quantised timestep of the sigma (k-diffusion `sigma_to_t(quantize=True)`; abar and `scale` use it, `c_in`/`c_skip` the exact sigma).
- Steps are counted per model evaluation of a pass (the source's `CFGDenoiser.step`); CHG runs for `round(steps * start) <= step < round(steps * end)` with Python rounding and the first-pass step count, also in the hires pass, which starts with a fresh warm start (A1111 creates a new sampler for it). img2img counts the executed steps from 0 against the requested steps, as A1111.
- Implementation: a UNet forward pre-hook registered last in `extensions.install`, so prompt-editing hooks before it (Semantic Guidance, CADS, CD Tuner, ...) have prepared the inputs, as the source's cfg_denoiser callbacks run before its forward. The iterations call the UNet's own `forward` (attention processors, LoRA, FreeU, IP-Adapter, NegPiP inside it apply; module hooks do not run again), then the evaluation itself proceeds on the shifted input. 9-channel inpainting models shift only the 4 latent channels. The source's `torch.linalg.lstsq` is solved by QR without pivoting (`linalg.qr` + `solve_triangular`), which is what lstsq's `gels` driver does on CUDA, where A1111 runs it; CPU lstsq (`gelsy`) is not bit-repeatable. With that, and no scatter/index_add, every run repeats bit for bit, so PNG replay is exact.
- ControlNet Compatible Mode: the source zeroes ControlNet unit weights during the iterations for `More Prompt`. NUI rejects ControlNet (below), so the mode only matters for IP-Adapter: `More ControlNet` keeps the adapter on in the iterations, which is what the source does with an IP-Adapter ControlNet unit; `More Prompt` with IP-Adapter is rejected.
- Infotext uses the source key and text: `CHG: "{'RegS': 1, 'RegR': 1, 'MaxI': 50, 'NBasis': 0, 'Reuse': 1, 'Tol': -4, 'IteSS': 1, 'ASpeed': 0.4, 'AStrength': 0.5, 'AADim': 2, 'CMode': 'More ControlNet', 'StartStep': 0, 'StopStep': 1}"` (`json.dumps` with double and single quotes swapped, numbers as the UI sends them). PNG Restore and MCP `inspect_generation_metadata` restore the plugin (Enable from the key's presence, missing keys such as Start/End Step of older PNGs take the defaults, Python spellings like `1.0` are read); other PNGs switch it off in the desktop form; malformed values are left unapplied.
- X/Y/Z: the source registers no axes; none are added.
- ADetailer passes do not run it (ADetailer's default script filter excludes the extension). CFG scale exactly 1 is left unchanged, which equals the source's result (its conditional rows are then evaluated unshifted).
- The convergence log replaces the source's "Check Convergence" plot: at the end of each pass the job log says how many image steps converged, and the engine log lists per step the best residual, iteration count and regularisation target (the source's `ite_infos`).

## Rejected or not provided

- Errors (they change the model evaluation or the CFG combination the correction is computed for, or keep per-evaluation state NUI cannot reproduce inside the iterations): Dynamic Thresholding, APG, Mahiro, Rescale CFG, Latent Modifier, CFG Schedule, Detail Daemon, Reference, ControlNet (diffusers computes the ControlNet residuals once per step at the unshifted input; the source re-evaluates ControlNet at every shifted input), ControlNet-LLLite, Fooocus Inpaint, MultiDiffusion, Kohya HRFix, HyperTile, Token Downsampling, img2img alternative test, img2img Hires Fix, PAG, Self Attention Guidance, Neutral Prompt / Composable LoRA branches (the source's AND prompts), IP-Adapter in More Prompt mode; CFG scales below 1 and models with guidance embedding (no unconditional rows); models other than SD1.x, SD2.x and SDXL UNets; sample prediction.
- Errors where the source fails or produces NaN: Max Num. Characteristic Iteration < 1, AA Iteration Memory Size < 1, Num. Basis < 0, Regularization Strength < 0, Range Over Time <= 0, Annealing Strength <= 0 (the source slider allows 0, which divides by zero; the NUI slider starts at 0.01), Log 10 Tolerance > 300, non-whole iteration/basis/memory values.
- Not applicable: the Forge path (`forge_CHG.py`), the Turbo_dev branch and the Euler SMEA Dyn sampler interpolation, since NUI has none of them.
- UI only, not provided: the Check Convergence plot (see the log above) and the README link.

## Differences

- Least squares by QR (as CUDA's lstsq) instead of `torch.linalg.lstsq` itself: the same solution up to rounding; on CPU, A1111's lstsq would use `gelsy` (minimum norm for rank-deficient systems, where QR gives NaN and the source's pseudo-inverse fallback applies).
- The source raises a `TypeError` when the first iteration of a step already converges (its iteration counter is still an int); NUI records 0 iterations and continues with the same correction.
- NUI's diffusers samplers differ from A1111's in places (for example DPM++ 2M rounds the timesteps it passes to the UNet), so the step's own evaluation sees NUI's timestep; the iterations use the quantised timestep of the scheduler's sigma like the source.

## Evidence (2026-11-01, CPU)

- `python/tests/test_characteristic_guidance.py` (25 tests): a literal restatement of the source `Chara_iteration` / `chara_ite_inner_loop` (A1111 row order, warm start, Anderson, quantile basis, ridge projection, final evaluation and `combine_denoised`) against NUI's hook on a deterministic non-linear model over six-step schedules, for eps and v on the k-diffusion path and eps and v on the timesteps path, each with defaults, Num. Basis 2 / AADim 3 (non-converging steps) and stretched Strength/Range with every advanced setting: guided predictions agree to 1e-4 (the source's lstsq against NUI's QR solve, through up to 50 iterations) and the per-step iteration counts are identical. Also: linear algebra helpers bit-identical to the source's, the QR solve against lstsq and bit-repeatable (CPU lstsq is not), NaN on rank-deficient systems, settings mapping and step rounding, the start/end window (plain CFG outside it, bit-exact), immediate convergence, quantised iteration timesteps, inpainting channels untouched, other pre-hooks seen by the iterations and not re-run, install passes and every rejection; a tiny diffusers SD pipeline with Euler, DPM++ 2M and DDIM: the hook uses the scheduler's sigma (or abar(t) on the DDIM path), runs are bit-exactly repeatable (checked also with 8 threads), CHG changes the latents and an empty window equals plain CFG.
- Scala: `CharacteristicGuidanceSuite` (controls, hook fields, the exact `CHG` text and its A1111 quoting, PNG round trip, source PNG spellings and older layouts, malformed values) and `InspectGenerationMetadataSuite` (MCP candidate, `CHG` consumed, malformed value unapplied).

## Real-model checks (2026-11-01)

`scripts/dev/check_characteristic_guidance_real.py` drives the official MCP Python SDK 2.3.0 against the real engine
(receipts `outputs/chg-real-sd15/receipt.json`, `outputs/chg-real-sdxl/receipt.json`), CFG 12, Euler, model offload.
SD1.5 `animekawa_v10` 512 px and SDXL `waiIllustriousSDXL_v170` 832x1216 both passed:

- Default settings, Annealing Strength 5, the basis/window/advanced settings each changed the image; an empty guidance
  window (start step = stop step) reproduced the baseline exactly.
- PNG replay through `inspect_generation_metadata` was pixel-exact.
- DDIM and a hires pass each changed from their own baselines; combining with Dynamic Thresholding was rejected.
- Cancelling mid-run and generating again reproduced the baseline exactly.

Images inspected: every variant stays coherent, with composition and pose shifts of the kind guidance corrections
produce. Nothing was compared against a running A1111.

## Limits

- Not compared against a running A1111 with the extension.
- Real-model behaviour, speed and convergence rates are not yet verified (see above).
