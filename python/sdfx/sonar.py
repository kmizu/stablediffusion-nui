"""Sonar, re-implemented from the behaviour of Kahsolt/stable-diffusion-webui-sonar 15f9285 (MIT).

The source wraps k-diffusion's `Euler a`, `Euler` and a simple `Naive` sampler:

* momentum on the latent difference (the ODE derivative `d`) with a decaying history of earlier differences,
  in the "pos" (affirm the history), "neg" (deny it) or "rand" direction, and three ways to start the history
  (`zero`, the initial noise, a new Gaussian noise);
* for `Naive` only, a "shallow fusion" with a reference image: after a step the latent is pulled towards the
  (per channel normalised) VAE latent of the image, by a linear blend or by an Euler step, inside a step range;
* an optional final upscale of the saved image by ratio or target size.

Here the same update rules are applied inside the diffusers `EulerAncestralDiscreteScheduler` /
`EulerDiscreteScheduler` step (a re-implementation of their step arithmetic, which is bit-identical to diffusers
when the momentum is 1.0 and nothing else is requested). While the hook is active the sampler named in the settings
replaces the sampler of the request, as the source's sampler replacement does; `Naive` runs on the Euler scheduler
(it is Euler plus a 1e-5 noise after each step and the reference guidance).

Kept source behaviour: the history `zero` start makes the first step `momentum * d`; the reference strength is the
slider value divided by 10; step bounds above 1 are step numbers and 1 or below fractions of the request's steps
(inclusive on both ends); the Euler guidance step uses the already stepped latent with the old sigma; the reference
normalisation uses the unbiased standard deviation.

Differences: the source's random choices (`rand` direction, `rand_new` history) use the global RNGs; here they come
from a generator seeded by the pass seed so replays are exact. The reference latent is the mean of the VAE posterior
instead of a sample. The source's grid-search script is covered by X/Y/Z axes.
"""
from __future__ import annotations

import functools
import math
import os
import sys
from typing import Optional

from .protocol import EngineError, INVALID_PARAMS, UNSUPPORTED

SAMPLERS = ("Euler a", "Euler", "Naive")
SIGNS = ("pos", "neg", "rand")
HISTORY_INITS = ("zero", "rand_init", "rand_new")
REF_METHODS = ("linear", "euler")
NAIVE_NOISE = 1e-5
REF_STRENGTH_DIVISOR = 10.0

DEFAULTS = {
    "sampler": "Euler a", "momentum": 0.95, "momentum_hist": 0.75, "momentum_hist_init": "zero",
    "momentum_sign": "pos", "ref_img": "", "ref_meth": "linear", "ref_hgf": 0.2, "ref_min_step": 0.0,
    "ref_max_step": 0.75, "upscale": False, "upscaler": "Lanczos", "upscale_ratio": 1.0, "upscale_width": 0,
    "upscale_height": 0,
}
_NUMBERS = ("momentum", "momentum_hist", "ref_hgf", "ref_min_step", "ref_max_step", "upscale_ratio")
_INTS = ("upscale_width", "upscale_height")


def normalize(config: dict) -> dict:
    from .hooks import coerce_bool
    c = dict(DEFAULTS)
    c.update({k: v for k, v in (config or {}).items() if k in DEFAULTS and v is not None})
    out: dict = {}
    try:
        for k in DEFAULTS:
            if k == "upscale":
                out[k] = coerce_bool(c[k], False)
            elif k in _NUMBERS:
                out[k] = float(c[k])
                if not math.isfinite(out[k]):
                    raise ValueError(f"{k} must be a finite number")
            elif k in _INTS:
                v = float(c[k])
                if not math.isfinite(v) or v != int(v):
                    raise ValueError(f"{k} must be a whole number")
                out[k] = int(v)
            else:
                out[k] = str(c[k])
    except (TypeError, ValueError) as e:
        raise EngineError(INVALID_PARAMS, f"Sonar: invalid setting ({e})")
    for key, allowed in (("sampler", SAMPLERS), ("momentum_sign", SIGNS), ("momentum_hist_init", HISTORY_INITS),
                         ("ref_meth", REF_METHODS)):
        if out[key] not in allowed:
            raise EngineError(INVALID_PARAMS, f"Sonar: unknown {key.replace('_', ' ')} '{out[key]}' "
                                              f"(use {', '.join(allowed)})")
    for key in ("momentum", "momentum_hist", "ref_hgf"):
        if not 0.0 <= out[key] <= 1.0:
            raise EngineError(INVALID_PARAMS, f"Sonar: {key.replace('_', ' ')} must be between 0 and 1")
    if out["ref_min_step"] < 0 or out["ref_max_step"] < 0:
        raise EngineError(INVALID_PARAMS, "Sonar: the reference step bounds must be >= 0")
    if out["upscale_ratio"] < 1.0 or out["upscale_width"] < 0 or out["upscale_height"] < 0:
        raise EngineError(INVALID_PARAMS, "Sonar: the upscale ratio must be >= 1 and the size >= 0")
    return out


