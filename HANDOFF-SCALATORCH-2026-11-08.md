# scala-torch handoff (2026-11-08)

## Goal
NUI's selling point is zero Python dependency (scala-torch worker on the JVM). Finish the scala-torch work, fix the SDXL load bug in scala-torch, deploy a new scala-torch release to Maven Central.

## Done
- `ScalaTorchLaunchSuite` failure = environment. The worker needs **Java 25**. Use `JAVA_HOME=C:\dev\Java\jdk-25.0.5.0.2` for sbt and `ui/assembly`. Suite passes 5/5.
- NUI `main` `cf802f5`: `LoadModelWire.json` omits default-valued `vae_slicing`, `fp8_storage`, `vae_auto`, `vae_fp16` (worker 0.1.0 rejects unknown load params). `LoadModelWireSuite` added. `engine/testOnly *`: 323 tests, 0 failed. `HANDSOFF.md` has the 2026-11-08 section.
- Real SD1.5 (`animekawa_v10`, 512px, 20 Euler a, seed 173) on scala-torch through MCP on the RTX 3090: coherent image. Needed `SDFX_SCALATORCH_UNET_MAX_BYTES` / `SDFX_SCALATORCH_VAE_MAX_BYTES` = 8 GiB and `SDFX_SCALATORCH_TOKENIZER=D:/stable-diffusion-webui-reForge/ldm_patched/modules/sd1_tokenizer`.
- Python backend, SDXL `catCarrier_v60` + LoRA `20_cat-hinomina` 0.9, "cute girl hinomina", 1024px, seed 173: LoRA clearly changes the image (compare `outputs/st-cat-hinomina-py` vs `outputs/st-cat-hinomina-py-nolora`). Likeness to the character not judged. Not tried on scala-torch yet.

## scala-torch bug (root cause found)
- Repo: `C:\Users\Mizushima\dev\scala-torch` (origin `git@github.com:scala-ml/scala-torch.git`, Maven group `com.github.kmizu`, BSD-3-Clause).
- `waiIllustriousSDXL_v170.safetensors` stores `conditioner.embedders.0...position_ids` as **F16** (SD1.5 stores I64). `PretrainedClip.loadFromView` called `readLong`, which only accepts I64, so loading failed with `dtype F16 is unsupported by this typed read`.
- Fix on branch `fix/clip-position-ids-float-dtype` (uncommitted in the working tree): `models/src/main/scala/scalatorch/models/PretrainedClip.scala` accepts I64, or F16/BF16/F32 compared with `i.toFloat`; `PretrainedClipSuite` fixture got `positionDtype` and new cases. `models/testOnly scalatorch.models.PretrainedClipSuite scalatorch.models.SingleFile*`: 41 passed, 0 failed.
- With the patched classes first on the classpath the SDXL model **loads**. Generation then hit, in order: "CUDA session allocation budget exceeded" (8 GiB) → "attention score workspace limit exceeded" (`MAX_ATTENTION_SCORE_ELEMENTS=134217728`). Latest run (U-Net 16 GiB, VAE 6 GiB, score elements 536870912) was still running at 14 GiB VRAM / 70% GPU: check `outputs/st-sdxl-native.log` for `isError`, and look at `outputs/st-sdxl-native/images/txt2img/*/*.png`.
- Patched classpath file: `C:\Users\MIZUSH~1\AppData\Local\Temp\claude\ecbf04dd658613e3\2fe541b7-5a4e-4370-8d1b-08ac891a6117\scratchpad\patched.cp` (patched `scala-torch-models/classes` dir, then `target/stablediffusion-nui.jar`), passed via `SDFX_SCALATORCH_CLASSPATH_FILE`.
- Test harness: `...\scratchpad\mcp_gen.py` (args `--backend scala-torch --checkpoint NAME --prompt ... --side --steps --sampler --out`; env `TOK` = tokenizer dir). GPU lock: `mkdir outputs/.gpu.lock` / `rmdir`.

## Maven Central deploy (requested by the user; not done)
- User said: after fixing, deploy to Maven Central; signing key is in `C:\Users\Mizushima\Dropbox\release-signing-key` (`secret-key.asc`, `pgp-passphrase.txt`, `README.txt`; fingerprint E0FA 0673 79B9 1CFA 1681 54C9 C8BF C42B 047C B4C1). Never print the key or passphrase.
- Flow in `scala-torch/docs/PUBLISHING.md`: `publishSigned` then `sonaUpload` / `sonaRelease` (sbt 2). `build.sbt` reads the passphrase from `~/.sbt/gpg/passphrase.txt` or `PGP_PASSPHRASE`, and Central credentials from `~/.sbt/sonatype_central.properties` (`user`, `password`) or `SONATYPE_USERNAME`/`SONATYPE_PASSWORD`. `useGpg=false`; gpg is not on this machine, so the armored secret key must be loaded for sbt-pgp.
- **Blocker:** no Sonatype Central token on this machine (no `~/.sbt/sonatype_central.properties`, no env vars) and none in the Dropbox key folder. Ask the user for it (Central Portal user token).
- Plan: bump `ThisBuild / version` to `0.1.1`, finish the SDXL end-to-end check first (a Central release is immutable), commit on the branch, signed publish of all seven modules, verify resolution from Central, then change NUI `build.sbt` `scala-torch-models` to `0.1.1`, rebuild, rerun the SDXL MCP check, update docs/HANDSOFF.
- `docs/PUBLISHING.md` in scala-torch is stale ("0.1.0 prepared locally, not published"); 0.1.0 is on Central (published 2026-11-07).

