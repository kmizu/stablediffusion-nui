"""Real-model fixtures for the Character Searcher check, through the official MCP SDK (run in the mcp client venv).

Generates anime images with the real engine on a real SD1.5 and a real SDXL checkpoint: pictures of two girls side by
side (two characters to separate), pictures of a single girl and of a boy, with different hair colours. The images are
the input of scripts/dev/check_chara_searcher_data.py (library level) and of the real UI check
(ui/Test/runMain sdfx.ui.RealCharaSearcherCheck). Hold outputs/.gpu.lock around the run when the GPU is shared.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import shutil
from pathlib import Path

from mcp import Client, StdioServerParameters

NEGATIVE = "lowres, blurry, text, watermark, bad anatomy, extra limbs, cropped"
SCENES = (  # (stem, prompt)
    ("two_red_blue", "2girls, standing side by side, one girl with red hair, one girl with blue hair, school uniform, full body, simple white background, masterpiece, best quality"),
    ("two_blonde_black", "2girls, standing side by side, one girl with blonde hair, one girl with black hair, summer dress, full body, simple white background, masterpiece, best quality"),
    ("solo_silver", "1girl, solo, silver hair, red eyes, kimono, standing, full body, simple white background, masterpiece, best quality"),
    ("solo_green", "1girl, solo, green hair, twintails, maid outfit, standing, full body, simple white background, masterpiece, best quality"),
    ("boy_brown", "1boy, solo, brown hair, hoodie, standing, full body, simple white background, masterpiece, best quality"),
)


async def exercise(client, args, receipt):
    receipt["calls"] = []

    async def call(name, values):
        result = await client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "error": error})
        assert not error, encoded
        return data

    await call("set_model", {"name": args.checkpoint})
    out = []
    for index, (stem, prompt) in enumerate(SCENES):
        data = await call("txt2img", dict(prompt=prompt, negative_prompt=NEGATIVE, width=args.width, height=args.height,
                                          steps=args.steps, cfg_scale=args.cfg, seed=args.seed + index, sampler_name="Euler",
                                          return_images=False, model_keyword=False, batch_size=1))
        assert data.get("images"), data
        source = Path(data["images"][0]["path"])
        target = args.output / "generated" / f"{args.label}_{stem}.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        out.append({"stem": stem, "prompt": prompt, "path": str(target), "sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
    receipt["images"] = out
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
    p.add_argument("--label", required=True, help="prefix of the generated files, e.g. sd15 or sdxl")
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=24)
    p.add_argument("--cfg", type=float, default=6.0)
    p.add_argument("--seed", type=int, default=7100)
    p.add_argument("--vram-mode", default="model_offload")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / f"receipt-{args.label}.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "images": receipt.get("images")}, indent=2))


if __name__ == "__main__":
    main()
