import os
import types

import pytest

from sdfx import animatediff as ad
from sdfx.protocol import EngineError


# --- settings --------------------------------------------------------------------------------------------------------

def test_defaults_and_validation():
    c = ad.normalize({})
    assert (c["video_length"], c["fps"], c["batch_size"], c["closed_loop"], c["stride"], c["overlap"]) == (16, 8, 16, "R+P", 1, -1)
    assert ad.normalize({"video_length": 0, "batch_size": 12})["video_length"] == 12
    assert ad.normalize({"enabled": True, "free_init_filter": "Gaussian"})["free_init_filter"] == "gaussian"
    for bad in ({"video_length": -1}, {"fps": 0}, {"batch_size": 33}, {"closed_loop": "X"}, {"stride": 1.5}, {"overlap": 32},
                {"free_init_filter": "x"}, {"latent_scale": 0}, {"fps": float("nan")}, {"video_length": "many"}):
        with pytest.raises(EngineError) as info:
            ad.normalize(bad)
        assert info.value.code == "invalid_params"


def test_frame_interpolation_is_reported_unsupported():
    with pytest.raises(EngineError) as info:
        ad.normalize({"interp": "FILM"})
    assert info.value.code == "unsupported"
    assert ad.normalize({"interp": "Off"})["interp"] == "Off"


# --- prompt travel ---------------------------------------------------------------------------------------------------

def test_prompt_travel_parsing():
    text = "1girl, solo,\n<lora:x:0.7>,\n0: closed mouth\n8: open mouth\nsmile\nsunset"
    keys, prompts = ad.parse_travel(text, 16)
    assert keys == [0, 8]
    assert prompts == ["1girl, solo, <lora:x:0.7>, closed mouth, smile, sunset", "1girl, solo, <lora:x:0.7>, open mouth, smile, sunset"]
    assert ad.parse_travel("a cat\nsitting", 16) is None
    assert ad.parse_travel("0: a\n4: b", 8)[1] == ["a", "b"]
    for bad in ("0: a\n0: b", "4: a\n2: b", "0: a\n20: b", "0: a\ntail\n3: b"):
        with pytest.raises(EngineError):
            ad.parse_travel(bad, 16)


def test_travel_weights_clamp_and_interpolate():
    w = ad.travel_weights([2, 6], 10, False)
    assert w[0] == (0, 0, 0.0) and w[2] == (0, 0, 0.0)
    assert w[4] == (0, 1, 0.5) and w[6] == (1, 1, 0.0) and w[9] == (1, 1, 0.0)


def test_travel_weights_loop_closes_back_to_the_first_key():
    w = ad.travel_weights([2, 6], 10, True)
    assert w[2][0] == 0 and w[2][2] == 0.0
    assert w[4] == (0, 1, 0.5)
    a, b, t = w[8]  # halfway between key 1 (frame 6) and key 0 (frame 12)
    assert (a, b) == (1, 0) and abs(t - 2 / 6) < 1e-9
    a, b, t = w[0]  # frame 10 on the wrapped axis
    assert (a, b) == (1, 0) and abs(t - 4 / 6) < 1e-9


# --- context windows -------------------------------------------------------------------------------------------------

def test_one_window_when_the_video_fits_the_context():
    assert ad.context_windows(8, 16, -1, 4, "A") == [list(range(8))]
    assert ad.context_windows(16, 16, -1, 1, "R+P") == [list(range(16))]


def test_sliding_windows_overlap_and_cover_every_frame():
    windows = ad.context_windows(20, 8, 2, 1, "N")
    assert all(len(w) == 8 for w in windows)
    assert windows[0] == list(range(8)) and windows[1][0] == 6 and windows[-1][-1] == 19
    assert set(sum(windows, [])) == set(range(20))
    default_overlap = ad.context_windows(20, 8, -1, 1, "N")
    assert default_overlap[1][0] == 6  # length // 4


