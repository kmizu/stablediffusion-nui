# Sonar verification (#118)

Source: Kahsolt/stable-diffusion-webui-sonar `15f92859f70f624513796dde91396109134da47b` (MIT, LICENSE SHA-256 `db9e98edd8524ac09e959b256bfb5687d27f88a9a442be629b0e55d102bfc0eb`), `README.md` and `scripts/sonar.py`, read as a behaviour reference; NUI re-implements it (`python/sdfx/sonar.py`, `plugins/src/main/scala/sdfx/plugins/SonarPlugin.scala`), no code is copied. The licence permits reuse; the code is independent because the A1111 k-diffusion hijack does not exist here. Ledger index 69, target `sonar`.

## Feature inventory and native mapping

| Source feature | NUI |
| --- | --- |
| Base samplers `Euler a`, `Euler`, `Naive` (hijacked k-diffusion `sample_*_ex`) | The diffusers `EulerAncestralDiscreteScheduler` / `EulerDiscreteScheduler` step is re-implemented in `sonar.wrap_scheduler`. With momentum 1 it is bit-identical to diffusers (unit tests, and real SD1.5 and SDXL identity checks). `Naive` runs on the Euler scheduler: Euler plus a 1e-5 noise after each step and the reference guidance. While the plugin is on, its sampler replaces the sampler of the request (as the source's sampler replacement does). |
| Momentum current 0.95 (slider 0.75-1), Momentum history 0.75 | Same defaults and formulas; values 0 to 1 are accepted (the slider keeps the source range, axes can go outside it). |
| Momentum sign pos / neg / rand, history init zero / rand_init / rand_new | Same. `rand` and `rand_new` use a generator seeded with the pass seed, not the global RNG, so replays are exact. |
| Reference image (Naive only): method linear / euler, factor 0.2 (divided by 10 as in the source), start 0, stop 0.75 | Same, per pass at the pass size; the latent is the VAE posterior mean (the source samples it). Values of 1 or less are fractions of the request's steps, larger ones are step numbers; both ends are inclusive. Ignored with a warning for Euler and Euler a, as in the source's UI. |
| Upscaling (upscaler, ratio, width, height) | Applied to every finished image after refinement and before saving (the source's save hook): ratio only resizes `gp.width/height * ratio`, a width or height covers the target and centre-crops. Pixel upscalers (Lanczos, Nearest, waifu2x, LDSR) and spandrel model upscalers work. |
| Grid search (momentum / history search lists, annotated grid) | Covered by X/Y/Z axes `Sonar momentum` and `Sonar momentum_hist` (plus sampler, sign, history init and reference factor). The `start:stop:step` list syntax is not provided. |
| Script / AlwaysVisible morph switch | Not applicable (NUI plugins are always AlwaysOn). |
| Infotext `Sonar sampler`, `Sonar momentum`, `Sonar momentum_hist` | Same keys. Added only when they differ from the defaults or are in use: `Sonar momentum_hist_init`, `Sonar momentum_sign`, `Sonar ref_img` (file name), `Sonar ref_meth`, `Sonar ref_hgf`, `Sonar ref_start_step`, `Sonar ref_stop_step`, `Sonar upscaler`, `Sonar upscale_ratio`, `Sonar upscale_width`, `Sonar upscale_height`. Source PNGs restore with the defaults for the keys they never wrote. |

## Behaviour kept from the source

- With the history start `zero` the first step is `momentum * d` (the missing history counts as 0); `neg` is `(1 + p) d - p h`.
- The reference strength is the slider value divided by 10; the Euler guidance uses the latent after the step with the old sigma; normalisation uses the unbiased standard deviation.
- `rand_init` uses the sigma-scaled initial noise (standard deviation about 14.6) as the history, which dominates the first steps and collapses the image to a smooth wash at ordinary momentum (SD1.5 and SDXL checks show this). It is marked experimental in the source.

## Differences

- The k-diffusion samplers do not exist; diffusers' Euler / Euler a schedulers are used (same update rule). Euler's stochastic churn is not supported (raises). `Euler a` with a `sample`-prediction model raises like diffusers.
- Random choices are seeded; the reference latent is the posterior mean.
- The reference image is an input file and is not stored in the PNG; a PNG made with a reference image replays without it (the infotext names the file).
- ADetailer's own inpaint passes run without Sonar. The source applies the hijack to `p.sample` of the script's `p` only.
- The upscale target for a ratio uses the base size (`p.width * ratio`), as the source does, so with Hires the result is resized to the base size times the ratio.
- Not compared against a running A1111 with the extension.

## Evidence (CPU, 2026-11-02)

- `python/tests/test_sonar.py` (42 tests): defaults and validation; effective sampler; step bounds; `momentum_step` equals a literal restatement of the source over a history (pos, neg, tensor history); guidance steps for linear and euler equal the source; the normalised reference; a scheduler loop with momentum 1 is bit-identical to diffusers for Euler and Euler a, epsilon and v prediction; Euler a, Euler and Naive loops with momentum, history starts, reference guidance and noise equal literal restatements of the source's sampler loops; fp16 output dtype; determinism and seeding; churn and unsupported prediction rejections; cleanup; upscale targets and cover-crop; hook registration; a small real diffusers pipeline (momentum 1 is a no-op, momentum changes the image deterministically, the scheduler is restored, the Naive reference changes the image for both methods).
- `SonarSuite` (7 tests): defaults, key sets, round trip of every control (except the reference file), source PNG restore, malformed values, axes. MCP: `InspectGenerationMetadataSuite` restores `sonar` and consumes its keys.

- Totals: Torch CPU 2,703 passed / 2 skipped; Scala `sbt test` all suites passed (this run: 162 + 64 + 119 + 227 tests executed, the rest cached as unchanged).

## Real-model checks (2026-11-02)

`scripts/dev/check_sonar_real.py` drives the official MCP Python SDK 2.3.0 against the real engine (receipts `outputs/sonar-real-sd15/receipt.json`, `outputs/sonar-real-sdxl/receipt.json`, jar SHA-1 in the receipt). SD1.5 `animekawa_v10` 512x512 and SDXL `waiIllustriousSDXL_v170` 832x1216, 24 steps, CFG 6, fixed seed. Both passed every assertion:

- Identity: momentum 1 with Euler a and Euler reproduced the plain samplers pixel for pixel, also when the request named DPM++ 2M (so the sampler replacement works), and again in the hires pass and in img2img.
- Every momentum case changed the image (mean absolute pixel difference SD1.5 / SDXL): Euler a m0.9 27.0 / 31.7, Euler a neg 47.4 / 69.9, Euler m0.9 13.5 / 30.9, rand + rand_new 14.8 / 27.1, rand_init 30.5 / 56.6. Naive without reference differs from Euler by 2.1 / 1.9 (the 1e-5 noise). Reference linear 43.4 / 46.1 and euler 26.1 / 26.2 from Naive.
- Upscale by ratio 1.5 (SD1.5 768x768, SDXL 1248x1824) and to a square 2x the width (SDXL covered and cropped from 832x1216 to 1664x1664) had exact sizes.
- PNG replay of the combined settings (rand sign, rand_new history, upscale 1.25) was pixel-exact. A missing reference file was rejected by the MCP path policy. Cancelling a running job ended as cancelled and the next plain Euler run reproduced the baseline.

Images were inspected on contact sheets. Momentum 0.9 gives a different but coherent neighbour of the plain picture (new pose, background, props); Euler a with momentum 0.85, history 0.9 and sign neg breaks into heavy texture noise (expected for the experimental sign at those values); `rand_init` collapses to a wash (SD1.5 flat pink, SDXL a faint figure on a gradient); the reference pulls the composition and pose of the reference picture, strongly colour-shifted with `linear` at factor 1.0 (effective 0.1 per step) and washed out with `euler`; hires and img2img keep the subject and change details.

## Limits

- No comparison with a running A1111 and the original extension; the equivalence is of the update formulas (unit tests) and of the diffusers schedulers (bit-identity).
- Model upscalers (spandrel) are not exercised by the real checks (Lanczos only); the model path is passed through the same `upscalerPaths` mechanism as Hires.
