"""Check Workflow Quick Mask through the official MCP SDK and real engine."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
from PIL import Image
from mcp import Client, StdioServerParameters


async def run(a):
    out = Path(a.output).resolve()
    home, images = out / "home", out / "images"
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    images.mkdir(exist_ok=True)
    src = images / "source.png"
    image = Image.new("RGB", (a.side, a.side), "white")
    from PIL import ImageDraw
    ImageDraw.Draw(image).rectangle((a.side // 4, a.side // 4, a.side * 3 // 4, a.side * 3 // 4), fill="#2255aa")
    image.save(src)
    original = hashlib.sha256(src.read_bytes()).hexdigest()
    settings = {"python_exe": str(Path(a.python).resolve()), "output_dir": str(images), "last_model": a.model,
                "model_dirs": {"checkpoints": [str(Path(a.models).resolve())]}, "vram_mode": "model_offload", "preview_every": 0}
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(Path(a.source).resolve() / "python"))
    command = [a.java, "-Dfile.encoding=UTF-8", "-cp", str(Path(a.jar).resolve()), "sdfx.mcp.McpMain", "--home", str(home)]
    receipt = {"jar_sha256": hashlib.sha256(Path(a.jar).read_bytes()).hexdigest(), "calls": []}
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env), read_timeout_seconds=1800) as client:
        async def call(name, args):
            response = (await client.call_tool(name, args, read_timeout_seconds=1800)).model_dump(mode="json")
            receipt["calls"].append({"tool": name, "args": args, "result": response})
            assert not response.get("isError", response.get("is_error", False)), response
            return response.get("structuredContent", response.get("structured_content"))
        await call("set_model", {"name": a.model})
        listed = await call("list_scripts", {})
        assert any(x["id"] == "img2img_workflow" for x in listed["scripts"]), listed
        params = {"init_image": str(src), "prompt": "anime portrait, looking at viewer", "negative_prompt": "lowres, blurry",
                  "width": a.side, "height": a.side, "steps": 12, "cfg_scale": 5, "seed": 2468,
                  "sampler_name": "Euler", "denoising_strength": 0.65, "model_keyword": False}
        values = {"phase": "None", "quick_mask": "White", "mask_threshold": 1}
        result = await call("run_script", {"script_id": "img2img_workflow", "params": params, "values": values, "return_images": False})
        assert len(result["images"]) == 1, result
        for item in result["images"]:
            with Image.open(item["path"]) as im:
                assert im.size == (a.side, a.side)
                metadata = im.info.get("parameters", "")
                assert "Img2Img Workflow mask" in metadata, metadata
        reference_mask = images / "reference-mask.png"
        mask = Image.new("L", (a.side, a.side), 255)
        ImageDraw.Draw(mask).rectangle((a.side // 4, a.side // 4, a.side * 3 // 4, a.side * 3 // 4), fill=0)
        mask.save(reference_mask)
        direct = await call("img2img", dict(params, mask=str(reference_mask), return_images=False))
        from PIL import ImageChops, ImageStat
        workflow_image = Image.open(result["images"][0]["path"]).convert("RGB")
        reference_image = Image.open(direct["images"][0]["path"]).convert("RGB")
        diff = ImageChops.difference(workflow_image, reference_image)
        assert diff.getbbox() is None, ImageStat.Stat(diff).mean
        assert hashlib.sha256(src.read_bytes()).hexdigest() == original
        receipt.update(success=True, images=[x["path"] for x in result["images"]], direct_image=direct["images"][0]["path"], source_sha256=original, workflow_direct_rgb_equal=True)
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print("WORKFLOW_QUICKMASK_REAL_OK", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for name in ("source", "output", "java", "jar", "python", "models", "model"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--side", type=int, default=512)
    asyncio.run(run(p.parse_args()))
