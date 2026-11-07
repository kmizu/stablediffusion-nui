"""Exercise Workflow phase settings through the official MCP SDK and real engine."""
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
    src.write_bytes(Path(a.input).read_bytes())
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
                  "sampler_name": "Euler", "denoising_strength": 0.05, "model_keyword": False}
        values = {"phase": "Phase 1", "orientation": "Vertical", "phase1_width": a.side,
                  "phase1_height": a.side, "phase1_count": 2, "phase1_denoise": 0.5}
        result = await call("run_script", {"script_id": "img2img_workflow", "params": params, "values": values, "return_images": False})
        assert len(result["images"]) == 2, result
        for item in result["images"]:
            with Image.open(item["path"]) as im:
                assert im.size == (a.side, a.side)
                metadata = im.info.get("parameters", "")
                assert "Img2Img Workflow" in metadata and "Denoising strength: 0.5" in metadata, metadata
        assert hashlib.sha256(src.read_bytes()).hexdigest() == original
        if a.cancel:
            long_params = dict(params, steps=80)
            long_values = dict(values, phase1_count=4)
            submitted = await call("run_script", {"script_id": "img2img_workflow", "params": long_params,
                                   "values": long_values, "async": True, "return_images": False})
            job_id = submitted["job_id"]
            for _ in range(120):
                status = await call("get_job", {"job_id": job_id})
                if status["status"] == "running" and status["progress"] > 0:
                    break
                assert status["status"] not in ("finished", "failed", "cancelled"), status
                await asyncio.sleep(0.5)
            else:
                raise AssertionError("Timed out waiting for workflow generation progress")
            cancellation = await call("cancel_job", {"job_id": job_id})
            assert cancellation["cancelled"], cancellation
            for _ in range(240):
                response = (await client.call_tool("get_job", {"job_id": job_id})).model_dump(mode="json")
                status = response.get("structuredContent", response.get("structured_content"))
                if status["status"] in ("cancelled", "failed", "finished"):
                    break
                await asyncio.sleep(0.5)
            assert status["status"] == "cancelled", status
            recovered = await call("run_script", {"script_id": "img2img_workflow", "params": params,
                                   "values": dict(values, phase1_count=1), "return_images": False})
            assert len(recovered["images"]) == 1, recovered
            receipt.update(cancelled_job=status, recovery_image=recovered["images"][0]["path"])
        receipt.update(success=True, images=[x["path"] for x in result["images"]], source_sha256=original)
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print("IMG2IMG_WORKFLOW_REAL_OK", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    for name in ("source", "output", "java", "jar", "python", "models", "model", "input"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--side", type=int, default=512)
    p.add_argument("--cancel", action="store_true")
    asyncio.run(run(p.parse_args()))
