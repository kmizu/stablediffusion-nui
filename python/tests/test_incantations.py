import math
import types

import pytest

from sdfx import incantations as inc
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")
F = torch.nn.functional


# --- independent restatements of the pinned source (v0xie/sd-webui-incantations 980d638) ---------------------------

def src_noise_level(i, N, sigma_min=0.002, sigma_max=80.0, rho=3):
    if i == 0:
        return sigma_max
    if i >= N:
        return 0.0
    a, b = sigma_max ** (1 / rho), sigma_min ** (1 / rho)
    return (a + (i / (N - 1)) * (b - a)) ** rho


def src_find_closest_index(noise_level, N, sigma_min=0.002, sigma_max=80.0, tol=1e-6):
    if noise_level <= sigma_min:
        return N
    if noise_level >= sigma_max:
        return 0
    low, high = 0, N - 1
    while low <= high:
        mid = (low + high) // 2
        mid_nl = src_noise_level(mid, N)
        if abs(mid_nl - noise_level) < tol:
            return mid
        elif mid_nl < noise_level:
            high = mid - 1
        else:
            low = mid + 1
    return low if abs(src_noise_level(low, N) - noise_level) < abs(src_noise_level(high, N) - noise_level) else high


def src_cfg_scheduler(schedule, step, max_steps, w0):
    linear = lambda: w0 * 2 * (1 - step / max_steps)  # noqa: E731
    cosine = lambda: w0 * (1 + math.cos(math.pi * step / max_steps))  # noqa: E731
    pcs = lambda s: w0 * ((1 - math.cos(math.pi * ((max_steps - step) / max_steps) ** s)) / 2.0)  # noqa: E731
    table = {
        'Constant': lambda: w0, 'Linear': linear,
        'Clamp-Linear (c=4.0)': lambda: max(4.0, linear()), 'Clamp-Linear (c=2.0)': lambda: max(2.0, linear()),
        'Clamp-Linear (c=1.0)': lambda: max(1.0, linear()), 'Inverse-Linear': lambda: w0 * 2 * (step / max_steps),
        'PCS (s=0.01)': lambda: pcs(0.01), 'PCS (s=0.1)': lambda: pcs(0.1), 'PCS (s=1.0)': lambda: pcs(1.0),
        'PCS (s=2.0)': lambda: pcs(2.0), 'PCS (s=4.0)': lambda: pcs(4.0),
        'Clamp-Cosine (c=4.0)': lambda: max(4.0, cosine()), 'Clamp-Cosine (c=2.0)': lambda: max(2.0, cosine()),
        'Clamp-Cosine (c=1.0)': lambda: max(1.0, cosine()), 'Cosine': cosine,
        'Sine': lambda: w0 * (math.sin((math.pi * step / max_steps) - (math.pi / 2)) + 1),
        'Interval': lambda: w0 if 0.25 <= step <= 5.42 else 1.0,
    }
    return table[schedule]()


def src_scheduled_cfg(schedule, low, high, step, steps, w0):
    """PAG on_cfg_denoiser + combiner: the CFG scale the combiner uses with the scheduler enabled."""
    low = src_noise_level(src_find_closest_index(low, steps), steps)
    high = src_noise_level(src_find_closest_index(high, steps), steps)
    value = w0
    if schedule != 'Constant':
        begin, end = (low, high) if low <= high else (high, low)
        level = src_noise_level(step, steps)
        scheduled = src_cfg_scheduler(schedule, step, steps, w0)
        value = scheduled if begin <= level <= end else 1.0
    return value