def test_stride_adds_spaced_groups():
    windows = ad.context_windows(8, 4, 1, 4, "N")
    assert [0, 2, 4, 6] in windows and [1, 3, 5, 7] in windows
    assert [0, 4] in windows and [3, 7] in windows
    only_one = ad.context_windows(8, 4, 1, 1, "N")
    assert all(w == list(range(w[0], w[0] + 4)) for w in only_one)


def test_closed_loop_windows_wrap_around_the_seam():
    for mode in ("R-P", "R+P", "A"):
        windows = ad.context_windows(20, 8, 2, 1, mode)
        wrapped = [w for w in windows if w != sorted(w)]
        assert wrapped, mode
        assert any(0 in w and 19 in w for w in wrapped)
    assert not [w for w in ad.context_windows(20, 8, 2, 1, "N") if w != sorted(w)]
    assert len(ad.context_windows(20, 8, 2, 1, "A")) > len(ad.context_windows(20, 8, 2, 1, "R-P"))


# --- output helpers --------------------------------------------------------------------------------------------------

def test_pingpong_drops_the_repeated_ends():
    assert ad.pingpong([1, 2, 3, 4]) == [1, 2, 3, 4, 3, 2]
    assert ad.pingpong([1, 2]) == [1, 2]


def frames(n=4, size=(16, 16)):
    from PIL import Image
    return [Image.new("RGB", size, (i * 40 % 256, 30, 90)) for i in range(n)]


def test_encode_gif_and_webp(tmp_path):
    out = ad.encode_videos(frames(), ["GIF", "WEBP"], 8, 0, str(tmp_path), "clip")
    from PIL import Image
    gif = Image.open(out["GIF"])
    assert gif.n_frames == 4 and gif.info["duration"] == 120  # GIF delays are 10 ms units
    assert Image.open(out["WEBP"]).n_frames == 4


@pytest.mark.skipif(ad.ffmpeg_path() is None, reason="needs ffmpeg")
def test_encode_mp4_and_webm(tmp_path):
    out = ad.encode_videos(frames(6, (32, 32)), ["MP4", "WEBM"], 8, 0, str(tmp_path), "clip")
    assert os.path.getsize(out["MP4"]) > 200 and os.path.getsize(out["WEBM"]) > 200


def test_encode_request_validates(tmp_path):
    ctx = types.SimpleNamespace(check_interrupt=lambda: None)
    for bad in ({}, {"images": []}, {"images": ["missing.png"]}, {"images": ["x"], "closed_loop": "Q"}):
        with pytest.raises(EngineError):
            ad.encode_request(dict(bad, output_dir=str(tmp_path)), ctx)
    from sdfx.images import save_png
    paths = []
    for i, img in enumerate(frames(5)):
        p = str(tmp_path / f"f{i}.png")
        save_png(img, p)
        paths.append(p)
    result = ad.encode_request({"images": paths, "formats": ["GIF"], "fps": 10, "closed_loop": "A", "output_dir": str(tmp_path / "o")}, ctx)
    assert result["frames"] == 8  # 5 frames + 3 reversed (ends not repeated)
    from PIL import Image
    assert Image.open(result["outputs"]["GIF"]).n_frames == 8


def test_model_resolution(tmp_path, monkeypatch):
    with pytest.raises(EngineError):
        ad.resolve_model("")
    (tmp_path / "AnimateDiff").mkdir()
    (tmp_path / "AnimateDiff" / "mm.safetensors").write_bytes(b"x")
    (tmp_path / "Stable-diffusion").mkdir()
    ckpt = str(tmp_path / "Stable-diffusion" / "a.safetensors")
    assert ad.resolve_model("mm.safetensors", ckpt) == ("file", str(tmp_path / "AnimateDiff" / "mm.safetensors"))
    assert ad.resolve_model("guoyww/animatediff-motion-adapter-v1-5-2", ckpt) == ("hub", "guoyww/animatediff-motion-adapter-v1-5-2")
    with pytest.raises(EngineError):
        ad.resolve_model("missing.safetensors", ckpt)
    with pytest.raises(EngineError):
        ad.resolve_model(str(tmp_path / "nowhere" / "x.ckpt"))


# --- torch parts -----------------------------------------------------------------------------------------------------

