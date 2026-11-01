import math
from types import SimpleNamespace

import pytest

from sdfx import characteristic_guidance as chg
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")

BETAS = torch.linspace(0.00085 ** 0.5, 0.012 ** 0.5, 1000, dtype=torch.float64) ** 2
ALPHAS = torch.cumprod(1 - BETAS, dim=0).float()
LOG_SIGMAS = (((1 - ALPHAS) / ALPHAS) ** 0.5).log()


# -- literal restatement of the pinned source (scripts/CharaIte.py, A1111 path) ---------------------------------
def up_solve_least_squares(A, B):
    C = torch.matmul(A.transpose(-2, -1), A)
    U, S, Vh = torch.linalg.svd(C.float(), full_matrices=False)
    D_inv = torch.diag_embed(1.0 / torch.maximum(S, torch.ones_like(S) * 1e-4))
    C_inv = Vh.transpose(-1, -2).matmul(D_inv).matmul(U.transpose(-1, -2))
    return torch.matmul(torch.matmul(C_inv, A.transpose(-2, -1)), B)


def up_split_basis(g, n):
    g_flat = g.view(g.shape[0], g.shape[1], -1)
    quantiles = torch.quantile(g_flat, torch.linspace(0, 1, n + 1, device=g.device), dim=-1).permute(1, 2, 0)
    output = torch.zeros(*g.shape, n, device=g.device)
    for i in range(n):
        lower = quantiles[..., i][..., None, None]
        upper = quantiles[..., i + 1][..., None, None]
        if i < n - 1:
            mask = (g >= lower) & (g < upper)
        else:
            mask = (g >= lower) & (g <= upper)
        output[..., i] = g * mask
    return output.view(*g.shape, n)


def up_proj_least_squares(A, B, reg):
    C = torch.matmul(A.transpose(-2, -1), A) + reg * torch.eye(A.shape[-1], device=A.device)
    eigenvalues, eigenvectors = torch.linalg.eigh(C)
    D_inv = torch.diag_embed(1.0 / torch.maximum(eigenvalues, torch.ones_like(eigenvalues) * 1e-4))
    C_inv = torch.matmul(torch.matmul(eigenvectors, D_inv), eigenvectors.transpose(-2, -1))
    return torch.matmul(A, torch.matmul(torch.matmul(C_inv, A.transpose(-2, -1)), B))


def append_dims(x, n):
    return x[(...,) + (None,) * (n - x.ndim)]


