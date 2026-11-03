"""Real-model MCP generation with a generation-time Fastload file input."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
from mcp import Client, StdioServerParameters
from PIL import Image


async def run(a):
    out = Path(a.output).resolve()
    home, images = out / "home", out / "images"
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    images.mkdir(exist_ok=True)
    source = Path(a.input).resolve()
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    settings = {"python_exe": str(Path(a.python).resolve()), "output_dir": str(images), "last_model": a.model,
                "model_dirs": {"checkpoints": [str(Path(a.models).resolve())]}, "vram_mode": "model_offload", "preview_every": 0}
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(Path(a.source).resolve() / "python"))
    params = StdioServerParameters(command=a.java, args=["-Dfile.encoding=UTF-8", "-cp", str(Path(a.jar).resolve()),
        "sdfx.mcp.McpMain", "--home", str(home), "--allow-dir", str(source.parent)], env=env)
    receipt = {"jar_sha256": hashlib.sha256(Path(a.jar).read_bytes()).hexdigest(), "calls": []}
    async with Client(params, read_timeout_seconds=1800) as client:
        async def call(name, args):
            response = (await client.call_tool(name, args, read_timeout_seconds=1800)).model_dump(mode="json")
            receipt["calls"].append({"tool": name, "args": args, "result": response})
            assert not response.get("isError", response.get("is_error", False)), response
            return response.get("structuredContent", response.get("structured_content"))
        await call("set_model", {"name": a.model})
        result = await call("txt2img", {"prompt": "anime portrait, looking at viewer", "negative_prompt": "blurry, lowres",
            "width": a.side, "height": a.side, "steps": 12, "cfg_scale": 5, "seed": 2468, "sampler_name": "Euler",
            "model_keyword": False, "return_images": False,
            "always_on": {"controlnet_fastload": {"enabled": True, "mode": "load_only", "priority": "loaded_first", "path": str(source)}}})
        assert len(result["images"]) == 1, result
        with Image.open(result["images"][0]["path"]) as image:
            assert image.size == (a.side, a.side)
        assert hashlib.sha256(source.read_bytes()).hexdigest() == before
        assert not list((images / ".tmp").glob("*.cni")), "Fastload snapshot remains after the job"
        receipt.update(success=True, image=result["images"][0]["path"], source_sha256=before)
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print("FASTLOAD_GENERATION_MCP_OK", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for name in ("source", "output", "java", "jar", "python", "models", "model", "input"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--side", type=int, default=512)
    asyncio.run(run(p.parse_args()))
