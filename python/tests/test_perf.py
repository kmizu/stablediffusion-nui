from types import SimpleNamespace

import pytest

from sdfx import deepcache, hooks, perf
from sdfx.params import LoadModelRequest
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")
diffusers = pytest.importorskip("diffusers")


# ------------------------------------------------------------------------------------------ auto VRAM mode

def test_explicit_mode_passes_through():
    assert perf.resolve_vram_mode("model_offload", weights_mb=2000, free_mb=99999) == ("model_offload", "requested")


@pytest.mark.parametrize("kind,weights,free,expected", [
    ("sd15", 2100, 24000, "gpu"),
    ("sdxl", 6500, 24000, "gpu"),
    ("sdxl", 6500, 9000, "model_offload"),
    ("sdxl", 6500, 4000, "sequential_offload"),
    ("sd15", 2100, 3000, "model_offload"),
    ("sd15", 2100, 1200, "sequential_offload"),
])
def test_auto_vram_mode(kind, weights, free, expected):
    mode, reason = perf.resolve_vram_mode("auto", weights_mb=weights, free_mb=free, kind=kind)
    assert mode == expected and "MiB" in reason


def test_auto_vram_mode_grows_with_resolution():
    small = perf.resolve_vram_mode("auto", weights_mb=6500, free_mb=12500, kind="sdxl")[0]
    big = perf.resolve_vram_mode("auto", weights_mb=6500, free_mb=12500, kind="sdxl", pixels=2048 * 2048)[0]
    assert small == "gpu" and big != "gpu"


def test_load_validation():
    perf.validate_load(LoadModelRequest(vram_mode="auto"))
    with pytest.raises(EngineError):
        perf.validate_load(LoadModelRequest(vram_mode="fast"))
    with pytest.raises(EngineError):
        perf.validate_load(LoadModelRequest(vram_mode="model_offload", fp8_storage=True))
    with pytest.raises(EngineError):
        perf.validate_load(LoadModelRequest(dtype="fp32", fp8_storage=True))
    perf.validate_load(LoadModelRequest(fp8_storage=True))


def test_defaults_are_off():
    req = LoadModelRequest()
    assert not any([req.vae_slicing, req.fp8_storage, req.vae_tiling, req.vae_fp16])
    assert req.vram_mode == "gpu"


# ------------------------------------------------------------------------------------------------ DeepCache

def tiny_unet():
    from diffusers import UNet2DConditionModel
    torch.manual_seed(0)
    return UNet2DConditionModel(
        sample_size=16, in_channels=4, out_channels=4, cross_attention_dim=16, attention_head_dim=2,
        block_out_channels=(8, 16, 16), layers_per_block=1,
        down_block_types=("CrossAttnDownBlock2D", "CrossAttnDownBlock2D", "DownBlock2D"),
        up_block_types=("UpBlock2D", "CrossAttnUpBlock2D", "CrossAttnUpBlock2D"), norm_num_groups=4).eval()


def call(unet, x, t=500.0):
    with torch.inference_mode():
        return unet(x, torch.tensor(t), encoder_hidden_states=torch.randn(2, 5, 16, generator=torch.Generator().manual_seed(1)),
                    return_dict=False)[0]


def test_hook_registered_and_normalised():
    assert "deepcache" in hooks.KNOWN_HOOKS
    assert deepcache.normalize({}) == {"interval": 3, "branch": 0, "full_first": 5}
    for bad in ({"interval": 1}, {"interval": 11}, {"branch": 4}, {"branch": -1}, {"interval": "x"}, {"full_first": 51}, {"full_first": -1}):
        with pytest.raises(EngineError):
            deepcache.normalize(bad)


@pytest.mark.parametrize("branch", [0, 1])
def test_full_steps_match_and_cached_steps_are_cheaper(branch):
    unet = tiny_unet()
    x = torch.randn(2, 4, 16, 16, generator=torch.Generator().manual_seed(2))
    plain = call(unet, x)
    originals = {id(m): dict(m.__dict__) for m in unet.modules()}
    state = deepcache.DeepCache(unet, interval=3, branch=branch, full_first=0)
    state.install()
    outs = [call(unet, x), call(unet, x), call(unet, x), call(unet, x)]
    assert torch.equal(outs[0], plain)                 # the first call is a full step
    assert torch.equal(outs[1], outs[0])               # same input, same time: cached output equals the full one
    assert torch.allclose(outs[3], plain)              # step 3 is a full step again
    assert (state.full_calls, state.cached_calls) == (2, 2)
    state.remove()
    assert all("forward" not in m.__dict__ for m in unet.modules())
    assert torch.equal(call(unet, x), plain)
    assert {id(m): set(m.__dict__) for m in unet.modules()} == {k: set(v) for k, v in originals.items()}


def test_cached_step_reuses_deep_features():
    unet = tiny_unet()
    x = torch.randn(2, 4, 16, 16, generator=torch.Generator().manual_seed(2))
    state = deepcache.DeepCache(unet, interval=2, branch=0)
    state.install()
    full = call(unet, x)
    cached = call(unet, x * 1.5)                       # different input: only the shallow branch sees it
    state.remove()
    exact = call(unet, x * 1.5)
    assert cached.shape == exact.shape and torch.isfinite(cached).all()
    assert not torch.equal(cached, exact) and not torch.equal(cached, full)


def test_full_first_runs_full_steps_then_interval():
    unet = tiny_unet()
    x = torch.randn(2, 4, 16, 16)
    state = deepcache.DeepCache(unet, interval=3, branch=0, full_first=4)
    state.install()
    for _ in range(10):
        call(unet, x)
    state.remove()
    assert (state.full_calls, state.cached_calls) == (6, 4)    # calls 0-3, then 4 and 7 are full; 5,6,8,9 cached