def up_denoiser_forward(self, model, x_in, sigma_in, t_outer, cond_scale, kind):
    """CHGDenoiser.forward on the k-diffusion (kind eps / v) or timesteps (kind ts_eps / ts_v) denoiser.

    Rows are A1111's [cond, uncond]; `model(input, t)` is the UNet on those rows. Returns the combined
    denoised (k-diffusion) or guided eps (timesteps)."""
    uncond_n = x_in.shape[0] // 2

    def get(inp, t):
        return model(inp, t)

    dxs = None
    if dxs is None:
        dxs = torch.zeros_like(x_in[-uncond_n:])
    res_thres = self.res_thres
    num_x_in_cond = len(x_in[:-uncond_n]) // len(dxs)
    h = cond_scale * num_x_in_cond
    if kind == "eps":
        t_in = (sigma_in.log() - LOG_SIGMAS[:, None]).abs().argmin(dim=0).view(sigma_in.shape)
        abt = ALPHAS[t_in.long()]
        c_out, c_in = [append_dims(x, x_in.ndim) for x in (-sigma_in, 1 / (sigma_in ** 2 + 1) ** 0.5)]
    elif kind == "v":
        t_in = (sigma_in.log() - LOG_SIGMAS[:, None]).abs().argmin(dim=0).view(sigma_in.shape)
        abt = ALPHAS[t_in.long()]
        c_skip, c_out, c_in = [append_dims(x, x_in.ndim) for x in (
            1 / (sigma_in ** 2 + 1), -sigma_in / (sigma_in ** 2 + 1) ** 0.5, 1 / (sigma_in ** 2 + 1) ** 0.5)]
    else:
        t_in = sigma_in
        abt = ALPHAS[t_in.long()]
    scale = ((1 - abt) ** 0.5)[-uncond_n:, None, None, None]
    abt_current = abt[-uncond_n:, None, None, None]
    abt_smallest = ALPHAS[-1]
    dxs_Anderson = []
    g_Anderson = []

    def AndersonAccR(dxs, g, reg_level, reg_target, pre_condition=None, m=3):
        batch = dxs.shape[0]
        x_shape = dxs.shape[1:]
        reg_residual_form = reg_level
        g_flat = g.reshape(batch, -1)
        dxs_flat = dxs.reshape(batch, -1)
        res_g = self.reg_size * (reg_residual_form[:, None] - reg_target[:, None])
        res_dxs = reg_residual_form[:, None]
        g_Anderson.append(torch.cat((g_flat, res_g), dim=-1))
        dxs_Anderson.append(torch.cat((dxs_flat, res_dxs), dim=-1))
        if len(g_Anderson) < 2:
            return dxs, g, res_dxs[:, 0], res_g[:, 0]
        g_Anderson[-2] = g_Anderson[-1] - g_Anderson[-2]
        dxs_Anderson[-2] = dxs_Anderson[-1] - dxs_Anderson[-2]
        if len(g_Anderson) > m:
            del dxs_Anderson[0]
            del g_Anderson[0]
        gA = torch.cat([g[..., None] for g in g_Anderson[:-1]], dim=-1)
        gB = g_Anderson[-1][..., None]
        gA_norm = torch.maximum(torch.sum(gA ** 2, dim=-2, keepdim=True) ** 0.5, torch.ones_like(gA) * 1e-4)
        gamma = torch.linalg.lstsq(gA / gA_norm, gB).solution
        if torch.sum(torch.isnan(gamma)) > 0:
            gamma = up_solve_least_squares(gA / gA_norm, gB)
        xA = torch.cat([x[..., None] for x in dxs_Anderson[:-1]], dim=-1)
        xB = dxs_Anderson[-1][..., None]
        xO = xB - (xA / gA_norm).matmul(gamma)
        gO = gB - (gA / gA_norm).matmul(gamma)
        return xO[:, :-1].reshape(batch, *x_shape), gO[:, :-1].reshape(batch, *x_shape), xO[:, -1, 0], gO[:, -1, 0]

    def downsample_reg_g(dx, g_1, reg):
        if g_1 is None:
            return dx
        elif self.noise_base >= 1:
            A = g_1.reshape(g_1.shape[0] * g_1.shape[1], g_1.shape[2] * g_1.shape[3], g_1.shape[4])
            B = dx.reshape(dx.shape[0] * dx.shape[1], -1, 1)
            regl = reg[:, None].expand(-1, dx.shape[1]).reshape(dx.shape[0] * dx.shape[1], 1, 1)
            return up_proj_least_squares(A, B, regl).reshape(*dx.shape)
        else:
            A = g_1.reshape(g_1.shape[0], g_1.shape[1] * g_1.shape[2] * g_1.shape[3], g_1.shape[4])
            B = dx.reshape(dx.shape[0], -1, 1)
            regl = reg[:, None].reshape(dx.shape[0], 1, 1)
            return up_proj_least_squares(A, B, regl).reshape(*dx.shape)

    g_1 = None
    reg_level = torch.zeros(dxs.shape[0], device=dxs.device) + max(5, self.reg_ini)
    reg_target_level = self.reg_ini * (abt_smallest / abt_current[:, 0, 0, 0]) ** (1 / self.reg_range)
    if self.dxs_buffer is not None:
        abt_prev = self.abt_buffer
        dxs = self.dxs_buffer
        dxs = dxs * ((abt_prev - abt_current * abt_prev) / (abt_current - abt_current * abt_prev))
        dxs = self.chara_decay * dxs
    iteration_counts = 0
    for iteration in range(self.ite):
        def compute_correction_direction(dxs):
            dxs_cond_part = torch.cat([*([(h - 1) * dxs[:, None, ...]] * num_x_in_cond)], axis=1).view(
                (dxs.shape[0] * num_x_in_cond, *dxs.shape[1:]))
            dxs_add = torch.cat([dxs_cond_part, h * dxs], axis=0)
            if kind == "eps":
                eps_out = get(x_in * c_in + dxs_add * c_in, t_in)
                pred_eps_uncond = eps_out[-uncond_n:]
                eps_cond_batch = eps_out[:-uncond_n]
                pred_eps_cond = torch.mean(eps_cond_batch.view((len(eps_cond_batch) // num_x_in_cond, num_x_in_cond,
                                                                *eps_cond_batch.shape[1:])), dim=1, keepdim=False)
                ggg = (pred_eps_uncond - pred_eps_cond) * scale / c_in[-uncond_n:]
            elif kind == "v":
                v_out = get(x_in * c_in + dxs_add * c_in, t_in)
                eps_out = -c_out * x_in + c_skip ** 0.5 * v_out
                pred_eps_uncond = eps_out[-uncond_n:]
                eps_cond_batch = eps_out[:-uncond_n]
                pred_eps_cond = torch.mean(eps_cond_batch.view((len(eps_cond_batch) // num_x_in_cond, num_x_in_cond,
                                                                *eps_cond_batch.shape[1:])), dim=1, keepdim=False)
                ggg = (pred_eps_uncond - pred_eps_cond) * scale / c_in[-uncond_n:]
            else:
                inp = x_in + dxs_add
                eps_out = get(inp, t_in)
                if kind == "ts_v":  # CompVisTimestepsVDenoiser.predict_eps_from_z_and_v
                    a = append_dims(ALPHAS[t_in.long()], 4)
                    eps_out = a ** 0.5 * eps_out + (1 - a) ** 0.5 * inp
                pred_eps_uncond = eps_out[-uncond_n:]
                eps_cond_batch = eps_out[:-uncond_n]
                pred_eps_cond = torch.mean(eps_cond_batch.view((len(eps_cond_batch) // num_x_in_cond, num_x_in_cond,
                                                                *eps_cond_batch.shape[1:])), dim=1, keepdim=False)
                ggg = (pred_eps_uncond - pred_eps_cond) * scale
            return ggg

        ggg = compute_correction_direction(dxs)
        g = dxs - downsample_reg_g(ggg, g_1, reg_level)
        if g_1 is None:
            g_basis = -compute_correction_direction(dxs * 0)
            g_1 = up_split_basis(g_basis, max(self.noise_base, 1))
            if self.noise_base >= 1:
                g_1_norm = torch.sum(g_1 ** 2, dim=(-2, -3), keepdim=True) ** 0.5
                g_1 = g_1 / torch.maximum(g_1_norm, torch.ones_like(g_1_norm) * 1e-4)
            else:
                g_1_norm = torch.sum(g_1 ** 2, dim=(-2, -3, -4), keepdim=True) ** 0.5
                g_1 = g_1 / torch.maximum(g_1_norm, torch.ones_like(g_1_norm) * 1e-4)
        reg_Acc = (reg_level * self.reg_w) ** 0.5
        reg_target = (reg_target_level * self.reg_w) ** 0.5
        g_flat_res = g.reshape(dxs.shape[0], -1)
        reg_g = self.reg_size * (reg_Acc[:, None] - reg_target[:, None])
        g_flat_res_reg = torch.cat((g_flat_res, reg_g), dim=-1)
        res_el = ((torch.mean((g_flat_res_reg) ** 2, dim=(-1), keepdim=False)) ** 0.5)[:, None, None, None]
        if iteration == 0:
            best_res_el = res_el
            best_dxs = dxs
            not_converged = torch.ones_like(res_el).bool()
        res_mask = torch.logical_and(res_el < best_res_el, not_converged).int()
        best_res_el = res_mask * res_el + (1 - res_mask) * best_res_el
        best_dxs = res_mask * dxs + (1 - res_mask) * best_dxs
        res_max = torch.max(best_res_el)
        not_converged = torch.logical_and(res_el >= res_thres, not_converged)
        if res_max < res_thres:
            break
        aa_dim = self.aa_dim if self.noise_base >= 1 else 1
        dxs_Acc, g_Acc, reg_dxs_Acc, reg_g_Acc = AndersonAccR(dxs, g, reg_Acc, reg_target, pre_condition=None,
                                                            m=aa_dim + 1)
        dxs = dxs_Acc - self.lr_chara * g_Acc
        reg_Acc = reg_dxs_Acc - self.lr_chara * reg_g_Acc
        reg_level = reg_Acc ** 2 / self.reg_w
        iteration_counts = iteration_counts * (1 - not_converged.long()) + iteration * not_converged.long()
    self.counts.append(iteration_counts)
    final_dxs = best_dxs * (1 - not_converged.long())
    dxs_cond_part = torch.cat([*([(h - 1) * final_dxs[:, None, ...]] * num_x_in_cond)], axis=1).view(
        (dxs.shape[0] * num_x_in_cond, *dxs.shape[1:]))
    dxs_add = torch.cat([dxs_cond_part, h * final_dxs], axis=0)
    self.dxs_buffer = final_dxs
    self.abt_buffer = abt_current
    # x_out_evaluation at x_in + dxs_add, then combine_denoised
    shifted = x_in + dxs_add
    if kind == "eps":
        x_out = shifted + c_out * get(shifted * c_in, t_outer)
    elif kind == "v":
        x_out = c_skip * shifted + c_out * get(shifted * c_in, t_outer)
    elif kind == "ts_eps":
        x_out = get(shifted, t_outer)
    else:
        a = append_dims(ALPHAS[t_outer.long()], 4)
        x_out = a ** 0.5 * get(shifted, t_outer) + (1 - a) ** 0.5 * shifted
    cond, uncond = x_out[:-uncond_n], x_out[-uncond_n:]
    return uncond + (cond - uncond) * cond_scale


