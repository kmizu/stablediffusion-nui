"""AnimateDiff for the engine ("animatediff" generation hook + "animatediff_encode" request).

Implemented from the documented behaviour of continue-revolution/sd-webui-animatediff (README, docs/how-to-use.md,
docs/features.md; the source is CC BY-NC-SA 4.0 and was not read or copied) on top of diffusers' MotionAdapter.

The motion modules are *not* merged into a second UNet: like the extension, they are inserted into the loaded
checkpoint's UNet at run time. A forward hook after every attention block (after the ResNet where a block has no
attention) runs the matching temporal transformer on the frames that the sampler stacked on the batch axis, so
LoRAs, embeddings and the other UNet code of the engine keep working and the SD checkpoint is never reloaded.

  hook (params.hooks.animatediff): {"model", "video_length", "fps", "loop_number", "closed_loop", "batch_size",
      "stride", "overlap", "interp", "latent_power", "latent_scale", "last_frame", "latent_power_last",
      "latent_scale_last", "free_init", "free_init_filter", "free_init_d_s", "free_init_d_t", "free_init_n",
      "free_init_iterations", "linear_betas"}
  request animatediff_encode: {"images": [png...], "formats": ["GIF","MP4","WEBP","WEBM"], "fps", "loop_number",
      "closed_loop", "output_dir", "name"}
"""
from __future__ import annotations

import math
import os
import re
import shutil
import subprocess
from typing import Optional

from .hooks import coerce_bool
from .protocol import INVALID_PARAMS, MODEL_LOAD_FAILED, UNSUPPORTED, EngineError

CLOSED_LOOPS = ("N", "R-P", "R+P", "A")
VIDEO_FORMATS = ("GIF", "MP4", "WEBP", "WEBM")
FREE_INIT_FILTERS = ("butterworth", "gaussian", "box", "ideal")
MAX_FRAMES = 512
MAX_CONTEXT = 32  # the motion modules' positional table (motion_max_seq_length) is 32 for V2/V3 and 24 for V1

DEFAULTS: dict = {
    "model": "", "video_length": 16, "fps": 8, "loop_number": 0, "closed_loop": "R+P", "batch_size": 16, "stride": 1,
    "overlap": -1, "interp": "Off", "interp_x": 10, "latent_power": 1.0, "latent_scale": 32.0, "last_frame": "",
    "latent_power_last": 1.0, "latent_scale_last": 32.0, "free_init": False, "free_init_filter": "butterworth",
    "free_init_d_s": 0.25, "free_init_d_t": 0.25, "free_init_n": 4, "free_init_iterations": 3, "linear_betas": True,
}


def _bad(message: str):
    raise EngineError(INVALID_PARAMS, f"AnimateDiff: {message}")


def _number(cfg: dict, key: str, low: float, high: float, integer: bool = False) -> float:
    try:
        value = float(cfg[key])
    except (TypeError, ValueError):
        _bad(f"{key} must be a number (got {cfg[key]!r})")
    if not math.isfinite(value) or value < low or value > high:
        _bad(f"{key} must be in {low:g}..{high:g} (got {cfg[key]!r})")
    if integer and value != int(value):
        _bad(f"{key} must be a whole number (got {cfg[key]!r})")
    return int(value) if integer else value


def normalize(raw: dict) -> dict:
    """Validated copy of the hook values with every default filled in."""
    cfg = dict(DEFAULTS)
    cfg.update({k: v for k, v in (raw or {}).items() if k != "enabled"})
    out: dict = dict(cfg)
    out["model"] = str(cfg["model"] or "").strip()
    out["video_length"] = _number(cfg, "video_length", 0, MAX_FRAMES, True)
    out["fps"] = _number(cfg, "fps", 1, 120, True)
    out["loop_number"] = _number(cfg, "loop_number", 0, 1000, True)
    out["batch_size"] = _number(cfg, "batch_size", 1, MAX_CONTEXT, True)
    out["stride"] = _number(cfg, "stride", 1, 16, True)
    out["overlap"] = _number(cfg, "overlap", -1, MAX_CONTEXT - 1, True)
    out["interp_x"] = _number(cfg, "interp_x", 1, 100, True)
    out["latent_power"] = _number(cfg, "latent_power", 0, 100)
    out["latent_scale"] = _number(cfg, "latent_scale", 1e-6, 1e6)
    out["latent_power_last"] = _number(cfg, "latent_power_last", 0, 100)
    out["latent_scale_last"] = _number(cfg, "latent_scale_last", 1e-6, 1e6)
    out["last_frame"] = str(cfg["last_frame"] or "").strip()
    closed = str(cfg["closed_loop"])
    if closed not in CLOSED_LOOPS:
        _bad(f"closed_loop must be one of {', '.join(CLOSED_LOOPS)} (got {closed!r})")
    out["closed_loop"] = closed
    if str(cfg["interp"]) not in ("Off", "off", ""):
        raise EngineError(UNSUPPORTED, "AnimateDiff: frame interpolation (FILM) needs the Deforum extension and is not provided")
    out["interp"] = "Off"
    out["free_init"] = coerce_bool(cfg["free_init"], False)
    out["linear_betas"] = coerce_bool(cfg["linear_betas"], True)
    flt = str(cfg["free_init_filter"]).lower()
    if flt not in FREE_INIT_FILTERS:
        _bad(f"free_init_filter must be one of {', '.join(FREE_INIT_FILTERS)} (got {cfg['free_init_filter']!r})")
    out["free_init_filter"] = flt
    out["free_init_d_s"] = _number(cfg, "free_init_d_s", 0.01, 1.0)
    out["free_init_d_t"] = _number(cfg, "free_init_d_t", 0.01, 1.0)
    out["free_init_n"] = _number(cfg, "free_init_n", 1, 16, True)
    out["free_init_iterations"] = _number(cfg, "free_init_iterations", 1, 10, True)
    if out["video_length"] == 0:
        out["video_length"] = out["batch_size"]
    if out["video_length"] < 1:
        _bad("video_length must be at least 1")
    return out


