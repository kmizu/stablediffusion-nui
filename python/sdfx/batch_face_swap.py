"""Batch Face Swap (kex0/batch-face-swap): detect every face of a finished image and redraw it.

Behaviour implemented from the project README only ("Automatically detects faces and replaces them", override
options that affect only the face pass: a different prompt, negative prompt, ... than the first pass). The upstream
repository has no licence, so no code was read or copied.

  {"batch_face_swap": {"detector": "face_yolov8n.pt", "confidence": 0.3, "max_faces": 0, "mask_shape": "ellipse",
                       "mask_size": 1.2, "mask_blur": 8, "denoising_strength": 0.5, "padding": 32, "resolution": 0,
                       "prompt": "", "negative_prompt": "", "steps": 0, "cfg_scale": 0.0, "sampler": "",
                       "color_correction": true}}

This is a face-only pass over the same inpaint machinery as ADetailer: each detected face becomes one mask, the
surrounding crop is redrawn at `resolution` (the face area magnification) and pasted back. `[PROMPT]` in the face
prompt is replaced by the main prompt and `[SEP]` gives a different prompt to each face (left to right).
Not provided: the "override model" option (a different checkpoint for the face pass) and the folder input of the
img2img tab; use the MCP/GUI img2img with one image at a time.
"""
from __future__ import annotations

from .protocol import INVALID_PARAMS, EngineError

DEFAULTS = {"detector": "face_yolov8n.pt", "confidence": 0.3, "max_faces": 0, "mask_shape": "ellipse",
            "mask_size": 1.2, "mask_blur": 8, "denoising_strength": 0.5, "padding": 32, "resolution": 0,
            "prompt": "", "negative_prompt": "", "steps": 0, "cfg_scale": 0.0, "sampler": "",
            "color_correction": True}
SHAPES = ("ellipse", "box")


def _num(c: dict, key: str, low: float, high: float, integer: bool = False) -> float:
    try:
        v = float(c[key])
    except (TypeError, ValueError):
        raise EngineError(INVALID_PARAMS, f"Batch Face Swap: {key} must be a number (got {c[key]!r})")
    if v != v or v in (float("inf"), float("-inf")) or not low <= v <= high or (integer and v != int(v)):
        raise EngineError(INVALID_PARAMS, f"Batch Face Swap: {key} must be {'an integer ' if integer else ''}"
                                          f"between {low:g} and {high:g} (got {c[key]!r})")
    return int(v) if integer else v


def normalize(cfg: dict) -> dict:
    """Validated settings; unknown keys and out-of-range values are rejected."""
    unknown = sorted(set(cfg) - set(DEFAULTS))
    if unknown:
        raise EngineError(INVALID_PARAMS, f"Batch Face Swap: unknown setting(s) {', '.join(unknown)}")
    c = dict(DEFAULTS)
    c.update(cfg)
    c["detector"] = str(c["detector"]).strip()
    if not c["detector"] or c["detector"].lower() == "none":
        raise EngineError(INVALID_PARAMS, "Batch Face Swap: a detector is required")
    c["confidence"] = _num(c, "confidence", 0.0, 1.0)
    c["max_faces"] = _num(c, "max_faces", 0, 64, True)
    if str(c["mask_shape"]).lower() not in SHAPES:
        raise EngineError(INVALID_PARAMS, f"Batch Face Swap: mask_shape must be one of {', '.join(SHAPES)}")
    c["mask_shape"] = str(c["mask_shape"]).lower()
    c["mask_size"] = _num(c, "mask_size", 0.3, 3.0)
    c["mask_blur"] = _num(c, "mask_blur", 0, 64, True)
    c["denoising_strength"] = _num(c, "denoising_strength", 0.0, 1.0)
    c["padding"] = _num(c, "padding", 0, 512, True)
    c["resolution"] = _num(c, "resolution", 0, 2048, True)
    if c["resolution"] and c["resolution"] < 64:
        raise EngineError(INVALID_PARAMS, "Batch Face Swap: resolution must be 0 (the generation size) or at least 64")
    c["steps"] = _num(c, "steps", 0, 150, True)
    c["cfg_scale"] = _num(c, "cfg_scale", 0.0, 30.0)
    c["prompt"], c["negative_prompt"] = str(c["prompt"]), str(c["negative_prompt"])
    c["sampler"] = str(c["sampler"]).strip()
    c["color_correction"] = bool(c["color_correction"]) if isinstance(c["color_correction"], bool) else \
        str(c["color_correction"]).strip().lower() in ("1", "true", "yes", "on")
    return c


def to_adetailer(cfg: dict) -> dict:
    """The one ADetailer unit that performs the face pass (face-shaped masks, no mask post-processing)."""
    c = normalize(cfg)
    side = int(c["resolution"]) // 8 * 8
    return {"units": [{
        "model": c["detector"], "confidence": c["confidence"], "mask_k_largest": c["max_faces"],
        "mask_shape": c["mask_shape"], "mask_scale": c["mask_size"], "dilate_erode": 0, "mask_blur": c["mask_blur"],
        "denoising_strength": c["denoising_strength"], "inpaint_only_masked": True, "inpaint_padding": c["padding"],
        "use_inpaint_width_height": side > 0, "inpaint_width": side or 512, "inpaint_height": side or 512,
        "prompt": c["prompt"], "negative_prompt": c["negative_prompt"],
        "use_steps": c["steps"] > 0, "steps": c["steps"] or 28,
        "use_cfg_scale": c["cfg_scale"] > 0, "cfg_scale": c["cfg_scale"] or 7.0,
        "use_sampler": bool(c["sampler"]), "sampler": c["sampler"], "color_correction": c["color_correction"]}]}


__all__ = ["DEFAULTS", "normalize", "to_adetailer"]
