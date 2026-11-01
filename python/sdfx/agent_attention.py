"""Agent Attention, re-implemented from v0xie/sd-webui-agentattention 8c2fd24 (GPL-3.0) behaviour.

The source is AgentSD (arXiv:2312.08874): ToMe-style token merging on UNet self-attention, with the attention
of the merged tokens replaced by agent attention. No source code is imported or copied.

Per BasicTransformerBlock whose token grid is downsampled at most `max_downsample` times from the latent:

- every sy x sx window of the block's token grid gives one destination token (a random cell when the block's
  batch is even, otherwise the top-left cell); the other tokens are sources. Each source is matched to its most
  cosine-similar destination, measured on the block input (before norm1);
- the `int(N * ratio)` most similar sources are averaged into their destinations (with the destination itself),
  giving the self-attention input; the `int(N * agent_ratio)` most similar, merged the same way, give the agent
  tokens. Both counts are capped at the number of sources;
- when agents * 2 < merged tokens, self-attention becomes
  `softmax(Q A^T * s^0.3) softmax(A K^T * s) V + 0.075 V` with A = to_q(agents) and s = head_dim^-0.5; otherwise
  the layer's own attention runs on the merged tokens. Merged sources take their destination's output.

Cross-attention and feed-forward are unchanged. The first-pass settings apply from sampling step 0; at "Second
Pass Step" the patch is removed and, with "Use Second Pass", re-applied with the second-pass settings. "Apply to
Hires. Fix Only" applies the patch only in the hires pass; otherwise it applies only in the first pass.

The random destination choice draws from a generator that the source forks from the device's global RNG at each
patch; NUI seeds it from the image seed instead, so results are reproducible but not pixel-equal to A1111.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Optional

from .protocol import EngineError, INVALID_PARAMS, UNSUPPORTED

DEFAULTS = {"use_sp": False, "sp_step": 20, "sx": 4, "sy": 4, "ratio": 0.4, "agent_ratio": 0.95,
            "sp_sx": 2, "sp_sy": 2, "sp_ratio": 0.4, "sp_agent_ratio": 0.5, "use_fp32": False,
            "max_downsample": 1, "hires_fix_only": False}
# Fixed by the source's apply_patch defaults (not exposed in its UI).
K_SCALE2 = 0.3
K_SHORTCUT = 0.075
PROCESSORS = ("AttnProcessor", "AttnProcessor2_0", "XFormersAttnProcessor", "SlicedAttnProcessor")
# Hooks whose self-attention changes rely on the unmerged token layout or replace the processors themselves.
CONFLICTS = {"hypertile": "HyperTile", "reference": "Reference-only", "controllllite": "ControlNet-LLLite"}


def _bool(value, key):
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.strip().lower() in ("true", "false"):
        return value.strip().lower() == "true"
    raise EngineError(INVALID_PARAMS, f"Agent Attention: {key} must be true or false")


def _int(value, key, low):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value != int(value):
        raise EngineError(INVALID_PARAMS, f"Agent Attention: {key} must be an integer")
    if value < low:
        raise EngineError(INVALID_PARAMS, f"Agent Attention: {key} must be >= {low}")
    return int(value)


def _ratio(value, key):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1:
        raise EngineError(INVALID_PARAMS, f"Agent Attention: {key} must be a number from 0 to 1")
    return float(value)


def normalize(config: dict) -> dict:
    unknown = sorted(set(config or {}) - set(DEFAULTS) - {"enabled"})
    if unknown:
        raise EngineError(INVALID_PARAMS, f"Agent Attention: unknown settings {unknown}")
    c = dict(DEFAULTS)
    c.update({k: v for k, v in (config or {}).items() if k in DEFAULTS})
    out = {"use_sp": _bool(c["use_sp"], "use_sp"), "use_fp32": _bool(c["use_fp32"], "use_fp32"),
           "hires_fix_only": _bool(c["hires_fix_only"], "hires_fix_only"),
           "sp_step": _int(c["sp_step"], "sp_step", 0), "max_downsample": _int(c["max_downsample"], "max_downsample", 1)}
    for prefix in ("", "sp_"):
        out[prefix + "sx"] = _int(c[prefix + "sx"], prefix + "sx", 0)
        out[prefix + "sy"] = _int(c[prefix + "sy"], prefix + "sy", 0)
        out[prefix + "ratio"] = _ratio(c[prefix + "ratio"], prefix + "ratio")
        out[prefix + "agent_ratio"] = _ratio(c[prefix + "agent_ratio"], prefix + "agent_ratio")
    # The source divides by the strides and unpacks an unmerged tensor when no token is merged, so these settings
    # fail there; reject them up front for every pass that is actually applied.
    for prefix, used in (("", out["sp_step"] > 0), ("sp_", out["use_sp"])):
        if not used:
            continue
        name = "second pass" if prefix else "first pass"
        if out[prefix + "sx"] < 1 or out[prefix + "sy"] < 1:
            raise EngineError(INVALID_PARAMS, f"Agent Attention {name}: sx and sy must be at least 1")
        if out[prefix + "ratio"] <= 0:
            raise EngineError(INVALID_PARAMS, f"Agent Attention {name}: ratio must be above 0 (0 merges no tokens)")
    return out


def pass_settings(c: dict, second: bool) -> dict:
    p = "sp_" if second else ""
    return {"sx": c[p + "sx"], "sy": c[p + "sy"], "ratio": c[p + "ratio"], "agent_ratio": c[p + "agent_ratio"],
            "use_fp32": c["use_fp32"], "max_downsample": c["max_downsample"]}


def settings_at(c: dict, step: int) -> Optional[dict]:
    """Settings in force from `step` on, at the steps where the source (re)patches (0 and Second Pass Step)."""
    if step >= c["sp_step"]:
        return pass_settings(c, True) if c["use_sp"] else None
    return pass_settings(c, False)


def applies(c: dict, pass_: str) -> bool:
    """Base pass unless "Apply to Hires. Fix Only"; then only the hires pass. ADetailer passes never."""
    if pass_ not in ("base", "hires"):
        return False
    return (pass_ == "hires") == c["hires_fix_only"]


def downsample_of(original_hw, tokens: int) -> int:
    h0, w0 = original_hw
    return int(math.ceil(math.sqrt((h0 * w0) // tokens)))


@dataclass
class MergePlan:
    tokens: int
    src: Any            # (Ns,) source token positions
    dst: Any            # (Nd,) destination token positions
    kept: Any           # (B, Ns - r) sources left as they are, by source slot
    merged: Any         # (B, r) sources averaged into a destination
    target: Any         # (B, r) destination slot of each merged source
    agent: Optional[tuple] = None  # (kept, merged, target) for the agent tokens
    use_fp32: bool = False


def destinations(h: int, w: int, sx: int, sy: int, generator, device):
    """Bool mask over the h*w grid marking one destination per full sy x sx window."""
    import torch
    rows, cols = h // sy, w // sx
    if rows * cols == 0:
        raise EngineError(UNSUPPORTED, f"Agent Attention: sx {sx} / sy {sy} exceed a {w}x{h} attention layer; "
                                       "lower them or raise the resolution")
    if generator is None:
        pick = torch.zeros(rows, cols, dtype=torch.int64, device=device)
    else:
        pick = torch.randint(sy * sx, size=(rows, cols, 1), device=generator.device, generator=generator).to(device)[..., 0]
    y = torch.arange(rows, device=device)[:, None] * sy + torch.div(pick, sx, rounding_mode="floor")
    x = torch.arange(cols, device=device)[None, :] * sx + pick % sx
    mask = torch.zeros(h * w, dtype=torch.bool, device=device)
    mask[(y * w + x).reshape(-1)] = True
    return mask


def plan_merge(metric, h: int, w: int, sx: int, sy: int, r: int, agent_r: int, generator=None,
               use_fp32: bool = False) -> MergePlan:
    import torch
    batch, tokens, _ = metric.shape
    mask = destinations(h, w, sx, sy, generator, metric.device)
    dst = mask.nonzero().reshape(-1)
    src = (~mask).nonzero().reshape(-1)
    # Normalized in float32, product in the model dtype: what A1111's autocast does with the source's code (and it
    # keeps the (B, sources, destinations) matrix at the source's size).
    unit = metric.float()
    unit = (unit / unit.norm(dim=-1, keepdim=True)).to(metric.dtype)
    similarity = unit[:, src] @ unit[:, dst].transpose(1, 2)
    best, nearest = similarity.max(dim=-1)
    order = best.argsort(dim=-1, descending=True, stable=True)

    def split(count):
        merged = order[:, :count]
        return order[:, count:], merged, nearest.gather(1, merged)

    r = min(src.numel(), r)
    agent_r = min(src.numel(), agent_r)
    kept, merged, target = split(r)
    agent = split(agent_r) if (tokens - agent_r) * 2 < tokens - r else None
    return MergePlan(tokens, src, dst, kept, merged, target, agent, use_fp32)


def _rows(x, index):
    return x.gather(1, index[..., None].expand(-1, -1, x.shape[-1]))


def merge(plan: MergePlan, x):
    """(features, agents or None) from the normalized block tokens x (B, N, C)."""
    import torch
    sources, targets = x[:, plan.src], x[:, plan.dst]

    def tokens(kept, merged, target):
        # Same as scatter_reduce(mean, include_self=True), but CUDA scatter adds are non-deterministic and the
        # differences grow over the steps; a one-hot matrix product keeps PNG replays pixel-exact.
        onehot = torch.nn.functional.one_hot(target, targets.shape[1]).to(torch.float32)
        sums = torch.bmm(onehot.transpose(1, 2), _rows(sources, merged).float())
        averaged = ((targets.float() + sums) / (onehot.sum(1)[..., None] + 1)).to(targets.dtype)
        return torch.cat([_rows(sources, kept), averaged], dim=1)

    features = tokens(plan.kept, plan.merged, plan.target)
    return features, (tokens(*plan.agent) if plan.agent is not None else None)


def unmerge(plan: MergePlan, y):
    """Back to (B, N, C): kept sources and destinations take their own output, merged sources their destination's."""
    batch, _, channels = y.shape
    count = plan.kept.shape[1]
    kept, targets = y[:, :count], y[:, count:]
    sources = y.new_empty(batch, plan.src.numel(), channels)
    sources.scatter_(1, plan.kept[..., None].expand(-1, -1, channels), kept)
    sources.scatter_(1, plan.merged[..., None].expand(-1, -1, channels), _rows(targets, plan.target))
    out = y.new_empty(batch, plan.tokens, channels)
    out[:, plan.src] = sources
    out[:, plan.dst] = targets
    return out


