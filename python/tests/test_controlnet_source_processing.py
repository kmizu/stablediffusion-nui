import types

import pytest

from sdfx import control_mode_policy, controlnet as cn
from sdfx.params import ControlNetUnit


def _upstream_canny(rgb, resolution, low, high):
    # Independent restatement of pinned ControlNet resize_image_with_pad + canny + remove_pad.
    import cv2
    import numpy as np
    h_raw, w_raw = rgb.shape[:2]
    k = float(resolution) / float(min(h_raw, w_raw))
    h, w = int(np.round(float(h_raw) * k)), int(np.round(float(w_raw) * k))
    img = cv2.resize(rgb, (w, h), interpolation=cv2.INTER_CUBIC if k > 1 else cv2.INTER_AREA)
    img = np.pad(img, [[0, int(np.ceil(h / 64.0) * 64 - h)], [0, int(np.ceil(w / 64.0) * 64 - w)], [0, 0]], mode='edge')
    edges = cv2.Canny(img, low, high)[:h, :w]
    return np.concatenate([edges[:, :, None]] * 3, axis=2)


@pytest.mark.parametrize('resolution,low,high', [(512, 100, 200), (200, 40, 160), (777, 1, 255)])
def test_source_canny_matches_pinned_formula(resolution, low, high):
    np = pytest.importorskip('numpy')
    pytest.importorskip('cv2')
    from PIL import Image
    rng = np.random.default_rng(7)
    rgb = (rng.random((300, 450, 3)) * 255).astype(np.uint8)
    rgb[100:200, 120:330] = 255
    result = np.asarray(cn.source_canny(Image.fromarray(rgb), resolution,
                                        {'low_threshold': low, 'high_threshold': high}))
    assert np.array_equal(result, _upstream_canny(rgb, resolution, low, high))


def test_source_canny_uses_default_thresholds_outside_slider_range():
    np = pytest.importorskip('numpy')
    pytest.importorskip('cv2')
    from PIL import Image
    rgb = np.zeros((64, 64, 3), np.uint8); rgb[16:48, 16:48] = 200
    image = Image.fromarray(rgb)
    assert np.array_equal(np.asarray(cn.source_canny(image, 64, {'low_threshold': 0, 'high_threshold': 999})),
                          np.asarray(cn.source_canny(image, 64, {})))


def test_pixel_perfect_and_processor_resolution():
    assert cn.pixel_perfect_resolution(1000, 500, 768, 768, 1) == 768
    assert cn.pixel_perfect_resolution(1000, 500, 768, 768, 2) == 384
    assert cn.pixel_perfect_resolution(400, 600, 512, 768, 0) == 512
    image = types.SimpleNamespace(width=1000, height=500)
    assert cn.processor_resolution(ControlNetUnit(processor_res=-1), image, 768, 768) == 512
    assert cn.processor_resolution(ControlNetUnit(processor_res=4096), image, 768, 768) == 512
    assert cn.processor_resolution(ControlNetUnit(processor_res=640), image, 768, 768) == 640
    assert cn.processor_resolution(ControlNetUnit(processor_res=640, pixel_perfect=True, resize_mode=2),
                                   image, 768, 768) == 384


class _FakeControlNet:
    def __init__(self, count, global_pool=False):
        import torch
        self.count = count
        self.config = types.SimpleNamespace(global_pool_conditions=global_pool)
        self.hooks = []
        self.torch = torch

    def register_forward_hook(self, hook):
        self.hooks.append(hook)
        model = self

        class Handle:
            def remove(self):
                model.hooks.remove(hook)
        return Handle()

    def __call__(self, batch):
        torch = self.torch
        down = tuple(torch.ones(batch, 1, 2, 2) for _ in range(self.count - 1))
        output = (down, torch.ones(batch, 1, 1, 1))
        for hook in list(self.hooks):
            output = hook(self, (), output)
        return output


def _pipe(models, cfg=True, pag=False):
    controlnet = models[0] if len(models) == 1 else types.SimpleNamespace(nets=models)
    return types.SimpleNamespace(controlnet=controlnet, do_classifier_free_guidance=cfg,
                                 do_perturbed_attention_guidance=pag)


def test_balanced_units_are_not_hooked():
    pytest.importorskip('torch')
    model = _FakeControlNet(13)
    remove = control_mode_policy.install(_pipe([model]), [ControlNetUnit(preprocessor='canny')])
    assert model.hooks == []
    remove()


def test_prompt_mode_softens_sd15_and_sdxl_residuals():
    torch = pytest.importorskip('torch')
    for count in (13, 10):
        model = _FakeControlNet(count)
        remove = control_mode_policy.install(_pipe([model]), [ControlNetUnit(control_mode='My prompt is more important')])
        down, mid = model(4)
        factors = [float(t.flatten()[0]) for t in list(down) + [mid]]
        assert factors == pytest.approx([0.825 ** (12 - i) for i in range(count)])
        assert all(torch.equal(t[0], t[-1]) for t in down)  # both guidance halves are softened
        remove()
        assert model.hooks == []


