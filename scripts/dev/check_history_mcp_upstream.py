"""Read a GUI-imported upstream-JS fixture using the official MCP SDK."""
import argparse
import asyncio
import json
import os
import shutil
from pathlib import Path
from check_history_mcp_stdio import connect


async def main(args):
    out = Path(args.output).resolve()
    home = out / "home"
    dest = home / "config" / "generation-history"
    dest.mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": args.python}), encoding="utf-8")
    target = dest / "library-v1.json"
    shutil.copyfile(args.library, target)
    before = target.read_bytes()
    env = dict(os.environ, PYTHONPATH=str(Path(args.source).resolve() / "python"))
    async with connect(args, home, env, out) as client:
        async def call(params):
            result = (await client.call_tool("generation_history", params)).model_dump(mode="json")
            assert not result.get("isError", result.get("is_error", False)), result
            return result.get("structuredContent", result.get("structured_content"))
        listing = await call({"action": "list", "mode": "txt2img"})
        assert len(listing["entries"]) == 1
        assert "actual upstream saved prompt" not in json.dumps(listing)
        details = await call({"action": "get", "mode": "txt2img", "id": listing["entries"][0]["id"]})
        fields = details["fields"]
        assert fields["generation.prompt"]["value"] == "actual upstream saved prompt"
        assert fields["generation.seed"]["value"] == "123"
        assert fields["model.clipSkip"]["value"] == "2"
        assert "originalFields" not in json.dumps(details)
    assert target.read_bytes() == before
    (out / "receipt.json").write_text(json.dumps({"listing": listing, "details": details}, indent=2), encoding="utf-8")
    print(f"UPSTREAM_FIXTURE_MCP_{args.transport.upper()}_OK", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--transport", choices=["stdio", "http"], required=True)
    for name in ("java", "jar", "library", "output", "python", "source"):
        p.add_argument("--" + name, required=True)
    asyncio.run(main(p.parse_args()))
