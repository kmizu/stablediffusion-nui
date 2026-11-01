"""Real-model NudeNet NSFW Censor acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

No explicit content is generated. A clothed portrait is censored by enabling NudeNet's *face* categories (normally
off, threshold 1): the script probes the baseline image through the Extras plugin with decreasing face thresholds and
uses the first one that detects a face. Custom-mask and Entire image (always) Extras paths need no detection at all.
Inspect the saved images afterwards (receipt.json lists them); assertions alone are not acceptance.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image, ImageChops

MAGENTA = (255, 0, 255)
FACES = ("threshold_Face_female", "threshold_Face_male")


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def load(path):
    with Image.open(path) as image:
        return image.convert("RGB")


def parameters(path):
    with Image.open(path) as image:
        return image.info.get("parameters", "")


def auxiliary(image_path, suffix):
    """The before-censor copy / mask saved next to a result: `<index>-<seed><suffix>.png` just before it."""
    path = Path(image_path)
    seed = path.stem.split("-", 1)[1]
    index = int(path.stem.split("-", 1)[0])
    found = sorted(path.parent.glob(f"*-{seed}{suffix}.png"))
    found = [p for p in found if int(p.stem.split("-", 1)[0]) < index]
    assert found, f"no {suffix} file for {path}"
    return str(found[-1])


async def exercise(client, args, receipt):
    receipt["calls"] = []

    async def call(name, values):
        result = await client.call_tool(name, values, read_timeout_seconds=1800)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        receipt["calls"].append({"tool": name, "arguments": values, "result": data or encoded.get("content")})
        assert not encoded.get("isError", encoded.get("is_error", False)), encoded
        return data

    def first(data):
        assert data.get("images"), data
        return data["images"][0]["path"]

    listed = await call("list_always_on", {"tab": "txt2img"})
    assert "nsfw_checker" in json.dumps(listed), "nsfw_checker missing: engine feature unavailable?"
    scripts = await call("list_scripts", {})
    assert "nsfw_checker_extras" in json.dumps(scripts)
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="nsfw, nude, lowres, blurry, text", width=args.width,
                  height=args.height, steps=args.steps, seed=args.seed, sampler_name="Euler", return_images=False,
                  model_keyword=False)
    out = {"baseline": first(await call("txt2img", params))}

    async def extras(image, **values):
        data = await call("run_script", {"script_id": "nsfw_checker_extras", "values": values, "return_images": False,
                                         "params": {"init_image": image, "prompt": ""}})
        return [entry["path"] for entry in data.get("images", [])]

    # Controlled positive detection: probe face thresholds on the baseline (mask only, nothing else saved).
    threshold = None
    for value in args.face_thresholds:
        masks = await extras(out["baseline"], mask_only=True, filter_type="No censor",
                             **{key: value for key in FACES})
        if masks:
            threshold, out["probe_mask"] = value, masks[0]
            break
    assert threshold is not None, "NudeNet found no face in the baseline; change --prompt or --seed"
    receipt["face_threshold"] = threshold
    faces = {key: threshold for key in FACES}

    def censor(**over):
        return {"nsfw_checker": dict({"enabled": True, "gen_filter_type": "Fill color", "fill_color": "#ff00ff",
                                      "live_preview_filter_type": "Fill color", "save_mask": True}, **faces, **over)}

    # Generation: censored after refinement, before-censor copy equals the uncensored baseline, mask saved.
    out["censored"] = first(await call("txt2img", dict(params, always_on=censor())))
    out["before_censor"] = auxiliary(out["censored"], "-before-censor")
    out["censor_mask"] = auxiliary(out["censored"], "-censor-mask")
    assert rgb(out["before_censor"]) == rgb(out["baseline"]), "before-censor copy must be the uncensored image"
    assert rgb(out["censored"]) != rgb(out["baseline"])
    mask = load(out["censor_mask"]).convert("L")
    censored = load(out["censored"])
    assert mask.getbbox(), "empty censor mask"
    inside = [censored.getpixel(xy) for xy in [((mask.getbbox()[0] + mask.getbbox()[2]) // 2,
                                                 (mask.getbbox()[1] + mask.getbbox()[3]) // 2)]]
    assert inside == [MAGENTA], inside
    assert parameters(out["before_censor"]) == parameters(out["censored"]) == parameters(out["censor_mask"])

    out["gaussian"] = first(await call("txt2img", dict(params, always_on=censor(gen_filter_type="Gaussian Blur",
                                                                                 save_before_censor=False, save_mask=False))))
    assert rgb(out["gaussian"]) != rgb(out["baseline"])
    out["variable_blur"] = first(await call("txt2img", dict(params, always_on=censor(gen_filter_type="Variable blur"))))
    out["pixelate"] = first(await call("txt2img", dict(params, always_on=censor(gen_filter_type="Pixelate",
                                                                                 mask_shape="Rounded rectangle"))))
    # Upstream defaults (exposed categories only) leave a clothed portrait untouched; disabled restores the baseline.
    out["defaults"] = first(await call("txt2img", dict(params, always_on={"nsfw_checker": {"enabled": True}})))
    assert rgb(out["defaults"]) == rgb(out["baseline"]), "default categories must not censor a clothed portrait"
    out["disabled"] = first(await call("txt2img", dict(params, always_on=censor(enabled=False))))
    assert rgb(out["disabled"]) == rgb(out["baseline"])
    out["after"] = first(await call("txt2img", params))
    assert rgb(out["after"]) == rgb(out["baseline"]), "baseline did not recover after censoring"

    # PNG replay restores the censor settings and reproduces the censored image.
    meta = await call("inspect_generation_metadata", {"image": out["censored"]})
    restored = meta["always_on"].get("nsfw_checker")
    assert restored and restored["enabled"] is True and restored["gen_filter_type"] == "Fill color", meta["always_on"]
    assert "NudeNet NSFW Censor" not in meta["unapplied_fields"], meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = first(await call("txt2img", replay))
    assert rgb(out["replay"]) == rgb(out["censored"]), "PNG replay did not reproduce the censored image"

    # Hires: censoring happens on the final (upscaled) image.
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    out["hires"] = first(await call("txt2img", dict(params, hires=hires, always_on=censor())))
    out["hires_before"] = auxiliary(out["hires"], "-before-censor")
    assert load(out["hires_before"]).size == load(out["hires"]).size == (round(args.width * args.hires_scale),
                                                                          round(args.height * args.hires_scale))

    # Extras: auto-detect with mask output, custom mask union / alone, Entire image (always), no detection.
    auto = await extras(out["baseline"], save_mask=True, **faces)
    assert len(auto) == 2, auto
    out["extras_auto"], out["extras_auto_mask"] = auto
    custom = Path(args.output).resolve() / "images" / "custom-mask.png"
    width, height = load(out["baseline"]).size
    mask_image = Image.new("L", (width, height), 0)
    mask_image.paste(255, (0, 0, width // 4, height // 4))
    mask_image.save(custom)
    out["custom_mask"] = str(custom)
    only = await extras(out["baseline"], enable_nudenet=False, mask=str(custom), filter_type="Fill color",
                        fill_color="#ff00ff", mask_blend_radius=0)
    out["extras_custom"] = only[0]
    assert load(only[0]).getpixel((2, 2)) == MAGENTA
    assert load(only[0]).getpixel((width - 3, height - 3)) == load(out["baseline"]).getpixel((width - 3, height - 3))
    union = await extras(out["baseline"], mask=str(custom), filter_type="Fill color", fill_color="#ff00ff",
                         mask_blend_radius=0, **faces)
    out["extras_union"] = union[0]
    union_image = load(union[0])
    assert union_image.getpixel((2, 2)) == MAGENTA
    assert ImageChops.difference(union_image, load(only[0])).getbbox(), "detected regions missing from the union"
    whole = await extras(out["baseline"], mask_shape="Entire image (always)", filter_type="Pixelate")
    out["extras_entire"] = whole[0]
    assert rgb(whole[0]) != rgb(out["baseline"])
    nothing = await extras(out["baseline"])
    out["extras_nothing"] = nothing[0]
    assert rgb(nothing[0]) == rgb(out["baseline"]) and "nothing detected" in parameters(nothing[0])
    receipt["outputs"] = out
    receipt["success"] = True


async def run(args, receipt):
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    (args.output / "images").mkdir(parents=True, exist_ok=True)
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs={"checkpoints": [str(args.models.resolve())]}, last_model=args.checkpoint,
                    vram_mode="model_offload", dtype="fp16", preview_every=2)  # live preview censor path runs
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    command = [str(args.launcher.resolve()), "--mcp", "--home", str(app_home), "--drain-seconds", "5"] if args.launcher else \
        [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
         "sdfx.mcp.McpMain", "--home", str(app_home), "--drain-seconds", "5"]
    receipt.update(command=command, jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
    model = args.source.resolve() / "models" / "nudenet" / "best.onnx"
    receipt["nudenet_sha256"] = hashlib.sha256(model.read_bytes()).hexdigest()
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=1800) as client:
        await exercise(client, args, receipt)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--models", type=Path, required=True)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--launcher", type=Path)
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=768)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--seed", type=int, default=777)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--prompt", default="1girl, solo, close-up portrait, face, looking at viewer, smile, "
                                       "winter coat, scarf, fully clothed, city street, detailed illustration")
    p.add_argument("--face-thresholds", type=float, nargs="+", default=[0.25, 0.15, 0.1, 0.05])
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "face_threshold": receipt.get("face_threshold"),
                      "outputs": receipt.get("outputs")}, indent=2))


if __name__ == "__main__":
    main()
