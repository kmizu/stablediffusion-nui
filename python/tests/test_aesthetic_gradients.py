import types
from pathlib import Path

import pytest

from sdfx import aesthetic_gradients as ag
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")
transformers = pytest.importorskip("transformers")

TOKENIZER_REPO = "openai/clip-vit-large-patch14"


def tiny_text_encoder():
    from transformers import CLIPTextConfig, CLIPTextModel
    torch.manual_seed(3)
    torch.set_num_threads(2)
    config = CLIPTextConfig(vocab_size=49408, hidden_size=768, intermediate_size=64, num_hidden_layers=2,
                            num_attention_heads=12, max_position_embeddings=77, eos_token_id=49407, bos_token_id=49406,
                            pad_token_id=49407)
    return CLIPTextModel(config).eval()


def tokenizer():
    from transformers import CLIPTokenizer
    try:
        return CLIPTokenizer.from_pretrained(TOKENIZER_REPO, local_files_only=True)
    except Exception:
        pytest.skip("the CLIP tokenizer is not in the local Hugging Face cache")


def fake_pipe(te):
    return types.SimpleNamespace(text_encoder=te, tokenizer=tokenizer(), text_encoder_2=None,
                                 _execution_device=torch.device("cpu"))


def tiny_head(seed=4):
    g = torch.Generator().manual_seed(seed)
    return ag.TextHead(torch.randn(768, 768, generator=g) / 28)


def unit(seed, n=768):
    g = torch.Generator().manual_seed(seed)
    v = torch.randn(n, generator=g)
    return v / v.norm()


# --- settings -------------------------------------------------------------------------------------------------------

def test_defaults_and_validation():
    c = ag.normalize({"embedding": "style"})
    assert (c["weight"], c["steps"], c["lr"], c["slerp"], c["slerp_angle"], c["text"], c["negative"]) == \
        (0.9, 20, 0.0001, False, 0.1, "", False)
    assert ag.normalize({"embedding": "x", "steps": "5", "slerp": "true"})["steps"] == 5
    for bad in ({}, {"embedding": " "}, {"embedding": "x", "weight": 1.5}, {"embedding": "x", "weight": -0.1},
                {"embedding": "x", "steps": 2.5}, {"embedding": "x", "steps": -1}, {"embedding": "x", "steps": 1000},
                {"embedding": "x", "lr": float("nan")}, {"embedding": "x", "lr": -1}, {"embedding": "x", "slerp_angle": 2},
                {"embedding": "x", "weight": "abc"}):
        with pytest.raises(EngineError):
            ag.normalize(bad)


# --- embedding files ------------------------------------------------------------------------------------------------

def test_load_embedding_formats_and_errors(tmp_path):
    v = unit(1) * 7.0
    torch.save(v.reshape(1, 768), tmp_path / "plain.pt")
    torch.save({"aesthetic_embedding": v}, tmp_path / "wrapped.pt")
    from safetensors.torch import save_file
    save_file({"embedding": v.contiguous()}, str(tmp_path / "s.safetensors"))
    for name in ("plain.pt", "wrapped.pt", "s.safetensors"):
        e = ag.load_embedding(tmp_path / name)
        assert e.shape == (768,) and abs(float(e.norm()) - 1.0) < 1e-5
        assert torch.allclose(e, v / v.norm(), atol=1e-6)
    torch.save(torch.zeros(1, 768), tmp_path / "zero.pt")
    torch.save(torch.ones(1, 512), tmp_path / "small.pt")
    torch.save(torch.full((1, 768), float("nan")), tmp_path / "nan.pt")
    (tmp_path / "junk.pt").write_bytes(b"not a tensor")
    for name in ("zero.pt", "small.pt", "nan.pt", "junk.pt"):
        with pytest.raises(EngineError):
            ag.load_embedding(tmp_path / name)


