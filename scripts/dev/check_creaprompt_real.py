"""Real-model CreaPrompt acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

Runs the creaprompt script on a real SD1.5 or SDXL checkpoint against your own build of the jar; no network is used.
A user category (creaprompt/csv/9_1Mine.csv in the app home) is created by the script itself.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import re
import zipfile
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def cat_key(name):
    return "cat_" + re.sub(r"[^A-Za-z0-9]+", "_", name)


def category_names(jar):
    with zipfile.ZipFile(jar) as z:
        index = z.read("creaprompt/index.txt").decode("utf-8").splitlines()
    names = []
    for line in sorted(x for x in index if x.endswith(".csv")):
        stem = line[:-4]
        names.append(stem[3:] if re.match(r"[0-9]_[0-9]", stem) else stem)
    return names


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
    assert "creaprompt" in scripts, scripts
    await call("set_model", {"name": args.checkpoint})
    base_prompt = "masterpiece"
    params = dict(prompt=base_prompt, negative_prompt="lowres, blurry", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=2468, sampler_name="Euler", return_images=False,
                  model_keyword=False)
    sparams = {k: v for k, v in params.items() if k != "return_images"}
    names = category_names(args.jar)
    none = {cat_key(n): False for n in names}

    async def script(values, extra_params=None, allow_error=False):
        return await call("run_script", {"script_id": "creaprompt", "values": values,
                                         "params": dict(sparams, **(extra_params or {})), "return_images": False},
                          allow_error=allow_error)

    async def meta(path):
        return await call("inspect_generation_metadata", {"image": path})

    async def prompt_of(path):
        return (await meta(path))["generation_args"]["prompt"]

    out = {"baseline": paths(await call("txt2img", params))[0]}

    # ---- random from categories (preset Woman), a different prompt per image -------------------------
    rnd = {"mode": "Random from categories", "preset": "Woman"}
    batch = paths(await script(rnd, {"n_iter": 2}), 2)
    out["random0"], out["random1"] = batch
    prompts = [await prompt_of(p) for p in batch]
    assert prompts[0] != prompts[1] and all(p != base_prompt for p in prompts), prompts
    assert rgb(batch[0]) != rgb(out["baseline"]) and rgb(batch[0]) != rgb(batch[1])
    info = (await meta(batch[0]))["infotext"]
    # the value contains commas, so the infotext writer quotes it
    assert 'CreaPrompt random From categories: "Woman, haircuts_Thomas_Buyle,' in info, info
    receipt["random_prompts"] = prompts
    saved_preset = paths(await script({"preset": "SDK User"}))[0]
    saved_info = (await meta(saved_preset))["infotext"]
    assert 'CreaPrompt random From categories: "Woman, Cameras"' in saved_info, saved_info
    out["saved_preset"] = saved_preset
    again = paths(await script(rnd, {"n_iter": 2}), 2)
    assert [rgb(p) for p in again] == [rgb(p) for p in batch], "the same seed gave other prompts or images"
    m = await meta(batch[1])
    replay = paths(await call("txt2img", dict(m["generation_args"], return_images=False, model_keyword=False)))[0]
    assert rgb(replay) == rgb(batch[1]), "txt2img replay of the saved prompt did not reproduce the image"
    out["random_replay"] = replay

    # ---- one prompt for the whole batch, prefix / suffix / position ------------------------------------
    same = paths(await script(dict(rnd, per_image=False, prefix="PFX", suffix="SFX", position="After prompt"), {"n_iter": 2}), 2)
    out["same0"], out["same1"] = same
    pa, pb = [await prompt_of(p) for p in same]
    assert pa == pb and pa.startswith("masterpiece, PFX, ") and pa.endswith(", SFX"), (pa, pb)
    assert rgb(same[0]) != rgb(same[1]), "batch images share a seed"

    # ---- collection ---------------------------------------------------------------------------------
    col = paths(await script({"mode": "Prompt collection"}, {"n_iter": 2}), 2)
    out["col0"], out["col1"] = col
    cp = [await prompt_of(p) for p in col]
    assert cp[0] != cp[1], cp
    assert "CreaPrompt: Collection" in (await meta(col[0]))["infotext"]
    receipt["collection_prompts"] = cp

    # ---- user folder: a new category and a manual pick ------------------------------------------------
    user = dict(none, cat_Mine=True)
    mine = paths(await script(dict(user, mode="Random from categories", preset="(use the checked categories)")))[0]
    out["mine"] = mine
    mine_prompt = await prompt_of(mine)
    assert mine_prompt in ("crimson lighthouse at dawn", "silver whale over a quiet city"), mine_prompt
    man = paths(await script({"mode": "Manual (choose per category)",
                              "manual_list": "Mine: silver whale over a quiet city\nCameras: random"}))[0]
    out["manual"] = man
    mp = await prompt_of(man)
    # library order is file-name order: 7_4Cameras comes before 9_1Mine
    assert mp.endswith(", silver whale over a quiet city"), mp
    assert "CreaPrompt: manual from categories" in (await meta(man))["infotext"]
    receipt["manual_prompt"] = mp
    # The same manual picks can be made using the per-category controls, without typing the list.
    selected = paths(await script({"mode": "Manual (choose per category)",
                                   "manual_cat_Mine": "silver whale over a quiet city",
                                   "manual_cat_Cameras": "(random)"}))[0]
    assert await prompt_of(selected) == mp
    assert rgb(selected) == rgb(man), "manual dropdowns did not reproduce the text-list selection"
    out["manual_selected"] = selected

    # ---- errors ------------------------------------------------------------------------------------
    for bad in ({"mode": "Random from categories", "preset": "(use the checked categories)", **none},
                {"mode": "Manual (choose per category)", "manual_list": "Nothing: random"},
                {"mode": "Manual (choose per category)", "manual_list": "Mine: not there"},
                {"preset": "Deselect All"}):
        err = await script(bad, allow_error=True)
        assert "error" in err, (bad, err)

    # ---- cancel and recover ----------------------------------------------------------------------
    queued = await call("run_script", {"script_id": "creaprompt", "async": True, "values": rnd,
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
    if args.launcher:
        bundled_jar = args.launcher.resolve().parent / "app" / "stablediffusion-nui.jar"
        if not bundled_jar.is_file() or hashlib.sha256(bundled_jar.read_bytes()).digest() != hashlib.sha256(args.jar.read_bytes()).digest():
            raise ValueError("--jar must match the extracted launcher's app/stablediffusion-nui.jar")
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    # AppHome.detect() only accepts a home that has python/sdfx (an empty folder is enough: a regular package elsewhere wins)
    (app_home / "python" / "sdfx").mkdir(parents=True, exist_ok=True)
    (app_home / "creaprompt" / "csv").mkdir(parents=True, exist_ok=True)
    (app_home / "creaprompt" / "csv" / "9_1Mine.csv").write_text(
        "crimson lighthouse at dawn\nsilver whale over a quiet city\n", encoding="utf-8")
    (app_home / "creaprompt" / "csv" / "SDK User.config").write_text("Woman\nCameras\n", encoding="utf-8")
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs={"checkpoints": [str(args.models.resolve())]}, last_model=args.checkpoint,
                    vram_mode=args.vram_mode, dtype="fp16", preview_every=0)
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    # Verify --home is authoritative, including for plugin resources.
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    env.pop("SDFX_HOME", None)
    if args.launcher:
        command = [str(args.launcher.resolve()), "--mcp", "--home", str(app_home), "--drain-seconds", "5"]
    else:
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
    p.add_argument("--launcher", type=Path, help="extracted SDNUI.exe (uses its bundled runtime)")
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