torch = pytest.importorskip("torch")
diffusers = pytest.importorskip("diffusers")


def tiny_unet(blocks=2):
    from diffusers import UNet2DConditionModel
    torch.manual_seed(5)
    torch.set_num_threads(2)
    down = ("CrossAttnDownBlock2D",) * (blocks - 1) + ("DownBlock2D",)
    up = ("UpBlock2D",) + ("CrossAttnUpBlock2D",) * (blocks - 1)
    return UNet2DConditionModel(sample_size=8, in_channels=4, out_channels=4, down_block_types=down, up_block_types=up,
                                block_out_channels=(8, 16, 16)[:blocks], layers_per_block=1, cross_attention_dim=8,
                                attention_head_dim=2, norm_num_groups=4).eval()


def tiny_adapter(blocks=2, mid=True):
    from diffusers import MotionAdapter
    torch.manual_seed(6)
    adapter = MotionAdapter(block_out_channels=(8, 16, 16)[:blocks], motion_layers_per_block=1, motion_norm_num_groups=4,
                            motion_num_attention_heads=2, motion_max_seq_length=16, use_motion_mid_block=mid)
    for p in adapter.parameters():  # a trained adapter is not the identity; make the temporal path matter
        torch.nn.init.normal_(p, std=0.2) if p.dim() > 1 else torch.nn.init.normal_(p, std=0.05)
    return adapter.eval()


def test_hooks_reproduce_diffusers_motion_unet():
    from diffusers import UNetMotionModel
    unet, adapter = tiny_unet(), tiny_adapter()
    reference = UNetMotionModel.from_unet2d(unet, adapter).eval()
    frames_n = 5
    torch.manual_seed(1)
    sample = torch.randn(2, 4, frames_n, 8, 8)
    text = torch.randn(2, 3, 8)
    with torch.no_grad():
        expected = reference(sample, torch.tensor(10), encoder_hidden_states=text.repeat_interleave(frames_n, dim=0)).sample
        state = ad.MotionState()
        state.num_frames = frames_n
        flat = sample.permute(0, 2, 1, 3, 4).reshape(2 * frames_n, 4, 8, 8)
        with ad.MotionHooks(unet, adapter, state):
            got = unet(flat, torch.tensor(10), encoder_hidden_states=text.repeat_interleave(frames_n, dim=0)).sample
        plain = unet(flat, torch.tensor(10), encoder_hidden_states=text.repeat_interleave(frames_n, dim=0)).sample
    got = got.reshape(2, frames_n, 4, 8, 8).permute(0, 2, 1, 3, 4)
    assert torch.allclose(got, expected, atol=1e-5), (got - expected).abs().max()
    assert not torch.allclose(plain.reshape(2, frames_n, 4, 8, 8).permute(0, 2, 1, 3, 4), expected, atol=1e-3)
    assert not unet.down_blocks[0].attentions[0]._forward_hooks  # removed on exit


def test_hooks_without_mid_block_and_three_levels():
    from diffusers import UNetMotionModel
    unet, adapter = tiny_unet(3), tiny_adapter(3, mid=False)
    reference = UNetMotionModel.from_unet2d(unet, adapter).eval()
    torch.manual_seed(2)
    sample = torch.randn(1, 4, 4, 8, 8)
    text = torch.randn(1, 3, 8)
    state = ad.MotionState()
    state.num_frames = 4
    with torch.no_grad(), ad.MotionHooks(unet, adapter, state):
        got = unet(sample[0].permute(1, 0, 2, 3), torch.tensor(5), encoder_hidden_states=text.repeat_interleave(4, dim=0)).sample
        expected = reference(sample, torch.tensor(5), encoder_hidden_states=text.repeat_interleave(4, dim=0)).sample
    assert torch.allclose(got.permute(1, 0, 2, 3).unsqueeze(0), expected, atol=1e-5)


def test_layout_mismatch_is_rejected_and_hooks_removed():
    unet = tiny_unet(2)
    with pytest.raises(EngineError) as info:
        ad.MotionHooks(unet, tiny_adapter(3, mid=False), ad.MotionState()).install()
    assert info.value.code == "unsupported"
    assert not unet.down_blocks[0].attentions[0]._forward_hooks


