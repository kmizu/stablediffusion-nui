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
An optional separate checkpoint is loaded into a job-scoped auxiliary engine for the face pass.
"""
from __future__ import annotations

from contextlib import contextmanager
import sys
import re
from .protocol import INVALID_PARAMS, EngineError

DEFAULTS = {"detector": "face_yolov8n.pt", "confidence": 0.3, "max_faces": 0, "mask_shape": "ellipse",
            "mask_size": 1.2, "mask_blur": 8, "denoising_strength": 0.5, "padding": 32, "resolution": 0,
            "prompt": "", "negative_prompt": "", "steps": 0, "cfg_scale": 0.0, "sampler": "",
            "color_correction": True, "checkpoint": "", "checkpoint_hash": ""}
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
    if not isinstance(c["checkpoint"], str) or len(c["checkpoint"]) > 8192:
        raise EngineError(INVALID_PARAMS, "Batch Face Swap: checkpoint must be a string")
    c["checkpoint"] = c["checkpoint"].strip()
    if not isinstance(c["checkpoint_hash"], str):
        raise EngineError(INVALID_PARAMS, "Batch Face Swap: checkpoint hash must be a string")
    c["checkpoint_hash"] = c["checkpoint_hash"].strip().lower()
    if c["checkpoint_hash"] and (not c["checkpoint"] or not re.fullmatch(r"[0-9a-f]{10}", c["checkpoint_hash"])):
        raise EngineError(INVALID_PARAMS, "Batch Face Swap: expected checkpoint hash requires a checkpoint and 10 hexadecimal digits")
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


def verify_checkpoint_hash(expected, model):
    actual = (model or {}).get("hash")
    if expected and (not isinstance(actual, str) or actual.lower() != expected.lower()):
        raise EngineError(INVALID_PARAMS, "Batch Face Swap: face checkpoint hash does not match the recorded hash")


@contextmanager
def face_engine_scope(base, checkpoint, ctx, factory):
    """Own an auxiliary face pipeline while keeping the base pipeline intact."""
    ctx.check_interrupt()
    from .resource_hashes import FileSnapshot
    try:
        snapshot = FileSnapshot.capture(checkpoint)
    except OSError as error:
        raise EngineError(INVALID_PARAMS, f"Batch Face Swap: cannot inspect checkpoint: {error}") from error
    other = factory()
    other._owns_shared_cache = False
    try:
        base.park()
        ctx.check_interrupt()
        options = {"path": checkpoint, "dtype": base.load_opts.dtype,
                   "vram_mode": "model_offload" if getattr(base, "cuda", False) else "gpu"}
        other.load_model(options, ctx)
        ctx.check_interrupt()
        try:
            digest = snapshot.digest()
        except (OSError, ValueError) as error:
            raise EngineError(INVALID_PARAMS, f"Batch Face Swap: checkpoint changed while loading: {error}") from error
        if (getattr(other, "model", None) or {}).get("hash") != digest:
            raise EngineError(INVALID_PARAMS, "Batch Face Swap: checkpoint hash changed while loading")
        other._face_checkpoint_hash = digest
        yield other
    finally:
        primary = sys.exc_info()[1]
        errors = []
        composable = getattr(other, "_composable_job", None)
        if composable is not None:
            try:
                composable.remove()
            except BaseException as error:
                errors.append(error)
            other._composable_job = None
        try:
            other._free()
        except BaseException as error:
            errors.append(error)
        try:
            if base.pipe is not None:
                base._activate(base.pipe)
        except BaseException as error:
            errors.append(error)
        if errors:
            target = primary or errors.pop(0)
            notes = [f"Face checkpoint cleanup or restore failed: {error}" for error in errors]
            if hasattr(target, "add_note"):
                for note in notes: target.add_note(note)
            else:
                target.__notes__ = getattr(target, "__notes__", []) + notes
            warn = getattr(ctx, "warn", None)
            if callable(warn):
                for note in notes: warn(note)
            if primary is None:
                raise target


def prepare_face_engine(other, gp, req, ctx, cfg):
    """Prepare a separately owned pipeline with this job's compatible conditioning."""
    from . import extensions as ext_mod
    from .cd_tuner import Job as CdTunerJob
    other._editor_preview = None
    other._embedding_merge_ctx = ctx
    other._clip_io_done = set()
    other._intermediate_recorder = None
    other._composable_job = None
    other._cd_tuner_job = CdTunerJob(False)
    other._incantations_job = None
    other._vae_recovery = []
    other._aesthetic_digests = set()
    other._extra = dict(gp.extra or {})
    other._ms_overrides = ext_mod.model_sampling_overrides(gp)
    applied = other._apply_loras(gp, req, ctx)
    missing = sorted({lr.name for lr in gp.loras} - set(applied))
    if missing:
        raise EngineError(INVALID_PARAMS, "Batch Face Swap: face checkpoint could not apply LoRA: " + ", ".join(missing))
    other._load_embeddings(req, [gp.prompt, gp.negative_prompt, cfg.get("prompt", ""), cfg.get("negative_prompt", "")], ctx)


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
