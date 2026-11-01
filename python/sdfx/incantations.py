"""Incantations, re-implemented from v0xie/sd-webui-incantations 980d638 (GPL-3.0) behaviour.

One hook, `incantations`, carries the settings of the source's submodules (field names are the source's `p.*`
attribute names):

  seg_*            Smoothed Energy Guidance. Blurs the query of the middle block self-attention (`attn1` of every
                   middle transformer block) with a Gaussian of sigma 2**seg_blur_sigma (above 10.5: replaced by
                   its spatial mean). The source splits the batch into halves and blurs the second one, which in
                   A1111's [cond, uncond] layout is the unconditional half, so CFG then guides away from a smoothed
                   unconditional prediction. Sigma 0 disables it.
  scfg_*           S-CFG. From the head-averaged query/key projections of the first image's conditional row in
                   every attention layer it builds per-token cross-attention masks (softmax(q k^T) without scaling,
                   self-attention with q as key, a power series of R terms, R smallest resolutions) and rescales
                   the CFG difference per region; all as the source's get_mask / scfg_combine_denoised.
  pag_*, cfg_interval_*
                   Perturbed Attention Guidance: an extra model evaluation of the conditional rows in which the
                   first middle transformer block's self-attention returns its value projection unchanged
                   (identity attention without to_out, like the source's forward hook), added as
                   pag_scale * (cond - perturbed); optionally mixed with CFG by Saliency-Adaptive Noise Fusion.
                   The CFG Scheduler replaces the CFG scale with one of 17 schedules inside a step-aligned noise
                   interval of a rho=3 Karras ladder (0.002..80), and with 1.0 outside it; Constant keeps the
                   scale everywhere.
  t2i0_*           Multi-Concept T2I-Zero: Correction by Similarities on the positive text conditioning and
                   Cross-Token Non-Maximum Suppression on every cross-attention output, with optional EMA.

Every guidance term is combined in the denoised (x0) space as the source's CFG combiner does in A1111, then
written back as the conditional half of the noise prediction so diffusers' `u + g (c - u)` gives the result.

Kept source behaviour: steps are A1111's `state.sampling_step`, which lags the model evaluation by one (0, 0, 1,
..., N-2); CTNMS counts its own module calls (PAG's extra evaluation included) and keeps counting into the hires
pass; the schedules and the noise interval use the base pass steps and CFG; `Interval` compares the step index
with 0.25..5.42; the PAG evaluation uses the conditioning from before Correction by Similarities.
"""
from __future__ import annotations

import copy
import math
from typing import Any, Callable, Optional

from .protocol import EngineError, INVALID_PARAMS, UNSUPPORTED

SCHEDULES = ("Constant", "Clamp-Linear (c=4.0)", "Clamp-Linear (c=2.0)", "Clamp-Linear (c=1.0)", "Linear",
             "Inverse-Linear", "Cosine", "Clamp-Cosine (c=4.0)", "Clamp-Cosine (c=2.0)", "Clamp-Cosine (c=1.0)",
             "Sine", "Interval", "PCS (s=0.01)", "PCS (s=0.1)", "PCS (s=1.0)", "PCS (s=2.0)", "PCS (s=4.0)")

DEFAULTS = {
    "seg_active": False, "seg_blur_sigma": 11.0, "seg_start_step": 0, "seg_end_step": 150,
    "scfg_active": False, "scfg_scale": 1.0, "scfg_rate_min": 0.8, "scfg_rate_max": 3.0, "scfg_rate_clamp": 0.0,
    "scfg_start_step": 0, "scfg_end_step": 150, "scfg_r": 4,
    "pag_active": False, "pag_sanf": False, "pag_scale": 0.0, "pag_start_step": 0, "pag_end_step": 150,
    "cfg_interval_enable": False, "cfg_interval_schedule": "Constant", "cfg_interval_low": 0.0,
    "cfg_interval_high": 100.0,
    "t2i0_active": False, "t2i0_step_start": 1, "t2i0_step_end": 25, "t2i0_tokens": "", "t2i0_window_size": 2,
    "t2i0_correction_threshold": 0.5, "t2i0_correction_strength": 0.0, "t2i0_ctnms_alpha": 0.1,
    "t2i0_ema_factor": 0.0,
}
_BOOLS = ("seg_active", "scfg_active", "pag_active", "pag_sanf", "cfg_interval_enable", "t2i0_active")
_INTS = ("seg_start_step", "seg_end_step", "scfg_start_step", "scfg_end_step", "scfg_r", "pag_start_step",
         "pag_end_step", "t2i0_step_start", "t2i0_step_end", "t2i0_window_size")
_STRS = ("cfg_interval_schedule", "t2i0_tokens")

SEG_INF_THRESHOLD = 10.5
SIGMA_MIN, SIGMA_MAX, RHO = 0.002, 80.0, 3
PAG_LAYER = "mid_block.attentions.0.transformer_blocks.0.attn1"