def original_format(adapter):
    """The original/A1111 key spelling of a diffusers adapter (what convert_original_state has to undo)."""
    reverse = [(new, old) for old, new in ad._NAME_MAP if new]
    state = {}
    for key, value in adapter.state_dict().items():
        for new, old in reverse:
            key = key.replace(new, old)
        key = key.replace(".motion_modules.", ".motion_modules.").replace("motion_modules.0.norm.", "motion_modules.0.temporal_transformer.norm.")
        parts = key.split(".")
        if "motion_modules" in parts:
            at = parts.index("motion_modules") + 2
            if parts[at] != "temporal_transformer":
                parts.insert(at, "temporal_transformer")
            key = ".".join(parts)
        state[key] = value
        if key.endswith("attention_blocks.0.to_q.weight") or key.endswith("attention_blocks.1.to_q.weight"):
            state[key.replace("to_q.weight", "pos_encoder.pe")] = torch.zeros(1, 16, value.shape[0])
    return state


def test_original_format_round_trip():
    from diffusers import MotionAdapter
    adapter = tiny_adapter(3, mid=True)
    state = original_format(adapter)
    assert ad.is_original_format(state)
    config = ad.adapter_config_from_state(state)
    assert config["block_out_channels"] == (8, 16, 16) and config["use_motion_mid_block"] and config["motion_max_seq_length"] == 16
    rebuilt = MotionAdapter(**{**config, "motion_norm_num_groups": 4, "motion_num_attention_heads": 2})
    result = rebuilt.load_state_dict(ad.convert_original_state(state), strict=False)
    assert not result.missing_keys and not result.unexpected_keys
    a, b = adapter.state_dict(), rebuilt.state_dict()
    assert all(torch.equal(a[k], b[k]) for k in a)
    assert not ad.adapter_config_from_state(original_format(tiny_adapter(2, mid=False)))["use_motion_mid_block"]


def test_diffusers_folder_gets_the_original_position_tables(tmp_path):
    adapter = tiny_adapter(2, mid=True)
    expected = {k: v.clone() for k, v in adapter.state_dict().items() if k.endswith("pos_embed.pe")}
    assert expected
    for module in adapter.modules():  # what the Hub adapters store: all sines, then all cosines
        table = getattr(module, "pe", None)
        if table is not None and module.__class__.__name__ == "SinusoidalPositionalEmbedding":
            half = table.shape[-1] // 2
            table.copy_(torch.cat([table[..., 0::2], table[..., 1::2]], dim=-1))
            assert half
    scrambled = adapter.state_dict()
    assert any(not torch.equal(scrambled[k], expected[k]) for k in expected)
    adapter.save_pretrained(tmp_path)
    ad._ADAPTERS.clear()
    loaded = ad.load_adapter("dir", str(tmp_path), torch.float32)
    state = loaded.state_dict()
    assert all(torch.equal(state[k], expected[k]) for k in expected)
    assert ad.reset_position_tables(loaded) == len(expected)


def test_freq_filters_and_mixing():
    for kind in ad.FREE_INIT_FILTERS:
        mask = ad.freq_filter((4, 8, 8), kind, 0.25, 0.25, 4)
        assert mask.shape == (4, 8, 8) and float(mask.min()) >= 0 and float(mask.max()) <= 1.0
        assert float(mask[2, 4, 4]) > 0.99  # DC after fftshift stays
        assert float(mask[0, 0, 0]) < 0.5
    low, high = torch.randn(1, 4, 4, 8, 8), torch.randn(1, 4, 4, 8, 8)
    ones = torch.ones(4, 8, 8)
    assert torch.allclose(ad.mix_frequencies(low, high, ones), low, atol=1e-5)
    assert torch.allclose(ad.mix_frequencies(low, high, torch.zeros(4, 8, 8)), high, atol=1e-5)
    mixed = ad.mix_frequencies(low, high, ad.freq_filter((4, 8, 8), "gaussian", 0.25, 0.25, 4))
    assert abs(float(mixed.mean() - low.mean())) < 0.05  # DC component comes from `low`


