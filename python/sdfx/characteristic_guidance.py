"""Characteristic Guidance, re-implemented from scraed/CharacteristicGuidanceWebUI e2f9fdc (GPL-3.0) behaviour.

The source (ICML 2024, "Characteristic Guidance: Non-linear Correction for Diffusion Model at Large Guidance
Scale") replaces the A1111 CFG denoiser's model evaluation during the CHG steps. For a guidance scale `s` it looks
for a correction `dx` of the latent such that, with the conditional rows evaluated at `x + (s - 1) * dx` and the
unconditional rows at `x + s * dx`,

    dx = P(g(dx)),   g(dx) = (eps_uncond - eps_cond) * sqrt(1 - abar) / c_in     (k-diffusion samplers)
                     g(dx) = (eps_uncond - eps_cond) * sqrt(1 - abar)            (DDIM / UniPC: z space, no c_in)

where `P` is a ridge projection onto a basis taken from `-g(0)`: one direction per image for "Num. Basis" 0,
otherwise `n` quantile bands of every latent channel. The fixed point is solved by Anderson-accelerated iteration
whose state also carries the ridge strength, annealed from `max(5, RegS)` towards
`RegS * (abar_T / abar_t) ** (1 / RegR)`. Images whose residual RMS never drops below `10 ** Tol` fall back to plain
CFG for that step (`dx = 0`); the best iterate of the others is kept and, scaled by `sigma_t^2 / sigma_prev^2` and
"Reuse", warm-starts the next CHG step. The step's model output is the model evaluated at the shifted inputs; the
skip terms cancel in the CFG combination, so the scheduler receives exactly the source's guided prediction.

NUI applies this from a UNet forward pre-hook: the iterations call the UNet's own forward (other UNet-level hooks
already ran for this evaluation, like the source's cfg_denoiser callbacks), then the evaluation itself proceeds on
the shifted inputs. Like the source, the iterations evaluate at the quantised timestep of the sigma (k-diffusion)
and the steps are counted per model evaluation of the pass, from `round(steps * start)` to `round(steps * end)` of
the first-pass step count, restarting (with a fresh warm start) in the hires pass.
"""
from __future__ import annotations

import logging
import math
from typing import Callable, Optional

from .protocol import INVALID_PARAMS, UNSUPPORTED, EngineError

log = logging.getLogger("sdfx.characteristic_guidance")

CONTROLNET_MODES = ("More Prompt", "More ControlNet")
DEFAULTS = {"reg_ini": 1.0, "reg_range": 1.0, "ite": 50, "noise_base": 0, "chara_decay": 1.0, "res": -4.0,
            "lr": 1.0, "reg_size": 0.4, "reg_w": 0.5, "aa_dim": 2, "controlnet_mode": "More ControlNet",
            "start_step": 0.0, "stop_step": 1.0}
INTEGERS = ("ite", "noise_base", "aa_dim")
SUPPORTED_KINDS = ("sd15", "sd15-inpaint", "sd2", "sdxl", "sdxl-inpaint")
# A1111 runs DDIM, PLMS and UniPC as "timesteps" samplers (z space); every other sampler is a k-diffusion one.
TIMESTEP_SCHEDULERS = ("DDIMScheduler", "UniPCMultistepScheduler")
# hook name -> label of the features whose model evaluation or CFG combination CHG cannot reproduce
CONFLICTS = {"dynamic_thresholding": "Dynamic Thresholding", "apg": "APG", "mahiro": "Mahiro",
             "rescale_cfg": "Rescale CFG", "latent_modifier": "Latent Modifier", "cfg_schedule": "CFG Schedule",
             "detail_daemon": "Detail Daemon", "reference": "Reference", "controllllite": "ControlNet-LLLite",
             "fooocus_inpaint": "Fooocus Inpaint", "multidiffusion": "MultiDiffusion", "kohya_hrfix": "Kohya HRFix",
             "hypertile": "HyperTile", "token_downsampling": "Token Downsampling", "img2imgalt": "img2img alternative test",
             "img2img_hires_fix": "img2img Hires Fix"}
_STRETCH = 0.8898  # the source's slope for Regularization Strength / Range values above 5


def _bad(message: str) -> EngineError:
    return EngineError(INVALID_PARAMS, f"Characteristic Guidance: {message}")