# ------------------------------------------------------------------------------------------------- prompt travel
_KEY_LINE = re.compile(r"^\s*(\d+)\s*:\s*(.*\S)?\s*$")


def _clean(piece: str) -> str:
    return piece.strip().rstrip(",").strip()


def parse_travel(text: str, frames: int) -> Optional[tuple[list[int], list[str]]]:
    """Prompt travel: head lines, `frame: prompt` lines in ascending order, tail lines. None when the prompt has no
    keyframe line (an ordinary prompt). Returns (key frames, full prompt of every keyframe = head + key + tail)."""
    lines = [ln for ln in (text or "").splitlines() if ln.strip()]
    head: list[str] = []
    keys: list[tuple[int, str]] = []
    tail: list[str] = []
    for line in lines:
        match = _KEY_LINE.match(line)
        if match and not tail:
            keys.append((int(match.group(1)), match.group(2) or ""))
        elif match:
            _bad("prompt travel keyframe lines must follow each other; text between them belongs to the tail prompt")
        elif keys:
            tail.append(line)
        else:
            head.append(line)
    if not keys:
        return None
    frames_list = [k for k, _ in keys]
    if frames_list != sorted(set(frames_list)):
        _bad("prompt travel frame numbers must be strictly ascending")
    if frames_list[-1] >= frames:
        _bad(f"prompt travel frame {frames_list[-1]} must be smaller than the number of frames ({frames})")
    head_text = [_clean(x) for x in head if _clean(x)]
    tail_text = [_clean(x) for x in tail if _clean(x)]
    full = [", ".join(head_text + ([_clean(p)] if _clean(p) else []) + tail_text) for _, p in keys]
    return frames_list, full


def travel_weights(key_frames: list[int], frames: int, loop: bool) -> list[tuple[int, int, float]]:
    """For each frame (key index a, key index b, t): the conditioning is lerp(a, b, t). Before the first and after the
    last key the nearest prompt holds; with `loop` the span from the last key runs on to the first key (frame numbers
    wrapped by the video length), so the video closes in prompt space too."""
    last = len(key_frames) - 1
    out = []
    for f in range(frames):
        if loop:
            pos = f if f >= key_frames[0] else f + frames
            ends = key_frames + [key_frames[0] + frames]
            b = next((i for i in range(1, len(ends)) if ends[i] > pos), len(ends) - 1)
            a = b - 1
            out.append((a, b % len(key_frames), (pos - ends[a]) / (ends[b] - ends[a])))
        elif f <= key_frames[0]:
            out.append((0, 0, 0.0))
        elif f >= key_frames[-1]:
            out.append((last, last, 0.0))
        else:
            b = next(i for i, k in enumerate(key_frames) if k > f)
            out.append((b - 1, b, (f - key_frames[b - 1]) / (key_frames[b] - key_frames[b - 1])))
    return out


# ------------------------------------------------------------------------------------------------- context windows
def _slide(seq: list[int], length: int, overlap: int) -> list[list[int]]:
    n = len(seq)
    if n <= length:
        return [seq]
    step = max(1, length - overlap)
    starts = list(range(0, n - length + 1, step))
    if starts[-1] != n - length:
        starts.append(n - length)
    return [seq[s:s + length] for s in starts]


def strides_for(stride: int) -> list[int]:
    out, s = [1], 2
    while s <= stride:
        out.append(s)
        s *= 2
    return out