def test_init_latent_mix_weights():
    noise = torch.ones(1, 4, 6, 2, 2)
    first = torch.full((1, 4, 2, 2), 5.0)
    cfg = ad.normalize({"latent_power": 1, "latent_scale": 4})
    out = ad.init_latent_mix(first, None, noise, 6, cfg)
    assert abs(float(out[0, 0, 0, 0, 0]) - 5.0) < 1e-6          # frame 0: all init
    assert abs(float(out[0, 0, 2, 0, 0]) - (0.5 * 5 + 0.5)) < 1e-6   # a = 1 - 2/4
    assert abs(float(out[0, 0, 5, 0, 0]) - 1.0) < 1e-6          # a clamped to 0: pure noise
    last = torch.full((1, 4, 2, 2), -3.0)
    both = ad.init_latent_mix(first, last, noise, 6, ad.normalize({"latent_scale": 4, "latent_scale_last": 4}))
    assert abs(float(both[0, 0, 5, 0, 0]) - (-3.0)) < 1e-6
    assert abs(float(both[0, 0, 0, 0, 0]) - 5.0) < 1e-6


# --- the whole loop on a tiny model ------------------------------------------------------------------------------------

class StubEngine:
    """The slice of Engine that generate_frames uses, around a random tiny UNet."""
    cuda = False
    sdxl = False
    kind = "sd15"
    device = torch.device("cpu")
    dtype = torch.float32
    model = {"path": ""}

    def __init__(self):
        self.unet = tiny_unet()
        self.pipe = types.SimpleNamespace(unet=self.unet, vae=None)
        self.encoded = []

    def _activate(self, pipe):
        pass

    def _scheduler(self, sampler, schedule, ctx):
        self.overrides_seen = dict(getattr(self, "_ms_overrides", {}))
        from diffusers import DDIMScheduler, EulerDiscreteScheduler
        cls = EulerDiscreteScheduler if sampler == "Euler" else DDIMScheduler
        return cls(num_train_timesteps=100, beta_schedule="scaled_linear", beta_start=0.00085, beta_end=0.012), None

    def _rng_device(self, gp):
        return "cpu"

    def _encode_plain(self, pos, neg, gp, bs):
        self.encoded.append(pos)
        g = torch.Generator().manual_seed(abs(hash(pos)) % 1000)
        n = 8 if len(pos) < 30 else 16
        pe = torch.randn(1, n, 8, generator=g)
        ne = torch.zeros(1, 8, 8)
        if pos == "":
            pe = torch.zeros(1, 8, 8)
        return {"prompt_embeds": pe, "negative_prompt_embeds": ne}

    def _decode(self, latents, ctx=None, recovery_dir=None, device=None):
        from PIL import Image
        out = []
        for lat in latents:
            arr = (lat[:3].float().clamp(-3, 3) / 6 + 0.5).mul(255).byte().permute(1, 2, 0).numpy()
            out.append(Image.fromarray(arr).resize((16, 16)))
        return out


class StubSdxlEngine(StubEngine):
    """SDXL-shaped: three blocks (the first without attention), text_time conditioning, pooled embeddings."""
    sdxl = True
    kind = "sdxl"

    def __init__(self):
        from diffusers import UNet2DConditionModel
        torch.manual_seed(7)
        self.unet = UNet2DConditionModel(
            sample_size=8, in_channels=4, out_channels=4,
            down_block_types=("DownBlock2D", "CrossAttnDownBlock2D", "CrossAttnDownBlock2D"),
            up_block_types=("CrossAttnUpBlock2D", "CrossAttnUpBlock2D", "UpBlock2D"), block_out_channels=(8, 16, 16),
            layers_per_block=1, cross_attention_dim=8, attention_head_dim=2, norm_num_groups=4,
            addition_embed_type="text_time", addition_time_embed_dim=4, projection_class_embeddings_input_dim=8 + 6 * 4).eval()
        self.pipe = types.SimpleNamespace(unet=self.unet, vae=None)
        self.encoded = []

    def _encode_plain(self, pos, neg, gp, bs):
        base = super()._encode_plain(pos, neg, gp, bs)
        g = torch.Generator().manual_seed(abs(hash(pos)) % 1000)
        return dict(base, pooled_prompt_embeds=torch.randn(1, 8, generator=g), negative_pooled_prompt_embeds=torch.zeros(1, 8))


