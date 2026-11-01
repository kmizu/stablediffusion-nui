"""CD (Color/Detail) Tuner, re-implemented from hako-mikan/sd-webui-cd-tuner aafdbdd (AGPL-3.0) behaviour.

The source is an A1111 AlwaysOn script that keeps a small per-batch state machine and acts from two sampler
callbacks. NUI keeps that state machine (`TunerState`) and feeds it from diffusers hooks (`PassHook`):

* Before each model evaluation (the source's `on_cfg_denoiser`), a UNet forward pre-hook.
  - Detail d1/d2 and Contrast con1: on the first evaluation of a pass, five UNet tensors are rewritten from their
    original values: `conv_in.weight * (1 - d1/100)`, `conv_in.bias * (1 + d1/50)`,
    `conv_norm_out.weight * (1 - d2/100)`, `conv_norm_out.bias * (1 + d2/50)` and `conv_out.bias + [con1/50, 0, 0, 0]`
    (the source's `input_blocks.0.0`, `out.0` and `out.2`). A pass is detected, like the source, by the latent area
    growing (hires). In it hd1/hd2 replace d1/d2 when non-zero and hr-scaling multiplies both by the area ratio.
    Stop Step / Hr-Stop Step restore the weights from that step of the pass; the restore also leaves them restored.
  - Color Map: during the first `Mapping Stop Step` steps the model input is blended towards the region colours,
    cond rows minus the shift and uncond rows plus it, exactly as the A1111 backend lays them out. The source edits
    k-diffusion's `x_in`, which also feeds the `x - sigma * eps` of the denoised result, so NUI corrects the model
    output by the same input change; the scheduler then sees the source's denoised prediction.
* After each model evaluation (the source's `on_cfg_denoised`), a UNet forward hook. Contrast 2, Brightness and the
  three colour axes shift the denoised prediction of every row by `-20/3 * [con2/50] + colour table` once, at step
  `total - 2 - dis`, in the hires pass when hires fix is on. NUI writes the equivalent noise/v prediction change.
* saturation/saturation2 scale VAE decoder weights (`* (1 + sat * 0.075)`, `* (1 + sat2 * 0.02)`) for the main
  passes of the batch; the decoder keys are the source's ldm paths in diffusers naming.

Values and timing follow the source, including its quirks: weights left from the base pass stay in the hires pass
when Hr-Stop Step is 0, a Color Map without detail settings colours only the first hires step, and follow-up passes
(ADetailer, img2img hires) keep acting on the batch's state unless "Apply once" is set. All weight edits are made
on the live tensors (or the accelerate offload map) and restored when each pass ends, on success and on error.
"""
from __future__ import annotations

import copy
import logging
import math
from typing import Any, Callable, Optional

from .protocol import INVALID_PARAMS, UNSUPPORTED, EngineError

log = logging.getLogger("sdfx.cd_tuner")

# The source's prompt identifiers, in its positional order.
IDENTIFIERS = ("d1", "d2", "con1", "con2", "bri", "col1", "col2", "col3", "hd1", "hd2", "hrs", "st1", "st2", "dis",
               "sat", "sat2")
UNSET = [0] * 11 + [-1, -1, 0, 0, 0]
DEFAULTS: dict = dict(zip(IDENTIFIERS, UNSET), split_ratio="1,1", split_by="Horizontal", colors="", map_stop_step=2,
                      map_strength=1.0, apply_once=False)

# Latent offsets of [brightness, Cyan-Red, Magenta-Green, Yellow-Blue] for latent channels 1..3.
COLOR_TABLE = {
    False: ((-1.0, 1 / 3, 2 / 3), (1.0, 1.0, 0.0), (0.0, -1.0, -1.0), (1.0, 0.0, 1.0)),  # SD1.x / SD2
    True: ((0.0, 0.0, 1.0), (1.0, 0.0, 0.0), (-1.0, -1.0, 0.0), (-1.0, 1.0, 0.0)),        # SDXL
}

