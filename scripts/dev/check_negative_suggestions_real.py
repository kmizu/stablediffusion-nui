"""External MCP check of a suggested negative prompt against a real checkpoint."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image

NEGATIVE = "low quality, worst quality, blurry, jpeg artifacts"


async def run(args, receipt):
    launcher = args.launcher.resolve()
    jar = args.jar.resolve()
    bundled = launcher.parent / "app" / "stablediffusion-nui.jar"
    assert hashlib.sha256(jar.read_bytes()).digest() == hashlib.sha256(bundled.read_bytes()).digest()
    home = args.output.resolve() / "home"
    (home / "python" / "sdfx").mkdir(parents=True, exist_ok=True)
    (home / "config").mkdir(parents=True, exist_ok=True)
    settings = {"python_exe": str(args.python.resolve()), "output_dir": str((args.output / "images").resolve()),
                "model_dirs": {"checkpoints": [str(args.models.resolve())]}, "last_model": args.checkpoint,
                "vram_mode": "model_offload", "dtype": "fp16", "preview_every": 0}
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    env.pop("SDFX_HOME", None)
    command = [str(launcher), "--mcp", "--home", str(home), "--drain-seconds", "5"]
    receipt.update(command=command, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(), negative=NEGATIVE)
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=900) as client:
        async def call(name, values):
            response = await client.call_tool(name, values, read_timeout_seconds=900)
            assert not response.is_error, response
            return response.structured_content or {}

        await call("set_model", {"name": args.checkpoint})
        params = {"prompt": "a red fox resting in a snowy forest, soft daylight", "negative_prompt": NEGATIVE,
                  "width": args.width, "height": args.height, "steps": 20, "seed": 20261103,
                  "sampler_name": "Euler", "return_images": False, "model_keyword": False}
        generated = await call("txt2img", params)
        path = Path(generated["images"][0]["path"])
        with Image.open(path) as image:
            assert image.size == (args.width, args.height)
            infotext = image.info["parameters"]
            assert f"Negative prompt: {NEGATIVE}" in infotext, infotext
        metadata = await call("inspect_generation_metadata", {"image": str(path)})
        assert metadata["generation_args"]["negative_prompt"] == NEGATIVE, metadata
        assert f"Negative prompt: {NEGATIVE}" in generated["images"][0]["infotext"]
        receipt.update(success=True, image=str(path), image_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                       png_infotext=infotext, inspected_negative=metadata["generation_args"]["negative_prompt"])


def main():
    parser = argparse.ArgumentParser()
    for name in ("source", "launcher", "jar", "python", "models", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    receipt = {"success": False, "checkpoint": args.checkpoint, "width": args.width, "height": args.height}
    try:
        asyncio.run(run(args, receipt))
    finally:
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
