"""Real-model Corridor Crawler acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image, ImageChops, ImageDraw, ImageStat


def rgb(path):
    with Image.open(path) as image:
        return image.convert("RGB")


def pixels(path):
    return rgb(path).tobytes()


def mean_diff(a, b):
    """Mean absolute difference (0..255) of two equally sized RGB images."""
    return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 3


def window_box(width, height):
    """Default window (49.6 / 49.6 / 24.8 / 15.8 percent), as the plugin rounds it."""
    return (round(width * .248), round(height * .158), round(width * .248) + round(width * .496),
            round(height * .158) + round(height * .496))


async def exercise(client, args, receipt):
    receipt["calls"] = []

    async def call(name, values, allow_error=False):
        result = await client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "error": error, "result": data or encoded.get("content")})
        assert allow_error or not error, encoded
        return data

    def images(data):
        assert data.get("images"), data
        return [image["path"] for image in data["images"]]

    await call("set_model", {"name": args.checkpoint})
    assert "corridor_crawler" in json.dumps(await call("list_scripts", {}))
    W, H = args.width, args.height
    base = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text, watermark, people", steps=args.steps, cfg_scale=7,
                sampler_name="DDIM", seed=4242, return_images=False, model_keyword=False)
    start = images(await call("txt2img", dict(base, width=W, height=H, prompt=args.start_prompt)))[0]
    out = {"start": start}

    def script(source=start, params_extra=None, **values):
        params = dict(base, init_image=source, denoising_strength=args.denoise, width=W, height=H)
        params.pop("return_images")
        params.update(params_extra or {})
        return {"script_id": "corridor_crawler", "values": values, "params": params, "return_images": False}

    def split(paths, grids):
        """MCP lists the steps first and the grid last (grids live under outputs/grids)."""
        steps = [p for p in paths if "grids" not in Path(p).parts]
        return steps, [p for p in paths if "grids" in Path(p).parts]

    box = window_box(W, H)
    # 1. defaults: three steps and a grid; the window of every step holds the previous picture
    data = await call("run_script", script(steps=3))
    paths = images(data)
    steps, grids = split(paths, None)
    assert len(steps) == 3 and len(grids) == 1, paths
    assert all(rgb(p).size == (W, H) for p in steps)
    out["defaults"] = steps
    out["grid"] = grids[0]
    chain = [rgb(start)] + [rgb(p) for p in steps]
    diffs = []
    for earlier, later in zip(chain, chain[1:]):
        crop = later.crop(box)
        shrunk = earlier.resize(crop.size, Image.LANCZOS)
        wrong = later.crop((W - box[2], H - box[3], W - box[0], H - box[1]))  # a different region, same size
        diffs.append((mean_diff(crop, shrunk), mean_diff(wrong, shrunk)))
    receipt["window_vs_previous_vs_other_region"] = diffs
    assert all(good < 25 and good < bad for good, bad in diffs), diffs
    assert mean_diff(chain[1], chain[2]) > 8 and mean_diff(chain[2], chain[3]) > 8  # steps are different pictures
    # the outer ring is new: it is not the start image stretched
    assert mean_diff(chain[1], chain[0]) > 8

    # 2. determinism (same seed, same inputs)
    again = split(images(await call("run_script", script(steps=3))), None)[0]
    assert [pixels(p) for p in again] == [pixels(p) for p in steps], "second run differs"
    out["repeat_identical"] = True

    # 3. kept-area shrink changes the result and still keeps the centre
    shrunk_run = split(images(await call("run_script", script(steps=1, mask_shrink=16))), None)[0][0]
    out["shrink"] = shrunk_run
    assert pixels(shrunk_run) != pixels(steps[0])
    centre = (W // 2 - 40, int(H * .35) - 40, W // 2 + 40, int(H * .35) + 40)
    assert mean_diff(rgb(shrunk_run).crop(centre), rgb(steps[0]).crop(centre)) < 40

    # 4. custom prefab (stretched to the canvas) and a different window
    prefab = Image.new("RGB", (400, 400), (70, 70, 90))
    draw = ImageDraw.Draw(prefab)
    draw.polygon([(0, 0), (400, 0), (300, 100), (100, 100)], fill=(40, 40, 60))
    draw.polygon([(0, 400), (400, 400), (300, 300), (100, 300)], fill=(120, 100, 70))
    (args.output / "images").mkdir(parents=True, exist_ok=True)
    prefab_path = args.output / "images" / "custom-prefab.png"
    prefab.save(prefab_path)
    custom = split(images(await call("run_script", script(steps=2, prefab="Custom image", prefab_image=str(prefab_path),
                                                           window_width=40, window_height=40, window_x=30, window_y=30))), None)[0]
    out["custom"] = custom
    assert len(custom) == 2
    cbox = (round(W * .30), round(H * .30), round(W * .30) + round(W * .40), round(H * .30) + round(H * .40))
    good = mean_diff(rgb(custom[1]).crop(cbox), rgb(custom[0]).resize((cbox[2] - cbox[0], cbox[3] - cbox[1]), Image.LANCZOS))
    receipt["custom_window_diff"] = good
    assert good < 25, good

    # 5. zoom-in GIF next to the last step
    gif_run = split(images(await call("run_script", script(steps=2, animation=True, frames_per_step=6, frame_ms=60,
                                                           animation_size=200))), None)[0]
    gif = Path(gif_run[-1]).with_name(Path(gif_run[-1]).stem + "-corridor.gif")
    assert gif.is_file(), gif
    with Image.open(gif) as animation:
        receipt["gif"] = dict(frames=animation.n_frames, size=animation.size, duration=animation.info.get("duration"),
                              loop=animation.info.get("loop"))
        assert animation.n_frames == 2 * 6 + 1 and max(animation.size) == 200 and animation.info.get("duration") == 60
        animation.seek(0)
        first = animation.convert("RGB")
        animation.seek(animation.n_frames - 1)
        last = animation.convert("RGB")
    assert mean_diff(first, last) > 8
    out["gif"] = str(gif)
    first.save(args.output / "gif-first.png")
    last.save(args.output / "gif-last.png")

    # 6. slideshow mode
    slide_run = split(images(await call("run_script", script(steps=2, animation=True, frames_per_step=0, frame_ms=100,
                                                             animation_size=160))), None)[0]
    with Image.open(Path(slide_run[-1]).with_name(Path(slide_run[-1]).stem + "-corridor.gif")) as animation:
        assert animation.n_frames == 3, animation.n_frames

    # 7. rejected combinations are real errors from the host
    await call("run_script", script(params_extra=dict(batch_size=2), steps=1), allow_error=True)
    refusal = receipt["calls"][-1]
    receipt["batch_refusal"] = refusal["result"]
    assert refusal["error"] and "batch" in json.dumps(refusal["result"]).lower(), refusal
    await call("run_script", script(steps=40), allow_error=True)
    refusal = receipt["calls"][-1]
    receipt["steps_refusal"] = refusal["result"]
    assert refusal["error"] and "steps" in json.dumps(refusal["result"]).lower(), refusal

    # 8. cancel in the middle of a walk, then an identical run afterwards
    pending = dict(script(steps=8), **{"async": True})
    pending["params"]["steps"] = 60
    queued = await call("run_script", pending)
    for _ in range(600):
        progress = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 1, "return_images": False}, allow_error=True)
        if "step 2/" in str(progress.get("message", "")) or progress.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_requested_at"] = progress.get("message")
    await call("cancel_job", {"job_id": queued["job_id"]})
    for _ in range(120):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False}, allow_error=True)
        if state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_state"] = state.get("status")
    assert state.get("status") == "cancelled", state
    recovered = split(images(await call("run_script", script(steps=3))), None)[0]
    assert [pixels(p) for p in recovered] == [pixels(p) for p in steps], "generation after cancel differs"
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
    p.add_argument("--denoise", type=float, default=0.9)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--start-prompt", default="fantasy dungeon hallway, stone walls, red carpet, torches, one point perspective")
    p.add_argument("--prompt", default="fantasy dungeon hallway, engraved stone walls, red carpet, glowing lights, "
                                       "intricate, cinematic lighting, masterpiece")
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "outputs": receipt.get("outputs"),
                      "window": receipt.get("window_vs_previous_vs_other_region"), "gif": receipt.get("gif")}, indent=2))


if __name__ == "__main__":
    main()
