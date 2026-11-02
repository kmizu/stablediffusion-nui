# Low-VRAM and speed optimizations (#143)

Measured 2026-11-02 on an RTX 3090 (24 GB), torch 2.7.1+cu128, diffusers 0.33.1, Windows (WDDM).
Models: SD1.5 `animekawa_v10` 512x512 and SDXL `waiIllustriousSDXL_v170` 832x1216, 20 steps, DPM++ 2M, CFG 6, seed 1234,
fp16. Each configuration runs in its own process (`scripts/dev/bench_optimizations.py --drive`), holds `outputs/.gpu.lock`,
does one untimed warm-up and then two timed generations (seconds = the faster one). Peak VRAM is `torch.cuda.max_memory_allocated`
including the resident weights. "Image diff" is the mean absolute pixel difference as a fraction of full scale against the
baseline image of the same model and size; the run-to-run noise floor is about 0.003 (batch kernels are not bit-stable).
Contact sheets of all images were looked at; notes below. Raw results: `outputs/opt/*/*.json` (not committed).

## 1. Inventory before this work

| Area | State |
|---|---|
| VRAM modes | `gpu`, `model_offload`, `sequential_offload` (Settings, `vram_mode`); no automatic choice |
| dtype | fp16 / bf16 / fp32 (`dtype`) |
| VAE | `vae_tiling` (load-time, diffusers default tile 512 SD1.5 / 1024 SDXL; no-op below the tile size); NeverOOM hook (per job: attention + VAE slicing, tiling); SDXL force-upcast to fp32 for decode; decode failures are caught and saved (VAE tools) |
| Attention | diffusers SDPA (`AttnProcessor2_0`) is the default; xformers, sage attention, flash-attn, triton, torchao, bitsandbytes are not installed |
| Token / spatial tricks | Token Downsampling hook, HyperTile hook, MultiDiffusion / Tiled hooks |
| Compile | `compile` hook (#19): Inductor / TensorRT, optional packages, see `docs/compile-verification.md` |
| Preview | cheap linear approximation, optional TAESD |
| Memory benchmark | `vram_estimator` hook measures the largest size/batch that fits |
| Missing | automatic VRAM mode, automatic VAE fallback, VAE slicing / fp16 decode as settings, fp8 weights, feature caching (DeepCache), large-image safety |

## 2. Results

### Existing options (baseline for the rest)

| Option | SD1.5 VRAM MiB | SD1.5 s/img | SDXL VRAM MiB | SDXL s/img | Image diff |
|---|---|---|---|---|---|
| gpu (baseline) | 2676 | 1.23 | 11817 | 6.95 (6.60 in a later run) | - |
| model_offload | 1792 | 2.71 | 5371 | 15.1 | 0.0 / 0.014 |
| sequential_offload | 620 | 14.8 | 4954 | 142.6 | 0.0 / 0.014 |
| `vae_tiling` | 2676 (no tile at 512) | 1.33 | 11038 | 6.67 | 0.0 / 0.0035 |
| bf16 | 2676 | 1.44 | 9085 | 6.24 | 0.017 / 0.057 |

Key finding: the VAE decode is the peak. SDXL denoising peaks at 7.1 GB (weights 6.7 GB), the fp32 decode of one 832x1216
image takes it to 11.8 GB. SD1.5 512 denoise 2196 MiB vs decode 2676 MiB.

### New and measured options

| Optimization | Setting | SD1.5 VRAM | SD1.5 time | SDXL VRAM | SDXL time | Image diff (SD1.5 / SDXL) | Decision |
|---|---|---|---|---|---|---|---|
| Automatic VAE slicing / tiling | `vae_auto` (default on) | unchanged when it fits | unchanged | unchanged when it fits | unchanged | 0.0 when it does not trigger | **default on**, see section 3 |
| fp16 SDXL VAE decode | `vae_fp16` | n/a (not force-upcast) | n/a | 11817 -> 9086 | 6.60 -> 6.37 | n/a / 0.0002 | opt-in (a VAE that yields NaN falls back to fp32 per decode); images identical to the eye |
| VAE slicing | `vae_slicing` | batch 4: 4502 -> 2683 | 0.957 -> 0.963 | batch 2: 14784 -> 11831 | 6.45 -> 6.62 | same as noise | opt-in (auto covers the OOM case) |
| fp8 U-Net storage | `fp8_storage` | resident 2057 -> 1241, peak 2676 -> 1858 | 1.22 -> 2.25 (slower) | resident 6725 -> 4235, peak 11817 -> 9334 | 6.60 -> 7.38 | 0.040 / 0.055 | opt-in VRAM saver, visibly (not badly) different image; GPU mode only |
| DeepCache interval 3, 5 full steps first | `deepcache` hook | 2686 | 1.24 -> 0.91 (1.4x) | 11856 | 6.56 -> 3.89 (1.7x) | 0.054 / 0.060 | opt-in hook; same composition on SDXL, softer fine detail |
| DeepCache interval 2 | | 2686 | 1.24 -> 1.17 | 11856 | 6.56 -> 4.81 (1.4x) | 0.043 / 0.047 | |
| DeepCache interval 5 | | 2686 | 1.24 -> 0.91 | 11855 | 6.56 -> 3.27 (2.0x) | 0.065 / 0.082 | visibly hazier on SDXL |
| DeepCache, 8 full steps first, interval 3 | | 2686 | 1.13 | 11855 | 4.43 (1.5x) | 0.027 / 0.043 | closest to the exact image |
| DeepCache 0 full steps first (first attempt) | | | 0.81 | | 3.02 | 0.072 / 0.111 | composition changed and quality fell: replaced by the `full_first` warm-up |
| Auto VRAM mode | `vram_mode=auto` | see section 3 | | | | | opt-in choice (the default stays `gpu`) |
| channels_last (U-Net + VAE) | removed | 2676 -> 2579 | 1.22 -> 1.45 (slower) | 11817 -> 11323 | 6.60 -> 7.32 (slower) | 0.004 / 0.015 | **dropped**: slower and changes pixels |
| TF32 matmul | removed | same | 1.22 -> 1.32 | same | 6.60 -> 6.65 | 0.0 / 0.0 | **dropped**: no effect (fp16 model; cuDNN already uses TF32) |
| cudnn.benchmark | removed | same | 1.22 -> 1.28 | 11817 -> 11570 | 6.60 -> 6.60 | 0.002 / 0.011 | **dropped**: no gain, changes pixels |
| channels_last + TF32 + benchmark | removed | | 1.63 | | 7.23 | 0.003 / 0.016 | **dropped** |

VAE decode alone (`scripts/dev/bench_vae_decode.py`, real latents, peak above the resident weights):

| Variant | SDXL 832x1216 | diff | SD1.5 1024x1024 batch 4 | diff |
|---|---|---|---|---|
| default (SDXL fp32 upcast, SD1.5 fp16) | 4942 MiB, 0.51 s | - | 9731 MiB, 1.21 s | - |
| bf16 | 2350 MiB, 0.29 s | 0.0020 | | |
| fp16 | 2350 MiB, 0.29 s | 0.0008 | | |
| SD1.5 in fp32 | | | 16388 MiB, 17.6 s | 0.0001 |
| tiled 1024 / 768 / 512 / 384 / 256 | 4162 / 2882 / 1288 / 734 / 340 MiB | 0.0035 / 0.0048 / 0.0065 / 0.0074 / 0.0094 | 9731 / 5475 / 2456 / 1403 / 655 MiB | 0 / 0.007 / 0.014 / 0.017 / 0.020 |
| sliced (batch) | | | 2453 MiB, 1.15 s | 0.00002 |

Slicing is free; tiling costs time (0.5 -> 0.7-1.4 s for SDXL) and some pixels (seam blending, grows as tiles shrink).

Existing hooks at large sizes (for reference, same harness):

| Hook | SD1.5 1024x1024 | SDXL 1280x1792 |
|---|---|---|
| base | 6.23 s, 4501 MiB | 18.7 s, 18078 MiB |
| HyperTile | 4.30 s (diff 0.079) | 19.3 s, no effect (0.0) |
| Token Downsampling factor 2 | 4.34 s (diff 0.044) | 17.3 s (diff 0.073) |
| DeepCache (3, 5 first) | 4.99 s (diff 0.053) | 11.1 s (diff 0.067) |
| `vae_tiling` at SDXL 1280x1792 | | 18078 -> 12016 MiB, 20.4 -> 18.3 s, diff 0.0044 |

## 3. Behaviour that matters for low-VRAM cards

Emulated small cards: `set_per_process_memory_fraction` makes the allocator really run out of memory above the cap and
`SDFX_VRAM_BUDGET_MB` makes the engine's free-VRAM figure follow it.

| Case | Without the option | With it |
|---|---|---|
| SDXL 832x1216, 8 GB cap, gpu mode | decode OOM, "Broken" recovery image (diff 0.114 vs the exact image) | `vae_auto`: tiles of 512 px, completes, peak 7429 MiB, 7.2 s vs 6.7 s, diff 0.008, image looks the same |
| same, plus `vae_fp16` | | completes, peak 7389 MiB, 6.9 s, diff 0.006 |
| SD1.5 512, 2.4 GB cap | decode OOM, recovery image | `vae_auto`: completes, peak 2220 MiB, 1.35 s vs 1.32 s, diff 0.016 |
| SDXL 1792x2304 (4.1 Mpx), 24 GB | decode spills into shared system memory: 208 s, peak 27 GB "allocated" (WDDM) | `vae_auto`: 1024 px tiles, 23.6 s, peak 12.0 GB, diff 0.0066 (9x faster) |
| `vram_mode=auto`, SDXL, 12 GB cap | | chose `gpu`; completes, peak 9.8 GB |
| `vram_mode=auto`, SDXL, 7 GB cap | | chose `model_offload`; completes, peak 5.4 GB, 16 s |
| `vram_mode=auto`, SDXL, 5 GB cap | | chose `sequential_offload`; completes, peak 3.9 GB |
| `vram_mode=auto`, SD1.5, 3 GB cap | | chose `model_offload`, peak 1.8 GB |
| `vram_mode=auto`, SD1.5, 2 GB cap | | chose `sequential_offload`, peak 0.6 GB |

`vae_auto` is on by default because it does nothing unless the decode would not fit (images stay byte-identical, checked:
diff 0.0 with the option on in every baseline run) and it turns an out-of-memory failure into a finished image.

## 4. Combinations

* `deepcache` is refused with ControlNet, Composable LoRA / Neutral Prompt, the offload VRAM modes and any hook that
  patches the U-Net or sampler loop; unit tests cover the refusals. LoRA and hires fix are allowed (hires installs a fresh
  cache per pass, a shape change forces a full step).
* `fp8_storage` is refused outside `gpu` mode and with fp32; offload modes move the weights.
* `vae_fp16` is skipped in the offload modes (the VAE is moved by accelerate hooks).
* `vae_auto` / `vae_slicing` / `vae_tiling` work in every VRAM mode and with ControlNet, LoRA and hires (the scope is
  applied around every pipeline call and explicit decode).
* The PNG infotext keeps `DeepCache interval / branch / full first`, so PNG replay restores the hook. Load options are
  engine settings, not per-image, and are not in the PNG.

## 5. Not provided

* xformers / sage attention / flash-attn / TeaCache: not installed or not applicable to the U-Net (TeaCache targets DiT
  models); SDPA is already used.
* Quantised (int8 / nf4) weights: bitsandbytes / torchao are not available on this machine, so nothing could be measured.
* Split generation of huge images is covered by the existing MultiDiffusion hook and automatic VAE tiling; no new tiler.
* The measurements are from one GPU (RTX 3090); the `vram_mode=auto` thresholds (`perf.py`) are calibrated from them and
  were only exercised through emulated caps, not on a real small card. `tf32` / `channels_last` may differ on other GPUs.

## 6. Verification status at commit time

* Measured: everything in sections 2 and 3 (real SD1.5 and SDXL, GPU-locked runs, images inspected).
* Not measured: the real-app / official MCP SDK run of the new settings and the DeepCache plugin (only the in-process engine harness was used); `vram_mode=auto` on a real small card; combination runs of DeepCache or fp8 with LoRA / hires on a GPU (refusals and the hires cache reset are covered by unit tests only).
* Tests: Python `tests/test_perf.py` and `tests/test_vae_memory.py` pass on CPU. The Scala `sbt test` run was stopped before it finished and `ui/assembly` was not run, so the Scala changes (Settings, Protocol, Settings dialog, DeepCachePlugin, PNG replay) are uncompiled and untested. The full Python suite was not run.
* Defaults: only `vae_auto` is on (measured: no change unless the decode would not fit). All other options default off.

## 7. Auto VRAM mode with fp8 (follow-up)

An engine-boundary CPU regression test reproduced that `vram_mode=auto` could select an offload mode after fp8 validation had already run. The engine now validates the resolved mode before loading weights and rejects the unsupported combination. The focused `test_perf.py` and `test_vae_memory.py` run passed 34 tests with one CUDA-only skip; the correction has not yet been checked through the application or on a GPU.

The DeepCache engine now rejects non-finite, fractional and boolean integer fields rather than coercing them or leaking an overflow exception. Regression cases were observed failing first; the focused Python suite then passed 46 tests with one CUDA-only skip. After merging main `d9f13ea`, the JDK 21 Protocol (34), DeepCache (4), MCP (30) and UI Round2 (3) tests passed and `ui/assembly` succeeded (`outputs/optimization-scala.log`). The official SDK harness `scripts/dev/check_optimization_mcp.py` passed its dry-run path (`outputs/codex-optimization-mcp-dry/receipt.json`); it does not establish real optimization, quality, or the incompatible-hook refusal. Real app/MCP SD1.5/SDXL testing remains pending.

A further engine-boundary regression showed that `fp8_storage` on CPU was silently skipped. The engine now rejects it before loading; focused Python tests passed 47 with one CUDA-only skip. This is CPU validation, not a real GPU test.

Additional CPU regression after main merge: 107 tests in engine safety, hooks, protocol, VAE tools/encoding-offload and img2img hires suites passed (`outputs/codex-optimization-cpu-regression.log`). These do not replace real-model application tests.

## 8. Official MCP SDK real-model check after v0.1 merge

On 2026-11-03 the official MCP SDK over stdio ran the JDK21 assembly (SHA-256 `8f0bf84cc5525463dad91534797895ec24711425cb21e009f6e561325f73ec45`) and the engine on an RTX 3090. SD1.5 `animekawa_v10` 512x512 and SDXL `waiIllustriousSDXL_v170` 832x1216 used 20 Euler steps, seed 2468, plus 1.25x latent hires. Settings requested GPU mode, VAE auto and slicing for both, and fp16 VAE for SDXL. The settings were passed through the app; this run did not independently measure VAE path activation.

Both runs exited 0 (`outputs/codex-optimization-mcp-{sd15,sdxl}/receipt.json`, corresponding `.log`). They checked DeepCache interval 3 / branch 0 / five initial full steps, a repeated identical output, exact PNG metadata replay, successful hires pass, FreeU refusal and exact restoration of the plain image afterwards. They also generated with `20_yui` (SD1.5) and `20_hinata` (SDXL) LoRAs: the plain LoRA outputs differed from non-LoRA by mean absolute RGB 0.0996 and 0.1191 respectively, establishing a visible LoRA effect. The DeepCache/plain mean RGB differences were 0.0370 and 0.0355; with LoRA they were 0.0291 and 0.0624. These are single images, not general quality guarantees.

Contact sheets were viewed: SD1.5 shows a forest cat with changed cub placement/details under caching and a coherent hires image; LoRA changes composition and style. SDXL shows a similar tiger/lion portrait across cache and replay, with changed fine features; the LoRA image changes background and face. No visual corruption was observed in these examples. The GPU lock was returned after each run.

Limits: GUI interaction, fp8 through MCP, actual VAE path/peak measurements in these two SDK runs, real small GPU, ControlNet and other hook combinations, full Python/Scala suites, and an extracted package with this change are still unverified. Previous in-process benchmark measurements above are separate evidence and do not prove those integration paths.

## 9. Full Python regression after main merge

The full Torch CPU suite initially failed 11 tests (`outputs/codex-optimization-python-full.log`): two execute a captured Python AST block without the newly referenced VAE scope and its arguments; the other nine instantiate an engine with `__new__`, omitting the `load_opts` initialized by the real `BaseEngine` constructor. Test fixtures were updated to supply those prerequisites without altering product behaviour. The four affected test files then passed all 133 selected tests. A complete rerun with CUDA disabled and one OMP/MKL thread exited 0: 2,998 passed and three skipped (`outputs/codex-optimization-python-full-rerun.log`; counts from pytest progress output).

## 10. Incomplete Scala full-suite attempt

On 2026-11-03 `sbt -batch test ui/assembly` was started after creating a worktree-local `.venv-test` junction to the existing test environment. The log (`outputs/codex-optimization-scala-full.log`) includes cached results and some suite output, but stopped advancing for over two minutes; the run was interrupted to return the shared sbt slot. Its sbt process, fork worker and retained Windows filesystem-probe process were confirmed gone. The probe is designed to stay alive while tests run, so its presence alone does not identify the stalled suite or cause. Full Scala and assembly for this run were not completed. The earlier focused Scala/assembly evidence in section 7 remains separate. Retry the scanner suites separately to determine timing and then run the full suite with sufficient time.

## 11. CPU VAE fallback coverage

Additional CPU tests exercise the fp16 VAE decode wrapper with a simulated SDXL upcast: finite fp16 output, non-finite fp16 output retried in fp32, and a decode exception. They check the dtype passed to each call, restoration of the pipeline-entering dtype and removal of the temporary decode wrapper. `python/tests/test_vae_memory.py` exited 0 with 13 tests passed and one CUDA-only skip. This uses a fake VAE and does not replace real-model, low-VRAM or GPU testing.

## 12. fp32 fallback needs its own memory plan

CPU test `outputs/codex-vae-fallback-red.log` reproduced a specific logic gap: with `vae_auto` and `vae_fp16`, the initial un-tiled fp16 plan fits 4,000 MiB but a non-finite decode retries fp32 without tiling. The fallback now recomputes the plan for fp32 using current free memory, never enlarges an already active tile, and restores the previous VAE state on scope exit. `outputs/codex-vae-fallback-green.log` passed 117 tests with one CUDA-only skip across four related files; after a state-restoration refactor `outputs/codex-vae-fallback-final.log` passed the VAE suite (14 passed, one skipped). These fake-VAE CPU tests do not establish real GPU memory behavior; real fp16-to-fp32 retry, automatic tiling, and post-retry images remain unverified. Earlier real MCP runs in section 8 predate this source change.

## 13. Real SDXL VAE fallback under an emulated cap

On 2026-11-03, `scripts/dev/check_vae_fp32_fallback_real.py` exited 0 on RTX 3090 with real `waiIllustriousSDXL_v170` (model short hash `f116b0c78f`), 832x1216 latent from a 20-step generation and source `vae_memory.py` SHA-256 `6160e927a7ca9dc9d8a9af263696bc6d97f31a203057b07ef008381519cd55e6`. A per-process allocator limit of 10732.9 MiB emulated 4000 MiB free for the decode plan. The harness deliberately replaced the first fp16 decode result with NaN: this was an injected fault, not a naturally observed model failure. The initial fp16 path was untiled; the actual fp32 retry used 768px tiles. Its resulting RGB was identical to the independently decoded tiled fp32 reference (mean absolute difference 0); both peaked at 9774.5 MiB allocated. VAE tiling/slicing and tile sizes matched the pre-test state afterwards. The output image was viewed: an intact tiger/lion portrait, no visible corruption. Receipt and images are in `outputs/codex-vae-fallback-sdxl-20261103/`, log alongside. The shared lock was released after the run.

This was an in-process `Engine._decode` check with injected NaN on an RTX 3090 and an emulated memory cap. It does not verify natural fp16 failure, a physical low-VRAM GPU, the SDK/application generation path for this fallback, SD1.5, or the extracted package. The pre-existing full CPU suite ran before this source change.

## 14. Main c4abab3 integration check

After merging main `c4abab3` into the #143 branch (`114ffbf`), targeted CPU Python tests across optimization and aesthetic-gradient paths passed 142 tests with one CUDA-only skip (`outputs/codex-optimization-main-c4-focused.log`). The DeepCache refusal test separately passed for four cases, including the newly added Aesthetic Gradients combination. This does not verify either feature on a GPU in the newly merged application or replace a full-suite run. See `optimization-handoff.md` for remaining gates.

## 15. CreaPrompt main integration

After merging main `2284f49` (CreaPrompt) into the #143 branch at `8d4b3b3`, the full Torch CPU Python suite exited 0: 3014 passed, 3 skipped (`outputs/optimization-main-2284-python.log`, parsed from pytest progress). The subsequent merge of `6ed1f22` changes only `HANDSOFF.md`. Both CreaPrompt and DeepCache remain registered. Scala full regression, assembly and real client/package checks on the merged tree have not yet been performed.

After the merge above, `sbt -batch test ui/assembly` was attempted (`outputs/optimization-main-2284-scala.log`). Compilation advanced through UI test sources and the log contained some cached suite output, but it stopped advancing for over two minutes. It was interrupted to release the shared sbt slot; the parent sbt process, fork worker and their child processes were confirmed gone. Full Scala did not finish and assembly was not reached. The earlier full-suite attempt also stalled; no cause is confirmed. A focused test/assembly run and separately diagnosed complete regression are still needed.

The two-minute no-output observations cannot distinguish a hang from a normal long-running test. The separate `ModelCardScannerNativeBulkSuite` contains two real-filesystem tests, each creating 4097 files and each with a five-minute timeout; neither printed completion in the partial logs. This is a candidate for isolated timing, not a confirmed diagnosis.

Isolated diagnosis: `core/testOnly *ModelCardScannerNativeBulkSuite` passed both tests in 163.642s and 175.329s (341s total), `outputs/optimization-bulk-isolated.log`. The first 4097-file fixture disappeared and the second appeared while the suite remained silent; the Windows probe consumed CPU throughout. This proves that this suite alone can legitimately take more than two minutes, but does not prove it was the exact active suite in either interrupted run. Full Scala remains pending.


## Integrated main and packaged launcher verification (2026-11-03)

Source commit `1d606b41ecffae2b64bcce0ad370b2218f4a1a4b` includes main `6ed1f22` (code same as `2284f49`). JDK 21 `sbt -batch test ui/assembly` exited 0: 985 reported tests, 984 passed, one skipped; sbt reported cache 47%. The previously isolated native bulk suite actually ran and passed 2/2 in 341 seconds. Log: `outputs/optimization-main-2284-scala-complete.log`. Full CPU Python: 3014 passed, 3 skipped (see `outputs/optimization-main-2284-python.log`). Jar SHA-256 `e134d3ac2a5c56d4141cdbb739596e8d42a9560dcd0b0631f9be1e1d55714dff`.

The Windows package was created with `scripts/package_windows.ps1` and unpacked. ZIP SHA-256 `e7893ff1e6ca58e2f0422810ea71a4fedd46df846572a8cbbe28338b3d753000`. `scripts/dev/payload_check.py` found 342 matching tracked payload files, no missing or differing files, matching jar and notices, and present exe. This was a test package, not a published release.

On RTX 3090, official MCP SDK stdio started the unpacked `SDNUI.exe --mcp`, with packaged Python source and the external smoke Python environment; SD1.5 animekawa_v10 (512x512, 20_yui LoRA) and SDXL waiIllustriousSDXL_v170 (832x1216, 20_hinata LoRA) used 20 Euler steps and seed 2468. The temporary launcher harness `outputs/check_optimization_launcher.py` extends `scripts/dev/check_optimization_mcp.py` only with launcher selection; receipts and logs are `outputs/optimization-launcher-{sd15,sdxl}/receipt.json` and `outputs/optimization-launcher-{sd15,sdxl}.log`. Both passed repeated DeepCache pixel identity, PNG metadata replay, hires generation, FreeU incompatibility refusal, restoration of ordinary generation, and LoRA effect. DeepCache vs ordinary mean absolute RGB difference was 0.0370 and 0.0355; LoRA vs no LoRA was 0.0996 and 0.1191 respectively. Cached and hires outputs from both models were viewed and show intact illustrations, though the prompt blends lion and tiger traits. GPU lock was released. Direct JDK 21 plus unpacked jar/Python also passed the same checks; the package runtime lacks java.exe so an initial attempt to launch it directly failed before generation.

These are finite tests on a 24 GiB GPU, not physical low-VRAM validation. Settings requested automatic VAE/slicing and SDXL fp16 VAE, but this SDK run did not independently instrument which VAE path activated. The previously recorded allocator-limit/NaN fallback test is simulated and in-process; it is not a naturally occurring failure or a physical low-VRAM test.


## Post-review corrections (2026-11-03)

The packaged GPU receipts above predate these corrections and do not verify the changed code. Automatic VAE slicing/tiling is now applied only during `decode`; the preceding img2img/inpaint `encode` retains its original settings. A CPU regression reproduced the former encode-time flag leak and passes after the change; another covers restoration after a failing decode. Re-test the final source on real img2img/inpaint, both models and the forced fp32 fallback, including output images and memory.

For `vram_mode=auto` plus fp8, auto is now assessed before dropping the existing model. If the currently free VRAM selects an offload mode, the request is rejected without destroying the old model. This is conservative: freeing the old model may make enough VRAM available; users can explicitly unload it and retry auto, or explicitly select GPU mode. For auto without fp8, selection still happens after unloading. The CPU test reproduced loss of the old pipeline before the correction and verifies preservation afterwards; no GPU application test of this correction has yet run.

DeepCache installation and cleanup were separately corrected and tested by the #26 reviewer; refer to its tests and changes for details. Full CPU Python after these changes exited 0 with 3018 passed and 3 skipped (`outputs/optimization-vae-fp8-regression.log`); a further VAE decode-failure regression was subsequently added and passed in the focused run.


## Post-review GPU checks (2026-11-03)

Python source `6f2734c72bfd42c179589d45fc307fead1f0b3ed` was packaged with pre-#24 jar SHA-256 `e134d3ac2a5c56d4141cdbb739596e8d42a9560dcd0b0631f9be1e1d55714dff`; ZIP SHA-256 `4e04b4a9cda1d2458d3c3ca609da103cf05e309ee916837275b1bc91c0da30ec`. Payload checker reported 342 matches, zero missing or differing, matching jar/notices. This does not cover later main `9876bda`.

Official SDK and unpacked `SDNUI.exe --mcp` passed SD1.5 and SDXL DeepCache repeat, replay, hires, LoRA, FreeU refusal and normal-generation recovery (`outputs/optimization-postreview-{sd15,sdxl}/receipt.json`). A separate SDK run used `SDFX_VRAM_BUDGET_MB=1` to force the VAE planner to select 256px tiles and passed txt2img, img2img, masked inpaint, PNG replay, FreeU, progress and cancellation/recovery with SD1.5 512x512 and SDXL 832x832 (`outputs/optimization-postreview-img-{sd15,sdxl}/receipt.json`). The four img2img/inpaint images were viewed: intact teapot illustrations, no apparent corruption. This environment variable changes the plan's budget only; it is not a physical VRAM limit and does not itself prove actual low-memory success or measure peak. CPU regression separately observes encode flags unchanged.

The in-process real SDXL injected-NaN fallback harness passed on RTX 3090 with allocator cap and 4000 MiB emulated free; fp32 fallback peak 9774.5 MiB, identical RGB to the independent tiled fp32 reference, VAE state restored; output viewed and intact. Receipt and images `outputs/optimization-postreview-vae-fallback/`; source VAE SHA-256 `76fb3d61639c3b3461d61db5d665b178df3d97f2fd0a8905c49a6a554ce52b3a`. Fault injection and emulated limits do not prove natural failure or physical low-VRAM behavior. The harness owns and released its GPU lock.


## Main 9876bda integration (2026-11-03)

Main `9876bda524b66054475ac3e87ee016473b964700` merged into #143 as `61fa1a05404d23f89f85e3017d3ba4bdb1333aed`; the upstream changes are UI wildcard Scala and documentation, not Python. JDK 21 `test ui/assembly` exited 0, reporting 43 newly executed tests and 90% task cache; do not claim a fresh all-suite run (`outputs/optimization-main-9876-scala.log`). The jar SHA-256 is `277aea203a5a44a39928d599c1293286ce7ddb8761217c1cffbc89aa494d7c9c`.

The unpacked test ZIP SHA-256 is `4dabfd9a66dc928439865688f90884106397085061b55829e85f3bc651e0c5b1`; payload checker found 342 matched tracked files, none missing/differing, and matching jar and notices. The official SDK through `SDNUI.exe --mcp` passed SD1.5 512x512 and SDXL 832x1216 DeepCache / LoRA / hires / replay / refusal / recovery, and separate SD1.5 512x512 and SDXL 832x832 img2img/inpaint/replay/FreeU/progress/cancel tests with planner-only `SDFX_VRAM_BUDGET_MB=1` (`outputs/optimization-main-9876-{sd15,sdxl,img-sd15,img-sdxl}/receipt.json`). The two inpaint images were viewed and intact. The 256px tile plan was logged; the planner cap is not physical low-VRAM testing. Shared GPU lock was released.


## SD1.5 ControlNet and fp8 through MCP (2026-11-03)

Official MCP SDK stdio invoked main product jar SHA-256 `9eab082e572f722c54f8ff47aeb6341a413763bc68a70e0647cc5aa299235982` with main Python, RTX 3090, SD1.5 `animekawa_v10` (short hash `d4c1719023`), 512x512, Euler 20 steps, seed 173, fp16 compute and GPU mode. A local cached `lllyasviel/control_v11p_sd15_canny` fp16 safetensors file (SHA-256 `9ed3a7f834aca0d2b26f9c7f186b3ee29465de1d13e152459e8c9cbb25a0c0bc`) was copied into an isolated ControlNet catalog. Canny input was a synthetic rectangle and circle. No SDXL ControlNet model was found in the inspected model directories or local Hugging Face cache; SDXL combination remains unverified.

Both default and fp8-requested settings generated a ControlNet image and rejected DeepCache plus ControlNet, then reproduced the preceding non-ControlNet image exactly. The control/no-control mean absolute RGB differences were 0.17518 and 0.17288; these show an effect for this input, not a quality guarantee. Compared control images were viewed and showed the expected circular and rectangular composition without apparent corruption. Plain fp8-requested vs default mean RGB difference was 0.03762.

For an independent repeat, a **test-only** `sitecustomize.py` wrapped `perf.apply_unet` and counted actual parameter dtypes immediately after the call; it did not change model settings or production source. Default: 686 fp16 parameters; fp8 requested: 468 `torch.float8_e4m3fn` and 218 fp16, with fp16 compute. The repeat passed the same ControlNet/refusal/recovery checks. The hook proves storage dtype for this SD1.5 run, not for SDXL. Receipts `outputs/optimization-controlnet-fixture/receipt{,-audit}.json`, observed dtype counts in `{normal,fp8}-audit/dtype-audit.json`, scripts `outputs/check_optimization_control_fp8{,_audit}.py` and logs `outputs/optimization-control-fp8{,-audit}.log`; outputs are local and ignored, not distributed. The GPU lock was released. GUI operation and physical low-VRAM hardware remain unverified.