def up_state(settings):
    e = chg.effective(chg.normalize(settings))
    return SimpleNamespace(res_thres=e["res_thres"], noise_base=e["noise_base"], lr_chara=e["lr"], ite=e["ite"],
                           reg_size=e["reg_size"], reg_ini=e["reg_ini"], reg_range=e["reg_range"], reg_w=e["reg_w"],
                           aa_dim=e["aa_dim"], chara_decay=e["chara_decay"], dxs_buffer=None, abt_buffer=None,
                           counts=[])


# -- a small deterministic non-linear "UNet" ----------------------------------------------------------------------
class FakeUNet(torch.nn.Module):
    def __init__(self, in_channels=4):
        super().__init__()
        gen = torch.Generator().manual_seed(0)
        self.mix = torch.randn(4, in_channels, generator=gen) * 0.6
        self.config = SimpleNamespace(out_channels=4, in_channels=in_channels, time_cond_proj_dim=None)
        self.calls = []

    def forward(self, sample, timestep, encoder_hidden_states=None, return_dict=False, **kwargs):
        t = float(torch.as_tensor(timestep).flatten()[0])
        self.calls.append((t, sample.detach().clone()))
        c = encoder_hidden_states.float().mean(dim=(1, 2)).view(-1, 1, 1, 1)
        mixed = torch.einsum("oc,bchw->bohw", self.mix, sample.float())
        out = torch.tanh(mixed * (1 + t / 1500) + c) + 0.2 * torch.sin(3 * sample[:, :4].float())
        return (out.to(sample.dtype),)


