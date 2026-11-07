"""DeepCache for the U-Net: reuse the deep features between neighbouring denoising steps.

Hook `deepcache` in params.hooks:

  {"deepcache": {"interval": 3, "branch": 0, "full_first": 5}}

Written from the method of the DeepCache paper (Ma et al., CVPR 2024: the high-level features of consecutive steps
are very similar, so only the shallow branch needs recomputing); no code of the reference implementation is used.

The first `full_first` calls are full steps (they decide the composition; caching them changes the picture), then every
`interval`-th U-Net call is a full step that also records the input of the up block `branch` levels from the
end. The calls in between run only the first `branch + 1` down blocks and the last `branch + 1` up blocks and feed the
recorded features to the latter, so the middle of the U-Net (the expensive low-resolution attention) is skipped.

The skipped blocks are replaced for the length of one generation pass by shims that hand back placeholder tensors of
the right shape, which keeps diffusers' own forward (time embedding, LoRA scaling, add-conditions) untouched.
The result is an approximation: docs/optimization-verification.md records the image difference and the speed-up.
Anything that adds a residual to the skipped part (ControlNet, T2I adapters) or calls the U-Net more than once per
step is refused with a clear message.
"""
from __future__ import annotations

import logging
import math
from typing import Any, Optional

from .hooks import hook_config
from .protocol import INVALID_PARAMS, UNSUPPORTED, EngineError

log = logging.getLogger("sdfx.deepcache")

DEFAULTS: dict = {"interval": 3, "branch": 0, "full_first": 5}
MAX_INTERVAL = 10

# Hooks that leave the U-Net call pattern and the skipped blocks alone.
COMPATIBLE_HOOKS = frozenset({
    "deepcache", "model_keyword", "pnginfo_injection", "steps_animation", "gpu_temperature_guard", "nsfw_checker",
    "aesthetic_score", "vae_tools", "tokenizer", "token_downsampling", "save_intermediate", "hires_fix_tweaks",
    "img2img_hires_fix", "latent_upscale", "conditioning_highres_fix",
})


def normalize(cfg: dict) -> dict:
    def integer(name: str) -> int:
        raw = cfg.get(name, DEFAULTS[name])
        try:
            if isinstance(raw, bool):
                raise ValueError()
            value = float(raw)
            if not math.isfinite(value) or not value.is_integer():
                raise ValueError()
            return int(value)
        except (TypeError, ValueError, OverflowError):
            raise EngineError(INVALID_PARAMS, f"DeepCache {name} must be integers")

    interval = integer("interval")
    branch = integer("branch")
    if not 2 <= interval <= MAX_INTERVAL:
        raise EngineError(INVALID_PARAMS, f"DeepCache interval must be 2..{MAX_INTERVAL} (got {interval})")
    if not 0 <= branch <= 3:
        raise EngineError(INVALID_PARAMS, f"DeepCache branch must be 0..3 (got {branch})")
    first = integer("full_first")
    if not 0 <= first <= 50:
        raise EngineError(INVALID_PARAMS, f"DeepCache full_first must be 0..50 (got {first})")
    return {"interval": interval, "branch": branch, "full_first": first}


