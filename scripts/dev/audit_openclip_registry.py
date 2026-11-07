"""Read HF model metadata for the installed OpenCLIP registry; do not download weights."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import posixpath
from pathlib import Path
import open_clip
from huggingface_hub import HfApi

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    rows = []
    for model, tag in open_clip.list_pretrained():
        cfg = open_clip.get_pretrained_cfg(model, tag)
        ref = cfg.get("hf_hub", "")
        repo, filename = posixpath.split(ref)
        rows.append({"name": f"{model}/{tag}", "repo": repo, "filename": filename or None,
                     "source_url": f"https://huggingface.co/{repo}" if repo else None})
    api = HfApi()
    def get(repo):
        try:
            info = api.model_info(repo, timeout=20)
            card = info.card_data.to_dict() if info.card_data else {}
            return {"revision": info.sha, "license": card.get("license"), "license_name": card.get("license_name"),
                    "license_link": card.get("license_link"), "gated": info.gated, "private": info.private}
        except Exception as e:
            return {"error": type(e).__name__}
    repos = sorted({row["repo"] for row in rows if row["repo"]})
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = dict(zip(repos, pool.map(get, repos)))
    for row in rows:
        row["metadata"] = results.get(row["repo"], {"error": "missing-reference"})
    out = Path(args.output); out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"openclip_version": open_clip.__version__, "models": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("registry", len(rows), "repos", len(repos), "failed", sum("error" in v for v in results.values()), flush=True)

if __name__ == "__main__":
    main()