def effective_sampler(hooks: Optional[dict], sampler: str) -> str:
    """The sampler a request really runs with: Sonar's own sampler replaces it while the hook is active."""
    from .hooks import hook_config
    c = hook_config(hooks or {}, "sonar", DEFAULTS)
    if c is None:
        return sampler
    return "Euler" if normalize(c)["sampler"] == "Naive" else normalize(c)["sampler"]


# -- update rules -----------------------------------------------------------------------------------------------------
def momentum_step(x, d, dt, momentum: float, momentum_hist: float, sign: str, history):
    """One Euler step along the momentum-corrected derivative. `history` None is the source's "zero" start.

    Returns (x, history)."""
    p = 1.0 - momentum
    if sign == "pos":
        corrected = (1.0 - p) * d + p * (0 if history is None else history)
    elif sign == "neg":
        corrected = (1.0 + p) * d - p * (0 if history is None else history)
    else:
        raise ValueError(f"unknown momentum sign {sign}")
    x = x + corrected * dt
    q = 1.0 - momentum_hist
    if history is None:
        history = corrected
    elif sign == "pos":
        history = (1.0 - q) * history + q * corrected
    else:
        history = (1.0 + q) * history - q * corrected
    return x, history


def normalize_reference(latent):
    """Per channel zero mean / unit (unbiased) deviation over the spatial dimensions."""
    mean = latent.mean(dim=[2, 3], keepdim=True)
    std = latent.std(dim=[2, 3], keepdim=True)
    return (latent - mean) / std


def guidance_step(x, method: str, reference, strength: float, denoised, sigma, sigma_next):
    """Pulls the stepped latent `x` towards the normalised reference latent (the source's `guidance_step`)."""
    if method == "euler":
        mean = denoised.mean(dim=[1, 2, 3], keepdim=True)
        std = denoised.std(dim=[1, 2, 3], keepdim=True)
        shifted = reference * std + mean
        d = (x - shifted) / sigma
        return x + d * ((sigma_next - sigma) * strength)
    mean = x.mean(dim=[1, 2, 3], keepdim=True)
    std = x.std(dim=[1, 2, 3], keepdim=True)
    shifted = reference * std + mean
    return (1 - strength) * x + strength * shifted


def step_bound(value: float, steps: int) -> int:
    """A bound above 1 is a step number, otherwise a fraction of the request's steps."""
    return int(value) if value > 1 else round(value * steps)


