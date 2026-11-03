"""Linux stdio MCP VAE snapshot check with a byte-observing dry-run worker fixture."""
import json
import base64
import os
from pathlib import Path
import select
import shutil
import subprocess
import sys
import tempfile
from PIL import Image

jar, source = map(Path, sys.argv[1:3])
home = Path(tempfile.mkdtemp(prefix="sdfx-linux-mcp-"))
shutil.copytree(source / "python" / "sdfx", home / "python" / "sdfx")
base = home / "python" / "sdfx" / "base.py"
text = base.read_text(encoding="utf-8")
old = "raise EngineError(UNSUPPORTED, 'VAE Tools requires a real loaded VAE; dry-run cannot encode or decode latents')"
assert old in text
text = text.replace(old, "return {'snapshot': params['input_paths'][0], 'label': params['input_labels'][0], 'bytes': open(params['input_paths'][0], 'rb').read().hex()}")
base.write_text(text, encoding="utf-8")
(home / "config").mkdir()
(home / "config" / "settings.json").write_text(json.dumps({"python_exe": "/usr/bin/python3"}), encoding="utf-8")
allowed = home / "allowed"
allowed.mkdir()
image = allowed / "input.png"
Image.new("RGB", (64, 64), (31, 97, 155)).save(image)
log = (home / "server.log").open("wb")
server = subprocess.Popen(["java", "-Djava.awt.headless=true", "-cp", str(jar), "sdfx.mcp.McpMain",
                           "--home", str(home), "--dry-run", "--allow-dir", str(allowed)],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True, bufsize=1)

def send(msg):
    server.stdin.write(json.dumps(msg) + "\n")
    server.stdin.flush()

def response(ident):
    while True:
        if not select.select([server.stdout], [], [], 90)[0]:
            raise TimeoutError("MCP response timed out; log: " + str(home / "server.log"))
        line = server.stdout.readline()
        if not line:
            raise RuntimeError("MCP exited; log: " + str(home / "server.log"))
        msg = json.loads(line)
        if msg.get("id") == ident:
            assert "error" not in msg, msg
            return msg["result"]

try:
    send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "linux-snapshot-probe", "version": "1"}}})
    response(1)
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    send({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "vae_tools", "arguments": {
        "input_paths": [str(image)], "operation": "encode+decode"}}})
    result = response(2)
    assert not result.get("isError"), result
    item = result["structuredContent"]
    assert item["label"] == str(image.resolve())
    assert item["snapshot"] != str(image.resolve())
    assert item["bytes"] == image.read_bytes().hex()
    assert not Path(item["snapshot"]).exists()
    (home / "receipt.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("LINUX_VAE_SNAPSHOT_OK", home, flush=True)

finally:
    server.stdin.close()
    server.wait(timeout=30)
    log.close()