def agent_attention(attn, x, agents, use_fp32: bool = False):
    """Agent attention of the merged tokens x with agent tokens, through the layer's projections."""
    heads = attn.heads
    q, k, v, a = attn.to_q(x), attn.to_k(x), attn.to_v(x), attn.to_q(agents)

    def split(t):
        return t.reshape(t.shape[0], t.shape[1], heads, -1).transpose(1, 2)

    q, k, v, a = split(q), split(k), split(v), split(a)
    dtype, scale = v.dtype, attn.scale
    if use_fp32:
        a = a.float()
        first = (a @ k.float().transpose(-1, -2)) * scale
    else:
        first = (a @ k.transpose(-1, -2)) * scale
    agent_values = first.float().softmax(dim=-1).to(dtype) @ v
    if use_fp32:
        second = (q.float() @ a.transpose(-1, -2)) * scale ** K_SCALE2
    else:
        second = (q @ a.transpose(-1, -2)) * scale ** K_SCALE2
    out = second.float().softmax(dim=-1).to(dtype) @ agent_values + v * K_SHORTCUT
    out = out.transpose(1, 2).reshape(x.shape[0], x.shape[1], -1)
    return attn.to_out[1](attn.to_out[0](out))


class AgentAttentionProcessor:
    """attn1 processor: runs the plan its block's pre-hook prepared, or the original processor."""

    def __init__(self, original):
        self.original = original
        self.plan: Optional[MergePlan] = None

    def __call__(self, attn, hidden_states, encoder_hidden_states=None, attention_mask=None, temb=None, **kwargs):
        plan, self.plan = self.plan, None
        if temb is not None:
            kwargs["temb"] = temb
        if plan is None:
            return self.original(attn, hidden_states, encoder_hidden_states=encoder_hidden_states,
                                 attention_mask=attention_mask, **kwargs)
        if encoder_hidden_states is not None or attention_mask is not None:
            raise EngineError(UNSUPPORTED, "Agent Attention cannot merge a self-attention layer with a context or mask")
        if hidden_states.ndim != 3 or hidden_states.shape[1] != plan.tokens:
            raise EngineError(UNSUPPORTED, "Agent Attention found self-attention tokens that differ from its block input")
        features, agents = merge(plan, hidden_states)
        if agents is not None and agents.shape[1] * 2 < features.shape[1]:
            out = agent_attention(attn, features, agents, plan.use_fp32)
        else:
            out = self.original(attn, features, encoder_hidden_states=None, attention_mask=None, **kwargs)
        return unmerge(plan, out)


