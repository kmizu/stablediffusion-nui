"""Exercise a candidate extracted Windows executable through the official MCP SDK."""
import argparse
import asyncio
import base64
import hashlib
import json
import os
import shutil
import socket
import subprocess
import time
from contextlib import asynccontextmanager
from pathlib import Path

from mcp import Client, StdioServerParameters


@asynccontextmanager
async def connect(args, home, env, out):
    command = [str(Path(args.launcher).resolve()), "--mcp", "--home", str(home), "--dry-run"]
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
    images = home / "outputs"
    images.mkdir(parents=True, exist_ok=True)
    (home / "config").mkdir(exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": args.python, "output_dir": str(images)}), encoding="utf-8")
    fixture = images / "fixture.png"
    shutil.copyfile(args.fixture, fixture)
    jar = Path(args.launcher).resolve().parent / "app" / "stablediffusion-nui.jar"
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    assert digest == args.jar_sha256, digest
    env = dict(os.environ, PYTHONPATH=str(Path(args.launcher).resolve().parent / "python"))
    env.pop("SDFX_HOME", None)
    async with connect(args, home, env, out) as client:
        async def inspect(image):
            result = (await client.call_tool("inspect_generation_metadata", {"image": image})).model_dump(mode="json")
            assert not result.get("isError", result.get("is_error", False)), result
            return result.get("structuredContent", result.get("structured_content"))
        by_path = await inspect(str(fixture))
        by_base64 = await inspect(base64.b64encode(fixture.read_bytes()).decode("ascii"))
        assert by_path == by_base64
        candidate = by_path["scripts"]["corridor_crawler"]
        assert candidate["placement"] == "Preserve aspect" and candidate["steps"] == 2
        assert candidate["prefab_image"] == "" and "init_image" not in by_path["generation_args"]
        assert any("initial image" in warning for warning in by_path["warnings"])
    (out / "receipt.json").write_text(json.dumps({"jar_sha256": digest, "transport": args.transport,
        "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(), "candidate": candidate}, indent=2), encoding="utf-8")
    print("CORRIDOR_REPLAY_PACKAGED_" + args.transport.upper() + "_OK", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", choices=["stdio", "http"], required=True)
    for name in ("launcher", "jar-sha256", "output", "python", "fixture"):
        parser.add_argument("--" + name, required=True)
    asyncio.run(main(parser.parse_args()))
