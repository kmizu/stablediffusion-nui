# #143 Optimization work handoff — 2026-11-03

## Location and integration

Worktree: `C:/Users/Mizushima/dev/stablediffusion-nui/.claude/worktrees/agent-ab7508137bf41c07e`, branch `worktree-agent-ab7508137bf41c07e`. The main checkout was at `d9f13ea72409ea543948bacb5807536ba6dc4f83` when merged into this branch (`ae0ad60`); recheck main and this worktree before integration. The branch is the sole source for #143 changes, is not integrated into main, and #143 remains open. Do not discard this worktree or overwrite newer main changes. `docs/optimization-verification.md` is the evidence record: earlier sections record earlier states, later sections supersede their pending-status statements.

The implementation was started in `7696734`. Follow-up commits include validation of auto/offload + fp8 and CPU + fp8 refusals, strict DeepCache integer input, real MCP tests, and fixture changes. The current handoff commit contains no other product changes. Review the complete branch diff against its merge base, including commits before the main merge, rather than assuming a single commit is the whole feature.

## Verified and not verified

- Original engine-only RTX 3090 benchmarks and image comparisons are recorded in `optimization-verification.md`; emulated memory budgets are not real low-VRAM hardware.
- Official SDK stdio runs on the branch jar with SD1.5 and SDXL passed DeepCache, Hires, LoRA, exact PNG replay and FreeU refusal; representative images were viewed. Receipts: `outputs/codex-optimization-mcp-{sd15,sdxl}/receipt.json`, logs and contact sheets beside them. Requested VAE settings were passed through the app, but those runs did not independently prove VAE path activation or measure peak memory.
- Full Torch CPU Python: 2,998 passed, 3 skipped (`outputs/codex-optimization-python-full-rerun.log`); initial 11 failures were missing test fixture prerequisites and are recorded with the corrections. Focused Scala suites and assembly passed (`outputs/optimization-scala.log`); the later full Scala test was interrupted and did not reach assembly (`outputs/codex-optimization-scala-full.log`). sbt 2 caches tests, so a successful cached report must not be described as a fresh all-suite run.
- Still needed: full Scala regression (investigate the delayed scanner suites and arrange an uncontended slot); real GUI settings/DeepCache interaction; fp8 through actual app/MCP including incompatible offload; ControlNet and other combinations exercised or explicitly rejected; verify VAE auto/slicing/fp16 behavior through the application with actual peak VRAM and output checks; assess a real low-VRAM device if available and otherwise document emulation as the limit; independent review; final merged build, extracted Windows package and real-model checks. Do not close #143 based on the existing partial evidence.

## Restart / coordination

Before using sbt or GPU coordinate with the parent/other work. GPU uses shared `C:/Users/Mizushima/dev/stablediffusion-nui/outputs/.gpu.lock`: create it atomically immediately before a GPU run and remove only the lock owned by that run. Never run or import reForge; its model folder is read-only reference.

- JDK 21: `C:/Program Files/Java/jdk-21`; sbt: `%LOCALAPPDATA%/Coursier/data/bin/sbt.bat`. A local ignored `python/.venv-test` junction points to the main checkout's existing test environment.
- Worker Python: `C:/Users/Mizushima/dev/stablediffusion-nui/python/.venv-smoke/Scripts/python.exe`; official MCP SDK Python: `C:/Users/Mizushima/.codex/worktrees/affc/stablediffusion-nui/outputs/mcp-client-venv/Scripts/python.exe`.
- Models: `D:/stable-diffusion-webui-reForge/models/Stable-diffusion`; `animekawa_v10` 512x512 and `waiIllustriousSDXL_v170` 832x1216. Test LoRAs `20_yui`, `20_hinata` are in `D:/stable-diffusion-webui-reForge/models/Lora`.
- `scripts/dev/check_optimization_mcp.py --help` describes the SDK harness. Use the exact jar just built from the candidate source and unique output directories; run one model at a time under the lock, inspect contact sheets, and retain receipt/logs and hash. `--dry-run` validates wiring only, not optimization or FreeU refusal. The harness currently checks DeepCache, not fp8/ControlNet or VAE peak, so extend it or use a separate appropriate real-client check before claiming those.
- For the stopped Scala run, the observed PowerShell is a retained `LocalPreviewFiles.WindowsProbe` helper; its presence does not prove the hang. Run `core/testOnly *ModelCardScannerNativeBulkSuite *ModelCardScannerSuite` in isolation when a slot is available, check timings/failures, then arrange a complete suite/assembly. If sbt reports cache hits, distinguish them from freshly executed tests.

Ignored `outputs/` hold local evidence and models/configuration references and are not committed. Record fresh hashes and preserve receipts when handing work to another machine. Never publish a release or count #143 closed solely from this handoff.

## Later fp32 fallback change

After the real MCP runs, a fake-VAE regression demonstrated that a half-precision plan could fit while its fp32 retry after non-finite output would not; the retry did not re-plan. `python/sdfx/vae_memory.py` now re-plans before retrying. See section 12 of `optimization-verification.md` for RED/GREEN and exact verification limits. The earlier real-model receipts do not verify this change: run real GPU fallback and full regression on the final candidate before integration.