def test_sdxl_layout_runs_with_added_conditioning():
    adapter = tiny_adapter(3, mid=False)
    frames_, _, engine = run_frames(StubSdxlEngine(), adapter=adapter, gp_over={"prompt": "a\n0: x\n3: y"})
    assert len(frames_) == 6 and engine.sdxl


def test_family_mismatch_is_rejected():
    with pytest.raises(EngineError) as info:
        run_frames(StubSdxlEngine(), adapter=tiny_adapter(2))  # a two-level (SD 1.x style) module on an SDXL checkpoint
    assert info.value.code == "unsupported"
    with pytest.raises(EngineError):
        run_frames(StubEngine(), adapter=tiny_adapter(3, mid=False))


def run_frames(engine=None, mode="txt2img", gp_over=None, hook=None, adapter=None, interrupt_after=None):
    engine = engine or StubEngine()
    adapter = adapter or tiny_adapter()
    gp = types.SimpleNamespace(width=64, height=64, steps=4, cfg_scale=5.0, sampler_name="DDIM", scheduler=None,
                               prompt="a cat", negative_prompt="", batch_size=1,
                               img2img=types.SimpleNamespace(init_image="", denoising_strength=0.5, resize_mode=0, mask=None),
                               hooks={})
    for k, v in (gp_over or {}).items():
        setattr(gp, k, v)
    cfg = ad.normalize(dict({"batch_size": 6, "video_length": 6}, **(hook or {})))
    calls = {"n": 0}

    def check():
        calls["n"] += 1
        if interrupt_after is not None and calls["n"] > interrupt_after:
            raise EngineError("interrupted", "interrupted")
    ctx = types.SimpleNamespace(check_interrupt=check, progress=lambda *a, **k: calls.setdefault("p", []).append(a),
                                warn=lambda m: calls.setdefault("w", []).append(m))
    req = types.SimpleNamespace(output_dir=".", vae_recovery_dir=None)
    ad._ADAPTERS.clear()
    ad._ADAPTERS[("t", "t", 0)] = adapter
    orig = ad.resolve_model, ad.load_adapter
    ad.resolve_model = lambda name, path="": ("t", "t")
    ad.load_adapter = lambda kind, location, dtype: adapter
    try:
        return ad.generate_frames(engine, gp, req, ctx, cfg, mode, 123), calls, engine
    finally:
        ad.resolve_model, ad.load_adapter = orig


def test_frames_are_deterministic_and_have_the_requested_count():
    a, calls, _ = run_frames()
    b, _, _ = run_frames()
    assert len(a) == 6 and a[0].size == (16, 16)
    assert all(x.tobytes() == y.tobytes() for x, y in zip(a, b))
    assert calls["p"][-1][:2] == (4, 4)
    assert a[0].tobytes() != a[3].tobytes()


def test_motion_changes_the_result_and_hooks_are_removed():
    engine = StubEngine()
    with_motion, _, _ = run_frames(engine)
    assert not engine.unet.down_blocks[0].attentions[0]._forward_hooks
    zero = tiny_adapter()
    for p in zero.parameters():
        torch.nn.init.zeros_(p)
    still, _, _ = run_frames(StubEngine(), adapter=zero)
    assert any(x.tobytes() != y.tobytes() for x, y in zip(with_motion, still))


def test_long_video_uses_context_windows_and_all_closed_loop_modes():
    for mode in ("N", "R-P", "R+P", "A"):
        frames_, _, _ = run_frames(hook={"video_length": 14, "batch_size": 6, "overlap": 2, "closed_loop": mode, "stride": 2})
        assert len(frames_) == 14


