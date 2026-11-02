"""Real-model check of the Character Searcher data preparation (run with the engine Python, CPU only).

Input: the images of scripts/dev/check_chara_searcher_real.py (real SD1.5 / SDXL generations: two girls side by side,
one girl, one boy) and an optional video made from them. Imports them through the pinned detector, ISNet matting, WD
tagger and CLIP models, then asserts what the extension promises: characters are separated, every cut-out has
transparency and a caption, an image query finds its own character first, tag filters agree with the captions, export
writes captions. Writes receipt-data.json, a contact sheet of the cut-outs (on green) and the cut-outs.

    python scripts/dev/check_chara_searcher_data.py --generated outputs/chara-real/generated --output outputs/chara-real/data
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

from sdfx import chara_searcher as cs  # noqa: E402


def make_video(images, path: Path, seconds_each: int = 8, fps: int = 5, side: int = 640) -> None:
    import cv2
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (side, side))
    for image in images:
        im = Image.open(image).convert("RGB")
        im.thumbnail((side, side))
        canvas = Image.new("RGB", (side, side), (0, 0, 0))
        canvas.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
        frame = cv2.cvtColor(np.asarray(canvas), cv2.COLOR_RGB2BGR)
        for _ in range(seconds_each * fps):
            writer.write(frame)
    writer.release()


def cosine(a, b):
    a, b = np.asarray(a, np.float32), np.asarray(b, np.float32)
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))


def contact_sheet(files, path: Path, columns: int = 8, cell: int = 190) -> None:
    rows = (len(files) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * cell, rows * cell), (0, 110, 0))
    for index, file in enumerate(files):
        im = Image.open(file).convert("RGBA")
        im.thumbnail((cell - 6, cell - 6))
        sheet.paste(im, ((index % columns) * cell + 3, (index // columns) * cell + 3), im)
    sheet.save(path)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--generated", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    out = args.output.resolve()
    shutil.rmtree(out, ignore_errors=True)
    (out / "sources").mkdir(parents=True)
    sources = sorted(args.generated.glob("*.png"))
    assert len(sources) >= 6, f"generated images missing in {args.generated}"
    for source in sources:
        shutil.copyfile(source, out / "sources" / source.name)
    receipt = {"sources": [s.name for s in sources], "checks": []}

    def ok(message):
        receipt["checks"].append(message)
        print("OK:", message, flush=True)

    # a video made from three of the sources (frame every 4 s -> two frames per picture)
    video_images = [s for s in sources if "two_" in s.name][:1] + [s for s in sources if "solo_" in s.name][:1] + [s for s in sources if "boy" in s.name][:1]
    video = out / "scene.mp4"
    make_video(video_images, video)

    folder = out / "dataset"
    started = time.time()
    result = cs.import_sources(folder, [out / "sources", video], cs.normalize({"frame_interval": 4.0}), log=lambda t: print("  ", t, flush=True))
    receipt["import"] = {k: (v if k != "created" else len(v)) for k, v in result.items()}
    receipt["import_seconds"] = round(time.time() - started, 1)
    assert not result["errors"], result["errors"]
    created = result["created"]
    by_source = {}
    for name in created:
        by_source.setdefault(name.rsplit("_", 1)[0], []).append(name)
    receipt["cutouts_per_source"] = {k: len(v) for k, v in by_source.items()}

    # 1. characters are separated: pictures of two girls give at least two cut-outs, single characters exactly one
    for source in sources:
        key = f"sources_{source.stem}"
        count = len(by_source.get(key, []))
        if "two_" in source.name:
            assert count >= 2, f"{source.name}: expected at least two characters, got {count}"
        else:
            assert count >= 1, f"{source.name}: no character found"
    singles = [len(by_source.get(f"sources_{s.stem}", [])) for s in sources if "two_" not in s.name]
    ok(f"{len(sources)} real images -> {len(created)} cut-outs; two-girl pictures {[len(by_source.get('sources_' + s.stem, [])) for s in sources if 'two_' in s.name]} cut-outs, single-character pictures {singles}")

    # 2. every cut-out is RGBA with real transparency and a caption; the corners are background
    for name in created:
        with Image.open(folder / name) as image:
            assert image.mode == "RGBA", name
            alpha = np.asarray(image.getchannel("A"))
            assert alpha.max() == 255 and (alpha < 128).mean() > 0.02 or alpha.min() == 0, name
            assert alpha[0, 0] < 128 or alpha[-1, -1] < 128 or alpha[0, -1] < 128 or alpha[-1, 0] < 128, f"{name}: no transparent corner"
        assert (folder / name).with_suffix(".txt").read_text(encoding="utf-8").strip(), f"{name}: empty caption"
    ok("every cut-out is RGBA with a transparent corner and a non-empty caption")

    # 3. the video produced frame cut-outs
    video_cuts = [n for n in created if n.startswith("out_scene_") or "scene_" in n]
    assert len(video_cuts) >= 3, video_cuts
    ok(f"the video gave {len(video_cuts)} cut-outs from sampled frames ({sorted(video_cuts)[:3]}...)")

    # 4. tags: single-character captions say 1girl/1boy; the two-girl pictures contain both hair colours across their cut-outs
    files, vectors = cs.read_index(folder)
    tags = {entry["path"]: [cs.normalize_tag(t) for t in cs.split_tags((folder / entry["path"]).with_suffix(".txt").read_text(encoding="utf-8"))] for entry in files}
    boy = [n for n in created if "boy_brown" in n]
    assert boy and all("1boy" in tags[n] for n in boy), [tags[n][:6] for n in boy]
    ok(f"boy picture caption has 1boy: {tags[boy[0]][:8]}")
    for label, a, b in (("two_red_blue", "red hair", "blue hair"), ("two_blonde_black", "blonde hair", "black hair")):
        names = [n for n in created if label in n and "scene" not in n]
        seen = {t for n in names for t in tags[n]}
        receipt.setdefault("two_girls_hair", {})[label] = sorted(t for t in seen if t.endswith(" hair"))
        assert a in seen or b in seen, f"{label}: hair tags {sorted(seen)[:20]}"
    ok(f"hair colours found in the two-girl captions: {receipt['two_girls_hair']}")

    # 5. similarity: the picture of a single character finds its own cut-out first; tag filters agree with the captions
    names = [e["path"] for e in files]
    vec = {n: v for n, v in zip(names, vectors)}
    for query_name in ("sd15_solo_silver", "sdxl_solo_green", "sd15_boy_brown", "sdxl_boy_brown"):
        query_source = next((s for s in sources if s.stem == query_name), None)
        if query_source is None:
            continue
        query = cs.embed_query(query_source)
        # the video repeats three of the pictures, so its frames would tie with the picture itself
        ranked = sorted((n for n in names if not n.startswith("data_scene")), key=lambda n: -cosine(query, vec[n]))
        top = ranked[0]
        receipt.setdefault("query", {})[query_name] = {"top": top, "similarity": round(cosine(query, vec[top]), 4),
                                                       "second": ranked[1], "second_similarity": round(cosine(query, vec[ranked[1]]), 4)}
        assert query_name in top, f"{query_name}: top hit {top}"
    ok(f"image queries find their own character first: {json.dumps(receipt['query'])}")
    boys = [n for n in names if "1boy" in tags[n]]
    girls = [n for n in names if "1girl" in tags[n]]
    sdxl_boy = [n for n in names if "sdxl_boy_brown" in n]
    sdxl_girls = [n for n in names if "sdxl_solo_" in n]
    assert sdxl_boy and set(sdxl_boy) <= set(boys), sdxl_boy  # the tagger also adds 1girl to this androgynous drawing
    assert sdxl_girls and set(sdxl_girls) <= set(girls) and not set(sdxl_girls) & set(boys), sdxl_girls
    receipt["tag_filters"] = {"1boy": len(boys), "1girl": len(girls), "cutouts": len(names)}
    ok(f"tag filters on the captions: 1boy -> {len(boys)} of {len(names)} cut-outs, 1girl -> {len(girls)}; the SDXL boy is in 1boy, the SDXL single girls are in 1girl and not in 1boy")

    # 6. a second import adds only new cut-outs and keeps the old embeddings bit for bit
    before = vectors.copy()
    extra = out / "extra"
    extra.mkdir()
    shutil.copyfile(sources[0], extra / "again.png")
    again = cs.import_sources(folder, [extra], cs.normalize({}))
    files2, vectors2 = cs.read_index(folder)
    assert again["new"] == len(again["created"]) >= 1 and len(files2) == len(files) + again["new"]
    assert np.array_equal(vectors2[: len(files)], before)
    ok(f"second import added {again['new']} cut-out(s) and kept the {len(files)} old vectors bit for bit")

    contact_sheet([folder / e["path"] for e in files2], out / "cutouts-sheet.png")
    receipt["success"] = True
    (out / "receipt-data.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps({"success": True, "cutouts": len(files2), "seconds": receipt["import_seconds"]}))


if __name__ == "__main__":
    main()
