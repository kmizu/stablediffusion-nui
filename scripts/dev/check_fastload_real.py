"""Real-model ControlNet Fastload acceptance through the official MCP SDK.

1. GPU venv (needs numpy + sdfx): --make-fixtures writes a control image and
   upstream-format .cni files (dict units with a NumPy RGB image).
2. MCP client venv (mcp==2.3.0): runs generation checks and writes receipt.json.

The ControlNet directory must contain a diffusers model directory named --controlnet.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

MODES = ("Balanced", "My prompt is more important", "ControlNet is more important")
START = b"###START_OF_CONTROLNET_FASTLOAD###"


def make_fixtures(args):
    sys.path.insert(0, str(args.source.resolve() / "python"))
    import numpy as np
    from PIL import Image, ImageDraw
    from sdfx.controlnet_fastload import encode_fastload
    out = args.output / "images" / "fixtures"
    out.mkdir(parents=True, exist_ok=True)
    # Non-square source exercises pixel-perfect resolution and crop-and-resize.
    image = Image.new("RGB", (900, 600), "white")
    draw = ImageDraw.Draw(image)
    draw.ellipse((120, 90, 480, 450), outline="black", width=14)
    draw.rectangle((520, 160, 820, 520), outline="black", width=14)
    draw.line((60, 560, 860, 40), fill="black", width=10)
    image.save(out / "control.png")
    pixels = np.asarray(image, dtype=np.uint8)
    array = {"dtype": "|u1", "shape": tuple(pixels.shape), "data": pixels.tobytes(), "order": "C"}
    for index, mode in enumerate(MODES):
        unit = {"is_ui": False, "input_mode": "simple", "batch_images": None, "output_dir": "", "loopback": False,
                "enabled": True, "module": "canny", "model": f"{args.controlnet} [0123abcd]", "weight": 1.0,
                "image": {"image": array, "mask": None}, "resize_mode": "Crop and Resize", "low_vram": False,
                "processor_res": -1, "threshold_a": -1, "threshold_b": -1, "guidance_start": 0.0,
                "guidance_end": 1.0, "pixel_perfect": True, "control_mode": mode, "inpaint_crop_input_image": True,
                "hr_option": "Both", "save_detected_map": True, "advanced_weighting": None,
                "effective_region_mask": None, "pulid_mode": "Fidelity", "union_control_type": "Unknown",
                "ipadapter_input": None, "mask": None, "batch_mask_dir": None, "animatediff_batch": False,
                "batch_modifiers": [], "batch_image_files": [], "batch_keyframe_idx": None}
        (out / f"mode{index}.cni").write_bytes(encode_fastload(b"", [unit]))
    print(out)


def rgb(path):
    from PIL import Image
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


async def exercise(client, args, receipt):
    fixtures = (args.output / "images" / "fixtures").resolve()
    receipt["calls"] = []

    async def call(name, values, allow_error=False):
        result = await client.call_tool(name, values, read_timeout_seconds=1800)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "error": error, "result": data or encoded.get("content")})
        assert allow_error or not error, encoded
        return data

    def fastload(mode, path="", fmt="embedded", priority="current_first"):
        return {"controlnet_fastload": {"enabled": True, "mode": mode, "priority": priority, "path": str(path),
                                        "save_format": fmt}}

    def image(data):
        assert data.get("images"), data
        return data["images"][0]["path"]

    listed = await call("list_always_on", {"tab": "txt2img"})
    assert "controlnet_fastload" in json.dumps(listed), listed
    await call("set_model", {"name": args.checkpoint})
    controlnets = await call("list_controlnets", {})
    assert args.controlnet in [m["name"] for m in controlnets["controlnets"]], controlnets
    assert "canny" in controlnets["preprocessors"], controlnets
    params = dict(prompt=args.prompt, negative_prompt="blurry, lowres, text", width=args.side, height=args.side,
                  steps=args.steps, seed=4242, sampler_name="Euler", return_images=False, model_keyword=False)
    outputs = {}
    outputs["baseline"] = image(await call("txt2img", params))
    for index, mode in enumerate(MODES):
        outputs[f"load_{index}"] = image(await call("txt2img", dict(params,
                                                    always_on=fastload("load_only", fixtures / f"mode{index}.cni"))))
    pixels = {k: rgb(v) for k, v in outputs.items()}
    names = list(pixels)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            assert pixels[a] != pixels[b], f"{a} and {b} are identical"

    # Load & save, both formats; reloading either copy reproduces the generation exactly.
    saved = image(await call("txt2img", dict(params, always_on=fastload("load_save", fixtures / "mode0.cni", "both"))))
    assert rgb(saved) == pixels["load_0"], "load & save changed the generation"
    data = Path(saved).read_bytes()
    assert data.count(START) == 1 and Path(saved).with_suffix(".cni").is_file(), saved
    inspected = await call("controlnet_fastload", {"operation": "inspect", "path": saved})
    summary = inspected["summary"][0]
    assert summary["unapplied"] == [] and "error" not in summary, summary
    for source in (saved, str(Path(saved).with_suffix(".cni"))):
        again = image(await call("txt2img", dict(params, always_on=fastload("load_only", source))))
        assert rgb(again) == pixels["load_0"], f"reloading {source} did not reproduce the generation"
    outputs["saved"] = saved

    # Native typed ControlNet captured by Save only, then loaded with no current unit.
    native_unit = {"model": args.controlnet, "image": str(fixtures / "control.png"), "preprocessor": "canny",
                   "control_mode": "ControlNet is more important", "pixel_perfect": True}
    captured = image(await call("txt2img", dict(params, controlnet=[native_unit],
                                                always_on=fastload("save_only", fmt="embedded"))))
    restored = image(await call("txt2img", dict(params, always_on=fastload("load_only", captured))))
    assert rgb(restored) == rgb(captured), "save-only capture did not reproduce the native unit"
    assert rgb(captured) != pixels["baseline"]
    outputs.update(native_capture=captured, native_restore=restored)

    # Current unit has priority over the file unless loaded_first is selected.
    current_first = image(await call("txt2img", dict(params, controlnet=[native_unit],
                                                     always_on=fastload("load_only", fixtures / "mode0.cni"))))
    assert rgb(current_first) == rgb(captured), "current-first did not keep the current unit"
    loaded_first = image(await call("txt2img", dict(params, controlnet=[native_unit],
                         always_on=fastload("load_only", fixtures / "mode0.cni", priority="loaded_first"))))
    assert rgb(loaded_first) == pixels["load_0"], "loaded-first did not use the file unit"

    # Hires pass with a loaded canny unit (source hires soft injection).
    hires_args = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent",
                  "steps": max(4, args.steps // 2), "denoising_strength": 0.5}
    hires = image(await call("txt2img", dict(params, hires=hires_args,
                                             always_on=fastload("load_only", fixtures / "mode0.cni"))))
    outputs["hires"] = hires

    # Cancelling a load & save job leaves no Fastload output and the service recovers.
    before = {p.name for p in Path(saved).parent.iterdir()}
    queued = await call("txt2img_async", dict(params, steps=150,
                                              always_on=fastload("load_save", fixtures / "mode0.cni", "both")))
    await call("cancel_job", {"job_id": queued["job_id"]})
    for _ in range(90):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False},
                           allow_error=True)
        if state.get("status") in {"done", "failed", "cancelled"}:
            break
    assert state.get("status") == "cancelled", state
    after = {p.name for p in Path(saved).parent.iterdir()}
    assert after == before, sorted(after - before)
    recovered = image(await call("txt2img", dict(params, always_on=fastload("load_only", fixtures / "mode0.cni"))))
    assert rgb(recovered) == pixels["load_0"], "generation after cancel differs"
    receipt["outputs"] = outputs
    receipt["success"] = True


async def run(args, receipt):
    from mcp import Client, StdioServerParameters
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs={"checkpoints": [str(args.models.resolve())],
                                "controlnet": [str(args.controlnets.resolve())]},
                    last_model=args.checkpoint, vram_mode=args.vram_mode, dtype="fp16", preview_every=0)
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    command = [str(args.launcher.resolve()), "--mcp", "--home", str(app_home), "--drain-seconds", "5"] if args.launcher else \
        [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
         "sdfx.mcp.McpMain", "--home", str(app_home), "--drain-seconds", "5"]
    receipt.update(command=command, settings=settings, jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=1800) as client:
        await exercise(client, args, receipt)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--make-fixtures", action="store_true")
    p.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--controlnet", required=True, help="ControlNet catalog name")
    p.add_argument("--controlnets", type=Path)
    p.add_argument("--models", type=Path)
    p.add_argument("--checkpoint")
    p.add_argument("--jar", type=Path)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--launcher", type=Path)
    p.add_argument("--python", type=Path)
    p.add_argument("--side", type=int, default=512)
    p.add_argument("--steps", type=int, default=16)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="a stone castle courtyard with a round fountain, detailed illustration")
    args = p.parse_args()
    if args.make_fixtures:
        make_fixtures(args)
        return
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "outputs": receipt.get("outputs")}, indent=2))


if __name__ == "__main__":
    main()
