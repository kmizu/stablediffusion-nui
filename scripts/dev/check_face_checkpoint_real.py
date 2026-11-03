"""Exercise a separate face checkpoint and main-model recovery via the official MCP SDK."""
import argparse
import asyncio
import hashlib
import json
import os
import subprocess
import threading
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image, ImageChops


async def run(a, receipt):
    a.output.mkdir(parents=True, exist_ok=True)
    home = (a.output / "app-home").resolve()
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    images = (a.output / "images").resolve()
    images.mkdir(exist_ok=True)
    source = images / "input.png"
    source.write_bytes(a.input.read_bytes())
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    model_dirs = [str(a.models.resolve())]
    if a.failure:
        broken = a.output.resolve() / "invalid-test-models"
        broken.mkdir(exist_ok=True)
        (broken / "face-corrupt.safetensors").write_bytes(b"not a model")
        model_dirs.append(str(broken))
    settings = {"python_exe": str(a.python.resolve()), "output_dir": str(images),
                "model_dirs": {"checkpoints": model_dirs}, "last_model": a.base,
                "vram_mode": a.vram_mode, "dtype": a.dtype, "preview_every": 0}
    if a.lora_dir:
        settings["model_dirs"]["lora"] = [str(a.lora_dir.resolve())]
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(a.source.resolve() / "python"))
    command = [str(a.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(a.jar.resolve()),
               "sdfx.mcp.McpMain", "--home", str(home), "--drain-seconds", "5"]
    receipt.update(jar_sha256=hashlib.sha256(a.jar.read_bytes()).hexdigest(), calls=[])
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(a.source.resolve())), read_timeout_seconds=3600) as client:
        async def call(name, args, allow_error=False):
            result = (await client.call_tool(name, args, read_timeout_seconds=3600)).model_dump(mode="json")
            receipt["calls"].append({"tool": name, "args": args, "result": result})
            if not allow_error:
                assert not result.get("isError", result.get("is_error", False)), result
            return result.get("structuredContent", result.get("structured_content")) or result

        await call("set_model", {"name": a.base})
        params = {"init_image": str(source), "prompt": "anime portrait, looking at viewer", "negative_prompt": a.negative,
                  "width": a.width, "height": a.height, "steps": 12, "cfg_scale": a.cfg, "seed": a.seed,
                  "sampler_name": a.sampler, "denoising_strength": a.strength, "return_images": False, "model_keyword": False}
        if a.schedule:
            params["scheduler"] = a.schedule
        if a.lora:
            params["loras"] = [{"name": a.lora, "weight": 0.8}]
        def face(checkpoint=""):
            result = {"batch_face_swap": {"enabled": True, "detector": a.detector,
                    "checkpoint": checkpoint, "denoising_strength": a.face_strength, "resolution": a.face_resolution, "prompt": a.face_prompt}}
            if a.pag:
                result["incantations"] = {"enabled": True, "pag_active": True, "pag_scale": 3}
            return result
        if a.txt_face_only:
            txt_params = {k: v for k, v in params.items() if k not in ("init_image", "denoising_strength")}
            changed = (await call("txt2img", dict(txt_params, always_on=face(a.face))))["images"][0]["path"]
            with Image.open(changed) as x:
                assert a.face in x.info.get("parameters", "") and "Face checkpoint hash" in x.info.get("parameters", "")
            receipt.update(changed=changed, success=True)
            return
        plain = (await call("img2img", params))["images"][0]["path"]
        if a.base_only:
            receipt.update(plain=plain, success=True)
            return
        same = (await call("img2img", dict(params, always_on=face())))["images"][0]["path"]
        if a.expect_lora_failure:
            failed = await call("img2img", dict(params, always_on=face(a.face)), True)
            assert failed.get("isError", failed.get("is_error", False)) and "could not apply LoRA" in str(failed), failed
            after = (await call("img2img", params))["images"][0]["path"]
            with Image.open(plain) as x, Image.open(after) as y:
                assert ImageChops.difference(x.convert("RGB"), y.convert("RGB")).getbbox() is None, "base did not recover"
            receipt.update(plain=plain, same=same, after=after, success=True, rejected_unapplied_lora=True)
            return
        changed = (await call("img2img", dict(params, always_on=face(a.face))))["images"][0]["path"]
        after = (await call("img2img", params))["images"][0]["path"]
        with Image.open(plain) as x, Image.open(after) as y:
            assert ImageChops.difference(x.convert("RGB"), y.convert("RGB")).getbbox() is None, "base did not recover"
        with Image.open(same) as x, Image.open(changed) as y:
            assert ImageChops.difference(x.convert("RGB"), y.convert("RGB")).getbbox(), "different face checkpoint had no effect"
            metadata = y.info.get("parameters", "")
            assert "Face checkpoint hash" in metadata and a.face in metadata, metadata
        inspected = await call("inspect_generation_metadata", {"image": changed})
        assert inspected["always_on"]["batch_face_swap"]["checkpoint"] == a.face, inspected
        assert inspected["always_on"]["batch_face_swap"]["checkpoint_hash"] in metadata, inspected
        replay_args = dict(inspected["generation_args"], init_image=str(source), denoising_strength=a.strength,
                           always_on=inspected["always_on"], return_images=False, model_keyword=False)
        replay = (await call("img2img", replay_args))["images"][0]["path"]
        with Image.open(changed) as x, Image.open(replay) as y:
            assert ImageChops.difference(x.convert("RGB"), y.convert("RGB")).getbbox() is None, "PNG/MCP replay differs"
        if a.hash_failure:
            mismatch = face(a.face)
            mismatch["batch_face_swap"]["checkpoint_hash"] = "0000000000"
            failed = await call("img2img", dict(params, always_on=mismatch), True)
            assert failed.get("isError", failed.get("is_error", False)) and "checkpoint hash" in str(failed), failed
            recovered = (await call("img2img", params))["images"][0]["path"]
            with Image.open(plain) as x, Image.open(recovered) as y:
                assert ImageChops.difference(x.convert("RGB"), y.convert("RGB")).getbbox() is None, "base did not recover after hash mismatch"
            receipt.update(hash_mismatch_rejected=True, after_hash_failure=recovered)
        if a.cancel:
            slow_face = face(a.face)
            slow_face["batch_face_swap"]["steps"] = 100
            queued = await call("img2img_async", dict(params, always_on=slow_face))
            saw_face = False
            for _ in range(600):
                state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 1, "return_images": False}, True)
                if "adetailer step" in state.get("message", ""):
                    saw_face = True
                    break
                if state.get("status") in {"done", "cancelled", "failed"}: break
            assert saw_face, state
            await call("cancel_job", {"job_id": queued["job_id"]})
            for _ in range(120):
                state = await call("get_job", {"job_id": queued["job_id"], "wait_seconds": 2, "return_images": False}, True)
                if state.get("status") in {"done", "cancelled", "failed"}: break
            assert state.get("status") == "cancelled", state
            recovered = (await call("img2img", params))["images"][0]["path"]
            with Image.open(plain) as x, Image.open(recovered) as y:
                assert ImageChops.difference(x.convert("RGB"), y.convert("RGB")).getbbox() is None, "base did not recover after cancel"
            receipt.update(cancel_state=state, after_cancel=recovered)
        if a.failure:
            failed = await call("img2img", dict(params, always_on=face("face-corrupt")), True)
            assert failed.get("isError", failed.get("is_error", False)), failed
            recovered = (await call("img2img", params))["images"][0]["path"]
            with Image.open(plain) as x, Image.open(recovered) as y:
                assert ImageChops.difference(x.convert("RGB"), y.convert("RGB")).getbbox() is None, "base did not recover after failed face model load"
            receipt.update(after_failure=recovered, failure_observed=True)
        assert hashlib.sha256(source.read_bytes()).hexdigest() == source_hash
        receipt.update(plain=plain, same=same, changed=changed, after=after, replay=replay, metadata=inspected, success=True)


