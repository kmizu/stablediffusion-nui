"""Real-model Clip I/O acceptance through the official MCP SDK (run in the mcp==2.3.0 client venv).

Checks, per checkpoint: export leaves the image unchanged; the exported files have the documented shapes and token
strings; feeding an exported file back reproduces the original image exactly; combining / editing conditioning
changes the image; identity edits do not; PNG replay reproduces the image; options are honoured on the real tokenizer.
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import hashlib
import json
import math
import os
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image

SHORT = "a lion, a tiger"
EMPHASIS = "a (lion:1.4), a [tiger]"
NEGATIVE = "lowres, blurry, text"


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert("RGB").tobytes()


def mean_abs_diff(a, b):
    (sa, da), (sb, db) = rgb(a), rgb(b)
    assert sa == sb
    return sum(abs(x - y) for x, y in zip(da, db)) / len(da) / 255


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.reader(handle))


async def exercise(client, args, receipt, conditioning: Path):
    receipt["calls"] = []

    async def call(name, values, allow_error=False):
        result = await client.call_tool(name, values, read_timeout_seconds=3600)
        encoded = result.model_dump(mode="json")
        data = encoded.get("structuredContent", encoded.get("structured_content")) or {}
        error = encoded.get("isError", encoded.get("is_error", False))
        receipt["calls"].append({"tool": name, "arguments": values, "error": error,
                                 "result": data or encoded.get("content")})
        assert allow_error or not error, encoded
        return data if not error else {"error": encoded}

    def first(data):
        assert data.get("images"), data
        return data["images"][0]["path"]

    def clip(**values):
        return {"clip_io": dict({"enabled": True}, **values)}

    assert "clip_io" in json.dumps(await call("list_always_on", {"tab": "txt2img"}))
    await call("set_model", {"name": args.checkpoint})
    tag = args.tag
    sdxl = args.sdxl
    dim = 2048 if sdxl else 768
    skip = 3 if sdxl else 2  # SDXL uses the penultimate layer for clip skip 1 and 2, so 3 is the first different one
    base = dict(width=args.width, height=args.height, steps=args.steps, cfg_scale=args.cfg, seed=2468,
                sampler_name="Euler", return_images=False, model_keyword=False)

    def params(prompt, negative=NEGATIVE, **extra):
        return dict(base, prompt=prompt, negative_prompt=negative, **extra)

    out = {}
    check = receipt.setdefault("checks", {})
    out["baseline"] = first(await call("txt2img", params(args.prompt)))
    out["baseline_short"] = first(await call("txt2img", params(SHORT)))

    # -- Clip Output ------------------------------------------------------------------------------------------
    out["export"] = first(await call("txt2img", params(args.prompt, always_on=clip(
        output_enabled=True, output_name=f"{tag}_pos", negative_output_name=f"{tag}_neg", format="pt"))))
    assert rgb(out["export"]) == rgb(out["baseline"]), "export changed the image"
    pos = conditioning / f"{tag}_pos.pt"
    neg = conditioning / f"{tag}_neg.pt"
    assert pos.is_file() and neg.is_file(), sorted(p.name for p in conditioning.iterdir())
    await call("txt2img", params(args.prompt, always_on=clip(
        output_enabled=True, output_name=f"{tag}_csv", token_strings=True)))
    rows = read_csv(conditioning / f"{tag}_csv.csv")
    check["csv_token_strings"] = {"rows": len(rows), "columns": len(rows[0]), "first": rows[0][0], "last": rows[-1][0]}
    assert len(rows) == 77 and len(rows[0]) == dim + 1, check["csv_token_strings"]
    # SDXL's second tokenizer pads after the end token with "!" (id 0), SD1.x pads with the end token itself
    assert rows[0][0] == "<|startoftext|>" and rows[-1][0] == ("!" if sdxl else "<|endoftext|>"), rows[-1][0]
    assert all(math.isfinite(float(x)) for row in rows for x in row[1:])

    await call("txt2img", params(SHORT, always_on=clip(output_enabled=True, output_name=f"{tag}_short_raw",
                                                       no_special_tokens=True, token_strings=True)))
    raw = read_csv(conditioning / f"{tag}_short_raw.csv")
    check["no_special_tokens"] = {"rows": len(raw), "tokens": [r[0] for r in raw]}
    assert [r[0] for r in raw] == ["a</w>", "lion</w>", ",</w>", "a</w>", "tiger</w>"], raw[:6]
    assert len(raw[0]) == dim + 1

    await call("txt2img", params(SHORT, always_on=clip(output_enabled=True, output_name=f"{tag}_short_t",
                                                       no_special_tokens=True, transpose=True, token_strings=True)))
    transposed = read_csv(conditioning / f"{tag}_short_t.csv")
    check["transpose"] = {"rows": len(transposed), "columns": len(transposed[1])}
    assert len(transposed) == dim + 1 and len(transposed[0]) == 5 and len(transposed[1]) == 5
    assert transposed[0] == [r[0] for r in raw]
    assert [float(r[1]) for r in raw] == [float(x) for x in transposed[1]]

    for name, options in (("emph", {}), ("emph_ignore", {"ignore_emphasis": True}),
                          ("emph_nonorm", {"bypass_normalization": True})):
        await call("txt2img", params(EMPHASIS, always_on=clip(output_enabled=True, output_name=f"{tag}_{name}",
                                                              no_special_tokens=True, **options)))
    emph = {n: [[float(x) for x in r] for r in read_csv(conditioning / f"{tag}_{n}.csv")]
            for n in ("emph", "emph_ignore", "emph_nonorm")}
    assert all(len(v) == 5 for v in emph.values())
    plain = [[float(x) for x in r[1:]] for r in raw]
    assert emph["emph_ignore"] == plain, "ignore emphasis must equal the unweighted prompt"
    assert emph["emph"] != plain and emph["emph_nonorm"] != emph["emph"]
    if not sdxl:
        ratios = {round(a / b, 3) for ra, rb in zip(emph["emph"], emph["emph_nonorm"])
                  for a, b in zip(ra, rb) if abs(b) > 1e-2}
        check["normalization_ratio"] = sorted(ratios)[:5]
        # normalised = raw * one global factor (fp16 rounding of the stored values spreads the ratio a little)
        assert max(ratios) - min(ratios) < 0.01 and 0.5 < min(ratios) < 1.0, ratios

    # -- Clip Input: exact round trips -------------------------------------------------------------------------
    files = clip(input_enabled=True, positive_mode="Simple", negative_mode="Simple")
    out["input_both"] = first(await call("txt2img", params(f"{tag}_pos", f"{tag}_neg", always_on=files)))
    assert rgb(out["input_both"]) == rgb(out["baseline"]), "conditioning file round trip changed the image"
    out["input_positive"] = first(await call("txt2img", params(f"{tag}_pos", always_on=clip(input_enabled=True))))
    assert rgb(out["input_positive"]) == rgb(out["baseline"]), "positive-only input changed the image"
    out["input_csv"] = first(await call("txt2img", params(f"{tag}_csv", always_on=clip(input_enabled=True))))
    csv_equal = rgb(out["input_csv"]) == rgb(out["baseline"])
    check["csv_input_equals_baseline"] = csv_equal
    assert csv_equal == (not sdxl), "CSV input: SD1.5 is exact; SDXL has no pooled vector in a CSV"
    await call("txt2img", params(SHORT, clip_skip=skip, always_on=clip(output_enabled=True, output_name=f"{tag}_skip2",
                                                                   format="pt")))
    out["skip2_reference"] = first(await call("txt2img", params(SHORT, clip_skip=skip)))
    # the negative prompt is still encoded by CLIP, so the request uses clip skip 2 as well; the positive comes
    # from the file
    out["skip2_file_with_skip1"] = first(await call("txt2img", params(f"{tag}_skip2", clip_skip=skip,
                                                                      always_on=clip(input_enabled=True))))
    assert rgb(out["skip2_file_with_skip1"]) == rgb(out["skip2_reference"]), "clip-skip-2 file did not reproduce clip skip 2"
    assert rgb(out["skip2_reference"]) != rgb(out["baseline_short"])

    # -- Clip Input: combining and directives ------------------------------------------------------------------
    directive = clip(input_enabled=True, positive_mode="Directive")
    out["combined"] = first(await call("txt2img", params(f'{tag}_pos """a castle on a hill"""',
                                                         always_on=clip(input_enabled=True))))
    check["combined_diff"] = mean_abs_diff(out["combined"], out["baseline"])
    assert check["combined_diff"] > 0.005
    out["eval_identity"] = first(await call("txt2img", params(f"{tag}_pos ?eval(i)", always_on=directive)))
    assert rgb(out["eval_identity"]) == rgb(out["baseline"]), "?eval(i) changed the image"
    out["eval_orders"] = first(await call("txt2img", params(f"{tag}_pos ?eval_2(i*2) ?eval_1(i/2)", always_on=directive)))
    assert rgb(out["eval_orders"]) == rgb(out["baseline"]), "i/2*2 must be exact"
    out["eval_a"] = first(await call("txt2img", params(f"{tag}_pos ?eval_1(i+0.5) ?eval_2(i*2)", always_on=directive)))
    out["eval_b"] = first(await call("txt2img", params(f"{tag}_pos ?eval_2(i+0.5) ?eval_1(i*2)", always_on=directive)))
    out["eval_a_swapped"] = first(await call("txt2img", params(f"{tag}_pos ?eval_2(i*2) ?eval_1(i+0.5)", always_on=directive)))
    assert rgb(out["eval_a"]) == rgb(out["eval_a_swapped"]), "directive order must follow ORDER, not position"
    assert rgb(out["eval_a"]) != rgb(out["eval_b"]), "different order must give a different result"
    out["eval_scale"] = first(await call("txt2img", params(f"{tag}_pos ?eval(i*0.7)", always_on=directive)))
    out["eval_wave"] = first(await call("txt2img", params(f"{tag}_pos ?eval(i*(1+0.2*sin(t*0.3+d*0.01)))",
                                                          always_on=directive)))
    check["eval_scale_diff"] = mean_abs_diff(out["eval_scale"], out["baseline"])
    check["eval_wave_diff"] = mean_abs_diff(out["eval_wave"], out["baseline"])
    assert check["eval_scale_diff"] > 0.005 and check["eval_wave_diff"] > 0.005
    out["prompt_directive"] = first(await call("txt2img", params(f"?prompt('''{SHORT}''')", always_on=directive)))
    assert rgb(out["prompt_directive"]) == rgb(out["baseline_short"]), "?prompt must equal ordinary CLIP encoding"
    out["prompt_skip2"] = first(await call("txt2img", params(f"?prompt('''{SHORT}''', clip_skip={skip})", always_on=directive)))
    # positive: ?prompt at clip skip 2 vs the file exported at clip skip 2; negative: clip skip 1 in both
    out["skip2_file_neg_skip1"] = first(await call("txt2img", params(f"{tag}_skip2", always_on=clip(input_enabled=True))))
    assert rgb(out["prompt_skip2"]) == rgb(out["skip2_file_neg_skip1"]), "?prompt clip_skip=2 must equal a clip-skip-2 export"
    assert rgb(out["prompt_skip2"]) != rgb(out["prompt_directive"])
    out["prompt_nopad"] = first(await call("txt2img", params(f"?prompt('''{SHORT}''',,True)", always_on=directive)))
    check["no_padding_diff"] = mean_abs_diff(out["prompt_nopad"], out["baseline_short"])
    assert check["no_padding_diff"] > 0.005
    out["mixed_skips"] = first(await call("txt2img", params(
        f"?prompt(('''{SHORT}''', '''{SHORT}'''), [1, {skip}])", always_on=directive)))
    check["mixed_skip_diff"] = mean_abs_diff(out["mixed_skips"], out["baseline_short"])
    assert check["mixed_skip_diff"] > 0.005

    # -- other pipeline paths ----------------------------------------------------------------------------------
    batch_base = (await call("txt2img", params(args.prompt, batch_size=2)))["images"]
    batch_input = (await call("txt2img", params(f"{tag}_pos", f"{tag}_neg", batch_size=2, always_on=files)))["images"]
    assert len(batch_base) == len(batch_input) == 2
    for a, b in zip(batch_base, batch_input):
        assert rgb(a["path"]) == rgb(b["path"]), "batch of 2 differs"
    hires = {"enabled": True, "scale": args.hires_scale, "upscaler": "Latent", "steps": max(4, args.steps // 2),
             "denoising_strength": 0.5}
    out["hires_base"] = first(await call("txt2img", params(args.prompt, hires=hires)))
    out["hires_input"] = first(await call("txt2img", params(f"{tag}_pos", f"{tag}_neg", hires=hires, always_on=files)))
    assert rgb(out["hires_input"]) == rgb(out["hires_base"]), "hires with input differs"

    # -- replay ------------------------------------------------------------------------------------------------
    meta = await call("inspect_generation_metadata", {"image": out["eval_wave"]})
    candidate = meta["always_on"].get("clip_io", {})
    assert candidate.get("enabled") is True and candidate.get("positive_mode") == "Directive", meta["always_on"]
    assert not any(k.startswith("Clip ") for k in meta["unapplied_fields"]), meta["unapplied_fields"]
    replay = dict(meta["generation_args"], always_on=meta["always_on"], return_images=False)
    replay.setdefault("model_keyword", False)
    out["replay"] = first(await call("txt2img", replay))
    assert rgb(out["replay"]) == rgb(out["eval_wave"]), "PNG replay did not reproduce the image"
    meta = await call("inspect_generation_metadata", {"image": out["export"]})
    assert meta["always_on"]["clip_io"]["output_name"] == f"{tag}_pos", meta["always_on"]

    # -- errors ------------------------------------------------------------------------------------------------
    messages = {}
    (conditioning / f"{tag}_wrong.csv").write_text("1,2,3\n4,5,6\n", encoding="utf-8")
    for name, prompt, extra in (
            ("missing", "no_such_file", {}),
            ("escape", "../outside", {}),
            ("python", f"{tag}_pos ?eval(__import__('os').getcwd())", {"positive_mode": "Directive"}),
            ("division", f"{tag}_pos ?eval(1/0)", {"positive_mode": "Directive"}),
            ("directive", f"{tag}_pos ?exec(1)", {"positive_mode": "Directive"}),
            ("quote", f'{tag}_pos "open', {}),
            ("width", f"{tag}_wrong", {})):
        result = await call("txt2img", params(prompt, always_on=clip(input_enabled=True, **extra)), allow_error=True)
        assert "error" in result, f"{name} did not fail"
        messages[name] = json.dumps(result["error"])[:300]
    check["error_messages"] = messages
    out["after_errors"] = first(await call("txt2img", params(args.prompt)))
    assert rgb(out["after_errors"]) == rgb(out["baseline"]), "engine state leaked after errors"
    receipt["outputs"] = out
    receipt["success"] = True


async def run(args, receipt):
    app_home = (args.output / "app-home").resolve()
    (app_home / "config").mkdir(parents=True, exist_ok=True)
    (app_home / "python").mkdir(exist_ok=True)
    conditioning = (args.output / "conditioning").resolve()
    conditioning.mkdir(parents=True, exist_ok=True)
    settings = dict(python_exe=str(args.python.resolve()), output_dir=str((args.output / "images").resolve()),
                    model_dirs={"checkpoints": [str(args.models.resolve())]}, last_model=args.checkpoint,
                    vram_mode=args.vram_mode, dtype="fp16", preview_every=0)
    (app_home / "config" / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"), SDFX_CLIP_IO_DIR=str(conditioning))
    command = [str(args.java), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()),
               "sdfx.mcp.McpMain", "--home", str(app_home), "--drain-seconds", "5"]
    receipt.update(command=command, jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=3600) as client:
        await exercise(client, args, receipt, conditioning)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--models", type=Path, required=True)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--sdxl", action="store_true")
    p.add_argument("--tag", default="sd15")
    p.add_argument("--width", type=int, default=512)
    p.add_argument("--height", type=int, default=512)
    p.add_argument("--steps", type=int, default=20)
    p.add_argument("--cfg", type=float, default=6.0)
    p.add_argument("--hires-scale", type=float, default=1.5)
    p.add_argument("--vram-mode", default="model_offload")
    p.add_argument("--prompt", default="a lion and a tiger in the woods, detailed illustration, (golden light:1.2)")
    args = p.parse_args()
    receipt = {"args": {k: str(v) for k, v in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"success": receipt.get("success"), "checks": receipt.get("checks"),
                      "outputs": receipt.get("outputs")}, indent=2))


if __name__ == "__main__":
    main()
