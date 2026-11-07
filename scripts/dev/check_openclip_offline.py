"""Verify local HF cache hit and cache miss with socket connection attempts forbidden."""
import os
import socket
from pathlib import Path

assert os.environ.get("HF_HUB_OFFLINE") == "1"
import open_clip
from sdfx.clip_interrogator import openclip_hub_download

attempts = []
def forbidden(*args, **kwargs):
    attempts.append(str(args))
    raise AssertionError("unexpected network connection")

socket.socket.connect = forbidden
socket.create_connection = forbidden
cfg = open_clip.get_pretrained_cfg("convnext_base_w", "laion2b_s13b_b82k")
cached = openclip_hub_download(open_clip.pretrained, cfg)
assert Path(cached).is_file()
try:
    openclip_hub_download(open_clip.pretrained, {"hf_hub": "does-not-exist-for-sdnui/cache-miss-test/"})
except Exception as e:
    assert not isinstance(e, AssertionError), e
    print("OFFLINE_MISS", type(e).__name__, flush=True)
else:
    raise AssertionError("missing model unexpectedly resolved")
assert attempts == [], attempts
print("OPENCLIP_OFFLINE_OK", flush=True)
