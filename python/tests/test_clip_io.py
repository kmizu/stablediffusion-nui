import math
from types import SimpleNamespace

import pytest

from sdfx import clip_io, prompt
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")

BOS, EOS = 1000, 1001
DIM = 8


class FakeTokenizer:
    bos_token_id, eos_token_id, pad_token_id = BOS, EOS, EOS

    def __init__(self):
        self.words = {}

    def _id(self, word):
        return self.words.setdefault(word, 10 + len(self.words))

    def __call__(self, text, add_special_tokens=False, truncation=False):
        if text == ",":
            return SimpleNamespace(input_ids=[1])
        ids = [1 if w == "," else self._id(w) for w in text.replace(",", " , ").split()]
        return SimpleNamespace(input_ids=ids)

    def convert_ids_to_tokens(self, ids):
        names = {v: k + "</w>" for k, v in self.words.items()}
        names.update({BOS: "<|startoftext|>", EOS: "<|endoftext|>", 1: ",</w>"})
        return [names[i] for i in ids]


class FakeEncoder(torch.nn.Module):
    def __init__(self, width=DIM, layers=3):
        super().__init__()
        torch.manual_seed(5)
        self.embed = torch.nn.Embedding(12000, width)
        self.position = torch.nn.Embedding(80, width)
        self.mix = torch.nn.ModuleList([torch.nn.Linear(width, width) for _ in range(layers)])
        self.config = SimpleNamespace(num_hidden_layers=layers, hidden_size=width)
        self.text_model = SimpleNamespace(final_layer_norm=torch.nn.LayerNorm(width))
        self.anchor = torch.nn.Parameter(torch.zeros(1))

    def forward(self, ids, output_hidden_states=True):
        h = self.embed(ids) + self.position(torch.arange(ids.shape[1]))[None]
        states = [h]
        for layer in self.mix:
            h = torch.tanh(layer(h)) + h
            states.append(h)
        return SimpleNamespace(last_hidden_state=h, hidden_states=tuple(states), text_embeds=h[:, 0])


def engine():
    pipe = SimpleNamespace(tokenizer=FakeTokenizer(), text_encoder=FakeEncoder())
    gp = SimpleNamespace(clip_skip=1, prompt="", negative_prompt="")
    eng = SimpleNamespace(pipe=pipe, sdxl=False, kind="sd15", dtype=torch.float32, gp=gp)

    def plain(pos, neg, gp, bs):
        pe = prompt.encode_sd15(pipe, pos, gp.clip_skip)
        ne = prompt.encode_sd15(pipe, neg, gp.clip_skip)
        if pe.shape[1] != ne.shape[1]:
            empty = prompt.encode_sd15(pipe, "", gp.clip_skip)
            pe, ne = prompt.pad_to_match(pe, ne, empty)
        return {"prompt_embeds": pe.repeat(bs, 1, 1), "negative_prompt_embeds": ne.repeat(bs, 1, 1)}
    eng._encode_plain = plain
    return eng


@pytest.fixture
def folder(tmp_path, monkeypatch):
    monkeypatch.setenv(clip_io.DIR_ENV, str(tmp_path))
    return tmp_path


# ------------------------------------------------------------------------------------------------ configuration
def test_normalize_defaults_and_validation():
    cfg = clip_io.normalize({})
    assert cfg["positive_mode"] == "simple" and cfg["negative_mode"] == "off" and not cfg["input_enabled"]
    assert clip_io.normalize({"positive_mode": "Directive", "transpose": "true"})["transpose"] is True
    with pytest.raises(EngineError, match="positive_mode"):
        clip_io.normalize({"positive_mode": "python"})
    with pytest.raises(EngineError, match="format"):
        clip_io.normalize({"format": "npy"})
    with pytest.raises(EngineError, match="output name"):
        clip_io.normalize({"output_enabled": True})


