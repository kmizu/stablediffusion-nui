"""Real-model ControlNet Preprocessor (Extras) acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

A checkpoint generates a source image; the `controlnet_preprocess` tool then runs canny (pixel perfect, an explicit
resolution, thresholds), invert, the PIL modules, optionally the model based depth modules, and a folder.  The canny
result is fed back as a ControlNet control image and must reproduce the generation made with the unit's own canny
preprocessor on the source (SD1.5 only: only an SD1.5 ControlNet is available locally).  Inspect the images afterwards
(receipt.json lists them); assertions alone are not acceptance.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import shutil
import time
from pathlib import Path

from PIL import Image, ImageChops, ImageStat


def pixels(path):
    with Image.open(path) as image:
        return image.convert("RGB").tobytes()


def load(path):
    with Image.open(path) as image:
        return image.convert("RGB")


def same(a, b):
    return a.size == b.size and ImageChops.difference(a, b).getbbox() is None


def mean(image):
    return ImageStat.Stat(image).mean[0]


def lit(image):
    """Number of non-zero pixels of the first channel."""
    return sum(image.getchannel(0).histogram()[1:])


def gradient(image):
    return mean(ImageChops.difference(image, ImageChops.offset(image, 1, 0)).convert("L"))


def text(path):
    with Image.open(path) as image:
        return dict(image.text)


async def exercise(client, args, receipt):
    receipt["calls"] = []

    async def call(name, values, expect_error=False):
        started = time.time()
        result = await client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        failed = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": {k: (v if len(str(v)) < 200 else "...") for k, v in values.items()},
                                 "seconds": round(time.time() - started, 1), "error": failed,
                                 "result": data or encoded.get("content")})
        assert bool(failed) == expect_error, encoded
        return data if not expect_error else json.dumps(encoded)

    def first(data):
        assert data.get("images"), data
        return data["images"][0]["path"]

    tools = await client.list_tools()
    assert "controlnet_preprocess" in [t.name for t in tools.tools], "controlnet_preprocess missing"
    listing = await call("controlnet_preprocess", {"operation": "list"})
    names = [m["name"] for m in listing["preprocessors"]]
    assert "none" not in names and "inpaint" not in names and "canny" in names and "invert" in names, names
    canny_info = next(m for m in listing["preprocessors"] if m["name"] == "canny")
    assert canny_info["resolution"] and [a["key"] for a in canny_info["args"]] == ["low_threshold", "high_threshold"]
    receipt["modules"] = names

    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text, watermark", width=args.width, height=args.height,
                  steps=args.steps, seed=args.seed, sampler_name="Euler", return_images=False, model_keyword=False)
    source = first(await call("txt2img", params))
    out = receipt["outputs"] = {"source": source}
    src = load(source)
    width, height = src.size
    assert (width, height) == (args.width, args.height)
    out_dir = Path(args.output).resolve() / "images" / "extras-out"
    shutil.rmtree(out_dir, ignore_errors=True)
    out_dir.mkdir(parents=True)

    async def run(preprocessor, **extra):
        data = await call("controlnet_preprocess", {"preprocessor": preprocessor, "image": source,
                                                    "output_dir": str(out_dir), **extra})
        assert len(data["results"]) == 1 and data["preprocessor"] == preprocessor
        return data, data["results"][0]["output"]

    # canny: pixel perfect on a non-square image resolves to the short side, keeps the full size
    data, pp = await run("canny", pixel_perfect=True)
    out["canny_pixel_perfect"] = pp
    edges = load(pp)
    assert edges.size == src.size and {v for _, v in edges.convert("L").getcolors()} <= {0, 255}, edges.size
    assert 0.005 < lit(edges) / (width * height) < 0.5, lit(edges) / (width * height)
    meta = text(pp)
    assert meta["parameters"].startswith('ControlNet Preprocessor: "{') and f"'processor_res': {min(width, height)}" in meta["parameters"], meta
    assert f"Size: {width}x{height}" in meta["parameters"]
    assert "Steps:" in meta["Source parameters"] and args.prompt[:20] in meta["Source parameters"], meta
    # explicit resolution scales the short side
    data, p256 = await run("canny", processor_res=256)
    out["canny_256"] = p256
    short = min(width, height)
    scaled = load(p256).size
    assert min(scaled) == 256 and abs(scaled[1] / scaled[0] - height / width) < 0.02, scaled
    assert data["processor_res"] == 256
    # default resolution is 512
    data, p512 = await run("canny")
    assert min(load(p512).size) == 512
    # thresholds: lower thresholds find at least as many edges
    _, lo = await run("canny", pixel_perfect=True, args={"low_threshold": 20, "high_threshold": 60})
    _, hi = await run("canny", pixel_perfect=True, args={"low_threshold": 220, "high_threshold": 250})
    n_lo, n_hi = lit(load(lo)), lit(load(hi))
    assert n_lo > lit(edges) > n_hi, (n_lo, lit(edges), n_hi)
    out.update(canny_low=lo, canny_high=hi)
    receipt["edge_pixels"] = {"low": n_lo, "default": lit(edges), "high": n_hi}
    assert "'threshold_a': 20" in text(lo)["parameters"] and "'threshold_b': 60" in text(lo)["parameters"]

    # invert is exactly 255 - source; resolution settings are ignored with a note
    data, inv = await run("invert", processor_res=256)
    assert same(load(inv), ImageChops.invert(src))
    assert data["notes"] and "no resolution" in data["notes"][0], data
    out["invert"] = inv
    # PIL modules change the picture in the documented direction
    _, lum = await run("recolor_luminance", args={"gamma_correction": 1.0})
    lum_a = load(lum)
    r, g, b = lum_a.split()
    assert ImageChops.difference(r, g).getbbox() is None and ImageChops.difference(g, b).getbbox() is None, "grayscale expected"
    y = src.convert("L", (0.2126, 0.7152, 0.0722, 0))
    assert ImageChops.difference(r, y).getextrema()[1] <= 2, ImageChops.difference(r, y).getextrema()
    _, dark = await run("recolor_luminance", args={"gamma_correction": 2.0})
    assert mean(load(dark)) < mean(lum_a) - 5
    _, tile = await run("tile_resample", args={"down_sampling_rate": 4.0})
    assert load(tile).size == src.size and not same(load(tile), src)
    _, blur = await run("blur_gaussian", args={"sigma": 12})
    assert gradient(load(blur)) < gradient(src) * 0.6, (gradient(load(blur)), gradient(src))
    out.update(recolor=lum, recolor_dark=dark, tile=tile, blur=blur)
    if args.models_depth:
        _, depth = await run("depth")
        d = load(depth)
        assert d.size == src.size and ImageChops.difference(d.getchannel(0), d.getchannel(1)).getbbox() is None, d.size
        assert ImageStat.Stat(d).stddev[0] > 10, ImageStat.Stat(d).stddev
        out["depth"] = depth
        _, mari = await run("depth_marigold", args={"steps": 2})
        assert load(mari).size == src.size and ImageStat.Stat(load(mari)).stddev[0] > 10
        out["depth_marigold"] = mari

    # folder: three images + a text file + a corrupt "image"; output never overwrites, results sorted by name
    folder = Path(args.output).resolve() / "images" / "extras-in"
    shutil.rmtree(folder, ignore_errors=True)
    folder.mkdir(parents=True)
    second = first(await call("txt2img", dict(params, prompt="a bowl of ramen on a wooden table", seed=args.seed + 1)))
    shutil.copy(source, folder / "a.png")
    shutil.copy(second, folder / "b.png")
    Image.open(second).convert("RGB").save(folder / "c.jpg", quality=95)
    (folder / "notes.txt").write_text("keep me", encoding="utf-8")
    batch_out = Path(args.output).resolve() / "images" / "extras-batch"
    shutil.rmtree(batch_out, ignore_errors=True)
    batch_out.mkdir(parents=True)
    batch = await call("controlnet_preprocess", {"preprocessor": "canny", "folder": str(folder), "output_dir": str(batch_out),
                                                 "pixel_perfect": True})
    assert [Path(r["input"]).name for r in batch["results"]] == ["a.png", "b.png", "c.jpg"], batch
    assert sorted(p.name for p in batch_out.iterdir()) == ["a-canny.png", "b-canny.png", "c-canny.png"]
    assert pixels(batch_out / "a-canny.png") == pixels(pp), "batch equals the single image run"
    again = await call("controlnet_preprocess", {"preprocessor": "canny", "folder": str(folder), "output_dir": str(batch_out),
                                                 "pixel_perfect": True})
    assert sorted(p.name for p in batch_out.iterdir()) == ["a-canny-1.png", "a-canny.png", "b-canny-1.png", "b-canny.png",
                                                           "c-canny-1.png", "c-canny.png"], "second run must not overwrite"
    assert (folder / "notes.txt").read_text(encoding="utf-8") == "keep me"
    out["batch"] = [str(p) for p in sorted(batch_out.glob("*-canny.png"))]
    (folder / "d.png").write_bytes(b"not an image")
    bad = await call("controlnet_preprocess", {"preprocessor": "canny", "folder": str(folder), "output_dir": str(batch_out)},
                     expect_error=True)
    assert "d.png" in bad, bad

    # bad input is refused without disturbing the engine
    assert "unknown preprocessor" in await call("controlnet_preprocess", {"preprocessor": "nope", "image": source}, expect_error=True)
    assert "between" in await call("controlnet_preprocess", {"preprocessor": "canny", "image": source,
                                                              "args": {"low_threshold": 0}}, expect_error=True)
    assert "not used" in await call("controlnet_preprocess", {"preprocessor": "canny", "image": source,
                                                               "args": {"sigma": 3}}, expect_error=True)
    assert "exactly one" in await call("controlnet_preprocess", {"preprocessor": "canny", "image": source,
                                                                  "folder": str(folder)}, expect_error=True)
    assert "not both" in await call("controlnet_preprocess", {"preprocessor": "canny", "image": source, "processor_res": 256,
                                                               "pixel_perfect": True}, expect_error=True)
    await call("controlnet_preprocess", {"preprocessor": "canny", "image": source, "processor_res": 32}, expect_error=True)
    await call("controlnet_preprocess", {"preprocessor": "canny", "image": str(Path(args.output).resolve() / "nowhere.png")},
               expect_error=True)
    await call("controlnet_preprocess", {"preprocessor": "canny", "image": str(Path(__file__).resolve())}, expect_error=True)

    # base64 input gives the same result as the path
    import base64
    encoded = "data:image/png;base64," + base64.b64encode(Path(source).read_bytes()).decode()
    data = await call("controlnet_preprocess", {"preprocessor": "canny", "image": encoded, "pixel_perfect": True,
                                                "output_dir": str(out_dir)})
    assert same(load(data["results"][0]["output"]), edges)

    # integration: the preprocessed canny as a ControlNet image == the unit running its own canny on the source
    if args.controlnet:
        base = dict(params, seed=args.seed + 7)
        plain = first(await call("txt2img", base))
        own = first(await call("txt2img", dict(base, controlnet=[{
            "model": args.controlnet, "image": source, "preprocessor": "canny", "pixel_perfect": True}])))
        fed = first(await call("txt2img", dict(base, controlnet=[{
            "model": args.controlnet, "image": pp, "preprocessor": "none", "pixel_perfect": False}])))
        out.update(controlnet_baseline=plain, controlnet_own_canny=own, controlnet_fed_canny=fed)
        assert pixels(own) != pixels(plain), "ControlNet had no effect"
        diff = ImageChops.difference(load(own), load(fed))
        receipt["controlnet_fed_vs_own"] = {"max_abs_diff": max(diff.getextrema(), key=lambda e: e[1])[1],
                                            "mean_abs_diff": sum(ImageStat.Stat(diff).mean) / 3}
        assert receipt["controlnet_fed_vs_own"]["mean_abs_diff"] < 1.0, receipt["controlnet_fed_vs_own"]
        low_vs_plain = sum(ImageStat.Stat(ImageChops.difference(load(plain), load(own))).mean) / 3
        receipt["controlnet_own_vs_plain_mean_abs_diff"] = low_vs_plain
        assert low_vs_plain > 5

    # the engine is undisturbed: regenerating the source reproduces it exactly
    after = first(await call("txt2img", params))
    assert pixels(after) == pixels(source), "generation did not recover bit-exactly"
    out["after"] = after
    receipt["success"] = True


async def run(args, receipt):
    from mcp import Client, StdioServerParameters
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    (args.output / "images").mkdir(parents=True, exist_ok=True)
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
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--seed", type=int, default=4242)
    p.add_argument("--controlnets", type=Path, help="folder holding a diffusers ControlNet directory (SD1.5 canny)")
    p.add_argument("--controlnet", default="", help="ControlNet catalog name; enables the generation equivalence check")
    p.add_argument("--models-depth", action="store_true", help="also run depth and depth_marigold (downloads weights)")
    p.add_argument("--prompt", default="a lighthouse on a rocky cliff at sunset, dramatic clouds, detailed illustration")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "edge_pixels": receipt.get("edge_pixels"),
                      "controlnet_fed_vs_own": receipt.get("controlnet_fed_vs_own"), "outputs": receipt.get("outputs")}, indent=2))


if __name__ == "__main__":
    main()
