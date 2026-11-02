from types import SimpleNamespace

import pytest

from sdfx import vae_memory
from sdfx.params import LoadModelRequest

torch = pytest.importorskip("torch")
diffusers = pytest.importorskip("diffusers")

MB = 2**20


def plan(width, height, batch, free_mb, fp32=True, **kw):
    return vae_memory.decode_plan(width=width, height=height, batch=batch, fp32_decode=fp32, free=free_mb * MB, **kw)


def test_fits_changes_nothing():
    assert plan(832, 1216, 1, 12000)["action"] == "none"          # measured 4942 MiB
    assert plan(1024, 1024, 4, 20000, fp32=False)["action"] == "none"  # measured 9731 MiB


def test_batch_that_does_not_fit_is_sliced():
    p = plan(1024, 1024, 4, 6000, fp32=False)                      # 4 x 2.4 KB x 1 Mpx = 9.8 GB > 6 GB, one image fits
    assert p["action"] == "slice"


def test_too_big_image_is_tiled_to_fit():
    p = plan(2048, 2816, 1, 8000)
    assert p["action"] == "tile" and p["fits"] and not p["slice"]
    assert p["tile"] % 64 == 0 and 256 <= p["tile"] <= 1024
    assert p["tile"] ** 2 * vae_memory.BYTES_PER_PIXEL["fp32"] <= 8000 * MB * vae_memory.SAFETY - vae_memory.RESERVE


def test_tile_gets_smaller_with_less_memory_and_batches_also_slice():
    big = plan(2048, 2048, 1, 8000)["tile"]
    small = plan(2048, 2048, 2, 3000)
    assert small["tile"] < big and small["slice"]


def test_hopeless_budget_clamps_to_minimum_tile():
    p = plan(2048, 2048, 1, 100)
    assert p["tile"] == vae_memory.MIN_TILE and not p["fits"]


def test_fp16_decode_needs_half_the_memory():
    assert plan(832, 1216, 1, 5000, fp32=True)["action"] == "tile"
    assert plan(832, 1216, 1, 5000, fp32=False)["action"] == "none"


def tiny_vae():
    from diffusers import AutoencoderKL
    return AutoencoderKL(in_channels=3, out_channels=3, down_block_types=("DownEncoderBlock2D",) * 2,
                         up_block_types=("UpDecoderBlock2D",) * 2, block_out_channels=(8, 8), layers_per_block=1,
                         latent_channels=4, norm_num_groups=4, sample_size=32).eval()


def test_scope_inactive_without_cuda_or_options():
    vae = tiny_vae()
    engine = SimpleNamespace(cuda=False, load_opts=LoadModelRequest(), _offload=False)
    with vae_memory.vae_scope(engine, vae, 64, 64, 1) as p:
        assert p is None
    engine = SimpleNamespace(cuda=True, load_opts=LoadModelRequest(vae_auto=False), _offload=False)
    with vae_memory.vae_scope(engine, vae, 64, 64, 1) as p:
        assert p is None


def test_scope_applies_and_restores(monkeypatch):
    vae = tiny_vae()
    before = (vae.use_tiling, vae.use_slicing, vae.tile_sample_min_size, vae.tile_latent_min_size)
    monkeypatch.setattr(vae_memory, "free_bytes", lambda: 1 * MB)
    engine = SimpleNamespace(cuda=True, load_opts=LoadModelRequest(), _offload=False)
    def check_decode(*args, **kwargs):
        assert vae.use_tiling and vae.use_slicing
        assert vae.tile_sample_min_size == vae_memory.MIN_TILE
        assert vae.tile_latent_min_size == vae_memory.MIN_TILE // 2
        return (torch.zeros(1),)
    monkeypatch.setattr(vae, "decode", check_decode)
    with vae_memory.vae_scope(engine, vae, 512, 512, 2) as p:
        assert p["action"] == "tile"
        vae.decode(torch.zeros(1))
    assert (vae.use_tiling, vae.use_slicing, vae.tile_sample_min_size, vae.tile_latent_min_size) == before


def test_scope_restores_after_an_error(monkeypatch):
    vae = tiny_vae()
    monkeypatch.setattr(vae_memory, "free_bytes", lambda: 1 * MB)
    engine = SimpleNamespace(cuda=True, load_opts=LoadModelRequest(), _offload=False)
    with pytest.raises(RuntimeError):
        with vae_memory.vae_scope(engine, vae, 512, 512, 1):
            raise RuntimeError("boom")
    assert not vae.use_tiling and not vae.use_slicing
    assert "decode" not in vae.__dict__


def test_defaults():
    req = LoadModelRequest()
    assert req.vae_auto is True and req.vae_fp16 is False


def test_budget_env_caps_free_memory(monkeypatch):
    if not torch.cuda.is_available():
        pytest.skip("needs CUDA")
    monkeypatch.setenv("SDFX_VRAM_BUDGET_MB", "1")
    assert vae_memory.free_bytes() <= 1 * MB


