"""Real-model Incantations acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image

# Each must change the image.
CASES = {
    "seg_inf": dict(seg_active=True),
    "seg_sigma3": dict(seg_active=True, seg_blur_sigma=3),
    "scfg": dict(scfg_active=True),
    "pag": dict(pag_active=True, pag_scale=3),
    "pag_sanf": dict(pag_active=True, pag_scale=3, pag_sanf=True),
    "scheduler": dict(cfg_interval_enable=True, cfg_interval_schedule="PCS (s=1.0)", cfg_interval_low=0.28,
                      cfg_interval_high=5.42),
    "ctnms": dict(t2i0_active=True),
    "cbs": dict(t2i0_active=True, t2i0_correction_strength=0.5, t2i0_ctnms_alpha=0),
}
# Each must reproduce the baseline exactly (nothing runs, or the source's no-op settings).
IDENTITY = {
    "pag_scale0": dict(pag_active=True),
    "constant_schedule": dict(cfg_interval_enable=True),
    "seg_sigma0": dict(seg_active=True, seg_blur_sigma=0),
}
REPLAY = dict(seg_active=True, seg_blur_sigma=3, pag_active=True, pag_scale=2, t2i0_active=True, t2i0_ema_factor=1.0)
HIRES = dict(seg_active=True, pag_active=True, pag_scale=3, t2i0_active=True)
# Kept source failure: CTNMS counts on into the hires pass, where its EMA meets another size.
HIRES_EMA = dict(t2i0_active=True, t2i0_ema_factor=1.0)


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


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

    def incant(**values):
        return {"incantations": dict({"enabled": True}, **values)}

    assert "incantations" in json.dumps(await call("list_always_on", {"tab": "txt2img"}))
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=2468, sampler_name="Euler", return_images=False,
                  model_keyword=False)
    out = {"baseline": first(await call("txt2img", params))}
    for name, values in CASES.items():
        out[name] = first(await call("txt2img", dict(params, always_on=incant(**values))))
        assert rgb(out[name]) != rgb(out["baseline"]), f"{name} did not change the image"
    for name, values in IDENTITY.items():
        out[name] = first(await call("txt2img", dict(params, always_on=incant(**values))))
        assert rgb(out[name]) == rgb(out["baseline"]), f"{name} changed the image"
    out["combo"] = first(await call("txt2img", dict(params, always_on=incant(**REPLAY))))
    meta = await call("inspect_generation_metadata", {"image": out["combo"]})
    assert meta["always_on"].get("incantations", {}).get("enabled") is True, meta["always_on"]
    assert not any(k.startswith(("SEG ", "PAG ", "T2I-0 ")) for k in meta["unapplied_fields"]), meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = first(await call("txt2img", replay))
    assert rgb(out["replay"]) == rgb(out["combo"]), "PNG replay did not reproduce the image"
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    out["hires_baseline"] = first(await call("txt2img", dict(params, hires=hires)))
    out["hires"] = first(await call("txt2img", dict(params, hires=hires, always_on=incant(**HIRES))))
    assert rgb(out["hires"]) != rgb(out["hires_baseline"]), "hires did not change"
    failed = await call("txt2img", dict(params, hires=hires, always_on=incant(**HIRES_EMA)), allow_error=True)
    receipt["hires_ema"] = failed
    assert "EMA" in json.dumps(failed), "expected the source's EMA failure in the hires pass"
    queued = await call("txt2img_async", dict(params, steps=80, always_on=incant(**REPLAY)))
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
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="a lion and a tiger and a grizzly bear in the woods, detailed illustration")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "outputs": receipt.get("outputs")}, indent=2))


if __name__ == "__main__":
    main()
