"""Check get_image with the official MCP SDK and a local source PNG."""
import argparse
import asyncio
import base64
import hashlib
import io
import json
import os
from pathlib import Path
from mcp import Client, StdioServerParameters
from PIL import Image


async def run(a):
    out = Path(a.output).resolve()
    home = out / "home"
    (home / "python").mkdir(parents=True, exist_ok=True)
    (home / "config").mkdir(exist_ok=True)
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": str(Path(a.python).resolve())}), encoding="utf-8")
    source = Path(a.input).resolve()
    original = source.read_bytes()
    env = dict(os.environ, PYTHONPATH=str(Path(a.source).resolve() / "python"))
    params = StdioServerParameters(command=a.java, args=["-Dfile.encoding=UTF-8", "-cp", str(Path(a.jar).resolve()),
        "sdfx.mcp.McpMain", "--home", str(home), "--dry-run", "--allow-dir", str(source.parent)], env=env)
    receipt = {"jar_sha256": hashlib.sha256(Path(a.jar).read_bytes()).hexdigest()}
    async with Client(params, read_timeout_seconds=120) as client:
        result = (await client.call_tool("get_image", {"path": str(source), "max_side": 0})).model_dump(mode="json")
        assert not result.get("isError", result.get("is_error", False)), result
        block = next(x for x in result["content"] if x["type"] == "image")
        assert base64.b64decode(block["data"]) == original
        expected = Image.open(source).info.get("parameters")
        structured = result.get("structuredContent", result.get("structured_content"))
        assert structured["infotext"] == expected, (structured, expected)
        small = (await client.call_tool("get_image", {"path": str(source), "max_side": 128})).model_dump(mode="json")
        assert not small.get("isError", small.get("is_error", False)), small
        small_block = next(x for x in small["content"] if x["type"] == "image")
        resized = Image.open(io.BytesIO(base64.b64decode(small_block["data"])))
        assert max(resized.size) <= 128
        assert small.get("structuredContent", small.get("structured_content"))["infotext"] == expected
        receipt.update(source_sha256=hashlib.sha256(original).hexdigest(), result=result, resized=small)
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print("GET_IMAGE_VERIFIED_OK", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("source", "output", "java", "jar", "python", "input"):
        parser.add_argument("--" + name, required=True)
    asyncio.run(run(parser.parse_args()))
