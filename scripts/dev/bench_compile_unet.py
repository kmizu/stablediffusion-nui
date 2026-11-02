"""U-Net-only timing of the Compile backends (random SD 1.5 / SDXL-sized weights: speed does not depend on values).

    python bench_compile_unet.py --family sd15 --variants plain inductor trt trt:min_block_size=1 ...

Prints the median seconds of one batch-2 U-Net forward (CUDA events) and the speed-up over the plain U-Net.
Needs the optional packages on PYTHONPATH (see docs/compile-verification.md). Hold the GPU lock while running.
"""
from __future__ import annotations

import argparse
import json
import statistics
import time

import torch
from diffusers import UNet2DConditionModel

from sdfx import compile_accel


def make_unet(family: str):
    if family == "sd15":
        unet = UNet2DConditionModel(
            sample_size=64, in_channels=4, out_channels=4, cross_attention_dim=768, attention_head_dim=8,
            block_out_channels=(320, 640, 1280, 1280), layers_per_block=2,
            down_block_types=("CrossAttnDownBlock2D",) * 3 + ("DownBlock2D",),
            up_block_types=("UpBlock2D",) + ("CrossAttnUpBlock2D",) * 3)
        shape, ctx, extra = (2, 4, 64, 64), (2, 77, 768), None
    else:
        unet = UNet2DConditionModel(
            sample_size=128, in_channels=4, out_channels=4, cross_attention_dim=2048, attention_head_dim=[5, 10, 20],
            block_out_channels=(320, 640, 1280), layers_per_block=2, transformer_layers_per_block=[1, 2, 10],
            down_block_types=("DownBlock2D", "CrossAttnDownBlock2D", "CrossAttnDownBlock2D"),
            up_block_types=("CrossAttnUpBlock2D", "CrossAttnUpBlock2D", "UpBlock2D"),
            addition_embed_type="text_time", addition_time_embed_dim=256, projection_class_embeddings_input_dim=2816,
            use_linear_projection=True)
        shape, ctx = (2, 4, 128, 128), (2, 77, 2048)
        extra = {"text_embeds": torch.randn(2, 1280, device="cuda", dtype=torch.float16),
                 "time_ids": torch.tensor([[1024, 1024, 0, 0, 1024, 1024]] * 2, device="cuda", dtype=torch.float16)}
    return unet.to("cuda", torch.float16).eval(), shape, ctx, extra


def timed(fn, iters):
    times = []
    for _ in range(iters):
        a, b = torch.cuda.Event(enable_timing=True), torch.cuda.Event(enable_timing=True)
        a.record()
        fn()
        b.record()
        torch.cuda.synchronize()
        times.append(a.elapsed_time(b) / 1000)
    return statistics.median(times), min(times)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--family", choices=("sd15", "sdxl"), default="sd15")
    p.add_argument("--variants", nargs="+", default=["plain", "inductor", "tensorrt"])
    p.add_argument("--iters", type=int, default=30)
    args = p.parse_args()
    unet, shape, ctx, extra = make_unet(args.family)
    x = torch.randn(*shape, device="cuda", dtype=torch.float16)
    c = torch.randn(*ctx, device="cuda", dtype=torch.float16)
    t = torch.tensor(500.0, device="cuda")
    kw = {"added_cond_kwargs": extra} if extra else {}
    results = {}
    with torch.inference_mode():
        ref = unet(x, t, encoder_hidden_states=c, return_dict=False, **kw)[0].float()
        plain_fn = unet.forward
        for variant in args.variants:
            name, _, opts = variant.partition(":")
            if name == "plain":
                fn = plain_fn
            else:
                cfg = {"backend": name}
                for kv in filter(None, opts.split(",")):
                    k, _, v = kv.replace("~", "=").partition("=")  # "~" survives cmd.exe argument splitting
                    cfg[k] = v
                unet.__dict__.pop("forward", None)
                start = time.time()
                fn = compile_accel._build(unet, compile_accel.normalize(cfg), torch.float16)
            start = time.time()
            out = fn(x, t, encoder_hidden_states=c, return_dict=False, **kw)[0].float()
            build = time.time() - start
            for _ in range(3):
                fn(x, t, encoder_hidden_states=c, return_dict=False, **kw)
            median, best = timed(lambda: fn(x, t, encoder_hidden_states=c, return_dict=False, **kw), args.iters)
            results[variant] = {"first_call_s": round(build, 1), "median_ms": round(median * 1000, 2),
                                "best_ms": round(best * 1000, 2),
                                "max_abs_vs_plain": round((out - ref).abs().max().item(), 4)}
            print(variant, results[variant], flush=True)
    base = results.get("plain", {}).get("median_ms")
    for v, r in results.items():
        if base:
            r["speedup"] = round(base / r["median_ms"], 3)
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