def normalize(config: Optional[dict]) -> dict:
    """Hook config -> the source's UI values (validated; impossible values are rejected, never clamped)."""
    c = dict(DEFAULTS)
    c.update({k: v for k, v in (config or {}).items() if k in DEFAULTS and v is not None})
    out: dict = {}
    for key in DEFAULTS:
        value = c[key]
        if key == "controlnet_mode":
            if value not in CONTROLNET_MODES:
                raise _bad(f"ControlNet Compatible Mode must be one of {', '.join(CONTROLNET_MODES)}, got {value!r}")
            out[key] = value
            continue
        if isinstance(value, bool):
            raise _bad(f"{key} must be a number")
        try:
            number = float(value)
        except (TypeError, ValueError):
            raise _bad(f"{key} must be a number, got {value!r}")
        if not math.isfinite(number):
            raise _bad(f"{key} must be finite")
        if key in INTEGERS:
            if number != int(number):
                raise _bad(f"{key} must be a whole number, got {value!r}")
            number = int(number)
        out[key] = number
    if out["ite"] < 1:
        raise _bad("Max Num. Characteristic Iteration must be at least 1")
    if out["noise_base"] < 0:
        raise _bad("Num. Basis for Correction must be 0 or more")
    if out["aa_dim"] < 1:
        raise _bad("AA Iteration Memory Size must be at least 1")
    if out["reg_ini"] < 0:
        raise _bad("Regularization Strength must be 0 or more")
    if out["reg_range"] <= 0:
        raise _bad("Regularization Range Over Time must be greater than 0")
    if out["reg_w"] <= 0:
        # the source divides the annealed strength by it (0 gives NaN regularisation, < 0 a square root of < 0)
        raise _bad("Regularization Annealing Strength must be greater than 0")
    if out["res"] > 300:
        raise _bad("Log 10 Tolerance must be 300 or less")
    return out


def stretch(value: float) -> float:
    """The source's mapping of Regularization Strength / Range: identity up to 5, exponential above."""
    if value <= 5:
        return value
    return math.exp(_STRETCH * (value - 5)) / math.exp(0) / _STRETCH + 5 - 1 / _STRETCH


def constrain_step(total: int, fraction: float) -> int:
    """The source's step bound: Python round of steps * fraction, kept within [0, steps]."""
    return max(0, min(round(total * fraction), total))


def effective(settings: dict) -> dict:
    """UI values -> the values the iteration uses."""
    out = dict(settings)
    out["res_thres"] = 10 ** settings["res"]
    out["reg_ini"] = stretch(settings["reg_ini"])
    out["reg_range"] = stretch(settings["reg_range"])
    return out


# -- linear algebra of the source ---------------------------------------------------------------------------------
def pinv_solve(A, B):
    """Least squares through the SVD pseudo-inverse of A^T A (singular values floored at 1e-4)."""
    import torch
    C = torch.matmul(A.transpose(-2, -1), A)
    U, S, Vh = torch.linalg.svd(C.float(), full_matrices=False)
    inverse = Vh.transpose(-1, -2).matmul(torch.diag_embed(1.0 / torch.maximum(S, torch.ones_like(S) * 1e-4))) \
        .matmul(U.transpose(-1, -2))
    return torch.matmul(torch.matmul(inverse, A.transpose(-2, -1)), B)


def qr_least_squares(A, B):
    """Least squares by QR without pivoting: what `torch.linalg.lstsq` computes on CUDA (driver gels), where A1111
    runs the source. Unlike CPU lstsq (gelsy, not bit-repeatable) it is deterministic on both devices, so PNG replay
    is exact. Rank-deficient columns give inf/NaN like gels; the caller then falls back to `pinv_solve`."""
    import torch
    Q, R = torch.linalg.qr(A)
    return torch.linalg.solve_triangular(R, Q.transpose(-2, -1) @ B, upper=True)


def ridge_projection(A, B, reg):
    """A (A^T A + reg I)^-1 A^T B, the inverse from the eigen decomposition (eigenvalues floored at 1e-4)."""
    import torch
    C = torch.matmul(A.transpose(-2, -1), A) + reg * torch.eye(A.shape[-1], device=A.device)
    values, vectors = torch.linalg.eigh(C)
    inverse = torch.matmul(torch.matmul(vectors, torch.diag_embed(1.0 / torch.maximum(values, torch.ones_like(values) * 1e-4))),
                           vectors.transpose(-2, -1))
    return torch.matmul(A, torch.matmul(torch.matmul(inverse, A.transpose(-2, -1)), B))


