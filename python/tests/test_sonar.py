import math
import types

import pytest

from sdfx import sonar
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")
diffusers = pytest.importorskip("diffusers")


# --- independent restatements of the documented source (Kahsolt/stable-diffusion-webui-sonar 15f9285) --------------

def src_momentum_step(x, d, dt, momentum, momentum_hist, sign, history_d):
    p = 1.0 - momentum
    if sign == "pos":
        momentum_d = (1.0 - p) * d + p * history_d
    else:
        momentum_d = (1.0 + p) * d - p * history_d
    x = x + momentum_d * dt
    q = 1.0 - momentum_hist
    if isinstance(history_d, int) and history_d == 0:
        history_d = momentum_d
    elif sign == "pos":
        history_d = (1.0 - q) * history_d + q * momentum_d
    else:
        history_d = (1.0 + q) * history_d - q * momentum_d
    return x, history_d


def src_guidance(x, meth, ref_norm, hgf, denoised, sigmas, i):
    if meth == "euler":
        avg_t = denoised.mean(dim=[1, 2, 3], keepdim=True)
        std_t = denoised.std(dim=[1, 2, 3], keepdim=True)
        shifted = ref_norm * std_t + avg_t
        d = (x - shifted) / sigmas[i]
        x = x + d * ((sigmas[i + 1] - sigmas[i]) * hgf)
    else:
        avg_t = x.mean(dim=[1, 2, 3], keepdim=True)
        std_t = x.std(dim=[1, 2, 3], keepdim=True)
        x = (1 - hgf) * x + hgf * (ref_norm * std_t + avg_t)
    return x


def toy_eps(x, t):
    """A deterministic stand-in for the UNet: a smooth function of the (scaled) input and the timestep."""
    return torch.tanh(0.7 * x) + 0.1 * torch.sin(x * 3.0) * (float(t) / 1000.0)


def make_scheduler(name, steps, prediction="epsilon"):
    cls = getattr(diffusers, name)
    sched = cls(num_train_timesteps=1000, beta_start=0.00085, beta_end=0.012, beta_schedule="scaled_linear",
                prediction_type=prediction)
    sched.set_timesteps(steps)
    return sched


def run(sched, x, generator, steps):
    x = x * sched.init_noise_sigma
    for t in sched.timesteps:
        inp = sched.scale_model_input(x, t)
        eps = toy_eps(inp, t)
        x = sched.step(eps, t, x, generator=generator, return_dict=False)[0]
    return x


def settings(**kw):
    return sonar.normalize(kw)


def stepper_for(c, sched, steps, seed=7, reference=None):
    return sonar.Stepper(c, steps=steps, seed=seed, ancestral=type(sched).__name__ == "EulerAncestralDiscreteScheduler",
                         reference=reference)


# --- settings ---------------------------------------------------------------------------------------------------------

def test_defaults_follow_the_source():
    c = sonar.normalize({})
    assert (c["sampler"], c["momentum"], c["momentum_hist"], c["momentum_hist_init"], c["momentum_sign"]) == \
        ("Euler a", 0.95, 0.75, "zero", "pos")
    assert (c["ref_meth"], c["ref_hgf"], c["ref_min_step"], c["ref_max_step"]) == ("linear", 0.2, 0.0, 0.75)
    assert (c["upscale"], c["upscaler"], c["upscale_ratio"], c["upscale_width"], c["upscale_height"]) == \
        (False, "Lanczos", 1.0, 0, 0)


@pytest.mark.parametrize("bad", [{"sampler": "DDIM"}, {"momentum_sign": "up"}, {"momentum_hist_init": "x"},
                                 {"ref_meth": "cubic"}, {"momentum": 1.5}, {"momentum_hist": -0.1}, {"ref_hgf": 2},
                                 {"ref_max_step": -1}, {"upscale_ratio": 0.5}, {"upscale_width": -8},
                                 {"momentum": float("nan")}, {"upscale_width": 1.5}, {"momentum": "abc"}])
def test_invalid_settings_are_rejected(bad):
    with pytest.raises(EngineError):
        sonar.normalize(bad)


