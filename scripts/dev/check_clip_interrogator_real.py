"""Real-model CLIP Interrogator acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

A checkpoint (SD1.5 or SDXL) generates a source image with a distinctive subject; the real CLIP ViT-L/14 and a real
BLIP caption model then interrogate it in every mode, analyze it, batch a folder in the three output formats, and the
interrogated prompt is fed back to txt2img (and the `negative` result back as a negative prompt).  Inspect the saved
images afterwards (receipt.json lists them); assertions alone are not acceptance.
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import hashlib
import json
import os
import shutil
import time
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image


async def exercise(client, args, receipt):
    receipt["calls"] = []

    async def call(name, values, expect_error=False):
        started = time.time()
        result = await client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        failed = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "seconds": round(time.time() - started, 1),
                                 "result": data or encoded.get("content")})
        assert bool(failed) == expect_error, encoded
        return data if not expect_error else encoded

    def first(data):
        assert data.get("images"), data
        return data["images"][0]["path"]

    tools = await client.list_tools()
    assert "clip_interrogate" in [t.name for t in tools.tools], "clip_interrogate missing"
    models = await call("clip_interrogate", {"operation": "models"})
    assert "ViT-L-14/openai" in models["clip_models"] and models["modes"] == ["best", "fast", "classic", "negative", "caption"]
    receipt["models"] = models
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text, watermark", width=args.width, height=args.height,
                  steps=args.steps, seed=args.seed, sampler_name="Euler", return_images=False, model_keyword=False)
    out = {"source": first(await call("txt2img", params))}
    receipt["outputs"] = out

    # Every mode on the generated image.  The first call downloads / loads the real models and builds the label
    # embeddings; keep_loaded avoids reloading them for the following calls.
    results = {}
    for mode in ("caption", "fast", "classic", "best", "negative"):
        results[mode] = await call("clip_interrogate", {"operation": "prompt", "image": out["source"], "mode": mode,
                                                          "keep_loaded": True, "clip_model": args.clip_model})
        assert results[mode]["prompt"].strip(), mode
        assert results[mode]["clip_model"] == args.clip_model
    receipt["prompts"] = {k: v["prompt"] for k, v in results.items()}
    receipt["similarities"] = {k: v["similarity"] for k, v in results.items()}
    assert results["caption"]["prompt"] == results["fast"]["caption"] == results["best"]["caption"], "one caption per image"
    assert results["fast"]["prompt"].startswith(results["caption"]["prompt"])
    assert results["classic"]["prompt"].startswith(results["caption"]["prompt"].split(",")[0][:10])
    assert results["negative"]["caption"] == ""
    # best picks the most similar of caption / classic / fast / chain, so it cannot lose to caption or fast.
    assert results["best"]["similarity"] >= results["caption"]["similarity"] - 1e-3, receipt["similarities"]
    assert results["best"]["similarity"] >= results["fast"]["similarity"] - 1e-3, receipt["similarities"]
    assert results["fast"]["similarity"] > 0.15, receipt["similarities"]
    assert (await call("clip_interrogate", {"operation": "models"}))["loaded"] == args.clip_model

    analysis = await call("clip_interrogate", {"operation": "analyze", "image": out["source"], "keep_loaded": True,
                                               "clip_model": args.clip_model})
    for key in ("medium", "artist", "movement", "trending", "flavor"):
        assert len(analysis[key]) == 5, (key, analysis[key])
        scores = list(analysis[key].values())
        assert scores == sorted(scores, reverse=True), (key, scores)
    receipt["analysis"] = analysis

    # Re-determinism: the same call returns the same prompt.
    again = await call("clip_interrogate", {"operation": "prompt", "image": out["source"], "mode": "fast", "keep_loaded": True,
                                            "clip_model": args.clip_model})
    assert again["prompt"] == results["fast"]["prompt"]

    # Round trip: interrogated prompts back into txt2img with the same seed; negative result as negative prompt.
    out["from_fast"] = first(await call("txt2img", dict(params, prompt=results["fast"]["prompt"])))
    out["from_best"] = first(await call("txt2img", dict(params, prompt=results["best"]["prompt"])))
    out["from_best_negated"] = first(await call("txt2img", dict(params, prompt=results["best"]["prompt"],
                                                                negative_prompt=results["negative"]["prompt"])))

    # Base64 input is accepted and equals the path input.
    import base64
    encoded = "data:image/png;base64," + base64.b64encode(Path(out["source"]).read_bytes()).decode()
    via_b64 = await call("clip_interrogate", {"operation": "prompt", "image": encoded, "mode": "fast", "keep_loaded": True,
                                              "clip_model": args.clip_model})
    assert via_b64["prompt"] == results["fast"]["prompt"]

    # Batch: three images (two more generations), three output formats, no-overwrite and overwrite semantics.
    folder = Path(args.output).resolve() / "images" / "batch"
    folder.mkdir(parents=True, exist_ok=True)
    out["batch_images"] = []
    for i, subject in enumerate(("a lighthouse on a cliff at sunset", "a bowl of ramen on a wooden table")):
        path = first(await call("txt2img", dict(params, prompt=subject, seed=args.seed + 1 + i)))
        shutil.copy(path, folder / f"img{i + 1}.png")
        out["batch_images"].append(str(folder / f"img{i + 1}.png"))
    shutil.copy(out["source"], folder / "img0.png")
    out["batch_images"].insert(0, str(folder / "img0.png"))
    (folder / "img3.png").write_bytes(b"not an image")
    (folder / "notes.txt").write_text("keep me", encoding="utf-8")
    texts = await call("clip_interrogate", {"operation": "batch", "folder": str(folder), "mode": "fast", "keep_loaded": True,
                                            "clip_model": args.clip_model})
    assert texts["processed"] == 3 and texts["failed"] == ["img3.png"], texts
    for i in range(3):
        assert (folder / f"img{i}.txt").read_text(encoding="utf-8").strip(), i
    assert (folder / "img0.txt").read_text(encoding="utf-8") == results["fast"]["prompt"], "batch equals single-image fast"
    assert (folder / "notes.txt").read_text(encoding="utf-8") == "keep me"
    (folder / "img1.txt").write_text("hand written", encoding="utf-8")
    (folder / "img2.txt").unlink()
    partial = await call("clip_interrogate", {"operation": "batch", "folder": str(folder), "mode": "caption", "keep_loaded": True,
                                              "clip_model": args.clip_model})
    assert partial["processed"] == 1 and partial["skipped_existing"] == ["img0.png", "img1.png"], partial
    assert (folder / "img1.txt").read_text(encoding="utf-8") == "hand written", "existing output must be kept"
    assert (folder / "img0.txt").read_text(encoding="utf-8") == results["fast"]["prompt"]
    every = await call("clip_interrogate", {"operation": "batch", "folder": str(folder), "mode": "caption"})
    assert every["processed"] == 0 and every["skipped_existing"] == ["img0.png", "img1.png", "img2.png"], every
    receipt["batch_texts"] = {f"img{i}": (folder / f"img{i}.txt").read_text(encoding="utf-8") for i in range(3)}
    single = await call("clip_interrogate", {"operation": "batch", "folder": str(folder), "mode": "caption",
                                             "output_mode": "Single text file with all prompts", "keep_loaded": True,
                                             "clip_model": args.clip_model})
    assert single["processed"] == 3
    assert len((folder / "batch.txt").read_text(encoding="utf-8").splitlines()) == 3
    refused = await call("clip_interrogate", {"operation": "batch", "folder": str(folder), "mode": "caption",
                                              "output_mode": "Single text file with all prompts"}, expect_error=True)
    assert "already exists" in json.dumps(refused)
    await call("clip_interrogate", {"operation": "batch", "folder": str(folder), "mode": "caption", "overwrite": True,
                                    "output_mode": "csv file with columns for filenames and prompts", "keep_loaded": True,
                                    "clip_model": args.clip_model})
    with open(folder / "batch.csv", encoding="utf-8", newline="") as f:
        rows = list(csv.reader(f))
    assert rows[0] == ["filename", "prompt"] and [r[0] for r in rows[1:]] == ["img0.png", "img1.png", "img2.png"], rows
    receipt["batch_csv"] = rows

    # A second CLIP model (the SD2/XL recommendation) loads, ranks and produces its own cache files.
    if args.second_clip:
        second = await call("clip_interrogate", {"operation": "prompt", "image": out["source"], "mode": "fast",
                                                 "clip_model": args.second_clip, "caption_model": "blip-base"})
        receipt["second_clip"] = {"model": args.second_clip, "prompt": second["prompt"], "similarity": second["similarity"]}
        assert second["clip_model"] == args.second_clip and second["prompt"].strip()

    # Unload frees the models; a later call still works; bad inputs fail without breaking the engine.
    assert (await call("clip_interrogate", {"operation": "unload"}))["unloaded"] in (True, False)
    assert (await call("clip_interrogate", {"operation": "models"}))["loaded"] is None
    await call("clip_interrogate", {"operation": "prompt", "image": str(Path(args.output).resolve() / "nowhere.png")}, expect_error=True)
    await call("clip_interrogate", {"operation": "prompt", "image": out["source"], "clip_model": "RN50/openai"}, expect_error=True)
    out["after"] = first(await call("txt2img", params))
    assert Image.open(out["after"]).tobytes() == Image.open(out["source"]).tobytes(), "generation did not recover bit-exactly"
    receipt["success"] = True


async def run(args, receipt):
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    (args.output / "images").mkdir(parents=True, exist_ok=True)
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs={"checkpoints": [str(args.models.resolve())]}, last_model=args.checkpoint,
                    vram_mode=args.vram_mode, dtype="fp16", preview_every=0)
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    command = [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
               "sdfx.mcp.McpMain", "--home", str(app_home), "--drain-seconds", "5"]
    receipt.update(command=command, jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=3600) as client:
        await exercise(client, args, receipt)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--models", type=Path, required=True)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--seed", type=int, default=4242)
    p.add_argument("--clip-model", default="ViT-L-14/openai")
    p.add_argument("--second-clip", default="")
    p.add_argument("--prompt", default="a red fox sitting in a snowy pine forest, watercolor illustration, soft light")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "prompts": receipt.get("prompts"),
                      "similarities": receipt.get("similarities"), "outputs": receipt.get("outputs")}, indent=2))


if __name__ == "__main__":
    main()
