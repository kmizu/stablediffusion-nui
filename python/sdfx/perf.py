"""Low-VRAM and speed options of the engine itself (not an A1111 extension port).

Load-time options (LoadModelRequest, Settings > Generation in the app):

* ``vram_mode = "auto"``  picks gpu / model_offload / sequential_offload from the free VRAM and the checkpoint size
  (`resolve_vram_mode`; the heuristic is calibrated by docs/optimization-verification.md);
* ``vae_slicing``         decode a batch one image at a time (lower peak VRAM for batch > 1, same pixels);
* ``vae_auto`` / ``vae_fp16``  slicing / tiling only when the decode would not fit, and an fp16 SDXL decode: vae_memory.py;
* ``vae_tiling``          existing: tile the VAE decode of images larger than the VAE tile (changes pixels slightly);
* ``fp8_storage``         keep the U-Net weights in float8 and upcast layer by layer (diffusers layerwise casting).

channels_last, TF32 and cudnn.benchmark were measured and dropped (no gain on this card).
Each of them defaults to off: docs/optimization-verification.md has the measured VRAM, time and image difference.
The per-job DeepCache hook (`deepcache`) lives at the bottom of this file.
"""
from __future__ import annotations

import logging
import os
from typing import Any, Optional

from .protocol import INVALID_PARAMS, UNSUPPORTED, EngineError

log = logging.getLogger("sdfx.perf")

VRAM_MODES = ("gpu", "model_offload", "sequential_offload")
VRAM_MODES_OR_AUTO = ("auto",) + VRAM_MODES
FP8_DTYPES = ("float8_e4m3fn",)

# Denoising-loop memory (MiB) on top of the resident weights, with the VAE decode left to vae_memory (auto tiling):
# measured 620 MiB (SD1.5 512x512) and 380 MiB (SDXL 832x1216) plus headroom; scaled by pixel count.
WORK_MB = {"sd15": 900, "sdxl": 1800}
# With model offload only the U-Net is resident while denoising and the VAE alone while decoding (measured SDXL peak 5371 MiB).
WORK_OFFLOAD_MB = {"sd15": 500, "sdxl": 800}
WORK_BASE_PIXELS = {"sd15": 512 * 512, "sdxl": 832 * 1216}
SAFETY = 1.15


def checkpoint_weights_mb(path: str) -> float:
    try:
        return os.path.getsize(path) / 2**20
    except OSError:
        return 0.0


def resolve_vram_mode(requested: str, *, weights_mb: float, free_mb: float, kind: str = "sd15",
                      pixels: Optional[int] = None) -> tuple[str, str]:
    """Return (mode, reason). Explicit modes pass through; ``auto`` picks the fastest mode that fits.

    gpu             all weights resident: needs weights + working memory;
    model_offload   one component (the U-Net, about 70-80 % of the weights) plus the working memory;
    sequential_offload  one layer at a time: always fits, very slow.
    """
    if requested != "auto":
        return requested, "requested"
    family = "sdxl" if kind.startswith("sdxl") else "sd15"
    scale = max(1.0, (pixels or WORK_BASE_PIXELS[family]) / WORK_BASE_PIXELS[family])
    work = WORK_MB[family] * scale
    work_offload = WORK_OFFLOAD_MB[family] * scale
    if free_mb >= (weights_mb + work) * SAFETY:
        return "gpu", f"{free_mb:.0f} MiB free covers weights {weights_mb:.0f} + working {work:.0f} MiB"
    if free_mb >= (weights_mb * 0.8 + work_offload) * SAFETY:
        return "model_offload", f"{free_mb:.0f} MiB free covers the U-Net {weights_mb * 0.8:.0f} + working {work_offload:.0f} MiB only"
    return "sequential_offload", f"{free_mb:.0f} MiB free is below the U-Net plus working memory"


def validate_load(req) -> None:
    if req.vram_mode not in VRAM_MODES_OR_AUTO:
        raise EngineError(INVALID_PARAMS, f"vram_mode must be auto|gpu|model_offload|sequential_offload (got {req.vram_mode})")
    if req.fp8_storage and req.vram_mode not in ("gpu", "auto"):
        raise EngineError(UNSUPPORTED, "fp8_storage needs the 'gpu' VRAM mode (offload modes move the weights)")
    if req.fp8_storage and req.dtype == "fp32":
        raise EngineError(UNSUPPORTED, "fp8_storage upcasts to the model precision: use fp16 or bf16, not fp32")


def apply_vae(vae, req) -> None:
    if vae is None:
        return
    if req.vae_tiling:
        vae.enable_tiling()
    if req.vae_slicing:
        vae.enable_slicing()


def apply_unet(pipe, req, dtype) -> None:
    """Weights layout / storage of the U-Net, after the pipeline is on the device."""
    import torch
    unet = getattr(pipe, "unet", None)
    if unet is None:
        return
    if req.fp8_storage:
        if not hasattr(unet, "enable_layerwise_casting"):
            raise EngineError(UNSUPPORTED, "fp8_storage needs diffusers with layerwise casting (0.33+)")
        unet.enable_layerwise_casting(storage_dtype=torch.float8_e4m3fn, compute_dtype=dtype)


def describe(req, mode_reason: str = "") -> dict:
    """Effective options for the load_model reply and the System Info page."""
    return {"vram_mode": req.vram_mode, "vram_reason": mode_reason, "vae_tiling": bool(req.vae_tiling),
            "vae_slicing": bool(req.vae_slicing), "fp8_storage": bool(req.fp8_storage),
            "vae_auto": bool(req.vae_auto), "vae_fp16": bool(req.vae_fp16)}