def context_windows(frames: int, length: int, overlap: int, stride: int, closed_loop: str) -> list[list[int]]:
    """The frame groups that go through the motion modules together in one sampling step.

    frames <= length: one window with every frame (stride and overlap do not apply, closed loop is only the output
    ping-pong of mode A). Otherwise sliding windows of `length` frames with `overlap` shared frames; for every motion
    stride 2^i <= stride the frames i apart form their own groups (offset 0..2^i-1); a closed loop adds windows that
    wrap from the last frames to the first ones (R-P/R+P: one around the seam, A: the whole ring)."""
    if frames <= length:
        return [list(range(frames))]
    overlap = length // 4 if overlap < 0 else min(overlap, length - 1)
    out: list[list[int]] = []
    for st in strides_for(stride):
        for offset in range(st):
            seq = list(range(offset, frames, st))
            if len(seq) < 2:
                continue
            out.extend(_slide(seq, length, overlap))
    if closed_loop in ("R-P", "R+P"):
        out.append([(frames - length // 2 + k) % frames for k in range(length)])
    elif closed_loop == "A":
        step = max(1, length - overlap)
        for start in range(frames - length // 2, frames - length // 2 + frames, step):
            out.append([(start + k) % frames for k in range(length)])
    seen, unique = set(), []
    for window in out:
        key = tuple(window)
        if key not in seen:
            seen.add(key)
            unique.append(window)
    return unique


def is_loop_prompt(closed_loop: str) -> bool:
    return closed_loop in ("R+P", "A")


# ------------------------------------------------------------------------------------------------- FreeInit
def freq_filter(shape: tuple[int, int, int], kind: str, d_s: float, d_t: float, n: int, device=None):
    """Low-pass mask over the centred (frames, height, width) spectrum; d_s/d_t are the spatial/temporal cutoffs."""
    import torch
    frames, height, width = shape

    def axis(size):
        return (torch.arange(size, dtype=torch.float32, device=device) * 2.0 / size) - 1.0
    ut = axis(frames).view(-1, 1, 1)
    uh = axis(height).view(1, -1, 1)
    uw = axis(width).view(1, 1, -1)
    d2 = (ut * (d_s / d_t)) ** 2 + uh ** 2 + uw ** 2
    if kind == "butterworth":
        return 1.0 / (1.0 + (d2 / (d_s ** 2)) ** n)
    if kind == "gaussian":
        return torch.exp(-d2 / (2.0 * d_s ** 2))
    if kind == "ideal":
        return (d2 <= d_s ** 2).to(torch.float32)
    mask = ((uh.abs() <= d_s) & (uw.abs() <= d_s) & (ut.abs() <= d_t)).to(torch.float32)
    return mask.expand(frames, height, width).contiguous()


def mix_frequencies(low_source, high_source, mask):
    """Low frequencies of `low_source`, high frequencies of `high_source`; tensors (B, C, F, H, W)."""
    import torch
    dims = (-3, -2, -1)
    low = torch.fft.fftshift(torch.fft.fftn(low_source.float(), dim=dims), dim=dims)
    high = torch.fft.fftshift(torch.fft.fftn(high_source.float(), dim=dims), dim=dims)
    mixed = low * mask + high * (1.0 - mask)
    mixed = torch.fft.ifftshift(mixed, dim=dims)
    return torch.fft.ifftn(mixed, dim=dims).real.to(low_source.dtype)


# ------------------------------------------------------------------------------------------------- motion module loading
_NAME_MAP = ((".norms.0", ".norm1"), (".norms.1", ".norm2"), (".ff_norm", ".norm3"), (".attention_blocks.0", ".attn1"),
             (".attention_blocks.1", ".attn2"), (".temporal_transformer", ""))


def is_original_format(state: dict) -> bool:
    return any(".temporal_transformer." in k for k in state)


def convert_original_state(state: dict) -> dict:
    """Original/A1111 motion module keys -> diffusers MotionAdapter keys (positional tables are computed)."""
    out = {}
    for key, value in state.items():
        if "pos_encoder" in key:
            continue
        for old, new in _NAME_MAP:
            key = key.replace(old, new)
        out[key] = value
    return out


def adapter_config_from_state(state: dict) -> dict:
    """MotionAdapter constructor arguments read from original-format weights (layout, widths, sequence table)."""
    def block_ids(prefix):
        return sorted({int(k.split(".")[1]) for k in state if k.startswith(prefix)})
    downs = block_ids("down_blocks.")
    if not downs:
        raise EngineError(MODEL_LOAD_FAILED, "AnimateDiff: motion module has no down_blocks.* weights")
    channels = tuple(int(state[f"down_blocks.{i}.motion_modules.0.temporal_transformer.proj_in.weight"].shape[0]) for i in downs)
    per_block = 1 + max(int(k.split(".")[3]) for k in state if k.startswith("down_blocks.0.motion_modules."))
    mid = [int(k.split(".")[2]) for k in state if k.startswith("mid_block.motion_modules.")]
    pe = next((v for k, v in state.items() if k.endswith("pos_encoder.pe")), None)
    return dict(block_out_channels=channels, motion_layers_per_block=per_block,
                motion_mid_block_layers_per_block=(1 + max(mid)) if mid else 1, use_motion_mid_block=bool(mid),
                motion_max_seq_length=int(pe.shape[1]) if pe is not None else 32, motion_num_attention_heads=8,
                motion_norm_num_groups=32)


def _load_tensors(path: str) -> dict:
    if path.lower().endswith(".safetensors"):
        from safetensors.torch import load_file
        return load_file(path)
    from .engine import safe_torch_load
    data = safe_torch_load(path, "AnimateDiff motion module")
    return data.get("state_dict", data) if isinstance(data, dict) else data


def search_dirs(model_path: str) -> list[str]:
    """Places a bare motion module file name is looked up in."""
    dirs = []
    env = os.environ.get("SDFX_ANIMATEDIFF_DIR")
    if env:
        dirs.append(env)
    if model_path:
        root = os.path.dirname(os.path.dirname(os.path.abspath(model_path)))
        for sub in ("AnimateDiff", "animatediff", "motion_module", "motion-module"):
            dirs.append(os.path.join(root, sub))
    return dirs


def resolve_model(name: str, model_path: str = "") -> tuple[str, str]:
    """('dir'|'file'|'hub', location). A path, a bare file/folder name found in the search folders, or a Hugging Face
    repo id such as guoyww/animatediff-motion-adapter-v1-5-2."""
    if not name:
        raise EngineError(INVALID_PARAMS, "AnimateDiff: choose a motion module (a file, a diffusers folder, or a "
                                          "Hugging Face repo id such as guoyww/animatediff-motion-adapter-v1-5-2)")
    candidates = [name] + [os.path.join(d, name) for d in search_dirs(model_path)]
    for cand in candidates:
        if os.path.isdir(cand):
            return "dir", cand
        if os.path.isfile(cand):
            return "file", cand
    if re.fullmatch(r"[\w.-]+/[\w.-]+", name) and not os.path.isabs(name):
        return "hub", name
    raise EngineError(INVALID_PARAMS, f"AnimateDiff: motion module not found: {name}")


_ADAPTERS: dict = {}


def _fp16_variant(path: str) -> Optional[str]:
    plain = os.path.isfile(os.path.join(path, "diffusion_pytorch_model.safetensors"))
    half = os.path.isfile(os.path.join(path, "diffusion_pytorch_model.fp16.safetensors"))
    return "fp16" if half and not plain else None


def load_adapter(kind: str, location: str, dtype):
    """The diffusers MotionAdapter for the module, cached (kept on the CPU between jobs)."""
    import torch
    key = (kind, location, os.path.getmtime(location) if kind != "hub" else 0)
    cached = _ADAPTERS.get(key)
    if cached is not None:
        return cached
    try:
        from diffusers import MotionAdapter  # type: ignore
    except ImportError as e:
        raise EngineError(UNSUPPORTED, f"AnimateDiff needs diffusers with MotionAdapter: {e}")
    try:
        if kind == "dir":
            adapter = MotionAdapter.from_pretrained(location, torch_dtype=dtype, variant=_fp16_variant(location))
        elif kind == "hub":
            try:
                adapter = MotionAdapter.from_pretrained(location, torch_dtype=dtype, variant="fp16")
            except Exception:
                try:
                    adapter = MotionAdapter.from_pretrained(location, torch_dtype=dtype)
                except Exception as e:
                    raise EngineError(MODEL_LOAD_FAILED, f"AnimateDiff: motion module '{location}' is not a local file or folder "
                                                         f"and could not be downloaded from Hugging Face: {str(e).splitlines()[0]}")
        else:
            state = _load_tensors(location)
            if not is_original_format(state):
                raise EngineError(MODEL_LOAD_FAILED, "AnimateDiff: a single file must be an original/A1111 motion module "
                                                     "(mm_sd15_v2.safetensors ...); use a folder for the diffusers format")
            config = adapter_config_from_state(state)
            adapter = MotionAdapter(**config)
            result = adapter.load_state_dict(convert_original_state(state), strict=False)
            # the sinusoidal position tables are rebuilt by the adapter itself (same values as pos_encoder.pe)
            bad = [k for k in list(result.missing_keys) + list(result.unexpected_keys) if not k.endswith(".pos_embed.pe")]
            if bad:
                raise EngineError(MODEL_LOAD_FAILED, f"AnimateDiff: motion module weights do not fit the adapter layout: {bad[:3]}")
            adapter = adapter.to(dtype)
    except EngineError:
        raise
    except Exception as e:
        raise EngineError(MODEL_LOAD_FAILED, f"AnimateDiff: failed to load the motion module: {e}")
    if kind != "file":
        reset_position_tables(adapter)
    adapter.eval().requires_grad_(False)
    _ADAPTERS.clear()
    _ADAPTERS[key] = adapter
    return adapter


def reset_position_tables(adapter) -> int:
    """Rebuild every sinusoidal position table the way the original motion modules have it (sin / cos interleaved, equal to
    their `pos_encoder.pe`). Some diffusers adapters on the Hub (guoyww/animatediff-motion-adapter-v1-5-2) store the tables in
    another layout (all sines, then all cosines) that the trained weights do not expect: frames come out grey and washed out
    with them (measured: contrast 24 against 50 with the original tables, same seed and settings); others (the SDXL beta
    adapter) already hold the original layout, for them this changes nothing. Returns how many tables were rebuilt."""
    import torch
    count = 0
    for module in adapter.modules():
        table = getattr(module, "pe", None)
        if module.__class__.__name__ != "SinusoidalPositionalEmbedding" or not isinstance(table, torch.Tensor):
            continue
        fresh = type(module)(int(table.shape[-1]), int(table.shape[1])).pe
        with torch.no_grad():
            table.copy_(fresh.to(table.dtype))
        count += 1
    return count


def max_context(adapter) -> int:
    for module in adapter.modules():
        table = getattr(module, "num_positional_embeddings", None)
        if table:
            return int(table)
    return MAX_CONTEXT


class MotionState:
    num_frames = 1


class MotionHooks:
    """Insert the adapter's temporal transformers into a UNet2DConditionModel with forward hooks."""

    def __init__(self, unet, adapter, state: MotionState):
        self.unet, self.adapter, self.state = unet, adapter, state
        self.handles: list = []

    def _attach(self, block, modules, where: str):
        attentions = list(getattr(block, "attentions", None) or [])
        targets = attentions or list(getattr(block, "resnets", None) or [])
        if len(targets) != len(modules):
            raise EngineError(UNSUPPORTED, f"AnimateDiff: the motion module does not match this checkpoint's UNet "
                                           f"({where} has {len(targets)} layers, the module {len(modules)}); "
                                           "SD 1.x modules need SD 1.x checkpoints and SDXL modules SDXL ones")
        use_attention = bool(attentions)
        for target, motion in zip(targets, modules):
            def hook(_module, _args, output, motion=motion, use_attention=use_attention):
                if use_attention:
                    sample = output[0] if isinstance(output, tuple) else output.sample
                    moved = motion(sample, num_frames=self.state.num_frames)
                    return (moved,) + tuple(output[1:]) if isinstance(output, tuple) else (moved,)
                return motion(output, num_frames=self.state.num_frames)
            self.handles.append(target.register_forward_hook(hook))

    def install(self):
        unet, adapter = self.unet, self.adapter
        if len(adapter.down_blocks) != len(unet.down_blocks) or len(adapter.up_blocks) != len(unet.up_blocks):
            raise EngineError(UNSUPPORTED, "AnimateDiff: the motion module has a different number of UNet blocks "
                                           "than this checkpoint (SD 1.x modules need SD 1.x checkpoints, SDXL modules SDXL ones)")
        try:
            for i, block in enumerate(adapter.down_blocks):
                self._attach(unet.down_blocks[i], block.motion_modules, f"down block {i}")
            if getattr(adapter, "mid_block", None) is not None:
                self._attach(unet.mid_block, adapter.mid_block.motion_modules, "mid block")
            for i, block in enumerate(adapter.up_blocks):
                self._attach(unet.up_blocks[i], block.motion_modules, f"up block {i}")
        except Exception:
            self.remove()
            raise
        return self

    def remove(self):
        for handle in self.handles:
            handle.remove()
        self.handles.clear()

    def __enter__(self):
        return self.install()

    def __exit__(self, *exc):
        self.remove()
        return False


# ------------------------------------------------------------------------------------------------- generation
def validate_job(engine, gp, mode: str, cfg: dict, *, hires: bool, controlnet: bool, inpaint_model: bool,
                 model_keyword: bool, refine: bool, hires_plan: bool) -> None:
    kind = engine.kind or ""
    if kind not in ("sd15", "sdxl"):
        raise EngineError(UNSUPPORTED, f"AnimateDiff supports SD 1.x and SDXL checkpoints (the loaded one is '{kind}')")
    if gp.batch_size != 1:
        raise EngineError(INVALID_PARAMS, "AnimateDiff: batch size must be 1 (one video per image; use the batch count for several videos)")
    for flag, label in ((hires or hires_plan, "Hires. fix"), (controlnet, "ControlNet units (ControlNet V2V is not provided)"),
                        (inpaint_model, "inpainting checkpoints"), (model_keyword, "Model Keyword"),
                        (refine, "img2img Hires fix")):
        if flag:
            raise EngineError(UNSUPPORTED, f"AnimateDiff cannot be combined with {label}")
    if mode == "img2img" and gp.img2img.mask is not None:
        raise EngineError(UNSUPPORTED, "AnimateDiff does not support inpainting masks")
    if cfg["free_init"] and mode == "img2img":
        raise EngineError(UNSUPPORTED, "AnimateDiff: FreeInit is only available for txt2img")
    if cfg["last_frame"] and mode != "img2img":
        raise EngineError(INVALID_PARAMS, "AnimateDiff: a last frame needs the img2img tab")
    if gp.width % 8 or gp.height % 8:
        raise EngineError(INVALID_PARAMS, "AnimateDiff: width and height must be multiples of 8")



# Hooks that work with this loop: PNG metadata and the post-processing of finished frames (NSFW censor, face restore,
# ADetailer; each frame on its own). Every other active hook acts inside the engine's own sampling pass and is not applied
# to the video; the job warns about it instead of dropping it silently.
COMPATIBLE_HOOKS = {"animatediff", "pnginfo_injection", "nsfw_checker", "face_restore", "adetailer"}


def warn_unapplied(gp, ctx) -> None:
    from .hooks import hook_config
    names = sorted(name for name in (gp.hooks or {})
                   if name not in COMPATIBLE_HOOKS and not name.endswith("_xyz") and hook_config(gp.hooks, name, {}) is not None)
    if names:
        ctx.warn("AnimateDiff does not apply these hooks to the video frames: " + ", ".join(names))


def _spatial_noise(engine, gp, seed: int, shape):
    import torch
    device = engine._rng_device(gp)
    generator = torch.Generator(device).manual_seed(int(seed) & 0xFFFFFFFFFFFFFFFF)
    return torch.randn(shape, generator=generator, device=device, dtype=torch.float32).to(engine.device, engine.dtype), generator


def init_latent_mix(first, last, noise, frames: int, cfg: dict):
    """img2vid: frame i starts as init*a + noise*(1-a) with a = 1 - i^power/scale (the first image) and, with a last
    image, the mirrored term for the end; weights are clamped to [0, 1] and, where both exceed 1 in sum, normalised."""
    import torch
    idx = torch.arange(frames, dtype=torch.float32, device=noise.device)
    a = (1.0 - idx.pow(cfg["latent_power"]) / cfg["latent_scale"]).clamp(0.0, 1.0)
    if last is None:
        b = torch.zeros_like(a)
    else:
        b = (1.0 - (frames - 1 - idx).pow(cfg["latent_power_last"]) / cfg["latent_scale_last"]).clamp(0.0, 1.0)
    total = (a + b).clamp(min=1.0)
    a, b = a / total, b / total
    rest = (1.0 - a - b).clamp(min=0.0)
    out = noise * rest.view(1, 1, -1, 1, 1).to(noise.dtype)
    out = out + first.unsqueeze(2) * a.view(1, 1, -1, 1, 1).to(noise.dtype)
    if last is not None:
        out = out + last.unsqueeze(2) * b.view(1, 1, -1, 1, 1).to(noise.dtype)
    return out


def _encode_image(engine, gp, path: str, w: int, h: int, generator):
    import torch
    from .images import load_rgb, resize_image
    image = resize_image(gp.img2img.resize_mode, load_rgb(path), w, h)
    vae = engine.pipe.vae
    original_dtype = vae.dtype
    upcast = original_dtype == torch.float16 and getattr(vae.config, "force_upcast", False)  # SDXL VAE overflows in fp16
    try:
        if upcast:
            vae.to(torch.float32)
        tensor = engine.pipe.image_processor.preprocess(image).to(engine.device, vae.dtype)
        with torch.no_grad():
            latent = vae.encode(tensor).latent_dist.sample(generator)
    finally:
        if upcast:
            vae.to(original_dtype)
    return (latent * vae.config.scaling_factor).to(engine.dtype)


def generate_frames(engine, gp, req, ctx, cfg: dict, mode: str, seed: int) -> list:
    """The video as a list of PIL frames (the ping-pong frames of closed loop A are added when encoding)."""
    adapters_used: list = []
    try:
        return _generate(engine, gp, req, ctx, cfg, mode, seed, adapters_used)
    finally:
        for adapter in adapters_used:
            if engine.cuda:
                adapter.to("cpu")


def _generate(engine, gp, req, ctx, cfg: dict, mode: str, seed: int, adapters_used: list) -> list:
    import torch
    from . import prompt as prompt_mod
    from .params import img2img_steps
    frames = cfg["video_length"]
    length = cfg["batch_size"]
    kind, location = resolve_model(cfg["model"], (engine.model or {}).get("path", ""))
    adapter = load_adapter(kind, location, engine.dtype)
    adapters_used.append(adapter)
    if length > max_context(adapter):
        _bad(f"context batch size {length} is larger than this motion module's maximum ({max_context(adapter)})")
    if engine.sdxl != (len(adapter.down_blocks) == 3):
        raise EngineError(UNSUPPORTED, "AnimateDiff: the motion module and the checkpoint are for different families "
                                       "(SD 1.x module with an SDXL checkpoint or the reverse)")
    w, h = gp.width, gp.height
    pipe = engine.pipe
    unet = pipe.unet
    engine._activate(pipe)
    if engine.cuda:
        adapter.to(engine.device)
    warn_unapplied(gp, ctx)
    # The motion modules were trained on the linear beta schedule (diffusers' recommended AnimateDiff scheduler); with the
    # checkpoint's scaled-linear one the frames come out grey and washed out.
    saved_overrides = getattr(engine, "_ms_overrides", {})
    if cfg["linear_betas"]:
        engine._ms_overrides = dict(saved_overrides, beta_schedule="linear")
    try:
        sched, _plan = engine._scheduler(gp.sampler_name, gp.scheduler, ctx)
    finally:
        engine._ms_overrides = saved_overrides
    device = engine.device

    # --- conditioning: one embedding per prompt-travel keyframe, interpolated per frame ---------------------------
    travel = parse_travel(gp.prompt, frames)
    texts = travel[1] if travel else [gp.prompt]
    encoded = [engine._encode_plain(text, gp.negative_prompt, gp, 1) for text in texts]
    empty = engine._encode_plain("", "", gp, 1)["prompt_embeds"]
    longest = max(max(e["prompt_embeds"].shape[1], e["negative_prompt_embeds"].shape[1]) for e in encoded)

    def pad(x):
        if x.shape[1] < longest:
            x = torch.cat([x] + [empty.to(x.device, x.dtype)] * ((longest - x.shape[1]) // empty.shape[1]), dim=1)
        return x
    keys = torch.cat([pad(e["prompt_embeds"]) for e in encoded], dim=0)
    uncond = pad(encoded[0]["negative_prompt_embeds"])
    if travel:
        loop_prompt = is_loop_prompt(cfg["closed_loop"]) and (frames > length or cfg["closed_loop"] == "A")
        weights = travel_weights(travel[0], frames, loop_prompt)
        cond = torch.stack([torch.lerp(keys[a], keys[b], t) for a, b, t in weights])
    else:
        cond = keys[:1].expand(frames, -1, -1)
    uncond = uncond.expand(frames, -1, -1)
    add_cond: Optional[dict] = None
    if engine.sdxl:
        pooled = encoded[0]["pooled_prompt_embeds"]
        pooled_neg = encoded[0]["negative_pooled_prompt_embeds"]
        if travel:
            pooled = torch.stack([torch.lerp(encoded[a]["pooled_prompt_embeds"][0], encoded[b]["pooled_prompt_embeds"][0], t)
                                  for a, b, t in weights])
        else:
            pooled = pooled.expand(frames, -1)
        ids = torch.tensor([[h, w, 0, 0, h, w]], dtype=engine.dtype, device=device)
        add_cond = {"cond": (pooled.to(device), ids), "uncond": (pooled_neg.expand(frames, -1).to(device), ids)}

    # --- sampler schedule and start latents --------------------------------------------------------------------
    shape = (1, int(unet.config.in_channels), frames, h // 8, w // 8)
    noise, generator = _spatial_noise(engine, gp, seed, shape)
    sched_kw = {}
    steps = gp.steps
    t_start = 0
    first_latent = last_latent = None
    if mode == "img2img":
        steps, _strength, actual = img2img_steps(gp.steps, gp.img2img.denoising_strength)
        t_start = steps - actual
        first_latent = _encode_image(engine, gp, gp.img2img.init_image, w, h, generator)
        if cfg["last_frame"]:
            if not os.path.isfile(cfg["last_frame"]):
                _bad(f"last frame image not found: {cfg['last_frame']}")
            last_latent = _encode_image(engine, gp, cfg["last_frame"], w, h, generator)
    sched.set_timesteps(steps, device=device)
    order = int(getattr(sched, "order", 1))
    timesteps = sched.timesteps[t_start * order:]
    if hasattr(sched, "set_begin_index"):
        sched.set_begin_index(t_start * order)
    if mode == "img2img":
        start = init_latent_mix(first_latent, last_latent, noise, frames, cfg)
        latents = sched.add_noise(start, noise, timesteps[:1])
    else:
        latents = noise * getattr(sched, "init_noise_sigma", 1.0)

    windows = context_windows(frames, length, cfg["overlap"], cfg["stride"], cfg["closed_loop"])
    counts = torch.zeros(frames, dtype=torch.float32, device=device)
    for window in windows:
        counts[window] += 1
    if bool((counts == 0).any()):
        raise EngineError(INVALID_PARAMS, "AnimateDiff: internal error, a frame is outside every context window")
    state = MotionState()
    cfg_scale = float(gp.cfg_scale)
    iterations = cfg["free_init_iterations"] if cfg["free_init"] else 1
    total = steps if mode != "img2img" else len(timesteps)
    done = 0

    def predict(unet_in, t, window, text_embeds, key):
        kwargs = {}
        if add_cond is not None:
            pooled_all, ids = add_cond[key]
            kwargs["added_cond_kwargs"] = {"text_embeds": pooled_all[window], "time_ids": ids.expand(len(window), -1)}
        return unet(unet_in, t, encoder_hidden_states=text_embeds[window], return_dict=False, **kwargs)[0]

    def denoise(current, run_timesteps, iteration):
        nonlocal done
        for i, t in enumerate(run_timesteps):
            ctx.check_interrupt()
            scaled = sched.scale_model_input(current, t)
            accumulated = torch.zeros_like(current, dtype=torch.float32)
            for window in windows:
                ctx.check_interrupt()
                chunk = scaled[:, :, window][0].permute(1, 0, 2, 3).contiguous()
                state.num_frames = len(window)
                eps_cond = predict(chunk, t, window, cond, "cond")
                if cfg_scale != 1.0:
                    eps_uncond = predict(chunk, t, window, uncond, "uncond")
                    eps = eps_uncond + cfg_scale * (eps_cond - eps_uncond)
                else:
                    eps = eps_cond
                accumulated[:, :, window] += eps.float().permute(1, 0, 2, 3).unsqueeze(0)
            noise_pred = (accumulated / counts.view(1, 1, -1, 1, 1)).to(current.dtype)
            step_kw = {"generator": generator} if "generator" in _signature(sched.step) else {}
            current = sched.step(noise_pred, t, current, return_dict=False, **step_kw)[0]
            done += 1
            ctx.progress(done, total * iterations, "base")
        return current

    with torch.inference_mode(), MotionHooks(unet, adapter, state):
        latents = denoise(latents, timesteps, 0)
        if cfg["free_init"]:
            mask = freq_filter((frames, h // 8, w // 8), cfg["free_init_filter"], cfg["free_init_d_s"], cfg["free_init_d_t"],
                               cfg["free_init_n"], device)
            for iteration in range(1, iterations):
                ctx.check_interrupt()
                sched.set_timesteps(steps, device=device)
                timesteps = sched.timesteps
                if hasattr(sched, "set_begin_index"):
                    sched.set_begin_index(0)
                diffuse_noise = torch.randn(shape, generator=generator, device=generator.device, dtype=torch.float32).to(device, engine.dtype)
                fresh = torch.randn(shape, generator=generator, device=generator.device, dtype=torch.float32).to(device, engine.dtype)
                noisy = sched.add_noise(latents, diffuse_noise, timesteps[:1])
                if hasattr(sched, "init_noise_sigma") and getattr(sched, "init_noise_sigma", 1.0) not in (1, 1.0):
                    noisy = noisy / sched.init_noise_sigma  # back to unit-variance space for the frequency mix
                mixed = mix_frequencies(noisy, fresh, mask)
                latents = mixed * getattr(sched, "init_noise_sigma", 1.0)
                latents = denoise(latents, timesteps, iteration)

    out = []
    flat = latents[0].permute(1, 0, 2, 3).contiguous()
    chunk = 1 if engine.sdxl else 4  # the SDXL VAE decodes in float32: one large frame at a time
    for start in range(0, frames, chunk):
        ctx.check_interrupt()
        out.extend(_decode_frames(engine, flat[start:start + chunk], ctx, getattr(req, "vae_recovery_dir", None) or req.output_dir))
    return out


def _decode_frames(engine, latents, ctx, recovery_dir):
    """VAE-decode latents to PIL images on the compute device (model offload keeps the VAE on the CPU until its decode
    hook runs, so the latents must not follow the VAE's current device)."""
    return engine._decode(latents, ctx, recovery_dir, device=engine.device if engine.cuda else None)


def _signature(fn) -> set:
    import inspect
    try:
        return set(inspect.signature(fn).parameters)
    except (TypeError, ValueError):
        return set()


# ------------------------------------------------------------------------------------------------- output
def pingpong(frames: list) -> list:
    """Closed loop 'A' for a video that fits one context: the frames followed by their reverse without the two ends."""
    return list(frames) + list(frames[-2:0:-1]) if len(frames) > 2 else list(frames)


def ffmpeg_path() -> Optional[str]:
    return shutil.which("ffmpeg")


def encode_videos(frames: list, formats: list, fps: int, loop_number: int, out_dir: str, stem: str) -> dict:
    """Write the frames as GIF / WEBP (Pillow) and MP4 / WEBM (ffmpeg); returns {format: path}."""
    from .images import next_output_path
    if not frames:
        raise EngineError(INVALID_PARAMS, "AnimateDiff: no frames to encode")
    result: dict = {}
    duration = max(1, int(round(1000 / max(1, fps))))
    for fmt in formats:
        fmt = fmt.upper()
        if fmt not in VIDEO_FORMATS:
            raise EngineError(INVALID_PARAMS, f"AnimateDiff: unknown video format '{fmt}'")
        ext = "." + fmt.lower()
        path = next_output_path(out_dir, stem, ext)
        if fmt == "GIF":
            frames[0].save(path, save_all=True, append_images=frames[1:], duration=duration, loop=loop_number,
                           optimize=False)
        elif fmt == "WEBP":
            frames[0].save(path, format="WEBP", save_all=True, append_images=frames[1:], duration=duration,
                           loop=loop_number, quality=90, method=4)
        else:
            exe = ffmpeg_path()
            if not exe:
                raise EngineError(UNSUPPORTED, f"AnimateDiff: {fmt} output requires ffmpeg on PATH")
            codec = ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18"] if fmt == "MP4" else \
                ["-c:v", "libvpx-vp9", "-pix_fmt", "yuv420p", "-b:v", "0", "-crf", "30"]
            width, height = frames[0].size
            command = [exe, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{width}x{height}",
                       "-framerate", str(fps), "-i", "-", *codec, path]
            proc = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                for frame in frames:
                    proc.stdin.write(frame.convert("RGB").tobytes())
                proc.stdin.close()
                error = proc.stderr.read().decode("utf-8", "replace")
                code = proc.wait()
            finally:
                if proc.poll() is None:
                    proc.kill()
                    proc.wait()
                for stream in (proc.stdin, proc.stderr):
                    if stream is not None and not stream.closed:
                        stream.close()
            if code != 0:
                raise EngineError(UNSUPPORTED, f"AnimateDiff: ffmpeg failed for {fmt}: {error.strip()[:300]}")
        result[fmt] = path
    return result


def encode_request(params: dict, ctx) -> dict:
    """Engine request animatediff_encode: finished frame PNGs -> video files in params['output_dir']."""
    from .images import load_rgb
    paths = params.get("images") or []
    if not isinstance(paths, list) or not paths or any(not isinstance(p, str) for p in paths):
        raise EngineError(INVALID_PARAMS, "animatediff_encode needs a non-empty images list")
    formats = params.get("formats") or ["GIF"]
    if not isinstance(formats, list):
        raise EngineError(INVALID_PARAMS, "animatediff_encode formats must be a list")
    closed = str(params.get("closed_loop", "N"))
    if closed not in CLOSED_LOOPS:
        raise EngineError(INVALID_PARAMS, f"closed_loop must be one of {', '.join(CLOSED_LOOPS)}")
    frames = []
    for path in paths:
        ctx.check_interrupt()
        if not os.path.isfile(path):
            raise EngineError(INVALID_PARAMS, f"frame not found: {path}")
        frames.append(load_rgb(path))
    if len({f.size for f in frames}) != 1:
        raise EngineError(INVALID_PARAMS, "animatediff_encode frames must have the same size")
    length = int(params.get("batch_size", 16))
    if closed == "A" and len(frames) <= length:
        frames = pingpong(frames)
    out_dir = params.get("output_dir") or "."
    os.makedirs(out_dir, exist_ok=True)
    outputs = encode_videos(frames, [str(f) for f in formats], int(params.get("fps", 8)), int(params.get("loop_number", 0)),
                            out_dir, str(params.get("name") or "animatediff"))
    return {"outputs": outputs, "frames": len(frames)}
