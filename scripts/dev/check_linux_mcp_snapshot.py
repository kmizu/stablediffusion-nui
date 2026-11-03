"""Linux stdio MCP dry-run check for verified local image inputs."""
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
    send({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "img2img", "arguments": {
        "init_image": str(image), "prompt": "test", "width": 64, "height": 64,
        "steps": 1, "seed": 12, "return_images": False, "model_keyword": False}}})
    result = response(2)
    assert not result.get("isError"), result
    assert len(result["structuredContent"]["images"]) == 1, result
    assert image.is_file()
    send({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "get_image", "arguments": {
        "path": str(image), "max_side": 0}}})
    returned = response(3)
    assert not returned.get("isError"), returned
    payload = next(x for x in returned["content"] if x["type"] == "image")
    assert base64.b64decode(payload["data"]) == image.read_bytes()
    sys.path.insert(0, str(source / "python"))
    from sdfx.controlnet_fastload import encode_fastload
    cni = allowed / "input.cni"
    cni.write_bytes(encode_fastload(b"", [{"enabled": False, "model": "linux-probe-model"}]))
    send({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "controlnet_fastload", "arguments": {
        "operation": "inspect", "path": str(cni)}}})
    fastload = response(4)
    assert not fastload.get("isError"), fastload
    assert "linux-probe-model" in json.dumps(fastload), fastload
    original_cni = encode_fastload(b"", [{"enabled": False, "model": "linux-fixture"}])
    cni.write_bytes(original_cni)
    send({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "txt2img", "arguments": {
        "prompt": "test", "width": 64, "height": 64, "steps": 1, "seed": 12,
        "return_images": False, "model_keyword": False, "always_on": {"controlnet_fastload": {
            "enabled": True, "mode": "load_only", "priority": "loaded_first", "path": str(cni)}}}}})
    generated = response(5)
    assert not generated.get("isError"), generated
    assert len(generated["structuredContent"]["images"]) == 1, generated
    assert cni.read_bytes() == original_cni
    assert not list((home / "outputs" / ".tmp").glob("*.cni"))
    (home / "receipt.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("LINUX_MCP_SNAPSHOT_OK", home, flush=True)
finally:
    server.stdin.close()
    server.wait(timeout=30)
    log.close()
