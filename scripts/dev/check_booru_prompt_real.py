"""Real-model Booru prompt acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

Uses the live booru APIs (Danbooru, e621, Safebooru, Konachan; Gelbooru/Rule34 must report the missing key)
and a real SD1.5 or SDXL checkpoint. Run it against your own build of the jar.
"""
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


def booru_posts(infotext):
    return infotext.split("Booru posts: ")[1].split(",")[0].split("\n")[0].split()


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
    assert "booru_prompt" in scripts, scripts
    await call("set_model", {"name": args.checkpoint})
    base_prompt = "masterpiece, best quality"
    params = dict(prompt=base_prompt, negative_prompt="lowres, blurry", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=1357, sampler_name="Euler", return_images=False,
                  model_keyword=False)
    sparams = {k: v for k, v in params.items() if k != "return_images"}

    async def script(values, extra_params=None, allow_error=False):
        return await call("run_script", {"script_id": "booru_prompt", "values": values,
                                         "params": dict(sparams, **(extra_params or {})), "return_images": False},
                          allow_error=allow_error)

    async def meta(path):
        return await call("inspect_generation_metadata", {"image": path})

    out = {"baseline": paths(await call("txt2img", params))[0]}

    # ---- specific post: Danbooru post 1 --------------------------------------------------------------
    post = {"booru": "Danbooru", "mode": "Specific post(s)", "post": "https://danbooru.donmai.us/posts/1?q=x", "use_cache": True}
    out["post1"] = paths(await script(post))[0]
    assert rgb(out["post1"]) != rgb(out["baseline"]), "the booru tags changed nothing"
    m = await meta(out["post1"])
    prompt = m["generation_args"]["prompt"]
    assert prompt.startswith(base_prompt + ", ") and "animal ears" in prompt, prompt
    assert "Booru: Danbooru" in m["infotext"] and "Booru posts: 1" in m["infotext"], m["infotext"]
    assert "watermark" not in prompt and "bad id" not in prompt, prompt
    receipt["post1_prompt"] = prompt
    # the PNG carries the resolved prompt: replay with plain txt2img needs no network and no script
    replay = paths(await call("txt2img", dict(m["generation_args"], return_images=False, model_keyword=False)))[0]
    assert rgb(replay) == rgb(out["post1"]), "txt2img replay of the saved prompt did not reproduce the image"
    out["post1_replay"] = replay
    out["post1_repeat"] = paths(await script(post))[0]
    assert rgb(out["post1_repeat"]) == rgb(out["post1"]), "the same specific post is not reproducible"

    # ---- tag options change the prompt and the image ---------------------------------------------------
    out["post1_artist"] = paths(await script(dict(post, cat_artist=True, artist_prefix="by ", cat_meta=True, remove_tags="*ears",
                                                  append_tags="1girl, best_quality")))[0]
    a = (await meta(out["post1_artist"]))["generation_args"]["prompt"]
    assert "ears" not in a and a.endswith("best quality"), a
    receipt["post1_artist_prompt"] = a
    out["post1_neg"] = paths(await script(dict(post, chaos_mode="Negative mode")))[0]
    n = (await meta(out["post1_neg"]))["generation_args"]
    assert n["prompt"] == base_prompt and "animal ears" in n["negative_prompt"], n
    assert rgb(out["post1_neg"]) != rgb(out["post1"])

    # ---- random posts: reproducible within a session (cache), different per image ----------------------
    rnd = {"booru": "Danbooru", "mode": "Random post", "search": "1girl, solo", "rating": "General", "max_pages": 3,
           "use_cache": True}
    batch = paths(await script(rnd, {"n_iter": 2}), 2)
    out["random0"], out["random1"] = batch
    posts = [booru_posts((await meta(p))["infotext"])[0] for p in batch]
    assert posts[0] != posts[1], posts
    again = paths(await script(rnd, {"n_iter": 2}), 2)
    assert [rgb(p) for p in again] == [rgb(p) for p in batch], "random posts are not reproducible with the same seed"
    mm = await meta(batch[0])
    assert "Booru search: 1girl solo" in mm["infotext"], mm["infotext"]
    receipt["random_prompts"] = [(await meta(p))["generation_args"]["prompt"] for p in batch]
    out["random_replay"] = paths(await call("txt2img", dict(mm["generation_args"], return_images=False, model_keyword=False)))[0]
    assert rgb(out["random_replay"]) == rgb(batch[0]), "random post replay did not reproduce the image"
    out["same_prompt"] = paths(await script(dict(rnd, same_prompt=True), {"n_iter": 2}), 2)
    pa, pb = [(await meta(p))["generation_args"]["prompt"] for p in out["same_prompt"]]
    assert pa == pb, "same prompt option gave different prompts"

    # ---- mixing ------------------------------------------------------------------------------------
    out["mix"] = paths(await script(dict(rnd, mix=True, mix_amount=3)))[0]
    ids = booru_posts((await meta(out["mix"]))["infotext"])
    assert len(set(ids)) == 3, ids

    # ---- other boorus (a quick low-step smoke each) ------------------------------------------------
    smoke = {}
    for booru, search in (("e621", "solo"), ("Safebooru", "1girl"), ("Konachan", "landscape")):
        o = paths(await script({"booru": booru, "search": search, "rating": "General", "max_pages": 2, "use_cache": True},
                               {"steps": 6}))[0]
        mi = await meta(o)
        assert f"Booru: {booru}" in mi["infotext"], mi["infotext"]
        smoke[booru] = mi["generation_args"]["prompt"]
        out["site_" + booru] = o
    receipt["site_prompts"] = smoke
    for booru in ("Gelbooru", "Rule34"):
        err = await script({"booru": booru, "search": "1girl"}, allow_error=True)
        assert "error" in err and "API key" in json.dumps(err), err
    unknown = await script({"booru": "Danbooru", "mode": "Specific post(s)", "post": "hello.png"}, allow_error=True)
    assert "error" in unknown, unknown

    # ---- img2img from the post's picture -------------------------------------------------------------
    i2i = dict(post, use_img2img=True, denoising=0.6)
    out["img2img"] = paths(await script(i2i))[0]
    assert rgb(out["img2img"]) != rgb(out["post1"]), "img2img mode produced the txt2img image"
    out["img2img_low"] = paths(await script(dict(i2i, denoising=0.25)))[0]
    out["img2img_high"] = paths(await script(dict(i2i, denoising=0.9)))[0]
    assert len({rgb(out[k])[1] for k in ("img2img", "img2img_low", "img2img_high")}) == 3, "denoising did not matter"
    out["img2img_repeat"] = paths(await script(i2i))[0]
    assert rgb(out["img2img_repeat"]) == rgb(out["img2img"]), "img2img mode is not reproducible"

    # ---- cancel and recover --------------------------------------------------------------------------
    queued = await call("run_script", {"script_id": "booru_prompt", "async": True, "values": post,
                                       "params": dict(sparams, steps=150)})
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
    assert rgb(out["after_cancel"]) == rgb(out["baseline"]), "generation differs after the cancel"
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
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--cfg", type=float, default=6.0)
    p.add_argument("--vram-mode", default="model_offload")
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
