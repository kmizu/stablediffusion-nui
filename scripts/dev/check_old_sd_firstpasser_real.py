"""Real-model Old SD firstpasser acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image


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

    def paths(data, count=1):
        assert len(data.get("images", [])) == count, data
        return [image["path"] for image in data["images"]]

    scripts = json.dumps(await call("list_scripts", {}))
    assert "old_sd_firstpasser" in scripts, scripts
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=2468, sampler_name="Euler", return_images=False,
                  model_keyword=False)
    sparams = {k: v for k, v in params.items() if k != "return_images"}  # return_images is a run_script argument
    values = {"steps": args.first_steps, "denoising": 0.55, "upscaler": args.upscaler, "network_type": args.network,
              "sd1_checkpoint": args.firstpass if args.network == "SD1" else "",
              "sdxl_checkpoint": args.firstpass if args.network == "SDXL" else ""}
    if args.network == "Auto":  # Auto: SD 1.x pass for an SDXL main checkpoint
        values["sd1_checkpoint"] = args.firstpass

    async def script(extra_values=None, extra_params=None):
        return await call("run_script", {"script_id": "old_sd_firstpasser", "values": dict(values, **(extra_values or {})),
                                         "params": dict(sparams, **(extra_params or {})), "return_images": False})

    out = {"baseline": paths(await call("txt2img", params))[0]}
    out["firstpass"] = paths(await script())[0]
    size, _ = rgb(out["firstpass"])
    assert size == (args.width, args.height), size
    assert rgb(out["firstpass"]) != rgb(out["baseline"]), "the firstpass changed nothing"
    out["repeat"] = paths(await script())[0]
    assert rgb(out["repeat"]) == rgb(out["firstpass"]), "the same request is not reproducible"
    out["denoise_low"] = paths(await script({"denoising": 0.25}))[0]
    out["denoise_high"] = paths(await script({"denoising": 0.85}))[0]
    assert len({rgb(out[k])[1] for k in ("firstpass", "denoise_low", "denoise_high")}) == 3, "denoising did not matter"
    out["no_upscaler"] = paths(await script({"upscaler": "None"}))[0]
    if args.firstpass_larger:  # the engine downsizes a firstpass that is not smaller than the target: no upscaler runs
        assert rgb(out["no_upscaler"]) == rgb(out["firstpass"]), "an upscaler ran for a firstpass larger than the target"
    else:
        assert rgb(out["no_upscaler"]) != rgb(out["firstpass"]), "the upscaler did not matter"
    out["resolution"] = paths(await script({"resolution": args.alt_resolution}))[0]
    assert rgb(out["resolution"]) != rgb(out["firstpass"]), "the firstpass resolution did not matter"

    meta = await call("inspect_generation_metadata", {"image": out["firstpass"]})
    candidate = meta.get("scripts", {}).get("old_sd_firstpasser")
    assert candidate, meta
    assert candidate["steps"] == args.first_steps and candidate["denoising"] == 0.55, candidate
    assert not any(k.startswith("Old SD firstpasser") for k in meta["unapplied_fields"]), meta["unapplied_fields"]
    replay_args = dict(meta["generation_args"], model_keyword=False)
    replayed = await call("run_script", {"script_id": "old_sd_firstpasser", "values": candidate, "params": replay_args,
                                         "return_images": False})
    out["replay"] = paths(replayed)[0]
    assert rgb(out["replay"]) == rgb(out["firstpass"]), "PNG replay did not reproduce the image"

    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Lanczos", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.45}
    out["hires"] = paths(await script(extra_params={"hires": hires}))[0]
    hsize, _ = rgb(out["hires"])
    assert hsize == (round(args.width * args.hires_scale / 8) * 8, round(args.height * args.hires_scale / 8) * 8), hsize
    hmeta = await call("inspect_generation_metadata", {"image": out["hires"]})
    hcand = hmeta["scripts"]["old_sd_firstpasser"]
    assert hmeta["generation_args"]["width"] == args.width and hmeta["generation_args"]["steps"] == args.steps, hmeta["generation_args"]
    assert hmeta["generation_args"].get("hires", {}).get("enabled"), hmeta["generation_args"]
    hreplay = await call("run_script", {"script_id": "old_sd_firstpasser", "values": hcand,
                                        "params": dict(hmeta["generation_args"], model_keyword=False),
                                        "return_images": False})
    out["hires_replay"] = paths(hreplay)[0]
    assert rgb(out["hires_replay"]) == rgb(out["hires"]), "hires PNG replay did not reproduce the image"

    batch = paths(await script(extra_params={"n_iter": 2}), 2)
    out["batch0"], out["batch1"] = batch
    assert rgb(batch[0])[1] != rgb(batch[1])[1], "batch images are identical"
    assert rgb(batch[0]) == rgb(out["firstpass"]), "image 0 of a batch differs from the single image"

    queued = await call("run_script", {"script_id": "old_sd_firstpasser", "async": True,
                                       "values": dict(values, steps=150), "params": dict(sparams, steps=args.steps)})
    state = {}
    for _ in range(600):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 1, "return_images": False}, allow_error=True)
        if 0.05 < float(state.get("progress", 0) or 0) < 1 or state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_progress"] = state.get("progress")
    await call("cancel_job", {"job_id": queued["job_id"]})
    for _ in range(120):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False}, allow_error=True)
        state = state.get("error", {}).get("structured_content", state) if "error" in state else state
        if state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_state"] = state.get("status")
    assert state.get("status") == "cancelled", state
    out["after_cancel"] = paths(await call("txt2img", params))[0]
    assert rgb(out["after_cancel"]) == rgb(out["baseline"]), "the main checkpoint was not reloaded after the cancel"
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
    p.add_argument("--checkpoint", required=True, help="main checkpoint name")
    p.add_argument("--firstpass", required=True, help="firstpass checkpoint name")
    p.add_argument("--network", choices=("Auto", "SD1", "SDXL"), default="Auto")
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--first-steps", type=int, default=16)
    p.add_argument("--cfg", type=float, default=6.0)
    p.add_argument("--upscaler", default="Lanczos")
    p.add_argument("--firstpass-larger", action="store_true", help="the firstpass is not smaller than the target (no upscaler)")
    p.add_argument("--alt-resolution", type=int, default=384)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="1girl, solo, smile, long hair, flower field, blue sky, masterpiece, best quality")
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
