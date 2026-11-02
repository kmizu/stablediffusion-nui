"""Makes real 2:1 pictures with SD1.5 and SDXL through the official MCP SDK (run in the mcp==2.3.0 client venv).

The Panorama tab is a viewer, so the generation itself is only the input: the pictures are written to
<output>/images/... and listed in receipt.json for ``sdfx.ui.RealPanoramaCheck``, which drives the real MainWindow with them.
The script also asserts the pictures are not blank and records how much the left and right edges differ, because a model
that was not trained on panoramas does not make them tile (the viewer shows that seam honestly).
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image

PROMPT = ("equirectangular 360 degree panorama, HDRI, inside a cozy wooden cabin living room, window, sofa, bookshelf, "
          "warm light, wide angle, detailed")
NEGATIVE = "lowres, blurry, text, watermark, people"


def stats(path):
    with Image.open(path) as image:
        rgb = image.convert("RGB")
        w, h = rgb.size
        small = rgb.resize((64, 32))
        px = list(small.getdata())
        mean = [sum(p[i] for p in px) / len(px) for i in range(3)]
        spread = max(max(p[i] for p in px) - min(p[i] for p in px) for i in range(3))
        # mean absolute difference between the first and the last column: how visible the wrap-around seam is
        left = [rgb.getpixel((0, y)) for y in range(0, h, 8)]
        right = [rgb.getpixel((w - 1, y)) for y in range(0, h, 8)]
        seam = sum(abs(a[i] - b[i]) for a, b in zip(left, right) for i in range(3)) / (3 * len(left))
    return {"size": [w, h], "mean_rgb": mean, "spread": spread, "seam_edge_difference": seam}


async def exercise(client, args, receipt):
    receipt["images"] = {}
    for label, checkpoint, width, height in (("sd15", args.sd15, 1024, 512), ("sdxl", args.sdxl, 1536, 768)):
        await client.call_tool("set_model", {"name": checkpoint}, read_timeout_seconds=3600)
        result = await client.call_tool(
            "txt2img", dict(prompt=PROMPT, negative_prompt=NEGATIVE, width=width, height=height, steps=args.steps,
                            cfg_scale=6.0, seed=360, sampler_name="Euler", return_images=False, model_keyword=False),
            read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        assert not encoded.get("isError", encoded.get("is_error", False)), encoded
        path = data["images"][0]["path"]
        info = stats(path)
        assert info["size"] == [width, height], info
        assert info["spread"] > 40, f"{label} looks blank: {info}"
        receipt["images"][label] = dict(info, path=path, checkpoint=checkpoint)
    receipt["success"] = True


async def run(args, receipt):
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs={"checkpoints": [str(args.models.resolve())]}, last_model=args.sd15,
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
    p.add_argument("--sd15", default="animekawa_v10")
    p.add_argument("--sdxl", default="waiIllustriousSDXL_v170")
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--steps", type=int, default=24)
    p.add_argument("--vram-mode", default="model_offload")
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    receipt = {"success": False}
    try:
        asyncio.run(run(args, receipt))
    finally:
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(receipt.get("images", {}), indent=2))


if __name__ == "__main__":
    main()
