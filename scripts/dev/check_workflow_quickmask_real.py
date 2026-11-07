"""Check Workflow Quick Mask through the official MCP SDK and real engine."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageStat
from mcp import Client, StdioServerParameters


def custom_mask(image, color, threshold, invert):
    target = tuple(int(color.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))
    mask = Image.new("L", image.size)
    source_pixels = image.convert("RGB").load()
    mask_pixels = mask.load()
    for y in range(image.height):
        for x in range(image.width):
            pixel = source_pixels[x, y]
            delta = max(abs(pixel[channel] - target[channel]) for channel in range(3))
            selected = delta < threshold
            mask_pixels[x, y] = 255 if selected != invert else 0
    return mask


async def run(a):
    out = Path(a.output).resolve()
    home, images = out / "home", out / "images"
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    images.mkdir(exist_ok=True)
    src = images / "source.png"
    if a.input:
        src.write_bytes(Path(a.input).resolve().read_bytes())
        with Image.open(src) as input_image:
            width, height = input_image.size
            mask = custom_mask(input_image, a.color, a.threshold, a.invert)
        values = {"phase": "None", "quick_mask": "Custom", "mask_color": a.color,
                  "mask_threshold": a.threshold, "mask_invert": a.invert, "return_mask": True}
    else:
        width = height = a.side
        image = Image.new("RGB", (width, height), "white")
        ImageDraw.Draw(image).rectangle((width // 4, height // 4, width * 3 // 4, height * 3 // 4), fill="#2255aa")
        image.save(src)
        mask = Image.new("L", (width, height), 255)
        ImageDraw.Draw(mask).rectangle((width // 4, height // 4, width * 3 // 4, height * 3 // 4), fill=0)
        values = {"phase": "None", "quick_mask": "White", "mask_threshold": 1, "return_mask": True}
    original = hashlib.sha256(src.read_bytes()).hexdigest()
    settings = {"python_exe": str(Path(a.python).resolve()), "output_dir": str(images), "last_model": a.model,
                "model_dirs": {"checkpoints": [str(Path(a.models).resolve())]}, "vram_mode": "model_offload", "preview_every": 0}
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(Path(a.source).resolve() / "python"))
    command = [str(a.launcher.resolve()), "--mcp", "--home", str(home), "--drain-seconds", "5"] if a.launcher else \
        [a.java, "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(Path(a.jar).resolve()),
         "sdfx.mcp.McpMain", "--home", str(home), "--drain-seconds", "5"]
    receipt = {"args": {k: str(v) for k, v in vars(a).items()}, "command": command,
               "jar_sha256": hashlib.sha256(Path(a.jar).read_bytes()).hexdigest(), "calls": []}
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(Path(a.source).resolve())), read_timeout_seconds=1800) as client:
        async def call(name, args):
            response = (await client.call_tool(name, args, read_timeout_seconds=1800)).model_dump(mode="json")
            receipt["calls"].append({"tool": name, "args": args, "result": response})
            assert not response.get("isError", response.get("is_error", False)), response
            return response.get("structuredContent", response.get("structured_content"))
        await call("set_model", {"name": a.model})
        listed = await call("list_scripts", {})
        assert any(x["id"] == "img2img_workflow" for x in listed["scripts"]), listed
        params = {"init_image": str(src), "prompt": "anime portrait, looking at viewer", "negative_prompt": "lowres, blurry",
                  "width": width, "height": height, "steps": 12, "cfg_scale": 5, "seed": 2468,
                  "sampler_name": "Euler", "denoising_strength": 0.65, "model_keyword": False}
        result = await call("run_script", {"script_id": "img2img_workflow", "params": params, "values": values, "return_images": False})
        assert len(result["images"]) == 2, result
        with Image.open(result["images"][1]["path"]) as mask_file:
            returned_mask = mask_file.convert("RGB")
            assert ImageChops.difference(returned_mask, mask.convert("RGB")).getbbox() is None
            assert "Img2Img Workflow output" in mask_file.info["parameters"]
        for item in result["images"]:
            with Image.open(item["path"]) as im:
                metadata = im.info.get("parameters", "")
                assert im.info.get("NUI img2img source") == str(src.resolve()), im.info
                assert ".tmp/sdfx-" not in metadata, metadata
        with Image.open(result["images"][0]["path"]) as im:
            assert im.size == (width, height)
            assert "Img2Img Workflow mask" in im.info.get("parameters", "")
        reference_mask = images / "reference-mask.png"
        mask.save(reference_mask)
        direct = await call("img2img", dict(params, mask=str(reference_mask), return_images=False))
        with Image.open(result["images"][0]["path"]) as workflow_file:
            workflow_image = workflow_file.convert("RGB")
        with Image.open(direct["images"][0]["path"]) as direct_file:
            reference_image = direct_file.convert("RGB")
        diff = ImageChops.difference(workflow_image, reference_image)
        assert diff.getbbox() is None, ImageStat.Stat(diff).mean
        assert hashlib.sha256(src.read_bytes()).hexdigest() == original
        receipt.update(success=True, images=[result["images"][0]["path"]], returned_mask=result["images"][1]["path"],
                       direct_image=direct["images"][0]["path"], source_sha256=original,
                       workflow_direct_rgb_equal=True, returned_mask_exact=True)
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print("WORKFLOW_QUICKMASK_REAL_OK", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for name in ("source", "output", "java", "jar", "python", "models", "model"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--launcher", type=Path)
    p.add_argument("--input", type=Path)
    p.add_argument("--color", default="#ffffff")
    p.add_argument("--threshold", type=int, default=1)
    p.add_argument("--invert", action="store_true")
    p.add_argument("--side", type=int, default=512)
    asyncio.run(run(p.parse_args()))
