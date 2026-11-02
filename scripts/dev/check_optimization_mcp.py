"""Exercise #143 through the official MCP SDK; GPU lock must be held by the caller."""
from __future__ import annotations
import argparse
import asyncio
import hashlib
import json
import os
import time
from pathlib import Path
from mcp import Client, StdioServerParameters
from PIL import Image


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def difference(a, b):
    (sa, da), (sb, db) = rgb(a), rgb(b)
    assert sa == sb
    return sum(abs(x-y) for x, y in zip(da, db)) / len(da) / 255


async def run(args, receipt):
    home = (args.output / "app-home").resolve()
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    settings = {"python_exe": str(args.python.resolve()), "output_dir": str((args.output / "images").resolve()),
                "model_dirs": {"checkpoints": [str(args.models.resolve())], **({"lora": [str(args.lora_dir.resolve())]} if args.lora_dir else {})},
                "last_model": args.checkpoint, "vram_mode": "gpu", "dtype": "fp16", "preview_every": 0,
                "vae_auto": True, "vae_slicing": True, "vae_fp16": args.vae_fp16}
    (home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    command = [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
               "sdfx.mcp.McpMain", "--home", str(home), "--drain-seconds", "5"]
    if args.dry_run:
        command.append("--dry-run")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    receipt.update(settings=settings, command=command, jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(), calls=[])
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=7200) as client:
        async def call(name, values, allow_error=False):
            start = time.monotonic()
            result = (await client.call_tool(name, values, read_timeout_seconds=7200)).model_dump(mode="json")
            error = result.get("isError", result.get("is_error", False))
            data = result.get("structuredContent", result.get("structured_content")) or {}
            receipt["calls"].append({"tool": name, "seconds": round(time.monotonic()-start, 2), "error": error, "data": data})
            assert allow_error or not error, result
            return data if not error else {"error": result}
        def path(result):
            assert result.get("images"), result
            return result["images"][0]["path"]
        await call("set_model", {"name": args.checkpoint})
        args_base = {"width": args.width, "height": args.height, "steps": args.steps, "cfg_scale": 6.0,
                     "seed": 2468, "sampler_name": "Euler", "return_images": False, "model_keyword": False,
                     "prompt": "a lion and a tiger in the woods, detailed illustration, golden light",
                     "negative_prompt": "lowres, blurry, text"}
        dc = {"deepcache": {"enabled": True, "interval": 3, "branch": 0, "full_first": 5}}
        plain = path(await call("txt2img", args_base))
        cached = path(await call("txt2img", dict(args_base, always_on=dc)))
        cached2 = path(await call("txt2img", dict(args_base, always_on=dc)))
        assert rgb(cached) == rgb(cached2), "DeepCache repeat differs"
        metadata = await call("inspect_generation_metadata", {"image": cached})
        assert metadata["always_on"]["deepcache"]["enabled"] is True
        replay = dict(metadata["generation_args"], always_on=metadata["always_on"], return_images=False)
        replay.setdefault("model_keyword", False)
        replayed = path(await call("txt2img", replay))
        assert rgb(cached) == rgb(replayed), "DeepCache metadata replay differs"
        hires = {"enabled": True, "scale": 1.25, "upscaler": "Latent", "steps": 6, "denoising_strength": 0.5}
        hi = path(await call("txt2img", dict(args_base, hires=hires, always_on=dc)))
        if not args.dry_run:
            refused = await call("txt2img", dict(args_base, always_on={**dc, "freeu": {"enabled": True}}), allow_error=True)
            assert "error" in refused and "freeu" in json.dumps(refused).lower(), refused
        after = path(await call("txt2img", args_base))
        assert rgb(plain) == rgb(after), "DeepCache leaked into subsequent generation"
        checks = {"plain_vs_cached_mad": difference(plain, cached), "repeat_identical": True,
                  "replay_identical": True, "freeu_refused": None if args.dry_run else True, "plain_after_identical": True}
        outputs = {"plain": plain, "cached": cached, "hires": hi, "replayed": replayed}
        if args.lora:
            lora_args = dict(args_base, prompt=args_base["prompt"] + f" <lora:{args.lora}:0.8>",
                             loras=[{"name": args.lora, "weight": 0.8}])
            lp = path(await call("txt2img", lora_args))
            lc = path(await call("txt2img", dict(lora_args, always_on=dc)))
            checks["lora_plain_vs_cached_mad"] = difference(lp, lc)
            checks["lora_vs_plain_mad"] = difference(plain, lp)
            assert checks["lora_vs_plain_mad"] > 0.002, "LoRA has no detectable effect"
            outputs.update(lora_plain=lp, lora_cached=lc)
        receipt.update(success=True, checks=checks, outputs=outputs)


def main():
    parser = argparse.ArgumentParser()
    for name in ("output", "models", "jar", "python"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--java", type=Path, default=Path("C:/Program Files/Java/jdk-21/bin/java.exe"))
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--width", type=int, default=512)
    parser.add_argument("--height", type=int, default=512)
    parser.add_argument("--steps", type=int, default=20)
    parser.add_argument("--vae-fp16", action="store_true")
    parser.add_argument("--dry-run", action="store_true", help="SDK wiring only; does not test the optimization or incompatibility")
    parser.add_argument("--lora", default="")
    parser.add_argument("--lora-dir", type=Path)
    args = parser.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "checks": receipt.get("checks")}, indent=2))


if __name__ == "__main__":
    main()