UNET_KEYS = ("conv_in.weight", "conv_in.bias", "conv_norm_out.weight", "conv_norm_out.bias", "conv_out.bias")
# ldm decoder.up.{i} is diffusers decoder.up_blocks.{3 - i}; nin_shortcut is conv_shortcut.
VAE_KEYS = ("decoder.up_blocks.2.upsamplers.0.conv.weight", "decoder.up_blocks.3.resnets.0.conv_shortcut.weight")
VAE_KEYS2 = ("decoder.up_blocks.0.upsamplers.0.conv.weight", "decoder.up_blocks.1.upsamplers.0.conv.weight",
             "decoder.up_blocks.2.resnets.0.conv_shortcut.weight", "decoder.conv_in.weight", "decoder.conv_out.weight")
SUPPORTED_KINDS = ("sd15", "sd15-inpaint", "sd2", "sdxl", "sdxl-inpaint")
MAIN_PASSES = ("base", "hires")


def _bad(message: str) -> EngineError:
    return EngineError(INVALID_PARAMS, f"CD Tuner: {message}")


def _number(key: str, value) -> float:
    if isinstance(value, bool):
        raise _bad(f"{key} must be a number")
    try:
        out = float(value)
    except (TypeError, ValueError):
        raise _bad(f"{key} must be a number, got {value!r}")
    if not math.isfinite(out):
        raise _bad(f"{key} must be finite")
    return out


def normalize(config: Optional[dict]) -> dict:
    """Hook config -> typed settings. Prompt overrides (`<cdt:...>`) are already merged in by the plugin."""
    from .hooks import coerce_bool
    c = dict(DEFAULTS)
    c.update({k: v for k, v in (config or {}).items() if k in DEFAULTS and v is not None})
    out: dict = {k: _number(k, c[k]) for k in IDENTIFIERS if k != "hrs"}
    # hr-scaling is a checkbox in the plugin; the source's value is 1 when on
    out["hrs"] = (1.0 if c["hrs"] else 0.0) if isinstance(c["hrs"], bool) else _number("hrs", c["hrs"])
    for k in ("split_ratio", "split_by", "colors"):
        if not isinstance(c[k], str):
            raise _bad(f"{k} must be text")
        out[k] = c[k]
    stop = c["map_stop_step"]
    try:
        # The source calls int(): a typed "2.5" fails, a slider value is truncated.
        out["map_stop_step"] = int(stop) if isinstance(stop, str) else int(_number("map_stop_step", stop))
    except ValueError:
        raise _bad(f"Mapping Stop Step must be an integer, got {stop!r}")
    out["map_strength"] = _number("map_strength", c["map_strength"].strip() if isinstance(c["map_strength"], str)
                                  else c["map_strength"])
    out["apply_once"] = coerce_bool(c["apply_once"], False)
    return out


def fine_ratios(r) -> list:
    """[conv_in.weight, conv_in.bias, norm_out.weight, norm_out.bias] factors and the conv_out.bias offset."""
    d1, d2, con1 = r[0], r[1], r[2]
    return [1 - d1 * 0.01, 1 + d1 * 0.02, 1 - d2 * 0.01, 1 + d2 * 0.02, [con1 * 0.02, 0, 0, 0]]


def color_offsets(cols, sdxl: bool) -> list:
    """Latent channel 1..3 offsets for [brightness, Cyan-Red, Magenta-Green, Yellow-Blue] (subtracted by callers)."""
    table = COLOR_TABLE[bool(sdxl)]
    return [sum(row[j] * cols[i] * 0.02 for i, row in enumerate(table)) for j in range(3)]


def _spans(weights: list) -> list:
    total = sum(weights)
    if total == 0:
        raise _bad("Split Ratio values must not sum to 0")
    out, start = [], 0
    for w in (x / total for x in weights):
        out.append([start, start + w])
        start = start + w
    return out


def make_cells(ratio: str, mode: str) -> tuple:
    """Split Ratio -> (outer spans, inner spans per outer span), Regional Prompter style like the source."""
    text = ratio.replace(",", " ")
    rows = text.split("|") if "|" in text else text.split(";")
    if len(rows) == 1:
        rows[0] = "1 " + rows[0]
    outer, inner = [], []
    for row in rows:
        try:
            values = [float(x) for x in row.split(" ")]
        except ValueError:
            raise _bad(f"Split Ratio {ratio!r} must be numbers separated by single commas or spaces")
        if len(values) == 1:
            values = values * 2
        outer.append(values[0])
        inner.append(_spans(values[1:]))
    return _spans(outer), inner


