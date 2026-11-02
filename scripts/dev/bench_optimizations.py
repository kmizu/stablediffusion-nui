"""Measure one low-VRAM / speed configuration on a real checkpoint in a fresh process.

    python bench_optimizations.py --model sd15|sdxl --label NAME --out DIR [--load k=v ...] [--steps 20] [--runs 2]
    python bench_optimizations.py --drive CONFIGS.json --out DIR      (holds outputs/.gpu.lock for the whole sweep)

Each configuration runs in its own subprocess so the peak VRAM is not inherited from an earlier one. The
subprocess loads the model with the given load options, runs one untimed warm-up generation, then `--runs`
timed generations with the same seed and records: resident VRAM after load, peak allocated / reserved VRAM
of the timed runs, seconds per image (min and median), and the saved image path. `--drive` finishes with the
mean absolute pixel difference (fraction of full scale) of every image against the first configuration of
its model and size (the baseline).
"""
from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

MODELS = Path(os.environ.get("SDFX_MODEL_DIR", "D:/stable-diffusion-webui-reForge/models/Stable-diffusion"))
CHECKPOINTS = {"sd15": ("animekawa_v10.safetensors", 512, 512), "sdxl": ("waiIllustriousSDXL_v170.safetensors", 832, 1216)}
PROMPT = "1girl, solo, silver hair, blue eyes, school uniform, cherry blossoms, detailed background, masterpiece, best quality"
NEGATIVE = "lowres, bad anatomy, worst quality, blurry"
LOCK = Path(os.environ.get("SDFX_GPU_LOCK", "C:/Users/Mizushima/dev/stablediffusion-nui/outputs/.gpu.lock"))


def parse_value(text: str):
    low = text.lower()
    if low in ("true", "false"):
        return low == "true"
    try:
        return int(text)
    except ValueError:
        pass
    try:
        return float(text)
    except ValueError:
        return text


