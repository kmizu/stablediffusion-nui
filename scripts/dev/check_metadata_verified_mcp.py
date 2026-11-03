"""Offline official SDK acceptance for verified PNG metadata input on stdio and HTTP."""
import argparse
import asyncio
import base64
import json
import os
from pathlib import Path

from PIL import Image, PngImagePlugin
from check_history_mcp_stdio import connect


async def main(args):
    out = Path(args.output).resolve()
    home = out / "home"
    images = home / "outputs"
    images.mkdir(parents=True, exist_ok=True)
    (home / "config").mkdir(exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": args.python, "output_dir": str(images)}), encoding="utf-8")
    info = PngImagePlugin.PngInfo()
    info.add_text("parameters", "verified metadata cat\nSteps: 20, Seed: 4, Size: 512x512")
    path = images / "metadata.png"
    Image.new("RGB", (4, 4)).save(path, pnginfo=info)
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    env = dict(os.environ, PYTHONPATH=str(Path(args.source).resolve() / "python"))
    async with connect(args, home, env, out) as client:
        async def invoke(value):
            result = await client.call_tool("inspect_generation_metadata", {"image": value}, read_timeout_seconds=60)
            return result.model_dump(mode="json")
        path_result = await invoke(str(path))
        data_result = await invoke(encoded)
        for result in (path_result, data_result):
            assert not result.get("isError", result.get("is_error", False)), result
            data = result.get("structuredContent", result.get("structured_content"))
            assert data["generation_args"]["prompt"] == "verified metadata cat", data
        assert path_result.get("structuredContent", path_result.get("structured_content")) == data_result.get("structuredContent", data_result.get("structured_content"))
        outside = out / "outside.png"
        outside.write_bytes(path.read_bytes())
        rejected = await invoke(str(outside))
        assert rejected.get("isError", rejected.get("is_error", False)), rejected
    print("METADATA_VERIFIED_MCP_" + args.transport.upper() + "_OK", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", choices=["stdio", "http"], required=True)
    for name in ("java", "jar", "output", "python", "source"):
        parser.add_argument("--" + name, required=True)
    asyncio.run(main(parser.parse_args()))