def test_effective_sampler_replaces_the_request_sampler_only_while_active():
    assert sonar.effective_sampler({}, "DPM++ 2M") == "DPM++ 2M"
    assert sonar.effective_sampler({"sonar": {"enabled": False}}, "DPM++ 2M") == "DPM++ 2M"
    assert sonar.effective_sampler({"sonar": {"sampler": "Euler"}}, "DPM++ 2M") == "Euler"
    assert sonar.effective_sampler({"sonar": {}}, "DPM++ 2M") == "Euler a"
    assert sonar.effective_sampler({"sonar": {"sampler": "Naive"}}, "DPM++ 2M") == "Euler"


def test_step_bounds_are_steps_above_one_and_fractions_otherwise():
    assert sonar.step_bound(0.75, 20) == 15
    assert sonar.step_bound(0.0, 20) == 0
    assert sonar.step_bound(1.0, 20) == 20
    assert sonar.step_bound(5, 20) == 5
    assert sonar.step_bound(2.9, 20) == 2


# --- update rules vs the source --------------------------------------------------------------------------------------

@pytest.mark.parametrize("sign", ["pos", "neg"])
def test_momentum_step_matches_the_source_over_a_history(sign):
    g = torch.Generator().manual_seed(1)
    x = torch.randn(2, 4, 6, 6, generator=g)
    mine, theirs = x.clone(), x.clone()
    hist_mine, hist_theirs = None, 0
    for _ in range(6):
        d = torch.randn_like(x)
        dt = -0.37
        mine, hist_mine = sonar.momentum_step(mine, d, dt, 0.9, 0.6, sign, hist_mine)
        theirs, hist_theirs = src_momentum_step(theirs, d, dt, 0.9, 0.6, sign, hist_theirs)
        assert torch.equal(mine, theirs) and torch.equal(hist_mine, hist_theirs)
    # tensor history (rand_init / rand_new) uses the blend from the first step
    h0 = torch.randn_like(x)
    a = sonar.momentum_step(x, d, dt, 0.8, 0.3, sign, h0)
    b = src_momentum_step(x, d, dt, 0.8, 0.3, sign, h0)
    assert torch.equal(a[0], b[0]) and torch.equal(a[1], b[1])


def test_zero_history_scales_the_first_step_by_the_momentum():
    x = torch.zeros(1, 4, 2, 2)
    d = torch.ones_like(x)
    out, hist = sonar.momentum_step(x, d, -1.0, 0.8, 0.5, "pos", None)
    assert torch.allclose(out, torch.full_like(x, -0.8))
    assert torch.allclose(hist, torch.full_like(x, 0.8))
    out, _ = sonar.momentum_step(x, d, -1.0, 0.8, 0.5, "neg", None)
    assert torch.allclose(out, torch.full_like(x, -1.2))


@pytest.mark.parametrize("method", ["linear", "euler"])
def test_guidance_step_matches_the_source(method):
    g = torch.Generator().manual_seed(3)
    x = torch.randn(2, 4, 8, 8, generator=g) * 4
    denoised = torch.randn(2, 4, 8, 8, generator=g)
    latent = torch.randn(2, 4, 8, 8, generator=g) * 2 + 1
    ref = sonar.normalize_reference(latent)
    assert torch.allclose(ref.mean(dim=[2, 3]), torch.zeros(2, 4), atol=1e-5)
    assert torch.allclose(ref.std(dim=[2, 3]), torch.ones(2, 4), atol=1e-5)
    sigmas = torch.tensor([9.0, 6.0, 3.0, 0.0])
    got = sonar.guidance_step(x, method, ref, 0.02, denoised, sigmas[1], sigmas[2])
    assert torch.equal(got, src_guidance(x, method, ref, 0.02, denoised, sigmas, 1))
    assert not torch.equal(got, x)


# --- the scheduler step -----------------------------------------------------------------------------------------------

