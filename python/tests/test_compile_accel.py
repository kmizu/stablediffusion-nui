from types import SimpleNamespace

import pytest

from sdfx import compile_accel, hooks
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")


class Ctx:
    def __init__(self):
        self.messages = []

    def warn(self, msg):
        self.messages.append(msg)


class Unet(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.lin = torch.nn.Linear(4, 4)

    def forward(self, x):
        return self.lin(x)


def engine(cuda=True, offload=False, dtype=torch.float16):
    return SimpleNamespace(cuda=cuda, _offload=offload, dtype=dtype)


def gp(hooks_=None, loras=()):
    return SimpleNamespace(hooks=hooks_ or {}, loras=list(loras))


def test_known_hook_and_defaults():
    assert "compile" in hooks.KNOWN_HOOKS
    assert compile_accel.normalize({}) == {"backend": "inductor", "mode": "default", "min_block_size": 5,
                                           "optimization_level": 3, "cache_engines": True}


@pytest.mark.parametrize("cfg", [{"backend": "onnx"}, {"mode": "fast"}, {"min_block_size": 0},
                                 {"optimization_level": 9}, {"min_block_size": "x"}])
def test_normalize_rejects(cfg):
    with pytest.raises(EngineError):
        compile_accel.normalize(cfg)


def test_normalize_values():
    c = compile_accel.normalize({"backend": " TensorRT ", "mode": "Reduce-Overhead", "min_block_size": "8",
                                 "optimization_level": 5, "cache_engines": "off"})
    assert c == {"backend": "tensorrt", "mode": "reduce-overhead", "min_block_size": 8, "optimization_level": 5,
                 "cache_engines": False}


@pytest.fixture
def ready(monkeypatch):
    monkeypatch.setattr(compile_accel, "inductor_problem", lambda: None)
    monkeypatch.setattr(compile_accel, "tensorrt_problem", lambda: None)


def cfg(**kw):
    return compile_accel.normalize(kw)


def test_check_job_rejections(ready):
    ok = cfg()
    compile_accel.check_job(engine(), gp({"compile": {}}), controlnet=False, loras=False, composable=False, cfg=ok)
    with pytest.raises(EngineError, match="CUDA"):
        compile_accel.check_job(engine(cuda=False), gp(), controlnet=False, loras=False, composable=False, cfg=ok)
    with pytest.raises(EngineError, match="gpu"):
        compile_accel.check_job(engine(offload=True), gp(), controlnet=False, loras=False, composable=False, cfg=ok)
    with pytest.raises(EngineError, match="freeu"):
        compile_accel.check_job(engine(), gp({"freeu": {}}), controlnet=False, loras=False, composable=False, cfg=ok)
    # a disabled hook does not count
    compile_accel.check_job(engine(), gp({"freeu": {"enabled": False}}), controlnet=False, loras=False,
                            composable=False, cfg=ok)
    with pytest.raises(EngineError, match="ControlNet"):
        compile_accel.check_job(engine(), gp(), controlnet=True, loras=False, composable=False, cfg=ok)
    with pytest.raises(EngineError, match="Composable"):
        compile_accel.check_job(engine(), gp(), controlnet=False, loras=False, composable=True, cfg=ok)


def test_loras_only_block_tensorrt(ready):
    compile_accel.check_job(engine(), gp(), controlnet=False, loras=True, composable=False, cfg=cfg())
    with pytest.raises(EngineError, match="LoRA"):
        compile_accel.check_job(engine(), gp(), controlnet=False, loras=True, composable=False,
                                cfg=cfg(backend="tensorrt"))


def test_missing_dependencies_are_reported(monkeypatch):
    monkeypatch.setattr(compile_accel, "inductor_problem", lambda: "no triton here")
    monkeypatch.setattr(compile_accel, "tensorrt_problem", lambda: "no trt here")
    with pytest.raises(EngineError, match="no triton here"):
        compile_accel.check_job(engine(), gp(), controlnet=False, loras=False, composable=False, cfg=cfg())
    with pytest.raises(EngineError, match="no trt here"):
        compile_accel.check_job(engine(), gp(), controlnet=False, loras=False, composable=False,
                                cfg=cfg(backend="tensorrt"))
    assert compile_accel.available() == []


def test_install_swaps_forward_and_restores(ready, monkeypatch):
    built = []

    def fake_build(unet, c, dtype):
        built.append(c["backend"])
        return lambda x: unet.lin(x) * 2

    monkeypatch.setattr(compile_accel, "_build", fake_build)
    unet = Unet()
    pipe = SimpleNamespace(unet=unet)
    params = gp({"compile": {"backend": "inductor"}})
    x = torch.ones(2, 4)
    base = unet(x)
    for run in range(2):
        cleanups, ctx = [], Ctx()
        c = compile_accel.install(engine(), pipe, params, ctx, cleanups, controlnet=False, pass_="base",
                                  shape=(512, 512, 1))
        assert c["backend"] == "inductor"
        assert torch.allclose(unet(x), base * 2)
        assert len(ctx.messages) == (1 if run == 0 else 0)  # second job at the same shape reuses the build
        for fn in reversed(cleanups):
            fn()
        assert "forward" not in unet.__dict__
        assert torch.allclose(unet(x), base)
    assert built == ["inductor"]
    # a new shape is announced again, the compiled callable is reused
    cleanups, ctx = [], Ctx()
    compile_accel.install(engine(), pipe, params, ctx, cleanups, controlnet=False, pass_="hires", shape=(768, 768, 1))
    assert len(ctx.messages) == 1 and "768x768" in ctx.messages[0]
    assert built == ["inductor"]
    cleanups[0]()
    # another backend builds another callable
    compile_accel.install(engine(), pipe, gp({"compile": {"backend": "tensorrt"}}), Ctx(), [], controlnet=False,
                          pass_="base", shape=(512, 512, 1))
    assert built == ["inductor", "tensorrt"]


def test_install_off_and_patched(ready, monkeypatch):
    monkeypatch.setattr(compile_accel, "_build", lambda *a: (lambda x: x))
    pipe = SimpleNamespace(unet=Unet())
    assert compile_accel.install(engine(), pipe, gp(), Ctx(), [], controlnet=False, pass_="base",
                                 shape=(64, 64, 1)) is None
    pipe.unet.forward = lambda x: x
    with pytest.raises(EngineError, match="patched"):
        compile_accel.install(engine(), pipe, gp({"compile": {}}), Ctx(), [], controlnet=False, pass_="base",
                              shape=(64, 64, 1))


def test_tensorrt_import_shim_is_harmless_without_tensorrt():
    compile_accel._tensorrt_import_shim()
