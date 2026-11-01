import types

import pytest

from sdfx import llul
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")


def tiny_unet():
    pytest.importorskip("diffusers")
    from diffusers import UNet2DConditionModel
    torch.manual_seed(5)
    torch.set_num_threads(2)
    return UNet2DConditionModel(sample_size=8, in_channels=4, out_channels=4,
        down_block_types=("CrossAttnDownBlock2D", "CrossAttnDownBlock2D"),
        up_block_types=("CrossAttnUpBlock2D", "CrossAttnUpBlock2D"),
        block_out_channels=(8, 16), layers_per_block=1, cross_attention_dim=8,
        attention_head_dim=2, norm_num_groups=4).eval()


def inputs(batch=2, size=8):
    torch.manual_seed(9)
    return torch.randn(batch, 4, size, size), torch.randn(batch, 5, 8)


def config(**over):
    return llul.normalize(dict({"start_steps": 1}, **over))


def make_hook(unet, c, steps=10, fx=0.25, fy=0.5, mask=None):
    return llul.LlulHook(unet, c, steps=steps, fx=fx, fy=fy, mask=mask).install()


# --- settings -------------------------------------------------------------------------------------------------------

def test_defaults_and_validation():
    c = llul.normalize({})
    assert (c["weight"], c["multiply"], c["layers"], c["apply_to"], c["start_steps"], c["max_steps"]) == \
        (0.15, 1, ["OUT"], ["out"], 5, 0)
    assert llul.multiplier(c) == 2
    assert llul.normalize({"apply_to": "Resblock, S. Attn."})["apply_to"] == ["resblock", "s. attn."]
    assert llul.normalize({"layers": ""})["layers"] is None
    assert llul.normalize({"layers": "IN01, M00"})["layers"] == ["IN01", "M00"]
    for bad in ({"multiply": 6}, {"multiply": 1.5}, {"weight": float("nan")}, {"start_steps": 0}, {"up": "lanczos"},
                {"down": "x"}, {"intp": "cubic"}, {"apply_to": ["nothing"]}, {"bogus": 1}, {"up": "nearest", "up_aa": True},
                {"down": "area", "down_aa": True}):
        with pytest.raises(EngineError):
            llul.normalize(bad)
    llul.normalize({"up": "bicubic", "up_aa": True, "down": "bilinear", "down_aa": True})


def test_origin_and_region_box():
    c = llul.normalize({"multiply": 2})
    assert llul.origin(c, 512, 768) == (192.0, 288.0)  # (512 - 128) / 2, (768 - 192) / 2
    assert llul.origin(llul.normalize({"x": 10, "y": 20}), 512, 512) == (10.0, 20.0)
    assert llul.region_box(64, 64, 0.25, 0.25, 2) == (16, 48, 16, 48)
    assert llul.region_box(64, 64, 0.9, 0.9, 2) == (32, 64, 32, 64)  # clamped to the border
    assert llul.region_box(1, 8, 0.0, 0.0, 2) is None
    with pytest.raises(EngineError):
        llul.region_box(64, 64, -0.5, 0.0, 2)


def test_scalers_match_torch():
    x = torch.arange(2 * 3 * 4 * 6, dtype=torch.float32).reshape(2, 3, 4, 6)
    assert llul.upscale(x, "nearest", False, 2).shape == (2, 3, 8, 12)
    assert torch.equal(llul.upscale(x, "nearest", False, 2)[:, :, ::2, ::2], x)
    assert llul.upscale(x, "bicubic", True, 4).shape == (2, 3, 16, 24)
    for mode in llul.DOWNSCALERS:
        assert llul.downscale(x, mode, False, 2).shape == (2, 3, 2, 3)
    assert torch.equal(llul.downscale(x, "pooling max", False, 2), torch.nn.functional.max_pool2d(x, 2))
    assert torch.allclose(llul.downscale(x, "pooling avg", False, 2), torch.nn.functional.avg_pool2d(x, 2))
    assert torch.allclose(llul.downscale(x, "area", False, 2), torch.nn.functional.avg_pool2d(x, 2))


def test_slerp():
    a, b = torch.tensor([[[[1.0]], [[0.0]]]]), torch.tensor([[[[0.0]], [[1.0]]]])
    assert torch.allclose(llul.slerp(a, b, 0.0), a, atol=1e-6)
    assert torch.allclose(llul.slerp(a, b, 1.0), b, atol=1e-6)
    mid = llul.slerp(a, b, 0.5)
    assert torch.allclose(mid.flatten(), torch.tensor([2 ** -0.5, 2 ** -0.5]), atol=1e-6)
    same = torch.randn(1, 4, 3, 3)
    assert torch.allclose(llul.slerp(same, same, 0.3), same, atol=1e-5)  # parallel vectors fall back to lerp
    zero = torch.zeros(1, 4, 2, 2)
    assert torch.isfinite(llul.slerp(zero, same[:, :, :2, :2], 0.5)).all()