def test_resolve_and_list(tmp_path, monkeypatch):
    folder = tmp_path / "emb"
    folder.mkdir()
    torch.save(unit(2).reshape(1, 768), folder / "sunset.pt")
    monkeypatch.setenv(ag.EMBEDDINGS_ENV, str(folder))
    monkeypatch.setenv(ag.CACHE_ENV, str(tmp_path / "cache"))
    assert ag.resolve_embedding("sunset") == folder / "sunset.pt"
    assert ag.resolve_embedding("sunset.pt") == folder / "sunset.pt"
    assert ag.resolve_embedding(str(folder / "sunset.pt")) == folder / "sunset.pt"
    assert ag.list_embeddings() == ["sunset"]
    with pytest.raises(EngineError):
        ag.resolve_embedding("missing")


def test_slerp():
    a, b = unit(1), unit(2)
    assert torch.allclose(ag.slerp(a, b, 0.0), a, atol=1e-5)
    assert torch.allclose(ag.slerp(a, b, 1.0), b, atol=1e-5)
    mid = ag.slerp(a, b, 0.5)
    assert abs(float(mid.norm()) - 1.0) < 1e-5
    assert abs(float(mid @ a) - float(mid @ b)) < 1e-5
    assert torch.allclose(ag.slerp(a, a, 0.3), a, atol=1e-5)  # parallel falls back


# --- personalisation ------------------------------------------------------------------------------------------------

def test_personalized_encoder_moves_towards_target_and_keeps_original():
    te = tiny_text_encoder()
    pipe = fake_pipe(te)
    before = {k: v.clone() for k, v in te.state_dict().items()}
    head = tiny_head()
    target = unit(11)
    from sdfx import prompt
    chunks = prompt._chunks_for(pipe, pipe.tokenizer, "a red fox")
    eos = int(pipe.tokenizer.eos_token_id)
    with torch.no_grad():
        start = float(ag._embed_text(te, chunks, head, eos, "cpu") @ target)
    clone, losses = ag.personalized_encoder(pipe, te, "a red fox", target, 8, 1e-3, head, "cpu")
    with torch.no_grad():
        end = float(ag._embed_text(clone, chunks, head, eos, "cpu") @ target)
    assert len(losses) == 8 and losses[-1] < losses[0]
    assert end > start
    assert all(not p.requires_grad for p in clone.parameters())
    for k, v in te.state_dict().items():  # the checkpoint's encoder is untouched
        assert torch.equal(v, before[k])
    zero, _ = ag.personalized_encoder(pipe, te, "a red fox", target, 0, 1e-3, head, "cpu")
    for (k, a), (_, b) in zip(te.state_dict().items(), zero.state_dict().items()):
        assert torch.equal(a.float(), b)


def test_sdxl_state_is_completed_from_the_reference_model(monkeypatch):
    # SDXL checkpoints load the encoder's last layer and final norm uninitialised (NaN here)
    te = tiny_text_encoder()
    layer = "text_model.encoder.layers.1."
    with torch.no_grad():
        for name, tensor in te.state_dict().items():
            if name.startswith(layer) or name.startswith("text_model.final_layer_norm."):
                tensor.fill_(float("nan"))
    reference = {k: torch.zeros_like(v).float() + 0.5 for k, v in te.state_dict().items()
                 if k.startswith(layer) or k.startswith("text_model.final_layer_norm.")}
    asked = []
    monkeypatch.setattr(ag, "reference_tensors", lambda prefixes, root=None: asked.append(prefixes) or reference)
    plain = ag.state_copy(types.SimpleNamespace(), te, sdxl=False)
    assert not asked and not bool(torch.isfinite(plain[layer + "mlp.fc1.weight"]).any())  # SD1.x: copied as is
    state = ag.state_copy(types.SimpleNamespace(), te, sdxl=True)
    assert asked == [(layer, "text_model.final_layer_norm.")]
    assert all(bool(torch.isfinite(v).all()) for v in state.values())
    pipe = fake_pipe(te)
    clone, losses = ag.personalized_encoder(pipe, te, "a red fox", unit(11), 3, 1e-3, tiny_head(), "cpu", state)
    assert all(x == x for x in losses) and losses[-1] < losses[0]