def conds(batch):
    gen = torch.Generator().manual_seed(5)
    positive = torch.randn(batch, 3, 8, generator=gen) * 0.5 + 0.4
    negative = torch.randn(batch, 3, 8, generator=gen) * 0.5 - 0.3
    return positive, negative


def kdiff_t(sigma: float) -> float:
    """k-diffusion's continuous sigma_to_t (what the sampler passes to the model)."""
    log_sigma = math.log(sigma)
    dists = log_sigma - LOG_SIGMAS
    low = int((dists >= 0).cumsum(0).argmax().clamp(max=len(LOG_SIGMAS) - 2))
    lo, hi = float(LOG_SIGMAS[low]), float(LOG_SIGMAS[low + 1])
    w = min(max((lo - log_sigma) / (lo - hi), 0.0), 1.0)
    return (1 - w) * low + w * (low + 1)


def run_both(settings, kind, *, steps=6, batch=2, scale=4.0, size=6, in_channels=4):
    """Runs NUI's hook (diffusers rows) and the source restatement (A1111 rows) over a sigma schedule."""
    s = chg.normalize(settings)
    gen = torch.Generator().manual_seed(11)
    x = torch.randn(batch, 4, size, size, generator=gen)
    extra = torch.randn(batch, in_channels - 4, size, size, generator=gen)
    positive, negative = conds(batch)
    k_path = kind in ("eps", "v")
    if k_path:
        schedule = [float(v) for v in torch.linspace(6.0, 0.4, steps)]
    else:
        schedule = [int(v) for v in torch.linspace(961, 21, steps).round()]
    unet = FakeUNet(in_channels)
    sched = SimpleNamespace(sigmas=torch.tensor(schedule + [0.0]) if k_path else None, step_index=0,
                            alphas_cumprod=ALPHAS, config={"prediction_type": "v_prediction" if kind in ("v", "ts_v") else "epsilon"})
    hook = chg.CharacteristicGuidanceHook(unet, sched, s, cfg_scale=scale, total_steps=steps, alphas_cumprod=ALPHAS,
                                          prediction=sched.config["prediction_type"], timesteps_path=not k_path).install()
    state = up_state(settings)
    first, last = chg.constrain_step(steps, s["start_step"]), chg.constrain_step(steps, s["stop_step"])
    ours, theirs = [], []
    try:
        for i, value in enumerate(schedule):
            sched.step_index = i
            ehs = torch.cat([negative, positive])
            if k_path:
                sigma = torch.tensor(value)
                c_in = 1 / (sigma ** 2 + 1) ** 0.5
                t_outer = torch.tensor(kdiff_t(value))
                u = torch.cat([x, x]) * c_in
            else:
                t_outer = torch.tensor(value)
                u = torch.cat([x, x])
            full = torch.cat([u, torch.cat([extra, extra])], dim=1) if in_channels > 4 else u
            out = unet(full, t_outer, encoder_hidden_states=ehs)[0]
            uo, co = out.chunk(2)
            guided = uo + scale * (co - uo)
            if kind == "eps":
                ours.append(x - value * guided)
            elif kind == "v":
                ours.append(x / (value ** 2 + 1) - value / (value ** 2 + 1) ** 0.5 * guided)
            elif kind == "ts_eps":
                ours.append(guided)
            else:
                a = ALPHAS[value]
                ours.append(a ** 0.5 * guided + (1 - a) ** 0.5 * x)

            def model(inp, t):  # A1111 rows: cond, then uncond
                return unet.forward(inp, t, encoder_hidden_states=torch.cat([positive, negative]))[0]
            if first <= i < last:
                theirs.append(up_denoiser_forward(state, model, torch.cat([x, x]),
                                                  torch.tensor([value] * 2 * batch, dtype=torch.float32 if k_path else torch.long),
                                                  t_outer, scale, kind))
            elif kind == "eps":
                theirs.append(x - value * guided)
            else:
                theirs.append(ours[-1])
            # a fixed update keeps the trajectories identical, so every step compares the same inputs
            x = x * 0.9 + 0.05
    finally:
        hook.remove()
    return ours, theirs, hook, state, unet


