"""Real-model Semantic Guidance acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
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

    async def call(name, values):
        result = await client.call_tool(name, values, read_timeout_seconds=1800)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        receipt["calls"].append({"tool": name, "arguments": values, "result": data or encoded.get("content")})
        assert not encoded.get("isError", encoded.get("is_error", False)), encoded
        return data

    def first(data):
        assert data.get("images"), data
        return data["images"][0]["path"]

    def sega(**over):
        return {"semantic_guidance": dict({"enabled": True, "prompt": args.concept, "negative_prompt": "",
                                           "warmup": 5, "edit_guidance_scale": args.scale}, **over)}

    listed = await call("list_always_on", {"tab": "txt2img"})
    assert "semantic_guidance" in json.dumps(listed)
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", width=args.width, height=args.height,
                  steps=args.steps, seed=777, sampler_name="Euler", return_images=False, model_keyword=False)
    out = {"baseline": first(await call("txt2img", params))}
    out["scale0"] = first(await call("txt2img", dict(params, always_on=sega(edit_guidance_scale=0.0))))
    assert rgb(out["scale0"]) == rgb(out["baseline"]), "edit scale 0 must not change the image"
    out["add"] = first(await call("txt2img", dict(params, always_on=sega())))
    out["remove"] = first(await call("txt2img", dict(params, always_on=sega(prompt="", negative_prompt=args.remove))))
    out["warmup0"] = first(await call("txt2img", dict(params, always_on=sega(warmup=0))))
    for key in ("add", "remove", "warmup0"):
        assert rgb(out[key]) != rgb(out["baseline"]), f"{key} did not change the image"
    assert rgb(out["warmup0"]) != rgb(out["add"])
    meta = await call("inspect_generation_metadata", {"image": out["add"]})
    restored = meta["always_on"].get("semantic_guidance")
    assert restored and restored["prompt"] == args.concept and restored["enabled"] is True, meta["always_on"]
    assert not any(k.startswith("SEGA") for k in meta["unapplied_fields"]), meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = first(await call("txt2img", replay))
    assert rgb(out["replay"]) == rgb(out["add"]), "PNG replay did not reproduce the image"
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    out["hires"] = first(await call("txt2img", dict(params, hires=hires, always_on=sega())))
    receipt["outputs"] = out
    receipt["success"] = True


async def run(args, receipt):
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs={"checkpoints": [str(args.models.resolve())]}, last_model=args.checkpoint,
                    vram_mode="model_offload", dtype="fp16", preview_every=0)
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    command = [str(args.launcher.resolve()), "--mcp", "--home", str(app_home), "--drain-seconds", "5"] if args.launcher else \
        [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
         "sdfx.mcp.McpMain", "--home", str(app_home), "--drain-seconds", "5"]
    receipt.update(command=command, jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=1800) as client:
        await exercise(client, args, receipt)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--models", type=Path, required=True)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--launcher", type=Path)
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--prompt", default="1girl, solo, standing in a park, trees, sunny day, upper body, detailed illustration")
    p.add_argument("--scale", type=float, default=1.0)
    p.add_argument("--concept", default="red scarf")
    p.add_argument("--remove", default="trees")
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