def test_file_names_cannot_leave_the_folder(folder):
    for name in ("../x", "a/../../x", "/etc/passwd", "C:/x", ""):
        with pytest.raises(EngineError):
            clip_io._inside(folder, name)
    (folder / "sub").mkdir()
    assert clip_io._inside(folder, "sub/x.csv") == (folder / "sub" / "x.csv").resolve()


def test_resolve_tries_extensions_in_documented_order(folder):
    (folder / "a.pt").write_bytes(b"x")
    assert clip_io.resolve_file("a").name == "a.pt"
    (folder / "a.csv").write_text("1")
    assert clip_io.resolve_file("a").name == "a.csv"
    (folder / "a").write_text("1")
    assert clip_io.resolve_file("a").name == "a"
    with pytest.raises(EngineError, match="not found"):
        clip_io.resolve_file("missing")
    assert clip_io.list_files(folder) == ["a.csv", "a.pt"]


# ------------------------------------------------------------------------------------------------ syntax
def test_simple_syntax_files_quotes_and_prompts():
    pieces = clip_io.parse_input('one  "two words" \'three four\' """a cat, (blue:1.2)""" five.pt', "simple")
    assert [(p.kind, p.text) for p in pieces] == [("file", "one"), ("file", "two words"), ("file", "three four"),
                                                   ("prompt", "a cat, (blue:1.2)"), ("file", "five.pt")]
    assert clip_io.parse_input("", "simple") == []
    assert [p.text for p in clip_io.parse_input("?eval(i)", "simple")] == ["?eval(i)"]  # plain name outside directive mode


def test_unterminated_quotes_are_errors():
    for text in ('"open', "'''open", 'a """b'):
        with pytest.raises(EngineError, match="unterminated"):
            clip_io.parse_input(text, "simple")


def test_directive_syntax_names_orders_and_nesting():
    pieces = clip_io.parse_input('x ?EVAL_5(i*(1+t)) y ?eval(o) ?prompt("""a ) b""", 2)', "directive")
    assert [(p.kind, p.name, p.order, p.text) for p in pieces] == [
        ("file", "", 0, "x"), ("directive", "eval", 5, "i*(1+t)"), ("file", "", 0, "y"),
        ("directive", "eval", 0, "o"), ("directive", "prompt", 0, '"""a ) b""", 2')]
    with pytest.raises(EngineError, match="unbalanced"):
        clip_io.parse_input("?eval(i*(2)", "directive")


def test_prompt_directive_arguments():
    items = clip_io.parse_prompt_directive("'''x''',,True")["items"]
    assert items == [("x", 1, True)]
    items = clip_io.parse_prompt_directive('("a", \'b\', """c"""), [2,1]')["items"]
    assert items == [("a", 2, False), ("b", 1, False), ("c", 1, False)]  # last clip_skip is broadcast
    assert clip_io.parse_prompt_directive('"p", clip_skip=2, no_padding=False')["items"] == [("p", 2, False)]
    for bad in ("", "'p', clip_skip=0", "'p', other=1", "'p',1,True,4", "1", "'p', clip_skip=[]"):
        with pytest.raises(EngineError):
            clip_io.parse_prompt_directive(bad)


# ------------------------------------------------------------------------------------------------ eval
def test_eval_elementwise_arithmetic_and_indices():
    i = torch.arange(6, dtype=torch.float32).reshape(2, 3)
    assert torch.equal(clip_io.evaluate_expression("i*2+1", i), i * 2 + 1)
    assert torch.equal(clip_io.evaluate_expression("t", i), torch.tensor([[0, 0, 0], [1, 1, 1.]]))
    assert torch.equal(clip_io.evaluate_expression("d", i), torch.tensor([[0, 1, 2], [0, 1, 2.]]))
    assert torch.allclose(clip_io.evaluate_expression("sin(pi*d/2)", i), torch.sin(math.pi * torch.tensor([0, 1, 2.]) / 2).expand(2, 3))
    assert torch.equal(clip_io.evaluate_expression("i if t == 0 else 0", i), torch.tensor([[0, 1, 2], [0, 0, 0.]]))
    assert torch.equal(clip_io.evaluate_expression("max(i, 3)", i), torch.tensor([[3, 3, 3], [3, 4, 5.]]))
    assert torch.equal(clip_io.evaluate_expression("clamp(i, 1, 4)", i), torch.tensor([[1, 1, 2], [3, 4, 4.]]))
    assert torch.equal(clip_io.evaluate_expression("torch.abs(-i) + 0*o", i), i)
    assert torch.equal(clip_io.evaluate_expression("5", i), torch.full((2, 3), 5.0))
    assert torch.equal(clip_io.evaluate_expression("(i > 1) and (i < 5)", i), torch.tensor([[0, 0, 1], [1, 1, 0.]]))


