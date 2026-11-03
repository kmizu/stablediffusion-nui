"""Official MCP SDK smoke test for the read-only prompt library."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from check_history_mcp_stdio import connect


async def run(args):
    out = Path(args.output).resolve()
    home = out / "home"
    directory = home / "config" / "prompt-tools"
    directory.mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": args.python}), encoding="utf-8")
    target = directory / "prompts.json"
    target.write_text(json.dumps({"quality": {"prompt": "detailed, sharp", "is_negative": False},
                                  "avoid": {"prompt": "blurry", "is_negative": True}}), encoding="utf-8")
    before = target.read_bytes()
    env = dict(os.environ, PYTHONPATH=str(Path(args.source).resolve() / "python"))
    async with connect(args, home, env, out) as client:
        tools = await client.list_tools()
        assert any(t.name == "prompt_library" for t in tools.tools)
        async def call(data):
            result = (await client.call_tool("prompt_library", data)).model_dump(mode="json")
            assert not result.get("isError", result.get("is_error", False)), result
            return result.get("structuredContent", result.get("structured_content"))
        listing = await call({"action": "list", "limit": 1})
        assert listing["entries"][0]["name"] == "quality" and "detailed, sharp" not in json.dumps(listing)
        assert listing["next_offset"] == 1
        item = await call({"action": "get", "name": "avoid"})
        assert item["prompt"] == "blurry" and item["negative"] is True
    assert target.read_bytes() == before
    out.joinpath("receipt.json").write_text(json.dumps({"transport": args.transport, "listing": listing, "item": item,
        "jar_sha256": hashlib.sha256(Path(args.jar).read_bytes()).hexdigest()}, indent=2), encoding="utf-8")
    print(f"PROMPT_LIBRARY_MCP_{args.transport.upper()}_OK", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for name in ("source", "output", "java", "jar", "python"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--transport", choices=("stdio", "http"), required=True)
    asyncio.run(run(p.parse_args()))