def parse_colors(text: str) -> list:
    """`colors` -> [[brightness, Cyan-Red, Magenta-Green, Yellow-Blue], ...]; 3 values get their mean as brightness."""
    regions = text.split("|") if "|" in text else text.split(";")
    out = []
    for region in regions:
        parts: Any = region
        if " " in region:
            parts = region.split(" ")
        if "," in region:
            parts = region.split(",")
        try:
            values = [float(x) for x in parts]
        except ValueError:
            raise _bad(f"colors region {region!r} must be 3 or 4 numbers separated by spaces or commas")
        if len(values) == 3:
            values.insert(0, sum(values) / 3)
        if len(values) != 4:
            raise _bad(f"colors region {region!r} must have 3 or 4 values")
        if not all(math.isfinite(v) for v in values):
            raise _bad("colors must be finite numbers")
        out.append(values)
    return out


def _area(shape) -> int:
    return shape[-2] * shape[-1]


class TunerState:
    """One batch of the source script, from process_batch to postprocess_batch, and the passes after it.

    `x` given to `denoiser` uses the A1111 layout: the cond rows followed by `batch` uncond rows. `weights` is an
    object with `apply(fine_ratios)` and `restore()`.
    """

    def __init__(self, c: dict, *, sdxl: bool, hr: bool, batch: int):
        allsets = [c[k] for k in IDENTIFIERS]
        self.sdxl, self.hr, self.batch, self.apply_once = bool(sdxl), bool(hr), int(batch), bool(c["apply_once"])
        self.shape = None
        self.done = [False, False]
        self.pas = 0
        self.colored = 0
        self.active = self.activec = False
        self.effective = None  # fine ratios the source leaves on the model, or None for the original weights
        self.saturation = self.saturation2 = 0.0
        self.vae = False  # saturation is applied (process_batch .. postprocess_batch)
        self.ended = False
        self.drratios, self.ddratios, self.sts, self.scaling = [0.0] * 5, [0.0] * 5, [-1, -1, 0], 0
        if allsets == UNSET and (c["map_strength"] == 0 or c["colors"] == ""):
            return
        if not all(x == 0 for x in allsets[:10] + allsets[14:16]):
            self.active = True
            self.drratios = allsets[0:3] + allsets[8:10]
            self.ddratios = allsets[3:8]
            self.scaling = allsets[10]
            self.sts = allsets[11:14]
            self.saturation, self.saturation2 = allsets[14], allsets[15]
        if c["colors"] != "":
            self.activec = True
            mode = c["split_by"]
            self.cmode = mode if ("Hor" in mode or "Ver" in mode) else "Vertical"
            self.fst = int(c["map_stop_step"])
            self.att = float(c["map_strength"]) * 0.5
            self.ocells, self.icells = make_cells(c["split_ratio"], self.cmode)
            self.colors = parse_colors(c["colors"])
            needed = sum(len(i) for i in self.icells)
            if len(self.colors) < needed:
                raise _bad(f"Split Ratio has {needed} regions but colors gives {len(self.colors)}")
            self.extra_colors = len(self.colors) - needed
            self.satt = 1.15 - self.fst * 0.15 if 5 > self.fst else 0.4
        self.vae = self.saturation != 0 or self.saturation2 != 0

    @property
    def needs_con2(self) -> bool:
        return self.active and any(x != 0 for x in self.ddratios)

    # -- before the model ------------------------------------------------------------------------------------------
    def denoiser(self, x, shape, step: int, weights) -> bool:
        """Returns True when the Color Map changed `x` in place."""
        painted = False
        if self.activec:
            if self.shape is None:
                self.shape = shape
            if _area(shape) > _area(self.shape):
                self.colored = 0
                self.pas = 1
            passes = 1 if shape[0] > self.batch else 2
            if self.colored // passes == step and self.colored // passes < self.fst:
                self._paint(x, step)
                self.colored += 1
                painted = True
        if self.active:
            if self.shape is None:
                self.shape = shape
            if _area(shape) > _area(self.shape):
                self.pas = 1
                scale = _area(shape) / _area(self.shape) if self.scaling else 1
            else:
                scale = 1
            ratios = self.drratios  # mutated in place like the source
            if self._stopped(step, weights):
                return painted
            if self.done[self.pas]:
                return painted
            self.done[self.pas] = True
            if self.pas:
                if ratios[-2]:
                    ratios[0] = ratios[-2]
                if ratios[-1]:
                    ratios[1] = ratios[-1]
            ratios[:2] = [v * scale for v in ratios[:2]]
            self.effective = fine_ratios(ratios)
            weights.apply(self.effective)
            self.shape = shape
        return painted

    def _stopped(self, step: int, weights) -> bool:
        stop = self.sts[self.pas]
        if 0 > stop:
            return False
        reached = step >= stop
        if reached and self.done[self.pas]:
            weights.restore()
            self.effective = None
            self.done[self.pas] = False
        return reached

    def _paint(self, x, step: int) -> None:
        b = self.batch
        h, w = x.shape[-2], x.shape[-1]
        enhance = 6
        hr_att = 0.25 if self.pas else 1
        c = 0
        for i, ocell in enumerate(self.ocells):
            for icell in self.icells[i]:
                if "Ver" in self.cmode:
                    rows = slice(int(h * ocell[0]), int(h * ocell[1]))
                    cols = slice(int(w * icell[0]), int(w * icell[1]))
                else:
                    rows = slice(int(h * icell[0]), int(h * icell[1]))
                    cols = slice(int(w * ocell[0]), int(w * ocell[1]))
                vec = color_offsets(self.colors[c], self.sdxl)
                offsets = [0.0] + vec
                cratio = (sum(abs(v * 50) for v in vec)) / 10 * 1.0 * (1 / (1 + (1 + step) ** 1.5 / 10)) \
                    * self.att * hr_att * self.satt
                if 0 > cratio:
                    c += 1
                    continue
                for ch in (1, 2, 3):
                    scale = x[:, ch].abs().mean()
                    blend = 1 - cratio
                    shift = offsets[ch] * enhance * scale * cratio
                    x[:-b, ch, ..., rows, cols] = blend * x[:-b, ch, ..., rows, cols] - shift
                    x[-b:, ch, ..., rows, cols] = blend * x[-b:, ch, ..., rows, cols] + shift
                c += 1

    # -- after the model -------------------------------------------------------------------------------------------
    def denoised(self, step: int, total: int) -> Optional[list]:
        """Per-channel amounts subtracted (times 20/3) from the denoised prediction at this step, or None."""
        if not self.active:
            return None
        if self.hr and not self.pas:
            return None
        if step == total - 2 - self.sts[2] and self.needs_con2:
            return [self.ddratios[0] * 0.02] + color_offsets(self.ddratios[1:], self.sdxl)
        return None

    def end_batch(self) -> None:
        """The source's postprocess_batch: model restored, saturation off, "Apply once" switches it off."""
        if True in self.done:
            self.effective = None
        self.vae = False
        self.done = [False, False]
        if self.apply_once:
            self.active = self.activec = False
        self.ended = True


