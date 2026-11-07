"""Exercise history via an external official MCP SDK client and a source-built jar."""
import argparse
import asyncio
import json
import os
import socket
import subprocess
import time
from contextlib import asynccontextmanager
from pathlib import Path

from mcp import Client, StdioServerParameters


@asynccontextmanager
async def connect(args, home, env, out):
    command = [args.java, "-cp", str(Path(args.jar).resolve()), "sdfx.mcp.McpMain", "--home", str(home), "--dry-run"]
    if args.transport == "stdio":
        async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env), read_timeout_seconds=60) as client:
            yield client
    else:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        with (out / "server-stderr.log").open("w", encoding="utf-8") as log:
            server = subprocess.Popen(command + ["--http", str(port)], env=env, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 30
                while True:
                    if server.poll() is not None:
                        raise RuntimeError(f"server exited {server.returncode}")
                    try:
                        with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                            break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise TimeoutError("HTTP server did not start")
                        await asyncio.sleep(0.1)
                async with Client(f"http://127.0.0.1:{port}/mcp", read_timeout_seconds=60) as client:
                    yield client
            finally:
                server.terminate()
                server.wait(timeout=30)


async def main(args):
    out = Path(args.output).resolve()
    home = out / "home"
    directory = home / "config" / "generation-history"
    directory.mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": args.python}), encoding="utf-8")
    doc = json.loads(Path(args.fixture).read_text(encoding="utf-8"))
    for collection in doc["collections"]:
        for record in collection["presets"]:
            record["name"] = "private-name-sentinel"
            record["source"]["originalFields"]["entry"]["preview"] = "private-image-sentinel"
    target = directory / "library-v1.json"
    target.write_text(json.dumps(doc), encoding="utf-8")
    before = target.read_bytes()
    env = dict(os.environ, PYTHONPATH=str(Path(args.source).resolve() / "python"))
    async with connect(args, home, env, out) as client:
        async def call(data):
            result = (await client.call_tool("generation_history", data)).model_dump(mode="json")
            assert not result.get("isError", result.get("is_error", False)), result
            return result.get("structuredContent", result.get("structured_content"))
        listing = await call({"action": "list", "mode": "img2img", "collection": "favourites"})
        assert listing["entries"][0]["name"] == "private-name-sentinel"
        assert "imported prompt" not in json.dumps(listing)
        details = await call({"action": "get", "mode": "img2img", "collection": "favourites",
                              "id": listing["entries"][0]["id"]})
        assert details["fields"]["generation.prompt"]["value"] == "imported prompt"
        assert "private-image-sentinel" not in json.dumps(details)
        assert "originalFields" not in json.dumps(details)
    assert target.read_bytes() == before
    out.joinpath("receipt.json").write_text(json.dumps({"listing": listing, "details": details}, indent=2), encoding="utf-8")
    print(f"HISTORY_MCP_{args.transport.upper()}_OK", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--transport", choices=["stdio", "http"], default="stdio")
    for name in ("java", "jar", "fixture", "output", "python", "source"):
        p.add_argument("--" + name, required=True)
    asyncio.run(main(p.parse_args()))
