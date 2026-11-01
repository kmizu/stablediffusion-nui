import types

import pytest

from sdfx import semantic_guidance as sg
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")


def upstream_edit(concepts, strengths, uncond, warmup, step, scale, tail, momentum_scale, momentum_beta):
    """Literal restatement of pinned sega_routine_batch for one conditioning key (returns the cond delta)."""
    from statistics import NormalDist
    batch_tensor = torch.stack(concepts)  # [num_concepts, batch, ...]
    new_shape = (-1,) + (1,) * (batch_tensor.dim() - 1)
    strength = torch.tensor(strengths, dtype=batch_tensor.dtype).view(new_shape)
    inside = tuple(range(-batch_tensor.dim() + 1, 0))
    mean, std = torch.mean(batch_tensor, dim=inside), torch.std(batch_tensor, dim=inside)
    edit_dir = torch.mul(strength, batch_tensor - uncond)
    upper = mean + NormalDist().inv_cdf(1.0 - tail) * std
    scale_tensor = torch.where(edit_dir.abs() > upper.view(new_shape), torch.ones_like(batch_tensor) * scale,
                               torch.zeros_like(batch_tensor))
    guidance_strength = 0.0 if step < warmup else 1.0
    edit = torch.zeros_like(batch_tensor) + guidance_strength * torch.mul(scale_tensor, edit_dir)
    delta = torch.zeros_like(uncond)
    v = torch.zeros(edit.shape[1:])
    for i in range(len(concepts)):
        edit[i] = edit[i] + momentum_scale * v
        v_next = momentum_beta * ((1 - momentum_beta) * v) * edit[i]
        if step >= warmup:
            delta = delta + edit[i]
        v = v_next
    return delta


def test_parse_concepts_follows_upstream():
    assert sg.parse_concepts("", "") == []
    assert sg.parse_concepts("apple, (cat:1.2)", "[dog]") == [("apple", 1.0), ("cat", 1.2), ("dog", -1 / 1.1)]
    assert sg.parse_concepts("a (cat:1.5)", "") == [("a ", 1.0)]  # only the first attention segment
    assert sg.parse_concepts(" ", "") == [("", 1.0)]


def test_edit_matches_upstream_routine():
    gen = torch.Generator().manual_seed(3)
    uncond = torch.randn(2, 77, 16, generator=gen)
    row = [torch.randn(1, 77, 16, generator=gen) for _ in range(3)]
    strengths = [1.0, 1.3, -0.8]
    z = sg.z_score(0.05)
    ours = sum(sg.edit_direction(c, uncond, s, 2.5, z) for c, s in zip(row, strengths))
    theirs = upstream_edit([c.expand_as(uncond) for c in row], strengths, uncond, 10, 12, 2.5, 0.05, 0.3, 0.6)
    assert torch.allclose(ours, theirs, atol=1e-6)
    assert ours.abs().sum() > 0
    before = upstream_edit([c.expand_as(uncond) for c in row], strengths, uncond, 10, 9, 2.5, 0.05, 0.3, 0.6)
    assert before.abs().sum() == 0


def test_tail_edges():
    uncond = torch.zeros(1, 4, 2)
    concept = torch.arange(8, dtype=torch.float32).view(1, 4, 2)
    assert sg.edit_direction(concept, uncond, 1.0, 1.0, sg.z_score(0.0)).abs().sum() == 0
    assert torch.equal(sg.edit_direction(concept, uncond, 1.0, 1.0, sg.z_score(1.0)), concept)


class _Unet:
    def __init__(self):
        self.calls = []
        self.hook = None
        self.add_time_proj = lambda ids: torch.stack([ids.float() * 0.01, -ids.float() * 0.01], dim=1)

    def register_forward_pre_hook(self, hook, with_kwargs):
        self.hook = hook
        unet = self

        class Handle:
            def remove(self):
                unet.hook = None
        return Handle()

    def __call__(self, sample, timestep, encoder_hidden_states=None, added_cond_kwargs=None):
        args, kwargs = (sample, timestep), {"encoder_hidden_states": encoder_hidden_states,
                                            "added_cond_kwargs": added_cond_kwargs}
        if self.hook:
            result = self.hook(self, args, kwargs)
            if result is not None:
                args, kwargs = result
        self.calls.append(kwargs)
        return kwargs


def _config(**over):
    return sg.normalize(dict({"prompt": "x", "warmup": 2, "edit_guidance_scale": 1.5,
                              "tail_percentage_threshold": 0.5}, **over))