@pytest.mark.parametrize("kind", ["eps", "v", "ts_eps", "ts_v"])
@pytest.mark.parametrize("settings", [
    {},
    {"noise_base": 2, "aa_dim": 3, "reg_ini": 0.3, "reg_range": 0.5, "ite": 12},
    {"reg_ini": 7.5, "reg_range": 6, "chara_decay": 0.5, "lr": 0.7, "reg_size": 0.2, "reg_w": 1.5, "res": -2.5},
])
def test_guided_prediction_matches_source_restatement(kind, settings):
    ours, theirs, hook, state, _ = run_both(settings, kind)
    for i, (a, b) in enumerate(zip(ours, theirs)):
        assert torch.allclose(a, b, atol=1e-4, rtol=1e-5), (kind, i, (a - b).abs().max())
    # the source's per-step iteration counts are what NUI records
    assert [r[1] for r in hook.solver.records] == [list(map(int, c[:, 0, 0, 0])) if torch.is_tensor(c) else [0, 0]
                                                    for c in state.counts]


def test_correction_changes_the_prediction_only_inside_the_step_window():
    plain, _, hook, _, _ = run_both({"start_step": 0.25, "stop_step": 0.25}, "eps", steps=10)  # round(2.5) twice
    assert not hook.solver.records
    ours, theirs, hook, _, _ = run_both({"start_step": 0.2, "stop_step": 0.7, "reg_ini": 0.2}, "eps", steps=10)
    assert chg.constrain_step(10, 0.2) == 2 and chg.constrain_step(10, 0.7) == 7
    assert len(hook.solver.records) == 5
    for i, (a, b) in enumerate(zip(ours, theirs)):
        assert torch.allclose(a, b, atol=1e-4, rtol=1e-5), i
    for i, (a, b) in enumerate(zip(ours, plain)):
        assert torch.equal(a, b) == (i < 2 or i >= 7), i


def test_converging_immediately_keeps_plain_cfg():
    # The source raises a TypeError when the first iteration already converges; NUI records 0 iterations.
    ours, theirs, hook, _, _ = run_both({"ite": 1, "res": 300}, "eps")
    plain, _, _, _, _ = run_both({"start_step": 0.25, "stop_step": 0.25}, "eps")
    assert all(r[1] == [0, 0] for r in hook.solver.records)
    for a, b, c in zip(ours, theirs, plain):
        assert torch.equal(a, c) and torch.allclose(a, b, atol=1e-4)


