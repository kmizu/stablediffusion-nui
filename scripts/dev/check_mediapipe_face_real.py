"""Official MCP client check for the optional MediaPipe face detector; run once per model."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image, ImageChops


async def exercise(args, receipt):
    home = args.output / "home"
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    settings = dict(python_exe=str(args.python), output_dir=str(args.output / "images"),
                    model_dirs={"checkpoints": [str(args.models)]}, last_model=args.checkpoint,
                    vram_mode=args.vram_mode, dtype="fp16", preview_every=0)
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=os.pathsep.join([str(args.source / "python"), str(args.deps)]),
               SDFX_MEDIAPIPE_DIR=str(args.model_dir))
    command = [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar),
               "sdfx.mcp.McpMain", "--home", str(home), "--drain-seconds", "5"]
    receipt["jar_sha256"] = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source)), read_timeout_seconds=3600) as client:
        async def call(name, values, allow_error=False):
            result = await client.call_tool(name, values, read_timeout_seconds=3600)
            data = result.model_dump(mode="json")
            assert allow_error or not data.get("isError", data.get("is_error", False)), data
            return data.get("structuredContent", data.get("structured_content")) or {}

        await call("set_model", {"name": args.checkpoint})
        params = dict(prompt="close-up portrait of a smiling young woman, looking at viewer, detailed face",
                      negative_prompt="blurry, text", width=args.width, height=args.height, steps=20,
                      cfg_scale=5.0, seed=2468, sampler_name="Euler", return_images=False, model_keyword=False)
        baseline = (await call("txt2img", params))["images"][0]["path"]
        hook = {"batch_face_swap": {"enabled": True, "detector": "mediapipe_face", "confidence": 0.3,
                                    "prompt": "[PROMPT], closed eyes", "denoising_strength": 0.65}}
        changed = (await call("txt2img", dict(params, always_on=hook)))["images"][0]["path"]
        diff = ImageChops.difference(Image.open(baseline).convert("RGB"), Image.open(changed).convert("RGB"))
        box = diff.getbbox()
        receipt.update(baseline=baseline, changed=changed, changed_box=box)
        assert box is not None, "MediaPipe found no face or face pass produced no change"
        meta = await call("inspect_generation_metadata", {"image": changed})
        restored = meta.get("always_on", {}).get("batch_face_swap", {})
        assert restored.get("detector") == "mediapipe_face", restored
        receipt.update(restored=restored)
        if args.extended:
            replay_args = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
            replay_args.setdefault("model_keyword", False)
            replay = (await call("txt2img", replay_args))["images"][0]["path"]
            with Image.open(changed) as a, Image.open(replay) as b:
                assert a.convert("RGB").tobytes() == b.convert("RGB").tobytes(), "PNG replay changed pixels"
            receipt["replay"] = replay
            job = await call("txt2img_async", dict(params, steps=80, always_on=hook))
            for _ in range(600):
                state = await call("get_job", {"job_id": job["job_id"], "wait_seconds": 1, "return_images": False})
                if float(state.get("progress", 0) or 0) > 0.2 or state.get("status") in {"done", "failed", "cancelled"}:
                    break
            assert state.get("status") not in {"done", "failed", "cancelled"}, state
            await call("cancel_job", {"job_id": job["job_id"]})
            for _ in range(120):
                state = await call("get_job", {"job_id": job["job_id"], "wait_seconds": 2, "return_images": False}, allow_error=True)
                if state.get("status") in {"done", "failed", "cancelled"}:
                    break
            assert state.get("status") == "cancelled", state
            recovered = (await call("txt2img", params))["images"][0]["path"]
            with Image.open(baseline) as a, Image.open(recovered) as b:
                assert a.convert("RGB").tobytes() == b.convert("RGB").tobytes(), "output changed after cancel"
            receipt.update(cancel_state=state.get("status"), recovered=recovered)
        receipt["success"] = True


def main():
    parser = argparse.ArgumentParser()
    for key in ("source", "output", "models", "jar", "java", "python", "deps", "model-dir"):
        parser.add_argument("--" + key, type=lambda p: Path(p).resolve(), required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--width", type=int, default=512)
    parser.add_argument("--height", type=int, default=512)
    parser.add_argument("--vram-mode", default="model_offload")
    parser.add_argument("--extended", action="store_true", help="also verify exact PNG replay and cancellation recovery")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    receipt = {"args": {key: str(value) for key, value in vars(args).items()}}
    try:
        asyncio.run(exercise(args, receipt))
    finally:
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps(receipt, indent=2, default=str))


if __name__ == "__main__":
    main()