def run_one(args) -> dict:
    import torch
    from sdfx.engine import Engine
    from sdfx.protocol import Emitter, JobContext
    ckpt, width, height = CHECKPOINTS[args.model]
    width, height = args.width or width, args.height or height
    if args.cap_gb:  # emulate a smaller card: real allocator OOM above the cap, and the engine's free-VRAM budget follows it
        total = torch.cuda.get_device_properties(0).total_memory
        torch.cuda.set_per_process_memory_fraction(min(1.0, args.cap_gb * 2**30 / total))
        os.environ["SDFX_VRAM_BUDGET_MB"] = str(int(args.cap_gb * 1024))
    load = {"path": str(MODELS / ckpt), "dtype": "fp16", "vram_mode": "gpu"}
    for item in args.load or []:
        key, _, value = item.partition("=")
        load[key] = parse_value(value)
    ctx = JobContext(Emitter(io.BytesIO()), "bench")
    engine = Engine()
    t0 = time.time()
    engine.load_model(load, ctx)
    torch.cuda.synchronize()
    load_s = time.time() - t0
    resident = torch.cuda.memory_allocated() / 2**20
    out = Path(args.out) / args.label
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    params = {"params": {"prompt": PROMPT, "negative_prompt": NEGATIVE, "seed": 1234, "steps": args.steps,
                         "width": width, "height": height, "cfg_scale": 6.0, "sampler_name": "DPM++ 2M",
                         "batch_size": args.batch, "hooks": json.loads(args.hooks) if args.hooks else {}},
              "output_dir": str(out), "preview_every": 0}
    if args.hires:
        params["params"]["hires"] = {"enabled": True, "scale": args.hires, "denoising_strength": 0.5, "steps": args.steps // 2}
    phases = {"denoise_peak_mb": 0, "decode_peak_mb": 0}
    vae = engine.pipe.vae
    raw_decode = vae.decode

    def spy_decode(*a, **k):  # peak of everything before the decode, then peak of the decode alone
        torch.cuda.synchronize()
        phases["denoise_peak_mb"] = max(phases["denoise_peak_mb"], torch.cuda.max_memory_allocated() / 2**20)
        torch.cuda.reset_peak_memory_stats()
        out = raw_decode(*a, **k)
        torch.cuda.synchronize()
        phases["decode_peak_mb"] = max(phases["decode_peak_mb"], torch.cuda.max_memory_allocated() / 2**20)
        return out

    vae.decode = spy_decode
    result = engine.txt2img(params, ctx)  # warm-up (cudnn autotune, lazy allocations)
    phases.update(denoise_peak_mb=0, decode_peak_mb=0)
    times, peak, reserved = [], 0, 0
    for _ in range(args.runs):
        torch.cuda.synchronize()
        torch.cuda.reset_peak_memory_stats()
        t = time.time()
        result = engine.txt2img(params, ctx)
        torch.cuda.synchronize()
        times.append((time.time() - t) / args.batch)
        peak = max(peak, phases["denoise_peak_mb"], phases["decode_peak_mb"])
        reserved = max(reserved, torch.cuda.max_memory_reserved() / 2**20)
    images = [i.get("path") or i.get("file") for i in result.get("images", [])]
    return {"label": args.label, "model": args.model, "size": [width, height], "load": load, "resident_mb": round(resident),
            "peak_alloc_mb": round(peak), "denoise_peak_mb": round(phases["denoise_peak_mb"]),
            "decode_peak_mb": round(phases["decode_peak_mb"]), "peak_reserved_mb": round(reserved), "sec_per_image_min": round(min(times), 3),
            "sec_per_image_median": round(statistics.median(times), 3), "load_s": round(load_s, 1),
            "images": images, "warnings": result.get("warnings", []), "decode_failed": bool(result.get("vae_recovery"))}


def mad(a: str, b: str):
    import numpy as np
    from PIL import Image
    x = np.asarray(Image.open(a).convert("RGB"), dtype=np.float32)
    y = np.asarray(Image.open(b).convert("RGB"), dtype=np.float32)
    if x.shape != y.shape:
        return None
    return round(float(np.abs(x - y).mean() / 255), 5)


def acquire_lock():
    while True:
        try:
            LOCK.mkdir()
            return
        except FileExistsError:
            print("GPU lock held, retrying in 30 s", flush=True)
            time.sleep(30)


def drive(args):
    configs = json.loads(Path(args.drive).read_text())
    results = []
    env = dict(os.environ, PYTHONPATH=os.environ.get("SDFX_BENCH_PYTHONPATH") or str(Path(__file__).resolve().parents[2] / "python"))
    acquire_lock()
    try:
        for cfg in configs:
            cmd = [sys.executable, __file__, "--model", cfg["model"], "--label", cfg["label"], "--out", args.out,
                   "--steps", str(cfg.get("steps", 20)), "--runs", str(cfg.get("runs", 2))]
            for item in cfg.get("load", []):
                cmd += ["--load", item]
            for key in ("width", "height", "batch", "hires", "hooks"):
                if key in cfg:
                    cmd += [f"--{key}", str(cfg[key])]
            if "cap_gb" in cfg:
                cmd += ["--cap-gb", str(cfg["cap_gb"])]
            print("RUN", cfg["label"], flush=True)
            p = subprocess.run(cmd, capture_output=True, text=True, env=env)
            line = [l for l in p.stdout.splitlines() if l.startswith("RESULT ")]
            if p.returncode or not line:
                results.append({"label": cfg["label"], "model": cfg["model"], "error": (p.stderr or p.stdout)[-1500:]})
                print("FAILED", cfg["label"], (p.stderr or "")[-600:], flush=True)
                continue
            results.append(json.loads(line[-1][7:]))
            print(line[-1][:300], flush=True)
    finally:
        try:
            LOCK.rmdir()
        except OSError:
            pass
    base = {}
    for r in results:
        if "error" in r or r.get("failed") or not r["images"]:
            continue
        key = (r["model"], tuple(r["size"]))
        if key in base:
            r["mad_vs_baseline"] = mad(base[key], r["images"][0])
        else:
            base[key] = r["images"][0]
    Path(args.out, args.name + ".json").write_text(json.dumps(results, indent=1))
    for r in results:
        if "error" in r or r.get("failed"):
            print(r["label"], "ERROR", r.get("failed", ""))
        else:
            print(f'{r["label"]:30s} resident {r["resident_mb"]:6d} peak {r["peak_alloc_mb"]:6d} reserved {r["peak_reserved_mb"]:6d} '
                  f'{r["sec_per_image_min"]:7.3f}s  mad {r.get("mad_vs_baseline")}')


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--model", choices=sorted(CHECKPOINTS))
    p.add_argument("--label", default="run")
    p.add_argument("--out", default="outputs/opt")
    p.add_argument("--load", action="append")
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--runs", type=int, default=2)
    p.add_argument("--batch", type=int, default=1)
    p.add_argument("--width", type=int)
    p.add_argument("--height", type=int)
    p.add_argument("--hires", type=float)
    p.add_argument("--hooks")
    p.add_argument("--cap-gb", dest="cap_gb", type=float)
    p.add_argument("--drive")
    p.add_argument("--name", default="sweep")
    args = p.parse_args()
    if args.drive:
        return drive(args)
    try:
        record = run_one(args)
    except Exception as exc:  # an expected failure (OOM on a capped card) is a result, not a crash
        import traceback
        record = {"label": args.label, "model": args.model, "failed": f"{type(exc).__name__}: {str(exc)[:300]}",
                  "trace": traceback.format_exc()[-800:], "images": []}
    print("RESULT " + json.dumps(record))


if __name__ == "__main__":
    main()
