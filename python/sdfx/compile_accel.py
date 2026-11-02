"""U-Net compilation: torch.compile (Inductor) and TensorRT (Torch-TensorRT) for the denoising network.

Hook `compile` in params.hooks:

  {"compile": {"backend": "inductor" | "tensorrt", "mode": "default" | "max-autotune-no-cudagraphs" | "reduce-overhead",
               "min_block_size": 5, "optimization_level": 3, "cache_engines": true}}

Behaviour (NVIDIA/Stable-Diffusion-WebUI-TensorRT, MIT, is the behavioural reference; nothing is copied):

* the first generation with a new resolution / batch size builds the engine (minutes for TensorRT), later
  generations at the same shape reuse it; TensorRT engines are also cached on disk, so a new session only
  pays the (much shorter) load. This replaces the extension's separate "Generate engine" step with a lazy build;
* like the extension's static engines, an engine serves one batch size and one resolution; a Hires. fix pass
  builds a second one;
* the compiled forward replaces `unet.forward` for the length of one generation and is restored afterwards, so
  generations without the hook (and other models) run the untouched U-Net;
* hooks that patch the U-Net, ControlNet, LoRAs (for TensorRT, whose engines bake the weights in) and the
  offload VRAM modes are rejected with a clear message instead of silently producing a wrong or slow result.

The Inductor backend needs `triton` (on Windows `triton-windows`) and a C compiler (MSVC `cl` on Windows).
The TensorRT backend needs `torch-tensorrt` and the matching `tensorrt` wheels. Both are optional.
"""
from __future__ import annotations

import logging
import os
import shutil
import sys
import time
from typing import Any, Callable, Optional

from .hooks import coerce_bool, hook_config
from .protocol import INVALID_PARAMS, UNSUPPORTED, EngineError

log = logging.getLogger("sdfx.compile")

BACKENDS = ("inductor", "tensorrt")
MODES = ("default", "max-autotune-no-cudagraphs", "reduce-overhead")
DEFAULTS: dict = {"backend": "inductor", "mode": "default", "min_block_size": 5, "optimization_level": 3,
                  "cache_engines": True}

# Hooks that do not touch the U-Net forward pass (they act on prompts, pixels, files or step callbacks only).
# Anything else is refused: nothing else is known to leave the compiled graph valid.
COMPATIBLE_HOOKS = frozenset({
    "compile", "model_keyword", "pnginfo_injection", "steps_animation", "gpu_temperature_guard", "nsfw_checker",
    "aesthetic_score", "vae_tools", "tokenizer",
})


def normalize(cfg: dict) -> dict:
    backend = str(cfg.get("backend", DEFAULTS["backend"])).strip().lower()
    if backend not in BACKENDS:
        raise EngineError(INVALID_PARAMS, f"compile backend must be one of {', '.join(BACKENDS)} (got {backend!r})")
    mode = str(cfg.get("mode", DEFAULTS["mode"])).strip().lower()
    if mode not in MODES:
        raise EngineError(INVALID_PARAMS, f"compile mode must be one of {', '.join(MODES)} (got {mode!r})")
    try:
        mbs = int(float(cfg.get("min_block_size", DEFAULTS["min_block_size"])))
        opt = int(float(cfg.get("optimization_level", DEFAULTS["optimization_level"])))
    except (TypeError, ValueError):
        raise EngineError(INVALID_PARAMS, "compile min_block_size / optimization_level must be integers")
    if not 1 <= mbs <= 64:
        raise EngineError(INVALID_PARAMS, "compile min_block_size must be 1..64")
    if not 0 <= opt <= 5:
        raise EngineError(INVALID_PARAMS, "compile optimization_level must be 0..5")
    return {"backend": backend, "mode": mode, "min_block_size": mbs, "optimization_level": opt,
            "cache_engines": coerce_bool(cfg.get("cache_engines"), True)}


# --------------------------------------------------------------------------------------------- availability

def _has_module(name: str) -> bool:
    from importlib.util import find_spec
    try:
        return find_spec(name) is not None
    except (ImportError, ValueError):
        return False


def inductor_problem() -> Optional[str]:
    """None when torch.compile(Inductor) can run on CUDA here, else the reason."""
    if not _has_module("triton"):
        return ("the Inductor backend needs Triton (pip install triton-windows on Windows, triton on Linux)")
    if sys.platform == "win32" and shutil.which("cl") is None:
        return ("the Inductor backend needs the MSVC compiler on PATH: start the app from a 'x64 Native Tools "
                "Command Prompt for VS' (cl.exe not found)")
    if sys.platform != "win32" and not (shutil.which("gcc") or shutil.which("cc") or shutil.which("clang")):
        return "the Inductor backend needs a C compiler (gcc / clang) on PATH"
    return None


def _tensorrt_import_shim() -> None:
    """tensorrt 10.9's wheel keeps the plugin API in tensorrt_bindings; Torch-TensorRT 2.7 imports tensorrt.plugin."""
    try:
        import tensorrt  # type: ignore
    except Exception:
        return
    if "tensorrt.plugin" in sys.modules or hasattr(tensorrt, "plugin"):
        return
    try:
        import tensorrt_bindings.plugin as plug  # type: ignore
    except Exception:
        return
    sys.modules["tensorrt.plugin"] = plug
    tensorrt.plugin = plug  # type: ignore[attr-defined]


def tensorrt_problem() -> Optional[str]:
    if not _has_module("torch_tensorrt") or not _has_module("tensorrt"):
        return ("the TensorRT backend needs Torch-TensorRT and TensorRT "
                "(pip install torch-tensorrt tensorrt-cu12 matching your torch build)")
    return None