def test_inpainting_channels_are_kept_and_hooks_run_once():
    unet = FakeUNet(9)
    seen = []
    unet.register_forward_pre_hook(lambda m, a, k: seen.append(1), with_kwargs=True)
    sched = SimpleNamespace(sigmas=torch.tensor([3.0, 1.0, 0.0]), step_index=0, alphas_cumprod=ALPHAS,
                            config={"prediction_type": "epsilon"})
    hook = chg.CharacteristicGuidanceHook(unet, sched, chg.normalize({}), cfg_scale=5, total_steps=2,
                                          alphas_cumprod=ALPHAS).install()
    sample = torch.randn(2, 9, 4, 4)
    positive, negative = conds(1)
    unet(sample, torch.tensor(500.3), encoder_hidden_states=torch.cat([negative, positive]))
    hook.remove()
    assert len(seen) == 1  # internal evaluations bypass the module hooks
    assert len(unet.calls) > 2
    # iterations use sigma 3's quantised timestep, the evaluation itself the sampler's timestep
    quantised = int((torch.tensor(3.0).log() - LOG_SIGMAS).abs().argmin())
    assert [t for t, _ in unet.calls[:-1]] == [quantised] * (len(unet.calls) - 1) and unet.calls[-1][0] == pytest.approx(500.3)
    for _, s in unet.calls:
        assert torch.equal(s[:, 4:], sample[:, 4:])
    assert not torch.equal(unet.calls[-1][1][:, :4], sample[:, :4])


def test_other_pre_hooks_are_seen_by_the_iterations():
    unet = FakeUNet()
    sched = SimpleNamespace(sigmas=torch.tensor([3.0, 0.0]), step_index=0, alphas_cumprod=ALPHAS,
                            config={"prediction_type": "epsilon"})

    def edit(module, args, kwargs):
        kwargs = dict(kwargs)
        kwargs["encoder_hidden_states"] = kwargs["encoder_hidden_states"] * 0 + 0.25
        return args, kwargs
    unet.register_forward_pre_hook(edit, with_kwargs=True)
    seen = []
    original = unet.forward

    def spy(*a, **k):
        seen.append(float(k["encoder_hidden_states"].mean()))
        return original(*a, **k)
    unet.forward = spy
    hook = chg.CharacteristicGuidanceHook(unet, sched, chg.normalize({"ite": 3}), cfg_scale=3, total_steps=1,
                                          alphas_cumprod=ALPHAS).install()
    positive, negative = conds(1)
    unet(torch.randn(2, 4, 4, 4), torch.tensor(400.0), encoder_hidden_states=torch.cat([negative, positive]))
    hook.remove()
    assert len(seen) > 1 and all(v == 0.25 for v in seen)


def test_odd_rows_are_rejected():
    unet = FakeUNet()
    sched = SimpleNamespace(sigmas=torch.tensor([3.0, 0.0]), step_index=0, alphas_cumprod=ALPHAS,
                            config={"prediction_type": "epsilon"})
    hook = chg.CharacteristicGuidanceHook(unet, sched, chg.normalize({}), cfg_scale=3, total_steps=1,
                                          alphas_cumprod=ALPHAS).install()
    with pytest.raises(EngineError, match="model rows"):
        unet(torch.randn(3, 4, 4, 4), torch.tensor(400.0), encoder_hidden_states=torch.zeros(3, 3, 8))
    hook.remove()


def test_linear_algebra_helpers_match_the_source():
    gen = torch.Generator().manual_seed(2)
    g = torch.randn(2, 4, 5, 5, generator=gen)
    for n in (1, 3):
        assert torch.equal(chg.split_basis(g, n), up_split_basis(g, n))
    A, B = torch.randn(3, 25, 3, generator=gen), torch.randn(3, 25, 1, generator=gen)
    reg = torch.tensor([0.5, 1.0, 2.0]).view(3, 1, 1)
    assert torch.equal(chg.ridge_projection(A, B, reg), up_proj_least_squares(A, B, reg))
    assert torch.equal(chg.pinv_solve(A, B), up_solve_least_squares(A, B))
    assert torch.allclose(chg.pinv_solve(A, B), torch.linalg.lstsq(A, B).solution, atol=1e-4)
    # the source's lstsq: NUI solves by QR like CUDA's gels driver, bit-repeatably
    big, rhs = torch.randn(2, 4097, 3, generator=gen), torch.randn(2, 4097, 1, generator=gen)
    first = chg.qr_least_squares(big, rhs)
    assert torch.allclose(first, torch.linalg.lstsq(big, rhs).solution, atol=1e-5)
    assert all(torch.equal(first, chg.qr_least_squares(big, rhs)) for _ in range(20))
    singular = big.clone()
    singular[..., 2] = 0
    assert torch.isnan(chg.qr_least_squares(singular, rhs)).any()  # so Anderson falls back to pinv_solve