def split_basis(g, n: int):
    """(B, C, H, W) -> (B, C, H, W, n): the values of each channel split into n quantile bands."""
    import torch
    flat = g.view(g.shape[0], g.shape[1], -1)
    bounds = torch.quantile(flat, torch.linspace(0, 1, n + 1, device=g.device), dim=-1).permute(1, 2, 0)
    out = torch.zeros(*g.shape, n, device=g.device)
    for i in range(n):
        low = bounds[..., i][..., None, None]
        high = bounds[..., i + 1][..., None, None]
        inside = (g >= low) & ((g < high) if i < n - 1 else (g <= high))
        out[..., i] = g * inside
    return out.view(*g.shape, n)


class Anderson:
    """Anderson acceleration over (correction, ridge strength) with the source's memory and column scaling."""

    def __init__(self, reg_size: float, memory: int):
        self.reg_size = reg_size
        self.memory = memory
        self.xs: list = []
        self.gs: list = []

    def step(self, dxs, g, reg_acc, reg_target):
        import torch
        batch, shape = dxs.shape[0], dxs.shape[1:]
        res_g = self.reg_size * (reg_acc[:, None] - reg_target[:, None])
        res_x = reg_acc[:, None]
        self.gs.append(torch.cat((g.reshape(batch, -1), res_g), dim=-1))
        self.xs.append(torch.cat((dxs.reshape(batch, -1), res_x), dim=-1))
        if len(self.gs) < 2:
            return dxs, g, res_x[:, 0], res_g[:, 0]
        self.gs[-2] = self.gs[-1] - self.gs[-2]
        self.xs[-2] = self.xs[-1] - self.xs[-2]
        if len(self.gs) > self.memory:
            del self.xs[0]
            del self.gs[0]
        gA = torch.cat([d[..., None] for d in self.gs[:-1]], dim=-1)
        gB = self.gs[-1][..., None]
        norm = torch.maximum(torch.sum(gA ** 2, dim=-2, keepdim=True) ** 0.5, torch.ones_like(gA) * 1e-4)
        gamma = qr_least_squares(gA / norm, gB)
        if torch.sum(torch.isnan(gamma)) > 0:
            gamma = pinv_solve(gA / norm, gB)
        xA = torch.cat([d[..., None] for d in self.xs[:-1]], dim=-1)
        xB = self.xs[-1][..., None]
        x_out = xB - (xA / norm).matmul(gamma)
        g_out = gB - (gA / norm).matmul(gamma)
        return (x_out[:, :-1].reshape(batch, *shape), g_out[:, :-1].reshape(batch, *shape),
                x_out[:, -1, 0], g_out[:, -1, 0])


