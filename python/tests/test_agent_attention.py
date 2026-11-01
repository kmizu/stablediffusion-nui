import math
import types

import pytest

from sdfx import agent_attention as aa
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")


# --- independent restatement of the pinned source maths (agentsd/patch.py + merge.py at 8c2fd24) ------------------

def reference_layer(attn, block_input, normed, size, s, generator):
    """Token-by-token restatement of one patched block's self-attention output (u_a(attn1(m_a(norm1(x)))))."""
    h0, w0 = size
    batch, n, channels = block_input.shape
    downsample = int(math.ceil(math.sqrt((h0 * w0) // n)))
    h, w = int(math.ceil(h0 / downsample)), int(math.ceil(w0 / downsample))
    r, agent_r = int(n * s["ratio"]), int(n * s["agent_ratio"])
    sx, sy = s["sx"], s["sy"]
    rows, cols = h // sy, w // sx
    if batch % 2 == 0:
        pick = torch.randint(sy * sx, size=(rows, cols, 1), device=generator.device, generator=generator)
    else:
        pick = torch.zeros(rows, cols, 1, dtype=torch.int64)
    dst = sorted((i * sy + int(pick[i, j, 0]) // sx) * w + j * sx + int(pick[i, j, 0]) % sx
                 for i in range(rows) for j in range(cols))
    src = [t for t in range(n) if t not in dst]
    r, agent_r = min(len(src), r), min(len(src), agent_r)
    out = torch.zeros(batch, n, channels)
    for b in range(batch):
        x, y = block_input[b], normed[b]
        cos = lambda i, j: float(torch.dot(x[i], x[j]) / (x[i].norm() * x[j].norm()))  # noqa: E731
        best, nearest = [], []
        for t in src:
            sims = [cos(t, d) for d in dst]
            k = max(range(len(dst)), key=lambda q: (sims[q], -q))
            best.append(sims[k])
            nearest.append(k)
        order = sorted(range(len(src)), key=lambda q: -best[q])

        def merged_tokens(count):
            kept = [y[src[q]] for q in order[count:]]
            groups = [[y[d]] for d in dst]
            for q in order[:count]:
                groups[nearest[q]].append(y[src[q]])
            return torch.stack(kept + [torch.stack(g).mean(0) for g in groups])

        feature = merged_tokens(r)
        agents = merged_tokens(agent_r) if (n - agent_r) * 2 < n - r else None
        heads = attn.heads
        q = attn.to_q(feature).view(len(feature), heads, -1)
        k = attn.to_k(feature).view(len(feature), heads, -1)
        v = attn.to_v(feature).view(len(feature), heads, -1)
        d = q.shape[-1]
        result = torch.zeros_like(q)
        for hd in range(heads):
            if agents is not None and len(agents) * 2 < len(feature):
                a = attn.to_q(agents).view(len(agents), heads, -1)[:, hd]
                agent_v = torch.softmax(a @ k[:, hd].T * d ** -0.5, -1) @ v[:, hd]
                result[:, hd] = torch.softmax(q[:, hd] @ a.T * (d ** -0.5) ** 0.3, -1) @ agent_v + 0.075 * v[:, hd]
            else:
                result[:, hd] = torch.softmax(q[:, hd] @ k[:, hd].T * d ** -0.5, -1) @ v[:, hd]
        y_out = attn.to_out[0](result.reshape(len(feature), -1))
        kept_n = len(src) - r
        for slot, q in enumerate(order[r:]):
            out[b, src[q]] = y_out[slot]
        for slot, dpos in enumerate(dst):
            out[b, dpos] = y_out[kept_n + slot]
        for q in order[:r]:
            out[b, src[q]] = y_out[kept_n + nearest[q]]
    return out


def attention_layer():
    pytest.importorskip("diffusers")
    from diffusers.models.attention_processor import Attention
    torch.manual_seed(5)
    return Attention(query_dim=8, heads=2, dim_head=4).eval()


def settings(**over):
    return dict({"sx": 2, "sy": 2, "ratio": 0.4, "agent_ratio": 0.85, "use_fp32": False, "max_downsample": 1}, **over)


@pytest.mark.parametrize("over,batch,agent_path", [({}, 2, True), ({"agent_ratio": 0.2}, 2, False), ({}, 1, True),
                                                    ({"sx": 3, "sy": 2}, 2, True),
                                                    ({"ratio": 1.0, "agent_ratio": 1.0}, 2, False)])
def test_layer_matches_upstream_restatement(over, batch, agent_path):
    attn = attention_layer()
    gen = torch.Generator().manual_seed(9)
    size = (7, 8)
    block_input = torch.randn(batch, 56, 8, generator=gen)
    normed = torch.nn.functional.layer_norm(block_input, (8,))
    s = settings(**over)
    hook = aa.AgentAttentionHook(None, {}, seed=123)
    hook.settings, hook.size = s, size
    ours = aa.AgentAttentionProcessor(attn.processor)
    attn.set_processor(ours)
    with torch.no_grad():
        ours.plan = hook.plan_for(block_input)
        assert (ours.plan.agent is not None) == agent_path
        got = attn(normed)
        expected = reference_layer(attn, block_input, normed, size, s, torch.Generator().manual_seed(123))
    assert torch.allclose(got, expected, atol=1e-5), (got - expected).abs().max()


def test_fp32_precision_keeps_the_maths_and_reduces_low_precision_error():
    import copy
    reference = attention_layer()
    low = copy.deepcopy(reference).to(torch.bfloat16)
    gen = torch.Generator().manual_seed(2)
    block_input = torch.randn(2, 64, 8, generator=gen) * 4
    plan = aa.plan_merge(block_input, 8, 8, 2, 2, 25, 46, torch.Generator().manual_seed(1))
    features, agents = aa.merge(plan, block_input)
    assert agents is not None
    with torch.no_grad():
        exact = aa.agent_attention(reference, features, agents, False)
        assert torch.allclose(aa.agent_attention(reference, features, agents, True), exact, atol=1e-6)
        plain = aa.agent_attention(low, features.bfloat16(), agents.bfloat16(), False).float()
        fp32 = aa.agent_attention(low, features.bfloat16(), agents.bfloat16(), True).float()
    assert (fp32 - exact).abs().max() <= (plain - exact).abs().max() + 1e-2
    assert (fp32 - exact).abs().max() < 0.1


def test_destinations_follow_windows_and_top_left_without_randomness():
    mask = aa.destinations(5, 7, 3, 2, None, torch.device("cpu")).reshape(5, 7)
    # 2 full rows x 2 full columns of 2x3 windows; leftover row 4 and column 6 hold only sources.
    assert mask.nonzero().tolist() == [[0, 0], [0, 3], [2, 0], [2, 3]]
    gen = torch.Generator().manual_seed(4)
    mask = aa.destinations(4, 4, 2, 2, gen, torch.device("cpu")).reshape(2, 2, 2, 2).permute(0, 2, 1, 3)
    assert mask.reshape(4, 4).sum() == 4 and all(mask[i, j].sum() == 1 for i in range(2) for j in range(2))
    with pytest.raises(EngineError):
        aa.destinations(3, 3, 4, 4, None, torch.device("cpu"))


def test_unmerge_inverts_merge_for_kept_tokens_and_broadcasts_destinations():
    x = torch.randn(2, 16, 3)
    plan = aa.plan_merge(x, 4, 4, 2, 2, 6, 0, None)
    features, agents = aa.merge(plan, x)
    assert agents is None and features.shape == (2, 10, 3)
    back = aa.unmerge(plan, features)
    for b in range(2):
        for slot, q in enumerate(plan.kept[b].tolist()):
            assert torch.equal(back[b, plan.src[q]], x[b, plan.src[q]])
        for q, target in zip(plan.merged[b].tolist(), plan.target[b].tolist()):
            assert torch.equal(back[b, plan.src[q]], back[b, plan.dst[target]])


def test_downsample_levels_follow_upstream_formula():
    assert aa.downsample_of((65, 65), 65 * 65) == 1
    assert aa.downsample_of((65, 65), 33 * 33) == 2
    assert aa.downsample_of((65, 65), 17 * 17) == 4
    assert aa.downsample_of((65, 65), 9 * 9) == 8
    assert aa.downsample_of((128, 96), 64 * 48) == 2


# --- schedule, passes and settings ------------------------------------------------------------------------------

def test_schedule_and_pass_gating_follow_source_callbacks():
    c = aa.normalize({})
    assert aa.settings_at(c, 0)["sx"] == 4 and aa.settings_at(c, 19)["agent_ratio"] == 0.95
    assert aa.settings_at(c, 20) is None  # without Second Pass the patch is removed at Second Pass Step
    c = aa.normalize({"use_sp": True, "sp_step": 0})
    assert aa.settings_at(c, 0) == aa.pass_settings(c, True)
    assert aa.applies(c, "base") and not aa.applies(c, "hires") and not aa.applies(c, "adetailer")
    c = aa.normalize({"hires_fix_only": True})
    assert aa.applies(c, "hires") and not aa.applies(c, "base")


@pytest.mark.parametrize("config", [{"sx": 0}, {"ratio": 0}, {"ratio": 1.5}, {"agent_ratio": -0.1},
                                    {"sp_step": -1}, {"max_downsample": 0}, {"sx": 2.5}, {"use_sp": "maybe"},
                                    {"use_sp": True, "sp_sy": 0}, {"use_sp": True, "sp_ratio": 0},
                                    {"ratio": float("nan")}, {"k_shortcut": 0.1}, {"sx": True}])
def test_normalize_rejects_settings_the_source_cannot_run(config):
    with pytest.raises(EngineError):
        aa.normalize(config)


def test_normalize_accepts_unused_second_pass_and_infotext_strings():
    c = aa.normalize({"sp_sx": 0, "sp_ratio": 0, "use_sp": "False", "max_downsample": 3, "sp_step": 0.0 + 5})
    assert c["use_sp"] is False and c["max_downsample"] == 3 and c["sp_step"] == 5
    assert aa.normalize({"sp_step": 0, "ratio": 0})["sp_step"] == 0  # first pass never runs at Second Pass Step 0


# --- real (tiny) UNet -------------------------------------------------------------------------------------------

def tiny_unet():
    pytest.importorskip("diffusers")
    from diffusers import UNet2DConditionModel
    torch.manual_seed(41)
    torch.set_num_threads(2)
    return UNet2DConditionModel(sample_size=8, in_channels=4, out_channels=4,
        down_block_types=("CrossAttnDownBlock2D", "CrossAttnDownBlock2D"),
        up_block_types=("CrossAttnUpBlock2D", "CrossAttnUpBlock2D"),
        block_out_channels=(8, 16), layers_per_block=1, cross_attention_dim=8,
        attention_head_dim=2, norm_num_groups=4).eval()


def config(**over):
    return aa.normalize(dict({"sx": 2, "sy": 2, "sp_step": 2}, **over))


def test_unet_hook_schedule_and_restore():
    unet = tiny_unet()
    x, context = torch.randn(2, 4, 8, 8), torch.randn(2, 5, 8)
    processors = dict(unet.attn_processors)
    with torch.no_grad():
        baseline = {t: unet(x, t, context).sample for t in (999, 800, 600)}
        hook = aa.AgentAttentionHook(unet, config(), seed=7).install()
        try:
            first = unet(x, 999, context).sample
            assert not torch.allclose(first, baseline[999])
            assert hook.merged_layers == 3  # the blocks at full latent resolution (down 0, up 1 twice)
            again = unet(x, 999, context).sample  # same step: no re-patch, generator continues
            assert not torch.allclose(unet(x, 800, context).sample, baseline[800])
            assert torch.equal(unet(x, 600, context).sample, baseline[600])  # removed at Second Pass Step
            assert hook.step == 2
        finally:
            hook.remove()
        assert dict(unet.attn_processors) == processors
        assert torch.equal(unet(x, 999, context).sample, baseline[999])
        assert again.shape == first.shape


def test_second_pass_repatches_with_fresh_generator_and_max_downsample_selects_layers():
    unet = tiny_unet()
    x, context = torch.randn(2, 4, 8, 8), torch.randn(2, 5, 8)
    second = dict(sp_sx=1, sp_sy=2, sp_ratio=0.3, sp_agent_ratio=0.8)
    with torch.no_grad():
        hook = aa.AgentAttentionHook(unet, config(use_sp=True, sp_step=1, max_downsample=2, **second), seed=3).install()
        try:
            unet(x, 999, context)
            assert hook.merged_layers == 7  # every block at downsample 1 or 2
            switched = unet(x, 800, context).sample
        finally:
            hook.remove()
        alone = aa.AgentAttentionHook(unet, config(max_downsample=2, sx=1, sy=2, ratio=0.3, agent_ratio=0.8,
                                                   sp_step=99), seed=3).install()
        try:
            direct = unet(x, 800, context).sample
        finally:
            alone.remove()
    assert torch.equal(switched, direct)


def test_errors_inside_the_unet_are_explicit_and_cleanup_restores():
    unet = tiny_unet()
    processors = dict(unet.attn_processors)
    x, context = torch.randn(2, 4, 8, 8), torch.randn(2, 5, 8)
    hook = aa.AgentAttentionHook(unet, config(ratio=0.001), seed=1).install()
    with pytest.raises(EngineError, match="merges no tokens"):
        with torch.no_grad():
            unet(x, 999, context)
    hook.remove()
    assert dict(unet.attn_processors) == processors
    hook = aa.AgentAttentionHook(unet, config(sx=9), seed=1).install()
    with pytest.raises(EngineError, match="exceed"):
        with torch.no_grad():
            unet(x, 999, context)
    hook.remove()
    assert dict(unet.attn_processors) == processors


def test_install_rejects_unsupported_processors_and_blocks():
    unet = tiny_unet()
    from diffusers.models.attention_processor import AttnAddedKVProcessor
    block = unet.down_blocks[0].attentions[0].transformer_blocks[0]
    block.attn1.set_processor(AttnAddedKVProcessor())
    processors = dict(unet.attn_processors)
    with pytest.raises(EngineError, match="processor"):
        aa.AgentAttentionHook(unet, config(), seed=1).install()
    assert dict(unet.attn_processors) == processors


class _Ctx:
    def __init__(self):
        self.warnings = []

    def warn(self, message):
        self.warnings.append(message)


def _gp(hooks=None, hires=False):
    return types.SimpleNamespace(hooks=hooks or {}, hires=types.SimpleNamespace(enabled=hires))


def test_engine_install_gates_passes_warns_and_rejects_combinations():
    unet = tiny_unet()
    pipe = types.SimpleNamespace(unet=unet)
    engine = types.SimpleNamespace(sdxl=False)
    ctx = _Ctx()
    remove = aa.install(engine, pipe, _gp(), ctx, {}, pass_="base", seed=1)
    assert remove is not None and not ctx.warnings
    remove()
    assert aa.install(engine, pipe, _gp(), ctx, {}, pass_="hires", seed=1) is None
    assert aa.install(engine, pipe, _gp(), ctx, {}, pass_="adetailer", seed=1) is None
    assert aa.install(engine, pipe, _gp(), ctx, {"hires_fix_only": True}, pass_="base", seed=1) is None
    assert "no hires pass" in ctx.warnings[-1]
    remove = aa.install(types.SimpleNamespace(sdxl=True), pipe, _gp(), ctx, {}, pass_="base", seed=1)
    assert "SDXL" in ctx.warnings[-1]
    remove()
    for hooks, kw in (({"hypertile": {}}, {}), ({"reference": {"image": "x"}}, {}), ({"controllllite": {}}, {}),
                      ({"token_downsampling": {"factor": 2}}, {}), ({}, {"pag": True}), ({}, {"sag": True})):
        with pytest.raises(EngineError, match="cannot combine"):
            aa.install(engine, pipe, _gp(hooks), ctx, {}, pass_="base", seed=1, **kw)
    # Token Downsampling at factor 1 changes nothing, so it may stay enabled.
    aa.install(engine, pipe, _gp({"token_downsampling": {"factor": 1}}), ctx, {}, pass_="base", seed=1)()


def test_registered_everywhere():
    from sdfx import dryrun, extensions, hooks
    assert "agent_attention" in hooks.KNOWN_HOOKS and "agent_attention" in extensions.EXTENSION_HOOKS
    assert "agent_attention" in dryrun.FakeEngine.features(object.__new__(dryrun.FakeEngine))
