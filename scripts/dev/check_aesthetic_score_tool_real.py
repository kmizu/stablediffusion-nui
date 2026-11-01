"""Independent re-scoring of PNGs written by check_aesthetic_score_real.py, through the engine's `image_tool` request
(the Extras path) with the real models. Run with the engine Python and PYTHONPATH=<tree>/python.

  python check_aesthetic_score_tool_real.py --all <png with every scorer> --output <dir> [--b16 <png>] [--cafe <png>]

The score each PNG recorded at generation time must equal the score of the same pixels read back from the file.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import shutil
import sys
from pathlib import Path

from PIL import Image

from sdfx.dryrun import FakeEngine
from sdfx.protocol import Emitter, EngineError, JobContext


def fields(path):
    with Image.open(path) as image:
        line = image.text["parameters"].splitlines()[-1]
    out = {}
    for match in re.finditer(r'\s*(\w[\w \-/]+):\s*("(?:\\.|[^\\"])+"|[^,]*)(?:,|$)', line):
        value = match.group(2).strip()
        out[match.group(1).strip()] = json.loads(value) if value.startswith('"') else value
    return out


def run_tool(engine, path, output, **settings):
    ctx = JobContext(Emitter(io.BytesIO()), "aesthetic-tool-check")
    result = engine.image_tool(dict(tool="aesthetic_score", image_path=str(path), output_dir=str(output), **settings), ctx)
    with Image.open(path) as original, Image.open(result["path"]) as returned:
        assert original.convert("RGB").tobytes() == returned.convert("RGB").tobytes(), "the tool changed the pixels"
    return result["info"]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--all", type=Path, nargs="+", required=True)
    p.add_argument("--b16", type=Path, nargs="*", default=[])
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    engine = FakeEngine()
    receipt = {"all": [], "b16": []}
    for path in args.all:
        recorded = fields(path)
        info = run_tool(engine, path, args.output, improved=True, simulacra="vit-l-14", cafe_aesthetic=True, cafe_style=True, cafe_waifu=True)
        assert abs(float(info["aesthetic_score"]) - float(recorded["aesthetic_score"])) <= 0.11, (info, recorded)
        assert abs(float(info["Aesthetic Scorer ViT-L/14"]) - float(recorded["Aesthetic Scorer ViT-L/14"])) <= 0.011, (info, recorded)
        for key in ("Cafe aesthetic", "Cafe style", "Cafe waifu"):
            label, probability = info[key].split()
            recorded_label, recorded_probability = recorded[key].split()
            assert label == recorded_label and abs(float(probability) - float(recorded_probability)) <= 0.002, (key, info[key], recorded[key])
        receipt["all"].append({"path": str(path), "recorded": {k: recorded[k] for k in info}, "tool": info})
    for path in args.b16:
        recorded = fields(path)
        info = run_tool(engine, path, args.output, improved=False, cafe_aesthetic=False, simulacra="vit-b-16")
        assert list(info) == ["Aesthetic Scorer ViT-B/16"], info
        assert abs(float(info["Aesthetic Scorer ViT-B/16"]) - float(recorded["Aesthetic Scorer ViT-B/16"])) <= 0.011, (info, recorded)
        receipt["b16"].append({"path": str(path), "recorded": recorded["Aesthetic Scorer ViT-B/16"], "tool": info})
    try:
        run_tool(engine, args.all[0], args.output, improved=False, cafe_aesthetic=False)
    except EngineError as error:
        receipt["no_scorer_error"] = str(error)
    else:
        raise AssertionError("an empty scorer selection must be rejected")
    receipt["success"] = True
    (args.output / "tool-receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    sys.exit(main())