class Job:
    """Tuner state for one generation job: one state per batch, copied for each hires run of that batch."""

    def __init__(self, hires: bool = False):
        self.hires = bool(hires)
        self.state: Optional[TunerState] = None
        self.after_base: Optional[TunerState] = None

    def state_for(self, pass_: str, make: Callable[[], TunerState]) -> TunerState:
        if pass_ == "base" or self.state is None:
            self.state, self.after_base = make(), None
        elif pass_ == "hires":
            self.state = copy.deepcopy(self.after_base) if self.after_base is not None else make()
        elif not self.state.ended:
            self.state.end_batch()
        return self.state

    def finish(self, pass_: str) -> None:
        if pass_ == "base" and self.state is not None:
            self.after_base = copy.deepcopy(self.state)


# -- model tensors -----------------------------------------------------------------------------------------------
class Slot:
    """One parameter by dotted path, read and written in place (PEFT base layers, accelerate offload maps)."""

    def __init__(self, root, path: str):
        import torch
        self.path = path
        *names, self.attr = path.split(".")
        module = root
        for name in names:
            module = getattr(module, name, None)
            if module is None:
                raise EngineError(UNSUPPORTED, f"CD Tuner needs the model tensor {path}, which this model does not have")
        base = getattr(module, "base_layer", None)
        self.module = base if isinstance(base, torch.nn.Module) else module
        self.read()

    def read(self):
        import torch
        tensor = getattr(self.module, self.attr, None)
        if not torch.is_tensor(tensor):
            raise EngineError(UNSUPPORTED, f"CD Tuner needs the model tensor {self.path}, which this model does not have")
        if not tensor.is_meta:
            return tensor
        hook = getattr(self.module, "_hf_hook", None)
        for h in (getattr(hook, "hooks", None) or ((hook,) if hook is not None else ())):
            table = getattr(h, "weights_map", None)
            if table is None:
                continue
            try:
                backing = table[self.attr]
            except KeyError:
                continue
            if table[self.attr] is not backing:
                raise EngineError(UNSUPPORTED, f"CD Tuner cannot edit {self.path}: its offloaded weight is reloaded "
                                               "on every use; use a VRAM mode without disk offload")
            return backing
        raise EngineError(UNSUPPORTED, f"CD Tuner cannot edit {self.path}: the weight is not materialised")

    def write(self, value) -> None:
        import torch
        tensor = self.read()
        with torch.inference_mode():
            tensor.copy_(value.to(device=tensor.device, dtype=tensor.dtype))