def test_settings_follow_the_source():
    assert chg.normalize(None) == chg.DEFAULTS
    e = chg.effective(chg.normalize({"reg_ini": 7, "reg_range": 5, "res": -3}))
    k = 0.8898
    assert e["reg_ini"] == pytest.approx(math.exp(k * 2) / k + 5 - 1 / k)
    assert e["reg_range"] == 5 and e["res_thres"] == pytest.approx(1e-3)
    assert chg.constrain_step(20, 0.125) == 2  # Python round: 2.5 -> 2
    assert chg.constrain_step(20, 1.4) == 20 and chg.constrain_step(20, -1) == 0
    assert chg.normalize({"ite": 7.0})["ite"] == 7
    for bad, message in (({"reg_w": 0}, "Annealing Strength"), ({"ite": 0}, "Iteration"), ({"ite": 2.5}, "whole"),
                         ({"aa_dim": 0}, "AA"), ({"reg_range": 0}, "Range"), ({"reg_ini": -1}, "Strength"),
                         ({"noise_base": -1}, "Basis"), ({"lr": float("nan")}, "finite"), ({"lr": True}, "number"),
                         ({"controlnet_mode": "More"}, "ControlNet"), ({"res": 400}, "Tolerance")):
        with pytest.raises(EngineError, match=message):
            chg.normalize(bad)


# -- install ------------------------------------------------------------------------------------------------------
def make_pipe(**unet_config):
    unet = FakeUNet()
    unet.config = SimpleNamespace(**dict(dict(out_channels=4, in_channels=4, time_cond_proj_dim=None), **unet_config))
    sched = SimpleNamespace(alphas_cumprod=ALPHAS, config={"prediction_type": "epsilon"})
    return SimpleNamespace(unet=unet, scheduler=sched)


def install(pipe, hooks=None, *, pass_="base", cfg=7.0, engine=None, **flags):
    cleanups = []
    gp = SimpleNamespace(hooks=hooks or {}, steps=20)
    chg.install(engine or SimpleNamespace(kind="sd15", sdxl=False), pipe, gp, {}, None, {}, pass_=pass_,
                sched=pipe.scheduler, cfg_scale=cfg, cleanups=cleanups, **flags)
    return cleanups


def test_install_hooks_base_and_hires_and_cleans_up():
    pipe = make_pipe()
    for pass_ in ("base", "hires"):
        cleanups = install(pipe, pass_=pass_)
        assert len(cleanups) == 1 and len(pipe.unet._forward_pre_hooks) == 1
        cleanups[0]()
        assert not pipe.unet._forward_pre_hooks
    assert install(pipe, pass_="adetailer") == []
    assert install(pipe, cfg=1.0) == []
    with pytest.raises(EngineError, match="not available in the img2img_hires pass"):
        install(pipe, pass_="img2img_hires")


def test_install_rejects_what_it_cannot_reproduce():
    pipe = make_pipe()
    with pytest.raises(EngineError, match="Dynamic Thresholding, APG.*PAG.*ControlNet"):
        install(SimpleNamespace(unet=pipe.unet, scheduler=pipe.scheduler, controlnet=object()),
                {"dynamic_thresholding": {}, "apg": {}}, pag=True)
    with pytest.raises(EngineError, match="Neutral Prompt"):
        install(pipe, composed=True)
    with pytest.raises(EngineError, match="IP-Adapter in More Prompt mode"):
        chg.install(SimpleNamespace(kind="sd15"), pipe, SimpleNamespace(hooks={"ip_adapter": {}}, steps=20), {}, None,
                    {"controlnet_mode": "More Prompt"}, pass_="base", sched=pipe.scheduler, cfg_scale=7, cleanups=[])
    install(pipe, {"ip_adapter": {}})[0]()  # More ControlNet keeps the adapter on, as the source does
    with pytest.raises(EngineError, match="CFG"):
        install(pipe, cfg=0.5)
    with pytest.raises(EngineError, match="guidance embedding"):
        install(make_pipe(time_cond_proj_dim=256))
    with pytest.raises(EngineError, match="4 channel"):
        install(make_pipe(out_channels=8))
    with pytest.raises(EngineError, match="not 'flux'"):
        install(pipe, engine=SimpleNamespace(kind="flux"))
    pipe.scheduler.config = {"prediction_type": "sample"}
    with pytest.raises(EngineError, match="sample"):
        install(pipe)


def test_registered_everywhere():
    from sdfx import dryrun, extensions, hooks
    assert "characteristic_guidance" in hooks.KNOWN_HOOKS
    assert "characteristic_guidance" in extensions.EXTENSION_HOOKS
    assert "characteristic_guidance" in dryrun.FakeEngine.features(object.__new__(dryrun.FakeEngine))