@pytest.mark.parametrize("name", ["EulerAncestralDiscreteScheduler", "EulerDiscreteScheduler"])
@pytest.mark.parametrize("prediction", ["epsilon", "v_prediction"])
def test_momentum_one_is_bit_identical_to_diffusers(name, prediction):
    steps = 7
    x = torch.randn(2, 4, 8, 8, generator=torch.Generator().manual_seed(5))
    plain = make_scheduler(name, steps, prediction)
    expected = run(plain, x, torch.Generator().manual_seed(11), steps)
    wrapped = make_scheduler(name, steps, prediction)
    c = settings(momentum=1.0, sampler="Euler a" if "Ancestral" in name else "Euler")
    restore = sonar.wrap_scheduler(wrapped, stepper_for(c, wrapped, steps))
    got = run(wrapped, x, torch.Generator().manual_seed(11), steps)
    assert torch.equal(got, expected)
    restore()
    assert "step" not in wrapped.__dict__


def test_half_precision_output_keeps_the_model_dtype():
    steps = 4
    sched = make_scheduler("EulerAncestralDiscreteScheduler", steps)
    c = settings(momentum=0.9)
    sonar.wrap_scheduler(sched, stepper_for(c, sched, steps))
    x = (torch.randn(1, 4, 8, 8) * sched.init_noise_sigma).half()
    out = sched.step(torch.randn(1, 4, 8, 8).half(), sched.timesteps[0], x, generator=torch.Generator().manual_seed(0))
    assert out.prev_sample.dtype == torch.float16
    assert out.pred_original_sample.dtype == torch.float32
    assert sched.step_index == 1


def test_ancestral_loop_matches_a_literal_restatement_with_momentum():
    steps = 9
    x0 = torch.randn(2, 4, 8, 8, generator=torch.Generator().manual_seed(2))
    sched = make_scheduler("EulerAncestralDiscreteScheduler", steps)
    c = settings(momentum=0.9, momentum_hist=0.5, momentum_sign="neg", momentum_hist_init="zero")
    sonar.wrap_scheduler(sched, stepper_for(c, sched, steps))
    got = run(sched, x0, torch.Generator().manual_seed(4), steps)

    ref = make_scheduler("EulerAncestralDiscreteScheduler", steps)
    sigmas = ref.sigmas
    gen = torch.Generator().manual_seed(4)
    x = x0 * ref.init_noise_sigma
    hist = 0
    for i, t in enumerate(ref.timesteps):
        inp = x / (sigmas[i] ** 2 + 1) ** 0.5
        denoised = x - sigmas[i] * toy_eps(inp, t)
        up = (sigmas[i + 1] ** 2 * (sigmas[i] ** 2 - sigmas[i + 1] ** 2) / sigmas[i] ** 2) ** 0.5
        down = (sigmas[i + 1] ** 2 - up ** 2) ** 0.5
        d = (x - denoised) / sigmas[i]
        x, hist = src_momentum_step(x, d, down - sigmas[i], 0.9, 0.5, "neg", hist)
        x = x + torch.randn(x.shape, generator=gen) * up
    assert torch.allclose(got, x, atol=1e-5, rtol=1e-5)


def test_euler_loop_matches_a_literal_restatement_with_a_noise_history():
    steps = 8
    x0 = torch.randn(1, 4, 8, 8, generator=torch.Generator().manual_seed(8))
    sched = make_scheduler("EulerDiscreteScheduler", steps)
    c = settings(sampler="Euler", momentum=0.85, momentum_hist=0.9, momentum_sign="pos", momentum_hist_init="rand_init")
    sonar.wrap_scheduler(sched, stepper_for(c, sched, steps))
    got = run(sched, x0, None, steps)

    ref = make_scheduler("EulerDiscreteScheduler", steps)
    sigmas = ref.sigmas
    x = x0 * ref.init_noise_sigma
    hist = x
    for i, t in enumerate(ref.timesteps):
        inp = x / (sigmas[i] ** 2 + 1) ** 0.5
        denoised = x - sigmas[i] * toy_eps(inp, t)
        d = (x - denoised) / sigmas[i]
        x, hist = src_momentum_step(x, d, sigmas[i + 1] - sigmas[i], 0.85, 0.9, "pos", hist)
    assert torch.allclose(got, x, atol=1e-5, rtol=1e-5)


