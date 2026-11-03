"""Inspect a real Fastload fixture via the official MCP SDK and a dry-run worker."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
from mcp import Client, StdioServerParameters


async def run(a):
    out = Path(a.output).resolve()
    home = out / "home"
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": str(Path(a.python).resolve())}), encoding="utf-8")
    source = Path(a.input).resolve()
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    env = dict(os.environ, PYTHONPATH=str(Path(a.source).resolve() / "python"))
    params = StdioServerParameters(command=a.java, args=["-Dfile.encoding=UTF-8", "-cp", str(Path(a.jar).resolve()),
        "sdfx.mcp.McpMain", "--home", str(home), "--dry-run", "--allow-dir", str(source.parent)], env=env)
    async with Client(params, read_timeout_seconds=120) as client:
        result = (await client.call_tool("controlnet_fastload", {"operation": "inspect", "path": str(source)})).model_dump(mode="json")
        assert not result.get("isError", result.get("is_error", False)), result
        assert "test-model" in json.dumps(result), result
        assert hashlib.sha256(source.read_bytes()).hexdigest() == before
    (out / "receipt.json").write_text(json.dumps({"result": result, "input_sha256": before,
        "jar_sha256": hashlib.sha256(Path(a.jar).read_bytes()).hexdigest()}, indent=2), encoding="utf-8")
    print("FASTLOAD_SNAPSHOT_MCP_OK", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("source", "output", "java", "jar", "python", "input"):
        parser.add_argument("--" + name, required=True)
    asyncio.run(run(parser.parse_args()))