class DeepCache:
    """Per-pass state: call counter, recorded features and the module shims."""

    def __init__(self, unet, interval: int, branch: int, full_first: int = 0):
        down, up = len(unet.down_blocks), len(unet.up_blocks)
        if branch > down - 2:
            raise EngineError(INVALID_PARAMS, f"DeepCache branch {branch} is too deep for this U-Net ({down} blocks)")
        self.unet, self.interval, self.branch, self.full_first = unet, interval, branch, full_first
        self.up_keep = up - 1 - branch           # index of the first up block that always runs
        self.calls = 0
        self.cached = False                      # this call reuses the recorded features
        self.feature = None                      # input of up_blocks[up_keep] from the last full step
        self.feature_key = None
        self.meta: dict = {}                     # down block index -> (sample meta, [res metas]) of a full step
        self.full_calls = 0
        self.cached_calls = 0
        self._handles: list = []
        self._shimmed: list = []

    # ---- placeholders -------------------------------------------------------------------------------------------
    @staticmethod
    def _meta(t):
        return (tuple(t.shape), t.dtype, t.device)

    @staticmethod
    def _empty(meta):
        import torch
        shape, dtype, device = meta
        return torch.empty(shape, dtype=dtype, device=device)

    # ---- hooks ----------------------------------------------------------------------------------------------------
    def install(self) -> None:
        unet = self.unet
        try:
            self._handles.append(unet.register_forward_pre_hook(self._begin, with_kwargs=True))
            self._handles.append(unet.up_blocks[self.up_keep].register_forward_pre_hook(self._record_feature, with_kwargs=True))
            for i in range(self.branch + 1, len(unet.down_blocks)):
                self._shim(unet.down_blocks[i], lambda orig, i=i: self._down(orig, i))
            if unet.mid_block is not None:
                self._shim(unet.mid_block, self._mid)
            for i in range(self.up_keep):
                self._shim(unet.up_blocks[i], self._up)
        except BaseException:
            self.remove()
            raise

    def remove(self) -> None:
        for h in self._handles:
            h.remove()
        self._handles.clear()
        for mod, had_own, own in reversed(self._shimmed):
            if had_own:
                mod.forward = own
            else:
                mod.__dict__.pop("forward", None)
        self._shimmed.clear()
        self.feature = None
        self.meta.clear()

    def _shim(self, module, make) -> None:
        original = module.forward
        had_own = "forward" in module.__dict__
        own = module.__dict__.get("forward")
        module.forward = make(original)
        self._shimmed.append((module, had_own, own))

    def _begin(self, module, args, kwargs):
        sample = kwargs.get("sample") if "sample" in kwargs else (args[0] if args else None)
        key = None if sample is None else (tuple(sample.shape), sample.dtype)
        full = self.calls < self.full_first or (self.calls - self.full_first) % self.interval == 0 or self.feature is None or key != self.feature_key
        self.cached = not full
        if full:
            self.feature_key = key
            self.full_calls += 1
        else:
            self.cached_calls += 1
        self.calls += 1

    def _record_feature(self, module, args, kwargs):
        if not self.cached:
            h = kwargs.get("hidden_states", args[0] if args else None)
            self.feature = h

    def _down(self, original, i):
        def run(*args, **kwargs):
            if not self.cached:
                sample, res = original(*args, **kwargs)
                self.meta[i] = (self._meta(sample), [self._meta(r) for r in res])
                return sample, res
            sample_meta, res_meta = self.meta[i]
            return self._empty(sample_meta), tuple(self._empty(m) for m in res_meta)
        return run

    def _mid(self, original):
        def run(*args, **kwargs):
            return original(*args, **kwargs) if not self.cached else self.feature
        return run

    def _up(self, original):
        def run(*args, **kwargs):
            return original(*args, **kwargs) if not self.cached else self.feature
        return run


def check_job(engine, gp, *, controlnet: bool, neutral: bool, ip_adapter: bool = False) -> None:
    if controlnet:
        raise EngineError(UNSUPPORTED, "DeepCache does not support ControlNet units (their residuals reach the skipped blocks)")
    if neutral:
        raise EngineError(UNSUPPORTED, "DeepCache does not support prompt-branch features (Neutral Prompt / Composable LoRA)")
    if engine._offload:
        raise EngineError(UNSUPPORTED, "DeepCache needs the 'gpu' VRAM mode (offload hooks run on the skipped blocks)")
    others = sorted(k for k in (gp.hooks or {}) if k not in COMPATIBLE_HOOKS and hook_config(gp.hooks, k, {}) is not None)
    if others:
        raise EngineError(UNSUPPORTED, "DeepCache cannot combine with hooks that patch the U-Net or the sampler loop: "
                          + ", ".join(others))


def install(engine, pipe, gp, ctx, cleanups: list, *, controlnet: bool, neutral: bool, pass_: str) -> Optional[dict]:
    """Install DeepCache on the U-Net for this pass. Returns the effective config (None when the hook is off)."""
    raw = hook_config(gp.hooks or {}, "deepcache", DEFAULTS)
    if raw is None:
        return None
    cfg = normalize(raw)
    check_job(engine, gp, controlnet=controlnet, neutral=neutral)
    unet = pipe.unet
    if "forward" in unet.__dict__:
        raise EngineError(UNSUPPORTED, "DeepCache: the U-Net forward is already patched by another feature")
    if not hasattr(unet, "down_blocks") or not hasattr(unet, "up_blocks"):
        raise EngineError(UNSUPPORTED, "DeepCache supports the SD1.5 / SDXL U-Net only")
    state = DeepCache(unet, cfg["interval"], cfg["branch"], cfg["full_first"])
    state.install()
    cleanups.append(state.remove)
    engine._deepcache_last = state
    log.info("DeepCache: interval %d branch %d (%s pass)", cfg["interval"], cfg["branch"], pass_)
    return cfg