@pytest.mark.parametrize("method", ["linear", "euler"])
def test_naive_loop_adds_the_reference_guidance_and_noise(method):
    steps = 10
    x0 = torch.randn(1, 4, 8, 8, generator=torch.Generator().manual_seed(9))
    latent = torch.randn(1, 4, 8, 8, generator=torch.Generator().manual_seed(10)) * 3
    reference = sonar.normalize_reference(latent)
    sched = make_scheduler("EulerDiscreteScheduler", steps)
    c = settings(sampler="Naive", momentum=0.9, momentum_hist=0.7, momentum_hist_init="rand_new", ref_meth=method,
                 ref_hgf=1.0, ref_min_step=0.2, ref_max_step=0.8)
    sonar.wrap_scheduler(sched, stepper_for(c, sched, steps, seed=21, reference=reference))
    got = run(sched, x0, None, steps)

    ref = make_scheduler("EulerDiscreteScheduler", steps)
    sigmas = ref.sigmas
    gen = torch.Generator("cpu").manual_seed(21)
    x = x0 * ref.init_noise_sigma
    hist = torch.randn(x.shape, generator=gen)
    lo, hi = round(0.2 * steps), round(0.8 * steps)
    guided = 0
    for i, t in enumerate(ref.timesteps):
        inp = x / (sigmas[i] ** 2 + 1) ** 0.5
        denoised = x - sigmas[i] * toy_eps(inp, t)
        d = (x - denoised) / sigmas[i]
        x, hist = src_momentum_step(x, d, sigmas[i + 1] - sigmas[i], 0.9, 0.7, "pos", hist)
        if lo <= i <= hi:
            x = src_guidance(x, method, reference, 0.1, denoised, sigmas, i)
            guided += 1
        x = x + torch.randn(x.shape, generator=gen) * 1e-5
    assert guided == hi - lo + 1
    assert torch.allclose(got, x, atol=1e-4, rtol=1e-4)

    # the guidance matters: without the reference the result differs
    plain = make_scheduler("EulerDiscreteScheduler", steps)
    sonar.wrap_scheduler(plain, stepper_for(c, plain, steps, seed=21))
    assert not torch.allclose(run(plain, x0, None, steps), got, atol=1e-3)


def test_the_reference_only_guides_the_naive_sampler():
    steps = 6
    x0 = torch.randn(1, 4, 8, 8, generator=torch.Generator().manual_seed(1))
    reference = sonar.normalize_reference(torch.randn(1, 4, 8, 8))
    outs = []
    for ref in (None, reference):
        sched = make_scheduler("EulerDiscreteScheduler", steps)
        c = settings(sampler="Euler", ref_hgf=1.0)
        sonar.wrap_scheduler(sched, stepper_for(c, sched, steps, reference=ref))
        outs.append(run(sched, x0, None, steps))
    assert torch.equal(outs[0], outs[1])


def test_runs_are_deterministic_for_random_choices_and_follow_the_seed():
    steps = 8
    x0 = torch.randn(2, 4, 8, 8, generator=torch.Generator().manual_seed(1))

    def go(seed):
        sched = make_scheduler("EulerDiscreteScheduler", steps)
        c = settings(sampler="Euler", momentum=0.8, momentum_sign="rand", momentum_hist_init="rand_new")
        sonar.wrap_scheduler(sched, stepper_for(c, sched, steps, seed=seed))
        return run(sched, x0, None, steps)

    assert torch.equal(go(5), go(5))
    assert not torch.allclose(go(5), go(6))


def test_batched_reference_is_broadcast():
    steps = 5
    x0 = torch.randn(3, 4, 8, 8, generator=torch.Generator().manual_seed(1))
    sched = make_scheduler("EulerDiscreteScheduler", steps)
    c = settings(sampler="Naive", ref_hgf=1.0)
    sonar.wrap_scheduler(sched, stepper_for(c, sched, steps, reference=sonar.normalize_reference(torch.randn(1, 4, 8, 8))))
    assert run(sched, x0, None, steps).shape == x0.shape


