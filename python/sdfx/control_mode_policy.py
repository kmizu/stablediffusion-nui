"""ControlNet per-unit injection policy, independently derived from pinned behavior.

The caller supplies the actual conditional batch mask and is responsible for
global-average-pooling separately when that option is enabled.
"""

from dataclasses import dataclass
import math


@dataclass(frozen=True)
class ControlPlan:
    soft: bool
    cfg_injection: bool
    conditional_only: bool
    scales: list


def control_plan(mode, weight, *, model_kind='controlnet', sdxl=False, hires=False,
                 preprocessor='', advanced_weighting=None, global_average_pooling=False):
    modes = ('Balanced', 'My prompt is more important', 'ControlNet is more important')
    if mode not in modes:
        raise ValueError('unknown ControlNet control mode')
    if isinstance(weight, bool) or not isinstance(weight, (int, float)) or not math.isfinite(weight):
        raise ValueError('ControlNet weight must be finite')
    if model_kind not in ('controlnet', 't2i_adapter'):
        raise ValueError('unsupported ControlNet model kind')
    if not isinstance(preprocessor, str):
        raise ValueError('preprocessor name must be text')
    cfg = mode == modes[2]
    soft = mode != modes[0] or (hires and ('canny' in preprocessor or 'mlsd' in preprocessor))
    count = (10 if sdxl else 13) if model_kind == 'controlnet' else 4
    if soft and model_kind == 'controlnet':
        scales = [weight * (0.825 ** (12 - index)) for index in range(count)]
    elif soft:
        scales = [weight * x for x in (0.25, 0.62, 0.825, 1.0)]
    else:
        scales = [weight] * count
    if advanced_weighting is not None:
        if not isinstance(advanced_weighting, (list, tuple)) or len(advanced_weighting) > 64:
            raise ValueError('invalid advanced weighting')
        if any(isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x) for x in advanced_weighting):
            raise ValueError('advanced weighting must be finite')
        # Upstream replaces the complete scale sequence; it does not multiply
        # by the unit weight, and zip with residuals handles short/empty lists.
        scales = list(advanced_weighting)
    return ControlPlan(bool(soft), cfg, bool(cfg or global_average_pooling), scales)


def apply_residual_policy(residuals, plan, conditional_mask=None):
    """Scale each block; a mask value of one identifies a conditional sample.

    The caller supplies the actual pipeline batch ordering. Output is a new
    list; source tensors are never edited. As upstream, zip truncates if an
    explicit advanced weighting list contains fewer scales than residuals.
    """
    import torch
    if plan.conditional_only and conditional_mask is None:
        raise ValueError('conditional sample mask is required')
    output = []
    for value, scale in zip(residuals, plan.scales):
        if not isinstance(value, torch.Tensor) or value.ndim < 1:
            raise ValueError('invalid residual tensor')
        result = value * scale
        if plan.conditional_only:
            mask = torch.as_tensor(conditional_mask, device=value.device)
            if mask.ndim != 1 or mask.shape[0] != value.shape[0] or not torch.all((mask == 0) | (mask == 1)):
                raise ValueError('conditional mask does not match residual batch')
            result = result * mask.to(dtype=value.dtype).reshape((-1,) + (1,) * (value.ndim - 1))
        output.append(result)
    return output


def _needs_hook(unit, hires):
    soft_hires = hires and unit.source_compatible_resize and (
        'canny' in unit.preprocessor or 'mlsd' in unit.preprocessor)
    return unit.control_mode != 'Balanced' or soft_hires


def install(pipe, units, *, hires=False, neutral=False):
    """Apply each enabled unit's source ControlNet control mode to its native residuals.

    `units` are the enabled units in pipeline order. Residuals arrive already
    multiplied by the unit weight, so plans use weight one. Returns a cleanup.
    Balanced units without forced hires softening get no hook.
    """
    from .protocol import EngineError, UNSUPPORTED
    controlnet = getattr(pipe, 'controlnet', None)
    models = list(getattr(controlnet, 'nets', None) or ([controlnet] if controlnet is not None else []))
    if len(models) != len(units):
        raise EngineError(UNSUPPORTED, 'ControlNet control mode could not match units to loaded models')
    hooked = [unit for unit in units if _needs_hook(unit, hires)]
    if hooked and len({id(model) for model in models}) != len(models):
        raise EngineError(UNSUPPORTED, 'ControlNet control modes need a different model for each unit')
    # Diffusers sends only conditional samples to every net when the first net pools its conditions.
    pooled = bool(models and getattr(models[0].config, 'global_pool_conditions', False))
    handles = []

    def make_hook(unit):
        def hook(module, args, output):
            if isinstance(output, tuple):
                down, mid, rest = output[0], output[1], tuple(output[2:])
            else:
                down, mid, rest = output.down_block_res_samples, output.mid_block_res_sample, None
            residuals = list(down) + [mid]
            if len(residuals) not in (10, 13):
                raise EngineError(UNSUPPORTED, f'ControlNet control mode needs 10 or 13 residuals, got {len(residuals)}')
            plan = control_plan(unit.control_mode, 1.0, sdxl=len(residuals) == 10,
                                hires=hires and unit.source_compatible_resize, preprocessor=unit.preprocessor)
            mask = None
            if plan.conditional_only:
                if neutral:
                    raise EngineError(UNSUPPORTED, "'ControlNet is more important' cannot combine with Neutral Prompt")
                batch = int(mid.shape[0])
                if pooled:
                    mask = [1] * batch  # the pipeline already passes only conditional samples
                elif getattr(pipe, 'do_classifier_free_guidance', False):
                    groups = 3 if getattr(pipe, 'do_perturbed_attention_guidance', False) else 2
                    if batch % groups:
                        raise EngineError(UNSUPPORTED, 'ControlNet control mode found an unexpected guidance batch')
                    mask = [0] * (batch // groups) + [1] * (batch - batch // groups)
                else:
                    mask = [1] * batch
            out = apply_residual_policy(residuals, plan, mask)
            if rest is None:
                output.down_block_res_samples, output.mid_block_res_sample = tuple(out[:-1]), out[-1]
                return output
            return (tuple(out[:-1]), out[-1]) + rest
        return hook

    try:
        for unit, model in zip(units, models):
            if _needs_hook(unit, hires):
                handles.append(model.register_forward_hook(make_hook(unit)))
    except BaseException:
        for handle in handles:
            handle.remove()
        raise

    def remove():
        for handle in handles:
            handle.remove()
        handles.clear()
    return remove
