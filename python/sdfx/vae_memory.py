"""VAE decode memory: automatic slicing / tiling when the decode would not fit, and the optional fp16 decode.

The VAE decode is the peak of a generation on modern checkpoints (SDXL: about 5 GB of fp32 activations for one
832x1216 image on top of the 6.7 GB of weights). `vae_scope` surrounds one pipeline call (or one explicit decode):

* ``vae_auto``  (default on) looks at the free VRAM and, only when the decode would not fit, turns on slicing (a batch
  is decoded one image at a time: same pixels) or tiling with a tile size chosen to fit (small seam-blending
  differences). When the decode fits nothing changes, so images stay byte-identical;
* ``vae_fp16``  (default off) decodes force-upcast VAEs (SDXL) in fp16 instead of fp32 and falls back to fp32 for that
  decode when the output is not finite. Halves the decode memory; the pixels differ by a fraction of a percent.

Both only change the VAE for the length of the scope. SDFX_VRAM_BUDGET_MB caps the VRAM the plan may count on.
"""
from __future__ import annotations

import logging
import os
from contextlib import contextmanager

log = logging.getLogger("sdfx.vae_memory")

# Measured decode working memory per output pixel and image (docs/optimization-verification.md).
BYTES_PER_PIXEL = {"half": 2400, "fp32": 4900}
SAFETY = 0.85
RESERVE = 384 * 2**20   # the fp32 copy of the VAE weights, cuDNN workspace and allocator fragmentation
MIN_TILE, MAX_TILE = 256, 1024


def free_bytes() -> int:
    """VRAM a decode can still use: the device's free memory plus what this process caches but does not hold.
    SDFX_VRAM_BUDGET_MB caps the process (testing a small GPU, or sharing the card)."""
    import torch
    free = torch.cuda.mem_get_info()[0] + (torch.cuda.memory_reserved() - torch.cuda.memory_allocated())
    budget = os.environ.get("SDFX_VRAM_BUDGET_MB", "").strip()
    if budget:
        try:
            free = min(free, int(float(budget) * 2**20) - torch.cuda.memory_allocated())
        except ValueError:
            pass
    return max(0, int(free))


def decode_plan(*, width: int, height: int, batch: int, fp32_decode: bool, free: int, tile_default: int = MAX_TILE) -> dict:
    """What the VAE decode needs to fit into `free` bytes: nothing, slicing, or tiling (+ slicing for a batch)."""
    bpp = BYTES_PER_PIXEL["fp32" if fp32_decode else "half"]
    per_image = width * height * bpp
    budget = max(0.0, free * SAFETY - RESERVE)
    if per_image * batch <= budget:
        return {"action": "none", "per_image": per_image}
    if batch > 1 and per_image <= budget:
        return {"action": "slice", "per_image": per_image}
    side = int((budget / bpp) ** 0.5) // 64 * 64
    tile = max(MIN_TILE, min(tile_default, side))
    return {"action": "tile", "tile": tile, "slice": batch > 1, "per_image": per_image,
            "fits": tile * tile * bpp <= budget}


class _VaeState:
    def __init__(self, vae):
        self.vae = vae
        self.tiling = bool(getattr(vae, "use_tiling", False))
        self.slicing = bool(getattr(vae, "use_slicing", False))
        self.sizes = (getattr(vae, "tile_sample_min_size", None), getattr(vae, "tile_latent_min_size", None))

    def restore(self):
        vae = self.vae
        (vae.enable_tiling if self.tiling else vae.disable_tiling)()
        (vae.enable_slicing if self.slicing else vae.disable_slicing)()
        if self.sizes[0] is not None:
            vae.tile_sample_min_size, vae.tile_latent_min_size = self.sizes


def set_tile(vae, tile: int) -> None:
    factor = 2 ** (len(vae.config.block_out_channels) - 1)
    vae.tile_sample_min_size = tile
    vae.tile_latent_min_size = tile // factor
    vae.enable_tiling()


@contextmanager
def vae_scope(engine, vae, width: int, height: int, batch: int, ctx=None):
    """Around one pipeline call or decode; yields the plan that was applied (None when the scope is inactive)."""
    opts = engine.load_opts
    if vae is None or not engine.cuda or not (opts.vae_auto or opts.vae_fp16):
        yield None
        return
    import torch
    state = _VaeState(vae)
    plan: dict = {"action": "none"}
    original_decode = vae.decode
    had_own = "decode" in vae.__dict__
    own = vae.__dict__.get("decode")
    upcast = vae.dtype == torch.float16 and bool(getattr(vae.config, "force_upcast", False))
    fp16_decode = bool(opts.vae_fp16 and upcast and not engine._offload)
    if opts.vae_auto:
        fp32 = vae.dtype == torch.float32 or (upcast and not fp16_decode)
        plan = decode_plan(width=width, height=height, batch=batch, fp32_decode=fp32, free=free_bytes(),
                           tile_default=state.sizes[0] or MAX_TILE)
        if plan["action"] != "none":
            msg = (f"VAE decode of {batch} x {width}x{height} would not fit into the free VRAM: "
                   + ("decoding one image at a time" if plan["action"] == "slice" else
                      f"decoding in {plan['tile']} px tiles" + ("" if plan["fits"] else " (still tight)")))
            if ctx is not None:
                ctx.warn(msg)
            log.info(msg)

    def apply_plan(current):
        if current["action"] == "slice":
            vae.enable_slicing()
        elif current["action"] == "tile":
            set_tile(vae, current["tile"])
            if current["slice"]:
                vae.enable_slicing()

    def decode(z, *args, **kwargs):
        nonlocal plan
        entering = vae.dtype
        try:
            apply_plan(plan)
            if not fp16_decode:
                return original_decode(z, *args, **kwargs)
            vae.to(torch.float16)
            out = original_decode(z.to(torch.float16), *args, **kwargs)
            sample = out[0] if isinstance(out, tuple) else out.sample
            if torch.isfinite(sample).all():
                return out
            del out, sample
            if opts.vae_auto:
                fallback = decode_plan(width=width, height=height, batch=batch, fp32_decode=True,
                                       free=free_bytes(), tile_default=state.sizes[0] or MAX_TILE)
                if fallback["action"] == "tile" and vae.use_tiling:
                    fallback["tile"] = min(fallback["tile"], vae.tile_sample_min_size)
                if fallback["action"] != "none":
                    plan = fallback
                    engine._vae_plan = plan
                    apply_plan(plan)
                    msg = "VAE fp16 decode was non-finite; using an automatically planned fp32 retry"
                    if ctx is not None:
                        ctx.warn(msg)
                    log.info(msg)
            vae.to(torch.float32)
            return original_decode(z.to(torch.float32), *args, **kwargs)
        finally:
            if fp16_decode:
                vae.to(entering)
            if plan["action"] != "none":
                state.restore()

    engine._vae_plan = plan
    vae.decode = decode
    try:
        yield plan
    finally:
        if had_own:
            vae.decode = own
        else:
            vae.__dict__.pop("decode", None)