def test_churn_and_unknown_predictions_are_rejected():
    steps = 4
    sched = make_scheduler("EulerDiscreteScheduler", steps)
    sonar.wrap_scheduler(sched, stepper_for(settings(sampler="Euler"), sched, steps))
    with pytest.raises(EngineError):
        sched.step(torch.zeros(1, 4, 8, 8), sched.timesteps[0], torch.zeros(1, 4, 8, 8), s_churn=1.0)
    ancestral = make_scheduler("EulerAncestralDiscreteScheduler", steps)
    ancestral.register_to_config(prediction_type="sample")
    sonar.wrap_scheduler(ancestral, stepper_for(settings(), ancestral, steps))
    with pytest.raises(EngineError):
        ancestral.step(torch.zeros(1, 4, 8, 8), ancestral.timesteps[0], torch.zeros(1, 4, 8, 8))


def test_install_checks_the_scheduler_and_restores_on_cleanup():
    engine = types.SimpleNamespace()
    gp = types.SimpleNamespace(steps=6)
    euler_a = make_scheduler("EulerAncestralDiscreteScheduler", 6)
    cleanups = []
    sonar.install(engine, None, gp, {}, None, {}, pass_="base", seed=1, sched=euler_a, w=64, h=64, cleanups=cleanups)
    assert "step" in euler_a.__dict__
    for fn in cleanups:
        fn()
    assert "step" not in euler_a.__dict__
    with pytest.raises(EngineError):  # Euler scheduler for the Euler a setting
        sonar.install(engine, None, gp, {}, None, {}, pass_="base", seed=1,
                      sched=make_scheduler("EulerDiscreteScheduler", 6), w=64, h=64, cleanups=[])
    with pytest.raises(EngineError):
        sonar.install(engine, None, gp, {}, None, {}, pass_="base", seed=1, sched=make_scheduler("DDIMScheduler", 6),
                      w=64, h=64, cleanups=[])


def test_the_reference_is_ignored_with_a_warning_for_other_samplers(tmp_path):
    warnings = []
    ctx = types.SimpleNamespace(warn=warnings.append)
    sched = make_scheduler("EulerAncestralDiscreteScheduler", 6)
    sonar.install(types.SimpleNamespace(), None, types.SimpleNamespace(steps=6), {}, ctx, {"ref_img": "x.png"},
                  pass_="base", seed=1, sched=sched, w=64, h=64, cleanups=[])
    assert warnings and "Naive" in warnings[0]


# --- upscale ----------------------------------------------------------------------------------------------------------

def test_upscale_targets_follow_the_source():
    t = lambda **kw: sonar.upscale_target(512, 768, settings(upscale=True, **kw))  # noqa: E731
    assert sonar.upscale_target(512, 768, settings()) is None
    assert t(upscale_ratio=1.0) is None
    assert t(upscaler="None", upscale_ratio=2.0) is None
    assert t(upscale_ratio=1.5) == (768, 1152, False)
    assert t(upscale_width=1024) == (1024, 1536, True)
    assert t(upscale_height=1024) == (683, 1024, True)
    assert t(upscale_width=1024, upscale_height=1024) == (1024, 1024, True)


def test_upscale_image_covers_and_crops(monkeypatch):
    Image = pytest.importorskip("PIL.Image")
    seen = []
    from sdfx import upscalers

    def fake(img, w, h, name, path, device, dtype, check):
        seen.append((w, h, name))
        return img.resize((w, h))
    monkeypatch.setattr(upscalers, "upscale_to", fake)
    engine = types.SimpleNamespace(device="cpu", dtype=None)
    ctx = types.SimpleNamespace(check_interrupt=lambda: None)
    gp = types.SimpleNamespace(width=64, height=96)
    req = types.SimpleNamespace(upscaler_paths={})
    im = Image.new("RGB", (64, 96), "white")
    out = sonar.upscale_image(engine, im, settings(upscale=True, upscale_ratio=2.0), gp, req, ctx)
    assert out.size == (128, 192) and seen[-1] == (128, 192, "Lanczos")
    out = sonar.upscale_image(engine, im, settings(upscale=True, upscale_width=128, upscale_height=128), gp, req, ctx)
    assert out.size == (128, 128) and seen[-1] == (128, 192, "Lanczos")
    assert sonar.upscale_image(engine, im, settings(), gp, req, ctx) is im