class Weights:
    """Original values of the touched UNet/VAE tensors for one pass, and the source's edits on top of them."""

    def __init__(self, unet, vae=None, *, saturation: float = 0.0, saturation2: float = 0.0):
        import torch
        self.unet = [Slot(unet, k) for k in UNET_KEYS]
        self.vae = []
        if saturation != 0 or saturation2 != 0:
            if vae is None:
                raise EngineError(UNSUPPORTED, "CD Tuner saturation needs the pipeline VAE")
            self.vae = [(Slot(vae, k), 1 + saturation * 0.075) for k in VAE_KEYS] + \
                       [(Slot(vae, k), 1 + saturation2 * 0.02) for k in VAE_KEYS2]
        with torch.no_grad():
            self.unet_orig = [s.read().detach().clone() for s in self.unet]
            self.vae_orig = [s.read().detach().clone() for s, _ in self.vae]
        self.unet_dirty = self.vae_dirty = False

    def apply(self, fine) -> None:
        import torch
        self.unet_dirty = True
        for i, (slot, base) in enumerate(zip(self.unet, self.unet_orig)):
            if 4 > i:
                new = base * torch.tensor(fine[i]).to(base.device, base.dtype)
            else:
                new = base + torch.as_tensor(fine[i]).to(base.device, base.dtype)
            slot.write(new)

    def restore(self) -> None:
        if self.unet_dirty:
            for slot, base in zip(self.unet, self.unet_orig):
                slot.write(base)
            self.unet_dirty = False

    def apply_vae(self) -> None:
        self.vae_dirty = True
        for (slot, factor), base in zip(self.vae, self.vae_orig):
            slot.write(base * factor)

    def restore_all(self) -> None:
        self.restore()
        if self.vae_dirty:
            for (slot, _), base in zip(self.vae, self.vae_orig):
                slot.write(base)
            self.vae_dirty = False


# -- diffusers adapter -------------------------------------------------------------------------------------------
def _scales(sched, t) -> tuple:
    """(sqrt(alpha_bar), sqrt(1 - alpha_bar)) the scheduler uses for this evaluation."""
    import torch
    sigmas = getattr(sched, "sigmas", None)
    if sigmas is not None and len(sigmas):
        index = getattr(sched, "step_index", None)
        if index is None:
            index = getattr(sched, "begin_index", None)
        if index is None:
            timesteps = getattr(sched, "timesteps", None)
            if timesteps is not None:
                match = (timesteps.to(torch.float32).cpu() == float(torch.as_tensor(t).flatten()[0])).nonzero()
                index = int(match[0]) if len(match) else None
        if index is not None and index < len(sigmas):
            sigma = float(sigmas[index])
            return 1 / math.sqrt(sigma * sigma + 1), sigma / math.sqrt(sigma * sigma + 1)
    ac = sched.alphas_cumprod
    tv = min(max(float(torch.as_tensor(t).flatten()[0]), 0.0), len(ac) - 1.0)
    lo, hi = int(math.floor(tv)), int(math.ceil(tv))
    a = float(ac[lo]) * (1 - (tv - lo)) + float(ac[hi]) * (tv - lo)
    return math.sqrt(a), math.sqrt(max(1.0 - a, 0.0))


