"""Exercise the fp16-to-fp32 VAE fallback with real SDXL latents, under the shared GPU lock.

The non-finite fp16 output is deliberately injected by this test; it is not evidence that the
model naturally produces NaNs. The fp32 retry runs the real VAE. A per-process allocator cap
emulates limited VRAM; it does not replace testing on a physical low-VRAM card.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import os
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
LOCK = Path("C:/Users/Mizushima/dev/stablediffusion-nui/outputs/.gpu.lock")
MODEL = Path("D:/stable-diffusion-webui-reForge/models/Stable-diffusion/waiIllustriousSDXL_v170.safetensors")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="New evidence directory (never overwritten)")
    parser.add_argument("--model", type=Path, default=MODEL)
    parser.add_argument("--width", type=int, default=832)
    parser.add_argument("--height", type=int, default=1216)
    parser.add_argument("--free-budget-mib", type=int, default=4000)
    args = parser.parse_args()
    if args.width <= 0 or args.height <= 0 or args.width % 8 or args.height % 8:
        parser.error("width and height must be positive multiples of 8")
    if args.free_budget_mib < 1000:
        parser.error("free budget must be at least 1000 MiB")
    token = "vae-fp32-fallback-" + uuid.uuid4().hex
    try:
        LOCK.mkdir()
    except FileExistsError:
        raise SystemExit(f"GPU lock held: {LOCK}; retry after the owner releases it")
    owner = LOCK / "owner.txt"
    owner.write_text(token, encoding="utf-8")
    try:
        args.output.mkdir(parents=True, exist_ok=False)
        receipt = {"success": False, "injected_nan": True, "emulated_budget": True,
                   "model": str(args.model), "dimensions": [args.width, args.height],
                   "free_budget_mib": args.free_budget_mib}
        try:
            import torch
            from PIL import ImageChops, ImageStat
            from sdfx.engine import Engine
            from sdfx.protocol import Emitter, JobContext
            from sdfx.vae_memory import free_bytes
            if not torch.cuda.is_available():
                raise RuntimeError("CUDA is unavailable")
            if not args.model.is_file():
                raise FileNotFoundError(args.model)
            receipt["source_sha256"] = hashlib.sha256((ROOT / "python/sdfx/vae_memory.py").read_bytes()).hexdigest()
            receipt["gpu"] = torch.cuda.get_device_name()
            ctx = JobContext(Emitter(io.BytesIO()), "real-vae-fallback")
            engine = Engine()
            engine.load_model({"path": str(args.model), "dtype": "fp16", "vram_mode": "gpu",
                               "vae_auto": True, "vae_fp16": False}, ctx)
            receipt["model_short_hash"] = engine.model.get("hash")
            pipe, vae = engine.pipe, engine.pipe.vae
            if not bool(getattr(vae.config, "force_upcast", False)):
                raise RuntimeError("selected VAE does not exercise the SDXL force-upcast path")
            gen = torch.Generator("cuda").manual_seed(173)
            with torch.inference_mode():
                latent = pipe(prompt="a lion and a tiger in the woods, golden light",
                              negative_prompt="blurry, text", width=args.width, height=args.height,
                              num_inference_steps=20, guidance_scale=6.0, generator=gen,
                              output_type="latent").images
            torch.cuda.synchronize()
            torch.cuda.empty_cache()
            total = torch.cuda.get_device_properties(0).total_memory
            current = torch.cuda.memory_allocated()
            limit = current + args.free_budget_mib * 2**20
            if limit >= total:
                raise RuntimeError("requested emulated cap is not below physical VRAM")
            torch.cuda.set_per_process_memory_fraction(limit / total)
            os.environ["SDFX_VRAM_BUDGET_MB"] = str(limit / 2**20)
            receipt["cap_mib"] = round(limit / 2**20, 1)
            receipt["free_before_mib"] = round(free_bytes() / 2**20, 1)
            state_before = [vae.use_tiling, vae.use_slicing, vae.tile_sample_min_size, vae.tile_latent_min_size]
            def measure():
                torch.cuda.synchronize()
                torch.cuda.reset_peak_memory_stats()
                start = time.monotonic()
                image = engine._decode(latent)[0]
                torch.cuda.synchronize()
                return image, round(time.monotonic()-start, 3), round(torch.cuda.max_memory_allocated() / 2**20, 1)
            reference, ref_s, ref_peak = measure()
            ref_plan = dict(engine._vae_plan)
            reference.save(args.output / "reference-fp32.png")
            original = vae.decode
            calls = []
            def injected(lat, *a, **kw):
                calls.append({"dtype": str(vae.dtype), "tiling": bool(vae.use_tiling),
                              "tile": int(vae.tile_sample_min_size)})
                if len(calls) == 1:
                    return (torch.full((1,), float("nan"), device=lat.device, dtype=lat.dtype),)
                return original(lat, *a, **kw)
            vae.decode = injected
            engine.load_opts.vae_fp16 = True
            try:
                result, retry_s, retry_peak = measure()
                retry_plan = dict(engine._vae_plan)
            finally:
                vae.__dict__.pop("decode", None)
            result.save(args.output / "fallback-fp32.png")
            delta = ImageStat.Stat(ImageChops.difference(reference.convert("RGB"), result.convert("RGB"))).mean
            state_after = [vae.use_tiling, vae.use_slicing, vae.tile_sample_min_size, vae.tile_latent_min_size]
            receipt.update(reference_plan=ref_plan, fallback_plan=retry_plan, calls=calls,
                           reference_seconds=ref_s, fallback_seconds=retry_s,
                           reference_peak_alloc_mib=ref_peak, fallback_peak_alloc_mib=retry_peak,
                           mean_abs_rgb=sum(delta) / (len(delta) * 255),
                           vae_state_before=state_before, vae_state_after=state_after)
            assert len(calls) == 2 and calls[0]["dtype"] == "torch.float16" and calls[1]["dtype"] == "torch.float32", calls
            assert not calls[0]["tiling"] and calls[1]["tiling"], calls
            assert retry_plan["action"] == "tile" and state_after == state_before, receipt
            assert receipt["mean_abs_rgb"] < 0.02, receipt
            receipt["success"] = True
        finally:
            (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
        print(json.dumps(receipt, indent=2))
    finally:
        if owner.is_file() and owner.read_text(encoding="utf-8") == token:
            owner.unlink()
            LOCK.rmdir()


if __name__ == "__main__":
    main()