# --- hooks ----------------------------------------------------------------------------------------------------------

def test_out_layer_matches_independent_computation():
    unet = tiny_unet()
    x, context = inputs()
    seen = {}
    handle = unet.conv_norm_out.register_forward_pre_hook(lambda m, a: seen.setdefault("in", a[0].clone()))
    with torch.no_grad():
        baseline = unet(x, 500, context).sample
    handle.remove()
    feature = seen["in"]  # (2, C, 8, 8) input of the output norm
    c = config(weight=0.4)
    # source maths: crop the 4x4 region at (2, 4), upscale x2, run the output layers, scale down, lerp
    s0, t0 = int(8 * 0.25), int(8 * 0.5)
    crop = feature[:, :, t0:t0 + 4, s0:s0 + 4]
    up = torch.nn.functional.interpolate(crop, scale_factor=2.0, mode="bilinear")

    def tail(f):
        return unet.conv_out(unet.conv_act(unet.conv_norm_out(f)))

    with torch.no_grad():
        plain, big = tail(feature), tail(up)
        hook = make_hook(unet, c)
        out = unet(x, 500, context).sample
    assert out.shape == baseline.shape and hook.layers_hooked == 1
    small = torch.nn.functional.interpolate(big, scale_factor=0.5, mode="bilinear")
    expected = plain.clone()
    expected[:, :, t0:t0 + 4, s0:s0 + 4] = torch.lerp(plain[:, :, t0:t0 + 4, s0:s0 + 4], small, 0.4)
    assert torch.allclose(out, expected, atol=1e-5)
    assert torch.allclose(plain, baseline, atol=1e-5)
    assert not torch.allclose(out, baseline)
    # outside the region nothing moves
    keep = torch.ones(8, 8, dtype=torch.bool)
    keep[t0:t0 + 4, s0:s0 + 4] = False
    assert torch.allclose(out[:, :, keep], baseline[:, :, keep], atol=1e-5)
    hook.remove()
    with torch.no_grad():
        assert torch.allclose(unet(x, 500, context).sample, baseline, atol=1e-6)


def test_step_window_and_removal():
    unet = tiny_unet()
    x, context = inputs()
    with torch.no_grad():
        baseline = unet(x, 500, context).sample
        hook = make_hook(unet, config(weight=0.5, start_steps=2, max_steps=3))
        outs = [unet(x, 500, context).sample for _ in range(4)]
    same = [torch.allclose(o, baseline, atol=1e-6) for o in outs]
    assert same == [True, False, False, True]  # steps 1..4 with the window 2..3
    hook.remove()
    assert not unet.conv_out._forward_hooks and not unet.conv_norm_out._forward_pre_hooks
    with torch.no_grad():
        assert torch.allclose(unet(x, 500, context).sample, baseline, atol=1e-6)


def test_max_steps_defaults_to_the_pass_step_count():
    unet = tiny_unet()
    assert llul.LlulHook(unet, config(max_steps=0), steps=3, fx=0.0, fy=0.0).stop == 3
    assert llul.LlulHook(unet, config(max_steps=7), steps=3, fx=0.0, fy=0.0).stop == 7


@pytest.mark.parametrize("apply_to", [["resblock"], ["transformer"], ["s. attn."], ["x. attn."],
                                      ["resblock", "transformer", "s. attn.", "x. attn.", "out"]])
def test_other_layers_run_and_weight_zero_is_neutral(apply_to):
    unet = tiny_unet()
    x, context = inputs()
    with torch.no_grad():
        baseline = unet(x, 500, context).sample
        hook = make_hook(unet, config(weight=0.0, apply_to=apply_to, layers=""))
        neutral = unet(x, 500, context).sample
        hook.remove()
        hook = make_hook(unet, config(weight=1.0, apply_to=apply_to, layers=""))
        changed = unet(x, 500, context).sample
    assert hook.layers_hooked > 0
    # with weight 0 the doubled batch must not disturb the first half
    assert torch.allclose(neutral, baseline, atol=1e-4)
    assert not torch.allclose(changed, baseline, atol=1e-3)
    assert changed.shape == baseline.shape


