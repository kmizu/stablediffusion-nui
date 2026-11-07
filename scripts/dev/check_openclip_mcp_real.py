"""Official MCP SDK over stdio, actual Python worker and already-generated SD images."""
import argparse
import asyncio
import json
import os
import shutil
from pathlib import Path
from mcp import Client, StdioServerParameters

async def main(args):
    out = Path(args.output).resolve(); out.mkdir(parents=True, exist_ok=True)
    home = out / "home"; (home / "config").mkdir(parents=True, exist_ok=True); (home / "python").mkdir(exist_ok=True)
    images = out / "images"; images.mkdir(exist_ok=True)
    inputs = []
    for i, image in enumerate(args.images):
        dst = images / f"source-{i}.png"; shutil.copyfile(image, dst); inputs.append(dst)
    settings = {"python_exe": str(Path(args.python).resolve()), "output_dir": str(images), "hf_offline": True}
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=os.pathsep.join([str(Path(args.source).resolve() / "python"), str(Path(args.deps).resolve())]))
    command = [args.java, "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", args.jar,
               "sdfx.mcp.McpMain", "--home", str(home), "--drain-seconds", "5"]
    name = "convnext_base_w/laion2b_s13b_b82k"
    receipt = {"command": command, "input_images": [str(x) for x in inputs], "results": []}
    async with Client(StdioServerParameters(command=command[0], args=command[1:], cwd=args.source, env=env), read_timeout_seconds=3600) as client:
        async def call(params):
            raw = (await client.call_tool("clip_interrogate", params, read_timeout_seconds=3600)).model_dump(mode="json")
            assert not raw.get("isError", raw.get("is_error", False)), raw
            return raw.get("structuredContent", raw.get("structured_content"))
        models = await call({"operation": "models"})
        assert name in models["clip_models"]
        assert models["openclip_status"]["state"] == "available"
        assert models["openclip_status"]["card_snapshot_date"] == "2026-11-03"
        assert models["openclip_model_info"][name]["card_license"] == "mit"
        invalid = (await client.call_tool("clip_interrogate", {"operation": "prompt", "image": str(inputs[0]),
                         "clip_model": "definitely-not-a-model/invalid", "mode": "fast"}, read_timeout_seconds=3600)).model_dump(mode="json")
        assert invalid.get("isError", invalid.get("is_error", False)), invalid
        receipt["invalid_model_rejected"] = True
        for image in inputs:
            response = await call({"operation": "prompt", "image": str(image), "mode": "fast", "caption_model": "blip-base", "clip_model": name, "keep_loaded": True})
            assert response["prompt"].startswith(response["caption"]) and response["clip_model"] == name
            receipt["results"].append(response)
        await call({"operation": "unload"})
    (out / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    print("OPENCLIP_MCP_OK", flush=True)

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for name in ("source", "deps", "output", "java", "jar", "python"):
        p.add_argument("--" + name, required=True)
    p.add_argument("images", nargs=2)
    asyncio.run(main(p.parse_args()))