def test_controlnet_mode_zeroes_unconditional_samples_per_unit():
    pytest.importorskip('torch')
    balanced, control = _FakeControlNet(13), _FakeControlNet(13)
    units = [ControlNetUnit(), ControlNetUnit(control_mode='ControlNet is more important')]
    remove = control_mode_policy.install(_pipe([balanced, control]), units)
    assert balanced.hooks == []
    down, mid = control(4)
    assert mid[:2].abs().sum() == 0 and float(mid[2:].min()) == 1.0
    assert float(down[0][2].flatten()[0]) == pytest.approx(0.825 ** 12)
    remove()
    pag = _FakeControlNet(13)
    remove = control_mode_policy.install(_pipe([pag], pag=True), [units[1]])
    _, mid = pag(6)
    assert mid[:2].abs().sum() == 0 and float(mid[2:].min()) == 1.0
    remove()
    no_cfg = _FakeControlNet(13)
    remove = control_mode_policy.install(_pipe([no_cfg], cfg=False), [units[1]])
    _, mid = no_cfg(2)
    assert float(mid.min()) == 1.0
    remove()


def test_hires_softening_applies_only_to_source_compatible_canny_and_mlsd():
    pytest.importorskip('torch')
    native, source, depth = _FakeControlNet(13), _FakeControlNet(13), _FakeControlNet(13)
    units = [ControlNetUnit(preprocessor='canny'), ControlNetUnit(preprocessor='canny', source_compatible_resize=True),
             ControlNetUnit(preprocessor='depth', source_compatible_resize=True)]
    remove = control_mode_policy.install(_pipe([native, source, depth]), units, hires=True)
    assert native.hooks == [] and depth.hooks == [] and len(source.hooks) == 1
    _, mid = source(2)
    assert float(mid.min()) == 1.0
    down, _ = source(2)
    assert float(down[0].flatten()[0]) == pytest.approx(0.825 ** 12)
    remove()
    base = control_mode_policy.install(_pipe([source]), [units[1]], hires=False)
    assert source.hooks == []
    base()


def test_controlnet_mode_rejects_neutral_prompt_and_unknown_layouts():
    pytest.importorskip('torch')
    from sdfx.protocol import EngineError
    model = _FakeControlNet(13)
    unit = ControlNetUnit(control_mode='ControlNet is more important')
    remove = control_mode_policy.install(_pipe([model]), [unit], neutral=True)
    with pytest.raises(EngineError):
        model(4)
    remove()
    odd = _FakeControlNet(13)
    remove = control_mode_policy.install(_pipe([odd]), [unit])
    with pytest.raises(EngineError):
        odd(3)
    remove()
    with pytest.raises(EngineError):
        control_mode_policy.install(_pipe([model]), [unit, unit])


def test_shared_model_with_hooked_unit_is_rejected():
    pytest.importorskip('torch')
    from sdfx.protocol import EngineError
    shared = _FakeControlNet(13)
    units = [ControlNetUnit(control_mode='My prompt is more important'), ControlNetUnit()]
    with pytest.raises(EngineError):
        control_mode_policy.install(_pipe([shared, shared]), units)
    assert shared.hooks == []
    control_mode_policy.install(_pipe([shared, shared]), [ControlNetUnit(), ControlNetUnit()])()


def test_first_pooled_net_means_conditional_only_batches():
    pytest.importorskip('torch')
    pooled, canny = _FakeControlNet(13, global_pool=True), _FakeControlNet(13)
    unit = ControlNetUnit(control_mode='ControlNet is more important')
    remove = control_mode_policy.install(_pipe([pooled, canny]), [ControlNetUnit(), unit])
    _, mid = canny(2)  # diffusers already passed conditional samples only
    assert float(mid.min()) == 1.0
    remove()


def test_native_save_round_trip_for_other_preprocessors():
    from sdfx.controlnet_fastload import map_native_unit, native_to_legacy_unit
    native = {'enabled': True, 'model': 'm', 'preprocessor': 'depth', 'weight': 1.0, 'guidance_start': 0.0,
              'guidance_end': 1.0, 'preprocessor_args': {}, 'resize_mode': 1, 'source_compatible_resize': False,
              'control_mode': 'Balanced', 'processor_res': -1, 'pixel_perfect': False}
    legacy = native_to_legacy_unit(native)
    assert 'processor_res' not in legacy and map_native_unit(legacy)['unapplied'] == {}


def test_mask_alpha_is_composited_before_selection():
    from PIL import Image
    from sdfx.controlnet_fastload import noninpaint_mask_input
    image = Image.new('RGB', (2, 1), (12, 34, 56))
    transparent_black = Image.new('RGBA', (2, 1), (0, 0, 0, 0))  # HWC3 -> white -> source RGB
    assert noninpaint_mask_input(image, transparent_black).getpixel((0, 0)) == (12, 34, 56)
