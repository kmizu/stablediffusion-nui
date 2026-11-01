"""LLuL - Local Latent upscaLer, re-implemented from hnmr293/sd-webui-llul 6c5ac1b (MIT) behaviour.

At the chosen UNet layers a square region of the layer input (1/M of the width and height, M = 2^multiply) is cut
out and upscaled M times. It runs through the layer next to the untouched input (the batch doubles). The second
half of the output is scaled back down and blended into the region of the first half:
`region = interpolate(region, downscaled, weight)`. The model so draws the region at a higher resolution than the
rest. A mask (grey levels, resized to the region) scales the weight per pixel.

By default only the output layer (`OUT`: the final norm + conv) is hooked. Advanced settings choose resblocks,
transformers and self- or cross-attention layers, filtered by the source's layer names (`IN00_00_00_sattn`,
`M00_00_resblock`, `OUT03_00_trans`, ...), and the up- and downscalers, the blend and the step window. The
step counter counts UNet calls from 1 in each pass; it is active from `start_steps` to `max_steps` (0 = the pass'
step count). No source code is imported or copied.
"""
from __future__ import annotations

import math
from typing import Any, Optional

from .hooks import coerce_bool
from .protocol import EngineError, INVALID_PARAMS, UNSUPPORTED

DEFAULTS = {"weight": 0.15, "multiply": 1, "x": None, "y": None, "layers": "OUT",
            "apply_to": ["out"], "start_steps": 5, "max_steps": 0, "up": "bilinear", "up_aa": False,
            "down": "bilinear", "down_aa": False, "intp": "lerp", "force_float": False, "mask": ""}
APPLY_TO = ("resblock", "transformer", "s. attn.", "x. attn.", "out")
UPSCALERS = {"nearest": "nearest-exact", "bilinear": "bilinear", "bicubic": "bicubic"}
DOWNSCALERS = {"nearest": "nearest-exact", "bilinear": "bilinear", "bicubic": "bicubic", "area": "area",
               "pooling max": "max", "pooling avg": "avg"}
AA_MODES = ("bilinear", "bicubic")
MIN_SIZE = 128


def _number(value, key, low=-math.inf, high=math.inf):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) \
            or not low <= value <= high:
        raise EngineError(INVALID_PARAMS, f"LLuL: {key} must be a number" +
                          (f" from {low:g} to {high:g}" if low > -math.inf or high < math.inf else ""))
    return float(value)


def _integer(value, key, low, high=math.inf):
    n = _number(value, key, low, high)
    if n != int(n):
        raise EngineError(INVALID_PARAMS, f"LLuL: {key} must be an integer")
    return int(n)


def _choice(value, key, table):
    name = str(value).strip().lower()
    if name not in table:
        raise EngineError(INVALID_PARAMS, f"LLuL: {key} must be one of {sorted(table)}")
    return name


def normalize(config: dict) -> dict:
    unknown = sorted(set(config or {}) - set(DEFAULTS) - {"enabled"})
    if unknown:
        raise EngineError(INVALID_PARAMS, f"LLuL: unknown settings {unknown}")
    c = dict(DEFAULTS)
    c.update({k: v for k, v in (config or {}).items() if k in DEFAULTS})
    apply_to = c["apply_to"]
    if isinstance(apply_to, str):
        apply_to = [part for part in apply_to.split(",")]
    if not isinstance(apply_to, (list, tuple)):
        raise EngineError(INVALID_PARAMS, "LLuL: apply_to must be a list of layer kinds")
    apply_to = [str(part).strip().lower() for part in apply_to]
    bad = [part for part in apply_to if part not in APPLY_TO]
    if bad:
        raise EngineError(INVALID_PARAMS, f"LLuL: apply_to {bad} is not one of {list(APPLY_TO)}")
    layers = c["layers"]
    if layers is None or (isinstance(layers, str) and not layers.strip()):
        layers = None
    elif isinstance(layers, str):
        layers = [part.strip() for part in layers.split(",")]
    elif isinstance(layers, (list, tuple)):
        layers = [str(part).strip() for part in layers]
    else:
        raise EngineError(INVALID_PARAMS, "LLuL: layers must be text")
    out = {"weight": _number(c["weight"], "weight"), "multiply": _integer(c["multiply"], "multiply", 0, 5),
           "layers": layers, "apply_to": apply_to,
           "start_steps": _integer(c["start_steps"], "start_steps", 1), "max_steps": _integer(c["max_steps"], "max_steps", 0),
           "up": _choice(c["up"], "up", UPSCALERS), "up_aa": coerce_bool(c["up_aa"], False),
           "down": _choice(c["down"], "down", DOWNSCALERS), "down_aa": coerce_bool(c["down_aa"], False),
           "intp": _choice(c["intp"], "intp", ("lerp", "slerp")), "force_float": coerce_bool(c["force_float"], False),
           "mask": str(c["mask"] or "")}
    for key in ("x", "y"):
        out[key] = None if c[key] is None or c[key] == "" else _number(c[key], key)
    if out["up_aa"] and out["up"] not in AA_MODES:
        raise EngineError(INVALID_PARAMS, "LLuL: anti-aliasing for upscaling needs the bilinear or bicubic upscaler")
    if out["down_aa"] and out["down"] not in AA_MODES:
        raise EngineError(INVALID_PARAMS, "LLuL: anti-aliasing for downscaling needs the bilinear or bicubic downscaler")
    return out