class Stepper:
    """State of one sampling pass."""

    def __init__(self, c: dict, *, steps: int, seed: int, ancestral: bool, reference=None):
        import torch
        self.c = c
        self.ancestral = ancestral
        self.naive = c["sampler"] == "Naive"
        self.reference = reference
        self.calls = 0
        self.history = None
        self.started = False
        self.min_step = step_bound(c["ref_min_step"], steps)
        self.max_step = step_bound(c["ref_max_step"], steps)
        self.strength = c["ref_hgf"] / REF_STRENGTH_DIVISOR
        self.generator = torch.Generator("cpu").manual_seed(int(seed) & 0xFFFFFFFFFFFF)

    def noise(self, like):
        import torch
        return torch.randn(like.shape, generator=self.generator, dtype=torch.float32).to(like.device)

    def start(self, x):
        init = self.c["momentum_hist_init"]
        self.history = {"zero": None, "rand_init": x, "rand_new": None}[init]
        if init == "rand_new":
            self.history = self.noise(x)
        self.started = True

    def direction(self) -> str:
        import torch
        sign = self.c["momentum_sign"]
        if sign == "rand":
            return "pos" if float(torch.rand(1, generator=self.generator)) < 0.5 else "neg"
        return sign

    def move(self, x, d, dt):
        if self.c["momentum"] < 1.0:
            x, self.history = momentum_step(x, d, dt, self.c["momentum"], self.c["momentum_hist"], self.direction(),
                                            self.history)
            return x
        return x + d * dt

    def guide(self, x, denoised, sigma, sigma_next):
        i = self.calls
        if (self.reference is not None and self.naive and self.strength and self.min_step <= i <= self.max_step):
            ref = self.reference
            if ref.shape[0] != x.shape[0]:
                ref = ref.expand(x.shape[0], -1, -1, -1)
            x = guidance_step(x, self.c["ref_meth"], ref.to(x.device, x.dtype), self.strength, denoised, sigma,
                              sigma_next)
        return x


def _output_class(sched):
    module = sys.modules[type(sched).__module__]
    return getattr(module, type(sched).__name__ + "Output")


def wrap_scheduler(sched, stepper: Stepper):
    """Replaces `sched.step` with the Sonar step; returns the function that restores it."""
    import torch
    from diffusers.utils.torch_utils import randn_tensor
    original = sched.step
    output_class = _output_class(sched)
    ancestral = stepper.ancestral
    prediction = sched.config.prediction_type

    @functools.wraps(original)
    def step(model_output, timestep, sample, *args, generator=None, return_dict=True, **kwargs):
        if kwargs.get("s_churn"):
            raise EngineError(UNSUPPORTED, "Sonar does not support Euler stochastic churn")
        if sched.step_index is None:
            sched._init_step_index(timestep)
        index = sched.step_index
        sigma = sched.sigmas[index]
        sigma_next = sched.sigmas[index + 1]
        x = sample.to(torch.float32)
        if prediction == "epsilon":
            denoised = x - sigma * model_output
        elif prediction == "v_prediction":
            denoised = model_output * (-sigma / (sigma ** 2 + 1) ** 0.5) + (x / (sigma ** 2 + 1))
        elif prediction in ("sample", "original_sample") and not ancestral:
            denoised = model_output
        else:
            raise EngineError(UNSUPPORTED, f"Sonar does not support prediction type '{prediction}'")
        if not stepper.started:
            stepper.start(x)
        d = (x - denoised) / sigma
        if ancestral:
            up = (sigma_next ** 2 * (sigma ** 2 - sigma_next ** 2) / sigma ** 2) ** 0.5
            down = (sigma_next ** 2 - up ** 2) ** 0.5
            nxt = stepper.move(x, d, down - sigma)
            noise = randn_tensor(model_output.shape, dtype=model_output.dtype, device=model_output.device,
                                 generator=generator)
            nxt = nxt + noise * up
        else:
            nxt = stepper.move(x, d, sigma_next - sigma)
            nxt = stepper.guide(nxt, denoised, sigma, sigma_next)
            if stepper.naive:
                nxt = nxt + stepper.noise(nxt) * NAIVE_NOISE
        stepper.calls += 1
        nxt = nxt.to(model_output.dtype)
        sched._step_index += 1
        if not return_dict:
            return (nxt, denoised)
        return output_class(prev_sample=nxt, pred_original_sample=denoised)

    sched.step = step

    def restore():
        sched.__dict__.pop("step", None)
    return restore


