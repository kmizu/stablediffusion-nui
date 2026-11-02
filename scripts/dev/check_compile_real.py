"""Real-model Compile (TensorRT / Inductor) acceptance through the official MCP SDK (mcp==2.3.0 client venv).

Per backend and checkpoint it measures, on the real engine:
  * the plain U-Net (baseline) twice, then the compiled U-Net twice (first call builds, second reuses);
  * image agreement of the compiled result with the plain one (mean absolute difference, PSNR), determinism of the
    compiled result, and the per-call wall clock so the speed-up (or lack of it) is on record;
  * Hires. fix (a second shape) and a batch of 2 (a third shape);
  * PNG replay through the typed always-on candidate reproduces the compiled image;
  * that a compiled U-Net does not leak into later plain generations (byte-identical baseline after everything);
  * the documented refusals (patching hook, TensorRT + LoRA, bad backend) and an Inductor run with a LoRA.
Run it from an environment where the optional packages are importable (see docs/compile-verification.md).
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import math
import os
import time
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image

NEGATIVE = "lowres, blurry, text"
MAX_MEAN_DIFF = 0.05  # fraction of full scale; fp16 kernels differ, trajectories stay close


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def stats(a, b):
    (sa, da), (sb, db) = rgb(a), rgb(b)
    assert sa == sb, (sa, sb)
    n = len(da)
    mad = sum(abs(x - y) for x, y in zip(da, db)) / n / 255
    mse = sum((x - y) ** 2 for x, y in zip(da, db)) / n
    psnr = float("inf") if mse == 0 else 10 * math.log10(255 * 255 / mse)
    return {"mean_abs_diff": round(mad, 5), "psnr_db": round(psnr, 2), "identical": da == db}


async def exercise(client, args, receipt):
    receipt["calls"] = []
    timings = receipt.setdefault("timings_s", {})

    async def call(name, values, allow_error=False, label=None):
        start = time.time()
        result = await client.call_tool(name, values, read_timeout_seconds=7200)
        took = round(time.time() - start, 2)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "label": label, "seconds": took, "error": error,
                                 "result": data or encoded.get("content")})
        if label:
            timings[label] = took
        assert allow_error or not error, encoded
        return data if not error else {"error": encoded}

    def paths(data):
        assert data.get("images"), data
        return [i["path"] for i in data["images"]]

    await call("set_model", {"name": args.checkpoint})
    base = dict(width=args.width, height=args.height, steps=args.steps, cfg_scale=args.cfg, seed=2468,
                sampler_name="Euler", return_images=False, model_keyword=False, prompt=args.prompt,
                negative_prompt=NEGATIVE)
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    check = receipt.setdefault("checks", {})
    out = {}

    out["baseline"] = paths(await call("txt2img", base, label="plain_1"))[0]
    out["baseline_2"] = paths(await call("txt2img", base, label="plain_2"))[0]
    assert rgb(out["baseline"]) == rgb(out["baseline_2"]), "plain generation is not deterministic"
    out["baseline_hires"] = paths(await call("txt2img", dict(base, hires=hires), label="plain_hires"))[0]
    out["baseline_batch"] = paths(await call("txt2img", dict(base, batch_size=2), label="plain_batch2"))

    for backend in args.backends:
        tag = backend
        comp = {"compile": {"enabled": True, "backend": {"inductor": "Inductor", "tensorrt": "TensorRT"}[backend]}}
        c1 = paths(await call("txt2img", dict(base, always_on=comp), label=f"{tag}_build"))[0]
        c2 = paths(await call("txt2img", dict(base, always_on=comp), label=f"{tag}_reuse"))[0]
        out[f"{tag}_1"], out[f"{tag}_2"] = c1, c2
        info = {"vs_plain": stats(out["baseline"], c2), "run1_vs_run2": stats(c1, c2),
                "build_s": timings[f"{tag}_build"], "reuse_s": timings[f"{tag}_reuse"],
                "plain_s": timings["plain_2"]}
        info["speedup_reuse_vs_plain"] = round(timings["plain_2"] / timings[f"{tag}_reuse"], 3)
        assert info["vs_plain"]["mean_abs_diff"] < MAX_MEAN_DIFF, info
        assert info["run1_vs_run2"]["identical"], f"{tag}: compiled result differs between runs"

        h = paths(await call("txt2img", dict(base, hires=hires, always_on=comp), label=f"{tag}_hires"))[0]
        out[f"{tag}_hires"] = h
        info["hires_vs_plain"] = stats(out["baseline_hires"], h)
        assert info["hires_vs_plain"]["mean_abs_diff"] < MAX_MEAN_DIFF, info
        b = paths(await call("txt2img", dict(base, batch_size=2, always_on=comp), label=f"{tag}_batch2"))
        out[f"{tag}_batch"] = b
        info["batch_vs_plain"] = [stats(x, y) for x, y in zip(out["baseline_batch"], b)]
        assert all(s["mean_abs_diff"] < MAX_MEAN_DIFF for s in info["batch_vs_plain"]), info

        # PNG replay (typed always-on candidate) reproduces the compiled image
        meta = await call("inspect_generation_metadata", {"image": c2})
        cand = meta["always_on"]["compile"]
        assert cand["enabled"] is True and str(cand["backend"]).lower() == backend, cand
        replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
        replay.setdefault("model_keyword", False)
        r = paths(await call("txt2img", replay, label=f"{tag}_replay"))[0]
        info["replay_identical"] = rgb(r) == rgb(c2)
        assert info["replay_identical"], "PNG replay did not reproduce the compiled image"
        info["infotext_candidate"] = cand

        # a plain generation after compiled ones is byte-identical to the first baseline (forward restored)
        after = paths(await call("txt2img", base, label=f"{tag}_plain_after"))[0]
        info["plain_after_identical"] = rgb(after) == rgb(out["baseline"])
        assert info["plain_after_identical"], "compiled U-Net leaked into a plain generation"

        # refusals
        refusals = {}
        for name, extra in (("freeu", {"always_on": {**comp, "freeu": {"enabled": True}}}),
                            ("bad_backend", {"always_on": {"compile": {"enabled": True, "backend": "ONNX"}}})):
            result = await call("txt2img", dict(base, **extra), allow_error=True)
            assert "error" in result, f"{name} did not fail"
            refusals[name] = json.dumps(result["error"])[:260]
        info["refusals"] = refusals
        after2 = paths(await call("txt2img", base, label=f"{tag}_plain_after_errors"))[0]
        assert rgb(after2) == rgb(out["baseline"]), "engine state leaked after refusals"
        check[backend] = info
    if args.lora:
        # LoRA section last: a LoRA that has been used leaves a small, pre-existing residue in later plain SDXL
        # generations (measured below as `plain_after_lora_plain`); the compiled runs must not add to it.
        lora = dict(base, prompt=base["prompt"] + f" <lora:{args.lora}:0.8>", loras=[{"name": args.lora, "weight": 0.8}])
        comp = {"compile": {"enabled": True, "backend": "Inductor"}}
        section = {}
        result = await call("txt2img", dict(lora, always_on={"compile": {"enabled": True, "backend": "TensorRT"}}),
                            allow_error=True)
        assert "error" in result and "LoRA" in json.dumps(result["error"]), result
        section["tensorrt_refusal"] = json.dumps(result["error"])[:260]
        lp = paths(await call("txt2img", lora, label="lora_plain"))[0]
        after_plain = paths(await call("txt2img", base, label="plain_after_lora_plain"))[0]
        lc = paths(await call("txt2img", dict(lora, always_on=comp), label="lora_inductor"))[0]
        lc2 = paths(await call("txt2img", dict(lora, always_on=comp), label="lora_inductor_2"))[0]
        after_comp = paths(await call("txt2img", base, label="plain_after_lora_inductor"))[0]
        out.update(lora_plain=lp, lora_inductor=lc, plain_after_lora_plain=after_plain, plain_after_lora_inductor=after_comp)
        section.update(
            compiled_vs_plain_lora=stats(lp, lc), lora_changes_image=not stats(out["baseline"], lp)["identical"],
            compiled_lora_vs_compiled_nolora=stats(out["inductor_2"], lc), repeat_identical=rgb(lc) == rgb(lc2),
            plain_after_lora_plain_vs_baseline=stats(out["baseline"], after_plain),
            plain_after_lora_inductor_equals_plain_after_lora_plain=rgb(after_comp) == rgb(after_plain))
        assert section["compiled_vs_plain_lora"]["mean_abs_diff"] < MAX_MEAN_DIFF, section
        assert section["compiled_lora_vs_compiled_nolora"]["mean_abs_diff"] > 0.002, "LoRA not applied in the compiled graph"
        assert section["repeat_identical"], section
        assert section["plain_after_lora_inductor_equals_plain_after_lora_plain"], "compiled LoRA run leaked into a plain generation"
        check["lora"] = section
    receipt["outputs"] = out
    receipt["success"] = True


async def run(args, receipt):
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs={"checkpoints": [str(args.models.resolve())], **({"lora": [str(args.lora_dir)]} if args.lora_dir else {})},
                    last_model=args.checkpoint, vram_mode=args.vram_mode, dtype="fp16", preview_every=0)
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=os.pathsep.join(filter(None, [str(args.source.resolve() / "python"), args.extra_pythonpath])),
               SDFX_TRT_CACHE=str(args.trt_cache.resolve()))
    command = [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
               "sdfx.mcp.McpMain", "--home", str(app_home), "--drain-seconds", "5"]
    receipt.update(command=command, jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=7200) as client:
        await exercise(client, args, receipt)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--models", type=Path, required=True)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--backends", nargs="+", default=["inductor", "tensorrt"])
    p.add_argument("--extra-pythonpath", default="", help="directories with the optional triton / torch-tensorrt packages")
    p.add_argument("--trt-cache", type=Path, default=Path("trt-cache"))
    p.add_argument("--lora", default="", help="LoRA name compatible with the checkpoint (Inductor run + TensorRT refusal)")
    p.add_argument("--lora-dir", type=Path, default=Path(os.environ["SDFX_CHECK_LORA_DIR"]) if os.environ.get("SDFX_CHECK_LORA_DIR") else None)
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--cfg", type=float, default=6.0)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="gpu")
    p.add_argument("--prompt", default="a lion and a tiger in the woods, detailed illustration, (golden light:1.2)")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "timings_s": receipt.get("timings_s"),
                      "checks": receipt.get("checks")}, indent=2))


if __name__ == "__main__":
    main()
