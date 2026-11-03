"""Exercise folder Img2Img with Batch Face Swap using the official MCP SDK."""
import argparse
import asyncio
import hashlib
import json
import os
import shutil
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image, ImageChops


async def run(a, receipt):
    a.output.mkdir(parents=True, exist_ok=True)
    home = (a.output / "app-home").resolve()
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    output = (a.output / "images").resolve()
    inputs = output / "folder-input"
    inputs.mkdir(parents=True, exist_ok=True)
    for name, source in (("a.png", a.input), ("b.png", a.input_two or a.input)):
        shutil.copyfile(source, inputs / name)
    original = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs.iterdir()}
    if a.input_two:
        assert len(set(original.values())) == 2, "distinct-input check requires different input bytes"
    settings = {"python_exe": str(a.python.resolve()), "output_dir": str(output),
                "model_dirs": {"checkpoints": [str(a.models.resolve())]}, "last_model": a.checkpoint,
                "vram_mode": a.vram_mode, "dtype": "fp16", "preview_every": 0}
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(a.source.resolve() / "python"))
    command = [str(a.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(a.jar.resolve()),
               "sdfx.mcp.McpMain", "--home", str(home), "--drain-seconds", "5"]
    receipt.update(jar_sha256=hashlib.sha256(a.jar.read_bytes()).hexdigest(), input_sha256=original, calls=[])
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(a.source.resolve())), read_timeout_seconds=3600) as client:
        async def call(name, args):
            result = (await client.call_tool(name, args, read_timeout_seconds=3600)).model_dump(mode="json")
            receipt["calls"].append({"tool": name, "args": args, "result": result})
            assert not result.get("isError", result.get("is_error", False)), result
            return result.get("structuredContent", result.get("structured_content")) or {}

        await call("set_model", {"name": a.checkpoint})
        params = {"image_folder": str(inputs), "prompt": "2girls, standing side by side, upper body, park, anime illustration",
                  "negative_prompt": "lowres, blurry, text", "width": a.width, "height": a.height,
                  "steps": 12, "cfg_scale": 5, "seed": 2468, "sampler_name": "Euler",
                  "denoising_strength": 0.05, "return_images": False, "model_keyword": False}
        async def batch(extra):
            job = await call("img2img_folder_async", dict(params, **extra))
            result = await call("get_job", {"job_id": job["job_id"], "wait_seconds": 300, "return_images": False})
            while result.get("status") in {"queued", "running", "waiting_for_engine"}:
                result = await call("get_job", {"job_id": job["job_id"], "wait_seconds": 300, "return_images": False})
            assert result.get("status") == "done" and len(result.get("images", [])) == 2, result
            return result["images"]
        plain = await batch({})
        hooked = await batch({"always_on": {"batch_face_swap": {"enabled": True, "detector": "face_yolov8n.pt",
                                                                  "denoising_strength": 0.6, "prompt": "[PROMPT], smile"}}})
        if a.check_grid:
            grid_job = await call("img2img_folder_async", dict(params, batch_size=2,
                extra={"Grid image numbers": "True"}))
            grid_result = await call("get_job", {"job_id": grid_job["job_id"], "wait_seconds": 300, "return_images": False})
            while grid_result.get("status") in {"queued", "running", "waiting_for_engine"}:
                grid_result = await call("get_job", {"job_id": grid_job["job_id"], "wait_seconds": 300, "return_images": False})
            assert grid_result.get("status") == "done", grid_result
            grids = [i for i in grid_result.get("images", []) if "-grid" in Path(i["path"]).name]
            assert len(grids) == 2, grid_result
            recorded = set()
            for grid in grids:
                with Image.open(grid["path"]) as image:
                    recorded.add(image.info.get("NUI img2img source"))
            assert recorded == {str(inputs / "a.png"), str(inputs / "b.png")}, recorded
            receipt["grids"] = grids
        if a.input_two:
            with Image.open(plain[0]["path"]) as first, Image.open(plain[1]["path"]) as second:
                assert ImageChops.difference(first.convert("RGB"), second.convert("RGB")).getbbox(), "different inputs produced identical images"
        for i, (base, face) in enumerate(zip(plain, hooked)):
            with Image.open(base["path"]) as left, Image.open(face["path"]) as right:
                assert ImageChops.difference(left.convert("RGB"), right.convert("RGB")).getbbox(), "face pass had no visible effect"
                source = right.info.get("parameters", "")
                assert f"{chr(97 + i)}.png" in source and "NUI img2img source" in source, source
        assert original == {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs.iterdir()}
        receipt.update(plain=plain, hooked=hooked, success=True)


def main():
    p = argparse.ArgumentParser()
    for name in ("source", "output", "models", "jar", "java", "python", "input"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--input-two", type=Path)
    p.add_argument("--check-grid", action="store_true")
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--vram-mode", default="model_offload")
    a = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(a).items()}, "success": False}
    try:
        asyncio.run(run(a, receipt))
    finally:
        a.output.mkdir(parents=True, exist_ok=True)
        (a.output / "receipt.json").write_text(json.dumps(receipt, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