def encode_setup(tmp_path, monkeypatch, sdxl=False):
    from sdfx import prompt
    te = tiny_text_encoder()
    pipe = fake_pipe(te)
    engine = types.SimpleNamespace(kind="sdxl" if sdxl else "sd15", sdxl=sdxl)
    torch.save(unit(5).reshape(1, 768), tmp_path / "style.pt")
    monkeypatch.setenv(ag.EMBEDDINGS_ENV, str(tmp_path))
    monkeypatch.setattr(ag, "text_head", lambda device, root=None: tiny_head())
    monkeypatch.setattr(ag, "reference_tensors", lambda prefixes, root=None: {
        k: v.float().clone() for k, v in te.state_dict().items() if k.startswith(tuple(prefixes))})
    ag._cache.clear()
    gp = types.SimpleNamespace(clip_skip=1)
    if sdxl:
        def plain(p, text):
            z = prompt.encode_sd15(p, text, 2)
            return z, torch.zeros(1, 4)
    else:
        def plain(p, text):
            return prompt.encode_sd15(p, text, gp.clip_skip, False)
    return engine, pipe, gp, plain


def test_encode_prompt_blend(tmp_path, monkeypatch):
    engine, pipe, gp, plain = encode_setup(tmp_path, monkeypatch)
    base = plain(pipe, "a red fox")
    c = ag.normalize({"embedding": "style", "weight": 0.0, "steps": 3, "lr": 1e-3})
    assert torch.equal(ag.encode_prompt(engine, pipe, "a red fox", gp, c, plain), base)  # weight 0: unchanged
    c1 = ag.normalize({"embedding": "style", "weight": 1.0, "steps": 3, "lr": 1e-3})
    full = ag.encode_prompt(engine, pipe, "a red fox", gp, c1, plain)
    assert full.shape == base.shape and not torch.equal(full, base)
    half = ag.encode_prompt(engine, pipe, "a red fox", gp, ag.normalize(dict(c1, weight=0.5)), plain)
    assert torch.allclose(half, (base + full) / 2, atol=1e-5)
    c0 = ag.normalize({"embedding": "style", "weight": 1.0, "steps": 0})
    assert torch.allclose(ag.encode_prompt(engine, pipe, "a red fox", gp, c0, plain), base, atol=1e-5)  # no training


def test_encode_prompt_cache_and_determinism(tmp_path, monkeypatch):
    engine, pipe, gp, plain = encode_setup(tmp_path, monkeypatch)
    c = ag.normalize({"embedding": "style", "weight": 1.0, "steps": 2, "lr": 1e-3})
    first = ag.encode_prompt(engine, pipe, "a red fox", gp, c, plain)
    assert len(ag._cache) == 1
    calls = []
    real = ag.personalized_encoder
    monkeypatch.setattr(ag, "personalized_encoder", lambda *a, **k: calls.append(1) or real(*a, **k))
    again = ag.encode_prompt(engine, pipe, "a red fox", gp, c, plain)
    assert calls == [] and torch.equal(first, again)  # cached
    ag.encode_prompt(engine, pipe, "a blue fox", gp, c, plain)
    assert calls == [1]
    ag._cache.clear()
    assert torch.allclose(ag.encode_prompt(engine, pipe, "a red fox", gp, c, plain), first, atol=1e-6)


def test_expected_embedding_fingerprint_rejects_file_changed_after_replay(tmp_path, monkeypatch):
    engine, pipe, gp, plain = encode_setup(tmp_path, monkeypatch)
    path = ag.resolve_embedding("style")
    _, digest = ag.embedding_snapshot(path)
    c = ag.normalize({"embedding": str(path), "embedding_sha256": digest, "steps": 0})
    torch.save(torch.ones(768), path)
    with pytest.raises(EngineError, match="fingerprint"):
        ag.encode_prompt(engine, pipe, "a red fox", gp, c, plain)