def test_hook_is_registered_everywhere():
    from sdfx import dryrun, extensions, hooks
    assert "sonar" in hooks.KNOWN_HOOKS and "sonar" in extensions.EXTENSION_HOOKS
    assert "sonar" in dryrun.FakeEngine().features()


# --- a tiny real pipeline ---------------------------------------------------------------------------------------------

def _pipeline(scheduler_name):
    unet = diffusers.UNet2DConditionModel(
        sample_size=8, in_channels=4, out_channels=4, block_out_channels=(8, 8), layers_per_block=1,
        down_block_types=("CrossAttnDownBlock2D", "CrossAttnDownBlock2D"),
        up_block_types=("CrossAttnUpBlock2D", "CrossAttnUpBlock2D"), norm_num_groups=4, cross_attention_dim=8,
        attention_head_dim=2).eval()
    vae = diffusers.AutoencoderKL(in_channels=3, out_channels=3, latent_channels=4,
                                  down_block_types=("DownEncoderBlock2D",) * 4, up_block_types=("UpDecoderBlock2D",) * 4,
                                  block_out_channels=(4, 8, 16, 16), layers_per_block=1, norm_num_groups=4)
    pipe = diffusers.StableDiffusionPipeline(
        vae=vae, text_encoder=None, tokenizer=None, unet=unet,
        scheduler=getattr(diffusers, scheduler_name)(steps_offset=1), safety_checker=None, feature_extractor=None,
        requires_safety_checker=False)
    pipe.set_progress_bar_config(disable=True)
    return pipe


def _generate(pipe, config, *, pass_="base", reference_file=None):
    torch.manual_seed(0)
    embeds = torch.randn(1, 77, 8, generator=torch.Generator().manual_seed(1))
    kw = dict(prompt_embeds=embeds, negative_prompt_embeds=torch.zeros_like(embeds), num_inference_steps=6,
              guidance_scale=4.0, height=64, width=64, output_type="np", generator=[torch.Generator().manual_seed(9)])
    cleanups = []
    gp = types.SimpleNamespace(steps=6)
    engine = types.SimpleNamespace(device="cpu", dtype=torch.float32)
    try:
        if config is not None:
            if reference_file:
                config = dict(config, ref_img=reference_file)
            sonar.install(engine, pipe, gp, kw, None, config, pass_=pass_, seed=3, sched=pipe.scheduler, w=64, h=64,
                          cleanups=cleanups)
        with torch.no_grad():
            return pipe(**kw).images[0]
    finally:
        for fn in reversed(cleanups):
            fn()


@pytest.mark.parametrize("name,sampler", [("EulerAncestralDiscreteScheduler", "Euler a"),
                                          ("EulerDiscreteScheduler", "Euler")])
def test_pipeline_momentum_changes_the_image_and_one_is_a_no_op(name, sampler):
    pipe = _pipeline(name)
    base = _generate(pipe, None)
    assert (_generate(pipe, {"sampler": sampler, "momentum": 1.0}) == base).all()
    one = _generate(pipe, {"sampler": sampler, "momentum": 0.9})
    assert not (one == base).all()
    assert (_generate(pipe, {"sampler": sampler, "momentum": 0.9}) == one).all()
    assert "step" not in pipe.scheduler.__dict__
    assert (_generate(pipe, None) == base).all()


def test_pipeline_naive_reference_image_pulls_the_result(tmp_path):
    Image = pytest.importorskip("PIL.Image")
    import numpy as np
    pipe = _pipeline("EulerDiscreteScheduler")
    path = tmp_path / "ref.png"
    Image.fromarray((np.random.default_rng(1).random((64, 64, 3)) * 255).astype("uint8")).save(path)
    naive = {"sampler": "Naive", "momentum": 1.0}
    without = _generate(pipe, naive)
    strong = dict(naive, ref_hgf=1.0, ref_min_step=0.0, ref_max_step=1.0)
    with_ref = _generate(pipe, strong, reference_file=str(path))
    assert not (with_ref == without).all()
    assert (_generate(pipe, strong, reference_file=str(path)) == with_ref).all()
    other = _generate(pipe, dict(strong, ref_meth="euler"), reference_file=str(path))
    assert not (other == with_ref).all()
