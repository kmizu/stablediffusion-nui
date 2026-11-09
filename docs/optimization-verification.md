# Low VRAM / speed optimizations (issue #143) - record, v0.14 (2026-11-10)

Environment: RTX 3090 (24 GB), Windows 11, scala-torch 0.1.3-gh34, extracted release ZIP, MCP over stdio, Euler a, 20 steps, seed 173,
second (warm) run, GPU memory = peak `memory.used` minus the idle baseline (other programs, about 2.5 GB).

## Inventory (what exists in the native engine)

| Item | State | Measurement |
| --- | --- | --- |
| fp16 execution (weights, activations, tensor-core GEMM) | exists, now selectable (v0.13) | SD1.5 4.4 s vs 9.1 s fp32; SDXL 17.1 s vs 39.2 s |
| VAE tiling (`vae_tiling`) | exists | SDXL 1536x1536: job memory 10.17 GB without, 10.16 GB with: **no gain**, because the peak is the U-Net; left off by default |
| U-Net weight offload (`SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS`) | exists | SDXL 1024: **146 s per image and a higher peak (14.3 GB)**: unusable, the weights are re-uploaded on every use; left off, not recommended |
| DeepCache hook | exists (SDXL 0.53 -> 0.22 s/step measured earlier) | quality trade-off, opt-in plugin |
| Attention score workspace | **new** (v0.14) | below |
| CUDA context / cuBLAS handle reuse | **new** (v0.14) | below |

## Where the time went

Warm SD1.5 512x512: 1 step took 4.8 s and 30 steps 7.6 s, i.e. about 4.7 s of the 7.7 s (20 steps) were fixed cost per image, not sampling.
A generation opens about six CUDA sessions (three text-encoder passes, the U-Net, the VAE). Opening and closing one cost about 250 ms
(`cuDevicePrimaryCtxRetain/Release` 90 ms, cuBLAS create/destroy 150 ms, PTX modules 2 ms). Per kernel launch overhead (23 us) was not the problem.

## Changes

1. **Context and cuBLAS keeper** (`CudaContextKeeper`): the primary context and one cuBLAS handle per device and thread stay alive between back-to-back
   sessions and are released after `SCALATORCH_CUDA_KEEP_CONTEXT_SECONDS` (default 20, 0 = off) without a session, so the device memory is still returned when idle.
   Session open+close: 250 ms -> about 10 ms.
2. **Chunked half attention**: heads and, when a single head does not fit, queries are processed inside a scratch budget
   (`SCALATORCH_CUDA_ATTENTION_SCRATCH_MB`, default 256) instead of requiring the whole score matrix to fit a workspace limit.
   Very large images no longer fail with "attention score workspace limit exceeded" and need less memory.

## Results (final ZIP, same machine; "before" = v0.13)

| Case | Time before | Time now | Job memory before | Job memory now |
| --- | --- | --- | --- | --- |
| SD1.5 512x512 fp16 | 7.7 s | **4.4 s** (-43 %) | about 3.3 GB | 3.3 GB |
| SDXL 1024x1024 fp16 | 20.3 s | **17.1 s** (-16 %) | about 8.1 GB | 8.0 GB |
| SD1.5 512x512 fp32 | 11.4 s | 9.1 s | 5.1 GB | 5.1 GB |
| SDXL 1024x1024 fp32 | 45.4 s | 39.2 s | 13.2 GB | 13.2 GB |
| SDXL 1536x1536 fp16 | 38.1 s | 36.6 s | 10.2 GB | **8.5 GB** (-1.7 GB) |
| SD1.5 4201x256 (generated 4208x256) | failed (attention limit) | **works** (19 s wall) | - | - |
| SD1.5 1280x1280 | - | works (29 s wall) | - | - |

Images were viewed (SD1.5 fp16/fp32 near identical, SDXL, SDXL 1536, the 4208x256 strip and the 1280x1280 picture). Chunking is exact: a test compares a
1 KiB-scratch run (9 query blocks per head) with the unchunked result (max abs difference < 1e-3).

## Tests

scala-torch `ffm` suite with the CUDA gate: 72 pass (new: chunked attention equals unchunked). The `models` suite has 47 failing reference-parity tests with
fp16 as default (tolerances written for fp32) and 7 that fail even with `SCALATORCH_CUDA_FP16=0`; the same 7 fail identically without these changes
(checked by running the suites on the unmodified tree), so nothing regressed. NUI: `scalaTorchEngine`, `engine`, `mcp`, `ui` suites pass.

## Not done / not claimed

- A real low-VRAM mode for 6-8 GB cards (SDXL needs about 8 GB for the job): needs block-wise weight streaming with pinned fp16 host copies; the current
  offload is not usable. Not implemented.
- Keeping the U-Net weights resident between generations (would save another 1.2 s per image on SD1.5): needs allocation ownership outside the session.
- fp8 weight storage, TeaCache, tiled generation of huge images, automatic VRAM-based choice of settings: not implemented.