class PassHook:
    """Feeds one pipeline run (a pass) into the job's TunerState and undoes every edit at the end."""

    def __init__(self, job: Job, state: TunerState, pass_: str, unet, vae, sched, pipe, prediction: str,
                 steps_hint: int, engine=None, guided: bool = True):
        self.job, self.state, self.pass_ = job, state, pass_
        self.guided = guided  # rows are [uncond, cond(, perturbed)] with CFG, [cond(, perturbed)] without
        self.unet, self.vae, self.sched, self.pipe = unet, vae, sched, pipe
        self.prediction = prediction
        self.steps_hint = steps_hint
        self.engine = engine
        self.step = 0
        self.pending = None
        self.handles: list = []
        self.weights: Optional[Weights] = None

    # lifecycle
    def install(self) -> "PassHook":
        st = self.state
        vae_on = st.vae and self.pass_ in MAIN_PASSES
        self.weights = Weights(self.unet, self.vae, saturation=st.saturation if vae_on else 0.0,
                               saturation2=st.saturation2 if vae_on else 0.0)
        if st.effective is not None:  # left on the model by the previous pass of the batch
            self.weights.apply(st.effective)
        if vae_on:
            self.weights.apply_vae()
        if st.active or st.activec:
            self.handles.append(self.unet.register_forward_pre_hook(self._pre, with_kwargs=True))
        if st.needs_con2 or st.activec:
            self.handles.append(self.unet.register_forward_hook(self._post, with_kwargs=True))
        return self

    def remove(self) -> None:
        for handle in self.handles:
            handle.remove()
        self.handles = []
        try:
            if self.weights is not None:
                self.weights.restore_all()
        except BaseException as error:
            log.exception("CD Tuner could not restore the model weights")
            if self.engine is not None and hasattr(self.engine, "_free"):
                self.engine._free()
            raise EngineError(UNSUPPORTED, "CD Tuner could not restore the model weights; the model was unloaded, "
                                           "load it again") from error
        finally:
            self.job.finish(self.pass_)

    def callback(self, pipe, i, t, d):
        self.step += 1
        return d

    # hooks
    def _rows(self, n: int) -> int:
        b = self.state.batch
        if b <= 0 or n % b or n // b not in (1, 2, 3):
            raise EngineError(UNSUPPORTED, f"CD Tuner Color Map found an unexpected batch of {n} for {b} images")
        return n // b

    def _pre(self, module, args, kwargs):
        import torch
        sample = args[0] if args else kwargs["sample"]
        st = self.state
        b = st.batch
        shape = (2 * b,) + tuple(sample.shape[1:])
        if not st.activec:
            st.denoiser(None, shape, self.step, self.weights)
            return None
        groups = self._rows(sample.shape[0])
        old = sample.float()
        guided = self.guided and groups > 1
        cond = old[b:2 * b] if guided else old[:b]
        # Without CFG the source's uncond rows are the same latents (A1111 still evaluates them at CFG 1).
        uncond = old[:b] if guided else cond
        x = torch.cat([cond, uncond])  # A1111: cond rows, then uncond rows
        if not st.denoiser(x, shape, self.step, self.weights):
            return None
        new_cond, new_uncond = x[:b], x[b:]
        new = torch.cat(([new_uncond] if guided else [new_cond]) + [new_cond] * (groups - 1))
        t = args[1] if len(args) > 1 else kwargs.get("timestep")
        sa, s1a = _scales(self.sched, t)
        self.pending = (new - old, sa, s1a)
        new = new.to(sample.dtype)
        if args:
            return (new,) + tuple(args[1:]), kwargs
        kwargs = dict(kwargs)
        kwargs["sample"] = new
        return tuple(args), kwargs

    def _total(self) -> int:
        total = getattr(self.pipe, "_num_timesteps", None)
        if isinstance(total, int) and total > 0:
            return total
        timesteps = getattr(self.sched, "timesteps", None)
        if timesteps is not None:
            return len(timesteps) - int(getattr(self.sched, "begin_index", None) or 0)
        return int(self.steps_hint)

    def _post(self, module, args, kwargs, output):
        import torch
        out = output[0] if isinstance(output, tuple) else getattr(output, "sample", output)
        if not torch.is_tensor(out):
            return None
        new = None
        pending, self.pending = self.pending, None
        if pending is not None:
            delta, sa, s1a = pending
            # model output for the original input whose denoised value equals the source's (x_in edited)
            factor = (sa if self.prediction == "v_prediction" else 1.0) / max(s1a, 1e-8)
            new = out.float() - delta.to(out.device) * factor
        shifts = self.state.denoised(self.step, self._total())
        if shifts is not None:
            t = args[1] if len(args) > 1 else kwargs.get("timestep")
            sa, s1a = _scales(self.sched, t)
            new = out.float() if new is None else new
            factor = (1.0 if self.prediction == "v_prediction" else sa) / max(s1a, 1e-8)
            for i, amount in enumerate(shifts):
                if i >= new.shape[1]:
                    break
                new[:, i] = new[:, i] + amount * 20 / 3 * factor
        if new is None:
            return None
        new = new.to(out.dtype)
        if isinstance(output, tuple):
            return (new,) + tuple(output[1:])
        if hasattr(output, "sample"):
            output.sample = new
            return output
        return new


