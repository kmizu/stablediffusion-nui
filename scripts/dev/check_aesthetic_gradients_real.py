"""Real-model Aesthetic Gradients acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

1. Generates reference pictures of two looks (monochrome ink sketch, saturated neon) with the loaded checkpoint and
   builds the aesthetic embeddings from them with `python -m sdfx.aesthetic_gradients create` (engine python).
2. Generates the same seed without and with the hook (several settings), measures with CLIP (`... score`) how much
   closer each image is to the embedding, and checks identity cases, PNG replay, hires, cancel recovery.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import subprocess
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image, ImageStat

LOOKS = {
    "mono": "monochrome, greyscale, ink sketch, black and white, high contrast, detailed illustration",
    "neon": "vivid saturated neon colors, glowing, cyberpunk, detailed illustration",
}
# Each must change the image.
CASES = {
    "default_mono": dict(embedding="mono"),
    "gentle_mono": dict(embedding="mono", weight=0.3, steps=10),
    "half_mono": dict(embedding="mono", weight=0.5),
    "neon": dict(embedding="neon"),
    "neon_half": dict(embedding="neon", weight=0.5),
    "slerp_mono": dict(embedding="mono", slerp=True, text="a castle, masterpiece", slerp_angle=0.4),
    "high_lr": dict(embedding="mono", lr=0.001, steps=30),  # far past the useful range: only has to run and differ
}
# Each must reproduce the baseline exactly (nothing runs).
IDENTITY = {"weight0": dict(embedding="mono", weight=0.0, steps=30)}
REPLAY = dict(embedding="mono", weight=0.5, steps=12, lr=0.0001, slerp=True, text="sketch", slerp_angle=0.2)


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def saturation(path):
    with Image.open(path) as image:
        return ImageStat.Stat(image.convert("HSV").split()[1]).mean[0] / 255.0


def clip_scores(args, embedding_name, images):
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"), SDFX_AESTHETIC_EMBEDDINGS=str(args.embeddings))
    out = subprocess.run([str(args.python), "-m", "sdfx.aesthetic_gradients", "score", embedding_name, *map(str, images)],
                         env=env, capture_output=True, text=True, check=True).stdout
    return [float(line.split()[0]) for line in out.strip().splitlines()]


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

    def first(data):
        assert data.get("images"), data
        return data["images"][0]["path"]

    def paths(data):
        return [i["path"] for i in data["images"]]

    def ag(**values):
        return {"aesthetic_gradients": dict({"enabled": True}, **values)}

    args.output.mkdir(parents=True, exist_ok=True)
    assert "aesthetic_gradients" in json.dumps(await call("list_always_on", {"tab": "txt2img"}))
    await call("set_model", {"name": args.checkpoint})
    base = dict(negative_prompt="lowres, blurry, text", width=args.width, height=args.height, steps=args.steps,
                cfg_scale=args.cfg, sampler_name="Euler", return_images=False, model_keyword=False)

    # 1. reference pictures -> embeddings (the engine python builds them with CLIP ViT-L/14)
    args.embeddings.mkdir(parents=True, exist_ok=True)
    refs = {}
    for look, text in LOOKS.items():
        refs[look] = []
        for seed in (100, 101, 102, 103):
            refs[look].append(first(await call("txt2img", dict(base, prompt=text, seed=seed))))
        env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"), SDFX_AESTHETIC_EMBEDDINGS=str(args.embeddings))
        done = subprocess.run([str(args.python), "-m", "sdfx.aesthetic_gradients", "create", look, *refs[look], "--overwrite"],
                              env=env, capture_output=True, text=True)
        assert done.returncode == 0, done.stderr[-2000:]
        receipt.setdefault("embeddings", {})[look] = done.stdout.strip()
    receipt["reference_saturation"] = {k: [round(saturation(p), 3) for p in v] for k, v in refs.items()}

    # 2. generations
    params = dict(base, prompt=args.prompt, seed=2468)
    out = {"baseline": first(await call("txt2img", params))}
    for name, values in CASES.items():
        out[name] = first(await call("txt2img", dict(params, always_on=ag(**values))))
        assert rgb(out[name]) != rgb(out["baseline"]), f"{name} did not change the image"
    for name, values in IDENTITY.items():
        out[name] = first(await call("txt2img", dict(params, always_on=ag(**values))))
        assert rgb(out[name]) == rgb(out["baseline"]), f"{name} changed the image"
    receipt["saturation"] = {k: round(saturation(v), 4) for k, v in out.items()}
    for look, names in (("mono", ["baseline", "gentle_mono", "half_mono", "default_mono", "slerp_mono", "neon"]),
                        ("neon", ["baseline", "neon", "neon_half", "default_mono"])):
        scores = clip_scores(args, look, [out[n] for n in names])
        receipt.setdefault("clip_similarity", {})[look] = dict(zip(names, scores))
    sim = receipt["clip_similarity"]
    assert sim["mono"]["default_mono"] > sim["mono"]["baseline"] + 0.1, sim
    assert max(sim["neon"]["neon"], sim["neon"]["neon_half"]) > sim["neon"]["baseline"] + 0.02, sim
    assert sim["mono"]["default_mono"] > sim["mono"]["gentle_mono"] > sim["mono"]["baseline"], sim  # weight/steps dose

    # negative prompt mode changes the image too
    out["negative_mode"] = first(await call("txt2img", dict(params, always_on=ag(embedding="mono", negative=True))))
    assert rgb(out["negative_mode"]) != rgb(out["baseline"]), "negative mode did not change the image"

    # 3. PNG round trip
    out["combo"] = first(await call("txt2img", dict(params, always_on=ag(**REPLAY))))
    meta = await call("inspect_generation_metadata", {"image": out["combo"]})
    got = meta["always_on"].get("aesthetic_gradients", {})
    assert got.get("enabled") is True and got.get("embedding") == "mono", meta["always_on"]
    assert not any(k.startswith("Aesthetic ") for k in meta["unapplied_fields"]), meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = first(await call("txt2img", replay))
    assert rgb(out["replay"]) == rgb(out["combo"]), "PNG replay did not reproduce the image"

    # 4. hires fix (the second pass encodes the prompts again)
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    out["hires_baseline"] = first(await call("txt2img", dict(params, hires=hires)))
    out["hires"] = first(await call("txt2img", dict(params, hires=hires, always_on=ag(**CASES["half_mono"]))))
    assert rgb(out["hires"]) != rgb(out["hires_baseline"]), "hires did not change"

    # 5. errors are reported, not crashes
    for bad in (dict(embedding="no-such-embedding"), dict(embedding="mono", weight=2.0), dict(embedding="")):
        failed = await call("txt2img", dict(params, always_on=ag(**bad)), allow_error=True)
        receipt.setdefault("rejections", []).append({"settings": bad, "rejected": "error" in failed})
        assert "error" in failed, bad

    # 6. cancel mid-run, then the engine produces the baseline again
    queued = await call("txt2img_async", dict(params, steps=80, always_on=ag(embedding="mono", steps=150)))
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
    out["after_cancel"] = first(await call("txt2img", params))
    assert rgb(out["after_cancel"]) == rgb(out["baseline"]), "the encoder was altered after cancel"
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
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"), SDFX_AESTHETIC_EMBEDDINGS=str(args.embeddings))
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
    p.add_argument("--cfg", type=float, default=6.0)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="a castle on a hill above a village and a river, trees, detailed illustration")
    args = p.parse_args()
    args.embeddings = (args.output / "embeddings").resolve()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({k: receipt.get(k) for k in ("success", "saturation", "clip_similarity", "cancel_state")}, indent=2))


if __name__ == "__main__":
    main()