def src_gaussian_blur_2d(img, kernel_size, sigma):
    height = img.shape[-1]
    kernel_size = min(kernel_size, height - (height % 2 - 1))
    ksize_half = (kernel_size - 1) * 0.5
    x = torch.linspace(-ksize_half, ksize_half, steps=kernel_size)
    pdf = torch.exp(-0.5 * (x / sigma).pow(2))
    x_kernel = (pdf / pdf.sum()).to(device=img.device, dtype=img.dtype)
    kernel2d = torch.mm(x_kernel[:, None], x_kernel[None, :])
    kernel2d = kernel2d.expand(img.shape[-3], 1, kernel2d.shape[0], kernel2d.shape[1])
    padding = [kernel_size // 2] * 4
    return F.conv2d(F.pad(img, padding, mode="reflect"), kernel2d, groups=img.shape[-3])


def src_seg_to_q_hook(inp, output, heads, height, width, seg_blur_sigma):
    """seg_to_q_hook on an A1111 [cond, uncond] batch: the second half is blurred."""
    batch_size, seq_len, inner_dim = inp.shape
    head_dim = inner_dim // heads
    downscale_h = int((seq_len * (height / width)) ** 0.5)
    downscale_w = seq_len // downscale_h
    is_inf_blur = seg_blur_sigma > 10.5
    blur_sigma_exp = 2 ** seg_blur_sigma
    kernel_size = math.ceil(6 * blur_sigma_exp) + 1 - math.ceil(6 * blur_sigma_exp) % 2
    q_uncond, q = output.chunk(2, dim=0)
    q = q.view(batch_size // 2, -1, heads, head_dim).transpose(1, 2)
    q = q.permute(0, 1, 3, 2).reshape(batch_size // 2 * heads, head_dim, downscale_h, downscale_w)
    if is_inf_blur:
        q[:] = q.mean(dim=(-2, -1), keepdim=True)
    else:
        q = src_gaussian_blur_2d(q, kernel_size, blur_sigma_exp)
    q = q.reshape(batch_size // 2, heads, head_dim, downscale_h * downscale_w)
    q = q.view(batch_size // 2, heads * head_dim, seq_len).transpose(1, 2)
    return torch.cat((q_uncond, q), dim=0)


def src_tv_blur(img):
    """torchvision GaussianBlur(kernel_size=3, sigma=1) as in torchvision 0.16 (float32 kernel, img dtype conv)."""
    x = torch.linspace(-1.0, 1.0, steps=3)
    pdf = torch.exp(-0.5 * (x / 1.0).pow(2))
    k1 = (pdf / pdf.sum()).to(img.dtype)
    k2 = torch.mm(k1[:, None], k1[None, :])
    squeeze = img.dim() == 3
    x = img.unsqueeze(0) if squeeze else img
    k2 = k2.expand(x.shape[-3], 1, 3, 3)
    out = F.conv2d(F.pad(x, [1, 1, 1, 1], mode="reflect"), k2, groups=x.shape[-3])
    return out.squeeze(0) if squeeze else out


class SrcGaussianSmoothing:
    def __init__(self):
        kernel = 1
        meshgrids = torch.meshgrid([torch.arange(3, dtype=torch.float32) for _ in range(2)], indexing="ij")
        for size, std, mgrid in zip([3, 3], [0.5, 0.5], meshgrids):
            mean = (size - 1) / 2
            kernel *= 1 / (std * math.sqrt(2 * math.pi)) * torch.exp(-((mgrid - mean) / (2 * std)) ** 2)
        kernel = kernel / torch.sum(kernel)
        self.weight = kernel.view(1, 1, 3, 3)

    def __call__(self, x):
        return F.conv2d(x, weight=self.weight.to(x.dtype), groups=1)


def src_prepare_attn_map(x, heads):
    b, s, d = x.shape
    x = x.reshape(b, s, heads, d // heads).permute(0, 2, 1, 3).reshape(b * heads, s, d // heads)
    einops = pytest.importorskip("einops")
    x = einops.rearrange(x, '(b h) s t -> b h s t', h=heads).mean(1)
    return torch.stack([x[0], x[0]], dim=0)


def src_attention_scores(q, k, dtype):
    p = (q @ k.transpose(-1, -2)).to(torch.float32)
    p -= torch.max(p)
    torch.exp(p, out=p)
    p /= p.sum(dim=-1, keepdim=True, dtype=torch.float32)
    return p.to(dtype)


def src_get_mask(modules, r, latent_size, height, width):
    """get_mask over [(name, q_full_batch, k_full_batch or None, heads)] in A1111 module order."""
    from einops import rearrange
    store = {}
    sizes = set()
    for name, q, k, heads in modules:
        kind = 'cross' if 'attn2' in name else 'self'
        k = q if k is None else k
        qm, km = src_prepare_attn_map(q, heads), src_prepare_attn_map(k, heads)
        sizes.add(qm.size(1))
        store.setdefault(f"r{qm.size(1)}_{kind}", []).append(src_attention_scores(qm, km, qm.dtype))
    sizes = sorted(sizes)
    smoothing = SrcGaussianSmoothing()
    attnmap_r, new_ca, new_fore, a_n = 0, 0, 0, 0
    curr_r = sizes.pop(0)
    while curr_r is not None and attnmap_r < r:
        sa = torch.stack(store[f"r{curr_r}_self"], dim=1)
        ca = torch.stack(store[f"r{curr_r}_cross"], dim=1)
        attn_num = sa.size(1)
        sa = rearrange(sa, 'b n h w -> (b n) h w')
        ca = rearrange(ca, 'b n h w -> (b n) h w')
        curr = 0
        curr += sa
        ssgc_sa = curr
        for _ in range(1, r):
            curr @= sa
            ssgc_sa += curr
        ssgc_sa /= r
        ca = ssgc_sa @ ca
        hw = ca.size(1)
        downscale_h = round((hw * (height / width)) ** 0.5)
        ca = rearrange(ca, 'b (h w) c -> b c h w', h=downscale_h)
        ca = F.interpolate(ca, scale_factor=[latent_size[0] / ca.shape[-2], latent_size[1] / ca.shape[-1]],
                           mode='bilinear')
        channel = ca.size(1)
        ca = rearrange(ca, ' b c h w -> (b c) h w').unsqueeze(1)
        ca = F.pad(ca, (1, 1, 1, 1), mode='reflect')
        ca = smoothing(ca.float()).squeeze(1)
        ca = rearrange(ca, ' (b c) h w -> b c h w', c=channel)
        ca_norm = ca / (ca.mean(dim=[2, 3], keepdim=True) + torch.finfo(ca.dtype).eps)
        new_ca += rearrange(ca_norm, '(b n) c h w -> b n c h w', n=attn_num).sum(1)
        fore_ca = torch.stack([ca[:, 0], ca[:, 1:].sum(dim=1)], dim=1)
        froe_ca_norm = fore_ca / fore_ca.mean(dim=[2, 3], keepdim=True)
        new_fore += rearrange(froe_ca_norm, '(b n) c h w -> b n c h w', n=attn_num).sum(1)
        a_n += attn_num
        curr_r = sizes.pop(0) if len(sizes) > 0 else None
        attnmap_r += 1
    new_ca = new_ca / a_n
    new_fore = new_fore / a_n
    _, new_ca = new_ca.chunk(2, dim=0)
    fore_ca, _ = new_fore.chunk(2, dim=0)
    max_ca, _ = torch.max(new_ca[:, :], dim=1)
    ca_mask = (new_ca == max_ca.unsqueeze(1)).float()
    max_fore, _ = torch.max(fore_ca[:, :], dim=1)
    fore_mask = 1.0 - (fore_ca == max_fore.unsqueeze(1)).float()[:, :1]
    return ca_mask, fore_mask


def src_scfg_rate(model_delta, cfg_scale, mask_t, mask_fore, scale, rmin, rmax, clamp):
    model_delta = model_delta.unsqueeze(0)
    norm = model_delta.norm(dim=1, keepdim=True)
    eps = lambda dtype: torch.finfo(dtype).eps  # noqa: E731
    if mask_t.shape[2:] != norm.shape[2:]:
        mask_t = F.interpolate(mask_t, size=norm.shape[2:], mode='bilinear')
    if mask_fore.shape[-2] != norm.shape[-2]:
        mask_fore = F.interpolate(mask_fore, size=norm.shape[2:], mode='bilinear')
    delta_mask_norms = (norm * mask_t).sum([2, 3]) / (mask_t.sum([2, 3]) + eps(mask_t.dtype))
    fore_norms = (norm * mask_fore).sum([2, 3]) / (mask_fore.sum([2, 3]) + eps(mask_fore.dtype))
    tmp_mask = (mask_t.sum([2, 3]) > 0).float()
    rate = fore_norms * tmp_mask / (delta_mask_norms + eps(delta_mask_norms.dtype))
    rate = (rate.unsqueeze(-1).unsqueeze(-1) * mask_t).sum(dim=1, keepdim=True)
    rate = torch.clamp(rate * scale, min=rmin, max=rmax)
    if clamp > 0:
        rate = torch.clamp_max(rate, clamp / cfg_scale)
    rate = SrcGaussianSmoothing()(F.pad(rate, (1, 1, 1, 1), mode='reflect'))
    return rate.squeeze(0)


def src_combine(x_out, batch, cond_scale, *, pag=None, pag_x_out=None, rate=None):
    """new_combine_denoised of cfg_combiner on an A1111 x_out [cond B, uncond B] (one cond per image)."""
    conds_list = [[(i, 1.0)] for i in range(batch)]
    denoised_uncond = x_out[-batch:]
    denoised = torch.clone(denoised_uncond)
    cfg_scale = cond_scale
    run_pag, use_saliency_map, pag_scale = False, False, None
    if pag is not None:
        if pag["cfg_interval_enable"]:
            cfg_scale = pag["scheduled"]
        pag_scale = pag["pag_scale"]
        if pag["pag_active"] and pag["start"] <= pag["step"] <= pag["end"] and pag_scale > 0:
            run_pag = True
        use_saliency_map = pag["sanf"]
    for i, conds in enumerate(conds_list):
        for cond_index, weight in conds:
            model_delta = x_out[cond_index] - denoised_uncond[i]
            r = rate(model_delta, cfg_scale) if rate is not None else 1.0
            cfg_x = model_delta * r * (weight * cfg_scale)
            if not use_saliency_map or not run_pag:
                denoised[i] += cfg_x
            if run_pag:
                pag_x = (x_out[cond_index] - pag_x_out[i]) * (weight * pag_scale)
                if not use_saliency_map:
                    denoised[i] += pag_x
                else:
                    omega_rt, omega_rs = src_tv_blur(torch.abs(cfg_x)), src_tv_blur(torch.abs(pag_x))
                    m = torch.stack([torch.softmax(omega_rt, dim=0), torch.softmax(omega_rs, dim=0)], dim=0)
                    _, argmax_indices = torch.max(m, dim=0)
                    m1 = torch.where(argmax_indices == 0, 1, 0)
                    denoised[i] += cfg_x * m1 + pag_x * (1 - m1)
    return denoised


def src_cbs(f, percentile, gamma, alpha, tokens=None, token_count=77):
    if alpha == 0:
        return f
    n, d = f.shape
    min_idx, max_idx = 1, min(token_count + 1, n)
    if tokens is None or tokens == []:
        token_indices = list(range(min_idx, max_idx))
    else:
        token_indices = [x + 1 for x in tokens if x >= 0 and x < n]
    f_tilde = f.detach().clone()
    for c in token_indices:
        if c < 0 or c >= n:
            continue
        Sc = f[c] * f
        k, e = 10, 2.718281
        pct = min(1 / (1 + 1e-10), max(1e-16, 1 - e ** (-k * percentile)))
        tau = torch.quantile(Sc, pct)
        Sc_tilde = Sc * (Sc > tau)
        Sc_tilde /= Sc_tilde.max()
        window = torch.zeros(n, dtype=Sc_tilde.dtype)
        window[max(min_idx, c - gamma):min(max_idx, c + gamma + 1)] = 1
        Sc_tilde *= window.unsqueeze(1)
        f_tilde[c] = (1 - alpha) * f[c] + alpha * torch.sum(Sc_tilde * f, dim=0)
    return f_tilde


class SrcCtnms:
    """cross_token_non_maximum_suppression with its module state, on one attn2 module."""

    def __init__(self, alpha, width, height, ema_factor, step_start, step_end, tokens, token_count):
        self.alpha, self.width, self.height = alpha, width, height
        self.ema_factor, self.start, self.end = ema_factor, step_start, step_end
        self.tokens = torch.tensor(tokens).to(dtype=torch.int64)
        self.token_count = token_count
        self.step, self.ema = -1, None

    def __call__(self, output, to_v_map, context_len=77):
        from einops import rearrange
        self.step += 1
        if context_len % 77 != 0:
            return output
        if (self.step > self.end and self.end > 0) or self.step < self.start:
            return output
        batch_size, sequence_length, inner_dim = output.shape
        factor = math.isqrt(self.width * self.height // sequence_length)
        dw, dh = self.width // factor, self.height // factor
        if dw * dh != sequence_length:
            return output
        to_v = to_v_map.detach().clone()
        to_v_inner_dim = to_v.size(-2)
        to_v = (to_v @ output.transpose(1, 2)).transpose(1, 2)
        to_v_attention_map = to_v.view(batch_size, dh, dw, to_v_inner_dim)
        attention_map = output.view(batch_size, dh, dw, inner_dim)
        selected = torch.arange(1, self.token_count) if len(self.tokens) == 0 else self.tokens
        if self.ema is None:
            self.ema = output.detach().clone()
        AC = to_v_attention_map[:, :, :, selected].permute(0, 3, 1, 2)
        AC = src_tv_blur(AC).permute(0, 2, 3, 1)
        M = torch.argmax(AC, dim=-1)
        one_hot = F.one_hot(M, num_classes=to_v_attention_map.size(-1)).to(dtype=output.dtype)
        one_hot = rearrange(one_hot, 'b h w c -> b (h w) c') @ to_v_map
        one_hot = rearrange(one_hot, 'b (h w) c -> b h w c', h=dh, w=dw)
        suppressed = (one_hot * attention_map).view(batch_size, sequence_length, inner_dim)
        if self.ema_factor > 0:
            ef = self.ema_factor / (1 + self.step)
            self.ema = ef * self.ema + (1 - ef) * suppressed
            return (1 - self.alpha) * output + self.alpha * self.ema
        return (1 - self.alpha) * output + self.alpha * suppressed


def src_token_count(tokenized, parsed, comma, chunk_length=75, backtrack=20):
    """A1111 tokenize_line token_count."""
    state = {"count": 0, "tokens": [], "last_comma": -1, "chunks": 0}

    def next_chunk(is_last=False):
        state["count"] += len(state["tokens"]) if is_last else chunk_length
        state["tokens"], state["last_comma"] = [], -1
        state["chunks"] += 1

    for tokens, (text, weight) in zip(tokenized, parsed):
        if text == 'BREAK' and weight == -1:
            next_chunk()
            continue
        for token in tokens:
            if token == comma:
                state["last_comma"] = len(state["tokens"])
            elif backtrack != 0 and len(state["tokens"]) == chunk_length and state["last_comma"] != -1 \
                    and len(state["tokens"]) - state["last_comma"] <= backtrack:
                break_location = state["last_comma"] + 1
                reloc = state["tokens"][break_location:]
                state["tokens"] = state["tokens"][:break_location]
                next_chunk()
                state["tokens"] = reloc
            if len(state["tokens"]) == chunk_length:
                next_chunk()
            state["tokens"].append(token)
    if state["tokens"] or not state["chunks"]:
        next_chunk(is_last=True)
    return state["count"]


# --- maths ----------------------------------------------------------------------------------------------------------

def test_noise_ladder_and_step_alignment_match_source():
    for n in (1, 2, 3, 7, 20, 30):
        for i in range(0, n + 2):
            assert inc.noise_level(i, n) == src_noise_level(i, n)
        for level in (0.0, 0.001, 0.002, 0.28, 1.0, 5.42, 17.3, 79.9, 80.0, 100.0):
            assert inc.closest_index(level, n) == src_find_closest_index(level, n), (n, level)


def test_every_schedule_matches_source():
    assert len(inc.SCHEDULES) == 17 and inc.SCHEDULES[0] == "Constant"
    for name in inc.SCHEDULES:
        for steps in (1, 4, 20):
            for step in range(steps + 1):
                assert inc.schedule_value(name, step, steps, 7.5) == src_cfg_scheduler(name, step, steps, 7.5), name
    with pytest.raises(EngineError):
        inc.schedule_value("V-Shape", 1, 10, 7.0)


@pytest.mark.parametrize("schedule", inc.SCHEDULES)
@pytest.mark.parametrize("low,high", [(0.0, 100.0), (0.28, 5.42), (5.42, 0.28), (1.0, 1.0)])
def test_scheduled_cfg_matches_source_callback(schedule, low, high):
    c = dict(inc.DEFAULTS, cfg_interval_enable=True, cfg_interval_schedule=schedule, cfg_interval_low=low,
             cfg_interval_high=high)
    interval = inc.CfgInterval(c, 20, 6.0)
    for step in range(21):
        assert interval.value(step) == src_scheduled_cfg(schedule, low, high, step, 20, 6.0), step
    # Constant keeps the CFG everywhere, even outside the interval (source quirk)
    if schedule == "Constant":
        assert {interval.value(s) for s in range(21)} == {6.0}


@pytest.mark.parametrize("sigma", [0.5, 1.0, 2.5, 10.5, 11.0])
@pytest.mark.parametrize("size", [(64, 64, 16), (512, 768, 96), (768, 512, 96)])
def test_seg_blurs_the_unconditional_half_like_the_source(sigma, size):
    width, height, seq = size
    heads, inner, batch = 2, 8, 2
    g = torch.Generator().manual_seed(3)
    cond = torch.randn(batch, seq, inner, generator=g)
    uncond = torch.randn(batch, seq, inner, generator=g)
    hidden = torch.randn(2 * batch, seq, inner, generator=g)
    ref = src_seg_to_q_hook(hidden, torch.cat([cond, uncond]), heads, height, width, sigma)
    mine = inc.seg_query(uncond, heads, height, width, sigma)
    assert torch.allclose(mine, ref[batch:], atol=1e-6)
    assert torch.equal(ref[:batch], cond)


def test_gaussian_blur_matches_torchvision():
    tv = pytest.importorskip("torchvision")
    x = torch.randn(2, 5, 7, 9, generator=torch.Generator().manual_seed(1))
    assert torch.allclose(inc.gaussian_blur(x), tv.transforms.GaussianBlur(kernel_size=3, sigma=1)(x), atol=1e-6)
    assert torch.allclose(inc.gaussian_blur(x[0]), src_tv_blur(x[0]), atol=1e-7)


def _scfg_modules(seed=0, batch=2, heads=2, dim=8, tokens=77, sizes=(16, 64), per_size=2):
    g = torch.Generator().manual_seed(seed)
    mods = []
    for s in sizes:
        for j in range(per_size):
            mods.append((f"down_{s}_{j}_attn1", torch.randn(batch, s, dim, generator=g), None, heads))
            q = torch.randn(batch, s, dim, generator=g)
            k = torch.randn(batch, tokens, dim, generator=g)
            mods.append((f"down_{s}_{j}_attn2", q, k, heads))
    return mods


@pytest.mark.parametrize("r,latent", [(1, (8, 8)), (2, (8, 8)), (4, (8, 8)), (8, (16, 16)), (4, (8, 12))])
def test_scfg_masks_and_rate_match_source(r, latent):
    pytest.importorskip("einops")
    width, height = latent[1] * 8, latent[0] * 8
    mods = _scfg_modules(sizes=(latent[0] * latent[1] // 4, latent[0] * latent[1]))
    ref_ca, ref_fore = src_get_mask(mods, r, [latent[0] // r, latent[1] // r], height, width)
    ref_mask_t = F.interpolate(ref_ca, scale_factor=r, mode='nearest')
    ref_mask_fore = F.interpolate(ref_fore, scale_factor=r, mode='nearest')
    # NUI keeps only the first image's conditional row (A1111 batch element 0)
    entries = [("cross" if name.endswith("attn2") else "self", q[:1], None if k is None else k[:1], h)
               for name, q, k, h in mods]
    mask_t, mask_fore = inc.scfg_masks(entries, r, latent, height, width)
    assert torch.equal(mask_t, ref_mask_t) and torch.equal(mask_fore, ref_mask_fore)
    delta = torch.randn(4, *latent, generator=torch.Generator().manual_seed(9))
    for scale, clamp in ((1.0, 0.0), (2.5, 9.0), (0.3, 0.0)):
        c = dict(inc.DEFAULTS, scfg_scale=scale, scfg_rate_min=0.8, scfg_rate_max=3.0, scfg_rate_clamp=clamp)
        mine = inc.scfg_rate(delta, 7.0, (mask_t, mask_fore), c)
        ref = src_scfg_rate(delta, 7.0, ref_mask_t, ref_mask_fore, scale, 0.8, 3.0, clamp)
        assert torch.allclose(mine, ref, atol=1e-6)


def test_scfg_masks_rescale_to_latents_not_divisible_by_r():
    pytest.importorskip("einops")
    mods = _scfg_modules(sizes=(16, 64))
    entries = [("cross" if n.endswith("attn2") else "self", q[:1], None if k is None else k[:1], h) for n, q, k, h in mods]
    masks = inc.scfg_masks(entries, 3, (8, 8), 64, 64)
    assert masks[0].shape[-2:] == (6, 6)
    delta = torch.randn(4, 8, 8)
    ref = src_scfg_rate(delta, 5.0, *masks, 1.0, 0.8, 3.0, 0.0)
    assert torch.allclose(inc.scfg_rate(delta, 5.0, masks, dict(inc.DEFAULTS)), ref, atol=1e-6)


@pytest.mark.parametrize("case", ["cfg", "scheduled", "pag", "sanf", "sanf_off_range", "scfg_pag"])
def test_combine_matches_source_combiner(case):
    g = torch.Generator().manual_seed(4)
    batch = 2
    x0c, x0u, x0p = (torch.randn(batch, 4, 8, 8, generator=g) for _ in range(3))
    a1111 = torch.cat([x0c, x0u])
    pag = dict(pag_active=case != "cfg", pag_scale=3.0, start=0, end=150, step=5, sanf=case.startswith("sanf"),
               cfg_interval_enable=case == "scheduled", scheduled=2.5)
    if case == "sanf_off_range":
        pag["end"] = 4
    rate = None
    if case == "scfg_pag":
        mask_t = (torch.rand(1, 77, 8, 8, generator=g) > 0.9).float()
        mask_fore = (torch.rand(1, 1, 8, 8, generator=g) > 0.5).float()
        c = dict(inc.DEFAULTS, scfg_scale=1.5)
        rate = lambda d, s: inc.scfg_rate(d, s, (mask_t, mask_fore), c)  # noqa: E731
    ref = src_combine(a1111, batch, 7.0, pag=pag, pag_x_out=x0p, rate=rate)
    cfg = 2.5 if case == "scheduled" else 7.0
    run = pag["pag_active"] and pag["start"] <= 5 <= pag["end"]
    mine = inc.combine(x0u, x0c, x0p, cfg, pag_scale=3.0, run_pag=run, use_sanf=pag["sanf"], rate=rate)
    assert torch.allclose(mine, ref, atol=1e-6)


@pytest.mark.parametrize("tokens", [None, [], [0, 2], [70, 76, 90]])
@pytest.mark.parametrize("threshold,window,alpha", [(0.5, 2, 0.25), (0.05, 0, 1.0), (0.99, 10, 1.7), (0.5, 2, 0.0)])
def test_correction_by_similarities_matches_source(tokens, threshold, window, alpha):
    f = torch.randn(77, 16, generator=torch.Generator().manual_seed(2))
    assert torch.allclose(inc.cbs(f, threshold, window, alpha, tokens), src_cbs(f, threshold, window, alpha, tokens),
                          atol=1e-6)


@pytest.mark.parametrize("ema", [0.0, 2.0])
@pytest.mark.parametrize("tokens,count", [([], 6), ([2, 5], 9)])
def test_ctnms_matches_source_over_calls(ema, tokens, count):
    pytest.importorskip("einops")
    g = torch.Generator().manual_seed(6)
    ref = SrcCtnms(0.3, 64, 64, ema, 1, 4, tokens, count)
    state = inc.BatchState(64, 64, 20, 7.0, count, tokens)
    c = dict(inc.DEFAULTS, t2i0_active=True, t2i0_ctnms_alpha=0.3, t2i0_ema_factor=ema, t2i0_step_start=1,
             t2i0_step_end=4)
    hook = inc.IncantationsHook.__new__(inc.IncantationsHook)
    hook.state, hook.c, hook.to_v = state, c, {}
    fn = hook._ctnms("x.attn2")
    for _ in range(7):
        out = torch.randn(4, 64, 8, generator=g)
        to_v = torch.randn(4, 77, 8, generator=g)
        hook.to_v["x.attn2"] = to_v
        got = fn(None, (out,), {"encoder_hidden_states": torch.zeros(4, 77, 8)}, out)
        want = ref(out, to_v)
        assert torch.allclose(out if got is None else got, want, atol=1e-6)
    assert state.calls["x.attn2"] == 6


def test_ctnms_rejects_one_token_prompts_and_ema_size_changes():
    state = inc.BatchState(64, 64, 20, 7.0, 1, [])
    hook = inc.IncantationsHook.__new__(inc.IncantationsHook)
    hook.state, hook.c, hook.to_v = state, dict(inc.DEFAULTS, t2i0_step_start=0), {"m": torch.randn(1, 77, 8)}
    fn = hook._ctnms("m")
    with pytest.raises(EngineError, match="two tokens"):
        fn(None, (), {"encoder_hidden_states": torch.zeros(1, 77, 8)}, torch.randn(1, 64, 8))
    state.token_count = 5
    hook.c = dict(inc.DEFAULTS, t2i0_step_start=0, t2i0_ema_factor=1.0)
    fn(None, (), {"encoder_hidden_states": torch.zeros(1, 77, 8)}, torch.randn(1, 64, 8))
    hook.to_v["m"] = torch.randn(1, 77, 8)
    with pytest.raises(EngineError, match="EMA"):
        fn(None, (), {"encoder_hidden_states": torch.zeros(1, 77, 8)}, torch.randn(1, 16, 8))


def test_token_count_follows_a1111_tokenize_line():
    from sdfx.prompt import parse_prompt_attention
    comma = 99

    def tokenize(texts):
        out = []
        for t in texts:
            ids = []
            for word in t.replace(",", " , ").split():
                ids += [comma] if word == "," else [len(word)] * (1 + len(word) // 6)
            out.append(ids)
        return out
    texts = ["", "a cat", "a cat BREAK a dog", "a cat BREAK", "(red:1.2) dog, [blue] sky",
             ", ".join(["masterpiece quality"] * 30), " ".join(["word"] * 74) + ", tail end words here",
             "x " * 150]
    for text in texts:
        parsed = parse_prompt_attention(text)
        want = src_token_count(tokenize([p for p, _ in parsed]), parsed, comma)
        assert inc.a1111_token_count(tokenize, comma, text) == want, text


def test_tokens_field_parsing_and_normalize():
    assert inc.parse_tokens("", 5) == []
    assert inc.parse_tokens("0, 3,4 ,9,-1", 5) == [1, 4, 5]
    with pytest.raises(EngineError, match="comma separated"):
        inc.parse_tokens("1,,2", 5)
    c = inc.normalize({"seg_active": "true", "seg_start_step": 2.0, "cfg_interval_schedule": "PCS (s=1.0)"})
    assert c["seg_active"] is True and c["seg_start_step"] == 2 and isinstance(c["seg_start_step"], int)
    for bad in ({"seg_start_step": 1.5}, {"cfg_interval_schedule": "V-Shape"}, {"scfg_r": 0}, {"pag_scale": "nan"},
                {"t2i0_window_size": -1}):
        with pytest.raises(EngineError):
            inc.normalize(bad)
    f = inc.features(inc.normalize({"seg_active": True, "seg_blur_sigma": 0}))
    assert not any(f.values())  # SEG Blur Sigma 0 skips SEG like the source


def test_job_state_continues_into_hires_copies_and_restarts_per_batch():
    job = inc.Job()
    made = []

    def make():
        made.append(1)
        return inc.BatchState(64, 64, 20, 7.0, 3, [])
    st = job.state_for("base", make)
    st.calls["m"] = 9
    job.finish("base")
    h1 = job.state_for("hires", make)
    assert h1.calls == {"m": 9} and h1 is not st
    h1.calls["m"] = 30
    assert job.state_for("hires", make).calls == {"m": 9}  # each hires repeat starts from the base state
    assert job.state_for("adetailer", make).calls == {}
    assert job.state_for("base", make).calls == {} and len(made) == 3


# --- hooks on a small UNet --------------------------------------------------------------------------------------------

def _unet():
    diffusers = pytest.importorskip("diffusers")
    torch.manual_seed(0)
    return diffusers.UNet2DConditionModel(
        sample_size=8, in_channels=4, out_channels=4, block_out_channels=(8, 8), layers_per_block=1,
        down_block_types=("CrossAttnDownBlock2D", "CrossAttnDownBlock2D"),
        up_block_types=("CrossAttnUpBlock2D", "CrossAttnUpBlock2D"), norm_num_groups=4, cross_attention_dim=8,
        attention_head_dim=2).eval()


def _hook(unet, batch=1, guided=True, cfg=5.0, **c):
    conf = inc.normalize(c)
    state = inc.BatchState(64, 64, 10, cfg, 6, [])
    ac = torch.linspace(0.999, 0.01, 1000)
    return inc.IncantationsHook(unet, conf, state, batch=batch, guided=guided, cfg_scale=cfg, alphas_cumprod=ac,
                                prediction="epsilon").install()


def _inputs(batch=1, seed=1):
    g = torch.Generator().manual_seed(seed)
    z = torch.randn(batch, 4, 8, 8, generator=g)
    neg = torch.randn(batch, 77, 8, generator=g)
    pos = torch.randn(batch, 77, 8, generator=g)
    return torch.cat([z, z]), torch.cat([neg, pos])


def test_steps_lag_the_evaluations_like_a1111_sampling_step():
    unet = _unet()
    hook = _hook(unet, pag_active=True)
    seen = []
    x, ehs = _inputs()
    with torch.no_grad():
        for t in (999, 900, 900, 800, 700):
            unet(x, torch.tensor(t), encoder_hidden_states=ehs)
            seen.append(hook.step)
    hook.remove()
    assert seen == [0, 0, 0, 1, 2]


def test_seg_changes_only_the_unconditional_rows():
    unet = _unet()
    x, ehs = _inputs(batch=2)
    with torch.no_grad():
        base = unet(x, torch.tensor(500), encoder_hidden_states=ehs).sample
        hook = _hook(unet, batch=2, seg_active=True, seg_blur_sigma=1.0)
        seg = unet(x, torch.tensor(500), encoder_hidden_states=ehs).sample
        hook.remove()
        again = unet(x, torch.tensor(500), encoder_hidden_states=ehs).sample
    assert torch.equal(seg[2:], base[2:]) and not torch.allclose(seg[:2], base[:2])
    assert torch.equal(again, base)


def _perturbed_reference(unet, x, t, ehs):
    """Cond rows evaluated with the first middle self-attention returning to_v(x) (the source's identity hook)."""
    attn = dict(unet.named_modules())[inc.PAG_LAYER]
    handle = attn.register_forward_hook(lambda m, a, o: m.to_v(a[0]))
    try:
        return unet(x, t, encoder_hidden_states=ehs).sample
    finally:
        handle.remove()


@pytest.mark.parametrize("sanf", [False, True])
def test_pag_adds_the_identity_attention_guidance_in_denoised_space(sanf):
    unet = _unet()
    x, ehs = _inputs()
    t = torch.tensor(600)
    a = torch.linspace(0.999, 0.01, 1000)[600].item()
    sa, s1a = math.sqrt(a), math.sqrt(1 - a)
    with torch.no_grad():
        base = unet(x, t, encoder_hidden_states=ehs).sample
        pert = _perturbed_reference(unet, x[1:], t, ehs[1:])
        hook = _hook(unet, pag_active=True, pag_scale=2.0, pag_sanf=sanf)
        out = unet(x, t, encoder_hidden_states=ehs).sample
        hook.remove()
    u, c = base[:1], base[1:]
    z = x[:1]
    x0 = lambda e: (z - s1a * e) / sa  # noqa: E731
    want = inc.combine(x0(u), x0(c), x0(pert), 5.0, pag_scale=2.0, run_pag=True, use_sanf=sanf)
    got = u + 5.0 * (out[1:] - u)  # what diffusers' CFG makes of the rewritten cond half
    assert torch.equal(out[:1], u)
    assert torch.allclose((z - sa * want) / s1a, got, atol=1e-4)


def test_inactive_steps_and_zero_scale_leave_the_output_untouched():
    unet = _unet()
    x, ehs = _inputs()
    with torch.no_grad():
        base = unet(x, torch.tensor(500), encoder_hidden_states=ehs).sample
        for conf in ({"pag_active": True, "pag_scale": 0.0}, {"pag_active": True, "pag_scale": 3.0, "pag_start_step": 5},
                     {"cfg_interval_enable": True, "cfg_interval_schedule": "Constant"},
                     {"scfg_active": True, "scfg_scale": 0.0}):
            hook = _hook(unet, **conf)
            out = unet(x, torch.tensor(500), encoder_hidden_states=ehs).sample
            hook.remove()
            assert torch.equal(out, base), conf
    assert not unet._forward_hooks and not unet._forward_pre_hooks


def test_scheduler_outside_the_interval_turns_cfg_off():
    unet = _unet()
    x, ehs = _inputs()
    with torch.no_grad():
        base = unet(x, torch.tensor(500), encoder_hidden_states=ehs).sample
        # the step-0 noise level (80) is outside (0.28, 5.42], so CFG becomes 1.0: the guided result is the cond row
        hook = _hook(unet, cfg_interval_enable=True, cfg_interval_schedule="Linear", cfg_interval_low=0.28,
                     cfg_interval_high=5.42)
        out = unet(x, torch.tensor(500), encoder_hidden_states=ehs).sample
        hook.remove()
    guided = base[:1] + 5.0 * (out[1:] - base[:1])
    assert torch.allclose(guided, base[1:], atol=1e-4)


def test_scfg_hook_masks_come_from_the_first_conditional_row():
    pytest.importorskip("einops")
    unet = _unet()
    x, ehs = _inputs(batch=2)
    hook = _hook(unet, batch=2, scfg_active=True, scfg_r=2)
    captured = {}
    real = inc.scfg_masks

    def spy(entries, *a, **k):
        captured["entries"] = entries
        return real(entries, *a, **k)
    inc.scfg_masks = spy
    try:
        with torch.no_grad():
            unet(x, torch.tensor(500), encoder_hidden_states=ehs)
    finally:
        inc.scfg_masks = real
        hook.remove()
    entries = captured["entries"]
    layers = inc.attention_layers(unet)
    assert len(entries) == len(layers)
    kinds = [k for k, *_ in entries]
    assert kinds.count("self") == kinds.count("cross") == len(layers) // 2
    cross = [e for e in entries if e[0] == "cross"][0]
    assert cross[2].shape == (1, 77, 8)
    # the key of a cross-attention entry is to_k of the first conditional row (row 2 of [u, u, c, c])
    first_attn2 = [m for n, m in layers if n.endswith("attn2")][0]
    assert torch.allclose(cross[2], first_attn2.to_k(ehs[2:3]))


def test_t2i0_edits_cond_rows_and_pag_uses_the_original_conditioning():
    unet = _unet()
    x, ehs = _inputs()
    hook = _hook(unet, t2i0_active=True, t2i0_correction_strength=0.5, t2i0_step_start=0, t2i0_ctnms_alpha=0.0,
                 pag_active=True, pag_scale=1.0)
    first, last = [], []
    spies = [unet.register_forward_pre_hook(lambda m, a, k: first.append(k["encoder_hidden_states"].clone()),
                                            with_kwargs=True, prepend=True),
             unet.register_forward_pre_hook(lambda m, a, k: last.append(k["encoder_hidden_states"].clone()),
                                            with_kwargs=True)]
    try:
        with torch.no_grad():
            unet(x, torch.tensor(500), encoder_hidden_states=ehs)
    finally:
        for s in spies:
            s.remove()
        hook.remove()
    edited = inc.cbs(ehs[1].float(), 0.5, 2, 0.5, [])
    assert not torch.equal(edited, ehs[1])
    assert torch.equal(first[0], ehs)
    assert torch.equal(last[0][0], ehs[0]) and torch.equal(last[0][1], edited)  # only the cond row is corrected
    # the PAG evaluation sees [cond, cond] from before the correction, as the source clones it first
    assert torch.equal(first[1], torch.cat([ehs[1:], ehs[1:]])) and torch.equal(last[1], first[1])


def test_combinations_are_rejected_by_name():
    c = inc.normalize({"pag_active": True})
    with pytest.raises(EngineError, match="APG"):
        inc.check_combination(c, {"APG": True}, guided=True)
    with pytest.raises(EngineError, match="CFG scale > 1"):
        inc.check_combination(c, {}, guided=False)
    seg = inc.normalize({"seg_active": True})
    with pytest.raises(EngineError, match="HyperTile"):
        inc.check_combination(seg, {"HyperTile": True}, guided=True)
    inc.check_combination(seg, {"APG": True}, guided=True)  # SEG alone keeps CFG combination intact
    t2 = inc.normalize({"t2i0_active": True})
    inc.check_combination(t2, {"HyperTile": True, "Semantic Guidance": True}, guided=False)
    with pytest.raises(EngineError, match="Semantic Guidance"):
        inc.check_combination(inc.normalize({"t2i0_active": True, "t2i0_correction_strength": 0.2}),
                              {"Semantic Guidance": True}, guided=True)
    with pytest.raises(EngineError, match="PAG"):
        inc.check_combination(t2, {"PAG": True}, guided=True)
    with pytest.raises(EngineError, match="Reference"):
        inc.check_combination(t2, {"Reference": True}, guided=True)


def _pipeline():
    diffusers = pytest.importorskip("diffusers")
    vae = diffusers.AutoencoderKL(in_channels=3, out_channels=3, latent_channels=4,
                                  down_block_types=("DownEncoderBlock2D",) * 4, up_block_types=("UpDecoderBlock2D",) * 4,
                                  block_out_channels=(4, 8, 16, 16), layers_per_block=1, norm_num_groups=4)
    pipe = diffusers.StableDiffusionPipeline(vae=vae, text_encoder=None, tokenizer=None, unet=_unet(),
                                             scheduler=diffusers.EulerDiscreteScheduler(steps_offset=1),
                                             safety_checker=None, feature_extractor=None, requires_safety_checker=False)
    pipe.set_progress_bar_config(disable=True)
    return pipe


def _generate(pipe, config, monkeypatch, *, pass_="base", engine=None):
    monkeypatch.setattr(inc, "prompt_token_count", lambda p, text, steps: 6)
    engine = engine or types.SimpleNamespace()
    embeds = torch.randn(1, 77, 8, generator=torch.Generator().manual_seed(1))
    kw = dict(prompt_embeds=embeds, negative_prompt_embeds=torch.zeros_like(embeds), num_inference_steps=6,
              guidance_scale=5.0, height=64, width=64, output_type="np", generator=[torch.Generator().manual_seed(9)])
    cleanups = []
    gp = types.SimpleNamespace(steps=6, cfg_scale=5.0, prompt="a cat", batch_size=1)
    try:
        if config:
            inc.install(engine, pipe, gp, kw, None, config, pass_=pass_, w=64, h=64, cfg_scale=5.0,
                        sched=pipe.scheduler, cleanups=cleanups)
        with torch.no_grad():
            return pipe(**kw).images[0]
    finally:
        for fn in reversed(cleanups):
            fn()


def test_pipeline_runs_every_feature_deterministically_and_cleans_up(monkeypatch):
    pytest.importorskip("einops")
    pipe = _pipeline()
    base = _generate(pipe, None, monkeypatch)
    conf = dict(seg_active=True, seg_blur_sigma=1.0, scfg_active=True, scfg_r=2, pag_active=True, pag_scale=2.0,
                pag_sanf=True, cfg_interval_enable=True, cfg_interval_schedule="Clamp-Cosine (c=2.0)",
                t2i0_active=True, t2i0_correction_strength=0.3, t2i0_ema_factor=1.0, t2i0_step_start=0)
    one = _generate(pipe, conf, monkeypatch)
    two = _generate(pipe, conf, monkeypatch)
    assert (one == two).all() and not (one == base).all()
    assert not pipe.unet._forward_hooks and not pipe.unet._forward_pre_hooks
    assert all(not m._forward_hooks for _, m in pipe.unet.named_modules())
    assert (_generate(pipe, None, monkeypatch) == base).all()
    for single in (dict(seg_active=True), dict(scfg_active=True), dict(pag_active=True, pag_scale=3.0),
                   dict(t2i0_active=True)):
        assert not (_generate(pipe, single, monkeypatch) == base).all(), single


def test_install_ignores_inactive_settings(monkeypatch):
    pipe = _pipeline()
    engine = types.SimpleNamespace()
    base = _generate(pipe, None, monkeypatch)
    assert (_generate(pipe, {"seg_active": True, "seg_blur_sigma": 0}, monkeypatch, engine=engine) == base).all()
    assert getattr(engine, "_incantations_job", None) is None


def test_feature_lists_name_the_hook():
    from sdfx import dryrun, extensions, hooks
    assert "incantations" in hooks.KNOWN_HOOKS and "incantations" in extensions.EXTENSION_HOOKS
    assert "incantations" in dryrun.FakeEngine().features()
