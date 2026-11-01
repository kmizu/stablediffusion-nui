"""Real-model Sonar acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv)."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image

# Each must change the image relative to the plain sampler of the same name.
CASES = {
    "euler_a_m90": dict(sampler="Euler a", momentum=0.9),
    "euler_a_neg": dict(sampler="Euler a", momentum=0.85, momentum_hist=0.9, momentum_sign="neg"),
    "euler_m90": dict(sampler="Euler", momentum=0.9),
    "euler_rand_new": dict(sampler="Euler", momentum=0.9, momentum_sign="rand", momentum_hist_init="rand_new"),
    "euler_rand_init": dict(sampler="Euler", momentum=0.9, momentum_hist_init="rand_init"),
}
# Must reproduce the plain sampler exactly (momentum 1.0 is the unmodified update), also over a different request sampler.
IDENTITY = {
    "euler_a_m1": ("Euler a", dict(sampler="Euler a", momentum=1.0), "Euler a"),
    "euler_m1": ("Euler", dict(sampler="Euler", momentum=1.0), "Euler"),
    "euler_a_replaces_dpm": ("DPM++ 2M", dict(sampler="Euler a", momentum=1.0), "Euler a"),
    "euler_replaces_dpm": ("DPM++ 2M", dict(sampler="Euler", momentum=1.0), "Euler"),
}
REPLAY = dict(sampler="Euler", momentum=0.88, momentum_hist=0.6, momentum_sign="rand", momentum_hist_init="rand_new",
              upscale=True, upscaler="Lanczos", upscale_ratio=1.25)


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def diff(a, b):
    """Mean absolute pixel difference of two images of the same size."""
    from PIL import ImageChops, ImageStat
    delta = ImageChops.difference(Image.open(a).convert("RGB"), Image.open(b).convert("RGB"))
    return float(sum(ImageStat.Stat(delta).mean) / 3)


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

    def sonar(**values):
        return {"sonar": dict({"enabled": True}, **values)}

    assert "sonar" in json.dumps(await call("list_always_on", {"tab": "txt2img"}))
    await call("set_model", {"name": args.checkpoint})
    params = dict(prompt=args.prompt, negative_prompt="lowres, blurry, text", width=args.width, height=args.height,
                  steps=args.steps, cfg_scale=args.cfg, seed=2468, return_images=False, model_keyword=False)
    out, dist = {}, {}
    for sampler in ("Euler a", "Euler", "DPM++ 2M"):
        out["base " + sampler] = first(await call("txt2img", dict(params, sampler_name=sampler)))
    for name, values in CASES.items():
        plain = out["base " + ("Euler a" if values["sampler"] == "Euler a" else "Euler")]
        out[name] = first(await call("txt2img", dict(params, sampler_name="Euler", always_on=sonar(**values))))
        assert rgb(out[name]) != rgb(plain), f"{name} did not change the image"
        dist[name] = diff(out[name], plain)
    # the Naive sampler differs from Euler only by its 1e-5 noise (smaller than the fp16 resolution of the latent
    # for most of the run): a small image change (mean absolute difference, recorded in the receipt)
    out["naive"] = first(await call("txt2img", dict(params, sampler_name="Euler",
                                                    always_on=sonar(sampler="Naive", momentum=1.0))))
    dist["naive"] = diff(out["naive"], out["base Euler"])
    assert dist["naive"] < 5.0, dist
    for name, (request_sampler, values, plain_name) in IDENTITY.items():
        out[name] = first(await call("txt2img", dict(params, sampler_name=request_sampler, always_on=sonar(**values))))
        assert rgb(out[name]) == rgb(out["base " + plain_name]), f"{name} changed the image"
    # reference guidance (Naive): pulled towards another sampler's picture; both methods
    ref = out["base DPM++ 2M"]
    for name, values in {"ref_linear": dict(ref_meth="linear", ref_hgf=1.0), "ref_euler": dict(ref_meth="euler", ref_hgf=1.0)}.items():
        out[name] = first(await call("txt2img", dict(params, sampler_name="Euler", always_on=sonar(
            sampler="Naive", momentum=0.95, ref_img=ref, ref_min_step=0.0, ref_max_step=1.0, **values))))
        dist[name] = diff(out[name], out["naive"])
        assert dist[name] > 1.0, f"{name} did not pull the image towards the reference ({dist[name]})"
    # upscale: ratio, then a size that needs the cover-and-crop
    up = first(await call("txt2img", dict(params, sampler_name="Euler", always_on=sonar(
        sampler="Euler", momentum=1.0, upscale=True, upscaler="Lanczos", upscale_ratio=1.5))))
    out["upscale_ratio"] = up
    assert rgb(up)[0] == (round(args.width * 1.5), round(args.height * 1.5)), rgb(up)[0]
    crop = first(await call("txt2img", dict(params, sampler_name="Euler", always_on=sonar(
        sampler="Euler", momentum=1.0, upscale=True, upscaler="Lanczos", upscale_width=args.width * 2,
        upscale_height=args.width * 2))))
    out["upscale_size"] = crop
    assert rgb(crop)[0] == (args.width * 2, args.width * 2), rgb(crop)[0]
    # PNG replay of the whole setting set, including the random choices and the upscale
    out["combo"] = first(await call("txt2img", dict(params, sampler_name="Euler", always_on=sonar(**REPLAY))))
    meta = await call("inspect_generation_metadata", {"image": out["combo"]})
    assert meta["always_on"].get("sonar", {}).get("enabled") is True, meta["always_on"]
    assert not any(k.startswith("Sonar ") for k in meta["unapplied_fields"]), meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = first(await call("txt2img", replay))
    assert rgb(out["replay"]) == rgb(out["combo"]), "PNG replay did not reproduce the image"
    # the source's own PNG text (three keys) restores into the same defaults
    # hires (latent upscale): sonar acts in both passes
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    out["hires_baseline"] = first(await call("txt2img", dict(params, sampler_name="Euler a", hires=hires)))
    out["hires_identity"] = first(await call("txt2img", dict(params, sampler_name="DPM++ 2M", hires=hires,
                                                             always_on=sonar(sampler="Euler a", momentum=1.0))))
    assert rgb(out["hires_identity"]) == rgb(out["hires_baseline"]), "hires identity changed the image"
    out["hires"] = first(await call("txt2img", dict(params, sampler_name="Euler a", hires=hires,
                                                    always_on=sonar(sampler="Euler a", momentum=0.9))))
    assert rgb(out["hires"]) != rgb(out["hires_baseline"]), "hires did not change"
    # img2img from the plain Euler picture
    i2i = dict(params, init_image=out["base Euler"], denoising_strength=0.6, sampler_name="Euler")
    i2i.pop("width"), i2i.pop("height")
    out["i2i_baseline"] = first(await call("img2img", i2i))
    out["i2i_identity"] = first(await call("img2img", dict(i2i, always_on=sonar(sampler="Euler", momentum=1.0))))
    assert rgb(out["i2i_identity"]) == rgb(out["i2i_baseline"]), "img2img identity changed the image"
    out["i2i"] = first(await call("img2img", dict(i2i, always_on=sonar(sampler="Euler", momentum=0.85))))
    assert rgb(out["i2i"]) != rgb(out["i2i_baseline"]), "img2img did not change"
    # a failed request: missing reference image
    bad = await call("txt2img", dict(params, sampler_name="Euler", always_on=sonar(sampler="Naive", ref_img="C:/no/such.png")),
                     allow_error=True)
    receipt["missing_ref"] = bad
    assert "no such file" in json.dumps(bad), bad  # the MCP path policy rejects it before the engine
    # cancel, then the scheduler is clean again
    queued = await call("txt2img_async", dict(params, steps=80, sampler_name="Euler", always_on=sonar(**REPLAY)))
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
    out["after_cancel"] = first(await call("txt2img", dict(params, sampler_name="Euler")))
    assert rgb(out["after_cancel"]) == rgb(out["base Euler"]), "state was not removed after cancel"
    receipt["mean_abs_pixel_difference"] = dist
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
    p.add_argument("--cfg", type=float, default=6.0)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="1girl, solo, silver hair, blue eyes, school uniform, cherry blossoms, masterpiece, best quality")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "outputs": receipt.get("outputs"),
                      "mean_abs_pixel_difference": receipt.get("mean_abs_pixel_difference")}, indent=2))


if __name__ == "__main__":
    main()