def normalize(config: dict) -> dict:
    from .hooks import coerce_bool
    c = dict(DEFAULTS)
    c.update({k: v for k, v in (config or {}).items() if k in DEFAULTS and v is not None})
    out: dict = {}
    try:
        for k in DEFAULTS:
            if k in _BOOLS:
                out[k] = coerce_bool(c[k], False)
            elif k in _STRS:
                out[k] = str(c[k])
            elif k in _INTS:
                v = float(c[k])
                if not math.isfinite(v) or v != int(v):
                    raise ValueError(f"{k} must be a whole number")
                out[k] = int(v)
            else:
                out[k] = float(c[k])
                if not math.isfinite(out[k]):
                    raise ValueError(f"{k} must be a finite number")
    except (TypeError, ValueError) as e:
        raise EngineError(INVALID_PARAMS, f"Incantations: invalid setting ({e})")
    if out["cfg_interval_schedule"] not in SCHEDULES:
        raise EngineError(INVALID_PARAMS, f"Incantations: unknown CFG schedule '{out['cfg_interval_schedule']}'")
    if out["scfg_r"] < 1:
        raise EngineError(INVALID_PARAMS, "Incantations: SCFG R must be at least 1")
    if out["t2i0_window_size"] < 0:
        raise EngineError(INVALID_PARAMS, "Incantations: the T2I-0 window size must be >= 0")
    return out


def features(c: dict) -> dict:
    """Which submodules run, as the source decides in each process_batch."""
    return {"seg": c["seg_active"] and c["seg_blur_sigma"] != 0.0, "scfg": c["scfg_active"],
            "pag": c["pag_active"], "cfg_interval": c["cfg_interval_enable"], "t2i0": c["t2i0_active"]}


# -- CFG scheduler ----------------------------------------------------------------------------------------------
def noise_level(i: int, n: int, sigma_min: float = SIGMA_MIN, sigma_max: float = SIGMA_MAX, rho: int = RHO) -> float:
    """Noise level of step i on the source's Karras ladder of n steps."""
    if i == 0:
        return sigma_max
    if i >= n:
        return 0.0
    hi, lo = sigma_max ** (1 / rho), sigma_min ** (1 / rho)
    return (hi + (i / (n - 1)) * (lo - hi)) ** rho


def closest_index(level: float, n: int, tol: float = 1e-6) -> int:
    """Binary search for the ladder step nearest to `level` (n for levels at or below sigma_min)."""
    if level <= SIGMA_MIN:
        return n
    if level >= SIGMA_MAX:
        return 0
    low, high = 0, n - 1
    while low <= high:
        mid = (low + high) // 2
        value = noise_level(mid, n)
        if abs(value - level) < tol:
            return mid
        if value < level:
            high = mid - 1
        else:
            low = mid + 1
    return low if abs(noise_level(low, n) - level) < abs(noise_level(high, n) - level) else high


def _linear(step, steps, w0):
    return w0 * 2 * (1 - step / steps)


def _inverse_linear(step, steps, w0):
    return w0 * 2 * (step / steps)


def _cosine(step, steps, w0):
    return w0 * (1 + math.cos(math.pi * step / steps))


def schedule_value(name: str, step: int, steps: int, w0: float) -> float:
    """Guidance weight of schedule `name` at `step` of `steps` for base CFG w0."""
    if name == "Constant":
        return w0
    if name == "Linear":
        return _linear(step, steps, w0)
    if name.startswith("Clamp-Linear (c="):
        return max(float(name[16:-1]), _linear(step, steps, w0))
    if name == "Inverse-Linear":
        return _inverse_linear(step, steps, w0)
    if name.startswith("PCS (s="):
        s = float(name[7:-1])
        return w0 * ((1 - math.cos(math.pi * ((steps - step) / steps) ** s)) / 2.0)
    if name.startswith("Clamp-Cosine (c="):
        return max(float(name[16:-1]), _cosine(step, steps, w0))
    if name == "Cosine":
        return _cosine(step, steps, w0)
    if name == "Sine":
        return w0 * (math.sin((math.pi * step / steps) - (math.pi / 2)) + 1)
    if name == "Interval":
        # the source compares the step index (not the noise level) with 0.25..5.42
        return w0 if 0.25 <= step <= 5.42 else 1.0
    raise EngineError(INVALID_PARAMS, f"Incantations: unknown CFG schedule '{name}'")


class CfgInterval:
    """Scheduled CFG of the source's PAG callback; `n` and `w0` are the base pass steps and CFG."""

    def __init__(self, c: dict, n: int, w0: float):
        self.schedule, self.n, self.w0 = c["cfg_interval_schedule"], int(n), float(w0)
        self.low = noise_level(closest_index(c["cfg_interval_low"], self.n), self.n)
        self.high = noise_level(closest_index(c["cfg_interval_high"], self.n), self.n)

    def value(self, step: int) -> float:
        if self.schedule == "Constant":
            return self.w0
        begin, end = (self.low, self.high) if self.low <= self.high else (self.high, self.low)
        level = noise_level(step, self.n)
        scheduled = schedule_value(self.schedule, step, self.n, self.w0)
        return scheduled if begin <= level <= end else 1.0


# -- Gaussian helpers -------------------------------------------------------------------------------------------
def _kernel1d(size: int, sigma: float):
    import torch
    half = (size - 1) * 0.5
    x = torch.linspace(-half, half, steps=size)
    pdf = torch.exp(-0.5 * (x / sigma).pow(2))
    return pdf / pdf.sum()