## New instruction from the user
- The MCP **client** must also run on the JVM (they want no Python anywhere). Python is only the test client now; the engine is already the Java worker (`NativeWorkerMain`). Replace `scripts/dev/check_mcp_client.py` and `mcp_gen.py` with a JVM client built on the official MCP Java SDK. Not started.

## Open items
1. Finish SDXL on scala-torch with the patched worker (raise budgets if needed; check quality by eye).
2. Commit the scala-torch fix, deploy to Central (needs the token).
3. Bump NUI to the new scala-torch version and re-verify.
4. JVM MCP client.
5. Native LoRA on a real SDXL checkpoint (`catCarrier_v60` + `20_cat-hinomina`) is unchecked.
6. v0.07 release; #62 remainder; unmerged branches #26/#27/#143 (see HANDSOFF.md).

## Update 2026-11-08 04:58 (after compaction)
- scala-torch 0.1.1 is **published on Central** (PUBLISHED; CLIP F16 position_ids fix + VAE feature budget 134217728 -> 536870912). NUI `build.sbt` still on 0.1.0: bump, rebuild jar, rerun checks (not done).
- SDXL waiIllustrious native 1024px 20 steps: image OK (looked at it), 8 min 44 s. Profile (`SCALATORCH_CUDA_PROFILE=1`, 2 steps) showed GEMM was only ~18%: groupnorm_stats (1 thread per group, FP64 serial) 17 s, attention ~13 s, geglu (FP64 erf) 5 s, transpose 4 s, weight upload 30 s (pageable HtoD).
- Branch commit in scala-torch (unreleased, after 0.1.1): parallel `groupnorm_stats_par`, optional cuBLAS SGEMM (`Cublas.scala`, CUDA_PATH lookup, `SCALATORCH_CUDA_CUBLAS=0` off, `SCALATORCH_CUDA_TF32=1` on), `geglu_fast` (FP32 erf, used by `CudaDiffusionRuntime`). Summed kernel time for 2 steps: 55 s -> 17.5 s.
- Pre-existing GPU failures (same on previous commit): CudaSlicedAttentionSuite x4, CudaDenseAttentionSuite x1, CudaOpsSuite dot products x1.
- Next (user asked): FP16 compute; then attention via cuBLAS strided-batched GEMM (+softmax kernel) or flash-style; transpose/broadcast_add fusion; pinned-memory weight upload. Fused attention is opt-in via `SCALATORCH_CUDA_FUSED_ATTENTION=1` + `SCALATORCH_CUDA_MULTI_QUERY_ATTENTION=1` (faster than the default path; worker should enable it). 
- Env for 1024px SDXL: UNET_MAX_BYTES 16 GiB, VAE_MAX_BYTES 12 GiB, MAX_ATTENTION_SCORE_ELEMENTS 536870912. `patched.cp` now also lists scala-torch ffm classes + `ffm/src/main/resources`.
- Test harness note: sbt.bat breaks on args with spaces; put `set` commands in a temporary `*.sbt` file. Release key settings go in `zz-local-pgp.sbt` (deleted after use); Central token and passphrase were copied into `~/.sbt` (never print).

## Update 2026-11-08 (speed round 2, scala-torch 0.1.2)
- SDXL waiIllustrious 1024px, 20 steps, RTX 3090 (shared): 524 s (0.1.1) -> 157 s. Image checked by eye (coherent, same composition as before).
- What changed in scala-torch (commits after 0.1.1, released as 0.1.2):
  - CLIP text layers run on CUDA (`ClipCuda`; the worker enables it when the U-Net device is cuda; `SCALATORCH_CLIP_DEVICE=cpu` disables). 2-step run 86 s -> 56 s. New kernels `gelu_erf`, `quick_gelu`.
  - CPU matmul is cache-friendly and multi-core (bit-identical results).
  - Attention uses cuBLAS strided-batched GEMM plus block-parallel `softmax_par` (default; `SCALATORCH_CUDA_CUBLAS_ATTENTION=0` disables; scratch capped at 2 GiB, otherwise the old sliced kernels run).
  - Linear layers call cuBLAS with a transposed B operand (no per-call weight transpose). `transpose5` and `broadcast_add5` take geometry as kernel parameters.
  - FP16 (`SCALATORCH_CUDA_FP16=1`, opt-in): Linear weights are converted to Float16 on first use, activations are cast per call, `cublasGemmEx` accumulates in Float32. Numerically fine and the image looks the same, but it is slower right now (210 s vs 157 s) because every call casts and allocates. Real gains need FP16 activations end to end (not done). It saves no memory yet: the Float32 weights stay resident.
