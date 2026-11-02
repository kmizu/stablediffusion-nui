"""Real-model AnimateDiff acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

Runs the `animatediff` script on a real checkpoint with a real motion module and checks: the video files and frame
counts, determinism, PNG-replay through inspect_generation_metadata, context windows (long video), prompt travel,
FreeInit, img2video, closed loop, option validation, cancel recovery and that normal generation is unchanged afterwards.
Contact sheets of the frames are written next to the receipt so they can be looked at.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image, ImageChops, ImageStat


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def gif_frames(path):
    out = []
    with Image.open(path) as image:
        for i in range(image.n_frames):
            image.seek(i)
            out.append(image.convert("RGB").copy())
    return out


def diff(a, b):
    return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 3.0


def sheet(frames, path, columns=8, width=1500):
    cell = max(32, width // columns)
    rows = (len(frames) + columns - 1) // columns
    h = round(cell * frames[0].height / frames[0].width)
    canvas = Image.new("RGB", (cell * columns, h * rows), (0, 0, 0))
    for i, frame in enumerate(frames):
        canvas.paste(frame.resize((cell, h)), ((i % columns) * cell, (i // columns) * h))
    canvas.save(path)
    return str(path)


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
    assert "animatediff" in scripts, scripts
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text, watermark", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=1234, sampler_name=args.sampler, return_images=False,
                  model_keyword=False)
    sparams = {k: v for k, v in params.items() if k != "return_images"}
    frames_n = args.frames
    base_values = {"model": args.motion, "video_length": frames_n, "batch_size": frames_n, "fps": 8, "closed_loop": "R+P",
                   "format_gif": True, "format_mp4": True, "format_webp": True, "format_png": False}

    skipped = {x for x in args.skip.split(',') if x}

    def want(name):
        return name not in skipped

    async def video(extra_values=None, extra_params=None, mode_args=None):
        data = await call("run_script", {"script_id": "animatediff", "values": dict(base_values, **(extra_values or {})),
                                         "params": dict(sparams, **(extra_params or {})), "return_images": False, **(mode_args or {})})
        assert data.get("images"), data
        return data

    def stem(data):
        first = Path(data["images"][0]["path"])
        return first.with_suffix("")

    out = {}
    receipt["outputs"] = out
    baseline = (await call("txt2img", params))["images"][0]["path"]
    out["baseline"] = baseline

    a = await video()
    sa = stem(a)
    gif = gif_frames(str(sa) + ".gif")
    out["video"] = str(sa)
    assert len(gif) == frames_n, len(gif)
    assert all(g.size == (args.width, args.height) for g in gif)
    assert os.path.getsize(str(sa) + ".mp4") > 1000 and os.path.getsize(str(sa) + ".webp") > 1000
    with Image.open(str(sa) + ".webp") as w:
        assert w.n_frames == frames_n, w.n_frames
    sheet(gif, sheets / "video.png", columns=min(8, frames_n))
    motion = [diff(gif[i], gif[i + 1]) for i in range(len(gif) - 1)]
    receipt["adjacent_frame_diff"] = {"min": min(motion), "max": max(motion), "mean": sum(motion) / len(motion)}
    assert min(motion) > 0.05, "the frames do not move at all"
    assert max(motion) < args.max_adjacent, "the frames are not temporally coherent"
    assert diff(gif[0], gif[-1]) > 0.2

    if not args.tail_only:
        def contrast(frames):
            return sum(sum(ImageStat.Stat(f).stddev) / 3.0 for f in frames) / len(frames)

        receipt["contrast"] = {"video_linear_betas": contrast(gif),
                               "baseline_image": sum(ImageStat.Stat(Image.open(baseline).convert("RGB")).stddev) / 3.0}
        if want('betas'):
            grey = await video({"linear_betas": False})
            ggrey = gif_frames(str(stem(grey)) + ".gif")
            receipt["contrast"]["video_checkpoint_betas"] = contrast(ggrey)
            sheet(ggrey, sheets / "checkpoint_betas.png", columns=min(8, frames_n))
            assert contrast(gif) > contrast(ggrey), "the linear beta schedule did not raise the contrast"

        if want('determinism'):
            b = await video()
            gb = gif_frames(str(stem(b)) + ".gif")
            assert all(diff(x, y) == 0 for x, y in zip(gif, gb)), "the same request is not reproducible"

        if want('replay'):
            meta = await call("inspect_generation_metadata", {"image": a["images"][0]["path"]})
            candidate = meta.get("scripts", {}).get("animatediff")
            assert candidate, meta
            assert candidate["video_length"] == frames_n and candidate["model"] == args.motion, candidate
            assert "AnimateDiff" not in meta["unapplied_fields"], meta["unapplied_fields"]
            replay = await call("run_script", {"script_id": "animatediff", "values": candidate,
                                               "params": dict(meta["generation_args"], model_keyword=False), "return_images": False})
            gr = gif_frames(str(stem(replay)) + ".gif")
            assert all(diff(x, y) == 0 for x, y in zip(gif, gr)), "PNG replay did not reproduce the video"
            out["replay"] = str(stem(replay))

        if args.alt_motion:
            c = await video({"model": args.alt_motion})
            gc = gif_frames(str(stem(c)) + ".gif")
            sheet(gc, sheets / "alt_motion.png", columns=min(8, frames_n))
            assert any(diff(x, y) > 0.2 for x, y in zip(gif, gc)), "a different motion module changed nothing"
            out["alt_motion"] = str(stem(c))

        if args.same_motion:  # the same weights in another format (diffusers folder vs original file) give the same frames
            same = await video({"model": args.same_motion})
            gsame = gif_frames(str(stem(same)) + ".gif")
            receipt["same_motion_max_diff"] = max(diff(x, y) for x, y in zip(gif, gsame))
            assert receipt["same_motion_max_diff"] == 0, "the same motion weights in another format gave other frames"
            out["same_motion"] = str(stem(same))

        if want('seed'):
            seeded = await video(extra_params={"seed": 99})
            gs = gif_frames(str(stem(seeded)) + ".gif")
            assert any(diff(x, y) > 1 for x, y in zip(gif, gs)), "the seed did not matter"

        if args.long_frames:
            d = await video({"video_length": args.long_frames, "overlap": 4, "stride": 2, "closed_loop": "R+P"})
            gd = gif_frames(str(stem(d)) + ".gif")
            assert len(gd) == args.long_frames, len(gd)
            sheet(gd, sheets / "long_context.png", columns=8)
            longdiff = [diff(gd[i], gd[i + 1]) for i in range(len(gd) - 1)]
            receipt["long_adjacent_frame_diff"] = {"max": max(longdiff), "mean": sum(longdiff) / len(longdiff)}
            assert max(longdiff) < args.max_adjacent
            out["long"] = str(stem(d))
            e = await video({"video_length": args.long_frames, "closed_loop": "N"})
            ge = gif_frames(str(stem(e)) + ".gif")
            assert len(ge) == args.long_frames and any(diff(x, y) > 0.2 for x, y in zip(gd, ge)), "closed loop N == R+P"

        if want('travel'):
            travel_prompt = args.travel_prompt.replace("\\n", "\n")
            t = await video(extra_params={"prompt": travel_prompt})
            gt = gif_frames(str(stem(t)) + ".gif")
            sheet(gt, sheets / "prompt_travel.png", columns=min(8, frames_n))
            assert any(diff(x, y) > 1 for x, y in zip(gif, gt)), "prompt travel changed nothing"
            out["travel"] = str(stem(t))

        if want('freeinit'):
            f = await video({"free_init": True, "free_init_iterations": 2})
            gf = gif_frames(str(stem(f)) + ".gif")
            sheet(gf, sheets / "freeinit.png", columns=min(8, frames_n))
            assert any(diff(x, y) > 0.5 for x, y in zip(gif, gf)), "FreeInit changed nothing"
            out["freeinit"] = str(stem(f))

        if want('closed'):
            closed = await video({"closed_loop": "A", "batch_size": frames_n, "video_length": frames_n})
            gcl = gif_frames(str(stem(closed)) + ".gif")
            assert len(gcl) == 2 * frames_n - 2, len(gcl)
            out["closed_a"] = str(stem(closed))

        # img2vid: the first frame of the video as the init image
        init = Path(a["images"][0]["path"])
        i2v = await call("run_script", {"script_id": "animatediff", "return_images": False,
            "values": dict(base_values, latent_scale=frames_n * 2, format_mp4=False, format_webp=False),
            "params": dict(sparams, init_image=str(init), denoising_strength=0.75)})
        gi = gif_frames(str(stem(i2v)) + ".gif")
        sheet(gi, sheets / "img2vid.png", columns=min(8, frames_n))
        assert len(gi) == frames_n
        with Image.open(init) as src:
            first = src.convert("RGB")
        assert diff(gi[0], first) < diff(gi[-1], first) + 1e-6, "img2vid does not start nearer to the init image"
        out["img2vid"] = str(stem(i2v))

        # PNG frames saved to the gallery
        if want('png'):
            p = await video({"format_png": True, "format_mp4": False, "format_webp": False, "video_length": 4, "batch_size": 4})
            assert len(p["images"]) == 4, p["images"]
            assert len({rgb(i["path"])[1] for i in p["images"]}) == 4, "PNG frames are identical"

    # option validation (errors, not silent changes)
    bad = await call("run_script", {"script_id": "animatediff", "values": dict(base_values, model="nowhere/missing.safetensors"),
                                    "params": sparams, "return_images": False}, allow_error=True)
    assert "error" in bad and "motion module" in bad["text"], bad
    bad = await call("run_script", {"script_id": "animatediff", "values": base_values,
                                    "params": dict(sparams, batch_size=2), "return_images": False}, allow_error=True)
    assert "error" in bad and "batch size" in bad["text"], bad
    bad = await call("run_script", {"script_id": "animatediff", "values": base_values,
                                    "params": dict(sparams, hires={"enabled": True, "scale": 1.5}), "return_images": False},
                     allow_error=True)
    assert "error" in bad and "Hires" in bad["text"], bad

    # cancel mid run, then ordinary generation is bit-identical to before (hooks removed, nothing leaked)
    queued = await call("run_script", {"script_id": "animatediff", "async": True,
                                       "values": dict(base_values, video_length=frames_n), "params": dict(sparams, steps=args.steps * 4)})
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
    after = (await call("txt2img", params))["images"][0]["path"]
    assert rgb(after) == rgb(baseline), "normal generation changed after AnimateDiff / cancel"
    out["after_cancel"] = after
    if want("again"):
        again = await video()
        assert all(diff(x, y) == 0 for x, y in zip(gif, gif_frames(str(stem(again)) + ".gif"))), "video changed after the cancel"
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
                                            cwd=str(args.source.resolve())), read_timeout_seconds=7200) as client:
        await exercise(client, args, receipt)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--models", type=Path, required=True)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--motion", required=True, help="motion module (file, diffusers folder or HF repo id)")
    p.add_argument("--alt-motion", default="", help="a second motion module to compare")
    p.add_argument("--same-motion", default="", help="the same weights in another format: frames must be identical")
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--cfg", type=float, default=7.0)
    p.add_argument("--sampler", default="Euler a")
    p.add_argument("--frames", type=int, default=16)
    p.add_argument("--long-frames", type=int, default=24, help="0 skips the context-window run")
    p.add_argument("--max-adjacent", type=float, default=40, help="largest mean pixel difference between neighbouring frames")
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--skip", default="", help="comma list of sections to skip: betas, determinism, replay, seed, travel, freeinit, closed, png, again")
    p.add_argument("--tail-only", action="store_true", help="skip the long middle section: baseline, one video, option errors, cancel, recovery")
    p.add_argument("--prompt", default="1girl, solo, smile, long hair, flower field, blue sky, wind, masterpiece, best quality")
    p.add_argument("--travel-prompt", default="1girl, solo, flower field, masterpiece, best quality,\\n0: smile, black hair\\n"
                                              "7: surprised, blonde hair\\nwind, blue sky")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "outputs": receipt.get("outputs"),
                      "adjacent": receipt.get("adjacent_frame_diff")}, indent=2))


if __name__ == "__main__":
    main()