class Solver:
    """One sampling pass of the source's characteristic iteration (its per-denoiser state: the warm start)."""

    def __init__(self, settings: dict):
        self.s = effective(settings)
        self.dxs_buffer = None
        self.abt_buffer = None
        self.records: list = []  # per CHG step: (best residual, iterations, target regularisation) per image

    def _project(self, dx, basis, reg):
        if basis is None:
            return dx
        if self.s["noise_base"] >= 1:
            A = basis.reshape(basis.shape[0] * basis.shape[1], basis.shape[2] * basis.shape[3], basis.shape[4])
            B = dx.reshape(dx.shape[0] * dx.shape[1], -1, 1)
            ridge = reg[:, None].expand(-1, dx.shape[1]).reshape(dx.shape[0] * dx.shape[1], 1, 1)
        else:
            A = basis.reshape(basis.shape[0], basis.shape[1] * basis.shape[2] * basis.shape[3], basis.shape[4])
            B = dx.reshape(dx.shape[0], -1, 1)
            ridge = reg[:, None].reshape(dx.shape[0], 1, 1)
        return ridge_projection(A, B, ridge).reshape(*dx.shape)

    def _basis(self, direction, dxs):
        import torch
        n = self.s["noise_base"]
        basis = split_basis(-direction(dxs * 0), max(n, 1))
        dims = (-2, -3) if n >= 1 else (-2, -3, -4)
        norm = torch.sum(basis ** 2, dim=dims, keepdim=True) ** 0.5
        return basis / torch.maximum(norm, torch.ones_like(norm) * 1e-4)

    def solve(self, direction: Callable, shape, abt_current, abt_smallest, device):
        """`direction(dxs)` -> g for a correction (B, C, H, W); returns the correction to apply this step."""
        import torch
        s = self.s
        batch = shape[0]
        dxs = torch.zeros(shape, device=device)
        if self.dxs_buffer is not None:
            if tuple(self.dxs_buffer.shape) != tuple(shape):
                raise EngineError(UNSUPPORTED, "Characteristic Guidance: the latent size changed within a pass")
            prev = self.abt_buffer
            dxs = self.dxs_buffer * ((prev - abt_current * prev) / (abt_current - abt_current * prev))
            dxs = s["chara_decay"] * dxs
        reg_level = torch.zeros(batch, device=device) + max(5, s["reg_ini"])
        reg_target_level = s["reg_ini"] * (abt_smallest / abt_current[:, 0, 0, 0]) ** (1 / s["reg_range"])
        anderson = Anderson(s["reg_size"], (s["aa_dim"] if s["noise_base"] >= 1 else 1) + 1)
        basis = None
        best_res = best_dxs = not_converged = None
        counts = torch.zeros((batch, 1, 1, 1), dtype=torch.long, device=device)
        for iteration in range(s["ite"]):
            g = dxs - self._project(direction(dxs), basis, reg_level)
            if basis is None:
                basis = self._basis(direction, dxs)
            reg_acc = (reg_level * s["reg_w"]) ** 0.5
            reg_target = (reg_target_level * s["reg_w"]) ** 0.5
            residual = torch.cat((g.reshape(batch, -1), s["reg_size"] * (reg_acc[:, None] - reg_target[:, None])), dim=-1)
            res_el = (torch.mean(residual ** 2, dim=-1) ** 0.5)[:, None, None, None]
            if iteration == 0:
                best_res, best_dxs = res_el, dxs
                not_converged = torch.ones_like(res_el).bool()
            better = torch.logical_and(res_el < best_res, not_converged).int()
            best_res = better * res_el + (1 - better) * best_res
            best_dxs = better * dxs + (1 - better) * best_dxs
            not_converged = torch.logical_and(res_el >= s["res_thres"], not_converged)
            if torch.max(best_res) < s["res_thres"]:
                break
            dxs_acc, g_acc, reg_x_acc, reg_g_acc = anderson.step(dxs, g, reg_acc, reg_target)
            dxs = dxs_acc - s["lr"] * g_acc
            reg_acc = reg_x_acc - s["lr"] * reg_g_acc
            reg_level = reg_acc ** 2 / s["reg_w"]
            counts = counts * (1 - not_converged.long()) + iteration * not_converged.long()
        final = best_dxs * (1 - not_converged.long())
        self.dxs_buffer, self.abt_buffer = final, abt_current
        self.records.append((best_res[:, 0, 0, 0].tolist(), counts[:, 0, 0, 0].tolist(), reg_target_level.tolist()))
        return final

    def summary(self) -> str:
        thres = self.s["res_thres"]
        images = len(self.records[0][0]) if self.records else 0
        converged = sum(1 for res, _, _ in self.records for r in res if r < thres)
        return (f"{converged} of {len(self.records) * images} image steps converged "
                f"(tolerance {thres:g}, {len(self.records)} CHG steps)")