def gaussian_blur(img, size: int = 3, sigma: float = 1.0):
    """torchvision GaussianBlur(size, sigma) on (..., C, H, W): reflect padding, depthwise conv in img dtype."""
    import torch
    import torch.nn.functional as F
    squeeze = img.dim() == 3
    x = img.unsqueeze(0) if squeeze else img
    k1 = _kernel1d(size, sigma).to(device=x.device, dtype=x.dtype)
    k2 = torch.mm(k1[:, None], k1[None, :]).expand(x.shape[-3], 1, size, size)
    x = F.conv2d(F.pad(x, [size // 2] * 4, mode="reflect"), k2, groups=x.shape[-3])
    return x.squeeze(0) if squeeze else x


def seg_kernel_size(sigma: float) -> int:
    return math.ceil(6 * sigma) + 1 - math.ceil(6 * sigma) % 2


def seg_blur_2d(img, size: int, sigma: float):
    """SEG Gaussian: the kernel is limited by the last (width) dimension, reflect padding."""
    import torch
    import torch.nn.functional as F
    width = img.shape[-1]
    size = min(size, width - (width % 2 - 1))
    k1 = _kernel1d(size, sigma).to(device=img.device, dtype=img.dtype)
    k2 = torch.mm(k1[:, None], k1[None, :]).expand(img.shape[-3], 1, size, size)
    return F.conv2d(F.pad(img, [size // 2] * 4, mode="reflect"), k2, groups=img.shape[-3])


def seg_query(q, heads: int, height: int, width: int, blur_sigma: float):
    """Blurred query rows (b, seq, inner) on the spatial grid of the image aspect height/width."""
    b, seq, inner = q.shape
    head_dim = inner // heads
    rows = int((seq * (height / width)) ** 0.5)
    cols = seq // rows if rows else 0
    if rows * cols != seq:
        raise EngineError(UNSUPPORTED, f"SEG cannot lay {seq} tokens out for a {width}x{height} image")
    x = q.reshape(b, seq, heads, head_dim).transpose(1, 2).permute(0, 1, 3, 2).reshape(b * heads, head_dim, rows, cols)
    sigma = 2 ** blur_sigma
    if blur_sigma > SEG_INF_THRESHOLD:
        x = x.mean(dim=(-2, -1), keepdim=True).expand_as(x)
    else:
        x = seg_blur_2d(x, seg_kernel_size(sigma), sigma)
    return x.reshape(b, heads, head_dim, rows * cols).reshape(b, heads * head_dim, seq).transpose(1, 2)


def scfg_kernel():
    """S-CFG's GaussianSmoothing(1 channel, 3, 0.5): exp(-((x - mean) / (2 sigma))**2), normalised."""
    import torch
    grids = torch.meshgrid([torch.arange(3, dtype=torch.float32)] * 2, indexing="ij")
    kernel = torch.ones(3, 3)
    for grid in grids:
        kernel = kernel * (1 / (0.5 * math.sqrt(2 * math.pi)) * torch.exp(-((grid - 1.0) / (2 * 0.5)) ** 2))
    return (kernel / kernel.sum()).view(1, 1, 3, 3)


# -- S-CFG --------------------------------------------------------------------------------------------------------
def head_mean(x, heads: int):
    """Head-averaged projection (b, s, d/heads) of a (b, s, d) query/key map."""
    b, s, d = x.shape
    per_head = x.reshape(b, s, heads, d // heads).permute(0, 2, 1, 3).reshape(b * heads, s, d // heads)
    return per_head.reshape(b, heads, s, d // heads).mean(1)


def attention_scores(q, k, dtype):
    import torch
    p = (q @ k.transpose(-1, -2)).to(torch.float32)
    p = torch.exp(p - torch.max(p))
    return (p / p.sum(dim=-1, keepdim=True, dtype=torch.float32)).to(dtype)


def scfg_masks(entries: list, r: int, latent_size, height: int, width: int):
    """(ca_mask, fore_mask) at latent_size // r * r from [(kind, q, k, heads)] in model order (q, k of one row)."""
    import torch
    import torch.nn.functional as F
    if not entries:
        raise EngineError(UNSUPPORTED, "S-CFG found no attention maps in this evaluation")
    kernel = scfg_kernel()
    maps: dict = {}
    sizes = set()
    for kind, q, k, heads in entries:
        qm = head_mean(q, heads)
        km = head_mean(k if k is not None else q, heads)
        sizes.add(qm.size(1))
        maps.setdefault((qm.size(1), kind), []).append(attention_scores(qm, km, qm.dtype))
    order = sorted(sizes)
    small = [latent_size[0] // r, latent_size[1] // r]
    new_ca, new_fore, total, done = 0, 0, 0, 0
    current = order.pop(0) if order else None
    while current is not None and done < r:
        if (current, "self") not in maps or (current, "cross") not in maps:
            if not order:
                raise EngineError(UNSUPPORTED, "S-CFG needs self- and cross-attention at every resolution")
            current, done = order.pop(0), done + 1
            continue
        sa = torch.cat(maps[(current, "self")])
        ca = torch.cat(maps[(current, "cross")])
        n = sa.size(0)
        if ca.size(0) != n:
            raise EngineError(UNSUPPORTED, "S-CFG needs as many self- as cross-attention layers per resolution")
        acc = sa.clone()
        power = acc
        for _ in range(1, r):
            power = power @ sa
            acc += power
        acc /= r
        ca = acc @ ca
        hw, tokens = ca.size(1), ca.size(2)
        rows = round((hw * (height / width)) ** 0.5)
        if rows == 0 or hw % rows:
            raise EngineError(UNSUPPORTED, f"S-CFG cannot lay {hw} tokens out for a {width}x{height} image")
        ca = ca.reshape(n, rows, hw // rows, tokens).permute(0, 3, 1, 2)
        ca = F.interpolate(ca, scale_factor=[small[0] / ca.shape[-2], small[1] / ca.shape[-1]], mode="bilinear")
        h2, w2 = ca.shape[-2:]
        ca = F.pad(ca.reshape(n * tokens, 1, h2, w2), (1, 1, 1, 1), mode="reflect")
        ca = F.conv2d(ca.float(), kernel.to(device=ca.device)).reshape(n, tokens, h2, w2)
        norm = ca / (ca.mean(dim=[2, 3], keepdim=True) + torch.finfo(ca.dtype).eps)
        new_ca = new_ca + norm.sum(0, keepdim=True)
        fore = torch.stack([ca[:, 0], ca[:, 1:].sum(dim=1)], dim=1)
        new_fore = new_fore + (fore / fore.mean(dim=[2, 3], keepdim=True)).sum(0, keepdim=True)
        total += n
        current = order.pop(0) if order else None
        done += 1
    new_ca = new_ca / total
    new_fore = new_fore / total
    ca_mask = (new_ca == new_ca.max(dim=1, keepdim=True)[0]).float()
    fore_mask = 1.0 - (new_fore == new_fore.max(dim=1, keepdim=True)[0]).float()[:, :1]
    return (F.interpolate(ca_mask, scale_factor=r, mode="nearest"),
            F.interpolate(fore_mask, scale_factor=r, mode="nearest"))


def scfg_rate(delta, cfg_scale: float, masks, c: dict):
    """Per-pixel rate (1, H, W) for one image's CFG difference (C, H, W), as scfg_combine_denoised."""
    import torch
    import torch.nn.functional as F
    mask_t, mask_fore = masks
    norm = delta.unsqueeze(0).norm(dim=1, keepdim=True)
    eps = lambda t: torch.finfo(t.dtype).eps  # noqa: E731
    if mask_t.shape[2:] != norm.shape[2:]:
        mask_t = F.interpolate(mask_t, size=norm.shape[2:], mode="bilinear")
    if mask_fore.shape[-2] != norm.shape[-2]:
        mask_fore = F.interpolate(mask_fore, size=norm.shape[2:], mode="bilinear")
    if mask_fore.shape[2:] != norm.shape[2:]:
        raise EngineError(UNSUPPORTED, "S-CFG: the latent width must be a multiple of SCFG R when the height is "
                                       "(the source fails here too)")
    down = (norm * mask_t).sum([2, 3]) / (mask_t.sum([2, 3]) + eps(mask_t))
    up = (norm * mask_fore).sum([2, 3]) / (mask_fore.sum([2, 3]) + eps(mask_fore))
    present = (mask_t.sum([2, 3]) > 0).float()
    rate = up * present / (down + eps(down))
    rate = (rate.unsqueeze(-1).unsqueeze(-1) * mask_t).sum(dim=1, keepdim=True)
    rate = torch.clamp(rate * c["scfg_scale"], min=c["scfg_rate_min"], max=c["scfg_rate_max"])
    if c["scfg_rate_clamp"] > 0:
        rate = torch.clamp_max(rate, c["scfg_rate_clamp"] / cfg_scale)
    rate = F.conv2d(F.pad(rate, (1, 1, 1, 1), mode="reflect"), scfg_kernel().to(rate.device, rate.dtype))
    return rate.squeeze(0)


# -- combination ----------------------------------------------------------------------------------------------------
def sanf(cfg_x, pag_x):
    """Saliency-Adaptive Noise Fusion: per element, CFG where its blurred saliency wins over PAG's, else PAG."""
    import torch
    soft_rt = torch.softmax(gaussian_blur(cfg_x.abs()), dim=0)
    soft_rs = torch.softmax(gaussian_blur(pag_x.abs()), dim=0)
    choice = torch.max(torch.stack([soft_rt, soft_rs], dim=0), dim=0)[1]
    m1 = torch.where(choice == 0, 1, 0)
    return cfg_x * m1 + pag_x * (1 - m1)


def combine(x0u, x0c, x0p, cfg_scale: float, *, pag_scale: float = 0.0, run_pag: bool = False,
            use_sanf: bool = False, rate: Optional[Callable] = None):
    """The source's CFG combiner on denoised predictions (B, C, H, W): CFG (x S-CFG rate), then PAG or SANF."""
    out = x0u.clone()
    for i in range(x0u.shape[0]):
        delta = x0c[i] - x0u[i]
        r = rate(delta, cfg_scale) if rate is not None else 1.0
        cfg_x = delta * r * (1.0 * cfg_scale)
        if not use_sanf or not run_pag:
            out[i] += cfg_x
        if run_pag:
            pag_x = (x0c[i] - x0p[i]) * (1.0 * pag_scale)
            out[i] += sanf(cfg_x, pag_x) if use_sanf else pag_x
    return out


# -- T2I-Zero -------------------------------------------------------------------------------------------------------
def cbs(f, percentile: float, gamma: int, alpha: float, tokens: Optional[list] = None, token_count: int = 77):
    """Correction by Similarities on one conditioning (n, d)."""
    import torch
    if alpha == 0:
        return f
    n, _ = f.shape
    low, high = 1, min(token_count + 1, n)
    indices = list(range(low, high)) if not tokens else [x + 1 for x in tokens if 0 <= x < n]
    out = f.detach().clone()
    pct = min(1 / (1 + 1e-10), max(1e-16, 1 - 2.718281 ** (-10 * percentile)))
    for c in indices:
        if c < 0 or c >= n:
            continue
        sc = f[c] * f
        tau = torch.quantile(sc, pct)
        kept = sc * (sc > tau)
        kept /= kept.max()
        window = torch.zeros(n, dtype=kept.dtype, device=kept.device)
        window[max(low, c - gamma):min(high, c + gamma + 1)] = 1
        kept *= window.unsqueeze(1)
        out[c] = (1 - alpha) * f[c] + alpha * torch.sum(kept * f, dim=0)
    return out


def ctnms_suppressed(output, to_v, rows: int, cols: int, selected):
    """CTNMS: the cross-attention output times the value of the token winning each (blurred) position."""
    import torch
    b, seq, inner = output.shape
    scores = (to_v @ output.transpose(1, 2)).transpose(1, 2).reshape(b, rows, cols, to_v.size(-2))
    picked = scores[..., selected].permute(0, 3, 1, 2)
    winner = torch.argmax(gaussian_blur(picked).permute(0, 2, 3, 1), dim=-1).reshape(b, rows * cols)
    # one_hot(winner) @ to_v, as a gather; `winner` indexes the selected list but the full token rows (source)
    values = torch.gather(to_v, 1, winner.unsqueeze(-1).expand(b, rows * cols, inner))
    return values * output


def a1111_token_count(tokenize, comma: Optional[int], text: str, chunk_length: int = 75, backtrack: int = 20) -> int:
    """Token count of A1111 tokenize_line (75 per finished chunk plus the last chunk's length)."""
    from .prompt import parse_prompt_attention
    parsed = parse_prompt_attention(text)
    count, current, last_comma, chunks = 0, 0, -1, 0

    def next_chunk(last=False):
        nonlocal count, current, last_comma, chunks
        count += current if last else chunk_length
        current, last_comma, chunks = 0, -1, chunks + 1

    for tokens, (part, weight) in zip(tokenize([t for t, _ in parsed]), parsed):
        if part == "BREAK" and weight == -1:
            next_chunk()
            continue
        for token in tokens:
            if comma is not None and token == comma:
                last_comma = current
            elif backtrack != 0 and current == chunk_length and last_comma != -1 and current - last_comma <= backtrack:
                moved = current - (last_comma + 1)
                current = last_comma + 1
                next_chunk()
                current = moved
            if current == chunk_length:
                next_chunk()
            current += 1
    if current or not chunks:
        next_chunk(last=True)
    return count


def prompt_token_count(pipe, prompt: str, steps: int) -> int:
    """The source's get_token_count: largest A1111 token count over the prompt-editing variants."""
    from . import prompt as prompt_mod
    from .prompt_schedule import schedule
    info = prompt_mod._TokInfo(pipe, pipe.tokenizer)
    text = prompt_mod.strip_extra_networks(prompt)
    return max(a1111_token_count(info.tokenize, info.comma, t) for _, t in schedule(text, max(1, int(steps))))


def parse_tokens(text: str, token_count: int) -> list:
    """Token indices of the T2I-0 Tokens field, shifted past BOS and limited to the prompt's tokens."""
    if len(text) == 0:
        return []
    try:
        values = [int(x) for x in text.split(",")]
    except ValueError:
        raise EngineError(INVALID_PARAMS, "T2I-0 Tokens must be comma separated integers")
    return [x + 1 for x in values if 0 <= x < token_count]


# -- per job state ------------------------------------------------------------------------------------------------
class BatchState:
    """What the source sets up in process_batch: base size, steps, CFG and T2I-0 tokens, plus CTNMS counters."""

    def __init__(self, width: int, height: int, steps: int, cfg: float, token_count: int, tokens: list):
        self.width, self.height, self.steps, self.cfg = int(width), int(height), int(steps), float(cfg)
        self.token_count, self.tokens = int(token_count), list(tokens)
        self.calls: dict = {}   # CTNMS module -> evaluation counter (starts at -1)
        self.ema: dict = {}     # CTNMS module -> EMA tensor


class Job:
    """One state per batch; a hires run continues a copy of its batch's base pass state, other passes start anew."""

    def __init__(self):
        self.state: Optional[BatchState] = None
        self.after_base: Optional[BatchState] = None

    def state_for(self, pass_: str, make: Callable[[], BatchState]) -> BatchState:
        if pass_ == "hires" and self.after_base is not None:
            self.state = copy.deepcopy(self.after_base)
        else:
            self.state, self.after_base = make(), None
        return self.state

    def finish(self, pass_: str) -> None:
        if pass_ == "base" and self.state is not None:
            self.after_base = copy.deepcopy(self.state)


# -- hooks --------------------------------------------------------------------------------------------------------
def _unwrap(output):
    return output[0] if isinstance(output, tuple) else getattr(output, "sample", output)


def _rewrap(output, new):
    if isinstance(output, tuple):
        return (new,) + tuple(output[1:])
    if hasattr(output, "sample"):
        output.sample = new
        return output
    return new


def _cond_pair(value, n: int, b: int):
    """The PAG evaluation input: every batch tensor becomes [cond rows, cond rows]."""
    import torch
    if torch.is_tensor(value) and value.dim() > 0 and value.shape[0] == n:
        return torch.cat([value[b:], value[b:]])
    if isinstance(value, dict):
        return {k: _cond_pair(v, n, b) for k, v in value.items()}
    if isinstance(value, list):
        return [_cond_pair(v, n, b) for v in value]
    if isinstance(value, tuple):
        return tuple(_cond_pair(v, n, b) for v in value)
    return value


def attention_layers(unet):
    """(name, module) of every transformer attention layer (attn1 / attn2) in model order."""
    return [(name, m) for name, m in unet.named_modules()
            if (name.endswith(".attn1") or name.endswith(".attn2")) and hasattr(m, "to_q") and hasattr(m, "to_v")]


class IncantationsHook:
    """All Incantations hooks of one pass. Rows follow diffusers: [uncond B, cond B] with CFG, [cond B] without."""

    def __init__(self, unet, c: dict, state: BatchState, *, batch: int, guided: bool, cfg_scale: float,
                 alphas_cumprod, prediction: str):
        self.unet, self.c, self.state, self.batch, self.guided = unet, c, state, int(batch), guided
        self.g = float(cfg_scale)
        self.ac = alphas_cumprod.float().cpu()
        self.pred = prediction
        f = features(c)
        self.f = f
        self.combiner = f["pag"] or f["cfg_interval"] or f["scfg"]
        self.interval = CfgInterval(c, state.steps, state.cfg) if f["cfg_interval"] else None
        self.handles: list = []
        self.calls = 0          # model evaluations seen (distinct timesteps)
        self.last_t = None
        self.step = 0
        self.inner = False      # inside the PAG evaluation
        self.seg_on = False
        self.scfg_on = False
        self.pag_on = False
        self.entries: list = []
        self.scfg_q: dict = {}
        self.original_hidden = None
        self.pag_value = None

    # step counting / conditioning -----------------------------------------------------------------------------
    def _rows(self, n: int) -> int:
        b = self.batch
        if self.guided and n == 2 * b:
            return b
        if not self.guided and n == b:
            return 0
        raise EngineError(UNSUPPORTED, f"Incantations found an unexpected batch of {n} for {b} images")

    def _in(self, prefix: str, start_key: str = "start_step", end_key: str = "end_step") -> bool:
        return self.c[f"{prefix}_{start_key}"] <= self.step <= self.c[f"{prefix}_{end_key}"]

    def pre(self, module, args, kwargs):
        import torch
        if self.inner:
            return None
        t = args[1] if len(args) > 1 else kwargs.get("timestep")
        tv = float(torch.as_tensor(t).flatten()[0])
        if self.last_t is not None and tv != self.last_t:
            self.calls += 1
        self.last_t = tv
        # A1111 hands callbacks state.sampling_step, set by the sampler callback after each step.
        self.step = max(0, self.calls - 1)
        self.seg_on = self.f["seg"] and self._in("seg")
        self.scfg_on = self.f["scfg"] and self._in("scfg") and self.c["scfg_scale"] > 0
        self.entries = []
        self.scfg_q = {}
        in_args = len(args) > 2
        hidden = args[2] if in_args else kwargs.get("encoder_hidden_states")
        self.original_hidden = hidden
        c = self.c
        if not (self.f["t2i0"] and c["t2i0_correction_strength"] != 0 and hidden is not None
                and c["t2i0_step_start"] <= self.step <= c["t2i0_step_end"]):
            return None
        start = self._rows(hidden.shape[0])
        edited = hidden.clone()
        for row in range(start, start + self.batch):
            edited[row] = cbs(hidden[row].float(), c["t2i0_correction_threshold"], c["t2i0_window_size"],
                              c["t2i0_correction_strength"], self.state.tokens).to(hidden.dtype)
        if in_args:
            args = list(args)
            args[2] = edited
            return tuple(args), kwargs
        kwargs = dict(kwargs)
        kwargs["encoder_hidden_states"] = edited
        return args, kwargs

    # SEG ---------------------------------------------------------------------------------------------------------
    def _seg_q(self, attn):
        def hook(module, args, output):
            if not self.seg_on or output.shape[0] % 2:
                return None
            half = output.shape[0] // 2
            blurred = seg_query(output[:half], attn.heads, self.state.height, self.state.width, self.c["seg_blur_sigma"])
            import torch
            return torch.cat([blurred.to(output.dtype), output[half:]])
        return hook

    # S-CFG -------------------------------------------------------------------------------------------------------
    def _scfg_q(self, name, attn):
        def hook(module, args, output):
            if self.scfg_on and not self.inner:
                row = self._rows(output.shape[0])
                if name.endswith(".attn1"):
                    self.entries.append(("self", output[row:row + 1], None, attn.heads))
                else:
                    self.scfg_q[name] = output[row:row + 1]
            return None
        return hook

    def _scfg_k(self, name, attn):
        def hook(module, args, output):
            if self.scfg_on and not self.inner and name in self.scfg_q:
                row = self._rows(output.shape[0])
                self.entries.append(("cross", self.scfg_q.pop(name), output[row:row + 1], attn.heads))
            return None
        return hook

    # PAG ---------------------------------------------------------------------------------------------------------
    def _pag_v(self, module, args, output):
        if self.pag_on:
            self.pag_value = output
        return None

    def _pag_attn(self, module, args, output):
        if self.pag_on and self.pag_value is not None:
            value, self.pag_value = self.pag_value, None
            return value
        return None

    # T2I-0 CTNMS -------------------------------------------------------------------------------------------------
    def _ctnms_v(self, name):
        def hook(module, args, output):
            self.to_v[name] = output
            return None
        return hook

    def _ctnms(self, name):
        def hook(module, args, kwargs, output):
            st, c = self.state, self.c
            step = st.calls.get(name, -1) + 1
            st.calls[name] = step
            context = kwargs.get("encoder_hidden_states", args[1] if len(args) > 1 else None)
            if context is None or context.shape[1] % 77 != 0:
                return None
            start, end = c["t2i0_step_start"], c["t2i0_step_end"]
            if (step > end and end > 0) or step < start:
                return None
            b, seq, inner = output.shape
            factor = math.isqrt((st.width * st.height) // seq)
            cols, rows = (st.width // factor, st.height // factor) if factor else (0, 0)
            if cols * rows != seq:
                return None
            import torch
            to_v = self.to_v[name]
            if st.tokens:
                selected = torch.tensor(st.tokens, dtype=torch.int64, device=output.device)
            else:
                selected = torch.arange(1, st.token_count, device=output.device)
            if selected.numel() == 0:
                raise EngineError(UNSUPPORTED, "T2I-0 CTNMS needs a prompt of at least two tokens (the source fails "
                                               "on shorter prompts)")
            if name not in st.ema:
                st.ema[name] = output.detach().clone()
            suppressed = ctnms_suppressed(output, to_v, rows, cols, selected)
            alpha = c["t2i0_ctnms_alpha"]
            if c["t2i0_ema_factor"] > 0:
                ema = st.ema[name]
                if ema.shape != suppressed.shape:
                    raise EngineError(UNSUPPORTED, "T2I-0 EMA cannot continue into an evaluation of another size "
                                                   "(hires or PAG); the source fails here too")
                factor_t = c["t2i0_ema_factor"] / (1 + step)
                ema = factor_t * ema + (1 - factor_t) * suppressed
                st.ema[name] = ema
                return (1 - alpha) * output + alpha * ema
            return (1 - alpha) * output + alpha * suppressed
        return hook

    # combination -----------------------------------------------------------------------------------------------------
    def _alpha(self, t) -> float:
        import torch
        tv = float(t.flatten()[0]) if isinstance(t, torch.Tensor) else float(t)
        tv = min(max(tv, 0.0), len(self.ac) - 1.0)
        lo, hi = int(math.floor(tv)), int(math.ceil(tv))
        w = tv - lo
        return float(self.ac[lo] * (1 - w) + self.ac[hi] * w)

    def _x0(self, pred, z, sa, s1a):
        return sa * z - s1a * pred if self.pred == "v_prediction" else (z - s1a * pred) / sa

    def _pred(self, x0, z, sa, s1a):
        return (sa * z - x0) / s1a if self.pred == "v_prediction" else (z - sa * x0) / s1a

    def cfg_value(self, step: int) -> float:
        return self.interval.value(step) if self.interval is not None else self.g

    def _pag_pass(self, module, args, kwargs, n: int, b: int):
        args = list(args)
        if len(args) > 2:
            args[2] = self.original_hidden
        else:
            kwargs = dict(kwargs, encoder_hidden_states=self.original_hidden)
        pargs, pkw = _cond_pair(tuple(args), n, b), _cond_pair(kwargs, n, b)
        self.inner, self.pag_on = True, True
        try:
            out = _unwrap(module(*pargs, **pkw))
        finally:
            self.inner, self.pag_on, self.pag_value = False, False, None
        # The source keeps the first half (its cond slot); here the cond slot is the second half.
        return out[b:]

    def post(self, module, args, kwargs, output):
        import torch
        if self.inner or not self.combiner:
            return None
        out = _unwrap(output)
        sample = args[0] if args else kwargs.get("sample")
        t = args[1] if len(args) > 1 else kwargs.get("timestep")
        n = out.shape[0]
        b = self._rows(n)
        step, c = self.step, self.c
        cfg = self.cfg_value(step)
        run_pag = self.f["pag"] and c["pag_scale"] > 0 and self._in("pag")
        masks = None
        if self.scfg_on:
            masks = scfg_masks(self.entries, c["scfg_r"], out.shape[-2:], self.state.height, self.state.width)
        self.entries, self.scfg_q = [], {}
        if not run_pag and masks is None and cfg == self.g:
            return None
        perturbed = self._pag_pass(module, args, kwargs, n, b) if run_pag else None
        a = self._alpha(t)
        sa, s1a = math.sqrt(a), math.sqrt(max(1.0 - a, 1e-8))
        dt = out.dtype
        u, cond = out.float()[:b], out.float()[b:]
        z = sample.float()[:b, :out.shape[1]]
        x0u, x0c = self._x0(u, z, sa, s1a), self._x0(cond, z, sa, s1a)
        x0p = self._x0(perturbed.float(), z, sa, s1a) if perturbed is not None else None
        rate = (lambda delta, scale: scfg_rate(delta, scale, masks, c)) if masks is not None else None
        x0 = combine(x0u, x0c, x0p, cfg, pag_scale=c["pag_scale"], run_pag=run_pag, use_sanf=c["pag_sanf"],
                     rate=rate)
        guided = self._pred(x0, z, sa, s1a)
        new = torch.cat([u, u + (guided - u) / self.g]).to(dt)
        return _rewrap(output, new)

    # install ---------------------------------------------------------------------------------------------------------
    def install(self):
        unet = self.unet
        add = self.handles.append
        layers = attention_layers(unet)
        self.to_v: dict = {}
        add(unet.register_forward_pre_hook(self.pre, with_kwargs=True))
        if self.f["seg"]:
            for name, attn in layers:
                if name.startswith("mid_block.") and name.endswith(".attn1"):
                    add(attn.to_q.register_forward_hook(self._seg_q(attn)))
        if self.f["scfg"]:
            for name, attn in layers:
                add(attn.to_q.register_forward_hook(self._scfg_q(name, attn)))
                if name.endswith(".attn2"):
                    add(attn.to_k.register_forward_hook(self._scfg_k(name, attn)))
        if self.f["pag"]:
            target = dict(layers).get(PAG_LAYER)
            if target is None:
                raise EngineError(UNSUPPORTED, "Incantations PAG needs a middle block transformer (SD1.x/SD2.x/SDXL)")
            add(target.to_v.register_forward_hook(self._pag_v))
            add(target.register_forward_hook(self._pag_attn))
        if self.f["t2i0"] and self.c["t2i0_ctnms_alpha"] > 0:
            for name, attn in layers:
                if name.endswith(".attn2"):
                    add(attn.to_v.register_forward_hook(self._ctnms_v(name)))
                    add(attn.register_forward_hook(self._ctnms(name), with_kwargs=True))
        if self.combiner:
            add(unet.register_forward_hook(self.post, with_kwargs=True))
        return self

    def remove(self):
        for h in self.handles:
            h.remove()
        self.handles = []
        self.to_v = {}
        self.entries, self.scfg_q, self.original_hidden, self.pag_value = [], {}, None, None


COMBINER_CONFLICTS = ("APG", "Mahiro", "Latent Modifier", "Dynamic Thresholding", "Rescale CFG", "CFG Schedule",
                      "CD Tuner", "Detail Daemon", "CADS", "Semantic Guidance", "MultiDiffusion")
LAYOUT_CONFLICTS = ("HyperTile", "Token Downsampling", "Agent Attention", "MultiDiffusion")
# Reference-only runs its own model evaluation inside ours, which every submodule would count or capture.
ALWAYS_CONFLICTS = ("PAG", "Self Attention Guidance", "Reference", "Neutral Prompt / Composable LoRA branches")


def check_combination(c: dict, others: dict, *, guided: bool) -> None:
    f = features(c)
    names = [n for n in ALWAYS_CONFLICTS if others.get(n)]
    if f["pag"] or f["cfg_interval"] or f["scfg"]:
        names += [n for n in COMBINER_CONFLICTS if others.get(n) and n not in names]
    if f["seg"] or f["scfg"]:
        names += [n for n in LAYOUT_CONFLICTS if others.get(n) and n not in names]
    if f["t2i0"] and c["t2i0_correction_strength"] != 0 and others.get("Semantic Guidance") and "Semantic Guidance" not in names:
        names.append("Semantic Guidance")
    if names:
        raise EngineError(UNSUPPORTED, "Incantations cannot combine with " + ", ".join(names) +
                          ": they evaluate, lay out or recombine the model differently")
    if not guided and (f["seg"] or f["pag"] or f["cfg_interval"] or f["scfg"]):
        raise EngineError(UNSUPPORTED, "Incantations SEG, S-CFG, PAG and the CFG Scheduler need CFG scale > 1 "
                                       "(they work on the unconditional prediction)")


def install(engine, pipe, gp, kw: dict, ctx, config: dict, *, pass_: str, w: int, h: int, cfg_scale: float, sched,
            cleanups: list, others: Optional[dict] = None) -> None:
    """Hooks one pass; appends its cleanup to `cleanups` before touching the model."""
    import torch
    c = normalize(config)
    f = features(c)
    if not any(f.values()):
        return
    unet = pipe.unet
    guided = cfg_scale > 1 and getattr(unet.config, "time_cond_proj_dim", None) is None
    check_combination(c, others or {}, guided=guided)
    job = getattr(engine, "_incantations_job", None)
    if job is None:
        job = Job()
        engine._incantations_job = job
    embeds = kw.get("prompt_embeds")
    if torch.is_tensor(embeds):
        batch = embeds.shape[0]
    elif isinstance(kw.get("generator"), list):
        batch = len(kw["generator"])
    else:
        batch = max(1, int(getattr(gp, "batch_size", 1) or 1))

    def make() -> BatchState:
        steps, cfg = int(getattr(gp, "steps", 0) or kw.get("num_inference_steps") or 1), float(getattr(gp, "cfg_scale", cfg_scale))
        count, tokens = 0, []
        if f["t2i0"]:
            count = prompt_token_count(pipe, str(getattr(gp, "prompt", "") or ""), steps)
            tokens = parse_tokens(c["t2i0_tokens"], count)
        return BatchState(w, h, steps, cfg, count, tokens)

    state = job.state_for(pass_, make)
    ac = getattr(sched, "alphas_cumprod", None)
    if ac is None:
        from .sigmas import default_alphas_cumprod
        ac = torch.tensor(default_alphas_cumprod())
    hook = IncantationsHook(unet, c, state, batch=batch, guided=guided, cfg_scale=cfg_scale, alphas_cumprod=ac,
                            prediction=sched.config.get("prediction_type", "epsilon"))

    def remove():
        hook.remove()
        job.finish(pass_)
    cleanups.append(remove)
    hook.install()


__all__ = ["DEFAULTS", "SCHEDULES", "normalize", "features", "noise_level", "closest_index", "schedule_value",
           "CfgInterval", "gaussian_blur", "seg_kernel_size", "seg_blur_2d", "seg_query", "scfg_kernel", "head_mean",
           "attention_scores", "scfg_masks", "scfg_rate", "sanf", "combine", "cbs", "ctnms_suppressed",
           "a1111_token_count", "prompt_token_count", "parse_tokens", "BatchState", "Job", "IncantationsHook",
           "check_combination", "install"]
