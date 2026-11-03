"""Exercise the fixed Unix helper on an actual Linux filesystem; argument is extracted helper source."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

helper = Path(sys.argv[1]).read_text(encoding="utf-8")
root = Path(tempfile.mkdtemp(prefix="verified-root-"))
directory = root / "images"
directory.mkdir()
outside = Path(tempfile.mkdtemp(prefix="verified-outside-"))
(directory / "in.png").write_bytes(b"inside")
(outside / "in.png").write_bytes(b"outside")

def launch():
    p = subprocess.Popen([sys.executable, "-I", "-c", helper], stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    p.stdin.write((json.dumps({"path": str(directory / "in.png"), "roots": [str(root)], "maxBytes": 48}) + "\n").encode())
    p.stdin.flush()
    return p

p = launch()
assert p.stdout.readline() == b"READY 6\n"
directory.rename(root / "moved")
directory.symlink_to(outside, target_is_directory=True)
out, err = p.communicate(b"COPY\n", timeout=10)
assert p.returncode == 0 and out == b"inside", (out, err)
p = launch()
out, err = p.communicate(timeout=10)
assert p.returncode != 0 and out == b"", (out, err)
directory.unlink()
directory.mkdir()
os.mkfifo(directory / "in.png")
p = launch()
out, err = p.communicate(timeout=10)
assert p.returncode != 0 and out == b"", (out, err)
print("VERIFIED_UNIX_INPUT_OK")