def main():
    p = argparse.ArgumentParser()
    for name in ("source", "output", "models", "jar", "java", "python", "input"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--base", required=True)
    p.add_argument("--face", required=True)
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--dtype", default="fp16")
    p.add_argument("--sampler", default="Euler")
    p.add_argument("--strength", type=float, default=0.05)
    p.add_argument("--seed", type=int, default=2468)
    p.add_argument("--cfg", type=float, default=5)
    p.add_argument("--negative", default="lowres, blurry")
    p.add_argument("--schedule", default="")
    p.add_argument("--base-only", action="store_true")
    p.add_argument("--txt-face-only", action="store_true")
    p.add_argument("--face-strength", type=float, default=0.65)
    p.add_argument("--face-resolution", type=int, default=512)
    p.add_argument("--face-prompt", default="[PROMPT], smiling")
    p.add_argument("--detector", default="face_yolov8n.pt")
    p.add_argument("--lora-dir", type=Path)
    p.add_argument("--lora")
    p.add_argument("--pag", action="store_true")
    p.add_argument("--expect-lora-failure", action="store_true")
    p.add_argument("--hash-failure", action="store_true")
    p.add_argument("--cancel", action="store_true")
    p.add_argument("--failure", action="store_true")
    a = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(a).items()}, "success": False}
    stop = threading.Event()
    samples = []
    def monitor():
        while not stop.is_set():
            try:
                value = subprocess.check_output(["nvidia-smi", "--query-gpu=memory.used,temperature.gpu",
                    "--format=csv,noheader,nounits"], text=True, timeout=5).splitlines()[0]
                memory, temperature = (int(x.strip()) for x in value.split(","))
                samples.append((memory, temperature))
            except (OSError, subprocess.SubprocessError, ValueError, IndexError):
                pass
            stop.wait(1)
    thread = threading.Thread(target=monitor, daemon=True)
    thread.start()
    try:
        asyncio.run(run(a, receipt))
    finally:
        stop.set()
        thread.join(timeout=6)
        receipt["gpu_samples"] = len(samples)
        if samples:
            receipt["gpu_peak_memory_mib"] = max(x[0] for x in samples)
            receipt["gpu_peak_temperature_c"] = max(x[1] for x in samples)
        a.output.mkdir(parents=True, exist_ok=True)
        (a.output / "receipt.json").write_text(json.dumps(receipt, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
