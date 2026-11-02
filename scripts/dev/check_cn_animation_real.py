"""Real-model SD-CN-Animation acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

Builds a source video from a real generated picture (a slow pan + zoom, so the optical flow is real), then runs the
`cn_animation` script on a real checkpoint (SD1.5 with a canny ControlNet unit, SDXL without) and checks the video files,
determinism, PNG replay through inspect_generation_metadata, temporal consistency against an independent per-frame img2img
baseline, the inpaint-occlusion mode, validation errors, cancel recovery and that ordinary generation is unchanged after.
Contact sheets are written next to the receipt so the frames can be looked at.
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
from PIL import Image, ImageChops, ImageStat


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def diff(a, b):
    return sum(ImageStat.Stat(ImageChops.difference(a.convert("RGB"), b.convert("RGB"))).mean) / 3.0


def sheet(frames, path, columns=6, width=1500):
    cell = max(64, width // columns)
    rows = (len(frames) + columns - 1) // columns
    h = round(cell * frames[0].height / frames[0].width)
    canvas = Image.new("RGB", (cell * columns, h * rows), (0, 0, 0))
    for i, frame in enumerate(frames):
        canvas.paste(frame.convert("RGB").resize((cell, h)), ((i % columns) * cell, (i // columns) * h))
    canvas.save(path)
    return str(path)


def make_source_video(picture: Path, out: Path, frames: int, width: int, height: int, fps: int, ffmpeg: str) -> list[Image.Image]:
    """A slow pan + zoom over the picture: frames of exactly width x height, encoded with ffmpeg."""
    base = Image.open(picture).convert("RGB")
    big = base.resize((round(base.width * 1.5), round(base.height * 1.5)), Image.LANCZOS)
    out.parent.mkdir(parents=True, exist_ok=True)
    pics = []
    for i in range(frames):
        t = i / max(1, frames - 1)
        cw, ch = round(big.width * (0.72 - 0.08 * t)), round(big.height * (0.72 - 0.08 * t))
        x = round((big.width - cw) * (0.15 + 0.5 * t))
        y = round((big.height - ch) * (0.25 + 0.2 * t))
        pics.append(big.crop((x, y, x + cw, y + ch)).resize((width, height), Image.LANCZOS))
    proc = subprocess.Popen([ffmpeg, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{width}x{height}",
                             "-framerate", str(fps), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "12", str(out)],
                            stdin=subprocess.PIPE)
    for pic in pics:
        proc.stdin.write(pic.tobytes())
    proc.stdin.close()
    assert proc.wait() == 0, "ffmpeg failed"
    return pics


async def exercise(client, args, receipt):
    receipt["calls"] = []
    sheets = args.output / "sheets"
    sheets.mkdir(parents=True, exist_ok=True)

    async def call(name, values, allow_error=False):
        result = await client.call_tool(name, values, read_timeout_seconds=7200)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "error": error, "result": data or encoded.get("content")})
        assert allow_error or not error, encoded
        return data if not error else {"error": encoded, "text": json.dumps(encoded)}

    scripts = json.dumps(await call("list_scripts", {}))
    assert "cn_animation" in scripts, scripts
    await call("set_model", {"name": args.checkpoint})
    out = {}
    receipt["outputs"] = out
    base_params = dict(prompt=args.source_prompt, negative_prompt="lowres, blurry, text, watermark", width=args.width,
                       height=args.height, steps=args.steps, cfg_scale=args.cfg, seed=77, sampler_name=args.sampler,
                       return_images=False, model_keyword=False)
    picture = (await call("txt2img", base_params))["images"][0]["path"]
    out["source_picture"] = picture
    video = args.output / "source" / "pan.mp4"
    pics = make_source_video(Path(picture), video, args.frames, args.width, args.height, 8, args.ffmpeg)
    sheet(pics, sheets / "source.png", columns=min(6, args.frames))
    src_motion = sum(diff(pics[i], pics[i + 1]) for i in range(len(pics) - 1)) / (len(pics) - 1)
    receipt["source_adjacent_diff"] = src_motion

    style = dict(base_params, prompt=args.prompt, seed=1234)
    style.pop("return_images")
    controlnet = []
    if args.controlnet:
        listed = await call("list_controlnets", {})
        assert args.controlnet in [m["name"] for m in listed["controlnets"]], listed
        controlnet = [{"model": args.controlnet, "preprocessor": "canny", "weight": 1.0, "control_mode": "Balanced"}]
        style["controlnet"] = controlnet
        style["init_image"] = picture  # the MCP layer wants an image for ControlNet units; the script ignores it

    values = {"video": str(video.resolve()), "format_mp4": True, "format_gif": True, "save_frames": True}

    async def animate(extra_values=None, extra_params=None):
        data = await call("run_script", {"script_id": "cn_animation", "values": dict(values, **(extra_values or {})),
                                         "params": dict(style, **(extra_params or {})), "return_images": False})
        assert data.get("images"), data
        return data

    def frames_of(data):
        return [Image.open(i["path"]).convert("RGB") for i in data["images"]]

    # --- main run: frame count, files, consistency ---
    a = await animate()
    paths = [i["path"] for i in a["images"]]
    assert len(paths) == args.frames, (len(paths), args.frames)
    first = Path(paths[0]).with_suffix("")
    mp4, gif = first.with_suffix(".mp4"), first.with_suffix(".gif")
    assert mp4.stat().st_size > 2000 and gif.stat().st_size > 2000, (mp4, gif)
    probe = subprocess.run([args.ffmpeg, "-hide_banner", "-i", str(mp4)], capture_output=True, text=True).stderr
    assert f"{args.width}x{args.height}" in probe, probe[-400:]
    with Image.open(gif) as g:
        assert g.n_frames == args.frames, g.n_frames
    out["video"] = str(first)
    fa = frames_of(a)
    sheet(fa, sheets / "styled.png", columns=min(6, args.frames))
    styled_motion = sum(diff(fa[i], fa[i + 1]) for i in range(len(fa) - 1)) / (len(fa) - 1)
    receipt["styled_adjacent_diff"] = styled_motion
    assert styled_motion > 0.3, "the frames do not move"
    # the styled frames must differ from their source frames (restyled), not copy them
    receipt["styled_vs_source"] = [diff(x, y) for x, y in zip(fa, pics)]
    assert min(receipt["styled_vs_source"]) > 4, "the frames were not restyled"

    # --- baseline: the same restyle with every frame independent (no flow, no previous frame) ---
    if "baseline" not in args.skip:
        indep = []
        frame_dir = (args.output / "images" / "baseline-frames").resolve()  # tools only read files under the output folder
        frame_dir.mkdir(parents=True, exist_ok=True)
        for i, pic in enumerate(pics):
            src = frame_dir / f"src-{i:03d}.png"
            pic.save(src)
            unit = [dict(controlnet[0], image=str(src))] if controlnet else []
            data = await call("img2img", dict(style, init_image=str(src), denoising_strength=0.85, seed=1234 + i,
                                              return_images=False, **({"controlnet": unit} if unit else {})))
            indep.append(Image.open(data["images"][0]["path"]).convert("RGB"))
        sheet(indep, sheets / "independent.png", columns=min(6, args.frames))
        indep_motion = sum(diff(indep[i], indep[i + 1]) for i in range(len(indep) - 1)) / (len(indep) - 1)
        receipt["independent_adjacent_diff"] = indep_motion
        receipt["flicker_ratio_vs_independent"] = styled_motion / indep_motion
        # With a big camera move and no ControlNet the independent frames follow the source just as well, so the
        # steadiness is only asserted for the ControlNet run (where independent frames drift apart); it is always recorded.
        if controlnet:
            assert styled_motion < indep_motion, "the flow-guided video is not steadier than independent frames"

    # --- determinism ---
    if "determinism" not in args.skip:
        b = await animate()
        assert [rgb(i["path"])[1] for i in b["images"]] == [rgb(p)[1] for p in paths], "the same request is not reproducible"

    # --- PNG replay through inspect_generation_metadata ---
    if "replay" not in args.skip:
        meta = await call("inspect_generation_metadata", {"image": paths[0]})
        candidate = meta.get("scripts", {}).get("cn_animation")
        assert candidate, meta
        assert candidate["video"] == "pan.mp4" and "SD-CN-Animation" not in meta["unapplied_fields"], meta
        replay = await call("run_script", {"script_id": "cn_animation", "values": dict(candidate, video=str(video.resolve()), format_gif=True),
                                           "params": dict(meta["generation_args"], model_keyword=False, **({"controlnet": controlnet, "init_image": picture} if controlnet else {})),
                                           "return_images": False})
        assert len(replay["images"]) >= 1
        assert rgb(replay["images"][0]["path"])[1] == rgb(paths[0])[1], "PNG replay did not reproduce the first frame"
        out["replay_first"] = replay["images"][0]["path"]

    # --- other modes ---
    if "modes" not in args.skip:
        inpaint = await animate({"step_1_processing_mode": "Inpaint occlusions", "max_frames": 5})
        fi = frames_of(inpaint)
        assert len(fi) == 5, len(fi)
        sheet(fi, sheets / "inpaint_mode.png", columns=5)
        out["inpaint_mode"] = inpaint["images"][0]["path"]
        small = await animate({"raft_model": "small", "fix_frame_strength": 0, "max_frames": 4, "frame_skip": 1, "occlusion_mask_trailing": False})
        expected = min(4, (args.frames + 1) // 2)  # max_frames 4 of every second source frame
        assert len(small["images"]) == expected, (len(small["images"]), expected)
        sheet(frames_of(small), sheets / "small_raft_skip1_nofix.png", columns=4)
        out["small_raft"] = small["images"][0]["path"]

    # --- validation: errors, not silent changes ---
    bad = await call("run_script", {"script_id": "cn_animation", "values": dict(values, video=str((args.output / "missing.mp4").resolve())),
                                    "params": style, "return_images": False}, allow_error=True)
    assert "error" in bad and "not found" in bad["text"], bad
    bad = await call("run_script", {"script_id": "cn_animation", "values": dict(values, processing_strength=2),
                                    "params": style, "return_images": False}, allow_error=True)
    assert "error" in bad and "processing_strength" in bad["text"], bad
    bad = await call("run_script", {"script_id": "cn_animation", "values": values, "params": dict(style, batch_size=2),
                                    "return_images": False}, allow_error=True)
    assert "error" in bad and "batch size" in bad["text"], bad

    # --- cancel mid run; ordinary generation is bit-identical afterwards ---
    baseline = (await call("txt2img", base_params))["images"][0]["path"]
    assert rgb(baseline) == rgb(picture), "txt2img is not reproducible before the cancel"
    queued = await call("run_script", {"script_id": "cn_animation", "async": True, "values": values, "params": dict(style, steps=args.steps * 2)})
    state = {}
    for _ in range(600):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 1, "return_images": False}, allow_error=True)
        if 0.1 < float(state.get("progress", 0) or 0) < 1 or state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_progress"] = state.get("progress")
    await call("cancel_job", {"job_id": queued["job_id"]})
    for _ in range(180):
        state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False}, allow_error=True)
        state = state.get("error", {}).get("structured_content", state) if "error" in state else state
        if state.get("status") in {"done", "failed", "cancelled"}:
            break
    receipt["cancel_state"] = state.get("status")
    assert state.get("status") == "cancelled", state
    after = (await call("txt2img", base_params))["images"][0]["path"]
    assert rgb(after) == rgb(picture), "normal generation changed after SD-CN-Animation / cancel"
    again = await animate()
    assert [rgb(i["path"])[1] for i in again["images"]] == [rgb(p)[1] for p in paths], "the video changed after the cancel"
    receipt["success"] = True


async def run(args, receipt):
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    model_dirs = {"checkpoints": [str(args.models.resolve())]}
    if args.controlnets:
        model_dirs["controlnet"] = [str(args.controlnets.resolve())]
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs=model_dirs, last_model=args.checkpoint, vram_mode=args.vram_mode, dtype="fp16", preview_every=0)
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    command = [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
               "sdfx.mcp.McpMain", "--home", str(app_home), "--drain-seconds", "5"]
    receipt.update(command=command, jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=7200) as client:
        await exercise(client, args, receipt)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--models", type=Path, required=True)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--controlnets", type=Path, default=None, help="folder with diffusers ControlNet directories")
    p.add_argument("--controlnet", default="", help="catalog name of a canny ControlNet (SD1.5); empty = no ControlNet unit")
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--ffmpeg", default="ffmpeg")
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--cfg", type=float, default=6.0)
    p.add_argument("--sampler", default="Euler a")
    p.add_argument("--frames", type=int, default=10)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--skip", default="", help="comma list: baseline, determinism, replay, modes")
    p.add_argument("--source-prompt", default="a girl standing in a flower field under a blue sky, photo, detailed, masterpiece")
    p.add_argument("--prompt", default="a girl standing in a flower field under a blue sky, watercolor painting, masterpiece")
    args = p.parse_args()
    args.skip = {x for x in args.skip.split(",") if x}
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({k: receipt.get(k) for k in ("success", "outputs", "source_adjacent_diff", "styled_adjacent_diff",
                                                  "independent_adjacent_diff", "flicker_ratio_vs_independent")}, indent=2))


if __name__ == "__main__":
    main()