@pytest.mark.parametrize("expression", [
    "__import__('os').system('x')", "open('f')", "i.__class__", "[1][0]", "lambda: 1", "'text'", "torch.load('x')",
    "unknown", "i[0]", "exec('1')", "eval('1')", "(1).real", "sin(i, i)", "x = 1", "", "i" * 3000,
    "i" + "+i" * 500])
def test_eval_rejects_anything_but_arithmetic(expression):
    with pytest.raises(EngineError):
        clip_io.evaluate_expression(expression, torch.ones(2, 3))


def test_eval_rejects_non_finite_results():
    with pytest.raises(EngineError, match="NaN"):
        clip_io.evaluate_expression("log(-1)", torch.ones(2, 2))
    with pytest.raises(EngineError, match="NaN"):
        clip_io.evaluate_expression("1/0", torch.ones(2, 2))


# ------------------------------------------------------------------------------------------------ files
OPTIONS = dict(transpose=False, token_strings=False)


def test_csv_round_trip_all_layouts(folder):
    matrix = torch.arange(15, dtype=torch.float32).reshape(3, 5) / 7
    tokens = ["a</w>", "12", "<|endoftext|>"]
    for transpose in (False, True):
        for strings in (False, True):
            path = folder / f"m{int(transpose)}{int(strings)}.csv"
            clip_io.write_conditioning(path, matrix, tokens, None, dict(transpose=transpose, token_strings=strings))
            back = clip_io.read_csv_matrix(path, dim=5)
            assert torch.allclose(back, matrix, atol=1e-7), (transpose, strings)
    text = (folder / "m01.csv").read_text().splitlines()
    assert text[0].startswith("a</w>,") and text[1].startswith("'12,")  # numeric-looking token strings are prefixed
    assert (folder / "m11.csv").read_text().splitlines()[0] == "a</w>,'12,<|endoftext|>"


def test_ambiguous_square_matrix_defaults_to_rows_as_tokens(folder):
    matrix = torch.arange(16, dtype=torch.float32).reshape(4, 4)
    path = folder / "sq.csv"
    clip_io.write_conditioning(path, matrix, [], None, OPTIONS)
    assert torch.equal(clip_io.read_csv_matrix(path, dim=4), matrix)
    clip_io.write_conditioning(path, matrix, list("abcd"), None, dict(transpose=True, token_strings=True))
    assert torch.equal(clip_io.read_csv_matrix(path, dim=4), matrix)  # the header row says transposed


def test_csv_errors(folder):
    path = folder / "bad.csv"
    path.write_text("1,2,3\n4,5\n")
    with pytest.raises(EngineError, match="different numbers"):
        clip_io.read_csv_matrix(path)
    path.write_text("1,2,x\n4,5,6\n")
    with pytest.raises(EngineError, match="not a number"):
        clip_io.read_csv_matrix(path)
    path.write_text("")
    with pytest.raises(EngineError, match="empty"):
        clip_io.read_csv_matrix(path)
    path.write_text("1,2,3\n4,5,6\n")
    with pytest.raises(EngineError, match="does not fit"):
        clip_io.read_csv_matrix(path, dim=4)


