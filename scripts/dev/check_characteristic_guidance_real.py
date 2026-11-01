"""Real-model Characteristic Guidance acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def saturation(path):
    """Mean HSV saturation (0..255) of a 64x64 thumbnail; CHG at a large CFG scale should not raise it."""
    with Image.open(path) as image:
        pixels = list(image.convert("RGB").resize((64, 64)).convert("HSV").getdata())
    return sum(p[1] for p in pixels) / len(pixels)


# The always_on values exercised (each also gets "enabled": True).
CASES = {
    "default": {},
    "strength5": {"reg_ini": 5, "reg_range": 2},
    "basis": {"noise_base": 2, "ite": 30},
    "window": {"start_step": 0.1, "stop_step": 0.4},
    "advanced": {"noise_base": 1, "chara_decay": 0.5, "res": -3, "lr": 0.8, "reg_size": 0.6, "reg_w": 1.0, "aa_dim": 3,
                 "controlnet_mode": "More Prompt"},
}
EMPTY_WINDOW = {"start_step": 0.25, "stop_step": 0.25}  # round(steps / 4) twice: no CHG step


async def exercise(client, args, receipt):
    receipt["calls"] = []

    async def call(name, values, allow_error=False):
        result = await client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "error": error, "result": data or encoded.get("content")})
        assert allow_error or not error, encoded
        return data, error, encoded

    async def image(name, values):
        data, _, _ = await call(name, values)
        assert data.get("images"), data
        return data["images"][0]["path"]

    def chg(**values):
        return {"characteristic_guidance": dict({"enabled": True}, **values)}

    data, _, _ = await call("list_always_on", {"tab": "txt2img"})
    assert "characteristic_guidance" in json.dumps(data)
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=2468, sampler_name="Euler", return_images=False,
                  model_keyword=False)
    out = {"baseline": await image("txt2img", params)}
    for name, values in CASES.items():
        out[name] = await image("txt2img", dict(params, always_on=chg(**values)))
        assert rgb(out[name]) != rgb(out["baseline"]), f"{name} did not change the image"
    out["empty_window"] = await image("txt2img", dict(params, always_on=chg(**EMPTY_WINDOW)))
    assert rgb(out["empty_window"]) == rgb(out["baseline"]), "a run without CHG steps must equal plain CFG"
    receipt["saturation"] = {name: saturation(out[name]) for name in ("baseline", *CASES)}
    meta, _, _ = await call("inspect_generation_metadata", {"image": out["advanced"]})
    assert meta["always_on"].get("characteristic_guidance", {}).get("enabled") is True, meta["always_on"]
    assert "CHG" not in meta["unapplied_fields"], meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = await image("txt2img", replay)
    assert rgb(out["replay"]) == rgb(out["advanced"]), "PNG replay did not reproduce the image"
    # DDIM runs the source's timesteps (z space) path
    out["ddim_baseline"] = await image("txt2img", dict(params, sampler_name="DDIM"))
    out["ddim"] = await image("txt2img", dict(params, sampler_name="DDIM", always_on=chg()))
    assert rgb(out["ddim"]) != rgb(out["ddim_baseline"]), "DDIM did not change"
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    out["hires_baseline"] = await image("txt2img", dict(params, hires=hires))
    out["hires"] = await image("txt2img", dict(params, hires=hires, always_on=chg()))
    assert rgb(out["hires"]) != rgb(out["hires_baseline"]), "hires did not change"
    _, error, encoded = await call("txt2img", dict(params, always_on=dict(chg(), dynamic_thresholding={"enabled": True})),
                                   allow_error=True)
    assert error and "Dynamic Thresholding" in json.dumps(encoded), encoded
    queued, _, _ = await call("txt2img_async", dict(params, steps=60, always_on=chg()))
    for _ in range(900):
        state, _, _ = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 1, "return_images": False},
                                 allow_error=True)
        if 0.2 < float(state.get("progress", 0) or 0) < 1 or state.get("status") in {"done", "failed", "cancelled"}:
            break
    await call("cancel_job", {"job_id": queued["job_id"]})
    for _ in range(120):
        state, _, _ = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False},
                                 allow_error=True)
        if state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_state"] = state.get("status")
    assert state.get("status") == "cancelled", state
    out["after_cancel"] = await image("txt2img", params)
    assert rgb(out["after_cancel"]) == rgb(out["baseline"]), "the UNet hook was not removed after cancel"
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
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--cfg", type=float, default=12.0)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="1girl, solo, running on a mountain meadow, detailed illustration")
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
