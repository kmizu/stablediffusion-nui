"""External MCP VAE snapshot and provenance check against a real checkpoint."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image

async def run(args, receipt):
    jar = args.jar.resolve()
    if args.launcher:
        launcher = args.launcher.resolve()
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
    command = ([str(launcher), "--mcp", "--home", str(home), "--drain-seconds", "5"] if args.launcher else
               [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(jar),
                "sdfx.mcp.McpMain", "--home", str(home), "--drain-seconds", "5"])
    receipt.update(command=command, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=900) as client:
        async def call(name, values):
            response = await client.call_tool(name, values, read_timeout_seconds=900)
            assert not response.is_error, response
            return response.structured_content or {}

        await call("set_model", {"name": args.checkpoint})
        params = {"prompt": "a red fox resting in a snowy forest, soft daylight", "width": args.width,
                  "height": args.height, "steps": 12, "seed": 20261103, "sampler_name": "Euler",
                  "return_images": False, "model_keyword": False}
        generated = await call("txt2img", params)
        source = Path(generated["images"][0]["path"])
        before = hashlib.sha256(source.read_bytes()).hexdigest()
        report = await call("vae_tools", {"input_paths": [str(source)], "operation": "encode+decode"})
        item = report["results"][0]
        assert item["status"] == "complete", item
        assert Path(item["input_path"]) == source.resolve(), item
        assert item["input_sha256"] == before, item
        assert hashlib.sha256(source.read_bytes()).hexdigest() == before
        with Image.open(item["path"]) as image:
            assert image.size == (args.width, args.height)
        persisted = json.loads(Path(report["report_path"]).read_text(encoding="utf8"))
        assert persisted["results"][0]["input_path"] == str(source.resolve())
        receipt.update(success=True, source=str(source), source_sha256=before,
                       image=item["path"], image_sha256=hashlib.sha256(Path(item["path"]).read_bytes()).hexdigest(),
                       report=report["report_path"])



def main():
    parser = argparse.ArgumentParser()
    for name in ("source", "jar", "python", "models", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--launcher", type=Path)
    parser.add_argument("--java", default="java")
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
