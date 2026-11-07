"""Load a real cached PyTorch state dict through the OpenCLIP backend in an isolated process."""
import argparse
import json
from pathlib import Path
import torch
import open_clip.pretrained
from PIL import Image
from sdfx.clip_interrogator import OpenClip

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("weights")
    parser.add_argument("image")
    parser.add_argument("output")
    args = parser.parse_args()
    torch.set_num_threads(2)
    old = open_clip.pretrained.hf_hub_download
    try:
        path = str(Path(args.weights).resolve(strict=True))
        open_clip.pretrained.hf_hub_download = lambda repo, filename=None, cache_dir=None, local_files_only=False: path
        clip = OpenClip("RN50", "yfcc15m", "cpu")
    finally:
        open_clip.pretrained.hf_hub_download = old
    assert any(type(m).__name__ == "QuickGELU" for m in clip.model.modules())
    with Image.open(args.image) as image:
        features = clip.encode_image(image)
    text = clip.encode_text(["an anime portrait", "a photograph of a city"])
    assert torch.isfinite(features).all() and torch.isfinite(text).all()
    assert features.shape == (1, text.shape[-1])
    scores = (features @ text.T).flatten().tolist()
    output = Path(args.output); output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"model": "RN50/yfcc15m", "weights": path, "image": args.image,
                                  "quick_gelu": True, "scores": scores}, indent=2), encoding="utf-8")
    print("OPENCLIP_REAL_PT_CPU_OK", flush=True)

if __name__ == "__main__":
    main()
