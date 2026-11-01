"""Real-model Agent Attention acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
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


async def exercise(client, args, receipt):
    receipt["calls"] = []

    async def call(name, values, allow_error=False):
        result = await client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "error": error, "result": data or encoded.get("content")})
        assert allow_error or not error, encoded
        return data

    def first(data):
        assert data.get("images"), data
        return data["images"][0]["path"]

    def agat(**over):
        return {"agent_attention": dict({"enabled": True}, **over)}

    assert "agent_attention" in json.dumps(await call("list_always_on", {"tab": "txt2img"}))
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", width=args.side, height=args.side,
                  steps=args.steps, seed=2468, sampler_name="Euler", return_images=False, model_keyword=False)
    out = {"baseline": first(await call("txt2img", params))}
    configs = {k: json.loads(v) for k, v in args.config}
    for name, config in configs.items():
        out[name] = first(await call("txt2img", dict(params, always_on=agat(**config))))
    for name, config in configs.items():
        same = rgb(out[name]) == rgb(out["baseline"])
        if config.get("max_downsample") == "1" and args.sdxl:
            assert same, f"{name}: Max Downsample 1 must not change SDXL"
        else:
            assert not same, f"{name} did not change the image"
    target = next(k for k in configs if not (args.sdxl and configs[k].get("max_downsample") == "1"))
    meta = await call("inspect_generation_metadata", {"image": out[target]})
    assert meta["always_on"].get("agent_attention", {}).get("enabled") is True, meta["always_on"]
    assert not any(k.startswith("AgAt") for k in meta["unapplied_fields"]), meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = first(await call("txt2img", replay))
    assert rgb(out["replay"]) == rgb(out[target]), "PNG replay did not reproduce the image"
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    hires_config = {"max_downsample": "2"} if args.sdxl else {}
    out["hires_baseline"] = first(await call("txt2img", dict(params, hires=hires)))
    out["hires_all"] = first(await call("txt2img", dict(params, hires=hires, always_on=agat(**hires_config))))
    out["hires_only"] = first(await call("txt2img", dict(params, hires=hires,
                                                         always_on=agat(hires_fix_only=True, **hires_config))))
    assert rgb(out["hires_all"]) != rgb(out["hires_baseline"]) and rgb(out["hires_only"]) != rgb(out["hires_baseline"])
    assert rgb(out["hires_only"]) != rgb(out["hires_all"])
    long = dict(params, steps=80, always_on=agat(**hires_config))
    queued = await call("txt2img_async", long)
    for _ in range(600):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 1, "return_images": False}, allow_error=True)
        if state.get("progress", 0) and 0.2 < float(state.get("progress", 0)) < 1 or state.get("status") in {"done", "failed", "cancelled"}:
            break
    await call("cancel_job", {"job_id": queued["job_id"]})
    for _ in range(120):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False}, allow_error=True)
        if state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_state"] = state.get("status")
    assert state.get("status") == "cancelled", state
    out["after_cancel"] = first(await call("txt2img", params))
    assert rgb(out["after_cancel"]) == rgb(out["baseline"]), "attention processors were not restored after cancel"
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
    p.add_argument("--side", type=int, default=512)
    p.add_argument("--steps", type=int, default=24)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--sdxl", action="store_true")
    p.add_argument("--config", nargs=2, action="append", metavar=("NAME", "JSON"), required=True)
    p.add_argument("--prompt", default="1girl, solo, sitting in a cafe by the window, detailed illustration, soft light")
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
