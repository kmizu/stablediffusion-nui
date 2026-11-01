"""Real-model SD safety checker (#91 index 83) and NSFWJS filter (index 231) acceptance through the official MCP SDK
(run in the mcp==2.3.0 client venv), next to check_nsfw_checker_real.py (NudeNet, index 238).

No explicit content is generated. Phase 1 (normal engine) downloads and hash-checks both models on first use and
shows that a clothed portrait passes unchanged: generation with each checker, NSFWJS at every safety level, live
previews on, the Extras checkers (with Only report) and PNG replay. Phase 2 restarts the server with
SDFX_NSFW_CHECKER_FORCE_FLAGGED=safety_checker,nsfwjs, which makes both real models' verdicts "flagged" after they
have run, to prove the flagged paths: black images, NSFWJS's two radius-20 blurs on saved files (one in Extras),
Only report, the combination with NudeNet, and PNG replay. Inspect the saved images afterwards (receipt.json lists
them); assertions alone are not acceptance.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import re
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image, ImageFilter

SAFETY = ("CompVis__stable-diffusion-safety-checker", "cb41f3a270d63d454d385fc2e4f571c487c253c5",
          {"config.json": "c879a63ee73f528bc5ec148aaea487b2b165f6a785f9cb55b90e590ba7977e0e",
           "preprocessor_config.json": "2a1da83b5e1032aaeef397552ddb408dca0d8cd1dc58f61bf6abf38d6f33a0a2",
           "pytorch_model.bin": "64b8393f1afd5a0c1ed2aa5f341fa7c08286839a48f3743162a76a2835c808bd"})
NSFWJS = ("deepghs__imgutils-models", "35e0ed8f35ce0dbea674568161a48b31ba7d2a99",
          {"nsfwjs.onnx": "510dce55edcd1cf8281cbe906dedb3f4963f8814bc1edfbd42d3bf261148df2d"})
NSFW_TOP = {"safe": ("hentai", "porn", "sexy"), "questionable": ("hentai", "porn"), "explicit": ()}


def load(path):
    with Image.open(path) as image:
        return image.convert("RGB")


def rgb(path):
    image = load(path)
    return image.size, image.tobytes()


def parameters(path):
    with Image.open(path) as image:
        return image.info.get("parameters", "")


def field(path, key):
    """One infotext value (A1111 quoting: values with commas are JSON-quoted)."""
    pattern = re.compile(rf'(?:^|, ){re.escape(key)}: ("(?:\\.|[^"\\])*"|[^,]*)')
    for line in parameters(path).splitlines():
        match = pattern.search(line)
        if match:
            value = match.group(1)
            return json.loads(value) if value.startswith('"') else value.strip()
    return None


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


class Session:
    def __init__(self, client, receipt, phase):
        self.client, self.receipt, self.phase = client, receipt, phase

    async def call(self, name, values):
        result = await self.client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        self.receipt.setdefault("calls", []).append({"phase": self.phase, "tool": name, "arguments": values,
                                                     "result": data or encoded.get("content")})
        assert not encoded.get("isError", encoded.get("is_error", False)), encoded
        return data

    async def generate(self, params, always_on=None):
        data = await self.call("txt2img", dict(params, **({"always_on": always_on} if always_on else {})))
        assert data.get("images"), data
        return data["images"][0]["path"]

    async def extras(self, image, **values):
        data = await self.call("run_script", {"script_id": "nsfw_checker_extras", "values": values,
                                              "return_images": False, "params": {"init_image": image, "prompt": ""}})
        paths = [entry["path"] for entry in data.get("images", [])]
        assert len(paths) == 1, data
        return paths[0]


def checker(**over):
    return {"nsfw_checker": dict({"enabled": True, "nudenet": False}, **over)}


async def normal_phase(s: Session, args, out):
    listed = await s.call("list_always_on", {"tab": "txt2img"})
    text = json.dumps(listed)
    assert "nsfw_checker" in text and "safety_checker" in text and "nsfwjs_safety_level" in text, \
        "nsfw_checker missing or without the index 83 / 231 controls"
    await s.call("set_model", {"name": args.checkpoint})
    params = s.params
    out["baseline"] = await s.generate(params)
    base = rgb(out["baseline"])

    # Extras first: report only, so the classes are known before any assertion about blurring
    report = await s.extras(out["baseline"], checker="NSFWJS", nsfwjs_safety_level="safe", report_only=True)
    out["extras_nsfwjs_report"] = report
    verdict = field(report, "NSFWJS filter Extras")
    assert verdict and verdict.startswith("safe; "), verdict
    top = verdict.rsplit("; top class ", 1)[1]
    s.receipt["nsfwjs_top_class"] = top
    assert top not in ("hentai", "porn"), f"clothed portrait classified {top}; change --prompt or --seed"
    assert rgb(report) == base, "Only report must keep the image"
    safety_report = await s.extras(out["baseline"], checker="SD safety checker", report_only=True)
    out["extras_safety_report"] = safety_report
    assert field(safety_report, "SD safety checker Extras") == "not NSFW", parameters(safety_report)
    assert rgb(safety_report) == base
    for level in ("safe", "questionable", "explicit"):
        path = await s.extras(out["baseline"], checker="NSFWJS", nsfwjs_safety_level=level)
        out[f"extras_nsfwjs_{level}"] = path
        flagged = top in NSFW_TOP[level]
        expected = load(out["baseline"]).filter(ImageFilter.GaussianBlur(20)).tobytes() if flagged else base[1]
        assert load(path).tobytes() == expected, f"Extras NSFWJS {level}"
    out["extras_safety"] = await s.extras(out["baseline"], checker="SD safety checker")
    assert rgb(out["extras_safety"]) == base

    # Generation: the clothed portrait passes every checker unchanged (models downloaded and hash-checked here)
    out["safety"] = await s.generate(params, checker(safety_checker=True, safety_checker_live_preview=True))
    assert rgb(out["safety"]) == base, "SD safety checker changed a clothed portrait"
    assert field(out["safety"], "SD safety checker") == "final images and live previews"
    for level in ("safe", "questionable", "explicit"):
        path = await s.generate(params, checker(nsfwjs=True, nsfwjs_safety_level=level))
        out[f"nsfwjs_{level}"] = path
        assert field(path, "NSFWJS filter") == level
        if top in NSFW_TOP[level]:  # e.g. "sexy" at safe: blurred once or twice (the second check sees the blur)
            s.receipt.setdefault("notes", []).append(f"top class {top} is NSFW at level {level}: blurred")
            assert load(path).tobytes() != base[1]
        else:
            assert rgb(path) == base, f"NSFWJS {level} changed a clothed portrait"
    out["all"] = await s.generate(params, {"nsfw_checker": {"enabled": True, "safety_checker": True, "nsfwjs": True,
                                                            "nsfwjs_safety_level": "questionable"}})
    assert rgb(out["all"]) == base, "defaults of NudeNet plus both classifiers must leave the portrait unchanged"
    out["after"] = await s.generate(params)
    assert rgb(out["after"]) == base

    meta = await s.call("inspect_generation_metadata", {"image": out["nsfwjs_questionable"]})
    restored = meta["always_on"].get("nsfw_checker")
    assert restored and restored["nsfwjs"] is True and restored["nudenet"] is False, meta["always_on"]
    assert restored["nsfwjs_safety_level"] == "questionable"
    assert "NSFWJS filter" not in meta["unapplied_fields"]


async def forced_phase(s: Session, args, out):
    await s.call("set_model", {"name": args.checkpoint})
    params = s.params
    out["forced_plain"] = await s.generate(params)
    base_image = load(out["forced_plain"])
    # a plain run is unaffected by the force variable (recorded; GPU runs across restarts may differ slightly)
    s.receipt["forced_plain_equals_baseline"] = rgb(out["forced_plain"]) == rgb(out["baseline"])
    black = Image.new("RGB", base_image.size, (0, 0, 0)).tobytes()
    once = base_image.filter(ImageFilter.GaussianBlur(20))
    twice = once.filter(ImageFilter.GaussianBlur(20))

    out["forced_safety"] = await s.generate(params, checker(safety_checker=True))
    assert load(out["forced_safety"]).tobytes() == black, "flagged image must be black"
    assert field(out["forced_safety"], "SD safety checker") == "final images"
    out["forced_nsfwjs"] = await s.generate(params, checker(nsfwjs=True))
    assert load(out["forced_nsfwjs"]).tobytes() == twice.tobytes(), "saved file: postprocess + save-time blur"
    out["forced_explicit"] = await s.generate(params, checker(nsfwjs=True, nsfwjs_safety_level="explicit"))
    assert rgb(out["forced_explicit"]) == rgb(out["forced_plain"]), "explicit never blurs"
    out["forced_both"] = await s.generate(params, checker(safety_checker=True, nsfwjs=True))
    assert load(out["forced_both"]).tobytes() == black
    # with NudeNet (exposed-only defaults find nothing on the portrait) the order still ends in two NSFWJS blurs
    out["forced_with_nudenet"] = await s.generate(params, {"nsfw_checker": {"enabled": True, "nsfwjs": True}})
    assert load(out["forced_with_nudenet"]).tobytes() == twice.tobytes()

    meta = await s.call("inspect_generation_metadata", {"image": out["forced_safety"]})
    restored = meta["always_on"].get("nsfw_checker")
    assert restored and restored["safety_checker"] is True and restored["nudenet"] is False, meta["always_on"]
    assert "SD safety checker" not in meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["forced_replay"] = await s.generate(replay)
    assert load(out["forced_replay"]).tobytes() == black, "PNG replay must re-enable the safety checker"

    out["forced_extras_nsfwjs"] = await s.extras(out["forced_plain"], checker="NSFWJS")
    assert load(out["forced_extras_nsfwjs"]).tobytes() == once.tobytes(), "Extras: one save-time blur"
    assert field(out["forced_extras_nsfwjs"], "NSFWJS filter Extras").startswith("safe; NSFW (blurred); top class ")
    out["forced_extras_report"] = await s.extras(out["forced_plain"], checker="NSFWJS", report_only=True)
    assert rgb(out["forced_extras_report"]) == rgb(out["forced_plain"])
    assert "NSFW (reported only)" in field(out["forced_extras_report"], "NSFWJS filter Extras")
    out["forced_extras_safety"] = await s.extras(out["forced_plain"], checker="SD safety checker")
    assert load(out["forced_extras_safety"]).tobytes() == black
    assert field(out["forced_extras_safety"], "SD safety checker Extras") == "NSFW (black image)"


async def run_phase(args, receipt, phase, extra_env):
    app_home = (args.output / f"app-home-{phase}").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    images = (args.output / "images" / phase).resolve()
    images.mkdir(parents=True, exist_ok=True)
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str(images),
                    model_dirs={"checkpoints": [str(args.models.resolve())]}, last_model=args.checkpoint,
                    vram_mode="model_offload", dtype="fp16", preview_every=2)  # live preview checks run
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"), **extra_env)
    if args.cache:
        env["SDFX_NSFW_CHECKER_CACHE"] = str(args.cache.resolve())
    if not extra_env:
        env.pop("SDFX_NSFW_CHECKER_FORCE_FLAGGED", None)
    command = [str(args.launcher.resolve()), "--mcp", "--home", str(app_home), "--drain-seconds", "5"] if args.launcher else \
        [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
         "sdfx.mcp.McpMain", "--home", str(app_home), "--drain-seconds", "5"]
    receipt.setdefault("commands", {})[phase] = {"command": command, "env": extra_env}
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=3600) as client:
        s = Session(client, receipt, phase)
        s.params = dict(prompt=args.prompt, negative_prompt="nsfw, nude, lowres, blurry, text", width=args.width,
                        height=args.height, steps=args.steps, seed=args.seed, sampler_name="Euler",
                        return_images=False, model_keyword=False)
        await (normal_phase if phase == "normal" else forced_phase)(s, args, receipt["outputs"])


def model_hashes(cache: Path):
    found = {}
    for repo, revision, files in (SAFETY, NSFWJS):
        for name, expected in files.items():
            path = cache / repo / revision / name
            actual = sha256(path) if path.is_file() else None
            found[f"{repo}/{revision}/{name}"] = actual
            assert actual == expected, f"{path}: {actual} != pinned {expected}"
    return found


async def run(args, receipt):
    receipt["jar_sha256"] = sha256(args.jar)
    receipt["outputs"] = {}
    await run_phase(args, receipt, "normal", {})
    cache = args.cache or Path.home() / ".cache" / "stablediffusion-nui" / "nsfw_checker"
    receipt["model_sha256"] = model_hashes(cache)
    await run_phase(args, receipt, "forced", {"SDFX_NSFW_CHECKER_FORCE_FLAGGED": "safety_checker,nsfwjs"})
    receipt["success"] = True


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--models", type=Path, required=True)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--launcher", type=Path)
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--cache", type=Path, help="SDFX_NSFW_CHECKER_CACHE for the engine (default: its own default)")
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=768)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--seed", type=int, default=777)
    p.add_argument("--prompt", default="1girl, solo, close-up portrait, face, looking at viewer, smile, "
                                       "winter coat, scarf, fully clothed, city street, detailed illustration")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "nsfwjs_top_class": receipt.get("nsfwjs_top_class"),
                      "outputs": receipt.get("outputs")}, indent=2))


if __name__ == "__main__":
    main()