def available() -> list[str]:
    """Backends that can run here (dependencies importable); used by the engine hello."""
    out = []
    if inductor_problem() is None:
        out.append("inductor")
    if tensorrt_problem() is None:
        out.append("tensorrt")
    return out


def cache_dir() -> str:
    base = os.environ.get("SDFX_TRT_CACHE") or os.path.join(os.path.expanduser("~"), ".cache",
                                                           "stablediffusion-nui", "tensorrt")
    os.makedirs(base, exist_ok=True)
    return base


# --------------------------------------------------------------------------------------------- compilation

def _build(unet, cfg: dict, dtype) -> Callable:
    import torch
    original = unet.forward
    if cfg["backend"] == "inductor":
        import torch._dynamo
        torch._dynamo.config.cache_size_limit = max(torch._dynamo.config.cache_size_limit, 32)
        return torch.compile(original, backend="inductor", mode=None if cfg["mode"] == "default" else cfg["mode"],
                             dynamic=False, fullgraph=False)
    _tensorrt_import_shim()
    import torch_tensorrt  # type: ignore  # noqa: F401  (registers the backend)
    import torch._dynamo
    torch._dynamo.config.cache_size_limit = max(torch._dynamo.config.cache_size_limit, 32)
    precision = torch.float16 if dtype == torch.float16 else (torch.bfloat16 if dtype == torch.bfloat16 else torch.float32)
    options = {"enabled_precisions": {precision}, "min_block_size": cfg["min_block_size"],
               "optimization_level": cfg["optimization_level"], "truncate_double": True,
               "use_fast_partitioner": True, "immutable_weights": True}
    if cfg["cache_engines"]:
        options.update(cache_built_engines=True, reuse_cached_engines=True, engine_cache_dir=cache_dir())
    extra = os.environ.get("SDFX_TRT_OPTIONS", "").strip()
    if extra:  # advanced escape hatch: a JSON object of further torch_tensorrt.compile options
        import json
        try:
            options.update(json.loads(extra))
        except ValueError as exc:
            raise EngineError(INVALID_PARAMS, f"SDFX_TRT_OPTIONS is not valid JSON: {exc}")
    return torch.compile(original, backend="torch_tensorrt", options=options, dynamic=False)


def check_job(engine, gp, *, controlnet: bool, loras: bool, composable: bool, cfg: dict) -> None:
    """Raise EngineError for generations the compiled U-Net cannot serve correctly."""
    if not engine.cuda:
        raise EngineError(UNSUPPORTED, "Compile needs a CUDA GPU")
    if engine._offload:
        raise EngineError(UNSUPPORTED, "Compile needs the 'gpu' VRAM mode (offload modes move the U-Net)")
    others = sorted(k for k in (gp.hooks or {}) if k not in COMPATIBLE_HOOKS and hook_config(gp.hooks, k, {}) is not None)
    if others:
        raise EngineError(UNSUPPORTED, "Compile cannot combine with hooks that patch the U-Net or the sampler loop: "
                          + ", ".join(others))
    if controlnet:
        raise EngineError(UNSUPPORTED, "Compile does not support ControlNet units")
    if composable:
        raise EngineError(UNSUPPORTED, "Compile does not support Composable LoRA")
    if loras and cfg["backend"] == "tensorrt":
        raise EngineError(UNSUPPORTED, "TensorRT engines bake the weights in: LoRAs are not supported "
                          "(use the Inductor backend, or merge the LoRA into a checkpoint)")
    problem = inductor_problem() if cfg["backend"] == "inductor" else tensorrt_problem()
    if problem:
        raise EngineError(UNSUPPORTED, f"Compile: {problem}")


def install(engine, pipe, gp, ctx, cleanups: list, *, controlnet: bool, pass_: str, shape: tuple) -> Optional[dict]:
    """Swap the U-Net forward for a compiled one for this pass. Returns the effective config (or None when off)."""
    raw = hook_config(gp.hooks or {}, "compile", DEFAULTS)
    if raw is None:
        return None
    cfg = normalize(raw)
    check_job(engine, gp, controlnet=controlnet, loras=bool(gp.loras),
              composable=hook_config(gp.hooks or {}, "composable_lora", {}) is not None, cfg=cfg)
    unet = pipe.unet
    if "forward" in unet.__dict__:
        raise EngineError(UNSUPPORTED, "Compile: the U-Net forward is already patched by another feature")
    import torch
    key = (cfg["backend"], cfg["mode"], cfg["min_block_size"], cfg["optimization_level"], cfg["cache_engines"],
           str(engine.dtype))
    store = unet.__dict__.setdefault("_sdfx_compiled", {})
    fresh = key not in store
    if fresh:
        store[key] = {"fn": _build(unet, cfg, engine.dtype), "shapes": set()}
    entry = store[key]
    new_shape = shape not in entry["shapes"]
    if new_shape:
        ctx.warn(f"Compile ({cfg['backend']}): building for {shape[0]}x{shape[1]} batch {shape[2]}; "
                 + ("this can take several minutes the first time" if cfg["backend"] == "tensorrt"
                    else "the first step compiles"))
    entry["shapes"].add(shape)
    unet.forward = entry["fn"]

    def restore():
        unet.__dict__.pop("forward", None)

    cleanups.append(restore)
    log.info("Compile: %s %s shape=%s (%s)", cfg["backend"], cfg["mode"], shape, "new" if new_shape else "reused")
    return cfg


def drop(unet) -> None:
    """Forget compiled forwards of a U-Net (called when the model is unloaded)."""
    unet.__dict__.pop("_sdfx_compiled", None)
    unet.__dict__.pop("forward", None)
