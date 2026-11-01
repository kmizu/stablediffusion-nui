"""Real-model Loopback Scaler acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image


def size(path):
    with Image.open(path) as image:
        return image.size


def pixels(path):
    with Image.open(path) as image:
        return image.convert("RGB").tobytes()


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

    def images(data):
        assert data.get("images"), data
        return [image["path"] for image in data["images"]]

    await call("set_model", {"name": args.checkpoint})
    scripts = await call("list_scripts", {})
    assert "loopback_scaler" in json.dumps(scripts)
    base = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", steps=args.steps, cfg_scale=7,
                sampler_name="DDIM", seed=4242, return_images=False, model_keyword=False)
    portrait = images(await call("txt2img", dict(base, width=args.width, height=args.height)))[0]
    landscape = images(await call("txt2img", dict(base, width=args.height, height=args.width)))[0]
    out = {"portrait_source": portrait, "landscape_source": landscape}

    def script(source, **values):
        params = dict(base, init_image=source, denoising_strength=0.3, width=size(source)[0], height=size(source)[1])
        params.pop("return_images")
        return {"script_id": "loopback_scaler", "values": dict({"max_width": args.max_side, "max_height": args.max_side},
                                                                **values), "params": params, "return_images": False}

    before = set(Path(portrait).parent.parent.rglob("*.png"))
    defaults = images(await call("run_script", script(portrait)))
    assert len(defaults) == 1, defaults
    loops = sorted(set(Path(portrait).parent.parent.rglob("*.png")) - before - {Path(defaults[0])})
    receipt["default_loop_files"] = [str(p) for p in loops]
    out["defaults"] = defaults[0]
    final = size(defaults[0])
    assert max(final) <= args.max_side + 16 and final[1] > final[0], final
    if args.width == 512 and args.height == 768 and args.max_side == 1024:
        assert final == (688, 1040), final  # upstream overshoot quirk kept
    out["landscape"] = images(await call("run_script", script(landscape)))[0]
    if args.height == 768 and args.width == 512 and args.max_side == 1024:
        assert size(out["landscape"]) == (1040, 688), size(out["landscape"])
    out["ease"] = images(await call("run_script", script(portrait, dimension_increment_factor="Ease Out: Cubic", loops=6,
                                                         denoising_strength_change_factor=0.95)))[0]
    out["scale"] = images(await call("run_script", script(portrait, use_scale=True, scale=1.5, loops=5,
                                                          dimension_increment_factor="Ease In: Sine")))[0]
    expected = (round(args.width * 1.5 / 8) * 8, round(args.height * 1.5 / 8) * 8)
    assert abs(size(out["scale"])[0] - expected[0]) <= 16 and abs(size(out["scale"])[1] - expected[1]) <= 16, size(out["scale"])
    filters = dict(sharpness_strength=1.4, color_strength=1.2, contrast_strength=1.1, detail_strength="Low")
    out["filters"] = images(await call("run_script", script(portrait, **filters)))[0]
    out["contour"] = images(await call("run_script", script(portrait, contour_bool=True, **filters)))[0]
    assert pixels(out["filters"]) != pixels(out["defaults"]) and pixels(out["contour"]) != pixels(out["filters"])
    batch = script(portrait)
    batch["params"]["n_iter"] = 2
    out["batch"] = images(await call("run_script", batch))
    assert len(out["batch"]) == 2, out["batch"]
    pending = dict(script(portrait, loops=4), **{"async": True})
    pending["params"]["steps"] = 60
    queued = await call("run_script", pending)
    for _ in range(600):
        progress = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 1, "return_images": False},
                              allow_error=True)
        if "iteration 2/" in str(progress.get("message", "")) or progress.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_requested_at"] = progress.get("message")
    await call("cancel_job", {"job_id": queued["job_id"]})
    for _ in range(120):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False},
                           allow_error=True)
        if state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_state"] = state.get("status")
    assert state.get("status") == "cancelled", state
    again = images(await call("run_script", script(portrait)))[0]
    assert pixels(again) == pixels(defaults[0]), "generation after cancel differs"
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
    p.add_argument("--height", type=int, default=768)
    p.add_argument("--max-side", type=int, default=1024)
    p.add_argument("--steps", type=int, default=30)
    p.add_argument("--prompt", default="1girl, solo, walking in a flower garden, detailed illustration, soft light")
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
