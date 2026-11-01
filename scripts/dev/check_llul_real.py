"""Real-model LLuL acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image

# Each must change the image.
CASES = {
    "default": dict(),
    "strong": dict(weight=0.6, multiply=2),
    "weight_negative": dict(weight=-0.4),
    "multiply3": dict(weight=0.4, multiply=3),
    "corner": dict(weight=0.5, multiply=2, x=0, y=0),
    "slerp": dict(weight=0.5, understand=True, intp="SLerp"),
    "bicubic_area": dict(weight=0.5, understand=True, up="Bicubic", up_aa=True, down="Area"),
    "pooling": dict(weight=0.5, understand=True, down="Pooling Avg"),
    "resblock_out": dict(weight=0.4, understand=True, layers="OUT", apply_resblock=True, apply_out=False),
    "transformer_in": dict(weight=0.4, understand=True, layers="IN", apply_transformer=True, apply_out=False),
    "attention": dict(weight=0.4, understand=True, layers="M, OUT", apply_sattn=True, apply_xattn=True, apply_out=False),
    "everything": dict(weight=0.3, understand=True, layers="", apply_resblock=True, apply_transformer=True,
                       apply_sattn=True, apply_xattn=True),
    "late_window": dict(weight=0.5, understand=True, start_steps=10, max_steps=14),
    "masked": dict(weight=1.0, multiply=2, use_mask=True),
}
# Each must reproduce the baseline exactly (nothing runs).
IDENTITY = {
    "weight0": dict(weight=0.0),
    "start_after_end": dict(weight=0.8, understand=True, start_steps=200),
    "no_layer_match": dict(weight=0.8, understand=True, layers="nothing", apply_resblock=True, apply_out=False),
}
REPLAY = dict(weight=0.5, multiply=2, x=40, y=60, understand=True, layers="", apply_resblock=True, apply_out=True,
              up="Bicubic", down="Bilinear", intp="SLerp", start_steps=3)
HIRES = dict(weight=0.5, multiply=2)


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def region_stats(base, other, box):
    """Mean absolute difference (0..1) inside and outside the box."""
    from PIL import ImageChops, ImageStat
    diff = ImageChops.difference(Image.open(base).convert("L"), Image.open(other).convert("L"))
    total = ImageStat.Stat(diff).sum[0]
    x0, y0, x1, y1 = box
    inside = ImageStat.Stat(diff.crop(box)).sum[0]
    area_in = (x1 - x0) * (y1 - y0)
    area_out = diff.width * diff.height - area_in
    return inside / area_in / 255, (total - inside) / area_out / 255


async def exercise(client, args, receipt):
    receipt["calls"] = []

    async def call(name, values, allow_error=False):
        result = await client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "error": error, "result": data or encoded.get("content")})
        assert allow_error or not error, encoded
        return data if not error else {"error": encoded}

    def first(data):
        assert data.get("images"), data
        return data["images"][0]["path"]

    args.output.mkdir(parents=True, exist_ok=True)
    mask_path = args.output / "mask.png"
    Image.linear_gradient("L").rotate(90).resize((256, 256)).save(mask_path)  # white left, black right

    def llul(**values):
        values = dict(values)
        if values.get("use_mask"):
            values["mask"] = "data:image/png;base64," + base64.b64encode(mask_path.read_bytes()).decode()
        return {"llul": dict({"enabled": True}, **values)}

    assert "llul" in json.dumps(await call("list_always_on", {"tab": "txt2img"}))
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=2468, sampler_name="Euler", return_images=False,
                  model_keyword=False)
    out = {"baseline": first(await call("txt2img", params))}
    for name, values in CASES.items():
        out[name] = first(await call("txt2img", dict(params, always_on=llul(**values))))
        assert rgb(out[name]) != rgb(out["baseline"]), f"{name} did not change the image"
    for name, values in IDENTITY.items():
        out[name] = first(await call("txt2img", dict(params, always_on=llul(**values))))
        assert rgb(out[name]) == rgb(out["baseline"]), f"{name} changed the image"
    # The default region (multiply 2: the centre quarter by area) changes more than the rest of the image.
    w, h = args.width, args.height
    box = (w // 2 - w // 4, h // 2 - h // 4, w // 2 + w // 4, h // 2 + h // 4)
    inside, outside = region_stats(out["baseline"], out["strong"], box)
    receipt["strong_region_diff"] = {"inside": inside, "outside": outside}
    assert inside > outside, receipt["strong_region_diff"]
    out["combo"] = first(await call("txt2img", dict(params, always_on=llul(**REPLAY))))
    meta = await call("inspect_generation_metadata", {"image": out["combo"]})
    assert meta["always_on"].get("llul", {}).get("enabled") is True, meta["always_on"]
    assert not any(k.startswith("LLuL ") for k in meta["unapplied_fields"]), meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = first(await call("txt2img", replay))
    assert rgb(out["replay"]) == rgb(out["combo"]), "PNG replay did not reproduce the image"
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    out["hires_baseline"] = first(await call("txt2img", dict(params, hires=hires)))
    out["hires"] = first(await call("txt2img", dict(params, hires=hires, always_on=llul(**HIRES))))
    assert rgb(out["hires"]) != rgb(out["hires_baseline"]), "hires did not change"
    queued = await call("txt2img_async", dict(params, steps=80, always_on=llul(**REPLAY)))
    for _ in range(600):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 1, "return_images": False}, allow_error=True)
        if 0.2 < float(state.get("progress", 0) or 0) < 1 or state.get("status") in {"done", "failed", "cancelled"}:
            break
    await call("cancel_job", {"job_id": queued["job_id"]})
    for _ in range(120):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False}, allow_error=True)
        state = state.get("error", {}).get("structured_content", state) if "error" in state else state
        if state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_state"] = state.get("status")
    assert state.get("status") == "cancelled", state
    out["after_cancel"] = first(await call("txt2img", params))
    assert rgb(out["after_cancel"]) == rgb(out["baseline"]), "hooks were not removed after cancel"
    receipt["outputs"] = out
    receipt["success"] = True


async def run(args, receipt):
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs={"checkpoints": [str(args.models.resolve())]}, last_model=args.checkpoint,
                    vram_mode=args.vram_mode, dtype="fp16", preview_every=0)
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    command = [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
               "sdfx.mcp.McpMain", "--home", str(app_home), "--drain-seconds", "5"]
    receipt.update(command=command, jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=3600) as client:
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
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=24)
    p.add_argument("--cfg", type=float, default=5.0)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="a lion and a tiger and a grizzly bear in the woods, detailed illustration")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "outputs": receipt.get("outputs")}, indent=2))


if __name__ == "__main__":
    main()