def test_pt_round_trip_tensor_and_sdxl_dict(folder):
    matrix = torch.randn(4, 6)
    path = folder / "t.pt"
    clip_io.write_conditioning(path, matrix, [], None, OPTIONS)
    back, pooled = clip_io.read_conditioning(path, 6)
    assert torch.equal(back, matrix) and pooled is None
    pool = torch.randn(1, 3)
    clip_io.write_conditioning(path, matrix, list("abcd"), pool, dict(transpose=True, token_strings=True))
    back, pooled = clip_io.read_conditioning(path, 6)
    assert torch.equal(back, matrix) and torch.equal(pooled, pool)  # transpose does not apply to .pt
    torch.save({"unrelated": 1}, path)
    with pytest.raises(EngineError, match="tensor"):
        clip_io.read_conditioning(path, 6)
    path.write_bytes(b"garbage")
    with pytest.raises(EngineError, match="could not be loaded"):
        clip_io.read_conditioning(path, 6)


def test_pt_files_cannot_run_pickled_code(folder):
    import pickle

    class Evil:
        def __reduce__(self):
            return (exec, ("raise SystemExit",))
    path = folder / "evil.pt"
    path.write_bytes(pickle.dumps(Evil()))
    with pytest.raises(EngineError, match="could not be loaded"):
        clip_io.read_conditioning(path, 6)


# ------------------------------------------------------------------------------------------------ encoding
def test_default_options_equal_generation_conditioning():
    eng = engine()
    text = "a cat, (blue:1.3) dog"
    matrix, pooled, strings = clip_io.encode_text(eng, text, 1, tokens=True)
    assert torch.equal(matrix, prompt.encode_sd15(eng.pipe, text, 1)[0])
    assert matrix.shape == (77, DIM) and pooled is None
    assert strings[0] == "<|startoftext|>" and strings[1] == "a</w>" and strings[-1] == "<|endoftext|>"
    assert len(strings) == 77


def test_no_special_tokens_drops_bos_eos_and_padding():
    eng = engine()
    matrix, _, strings = clip_io.encode_text(eng, "a cat dog", 1, special=False, tokens=True)
    assert matrix.shape == (3, DIM) and strings == ["a</w>", "cat</w>", "dog</w>"]
    ids = [eng.pipe.tokenizer.words[w] for w in ("a", "cat", "dog")]
    with torch.no_grad():
        expected = eng.pipe.text_encoder(torch.tensor([ids])).last_hidden_state[0]
    assert torch.allclose(matrix, expected, atol=1e-6)  # weights are 1 so the mean rescale is the identity
    with pytest.raises(EngineError, match="no tokens"):
        clip_io.encode_text(eng, "", 1, special=False)


def test_ignore_emphasis_and_bypass_normalization():
    eng = engine()
    weighted, _, _ = clip_io.encode_text(eng, "a (cat:2) dog", 1)
    plain, _, _ = clip_io.encode_text(eng, "a cat dog", 1)
    ignored, _, _ = clip_io.encode_text(eng, "a (cat:2) dog", 1, ignore_emphasis=True)
    assert torch.allclose(ignored, plain, atol=1e-6) and not torch.allclose(weighted, plain)
    raw, _, _ = clip_io.encode_text(eng, "a (cat:2) dog", 1, normalize=False)
    assert not torch.allclose(raw, weighted)
    assert torch.allclose(raw[2], plain[2] * 2, atol=1e-5)  # the emphasised token is exactly doubled
    assert math.isclose(float(weighted.mean()), float(plain.mean()), rel_tol=1e-4)  # normalisation keeps the mean


def test_long_prompts_make_several_chunks_and_break_works():
    eng = engine()
    long = " ".join(f"w{k}" for k in range(100))
    assert clip_io.encode_text(eng, long, 1)[0].shape[0] == 154
    assert clip_io.encode_text(eng, long, 1, special=False)[0].shape[0] == 100
    assert clip_io.encode_text(eng, "a BREAK b", 1)[0].shape[0] == 154
    assert clip_io.encode_text(eng, "a BREAK b", 1, special=False)[0].shape[0] == 2
    with pytest.raises(EngineError, match="longer than"):
        clip_io.encode_text(eng, " ".join(f"w{k}" for k in range(5000)), 1)


