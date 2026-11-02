"""Real-model Batch Face Swap acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image, ImageChops

# Each must change the image (a face is found on the portrait prompt).
CASES = {
    "default": dict(),
    "prompt_override": dict(prompt="[PROMPT], closed eyes, big smile", denoising_strength=0.6),
    "strong": dict(denoising_strength=0.85),
    "box_big_mask": dict(mask_shape="box", mask_size=1.6, mask_blur=16),
    "magnified": dict(resolution=768),
    "step_cfg_sampler": dict(steps=14, cfg_scale=3.0, sampler="Euler a"),
    "no_colour_match": dict(color_correction=False),
    "largest_only": dict(max_faces=1),
    "yolo_s": dict(detector="face_yolov8s.pt"),
}
# Each must reproduce the baseline exactly (nothing is redrawn).
IDENTITY = {
    "confidence_1": dict(confidence=1.0),
    "disabled": dict(enabled=False),
}
REPLAY = dict(denoising_strength=0.55, mask_shape="box", mask_size=1.4, mask_blur=10, padding=24,
              prompt="[PROMPT], smile", negative_prompt="frown", steps=16, cfg_scale=4.0, sampler="Euler a")


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def change_stats(base, other):
    """(fraction of changed pixels, bounding box of the change)."""
    diff = ImageChops.difference(Image.open(base).convert("RGB"), Image.open(other).convert("RGB")).convert("L")
    mask = diff.point(lambda v: 255 if v > 6 else 0)
    box = mask.getbbox()
    changed = sum(1 for v in mask.getdata() if v) / (mask.width * mask.height)
    return changed, box


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

    def bfs(**values):
        return {"batch_face_swap": dict({"enabled": True}, **values)}

    args.output.mkdir(parents=True, exist_ok=True)
    listed = json.dumps(await call("list_always_on", {"tab": "txt2img"}))
    assert "batch_face_swap" in listed
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=args.seed, sampler_name="Euler", return_images=False,
                  model_keyword=False)
    out = {"baseline": first(await call("txt2img", params))}
    stats = receipt.setdefault("change", {})
    for name, values in CASES.items():
        out[name] = first(await call("txt2img", dict(params, always_on=bfs(**values))))
        assert rgb(out[name]) != rgb(out["baseline"]), f"{name} did not change the image"
        stats[name] = change_stats(out["baseline"], out[name])
        assert stats[name][0] < 0.5, f"{name} changed {stats[name][0]:.0%} of the image: not a local face pass"
    for name, values in IDENTITY.items():
        out[name] = first(await call("txt2img", dict(params, always_on=bfs(**values))))
        assert rgb(out[name]) == rgb(out["baseline"]), f"{name} changed the image"
    # the redraw only touches faces: the largest-only pass changes no more than the all-faces pass
    assert stats["largest_only"][0] <= stats["default"][0] + 1e-9, stats
    # PNG round trip
    out["combo"] = first(await call("txt2img", dict(params, always_on=bfs(**REPLAY))))
    meta = await call("inspect_generation_metadata", {"image": out["combo"]})
    assert meta["always_on"].get("batch_face_swap", {}).get("enabled") is True, meta["always_on"]
    assert "Batch Face Swap" not in meta["unapplied_fields"], meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = first(await call("txt2img", replay))
    assert rgb(out["replay"]) == rgb(out["combo"]), "PNG replay did not reproduce the image"
    # img2img of the finished image with the hook
    edited = first(await call("img2img", dict(params, init_image=out["baseline"], denoising_strength=0.05,
                                              always_on=bfs(denoising_strength=0.6, prompt="[PROMPT], smile"))))
    plain = first(await call("img2img", dict(params, init_image=out["baseline"], denoising_strength=0.05)))
    out["img2img_hook"], out["img2img_plain"] = edited, plain
    assert rgb(edited) != rgb(plain), "img2img + hook equals img2img alone"
    # bad settings are rejected before an image is drawn
    bad = await call("txt2img", dict(params, always_on=bfs(confidence=3)), allow_error=True)
    assert "error" in bad, bad
    # cancel recovery
    queued = await call("txt2img_async", dict(params, steps=80, always_on=bfs(**REPLAY)))
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
    assert rgb(out["after_cancel"]) == rgb(out["baseline"]), "hooks were not removed after cancel"
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
    p.add_argument("--seed", type=int, default=2468)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="2girls, standing side by side, upper body, park, looking at viewer, anime illustration")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "change": receipt.get("change")}, indent=2))


if __name__ == "__main__":
    main()