def test_slerp_target_uses_text(tmp_path, monkeypatch):
    engine, pipe, gp, plain = encode_setup(tmp_path, monkeypatch)
    plainc = ag.normalize({"embedding": "style", "weight": 1.0, "steps": 2, "lr": 1e-3})
    s0 = ag.normalize(dict(plainc, slerp=True, slerp_angle=0.0, text="masterpiece"))
    a = ag.encode_prompt(engine, pipe, "a red fox", gp, plainc, plain)
    b = ag.encode_prompt(engine, pipe, "a red fox", gp, s0, plain)  # angle 0 = the aesthetic embedding itself
    assert torch.allclose(a, b, atol=1e-6)
    s1 = ag.normalize(dict(plainc, slerp=True, slerp_angle=0.7, text="masterpiece"))
    c = ag.encode_prompt(engine, pipe, "a red fox", gp, s1, plain)
    assert not torch.allclose(a, c, atol=1e-6)
    empty = ag.normalize(dict(plainc, slerp=True, slerp_angle=0.7, text="  "))  # no text: plain embedding
    assert torch.allclose(ag.encode_prompt(engine, pipe, "a red fox", gp, empty, plain), a, atol=1e-6)


def test_sdxl_keeps_pooled_and_sd2_rejected(tmp_path, monkeypatch):
    engine, pipe, gp, plain = encode_setup(tmp_path, monkeypatch, sdxl=True)
    c = ag.normalize({"embedding": "style", "weight": 1.0, "steps": 2, "lr": 1e-3})
    base = plain(pipe, "a red fox")
    z, pooled = ag.encode_prompt(engine, pipe, "a red fox", gp, c, plain)
    assert z.shape == base[0].shape and not torch.equal(z, base[0]) and torch.equal(pooled, base[1])
    engine.kind = "sd2"
    with pytest.raises(EngineError):
        ag.encode_prompt(engine, pipe, "a red fox", gp, c, plain)


def test_missing_embedding_is_an_error(tmp_path, monkeypatch):
    engine, pipe, gp, plain = encode_setup(tmp_path, monkeypatch)
    with pytest.raises(EngineError):
        ag.encode_prompt(engine, pipe, "a red fox", gp, ag.normalize({"embedding": "nope"}), plain)


def test_wrong_encoder_width_is_rejected(tmp_path, monkeypatch):
    from transformers import CLIPTextConfig, CLIPTextModel
    engine, pipe, gp, plain = encode_setup(tmp_path, monkeypatch)
    pipe.text_encoder = CLIPTextModel(CLIPTextConfig(vocab_size=100, hidden_size=32, intermediate_size=64,
                                                     num_hidden_layers=1, num_attention_heads=2))
    with pytest.raises(EngineError):
        ag.encode_prompt(engine, pipe, "a red fox", gp, ag.normalize({"embedding": "style"}), plain)


def test_state_copy_lifts_offloaded_weights_only_when_needed():
    class Encoder:
        lifted = False

        def state_dict(self):
            device = "cpu" if self.lifted else "meta"
            return {"w": torch.ones(2, 2, dtype=torch.float16, device=device)}

    te = Encoder()
    calls = []

    def access(operation):
        calls.append(1)
        te.lifted = True
        try:
            return operation()
        finally:
            te.lifted = False
    engine = types.SimpleNamespace(_with_embedding_encoder_access=access)
    state = ag.state_copy(engine, te)
    assert calls == [1] and state["w"].dtype == torch.float32 and bool((state["w"] == 1).all())
    te.lifted = True
    calls.clear()
    assert ag.state_copy(engine, te)["w"].shape == (2, 2) and calls == []  # resident weights: no lifting
    te.lifted = False
    with pytest.raises(EngineError):
        ag.state_copy(types.SimpleNamespace(), te)  # meta weights and no way to read them


# --- hook wiring ----------------------------------------------------------------------------------------------------

def test_hook_is_registered():
    from sdfx import dryrun, extensions, hooks
    assert "aesthetic_gradients" in hooks.KNOWN_HOOKS and "aesthetic_gradients" in extensions.EXTENSION_HOOKS
    assert "aesthetic_gradients" in dryrun.FakeEngine().features()