def _check_block(block):
    attn = block.attn1
    if getattr(block, "norm_type", "layer_norm") != "layer_norm" or getattr(block, "pos_embed", None) is not None \
            or getattr(block, "only_cross_attention", False):
        raise EngineError(UNSUPPORTED, "Agent Attention needs ordinary SD transformer blocks")
    if getattr(attn, "group_norm", None) is not None or getattr(attn, "spatial_norm", None) is not None \
            or getattr(attn, "norm_q", None) is not None or getattr(attn, "norm_k", None) is not None \
            or getattr(attn, "residual_connection", False) or getattr(attn, "rescale_output_factor", 1.0) != 1.0 \
            or getattr(attn, "is_cross_attention", False):
        raise EngineError(UNSUPPORTED, "Agent Attention needs ordinary SD self-attention layers")
    processor = type(attn.processor).__name__
    if processor not in PROCESSORS:
        raise EngineError(UNSUPPORTED, f"Agent Attention does not support self-attention processor {processor}")


class AgentAttentionHook:
    def __init__(self, unet, config: dict, seed: int = 0):
        self.unet = unet
        self.config = config
        self.seed = int(seed) & 0xFFFFFFFFFFFFFFFF
        self.settings: Optional[dict] = None
        self.step = -1
        self.last_timestep = None
        self.size = None
        self.generators: dict = {}
        self.handles: list = []
        self.processors: list = []  # (attn, ours)
        self.merged_layers = 0

    # -- schedule ---------------------------------------------------------------------------------------------
    def _unet_pre(self, module, args, kwargs):
        import torch
        timestep = args[1] if len(args) > 1 else kwargs.get("timestep")
        t = float(torch.as_tensor(timestep).flatten()[0])
        if self.last_timestep is None or t != self.last_timestep:
            self.step += 1
            self.last_timestep = t
            if self.step == 0 or self.step == self.config["sp_step"]:
                self.settings = settings_at(self.config, self.step)
                self.generators = {}  # the source re-forks its generator on every patch

    def _size(self, module, args):
        self.size = tuple(args[0].shape[-2:])

    def _generator(self, device):
        import torch
        dev = device if device.type == "cuda" else torch.device("cpu")
        key = str(dev)
        if key not in self.generators:
            self.generators[key] = torch.Generator(device=dev).manual_seed(self.seed)
        return self.generators[key]

    def plan_for(self, hidden_states) -> Optional[MergePlan]:
        s = self.settings
        if s is None:
            return None
        if self.size is None or hidden_states.ndim != 3:
            raise EngineError(UNSUPPORTED, "Agent Attention could not determine the latent size")
        batch, tokens, _ = hidden_states.shape
        downsample = downsample_of(self.size, tokens)
        if downsample > s["max_downsample"]:
            return None
        h, w = (int(math.ceil(v / downsample)) for v in self.size)
        if h * w != tokens:
            raise EngineError(UNSUPPORTED, f"Agent Attention cannot map {tokens} tokens to a {w}x{h} grid "
                                           f"(latent {self.size[1]}x{self.size[0]})")
        r, agent_r = int(tokens * s["ratio"]), int(tokens * s["agent_ratio"])
        if r <= 0:
            raise EngineError(UNSUPPORTED, f"Agent Attention ratio {s['ratio']} merges no tokens in a {w}x{h} layer")
        generator = self._generator(hidden_states.device) if batch % 2 == 0 else None
        self.merged_layers += 1
        return plan_merge(hidden_states, h, w, s["sx"], s["sy"], r, agent_r, generator, s["use_fp32"])

    # -- install ----------------------------------------------------------------------------------------------
    def install(self):
        blocks = [m for _, m in self.unet.named_modules() if type(m).__name__ == "BasicTransformerBlock"]
        if not blocks:
            raise EngineError(UNSUPPORTED, "Agent Attention found no UNet transformer blocks")
        for block in blocks:
            _check_block(block)
        try:
            self.handles.append(self.unet.register_forward_pre_hook(self._unet_pre, with_kwargs=True))
            self.handles.append(self.unet.conv_in.register_forward_pre_hook(self._size))
            for block in blocks:
                ours = AgentAttentionProcessor(block.attn1.processor)
                block.attn1.set_processor(ours)
                self.processors.append((block.attn1, ours))

                def pre(module, args, kwargs, ours=ours):
                    hidden = args[0] if args else kwargs.get("hidden_states")
                    ours.plan = self.plan_for(hidden)
                self.handles.append(block.register_forward_pre_hook(pre, with_kwargs=True))
            return self
        except BaseException:
            self.remove()
            raise

    def remove(self):
        for handle in reversed(self.handles):
            handle.remove()
        self.handles.clear()
        for attn, ours in reversed(self.processors):
            ours.plan = None
            if attn.processor is ours:
                attn.set_processor(ours.original)
        self.processors.clear()