def multiplier(c: dict) -> int:
    return 2 ** c["multiply"]


def origin(c: dict, width: int, height: int) -> tuple:
    """Region origin in image pixels (centred unless x / y are given)."""
    m = multiplier(c)
    x = (width - width // m) // 2 if c["x"] is None else c["x"]
    y = (height - height // m) // 2 if c["y"] is None else c["y"]
    return float(x), float(y)


def upscale(x, mode: str, aa: bool, factor: int):
    import torch.nn.functional as F
    kwargs = {"antialias": True} if aa else {}
    return F.interpolate(x, scale_factor=float(factor), mode=UPSCALERS[mode], **kwargs)


def downscale(x, mode: str, aa: bool, factor: int):
    import torch.nn.functional as F
    kind = DOWNSCALERS[mode]
    if kind == "max":
        return F.max_pool2d(x, kernel_size=factor)
    if kind == "avg":
        return F.avg_pool2d(x, kernel_size=factor)
    kwargs = {"antialias": True} if aa else {}
    return F.interpolate(x, scale_factor=1.0 / factor, mode=kind, **kwargs)


def lerp(low, high, t):
    import torch
    return torch.lerp(low, high, t)


def slerp(low, high, t):
    """Spherical interpolation over the channel axis; nearly parallel or degenerate vectors fall back to lerp."""
    import torch
    low_n = low / torch.norm(low, dim=1, keepdim=True)
    high_n = high / torch.norm(high, dim=1, keepdim=True)
    dot = (low_n * high_n).sum(1).clamp(-1.0, 1.0)
    omega = torch.acos(dot)
    so = torch.sin(omega)
    mixed = (torch.sin((1.0 - t) * omega) / so).unsqueeze(1) * low + (torch.sin(t * omega) / so).unsqueeze(1) * high
    bad = ~torch.isfinite(mixed).all(dim=1, keepdim=True) | (so.abs() < 1e-6).unsqueeze(1)
    if bad.any():
        mixed = torch.where(bad, torch.lerp(low, high, t), mixed)
    return mixed


def region_box(width: int, height: int, fx: float, fy: float, m: int):
    """(s0, s1, t0, t1) of the region in a layer of width x height, or None when the layer is too small."""
    w, h = width // m, height // m
    if w == 0 or h == 0:
        return None
    s0, t0 = int(width * fx), int(height * fy)
    s1, t1 = s0 + w, t0 + h
    if width < s1:
        s1 = width
        s0 = s1 - w
    if height < t1:
        t1 = height
        t0 = t1 - h
    if s0 < 0 or t0 < 0:
        raise EngineError(INVALID_PARAMS, f"LLuL: the region origin ({fx:.3f}, {fy:.3f}) is outside the image")
    return s0, s1, t0, t1


def downsample_of(latent_hw, tokens: int) -> int:
    h0, w0 = latent_hw
    return int(math.ceil(math.sqrt((h0 * w0) // tokens)))


def load_mask(path: str):
    """Mask image as a float tensor (1, 1, H, W) in 0..1, or None."""
    if not path:
        return None
    import numpy as np
    import torch
    from PIL import Image
    try:
        with Image.open(path) as im:
            arr = np.asarray(im.convert("L"), dtype=np.float32) / 255.0
    except Exception as e:  # noqa: BLE001
        raise EngineError(INVALID_PARAMS, f"LLuL: cannot read the mask image {path!r}: {e}") from e
    return torch.from_numpy(arr)[None, None]


def resolve_layers(unet) -> dict:
    """{kind: [(source name, module)]} for kinds resblock, transformer, s. attn., x. attn., following the source's
    IN##/M##/OUT## names (the index counts the source's input and output blocks)."""
    kinds = {"resblock": [], "transformer": [], "s. attn.": [], "x. attn.": []}

    def add_trans(prefix, index, attn, n):
        kinds["transformer"].append((f"{prefix}{index:02d}_{n:02d}_trans", attn))
        for depth, block in enumerate(getattr(attn, "transformer_blocks", [])):
            kinds["s. attn."].append((f"{prefix}{index:02d}_{n:02d}_{depth:02d}_sattn", block.attn1))
            if getattr(block, "attn2", None) is not None:
                kinds["x. attn."].append((f"{prefix}{index:02d}_{n:02d}_{depth:02d}_xattn", block.attn2))

    index = 1  # input_blocks[0] is the input conv
    for block in unet.down_blocks:
        attentions = getattr(block, "attentions", None) or []
        for j, res in enumerate(block.resnets):
            kinds["resblock"].append((f"IN{index:02d}_00_resblock", res))
            if j < len(attentions):
                add_trans("IN", index, attentions[j], 0)
            index += 1
        if getattr(block, "downsamplers", None):
            index += 1
    mid = unet.mid_block
    for n, res in enumerate(mid.resnets):
        kinds["resblock"].append((f"M00_{n:02d}_resblock", res))
    for n, attn in enumerate(getattr(mid, "attentions", None) or []):
        add_trans("M", 0, attn, n)
    index = 0
    for block in unet.up_blocks:
        attentions = getattr(block, "attentions", None) or []
        for j, res in enumerate(block.resnets):
            kinds["resblock"].append((f"OUT{index:02d}_00_resblock", res))
            if j < len(attentions):
                add_trans("OUT", index, attentions[j], 0)
            index += 1
    return kinds


def _first(args, kwargs, name, position):
    if name in kwargs:
        return kwargs[name]
    return args[position] if len(args) > position else None


def _with(args, kwargs, name, position, value):
    args, kwargs = list(args), dict(kwargs)
    if name in kwargs:
        kwargs[name] = value
    elif len(args) > position:
        args[position] = value
    else:
        kwargs[name] = value
    return tuple(args), kwargs


def _dup(t):
    import torch
    return torch.cat((t, t), dim=0)


class LlulHook:
    def __init__(self, unet, config: dict, *, steps: int, fx: float, fy: float, mask=None):
        self.unet = unet
        self.c = config
        self.m = multiplier(config)
        self.start = config["start_steps"]
        self.stop = config["max_steps"] if config["max_steps"] >= 1 else max(1, int(steps))
        self.fx, self.fy = fx, fy
        self.mask = mask
        self.step = 0
        self.latent_hw = None
        self.handles: list = []
        self.layers_hooked = 0

    # -- schedule -------------------------------------------------------------------------------------------------
    def _unet_pre(self, module, args, kwargs):
        self.step += 1

    def _latent(self, module, args):
        self.latent_hw = tuple(args[0].shape[-2:])

    @property
    def active(self) -> bool:
        return self.start <= self.step <= self.stop

    # -- cut and blend --------------------------------------------------------------------------------------------
    def _grid(self, x):
        """(B, C, H, W) view of a layer input and its token-grid width (None for images)."""
        if x.ndim == 4:
            return x, None
        if x.ndim != 3:
            return None, None
        if self.latent_hw is None:
            raise EngineError(UNSUPPORTED, "LLuL could not determine the latent size")
        tokens = x.shape[1]
        d = downsample_of(self.latent_hw, tokens)
        h, w = (int(math.ceil(v / d)) for v in self.latent_hw)
        if h * w != tokens:
            raise EngineError(UNSUPPORTED, f"LLuL cannot map {tokens} tokens to a {w}x{h} grid "
                                           f"(latent {self.latent_hw[1]}x{self.latent_hw[0]})")
        return x.transpose(1, 2).reshape(x.shape[0], x.shape[2], h, w), w

    def cut(self, x, stack=True):
        """The upscaled region, stacked after the layer input on the batch (stack=False: the region alone);
        None to skip this layer."""
        grid, width = self._grid(x)
        if grid is None:
            return None
        _, _, height, wide = grid.shape
        box = region_box(wide, height, self.fx, self.fy, self.m)
        if box is None:
            return None
        s0, s1, t0, t1 = box
        part = upscale(grid[:, :, t0:t1, s0:s1], self.c["up"], self.c["up_aa"], self.m)
        dy, dx = height - part.shape[-2], wide - part.shape[-1]
        if dx > 0 or dy > 0:
            import torch.nn.functional as F
            part = F.pad(part, (dx // 2, dx - dx // 2, dy // 2, dy - dy // 2), "replicate")
        import torch
        both = torch.cat((grid, part), dim=0) if stack else part
        if width is not None:
            both = both.reshape(both.shape[0], both.shape[1], -1).transpose(1, 2).contiguous()
        return both

    def blend(self, y):
        """Splits the doubled output, scales the second half down and blends it into the first half's region."""
        import torch
        tokens = y.ndim == 3
        if tokens:
            grid, _ = self._grid(y)
        else:
            grid = y
        batch = grid.shape[0]
        if batch % 2:
            raise EngineError(UNSUPPORTED, "LLuL found a layer output whose batch is not doubled")
        return self.mix(grid[: batch // 2], grid[batch // 2:], tokens)

    def mix(self, base, extra, tokens=False):
        """Scales the region's output down and blends it into the region of the layer's own output (4-D grids)."""
        import torch
        extra = downscale(extra, self.c["down"], self.c["down_aa"], self.m)
        height, wide = base.shape[-2:]
        h, w = extra.shape[-2:]
        s0, t0 = int(wide * self.fx), int(height * self.fy)
        s1, t1 = s0 + w, t0 + h
        if wide < s1:
            s1 = wide
            s0 = s1 - w
        if height < t1:
            t1 = height
            t0 = t1 - h
        if s0 < 0 or t0 < 0:
            raise EngineError(INVALID_PARAMS, "LLuL: the region origin is outside the image")
        out = base.clone()
        low = out[:, :, t0:t1, s0:s1]
        dtype = low.dtype
        if self.c["force_float"]:
            low, extra = low.float(), extra.float()
        if self.mask is None:
            mixed = (slerp if self.c["intp"] == "slerp" else lerp)(low, extra, self.c["weight"])
        else:
            import torch.nn.functional as F
            weights = F.interpolate(self.mask.to(device=low.device, dtype=low.dtype), size=(h, w), mode="bilinear",
                                    align_corners=False, antialias=True) * self.c["weight"]
            mixed = torch.lerp(low, extra, weights)
        out[:, :, t0:t1, s0:s1] = mixed.to(dtype)
        if tokens:
            out = out.reshape(out.shape[0], out.shape[1], -1).transpose(1, 2).contiguous()
        return out

    # -- layer hooks ----------------------------------------------------------------------------------------------
    def _make(self, kind: str):
        state = {"skipped": True}

        def pre(module, args, kwargs):
            state["skipped"] = True
            if not self.active:
                return None
            x = _first(args, kwargs, "hidden_states" if kind != "resblock" else "input_tensor", 0)
            both = self.cut(x)
            if both is None:
                return None
            if kind == "resblock":
                temb = _first(args, kwargs, "temb", 1)
                if temb is not None:
                    args, kwargs = _with(args, kwargs, "temb", 1, _dup(temb))
                args, kwargs = _with(args, kwargs, "input_tensor", 0, both)
            else:
                args, kwargs = _with(args, kwargs, "hidden_states", 0, both)
                if kind in ("transformer", "x. attn."):
                    context = _first(args, kwargs, "encoder_hidden_states", 1)
                    if context is not None:
                        args, kwargs = _with(args, kwargs, "encoder_hidden_states", 1, _dup(context))
                if kind == "x. attn." or kind == "s. attn.":
                    mask = _first(args, kwargs, "attention_mask", 2)
                    if mask is not None:
                        args, kwargs = _with(args, kwargs, "attention_mask", 2, _dup(mask))
                if kind == "transformer":
                    for extra in ("encoder_attention_mask", "attention_mask", "class_labels"):
                        if kwargs.get(extra) is not None:
                            kwargs[extra] = _dup(kwargs[extra])
            state["skipped"] = False
            return args, kwargs

        def post(module, args, kwargs, output):
            if state["skipped"] or not self.active:
                return None
            if hasattr(output, "sample"):
                output.sample = self.blend(output.sample)
                return output
            if isinstance(output, tuple):
                return (self.blend(output[0]),) + output[1:]
            return self.blend(output)

        return pre, post

    def install(self):
        c = self.c
        try:
            self.handles.append(self.unet.register_forward_pre_hook(self._unet_pre, with_kwargs=True))
            self.handles.append(self.unet.conv_in.register_forward_pre_hook(self._latent))
            wanted = [k for k in c["apply_to"] if k != "out"]
            if wanted:
                kinds = resolve_layers(self.unet)
                for kind in wanted:
                    for name, module in kinds[kind]:
                        if c["layers"] is not None and not any(layer in name for layer in c["layers"]):
                            continue
                        pre, post = self._make(kind)
                        self.handles.append(module.register_forward_pre_hook(pre, with_kwargs=True))
                        self.handles.append(module.register_forward_hook(post, with_kwargs=True))
                        self.layers_hooked += 1
            if "out" in c["apply_to"]:
                norm, conv = self.unet.conv_norm_out, self.unet.conv_out
                if norm is None:
                    raise EngineError(UNSUPPORTED, "LLuL needs a UNet with an output norm layer")
                state = {"part": None, "busy": False}

                def pre(module, args):
                    state["part"] = None
                    if state["busy"] or not self.active:
                        return None
                    state["part"] = self.cut(args[0], stack=False)
                    return None

                def post(module, args, output):
                    part, state["part"] = state["part"], None
                    if part is None or not self.active:
                        return None
                    # The region runs through the output layers on its own, so that the layer's own output is
                    # bit-identical to a run without LLuL (fp16 convolutions differ with the batch size).
                    state["busy"] = True
                    try:
                        extra = conv(self.unet.conv_act(norm(part)))
                    finally:
                        state["busy"] = False
                    return self.mix(output, extra)

                self.handles.append(norm.register_forward_pre_hook(pre))
                self.handles.append(conv.register_forward_hook(post))
                self.layers_hooked += 1
            return self
        except BaseException:
            self.remove()
            raise

    def remove(self):
        for handle in reversed(self.handles):
            handle.remove()
        self.handles.clear()


def install(engine, pipe, gp, ctx, config: dict, *, pass_: str, steps: int, cleanups: list) -> Optional[LlulHook]:
    """Validates and hooks one pass (a no-op for ADetailer passes); appends the cleanup before it goes live."""
    c = normalize(config)
    if pass_ not in ("base", "hires"):
        return None
    width, height = int(gp.width), int(gp.height)
    if width < MIN_SIZE or height < MIN_SIZE:
        raise EngineError(INVALID_PARAMS, f"LLuL: the image is too small ({width}x{height}); it needs at least "
                                          f"{MIN_SIZE}x{MIN_SIZE}")
    x, y = origin(c, width, height)
    mask = load_mask(c["mask"])
    c = dict(c, intp="lerp") if mask is not None else c
    hook = LlulHook(pipe.unet, c, steps=steps, fx=x / width, fy=y / height, mask=mask)
    cleanups.append(hook.remove)
    hook.install()
    if hook.layers_hooked == 0:
        ctx.warn("LLuL: no UNet layer matches the Layers filter and Apply to settings; nothing changes")
    return hook


__all__ = ["DEFAULTS", "APPLY_TO", "normalize", "multiplier", "origin", "upscale", "downscale", "lerp", "slerp",
           "region_box", "load_mask", "resolve_layers", "LlulHook", "install"]
