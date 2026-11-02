"""VAE decode only: peak VRAM, time and pixel difference of precision / tiling / slicing variants on real latents.

    python bench_vae_decode.py sd15|sdxl [width height] [batch]

Latents come from a real 20-step generation (output_type="latent"), so the decode sees realistic activations.
Holds outputs/.gpu.lock for the whole run. Prints one line per variant: peak MiB above the resident weights,
seconds and the mean absolute difference (fraction of full scale) against the reference (the engine default).
"""
from __future__ import annotations

import io
import sys
import time
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "python"))
import bench_optimizations as bench  # noqa: E402


def decode(vae, latents, dtype, tile=None, slicing=False):
    vae.disable_tiling()
    vae.disable_slicing()
    default_tile = getattr(vae, "_bench_default_tile", None)
    if default_tile is None:
        vae._bench_default_tile = default_tile = (vae.tile_sample_min_size, vae.tile_latent_min_size)
    if tile:
        factor = 2 ** (len(vae.config.block_out_channels) - 1)
        vae.tile_sample_min_size, vae.tile_latent_min_size = tile, tile // factor
        vae.enable_tiling()
    else:
        vae.tile_sample_min_size, vae.tile_latent_min_size = default_tile
    if slicing:
        vae.enable_slicing()
    original = vae.dtype
    vae.to(dtype)
    torch.cuda.synchronize()
    base = torch.cuda.memory_allocated()
    torch.cuda.reset_peak_memory_stats()
    t = time.time()
    with torch.inference_mode():
        img = vae.decode(latents.to(dtype) / vae.config.scaling_factor, return_dict=False)[0]
    torch.cuda.synchronize()
    took = time.time() - t
    peak = (torch.cuda.max_memory_allocated() - base) / 2**20
    out = ((img.float().clamp(-1, 1) + 1) / 2).cpu()
    vae.to(original)
    del img
    torch.cuda.empty_cache()
    return out, peak, took


def main():
    name = sys.argv[1]
    ckpt, w, h = bench.CHECKPOINTS[name]
    if len(sys.argv) > 3:
        w, h = int(sys.argv[2]), int(sys.argv[3])
    batch = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    from sdfx.engine import Engine
    from sdfx.protocol import Emitter, JobContext
    bench.acquire_lock()
    try:
        ctx = JobContext(Emitter(io.BytesIO()), "vae")
        engine = Engine()
        engine.load_model({"path": str(bench.MODELS / ckpt), "dtype": "fp16", "vram_mode": "gpu"}, ctx)
        pipe = engine.pipe
        gen = torch.Generator("cuda").manual_seed(7)
        with torch.inference_mode():
            lat = pipe(prompt=bench.PROMPT, negative_prompt=bench.NEGATIVE, width=w, height=h, num_inference_steps=20,
                       guidance_scale=6.0, generator=gen, output_type="latent", num_images_per_prompt=batch).images
        vae = pipe.vae
        sdxl = name == "sdxl"
        rows = []
        variants = [("default" + (" (fp32 upcast)" if sdxl else " (fp16)"), torch.float32 if sdxl else torch.float16, None, False)]
        if sdxl:
            variants += [("bf16", torch.bfloat16, None, False), ("fp16", torch.float16, None, False)]
        else:
            variants += [("fp32", torch.float32, None, False)]
        for tile in (1024, 768, 512, 384, 256):
            variants.append((f"tiled {tile}", torch.float32 if sdxl else torch.float16, tile, False))
        if batch > 1:
            variants.append(("sliced", torch.float32 if sdxl else torch.float16, None, True))
        ref = None
        for label, dtype, tile, sl in variants:
            try:
                decode(vae, lat, dtype, tile, sl)  # warm-up
                out, peak, took = decode(vae, lat, dtype, tile, sl)
            except torch.cuda.OutOfMemoryError:
                print(f"{label:24s} OOM", flush=True)
                torch.cuda.empty_cache()
                continue
            if ref is None:
                ref = out
            diff = float((out - ref).abs().mean()) if torch.isfinite(out).all() else float("nan")
            print(f"{label:24s} {w}x{h} b{batch}  peak +{peak:7.0f} MiB  {took:6.3f}s  mad {diff:.5f}", flush=True)
    finally:
        try:
            bench.LOCK.rmdir()
        except OSError:
            pass


if __name__ == "__main__":
    main()