The prepared `scripts/dev/check_vae_fp32_fallback_real.py --output <new directory>` exercises `Engine._decode` on real generated SDXL latents, comparing real fp32 reference and fp32 retry images. It injects a non-finite fp16 result deliberately and applies an emulated per-process VRAM cap; neither is evidence of a natural model failure or a physical small GPU. It refuses a held shared lock, records its own owner marker and receipt (including plans, dtype/tiling calls, source hash, model identifier, peak allocated memory and image difference), and must be followed by image inspection. Only `--help` and Python syntax were checked; it has not yet run on GPU. Coordinate before running, preserve a fresh output directory and use `.venv-smoke` with the worktree source. Follow this with actual app/MCP and package tests: this in-process harness is not those tests.

The prepared fallback harness subsequently passed its real SDXL in-process run with deliberately injected NaN and emulated VRAM cap, and the image was inspected; see section 13 of `optimization-verification.md` for receipt and exact limits. Physical low-VRAM hardware and SDK/package fallback remain unverified.

## Merge of main `c4abab3`

Main `c4abab32d3150ca979b4e440db602990fabcfd8b` was merged into this branch at `114ffbf0701886673d2426a55bf5c165c0ab44e6` without textual conflicts. This brings in negative suggestions and #2 Aesthetic Gradients GUI/MCP/provenance; main itself was not changed. Static inspection confirmed both new aesthetic metadata handling and DeepCache metadata candidate, both load-model optimization fields and aesthetic worker lifecycle remain present. DeepCache plus Aesthetic Gradients is deliberately unsupported and is refused; the CPU `test_refusals` case includes this combination.

After the merge, seven targeted Python files covering DeepCache, VAE planning/offload and #2 aesthetic gradients/publication/provenance passed 142 tests with one CUDA-only skip (`outputs/codex-optimization-main-c4-focused.log`). The later four-case `test_refusals` run passed. `vae_memory.py` retains SHA-256 `6160e927a7ca9dc9d8a9af263696bc6d97f31a203057b07ef008381519cd55e6`, the exact source used by the earlier injected-NaN SDXL in-process test, but the merged application and package are new integration targets, not covered by the earlier jar receipts.

Before integration, recheck main for later changes; arrange full Python and full Scala with JDK21 and an uncontended sbt slot, including AestheticEmbeddingLocatorSuite, MCP metadata/policy and GUI suites. On real SD1.5/SDXL run official SDK baseline and DeepCache checks, #2 embedding creation plus PNG hash replay, the DeepCache/aesthetic refusal and recovery, and inspect images. Repeat VAE fallback through the actual updated app/client or explicitly document if only the in-process injected-fault test is available; verify extracted package, notices and hashes. Respect shared GPU lock and do not treat a cached sbt result as fresh all-suite evidence. Physical low-VRAM hardware remains untested.

## Merge of main `2284f49` and handoff `6ed1f22`

Main `2284f49e01582ae1021dc574d2dd9d1703319285` (CreaPrompt) was merged as `8d4b3b3d8dbc1f580ead3dbb54c80c66d01f44dd` without textual conflicts; both CreaPrompt and DeepCache registrations remain. Main `6ed1f2254ab28f758ab05f01bac16aeeabaef6a9` was then merged as `c0b1049` and differs from `2284f49` only in `HANDSOFF.md`. Do not replace newer main files when integrating. `git diff --check` against the pre-CreaPrompt parent reports trailing whitespace/blank lines in imported licensed CSV data; these bytes are intentionally preserved as upstream data rather than silently rewritten.

On the merged product code, the full Torch CPU suite exited 0 with 3014 passed / 3 skipped (`outputs/optimization-main-2284-python.log`, counts from pytest progress). Use `CUDA_VISIBLE_DEVICES=-1` and worktree-absolute `PYTHONPATH` so CPU-only fixtures and subprocess imports use the correct environment. Scala full regression/assembly and packaged real-model checks of the merged tree remain pending. The existing jar predates this merge and must not be used as evidence for it.

The first full Scala attempt after this merge was interrupted after over two minutes without new log output (`outputs/optimization-main-2284-scala.log`), before assembly. No cause has been confirmed; this is not a passing suite. When the shared slot returns, run relevant focused suites and `ui/assembly` for the current merged tree, then separately diagnose and complete the full regression. Do not use an earlier jar to represent this tree.

Two interrupted full Scala runs stopped after roughly two minutes without new output, but no stalled suite has been identified. This does not distinguish a hang from a legitimately long test. `core/src/test/scala/sdfx/core/ModelCardScannerNativeBulkSuite.scala` contains two cases that each create 4097 files and each permits five minutes; neither suite's results appeared in the two partial logs. When the sbt slot is available, run `core/testOnly *ModelCardScannerNativeBulkSuite` alone with sufficient time (up to each case's five-minute limit), record process/timing and temp-directory progress, then reassess. The last printed suite differs across the two full logs and is not proof of the active suite.

