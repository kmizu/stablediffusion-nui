import math
import pytest

from sdfx.control_mode_policy import control_plan


@pytest.mark.parametrize('mode,soft,cfg', [
    ('Balanced', False, False),
    ('My prompt is more important', True, False),
    ('ControlNet is more important', True, True),
])
def test_modes_are_independent_soft_and_cfg_flags(mode, soft, cfg):
    p = control_plan(mode, 1.25, model_kind='controlnet')
    assert p.soft == soft and p.cfg_injection == cfg
    expected = [1.25 * 0.825 ** (12 - i) for i in range(13)] if soft else [1.25] * 13
    assert p.scales == pytest.approx(expected)


@pytest.mark.parametrize('preprocessor', ['canny', 'canny_variant', 'mlsd'])
def test_hires_edges_force_soft_even_balanced(preprocessor):
    p = control_plan('Balanced', 0.7, model_kind='controlnet', sdxl=True, hires=True, preprocessor=preprocessor)
    assert p.soft and not p.cfg_injection
    assert p.scales == pytest.approx([0.7 * 0.825 ** (12 - i) for i in range(10)])


def test_adapter_schedule_and_explicit_weights_override_global_and_soft():
    p = control_plan('My prompt is more important', 0.6, model_kind='t2i_adapter')
    assert p.scales == pytest.approx([0.15, 0.372, 0.495, 0.6])
    values = [0.1 * i for i in range(13)]
    p = control_plan('ControlNet is more important', 0.6, model_kind='controlnet', advanced_weighting=values)
    assert p.soft and p.cfg_injection and p.scales == pytest.approx(values)
    assert control_plan('Balanced', 0.6, advanced_weighting=[0.1]).scales == [0.1]
    assert control_plan('Balanced', 0.6, advanced_weighting=[]).scales == []


def test_global_pooling_also_requires_conditional_only_control():
    p = control_plan('Balanced', 1, global_average_pooling=True)
    assert p.conditional_only and not p.cfg_injection and not p.soft


def test_conditional_mask_is_applied_per_sample_without_mutating_input():
    torch = pytest.importorskip('torch')
    from sdfx.control_mode_policy import apply_residual_policy
    source = [torch.ones(2, 1, 1, 1), torch.full((2, 1, 1, 1), 2.0)]
    p = control_plan('ControlNet is more important', 1, advanced_weighting=[0.5, 2.0])
    result = apply_residual_policy(source, p, [0, 1])
    assert [x.flatten().tolist() for x in result] == [[0.0, 0.5], [0.0, 4.0]]
    assert source[0].flatten().tolist() == [1.0, 1.0]
    with pytest.raises(ValueError):
        apply_residual_policy(source, p, [1])


@pytest.mark.parametrize('kwargs', [
    {'mode': 'unknown', 'weight': 1},
    {'mode': 'Balanced', 'weight': math.nan},
    {'mode': 'Balanced', 'weight': 1, 'model_kind': 'unknown'},
    {'mode': 'Balanced', 'weight': 1, 'advanced_weighting': [float('inf')] * 13},
])
def test_invalid_policy_is_rejected(kwargs):
    with pytest.raises(ValueError):
        control_plan(**kwargs)
