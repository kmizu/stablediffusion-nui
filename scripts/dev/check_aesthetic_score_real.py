"""Real-model Aesthetic Score acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

Generates with the real engine and the real CLIP / predictor / cafeai models, then checks that scoring never changes
the pixels, that the infotext and PNG chunks carry the scores, that an independent reader of the saved PNG agrees
(see check_aesthetic_score_tool_real.py, run with the engine Python), and that PNG replay, hires, batches and
cancellation behave.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import re
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image

ALL = dict(improved=True, simulacra="ViT-L/14", cafe_aesthetic=True, cafe_style=True, cafe_waifu=True,
           generation_fields=True, hash=True)
SIMULACRA_B16 = dict(improved=False, simulacra="ViT-B/16")
CAFE_ONLY = dict(improved=False, cafe_style=True, png_chunks=False)
CPU_ONLY = dict(improved=True, force_cpu=True)


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def texts(path):
    with Image.open(path) as image:
        return dict(image.text)


def fields(path):
    """The `Key: value` fields of the parameters text (values unquoted)."""
    text = texts(path)["parameters"]
    line = text.splitlines()[-1]
    out = {}
    for match in re.finditer(r'\s*(\w[\w \-/]+):\s*("(?:\\.|[^\\"])+"|[^,]*)(?:,|$)', line):
        value = match.group(2).strip()
        out[match.group(1).strip()] = json.loads(value) if value.startswith('"') else value
    return out


async def exercise(client, args, receipt):
    receipt["calls"] = []

    async def call(name, values, allow_error=False):
        result = await client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "error": error, "result": data or encoded.get("content")})
        assert allow_error or not error, encoded
        return data if not error else {"error": encoded}

    def paths(data):
        assert data.get("images"), data
        return [i["path"] for i in data["images"]]

    assert "aesthetic_score" in json.dumps(await call("list_always_on", {"tab": "txt2img"}))
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=2468, sampler_name="Euler", return_images=False,
                  model_keyword=False, batch_size=2)

    def hook(**values):
        return {"aesthetic_score": dict({"enabled": True}, **values)}

    out = {}
    out["baseline"] = paths(await call("txt2img", params))
    for path in out["baseline"]:
        assert "aesthetic_score" not in fields(path) and "aesthetic_score" not in texts(path)

    # 1. default settings: improved predictor only; the pixels do not change
    out["default"] = paths(await call("txt2img", dict(params, always_on=hook())))
    scores = []
    for base, scored in zip(out["baseline"], out["default"]):
        assert rgb(base) == rgb(scored), "scoring changed the image"
        f, t = fields(scored), texts(scored)
        assert 0.0 <= float(f["aesthetic_score"]) <= 10.0, f
        assert t["aesthetic_score"] == f["aesthetic_score"], (t.get("aesthetic_score"), f)
        assert "Aesthetic score settings" in f and "score" not in t and "Cafe waifu" not in f
        scores.append(float(f["aesthetic_score"]))
    receipt["default_scores"] = scores

    # 2. every scorer, generation fields and hash
    out["all"] = paths(await call("txt2img", dict(params, always_on=hook(**ALL))))
    receipt["all_fields"] = []
    for path in out["all"]:
        f, t = fields(path), texts(path)
        receipt["all_fields"].append({k: f[k] for k in f if k.startswith(("aesthetic_score", "Aesthetic Scorer", "Cafe", "hash"))})
        assert re.fullmatch(r"\d+\.\d", f["aesthetic_score"]) and re.fullmatch(r"\d+\.\d\d", f["Aesthetic Scorer ViT-L/14"])
        for key, labels in (("Cafe aesthetic", ("aesthetic", "not_aesthetic")), ("Cafe style", ("anime", "real_life", "3d", "manga_like", "other")),
                            ("Cafe waifu", ("waifu", "not_waifu"))):
            label, probability = f[key].split()
            assert label in labels and 0.0 <= float(probability) <= 1.0, (key, f[key])
        assert len(f["hash"]) == 32 and t["hash"] == f["hash"]
        assert t["score"] == f["Aesthetic Scorer ViT-L/14"] and t["aesthetic_score"] == f["aesthetic_score"]
        for key in ("cafe_aesthetic", "cafe_style", "cafe_waifu"):
            dist = json.loads(t[key])
            assert abs(sum(dist.values()) - 1.0) < 2e-4, dist
        assert t["sampler"] == "Euler" and float(t["cfg_scale"]) == float(f["CFG scale"]) and t["sd_model_hash"] and t["seed"] == "2468"
    assert rgb(out["all"][0]) == rgb(out["baseline"][0]) and rgb(out["all"][1]) == rgb(out["baseline"][1])

    # 3. the other CLIP, cafe only without chunks, CPU
    out["b16"] = paths(await call("txt2img", dict(params, always_on=hook(**SIMULACRA_B16))))
    for path in out["b16"]:
        f, t = fields(path), texts(path)
        assert "aesthetic_score" not in f and "Aesthetic Scorer ViT-B/16" in f and t["score"] == f["Aesthetic Scorer ViT-B/16"]
    out["cafe"] = paths(await call("txt2img", dict(params, always_on=hook(**CAFE_ONLY))))
    for path in out["cafe"]:
        f, t = fields(path), texts(path)
        assert "Cafe style" in f and "cafe_style" not in t and "aesthetic_score" not in f
    out["cpu"] = paths(await call("txt2img", dict(params, always_on=hook(**CPU_ONLY))))
    receipt["cpu_vs_gpu"] = [(fields(a)["aesthetic_score"], fields(b)["aesthetic_score"]) for a, b in zip(out["cpu"], out["default"])]
    for a, b in zip(out["cpu"], out["default"]):
        assert abs(float(fields(a)["aesthetic_score"]) - float(fields(b)["aesthetic_score"])) <= 0.11, receipt["cpu_vs_gpu"]

    # 4. invalid settings fail before sampling and leave the engine usable
    bad = await call("txt2img", dict(params, always_on=hook(improved=False)), allow_error=True)
    assert "error" in bad and "at least one scorer" in json.dumps(bad), bad

    # 5. PNG replay: settings and scores come back, the regenerated image has the same pixels and scores
    meta = await call("inspect_generation_metadata", {"image": out["all"][0]})
    candidate = meta["always_on"].get("aesthetic_score", {})
    assert candidate.get("enabled") is True and candidate.get("simulacra") == "ViT-L/14" and candidate.get("cafe_style") is True, candidate
    assert not any(k in meta["unapplied_fields"] for k in ("aesthetic_score", "Cafe waifu", "Aesthetic score settings")), meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = paths(await call("txt2img", replay))
    assert rgb(out["replay"][0]) == rgb(out["all"][0])
    for key in ("aesthetic_score", "Aesthetic Scorer ViT-L/14", "Cafe aesthetic", "Cafe style", "Cafe waifu", "hash"):
        assert fields(out["replay"][0])[key] == fields(out["all"][0])[key], key

    # 6. hires fix: the saved (final) image is the one that is scored
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2), "denoising_strength": 0.5}
    out["hires"] = paths(await call("txt2img", dict(params, hires=hires, batch_size=1, always_on=hook(**ALL))))
    assert rgb(out["hires"][0])[0] == (int(args.width * args.hires_scale), int(args.height * args.hires_scale)), rgb(out["hires"][0])[0]
    assert "aesthetic_score" in fields(out["hires"][0])

    # 7. cancellation leaves no scoring state behind
    queued = await call("txt2img_async", dict(params, steps=80, always_on=hook(**ALL)))
    for _ in range(600):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 1, "return_images": False}, allow_error=True)
        if 0.2 < float(state.get("progress", 0) or 0) < 1 or state.get("status") in {"done", "failed", "cancelled"}:
            break
    await call("cancel_job", {"job_id": queued["job_id"]})
    for _ in range(120):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False}, allow_error=True)
        state = state.get("error", {}).get("structured_content", state) if "error" in state else state
        if state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_state"] = state.get("status")
    assert state.get("status") == "cancelled", state
    out["after_cancel"] = paths(await call("txt2img", params))
    assert all(rgb(a) == rgb(b) for a, b in zip(out["after_cancel"], out["baseline"]))
    assert all("aesthetic_score" not in fields(p) for p in out["after_cancel"])
    receipt["outputs"] = out
    receipt["success"] = True


async def run(args, receipt):
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
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
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=24)
    p.add_argument("--cfg", type=float, default=5.0)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="1girl, solo, long hair, looking at viewer, upper body, masterpiece, best quality")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "default_scores": receipt.get("default_scores"),
                      "all_fields": receipt.get("all_fields"), "cpu_vs_gpu": receipt.get("cpu_vs_gpu")}, indent=2))


if __name__ == "__main__":
    main()