def test_clip_skip_changes_the_layer():
    eng = engine()
    assert not torch.allclose(clip_io.encode_text(eng, "a cat", 1)[0], clip_io.encode_text(eng, "a cat", 2)[0])


def test_export_writes_once_unless_overwrite(folder):
    eng = engine()
    gp = eng.gp
    cfg = clip_io.normalize({"output_enabled": True, "output_name": "cat", "token_strings": True, "format": "csv"})
    path = clip_io.export(eng, cfg, "a cat", "cat", gp)
    assert path == (folder / "cat.csv").resolve()
    lines = path.read_text().splitlines()
    assert len(lines) == 77 and lines[0].startswith("<|startoftext|>,") and len(lines[0].split(",")) == DIM + 1
    assert clip_io.export(eng, cfg, "a dog", "cat", gp) is None  # exists, overwrite off
    assert path.read_text().splitlines() == lines
    cfg = clip_io.normalize({"output_enabled": True, "output_name": "cat", "overwrite": True, "transpose": True})
    clip_io.export(eng, cfg, "a dog", "cat", gp)
    assert len(path.read_text().splitlines()) == DIM
    cfg = clip_io.normalize({"output_enabled": True, "output_name": "cat.pt", "no_special_tokens": True})
    pt = clip_io.export(eng, cfg, "a cat", "cat.pt", gp)
    assert pt.name == "cat.pt" and clip_io.read_conditioning(pt, DIM)[0].shape == (2, DIM)


def test_export_rejects_escaping_names(folder):
    eng = engine()
    cfg = clip_io.normalize({"output_enabled": True, "output_name": "../x"})
    with pytest.raises(EngineError):
        clip_io.export(eng, cfg, "a cat", "../x", eng.gp)
    assert not (folder.parent / "x.csv").exists()


# ------------------------------------------------------------------------------------------------ input
def test_export_then_input_reproduces_the_conditioning_exactly(folder):
    eng = engine()
    gp = eng.gp
    gp.prompt, gp.negative_prompt = "cat.csv", "plain negative text"
    original = prompt.encode_sd15(eng.pipe, "a cat, (blue:1.3) dog", 1)
    clip_io.export(eng, clip_io.normalize({"output_enabled": True, "output_name": "cat"}), "a cat, (blue:1.3) dog", "cat", gp)
    cfg = clip_io.normalize({"input_enabled": True})
    modes = clip_io.applies(cfg, gp.prompt, gp.negative_prompt, gp)
    assert modes == ("simple", "off")
    out = clip_io.encode_input(eng, cfg, gp.prompt, gp.negative_prompt, gp, 2, modes)
    assert out["prompt_embeds"].shape == (2, 77, DIM)
    assert torch.allclose(out["prompt_embeds"][0], original[0], atol=1e-6)
    assert torch.equal(out["negative_prompt_embeds"][0], prompt.encode_sd15(eng.pipe, "plain negative text", 1)[0])