- GPU tests: the 6 old failures were stale expectations (bit-exact comparisons, 8-byte score scratch, profile-gated label); fixed in `ce09111`. `ffm/test`: 60 tests, 0 failed. `models/test` has 6 unrelated failures (BetaAndDynThreshSuite, EulerConfigSuite, 4x missing `tiny-sd-pipe` fixture).
- Weight upload is only ~4 s per load, so pinned memory was not worth doing. The rest of the fixed cost per run (~40 s) is model read/convert on the CPU.
- Remaining per-step time (~7 s/step): `transpose5` (5212 calls), cuBLAS attention scores, `broadcast_add5`, GroupNorm, per-launch synchronize overhead. reForge is still much faster; FP16 activations, fused elementwise kernels and fewer synchronizations are the next steps.

## Update 2026-11-08 (release state and verification)
- NUI `build.sbt` now depends on scala-torch 0.1.2 (published on Central, resolved from Central, jar rebuilt). Verified through MCP with the jar only (no classpath override): SDXL waiIllustrious 1024px 20 steps 163 s, SD1.5 animekawa_v10 512px 20 steps 51 s; both images looked coherent.
- SDXL `catCarrier_v60` + LoRA `20_cat-hinomina` 0.9 on scala-torch: fails on 0.1.2 with "LoRA factors exceed allocation budget" (hard-coded 100M-element limit; the LoRA file is 681 MB). Fixed in scala-torch commit "Raise the LoRA allocation budget..." (limit 536870912, version 0.1.3). With the local classes it generated in 246 s; image coherent, likeness to the character not judged.
- **0.1.3 is NOT published.** Central refused it: "your organization has reached its monthly release size limit. Grace period ended 2026-11-04. Usage resets 2026-12-01." Publish 0.1.3 on or after 2026-12-01 (signed staging is in `scala-torch/target/sona-staging`; remove stale version dirs there first, or sonaRelease re-uploads old versions and fails), then bump NUI to 0.1.3. Until then a large SDXL LoRA needs the local classpath (`SDFX_SCALATORCH_CLASSPATH_FILE`).
- Still open: JVM MCP client (replace `mcp_gen.py` / `scripts/dev/check_mcp_client.py` with the official MCP Java SDK), FP16 activations end to end, fewer per-launch synchronisations, scala-torch `docs/PUBLISHING.md` is stale, 6 unrelated `models/test` failures (BetaAndDynThreshSuite, EulerConfigSuite, 4x missing tiny-sd-pipe fixture).

## Float16 U-Net and the snapshot dependency (2026-11-08, late)

- scala-torch 0.1.3 is blocked on Maven Central by the monthly release-size limit (resets 2026-12-01; a request to
  central-support@sonatype.com is drafted but not sent). NUI therefore resolves `0.1.3-SNAPSHOT` from
  `https://central.sonatype.com/repository/maven-snapshots/` (see `build.sbt`). Switch back to `0.1.3` once it is released.
  Coursier caches snapshots; to pick up a republished snapshot delete
  `%LOCALAPPDATA%\Coursier\Cache\v1\https\central.sonatype.com\repository\maven-snapshots\com\github\kmizu` and rebuild.
- The U-Net now runs in Float16 by default (`SCALATORCH_CUDA_FP16=0` restores Float32): `__half` kernels written in CUDA C
  (`ffm/src/main/cuda/half.cu`, compiled to `half.ptx` with `build_ptx.py` through NVRTC), cuBLAS half GEMM for linear,
  convolution and attention, a caching device allocator, and no per-launch synchronization
  (`SCALATORCH_CUDA_SYNC=1` re-enables it for debugging).
- Verified through the NUI MCP with the assembled jar (RTX 3090, shared): SDXL 1024px, 20 steps, 0.22 s per U-Net step
  (was about 7 s), 37 s in total including about 33 s of model load; SD1.5 512px 20 steps 25 s in total;
  SDXL + LoRA catCarrier_v60 + 20_cat-hinomina (0.9) 49 s in total. Images are coherent and the asynchronous run is
  pixel-identical to the synchronous one.
- Open: CLIP and VAE are still Float32; the model load (CPU read and convert, about 30 s) is the remaining fixed cost;
  scala-torch commits 7ca120d and 3fc51e6 are local and not pushed.