def validate(config: dict, gp, req, engine=None) -> dict:
    """Request-time checks (before a model runs)."""
    c = normalize(config)
    if c["ref_img"] and not os.path.isfile(c["ref_img"]):
        raise EngineError(INVALID_PARAMS, f"Sonar: reference image not found: {c['ref_img']}")
    if c["upscale"] and c["upscaler"] != "None":
        from .base import PIXEL_UPSCALERS
        if c["upscaler"] not in PIXEL_UPSCALERS and engine is not None:
            engine.check_model_upscaler(c["upscaler"], req.upscaler_paths)
    return c


def install(engine, pipe, gp, kw: dict, ctx, config: dict, *, pass_: str, seed: int, sched, w: int, h: int,
            cleanups: list, others: Optional[dict] = None) -> None:
    """Hooks one pass; appends its cleanup to `cleanups` before touching the scheduler."""
    c = normalize(config)
    name = type(sched).__name__
    if name not in ("EulerDiscreteScheduler", "EulerAncestralDiscreteScheduler"):
        raise EngineError(UNSUPPORTED, f"Sonar needs the Euler or Euler a sampler, not {name}")
    conflicts = [n for n, on in (others or {}).items() if on]
    if conflicts:
        raise EngineError(UNSUPPORTED, "Sonar cannot combine with " + ", ".join(conflicts) +
                                       ": they replace or wrap the scheduler step as well")
    if c["sampler"] == "Euler a" and name != "EulerAncestralDiscreteScheduler" or \
            c["sampler"] != "Euler a" and name != "EulerDiscreteScheduler":
        raise EngineError(UNSUPPORTED, f"Sonar sampler '{c['sampler']}' does not match the scheduler {name}")
    reference = None
    if c["ref_img"]:
        if c["sampler"] != "Naive":
            if ctx is not None and pass_ == "base":
                ctx.warn("Sonar: the reference image only applies to the Naive sampler; ignored")
        else:
            from .extensions import encode_image
            from .images import load_rgb, resize_image
            image = resize_image(1, load_rgb(c["ref_img"]), w, h)
            import torch
            reference = normalize_reference(encode_image(engine, pipe, image).to(torch.float32))
    steps = int(getattr(gp, "steps", 0) or kw.get("num_inference_steps") or 1)
    stepper = Stepper(c, steps=steps, seed=seed, ancestral=name == "EulerAncestralDiscreteScheduler",
                      reference=reference)
    cleanups.append(wrap_scheduler(sched, stepper))


# -- final upscale ---------------------------------------------------------------------------------------------------
def upscale_target(width: int, height: int, c: dict) -> Optional[tuple]:
    """(target width, target height, cover) of the source's `get_upscale_resolution`, or None."""
    if not c["upscale"] or c["upscaler"] == "None":
        return None
    w, h = c["upscale_width"], c["upscale_height"]
    if w == 0 and h == 0:
        if c["upscale_ratio"] == 1.0:
            return None
        return round(width * c["upscale_ratio"]), round(height * c["upscale_ratio"]), False
    if w == 0:
        w = round(width * h / height)
    if h == 0:
        h = round(height * w / width)
    return w, h, True


def upscale_image(engine, im, c: dict, gp, req, ctx):
    """Upscales a finished image like the source's save hook (cover the target, then centre crop)."""
    target = upscale_target(gp.width, gp.height, c)
    if target is None:
        return im
    from . import upscalers
    from .params import center_crop_box
    tw, th, _ = target
    scale = max(tw / im.width, th / im.height)
    cw, ch = (tw, th) if scale <= 0 or (round(im.width * scale) == tw and round(im.height * scale) == th) \
        else (max(tw, math.ceil(im.width * scale)), max(th, math.ceil(im.height * scale)))
    up = upscalers.upscale_to(im, cw, ch, c["upscaler"], req.upscaler_paths.get(c["upscaler"]), engine.device,
                              engine.dtype, ctx.check_interrupt)
    if up.size != (tw, th):
        up = up.crop(center_crop_box(up.width, up.height, tw, th))
    return up


__all__ = ["DEFAULTS", "SAMPLERS", "normalize", "effective_sampler", "momentum_step", "normalize_reference",
           "guidance_step", "step_bound", "Stepper", "wrap_scheduler", "validate", "install", "upscale_target",
           "upscale_image"]