# -- a real (tiny) diffusers pipeline -------------------------------------------------------------------------------
def _pipeline(scheduler):
    diffusers = pytest.importorskip("diffusers")
    torch.manual_seed(0)
    vae = diffusers.AutoencoderKL(in_channels=3, out_channels=3, latent_channels=4,
                                  down_block_types=("DownEncoderBlock2D",) * 4, up_block_types=("UpDecoderBlock2D",) * 4,
                                  block_out_channels=(4, 8, 16, 16), layers_per_block=1, norm_num_groups=4)
    unet = diffusers.UNet2DConditionModel(sample_size=8, in_channels=4, out_channels=4,
                                          down_block_types=("CrossAttnDownBlock2D",), up_block_types=("CrossAttnUpBlock2D",),
                                          block_out_channels=(8,), layers_per_block=1, norm_num_groups=4,
                                          cross_attention_dim=8, attention_head_dim=2)
    sched = getattr(diffusers, scheduler).from_config({"num_train_timesteps": 1000, "beta_start": 0.00085,
                                                       "beta_end": 0.012, "beta_schedule": "scaled_linear",
                                                       "steps_offset": 1})
    pipe = diffusers.StableDiffusionPipeline(vae=vae, text_encoder=None, tokenizer=None, unet=unet, scheduler=sched,
                                             safety_checker=None, feature_extractor=None, requires_safety_checker=False)
    pipe.set_progress_bar_config(disable=True)
    return pipe


def _generate(pipe, config, *, steps=5, spy=None):
    gen = torch.Generator().manual_seed(1)
    embeds = torch.randn(2, 77, 8, generator=gen)
    kw = dict(prompt_embeds=embeds, negative_prompt_embeds=-embeds, num_inference_steps=steps, guidance_scale=6.0,
              height=64, width=64, output_type="latent", generator=[torch.Generator().manual_seed(s) for s in (3, 4)])
    cleanups = []
    try:
        if config is not None:
            chg.install(SimpleNamespace(kind="sd15", sdxl=False), pipe, SimpleNamespace(hooks={}, steps=steps), kw,
                        None, config, pass_="base", sched=pipe.scheduler, cfg_scale=6.0, cleanups=cleanups)
            if spy is not None:
                hook = cleanups[0].__self__
                original = hook._scalars
                hook._scalars = lambda t: spy(hook, t, original(t))
        with torch.inference_mode():
            return pipe(**kw).images
    finally:
        for fn in reversed(cleanups):
            fn()


@pytest.mark.parametrize("scheduler", ["EulerDiscreteScheduler", "DPMSolverMultistepScheduler", "DDIMScheduler"])
def test_tiny_pipeline_runs_deterministically_and_changes_only_chg_steps(scheduler):
    previous = torch.get_num_threads()
    torch.set_num_threads(1)  # multi-threaded CPU kernels are not bit-repeatable; like the other tiny-pipeline tests
    try:
        _tiny_pipeline_check(scheduler)
    finally:
        torch.set_num_threads(previous)


def _tiny_pipeline_check(scheduler):
    pipe = _pipeline(scheduler)
    plain = _generate(pipe, None)
    seen = []

    def spy(hook, t, result):
        abt, c_in, _, t_iter = result
        sched = pipe.scheduler
        ac = sched.alphas_cumprod
        if hook.timesteps_path:
            assert t_iter is t and torch.equal(abt, ac[int(t)])
        else:
            index = sched.step_index if sched.step_index is not None else int((sched.timesteps == t).nonzero()[0])
            sigma = float(sched.sigmas[index])
            assert float(c_in) == pytest.approx(1 / math.sqrt(sigma * sigma + 1), rel=1e-6)
            log_sigmas = (((1 - ac) / ac) ** 0.5).log()
            assert float(abt) == float(ac[int((torch.tensor(sigma).log() - log_sigmas).abs().argmin())])
            assert float(t_iter) == int((torch.tensor(sigma).log() - log_sigmas).abs().argmin())
        seen.append(hook.timesteps_path)
        return result
    first = _generate(pipe, {"reg_ini": 0.5}, spy=spy)
    assert seen == [scheduler == "DDIMScheduler"] * 5
    assert torch.equal(first, _generate(pipe, {"reg_ini": 0.5}))  # replay is exact
    assert not torch.equal(first, plain)
    assert torch.equal(_generate(pipe, {"start_step": 0.25, "stop_step": 0.25}), plain)  # no CHG step: plain CFG