def test_hook_waits_for_warmup_and_edits_only_positive_rows():
    unet = _Unet()
    gen = torch.Generator().manual_seed(1)
    concept = torch.randn(1, 77, 8, generator=gen)
    hook = sg.SemanticGuidanceHook(unet, [(concept, None, 1.0)], _config(), 1).install()
    hidden = torch.randn(2, 77, 8, generator=gen)
    outputs = [unet(None, torch.tensor(t), encoder_hidden_states=hidden) for t in (999, 999, 800, 600, 400)]
    # 999 twice is one evaluation step (e.g. PAG or a second branch at the same timestep).
    assert [torch.equal(o["encoder_hidden_states"], hidden) for o in outputs] == [True, True, True, False, False]
    edited = outputs[3]["encoder_hidden_states"]
    assert torch.equal(edited[0], hidden[0])
    expected = hidden[1] + sg.edit_direction(concept, hidden[:1], 1.0, 1.5, sg.z_score(0.5))[0]
    assert torch.allclose(edited[1], expected)
    hook.remove()
    assert unet.hook is None


def test_hook_layouts_and_padding():
    unet = _Unet()
    concept = torch.ones(1, 77, 4)
    empty = torch.zeros(1, 77, 4)
    negative = torch.full((1, 154, 4), 0.5)
    hook = sg.SemanticGuidanceHook(unet, [(concept, None, 2.0)], _config(warmup=0, tail_percentage_threshold=1.0), 1,
                                   empty=empty, negative=negative).install()
    no_cfg = unet(None, torch.tensor(1), encoder_hidden_states=torch.zeros(1, 154, 4))["encoder_hidden_states"]
    padded = torch.cat([concept, empty], dim=1)
    assert torch.allclose(no_cfg[0], 2.0 * (padded[0] - 0.5) * 1.5)
    pag = unet(None, torch.tensor(1), encoder_hidden_states=torch.zeros(3, 154, 4))["encoder_hidden_states"]
    assert pag[0].abs().sum() == 0 and torch.equal(pag[1], pag[2]) and pag[1].abs().sum() > 0
    with pytest.raises(EngineError):
        unet(None, torch.tensor(1), encoder_hidden_states=torch.zeros(4, 154, 4))


def test_sdxl_pooled_edit_uses_size_vector_for_threshold():
    unet = _Unet()
    concept = torch.zeros(1, 77, 4)
    pooled = torch.tensor([[30.0, -30.0, 0.1]])
    hook = sg.SemanticGuidanceHook(unet, [(concept, pooled, 1.0)], _config(warmup=0, tail_percentage_threshold=0.3), 1,
                                   add_time_proj=unet.add_time_proj)
    hook.install()
    time_ids = torch.tensor([[1024, 1024, 0, 0, 1024, 1024]] * 2)
    out = unet(None, torch.tensor(1), encoder_hidden_states=torch.zeros(2, 77, 4),
               added_cond_kwargs={"text_embeds": torch.zeros(2, 3), "time_ids": time_ids})
    size = unet.add_time_proj(time_ids[:1].flatten()).reshape(1, -1)
    vector = torch.cat([pooled, size], dim=1)
    expected = sg.edit_direction(vector, torch.cat([torch.zeros(1, 3), size], dim=1), 1.0, 1.5, sg.z_score(0.3))
    pos = out["added_cond_kwargs"]["text_embeds"]
    assert torch.equal(pos[0], torch.zeros(3)) and torch.allclose(pos[1], expected[0, :3])
    assert pos[1].abs().sum() > 0


def test_install_rejects_branch_composition_and_photomaker():
    engine = types.SimpleNamespace(sdxl=False, kind="sd15", dtype=torch.float32)
    gp = types.SimpleNamespace(batch_size=1, clip_skip=1)
    with pytest.raises(EngineError):
        sg.install(engine, None, gp, {"prompt_embeds": torch.zeros(1, 77, 4)}, {"prompt": "x"}, composed=True)
    with pytest.raises(EngineError):
        sg.install(engine, None, gp, {}, {"prompt": "x"})


def test_normalize_rejects_bad_settings():
    for bad in ({"warmup": -1}, {"tail_percentage_threshold": 2}, {"edit_guidance_scale": float("nan")}):
        with pytest.raises(EngineError):
            sg.normalize(bad)