def test_input_concatenates_pads_and_mixes_prompts(folder):
    eng = engine()
    gp = eng.gp
    a, b = torch.randn(77, DIM), torch.randn(5, DIM)
    clip_io.write_conditioning(folder / "a.pt", a, [], None, OPTIONS)
    clip_io.write_conditioning(folder / "my b.csv", b, [], None, OPTIONS)
    gp.prompt, gp.negative_prompt = 'a "my b" """a cat"""', "a"
    cfg = clip_io.normalize({"input_enabled": True, "negative_mode": "simple"})
    modes = clip_io.applies(cfg, gp.prompt, gp.negative_prompt, gp)
    out = clip_io.encode_input(eng, cfg, gp.prompt, gp.negative_prompt, gp, 1, modes)
    cat = prompt.encode_sd15(eng.pipe, "a cat", 1)[0]
    positive = out["prompt_embeds"][0]
    assert positive.shape[0] == 77 + 5 + 77
    assert torch.allclose(positive[:77], a, atol=1e-6) and torch.allclose(positive[77:82], b, atol=1e-5)
    assert torch.allclose(positive[82:], cat, atol=1e-6)
    negative = out["negative_prompt_embeds"][0]
    assert negative.shape == positive.shape and torch.allclose(negative[:77], a, atol=1e-6)
    empty = prompt.encode_sd15(eng.pipe, "", 1)[0]
    assert torch.allclose(negative[77:154], empty, atol=1e-6) and torch.allclose(negative[154:159], empty[:5], atol=1e-6)


def test_input_replaces_only_the_generations_own_prompts(folder):
    gp = engine().gp
    gp.prompt, gp.negative_prompt = "x", "y"
    cfg = clip_io.normalize({"input_enabled": True, "negative_mode": "directive"})
    assert clip_io.applies(cfg, "x", "y", gp) == ("simple", "directive")
    assert clip_io.applies(cfg, "face", "y", gp) == ("off", "directive")
    assert clip_io.applies(cfg, "x", "other", gp) == ("simple", "off")
    assert clip_io.applies(clip_io.normalize({}), "x", "y", gp) == ("off", "off")
    assert clip_io.applies(None, "x", "y", gp) == ("off", "off")


def test_input_directives_order_and_prompt_directive(folder):
    eng = engine()
    gp = eng.gp
    base = torch.ones(3, DIM)
    clip_io.write_conditioning(folder / "ones.csv", base, [], None, OPTIONS)
    text = "ones ?eval_2(i*10) ?eval_1(i+1) ?prompt('''a cat''', 1, True)"
    cond, pooled = clip_io.build_side(eng, text, "directive", gp, DIM)
    cat = prompt.encode_sd15(eng.pipe, "a cat", 1)  # padded to 77
    tokens = clip_io.encode_text(eng, "a cat", 1, special=False)[0]
    assert cond.shape == (1, 3 + 2, DIM) and tokens.shape[0] == 2
    # order 1 (+1) runs before order 2 (*10); the whole concatenation is transformed
    expected = torch.cat([base, tokens]) + 1
    assert torch.allclose(cond[0], expected * 10, atol=1e-4) and pooled is None
    assert cat.shape[1] == 77


def test_input_errors(folder):
    eng = engine()
    gp = eng.gp
    with pytest.raises(EngineError, match="names no conditioning"):
        clip_io.build_side(eng, "   ", "simple", gp, DIM)
    with pytest.raises(EngineError, match="not found"):
        clip_io.build_side(eng, "nothing", "simple", gp, DIM)
    clip_io.write_conditioning(folder / "wide.csv", torch.ones(2, 16), [], None, OPTIONS)
    with pytest.raises(EngineError, match="does not fit"):
        clip_io.build_side(eng, "wide", "simple", gp, DIM)
    with pytest.raises(EngineError, match="unknown directive"):
        clip_io.build_side(eng, "?exec(1)", "directive", gp, DIM)
    with pytest.raises(EngineError):
        clip_io.build_side(eng, "../outside", "simple", gp, DIM)
    with pytest.raises(EngineError, match="no conditioning"):
        clip_io.build_side(eng, "?eval(i)", "directive", gp, DIM)