def test_layer_names_and_filter():
    unet = tiny_unet()
    kinds = llul.resolve_layers(unet)
    names = {k: [n for n, _ in v] for k, v in kinds.items()}
    # two down blocks (resnet + attention each), the middle block, two up blocks
    assert names["resblock"] == ["IN01_00_resblock", "IN03_00_resblock", "M00_00_resblock", "M00_01_resblock",
                                 "OUT00_00_resblock", "OUT01_00_resblock", "OUT02_00_resblock", "OUT03_00_resblock"]
    assert names["transformer"] == ["IN01_00_trans", "IN03_00_trans", "M00_00_trans", "OUT00_00_trans", "OUT01_00_trans",
                                    "OUT02_00_trans", "OUT03_00_trans"]
    assert names["s. attn."][0] == "IN01_00_00_sattn" and names["x. attn."][-1] == "OUT03_00_00_xattn"
    hook = make_hook(unet, config(apply_to=["resblock"], layers="IN, M"))
    assert hook.layers_hooked == 4
    hook.remove()
    hook = make_hook(unet, config(apply_to=["s. attn."], layers="OUT03"))
    assert hook.layers_hooked == 1
    hook.remove()
    hook = make_hook(unet, config(apply_to=["resblock"], layers="nothing like it"))
    assert hook.layers_hooked == 0
    hook.remove()
    assert not unet.down_blocks[0].resnets[0]._forward_pre_hooks


def test_mask_scales_the_weight():
    unet = tiny_unet()
    x, context = inputs()
    with torch.no_grad():
        baseline = unet(x, 500, context).sample
        zero = make_hook(unet, config(weight=1.0), mask=torch.zeros(1, 1, 16, 16))
        assert torch.allclose(unet(x, 500, context).sample, baseline, atol=1e-6)
        zero.remove()
        plain = make_hook(unet, config(weight=1.0))
        unmasked = unet(x, 500, context).sample
        plain.remove()
        full = make_hook(unet, config(weight=1.0), mask=torch.ones(1, 1, 16, 16))
        masked = unet(x, 500, context).sample
        full.remove()
        half = make_hook(unet, config(weight=1.0), mask=torch.full((1, 1, 16, 16), 0.5))
        halved = unet(x, 500, context).sample
        half.remove()
    assert torch.allclose(masked, unmasked, atol=1e-5)
    assert torch.allclose(halved - baseline, (unmasked - baseline) * 0.5, atol=1e-4)


def test_load_mask(tmp_path):
    from PIL import Image
    path = tmp_path / "m.png"
    Image.new("RGB", (6, 4), (255, 255, 255)).save(path)
    m = llul.load_mask(str(path))
    assert m.shape == (1, 1, 4, 6) and float(m.min()) == 1.0
    assert llul.load_mask("") is None
    with pytest.raises(EngineError):
        llul.load_mask(str(tmp_path / "missing.png"))


class Ctx:
    def __init__(self):
        self.warnings = []

    def warn(self, text):
        self.warnings.append(text)


def test_install_validates_and_skips_adetailer_passes(tmp_path):
    unet = tiny_unet()
    pipe = types.SimpleNamespace(unet=unet)
    ctx, cleanups = Ctx(), []
    gp = types.SimpleNamespace(width=512, height=512)
    assert llul.install(None, pipe, gp, ctx, {}, pass_="adetailer", steps=10, cleanups=cleanups) is None and not cleanups
    with pytest.raises(EngineError):
        llul.install(None, pipe, types.SimpleNamespace(width=64, height=512), ctx, {}, pass_="base", steps=10, cleanups=[])
    hook = llul.install(None, pipe, gp, ctx, {"multiply": 2}, pass_="base", steps=10, cleanups=cleanups)
    assert cleanups == [hook.remove] and hook.fx == hook.fy == 0.375  # (512 - 128) / 2 / 512
    cleanups.pop()()
    llul.install(None, pipe, gp, ctx, {"layers": "zzz", "apply_to": ["resblock"]}, pass_="hires", steps=10, cleanups=[])
    assert ctx.warnings
    from PIL import Image
    path = tmp_path / "mask.png"
    Image.new("L", (4, 4), 128).save(path)
    hook = llul.install(None, pipe, gp, ctx, {"mask": str(path), "intp": "slerp"}, pass_="base", steps=10, cleanups=[])
    assert hook.mask is not None and hook.c["intp"] == "lerp"  # a mask forces lerp
    hook.remove()


def test_out_layer_weight_zero_is_bit_identical():
    unet = tiny_unet()
    x, context = inputs()
    with torch.no_grad():
        baseline = unet(x, 500, context).sample
        hook = make_hook(unet, config(weight=0.0))
        assert torch.equal(unet(x, 500, context).sample, baseline)
    hook.remove()