# -- diffusers adapter -------------------------------------------------------------------------------------------
class CharacteristicGuidanceHook:
    """UNet forward pre-hook running the source's CHG denoiser for one pass. Rows are [uncond, cond] (diffusers)."""

    def __init__(self, unet, sched, settings: dict, *, cfg_scale: float, total_steps: int, alphas_cumprod,
                 prediction: str = "epsilon", timesteps_path: bool = False, latent_channels: int = 4, ctx=None):
        import torch
        self.unet, self.sched = unet, sched
        self.settings = settings
        self.solver = Solver(settings)
        self.h = float(cfg_scale)
        self.start = constrain_step(int(total_steps), settings["start_step"])
        self.stop = constrain_step(int(total_steps), settings["stop_step"])
        self.prediction = prediction
        self.timesteps_path = bool(timesteps_path)
        self.channels = int(latent_channels)
        self.ctx = ctx
        self.ac = torch.as_tensor(alphas_cumprod).detach().to("cpu", torch.float32)
        self.log_sigmas = (((1 - self.ac) / self.ac) ** 0.5).log()
        self.step = 0
        self.handle = None

    def install(self) -> "CharacteristicGuidanceHook":
        self.handle = self.unet.register_forward_pre_hook(self.pre, with_kwargs=True)
        return self

    def remove(self) -> None:
        if self.handle is not None:
            self.handle.remove()
            self.handle = None
            if self.solver.records:
                message = "Characteristic Guidance: " + self.solver.summary()
                log.info("%s; per step (residual, iterations, regularisation): %s", message, self.solver.records)
                if self.ctx is not None and hasattr(self.ctx, "log"):
                    self.ctx.log("info", message)

    # model evaluation with the outer call's other arguments
    @staticmethod
    def _call(module, args, kwargs, sample, timestep):
        a = list(args)
        kw = {k: (list(v) if isinstance(v, list) else dict(v) if isinstance(v, dict) else v) for k, v in kwargs.items()}
        if a:
            a[0] = sample
        else:
            kw["sample"] = sample
        if len(a) > 1:
            a[1] = timestep
        else:
            kw["timestep"] = timestep
        out = module.forward(*a, **kw)
        return out[0] if isinstance(out, tuple) else getattr(out, "sample", out)

    def _scalars(self, t):
        """(abar of the iteration, c_in, c_skip or None, iteration timestep) for this evaluation."""
        import torch
        from .cd_tuner import _scales
        if self.timesteps_path:
            index = int(float(torch.as_tensor(t).flatten()[0]))  # the source's `t.long()`
            return self.ac[min(max(index, 0), len(self.ac) - 1)], None, None, t
        sa, s1a = _scales(self.sched, t)
        sigma = torch.tensor(s1a / sa, dtype=torch.float32)
        index = int((sigma.log() - self.log_sigmas[:, None]).abs().argmin(dim=0))
        c_in = 1 / (sigma ** 2 + 1) ** 0.5
        c_skip = 1 / (sigma ** 2 + 1) if self.prediction == "v_prediction" else None
        quantised = torch.full_like(t, index) if torch.is_tensor(t) else index
        return self.ac[index], c_in, c_skip, quantised

    def pre(self, module, args, kwargs):
        import torch
        step = self.step
        self.step += 1
        if not self.start <= step < self.stop:
            return None
        sample = args[0] if args else kwargs["sample"]
        t = args[1] if len(args) > 1 else kwargs.get("timestep")
        rows = sample.shape[0]
        if rows % 2:
            raise EngineError(UNSUPPORTED, f"Characteristic Guidance found {rows} model rows; it needs CFG rows "
                                           "[negative, positive] for every image")
        batch = rows // 2
        device = sample.device
        abt, c_in, c_skip, t_iter = self._scalars(t)
        abt_current = abt.to(device).expand(batch).reshape(batch, 1, 1, 1)
        scale = ((1 - abt_current) ** 0.5)
        abt_smallest = self.ac[-1].to(device)
        base = sample[:, :self.channels].float()
        extra = sample[:, self.channels:]
        h = self.h
        to_model = (lambda d: d * c_in.to(device)) if c_in is not None else (lambda d: d)

        def shifted(dxs):
            """Latents with the uncond rows moved by h * dxs and the cond rows by (h - 1) * dxs (x units)."""
            return base + to_model(torch.cat([h * dxs, (h - 1) * dxs]))

        def model_input(lat):
            lat = lat.to(sample.dtype)
            return torch.cat([lat, extra], dim=1) if extra.shape[1] else lat

        cache: dict = {}

        def direction(dxs):
            zero = not bool(torch.count_nonzero(dxs))
            if zero and "zero" in cache:
                return cache["zero"]
            lat = shifted(dxs)
            out = self._call(module, args, kwargs, model_input(lat), t_iter)[:, :self.channels].float()
            if self.timesteps_path:
                if self.prediction == "v_prediction":
                    # the source's timesteps V denoiser returns eps = sqrt(abar) v + sqrt(1 - abar) z (shifted z)
                    rows_abt = abt_current.repeat(2, 1, 1, 1)
                    out = rows_abt ** 0.5 * out + (1 - rows_abt) ** 0.5 * lat
                result = (out[:batch] - out[batch:]) * scale
            else:
                if c_skip is not None:
                    out = c_skip.to(device) ** 0.5 * out
                result = (out[:batch] - out[batch:]) * scale / c_in.to(device)
            if zero:
                cache["zero"] = result
            return result

        final = self.solver.solve(direction, (batch,) + tuple(base.shape[1:]), abt_current, abt_smallest, device)
        new = model_input(shifted(final))
        if args:
            return (new,) + tuple(args[1:]), kwargs
        kwargs = dict(kwargs)
        kwargs["sample"] = new
        return tuple(args), kwargs