def test_sdxl_pooled_comes_from_the_file_or_the_empty_prompt(folder, monkeypatch):
    eng = engine()
    eng.sdxl = True
    gp = eng.gp
    gp.prompt, gp.negative_prompt = "p", "n"
    pool = torch.full((1, 3), 7.0)
    clip_io.write_conditioning(folder / "p.pt", torch.ones(4, DIM), ["a"] * 4, pool, OPTIONS)
    clip_io.write_conditioning(folder / "n.csv", torch.ones(4, DIM), [], None, OPTIONS)
    empty_pool = torch.full((1, 3), -1.0)
    monkeypatch.setattr(prompt, "encode_sdxl", lambda pipe, text, skip: (torch.zeros(1, 77, DIM), empty_pool))
    eng._encode_plain = lambda pos, neg, gp, bs: {
        "prompt_embeds": torch.zeros(1, 77, DIM), "negative_prompt_embeds": torch.zeros(1, 77, DIM),
        "pooled_prompt_embeds": torch.zeros(1, 3), "negative_pooled_prompt_embeds": torch.zeros(1, 3)}
    cfg = clip_io.normalize({"input_enabled": True, "negative_mode": "simple"})
    out = clip_io.encode_input(eng, cfg, "p", "n", gp, 2, ("simple", "simple"))
    assert torch.equal(out["pooled_prompt_embeds"], pool.repeat(2, 1))
    assert torch.equal(out["negative_pooled_prompt_embeds"], empty_pool.repeat(2, 1))
    assert out["prompt_embeds"].shape == (2, 4, DIM)


# ------------------------------------------------------------------------------------------------ engine wiring
def wired(hooks):
    pytest.importorskip("diffusers")
    from types import MethodType
    from sdfx.engine import Engine
    eng = engine()
    eng.warnings = []
    eng._embedding_merge_ctx = SimpleNamespace(warn=eng.warnings.append)
    eng._clip_io_done = set()
    eng._clip_io_export = MethodType(Engine._clip_io_export, eng)
    eng._encode = lambda pos, neg, gp, bs: eng._encode_plain(pos, neg, gp, bs)
    eng.gp.hooks = hooks
    eng.gp.steps = 20
    return eng, MethodType(Engine._encode_scheduled_unmerged, eng)


def test_engine_exports_once_per_request_and_not_for_other_prompts(folder):
    eng, encode = wired({"clip_io": {"output_enabled": True, "output_name": "out", "negative_output_name": "neg"}})
    eng.gp.prompt, eng.gp.negative_prompt = "a cat [dog:cat:10]", "bad"
    encode("face", "other", eng.gp, 1, 20)  # an ADetailer-style prompt is not exported
    assert not (folder / "out.csv").exists() and not (folder / "neg.csv").exists()
    encode(eng.gp.prompt, eng.gp.negative_prompt, eng.gp, 1, 20)
    assert (folder / "neg.csv").exists()
    expected = prompt.encode_sd15(eng.pipe, "a cat dog", 1)[0]  # the first step of the schedule
    assert torch.allclose(clip_io.read_conditioning(folder / "out.csv", DIM)[0], expected, atol=1e-6)
    (folder / "out.csv").write_text("1")
    encode(eng.gp.prompt, eng.gp.negative_prompt, eng.gp, 1, 20)  # same request: not exported twice
    assert (folder / "out.csv").read_text() == "1" and eng.warnings == []
    eng._clip_io_done = set()
    encode(eng.gp.prompt, eng.gp.negative_prompt, eng.gp, 1, 20)  # next request: exists and overwrite is off
    assert (folder / "out.csv").read_text() == "1" and "already exists" in eng.warnings[0]


def test_engine_input_replaces_the_prompt_and_rejects_bad_settings(folder):
    clip_io.write_conditioning(folder / "k.pt", torch.ones(3, DIM), [], None, OPTIONS)
    eng, encode = wired({"clip_io": {"input_enabled": True}})
    eng.gp.prompt, eng.gp.negative_prompt = "k", "bad"
    out = encode("k", "bad", eng.gp, 2, 20)
    assert out["prompt_embeds"].shape == (2, 77, DIM)
    assert torch.equal(out["prompt_embeds"][1, :3], torch.ones(3, DIM))
    eng.gp.hooks = {"clip_io": {"input_enabled": True, "positive_mode": "bogus"}}
    with pytest.raises(EngineError):
        encode("k", "bad", eng.gp, 1, 20)