def test_shape_change_forces_full_step():
    unet = tiny_unet()
    state = deepcache.DeepCache(unet, interval=5, branch=0)
    state.install()
    call(unet, torch.randn(2, 4, 16, 16))
    call(unet, torch.randn(2, 4, 32, 32))              # e.g. a hires pass reusing the object
    state.remove()
    assert (state.full_calls, state.cached_calls) == (2, 0)


def test_branch_too_deep():
    with pytest.raises(EngineError):
        deepcache.DeepCache(tiny_unet(), interval=2, branch=2)


def gp(hooks_):
    return SimpleNamespace(hooks=hooks_, loras=[])


def engine(offload=False):
    return SimpleNamespace(cuda=True, _offload=offload)


@pytest.mark.parametrize("kwargs,hooks_,match", [
    ({"controlnet": True, "neutral": False}, {}, "ControlNet"),
    ({"controlnet": False, "neutral": True}, {}, "Neutral"),
    ({"controlnet": False, "neutral": False}, {"freeu": {}}, "freeu"),
    ({"controlnet": False, "neutral": False}, {"aesthetic_gradients": {}}, "aesthetic_gradients"),
])
def test_refusals(kwargs, hooks_, match):
    with pytest.raises(EngineError, match=match):
        deepcache.check_job(engine(), gp({"deepcache": {}, **hooks_}), **kwargs)


def test_offload_refused():
    with pytest.raises(EngineError, match="gpu"):
        deepcache.check_job(engine(offload=True), gp({"deepcache": {}}), controlnet=False, neutral=False)


def test_install_off_is_noop():
    pipe = SimpleNamespace(unet=tiny_unet())
    cleanups = []
    assert deepcache.install(engine(), pipe, gp({}), None, cleanups, controlnet=False, neutral=False, pass_="base") is None
    assert cleanups == []


def test_install_and_cleanup():
    pipe = SimpleNamespace(unet=tiny_unet())
    cleanups = []
    cfg = deepcache.install(engine(), pipe, gp({"deepcache": {"interval": 4}}), None, cleanups, controlnet=False,
                            neutral=False, pass_="base")
    assert cfg == {"interval": 4, "branch": 0, "full_first": 5} and len(cleanups) == 1
    cleanups[0]()
    assert all("forward" not in m.__dict__ for m in pipe.unet.modules())


def test_auto_mode_rejects_fp8_when_resolved_to_offload(monkeypatch):
    from sdfx.engine import Engine
    from sdfx import models

    req = LoadModelRequest(path="fixture.safetensors", vram_mode="auto", fp8_storage=True)
    engine = Engine.__new__(Engine)
    engine.cuda = True
    monkeypatch.setattr(engine, "parse_load", lambda params: req)
    monkeypatch.setattr(engine, "_free", lambda: None)
    monkeypatch.setattr(engine, "_auto_vram_mode", lambda request, kind: ("model_offload", "low memory"))
    monkeypatch.setattr(models, "detect_kind", lambda path: "sd15")
    ctx = SimpleNamespace(log=lambda *args: None, warn=lambda *args: None)
    with pytest.raises(EngineError, match="fp8_storage needs the 'gpu' VRAM mode"):
        engine.load_model({}, ctx)


@pytest.mark.parametrize("field", ["interval", "branch", "full_first"])
@pytest.mark.parametrize("value", [float("inf"), float("nan"), 2.5, True])
def test_deepcache_rejects_non_integer_or_nonfinite_fields(field, value):
    with pytest.raises(EngineError, match="must be integers"):
        deepcache.normalize({field: value})


def test_fp8_storage_rejected_on_cpu_before_loading(monkeypatch):
    from sdfx.engine import Engine
    from sdfx import models

    req = LoadModelRequest(path="fixture.safetensors", fp8_storage=True)
    engine = Engine.__new__(Engine)
    engine.cuda = False
    monkeypatch.setattr(engine, "parse_load", lambda params: req)
    monkeypatch.setattr(engine, "_free", lambda: None)
    monkeypatch.setattr(models, "detect_kind", lambda path: "sd15")
    ctx = SimpleNamespace(log=lambda *args: None, warn=lambda *args: None)
    with pytest.raises(EngineError, match="fp8_storage needs a CUDA device"):
        engine.load_model({}, ctx)


def test_auto_fp8_rejection_preserves_loaded_model(monkeypatch):
    from sdfx.engine import Engine
    from sdfx import models

    req = LoadModelRequest(path="next.safetensors", vram_mode="auto", fp8_storage=True)
    engine = Engine.__new__(Engine)
    engine.cuda = True
    old_pipe = object()
    old_model = {"name": "current"}
    engine.pipe, engine.model = old_pipe, old_model
    monkeypatch.setattr(engine, "parse_load", lambda params: req)
    def free():
        engine.pipe, engine.model = None, None
    monkeypatch.setattr(engine, "_free", free)
    monkeypatch.setattr(engine, "_auto_vram_mode", lambda request, kind: ("model_offload", "low memory") if engine.pipe is old_pipe else ("gpu", "memory released"))
    monkeypatch.setattr(models, "detect_kind", lambda path: "sd15")
    ctx = SimpleNamespace(log=lambda *args: None, warn=lambda *args: None)
    with pytest.raises(EngineError, match="fp8_storage needs the 'gpu' VRAM mode") as error:
        engine.load_model({}, ctx)
    assert "unload" in str(error.value).lower()
    assert engine.pipe is old_pipe and engine.model is old_model