@pytest.mark.parametrize("nonfinite", [False, True])
def test_fp16_decode_retries_nonfinite_output_in_fp32_and_restores_entering_dtype(nonfinite):
    class FakeVae:
        def __init__(self):
            self.dtype = torch.float16
            self.config = SimpleNamespace(force_upcast=True)
            self.calls = []
        def to(self, dtype):
            self.dtype = dtype
            return self
        def decode(self, latent, return_dict=False):
            self.calls.append((self.dtype, latent.dtype))
            value = float("nan") if nonfinite and self.dtype == torch.float16 else 1.0
            return (torch.full((1,), value, dtype=self.dtype),)

    vae = FakeVae()
    engine = SimpleNamespace(cuda=True, load_opts=LoadModelRequest(vae_auto=False, vae_fp16=True), _offload=False)
    with vae_memory.vae_scope(engine, vae, 64, 64, 1):
        vae.to(torch.float32)  # SDXL pipeline upcasts its VAE before invoking decode.
        result = vae.decode(torch.ones(1, dtype=torch.float32), return_dict=False)[0]
        assert torch.isfinite(result).all()
        assert vae.dtype == torch.float32
        assert vae.calls == ([(torch.float16, torch.float16), (torch.float32, torch.float32)] if nonfinite
                             else [(torch.float16, torch.float16)])
    assert "decode" not in vae.__dict__


def test_fp16_decode_restores_dtype_and_method_after_decode_failure():
    class FakeVae:
        def __init__(self):
            self.dtype = torch.float16
            self.config = SimpleNamespace(force_upcast=True)
        def to(self, dtype):
            self.dtype = dtype
            return self
        def decode(self, latent, return_dict=False):
            assert self.dtype == latent.dtype == torch.float16
            raise RuntimeError("injected decode failure")

    vae = FakeVae()
    engine = SimpleNamespace(cuda=True, load_opts=LoadModelRequest(vae_auto=False, vae_fp16=True), _offload=False)
    with pytest.raises(RuntimeError, match="injected decode failure"):
        with vae_memory.vae_scope(engine, vae, 64, 64, 1):
            vae.to(torch.float32)
            vae.decode(torch.ones(1, dtype=torch.float32), return_dict=False)
    assert vae.dtype == torch.float32
    assert "decode" not in vae.__dict__


def test_auto_replans_for_fp32_when_fp16_decode_is_nonfinite(monkeypatch):
    class FakeVae:
        def __init__(self):
            self.dtype = torch.float16
            self.config = SimpleNamespace(force_upcast=True, block_out_channels=(1, 1, 1, 1))
            self.use_tiling = False
            self.use_slicing = False
            self.tile_sample_min_size = 1024
            self.tile_latent_min_size = 128
            self.calls = []
        def to(self, dtype):
            self.dtype = dtype
            return self
        def enable_tiling(self): self.use_tiling = True
        def disable_tiling(self): self.use_tiling = False
        def enable_slicing(self): self.use_slicing = True
        def disable_slicing(self): self.use_slicing = False
        def decode(self, latent, return_dict=False):
            self.calls.append((self.dtype, self.use_tiling, self.tile_sample_min_size))
            value = float("nan") if self.dtype == torch.float16 else 1.0
            return (torch.full((1,), value, dtype=self.dtype),)

    vae = FakeVae()
    monkeypatch.setattr(vae_memory, "free_bytes", lambda: 4000 * MB)
    engine = SimpleNamespace(cuda=True, load_opts=LoadModelRequest(vae_auto=True, vae_fp16=True), _offload=False)
    with vae_memory.vae_scope(engine, vae, 1024, 1024, 1):
        vae.to(torch.float32)
        assert torch.isfinite(vae.decode(torch.ones(1, dtype=torch.float32))[0]).all()
        assert vae.calls[0][1] is False  # half precision fits un-tiled
        assert vae.calls[1][1] is True   # fp32 fallback requires tiling
    assert not vae.use_tiling
    assert vae.tile_sample_min_size == 1024 and vae.tile_latent_min_size == 128
    assert "decode" not in vae.__dict__


def test_auto_decode_optimizations_do_not_affect_encode(monkeypatch):
    vae = tiny_vae()
    monkeypatch.setattr(vae_memory, "free_bytes", lambda: 1 * MB)
    observed = []
    def fake_decode(*args, **kwargs):
        observed.append((vae.use_tiling, vae.use_slicing))
        return (torch.zeros(1),)
    monkeypatch.setattr(vae, "decode", fake_decode)
    engine = SimpleNamespace(cuda=True, load_opts=LoadModelRequest(), _offload=False)
    with vae_memory.vae_scope(engine, vae, 512, 512, 2):
        # A pipeline may encode its input before decoding the generated latents.
        assert not vae.use_tiling and not vae.use_slicing
        vae.encode(torch.zeros(1, 3, 64, 64))
        vae.decode(torch.zeros(1))
        assert not vae.use_tiling and not vae.use_slicing
    assert observed == [(True, True)]


def test_auto_decode_restores_flags_when_decode_fails(monkeypatch):
    vae = tiny_vae()
    before = (vae.use_tiling, vae.use_slicing, vae.tile_sample_min_size, vae.tile_latent_min_size)
    monkeypatch.setattr(vae_memory, "free_bytes", lambda: 1 * MB)
    def fail(*args, **kwargs):
        assert vae.use_tiling and vae.use_slicing
        raise RuntimeError("decode failed")
    monkeypatch.setattr(vae, "decode", fail)
    engine = SimpleNamespace(cuda=True, load_opts=LoadModelRequest(), _offload=False)
    with pytest.raises(RuntimeError, match="decode failed"):
        with vae_memory.vae_scope(engine, vae, 512, 512, 2):
            vae.decode(torch.zeros(1))
    assert (vae.use_tiling, vae.use_slicing, vae.tile_sample_min_size, vae.tile_latent_min_size) == before
    assert vae.decode is fail
