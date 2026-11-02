"""Real OpenCLIP engine-entry check on two existing model-generated images; writes a receipt."""
import argparse
import json
from pathlib import Path
from types import SimpleNamespace

from sdfx import clip_interrogator as ci

class Context:
    def check_interrupt(self):
        pass
    def progress(self, *args):
        print("progress", args, flush=True)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", required=True)
    ap.add_argument("--model", default="convnext_base_w/laion2b_s13b_b82k")
    ap.add_argument("--weights-path", help="test-only local weight override of the registry's download function")
    ap.add_argument("images", nargs=2)
    args = ap.parse_args()
    out = Path(args.output).resolve(); out.mkdir(parents=True, exist_ok=True)
    engine = SimpleNamespace(device="cuda:0")
    name = args.model
    restore_download = None
    if args.weights_path:
        import open_clip.pretrained
        restore_download = open_clip.pretrained.hf_hub_download
        path = str(Path(args.weights_path).resolve(strict=True))
        open_clip.pretrained.hf_hub_download = lambda repo, filename=None, cache_dir=None, local_files_only=False: path
    assert name in ci.run(engine, {"operation": "models"}, Context())["clip_models"]
    results = []
    try:
        for image in args.images:
            result = ci.run(engine, {"operation": "analyze", "image_path": image, "clip_model": name,
                                     "keep_loaded": True, "cache_dir": str(out / "cache")}, Context())
            assert result["clip_model"] == name
            assert all(len(result[k]) == 5 for k in ci.ANALYZE_KEYS)
            negative = ci.run(engine, {"operation": "prompt", "mode": "negative", "image_path": image,
                                       "clip_model": name, "keep_loaded": True, "cache_dir": str(out / "cache")}, Context())
            assert negative["prompt"].strip()
            fast = ci.run(engine, {"operation": "prompt", "mode": "fast", "image_path": image,
                                   "clip_model": name, "caption_model": "blip-base", "keep_loaded": True,
                                   "cache_dir": str(out / "cache")}, Context())
            assert fast["caption"].strip() and fast["prompt"].startswith(fast["caption"])
            results.append({"image": image, "analysis": result, "negative": negative, "fast": fast})
            print("checked", image, flush=True)
        (out / "receipt.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        print("OPENCLIP_ENGINE_OK", flush=True)
    finally:
        ci.release()
        if restore_download is not None:
            import open_clip.pretrained
            open_clip.pretrained.hf_hub_download = restore_download

if __name__ == "__main__":
    main()
