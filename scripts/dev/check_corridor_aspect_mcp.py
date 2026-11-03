"""Official MCP SDK: real image, non-square initial crop, and aspect-preserving corridor."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image


async def run(args):
    out = Path(args.output).resolve()
    home = out / "home"
    images = out / "images"
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    images.mkdir(parents=True, exist_ok=True)
    settings = {"python_exe": args.python, "output_dir": str(images),
                "model_dirs": {"checkpoints": [args.models]}, "last_model": args.checkpoint,
                "vram_mode": "sequential_offload" if args.sdxl else "model_offload", "dtype": "fp16"}
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    command = [args.java, "-cp", args.jar, "sdfx.mcp.McpMain", "--home", str(home)]
    env = dict(os.environ, PYTHONPATH=str(Path(args.source).resolve() / "python"))
    receipt = {"checkpoint": args.checkpoint, "jar_sha256": hashlib.sha256(Path(args.jar).read_bytes()).hexdigest(),
               "transport": "stdio", "calls": []}
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env), read_timeout_seconds=900) as client:
        async def call(name, values):
            result = (await client.call_tool(name, values, read_timeout_seconds=900)).model_dump(mode="json")
            assert not result.get("isError", result.get("is_error", False)), result
            data = result.get("structuredContent", result.get("structured_content")) or {}
            receipt["calls"].append({"tool": name, "arguments": values, "result": data})
            return data
        await call("set_model", {"name": args.checkpoint})
        width, height = (832, 1216) if args.sdxl else (512, 512)
        common = {"prompt": "a stone hallway, soft light", "negative_prompt": "text, blurry", "seed": 4242,
                  "width": width, "height": height, "steps": 12, "sampler_name": "Euler", "model_keyword": False}
        generated = await call("txt2img", dict(common, return_images=False))
        source = Path(generated["images"][0]["path"])
        crop = images / "wide-initial.png"
        with Image.open(source) as image:
            image.convert("RGB").resize((160, 80)).save(crop)
        response = await call("run_script", {"script_id": "corridor_crawler", "values": {"steps": 2,
            "placement": "Preserve aspect", "make_grid": False, "animation": True,
            "frames_per_step": 2, "animation_size": 128}, "params": dict(common, init_image=str(crop),
            denoising_strength=0.9), "return_images": False})
        records = response["images"]
        assert len(records) == 2, records
        first = records[0]["infotext"]
        assert "Corridor Crawler placement: Preserve aspect" in first, first
        assert "160,80" in first, first
        inspected = await call("inspect_generation_metadata", {"image": records[0]["path"]})
        candidate = inspected["scripts"]["corridor_crawler"]
        assert candidate["steps"] == 2 and candidate["placement"] == "Preserve aspect", candidate
        assert candidate["prefab_image"] == "" and any("initial image" in w for w in inspected["warnings"]), inspected
        assert "init_image" not in inspected["generation_args"]
        repeated = await call("run_script", {"script_id": "corridor_crawler", "values": candidate,
            "params": dict(common, init_image=str(crop), denoising_strength=0.9), "return_images": False})
        assert len(repeated["images"]) == 2, repeated
        for original, replayed in zip(records, repeated["images"]):
            with Image.open(original["path"]) as a, Image.open(replayed["path"]) as b:
                assert a.convert("RGB").tobytes() == b.convert("RGB").tobytes(), "restored settings changed image pixels"
        receipt["replay_images"] = [item["path"] for item in repeated["images"]]
        target = Path(records[-1]["path"])
        gif = target.with_name(target.stem + "-corridor.gif")
        with Image.open(gif) as animation:
            assert animation.n_frames == 5, animation.n_frames
            receipt["gif_frames"] = animation.n_frames
        receipt.update(source=str(source), initial=str(crop), outputs=[item["path"] for item in records], gif=str(gif))
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print("CORRIDOR_REPLAY_REAL_MCP_OK", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("java", "jar", "source", "python", "models", "checkpoint", "output"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--sdxl", action="store_true")
    asyncio.run(run(parser.parse_args()))