def check_combination(gp, *, pag: bool, sag: bool, todo_active: bool):
    from .hooks import hook_config
    hooks = gp.hooks or {}
    names = [label for key, label in CONFLICTS.items() if hook_config(hooks, key, {}) is not None]
    names += ["PAG"] if pag else []
    names += ["Self Attention Guidance"] if sag else []
    names += ["Token Downsampling"] if todo_active else []
    if names:
        raise EngineError(UNSUPPORTED, f"Agent Attention cannot combine with {', '.join(names)}: they change or rely "
                                       "on the unmerged self-attention tokens")


def install(engine, pipe, gp, ctx, config: dict, *, pass_: str, seed: int, pag: bool = False, sag: bool = False):
    """Validates and installs for this pass; returns the cleanup callable, or None when the pass is unaffected."""
    c = normalize(config)
    from .hooks import hook_config
    todo = hook_config(gp.hooks or {}, "token_downsampling", {})
    todo_active = todo is not None and any(float(todo.get(k, 1)) > 1 for k in ("factor", "factor2"))
    check_combination(gp, pag=pag, sag=sag, todo_active=todo_active)
    if pass_ == "base" and c["hires_fix_only"] and not getattr(getattr(gp, "hires", None), "enabled", False):
        ctx.warn("Agent Attention is set to Apply to Hires. Fix Only, but there is no hires pass; nothing changes")
    if not applies(c, pass_):
        return None
    if getattr(engine, "sdxl", False) and c["max_downsample"] < 2:
        ctx.warn("Agent Attention: SDXL has no attention layers at Max Downsample 1, so nothing changes "
                 "(the source recommends values above 1 for SDXL)")
    hook = AgentAttentionHook(pipe.unet, c, seed).install()
    return hook.remove