def conflicts(hooks: dict, settings: dict, *, pag: bool = False, sag: bool = False, controlnet: bool = False,
              composed: bool = False) -> list:
    from .hooks import hook_config
    names = [label for key, label in CONFLICTS.items() if hook_config(hooks or {}, key, {}) is not None]
    names += ["PAG"] if pag else []
    names += ["Self Attention Guidance"] if sag else []
    names += ["ControlNet"] if controlnet else []
    names += ["Neutral Prompt / Composable LoRA branches"] if composed else []
    if settings["controlnet_mode"] == "More Prompt" and hook_config(hooks or {}, "ip_adapter", {}) is not None:
        names.append("IP-Adapter in More Prompt mode")
    return names


def install(engine, pipe, gp, kw: dict, ctx, config: dict, *, pass_: str, sched, cfg_scale: float, cleanups: list,
            pag: bool = False, sag: bool = False, composed: bool = False) -> None:
    """Hooks one pass; appends the cleanup before the hook is live."""
    import torch
    settings = normalize(config)
    hooks = getattr(gp, "hooks", None) or {}
    names = conflicts(hooks, settings, pag=pag, sag=sag, controlnet=getattr(pipe, "controlnet", None) is not None,
                      composed=composed)
    if names:
        raise EngineError(UNSUPPORTED, "Characteristic Guidance cannot combine with " + ", ".join(names) +
                          ": they change the model evaluation or the CFG combination it corrects")
    if pass_ == "adetailer":
        # ADetailer's default script filter does not run this extension in its inpainting passes.
        log.info("Characteristic Guidance is not applied in ADetailer passes")
        return
    if pass_ not in ("base", "hires"):
        raise EngineError(UNSUPPORTED, f"Characteristic Guidance is not available in the {pass_} pass")
    kind = getattr(engine, "kind", None) or ("sdxl" if getattr(engine, "sdxl", False) else "sd15")
    if kind not in SUPPORTED_KINDS:
        raise EngineError(UNSUPPORTED, f"Characteristic Guidance supports SD1.x, SD2.x and SDXL UNet models, not '{kind}'")
    unet = pipe.unet
    channels = getattr(unet.config, "out_channels", 4)
    if channels != 4:
        raise EngineError(UNSUPPORTED, "Characteristic Guidance needs a 4 channel latent model")
    prediction = sched.config.get("prediction_type", "epsilon")
    if prediction not in ("epsilon", "v_prediction"):
        raise EngineError(UNSUPPORTED, f"Characteristic Guidance supports epsilon and v prediction, not '{prediction}'")
    guided = cfg_scale > 1 and getattr(unet.config, "time_cond_proj_dim", None) is None
    if not guided:
        if cfg_scale == 1:
            # The source's conditional rows are then evaluated unshifted: the result equals plain sampling.
            log.info("Characteristic Guidance leaves CFG scale 1 unchanged")
            return
        raise EngineError(UNSUPPORTED, "Characteristic Guidance needs CFG (a CFG scale above 1, or exactly 1) and a "
                                       "model without guidance embedding")
    ac = getattr(sched, "alphas_cumprod", None)
    if ac is None:
        from .sigmas import default_alphas_cumprod
        ac = torch.tensor(default_alphas_cumprod())
    hook = CharacteristicGuidanceHook(unet, sched, settings, cfg_scale=cfg_scale, total_steps=int(gp.steps),
                                      alphas_cumprod=ac, prediction=prediction,
                                      timesteps_path=type(sched).__name__ in TIMESTEP_SCHEDULERS,
                                      latent_channels=channels, ctx=ctx)
    cleanups.append(hook.remove)
    hook.install()


__all__ = ["DEFAULTS", "CONTROLNET_MODES", "CONFLICTS", "normalize", "stretch", "constrain_step", "effective",
           "pinv_solve", "qr_least_squares", "ridge_projection", "split_basis", "Anderson", "Solver", "CharacteristicGuidanceHook",
           "conflicts", "install"]