def install(engine, pipe, gp, kw: dict, ctx, config: dict, *, pass_: str, sched, chain, cleanups: list,
            cfg_scale: float = 7.0, conflicts=(), composed: bool = False) -> None:
    """Hooks one pass; appends its cleanup to `cleanups` before touching the model."""
    import torch
    c = normalize(config)
    kind = getattr(engine, "kind", None) or ("sdxl" if getattr(engine, "sdxl", False) else "sd15")
    if kind not in SUPPORTED_KINDS:
        raise EngineError(UNSUPPORTED, f"CD Tuner supports SD1.x, SD2.x and SDXL UNet models, not '{kind}'")
    unet = pipe.unet
    if getattr(unet.config, "out_channels", 4) != 4:
        raise EngineError(UNSUPPORTED, "CD Tuner needs a 4 channel latent model")
    job = getattr(engine, "_cd_tuner_job", None)
    if job is None:
        job = Job(False)
        engine._cd_tuner_job = job
    embeds = kw.get("prompt_embeds")
    if torch.is_tensor(embeds):
        batch = embeds.shape[0]
    elif isinstance(kw.get("generator"), list):
        batch = len(kw["generator"])
    else:
        batch = max(1, int(getattr(gp, "batch_size", 1) or 1))
    state = job.state_for(pass_, lambda: TunerState(c, sdxl=bool(engine.sdxl), hr=job.hires, batch=batch))
    state.batch = batch
    if state.activec and conflicts:
        raise EngineError(UNSUPPORTED, "CD Tuner Color Map cannot combine with " + ", ".join(conflicts) +
                          ": they evaluate or recombine the model differently")
    if composed and state.needs_con2:
        raise EngineError(UNSUPPORTED, "CD Tuner Contrast 2/Brightness/colour cannot combine with Neutral Prompt or "
                                       "Composable LoRA branches")
    if state.activec and state.extra_colors and ctx is not None:
        ctx.warn(f"CD Tuner: {state.extra_colors} extra colors beyond the Split Ratio regions are unused")
    hook = PassHook(job, state, pass_, unet, getattr(pipe, "vae", None), sched, pipe,
                    sched.config.get("prediction_type", "epsilon"), int(kw.get("num_inference_steps") or 0), engine,
                    guided=cfg_scale > 1 and getattr(unet.config, "time_cond_proj_dim", None) is None)
    cleanups.append(hook.remove)
    hook.install()
    if hook.handles:
        chain(kw, hook.callback)


__all__ = ["DEFAULTS", "IDENTIFIERS", "UNSET", "normalize", "fine_ratios", "color_offsets", "make_cells",
           "parse_colors", "TunerState", "Job", "Weights", "PassHook", "install"]