Isolated `ModelCardScannerNativeBulkSuite` passed 2/2 in 341 seconds (individual cases 163.642s and 175.329s; `outputs/optimization-bulk-isolated.log`). New fixture creation and sustained probe CPU showed the suite was progressing while its output remained silent. Therefore do not interrupt a full run solely after two minutes without output; allow at least this duration and track the actual process. The earlier interrupted full runs still have no confirmed active-suite diagnosis.


## Integrated verification update (2026-11-03)

Full Scala test and assembly exited 0 (985 reported, 984 passed, one skipped; cache 47%); native bulk was separately run 2/2. Full Python was 3014 passed, 3 skipped. Built jar `e134d3ac2a5c56d4141cdbb739596e8d42a9560dcd0b0631f9be1e1d55714dff`; extracted test ZIP `e7893ff1e6ca58e2f0422810ea71a4fedd46df846572a8cbbe28338b3d753000` has 342 matching tracked payload files and no missing/differences. Official SDK through extracted `SDNUI.exe --mcp` passed both real SD1.5 and SDXL with DeepCache, LoRA, hires, metadata replay and compatibility refusal; output images viewed. See `docs/optimization-verification.md` for evidence and limits. Physical low-VRAM hardware remains untested, and allocator cap plus NaN injection must not be described as a natural low-VRAM failure. No issue was closed or package published.


## Post-review changes require renewed GPU verification

The earlier packaged GPU results precede changes to VAE auto scope, fp8 auto preflight and DeepCache cleanup. VAE auto now affects decode only, not img2img/inpaint encode; auto+fp8 conservatively checks available VRAM before unloading the existing pipeline, preserving it on refusal but potentially rejecting a request that would fit after an explicit unload. DeepCache partial-install and prior forward restoration were corrected. Full Python CPU regression passed 3018 / 3 skipped, and subsequent focused tests including an additional VAE failure-restoration test passed. Rebuild and recheck actual packaged app/MCP, both model families, image inputs, fallback and compatibility on GPU before treating prior GPU evidence as current; physical low-VRAM remains untested.


Post-review real SD1.5/SDXL packaged launcher and SDK tests now passed DeepCache combinations and img2img/inpaint under a planner-only cap, with images viewed. SDXL in-process injected-NaN fallback also passed with an emulated allocator cap and viewed output. See final section of `optimization-verification.md` for exact limits and hashes. This used pre-#24 jar and source `6f2734c`; merge current main and retest the resulting candidate before integration. Physical low-VRAM, GUI and fp8 application path remain unverified.


Main `9876bda` merged at `61fa1a0`. JDK 21 Scala test and assembly passed; 43 tests executed and task cache 90%. Latest test ZIP `4dabfd9a66dc928439865688f90884106397085061b55829e85f3bc651e0c5b1` contains jar `277aea203a5a44a39928d599c1293286ce7ddb8761217c1cffbc89aa494d7c9c`; payload hashes match. Packaged launcher with official SDK passed both model families for DeepCache and planner-capped img2img/inpaint; viewed inpaint images were intact. The cap affects planner calculation only, not the physical device. See final verification section. GUI, fp8 application path, actual ControlNet integration and physical low-VRAM device remain unverified.


SD1.5 real canny ControlNet and DeepCache refusal/recovery passed through main jar and official MCP SDK, with and without fp8 requested. Test-only instrumentation counted 468 float8 and 218 fp16 U-Net parameters for fp8 vs 686 fp16 normally; images were viewed. Details and limitations in the last verification section. No SDXL ControlNet model was found in inspected locations; SDXL ControlNet, GUI real operation, SDXL app fp8 and physical low-VRAM remain unverified.


Real JavaFX SettingsDialog/MainWindow harness now passed SD1.5 and SDXL real-model fp8-requested generation, DeepCache checkbox enable/disable and exact recovery; both cached images and settings screenshot were viewed. It fires controls programmatically: Cua could not inject a click or capture the window because monitor geometry was unavailable, so physical mouse operation is not verified. See final verification section for command/evidence and remaining limits.


SD1.5 real MainWindow plus AppServices now also passed the auto+fp8 load-refusal and existing-model recovery scenario with a deliberately tiny planner budget; baseline and recovery were RGB-identical and the image was viewed. This is a planner simulation, not physical low-VRAM hardware, and control activation was programmatic. See the final verification section.


A real JavaFX SD1.5 test reproduced rejected auto+fp8 checkpoint switches leaving a wrong GUI selection and breaking the next generation. A narrow confirmed pre-free refusal recovery now restores the retained checkpoint, with checkpoint/style selection epoch guards; normal and VAE-interleaved rejection both passed actual GPU generation with a planner-only simulated cap. The worker request dispatch order is not serialized by the UI guard, and style success can still overwrite a newer VAE selection; these are existing unverified concurrency risks. See the final section of `optimization-verification.md`.