def test_prompt_travel_encodes_every_keyframe():
    engine = StubEngine()
    frames_, _, _ = run_frames(engine, gp_over={"prompt": "head\n0: one\n3: two\ntail"}, hook={"video_length": 6})
    assert len(frames_) == 6
    assert "head, one, tail" in engine.encoded and "head, two, tail" in engine.encoded
    plain = StubEngine()
    run_frames(plain, gp_over={"prompt": "just a cat"})
    assert plain.encoded.count("just a cat") == 1


def test_free_init_runs_extra_passes():
    _, calls, _ = run_frames(hook={"free_init": True, "free_init_iterations": 2})
    assert calls["p"][-1][:2] == (8, 8)
    plain, _, _ = run_frames()
    fi, _, _ = run_frames(hook={"free_init": True, "free_init_iterations": 2})
    assert any(x.tobytes() != y.tobytes() for x, y in zip(plain, fi))


def test_linear_betas_override_is_scoped_to_the_scheduler_build():
    engine = StubEngine()
    engine._ms_overrides = {"prediction_type": "epsilon"}
    run_frames(engine)
    assert engine.overrides_seen == {"prediction_type": "epsilon", "beta_schedule": "linear"}
    assert engine._ms_overrides == {"prediction_type": "epsilon"}
    off = StubEngine()
    run_frames(off, hook={"linear_betas": False})
    assert "beta_schedule" not in off.overrides_seen


def test_euler_sampler_and_cfg_one():
    frames_, _, _ = run_frames(gp_over={"sampler_name": "Euler", "cfg_scale": 1.0})
    assert len(frames_) == 6


def test_cancel_stops_the_run():
    with pytest.raises(EngineError) as info:
        run_frames(interrupt_after=3)
    assert info.value.code == "interrupted"


def test_context_larger_than_the_module_is_rejected():
    with pytest.raises(EngineError):
        run_frames(hook={"batch_size": 20, "video_length": 20})  # tiny adapter table is 16


def test_validate_job_rejects_incompatible_requests():
    engine = StubEngine()
    gp = types.SimpleNamespace(batch_size=1, width=64, height=64, img2img=types.SimpleNamespace(mask=None))
    cfg = ad.normalize({})

    def check(**over):
        base = dict(hires=False, controlnet=False, inpaint_model=False, model_keyword=False, refine=False, hires_plan=False)
        base.update(over)
        ad.validate_job(engine, gp, "txt2img", cfg, **base)
    check()
    for bad in ({"hires": True}, {"controlnet": True}, {"inpaint_model": True}, {"model_keyword": True}, {"refine": True}):
        with pytest.raises(EngineError):
            check(**bad)
    gp.batch_size = 2
    with pytest.raises(EngineError):
        check()
    gp.batch_size = 1
    engine.kind = "sd2"
    with pytest.raises(EngineError):
        check()
    engine.kind = "sd15"
    with pytest.raises(EngineError):
        ad.validate_job(engine, gp, "img2img", ad.normalize({"free_init": True}), hires=False, controlnet=False,
                        inpaint_model=False, model_keyword=False, refine=False, hires_plan=False)
    with pytest.raises(EngineError):
        ad.validate_job(engine, gp, "txt2img", ad.normalize({"last_frame": "x.png"}), hires=False, controlnet=False,
                        inpaint_model=False, model_keyword=False, refine=False, hires_plan=False)


def test_unapplied_hooks_are_warned_about():
    warnings = []
    gp = types.SimpleNamespace(hooks={"freeu": {"b1": 1.2}, "llul": {"enabled": False}, "pnginfo_injection": {}, "adetailer": {}, "sonar": {}, "pag_xyz": {"scale": 1}})
    ad.warn_unapplied(gp, types.SimpleNamespace(warn=warnings.append))
    assert len(warnings) == 1 and warnings[0].endswith("freeu, sonar"), warnings
    quiet = []
    ad.warn_unapplied(types.SimpleNamespace(hooks={"animatediff": {}, "nsfw_checker": {}}), types.SimpleNamespace(warn=quiet.append))
    assert quiet == []
