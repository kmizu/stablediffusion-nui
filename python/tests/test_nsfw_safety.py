"""SD safety checker (index 83) and NSFWJS filter (index 231) of the nsfw_checker hook: pinned downloads, NSFWJS
input / levels / blur, the safety checker on a tiny CLIP model, and their place in the nsfw_checker workflows.

No test downloads anything: downloads use a fake opener, models are tiny or stubbed. The optional real-model tests
run only when the pinned files are already in SDFX_NSFW_CHECKER_CACHE.
"""
import hashlib
import io

import numpy as np
import pytest
from PIL import Image, ImageFilter

from sdfx import nsfw_checker, nsfw_safety
from sdfx.protocol import EngineError


def asset(data: bytes, filename="weights.bin"):
    return {"filename": filename, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


class Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def opener(data, urls):
    def open_(url, timeout=None):
        urls.append(url)
        return Response(data)
    return open_


# ------------------------------------------------------------------------------------------------ pins / downloads
def test_pinned_sources_match_the_upstream_models():
    assert nsfw_safety.SAFETY_REPO == "CompVis/stable-diffusion-safety-checker"
    assert nsfw_safety.SAFETY_REVISION == "cb41f3a270d63d454d385fc2e4f571c487c253c5"
    assert [a["filename"] for a in nsfw_safety.SAFETY_FILES] == ["config.json", "preprocessor_config.json",
                                                                 "pytorch_model.bin"]
    weight = nsfw_safety.SAFETY_FILES[2]
    assert (weight["bytes"], weight["sha256"]) == (
        1216067303, "64b8393f1afd5a0c1ed2aa5f341fa7c08286839a48f3743162a76a2835c808bd")
    assert (nsfw_safety.NSFWJS_REPO, nsfw_safety.NSFWJS_REVISION) == (
        "deepghs/imgutils-models", "35e0ed8f35ce0dbea674568161a48b31ba7d2a99")
    assert nsfw_safety.NSFWJS_FILE == {"filename": "nsfw/nsfwjs.onnx", "bytes": 10318774,
                                       "sha256": "510dce55edcd1cf8281cbe906dedb3f4963f8814bc1edfbd42d3bf261148df2d"}
    assert nsfw_safety.download_url("a/b", "rev", "nsfw/x.onnx") == "https://huggingface.co/a/b/resolve/rev/nsfw/x.onnx"
    assert nsfw_safety.NSFWJS_LABELS == ("drawings", "hentai", "neutral", "porn", "sexy")
    assert nsfw_safety.SAFETY_LEVELS == {"safe": ("hentai", "porn", "sexy"), "questionable": ("hentai", "porn"),
                                         "explicit": ()}
    assert nsfw_safety.NSFWJS_BLUR_RADIUS == 20


def test_download_is_verified_and_published_atomically(tmp_path):
    data = b"pinned model bytes" * 100
    urls = []
    path = nsfw_safety.materialize("o/r", "abc123", asset(data, "nsfw/m.onnx"), tmp_path, True, opener(data, urls))
    assert urls == ["https://huggingface.co/o/r/resolve/abc123/nsfw/m.onnx"]
    assert path == tmp_path / "o__r" / "abc123" / "m.onnx" and path.read_bytes() == data
    assert [p.name for p in path.parent.iterdir()] == ["m.onnx"], "no partial file may remain"
    # cached: no second download
    assert nsfw_safety.materialize("o/r", "abc123", asset(data, "nsfw/m.onnx"), tmp_path, True, opener(b"", urls)) == path
    assert len(urls) == 1


@pytest.mark.parametrize("served", [b"tampered bytes!!", b"x" * 5000])
def test_download_with_wrong_hash_or_size_is_rejected_and_removed(tmp_path, served):
    data = b"expected bytes!!"
    with pytest.raises(nsfw_safety.ModelUnavailable, match="size"):
        nsfw_safety.materialize("o/r", "rev", asset(data), tmp_path, True, opener(served, []))
    assert not any((tmp_path / "o__r" / "rev").iterdir())


def test_existing_file_with_wrong_hash_is_refused(tmp_path):
    data = b"expected bytes"
    folder = nsfw_safety.model_dir("o/r", "rev", tmp_path)
    folder.mkdir(parents=True)
    (folder / "weights.bin").write_bytes(b"something else")
    with pytest.raises(nsfw_safety.ModelUnavailable, match="refusing to load"):
        nsfw_safety.materialize("o/r", "rev", asset(data), tmp_path, True, opener(data, []))


def test_offline_refuses_to_download(tmp_path, monkeypatch):
    urls = []
    with pytest.raises(nsfw_safety.ModelUnavailable, match="not downloaded"):
        nsfw_safety.materialize("o/r", "rev", asset(b"abc"), tmp_path, False, opener(b"abc", urls))
    monkeypatch.setenv(nsfw_safety.OFFLINE_ENV, "1")
    with pytest.raises(nsfw_safety.ModelUnavailable):
        nsfw_safety.materialize("o/r", "rev", asset(b"abc"), tmp_path, None, opener(b"abc", urls))
    assert urls == []


def test_network_failure_is_a_model_error(tmp_path):
    def broken(url, timeout=None):
        raise OSError("connection refused")
    with pytest.raises(nsfw_safety.ModelUnavailable, match="connection refused"):
        nsfw_safety.materialize("o/r", "rev", asset(b"abc"), tmp_path, True, broken)


def test_cache_root_follows_the_environment(monkeypatch, tmp_path):
    monkeypatch.setenv(nsfw_safety.CACHE_ENV, str(tmp_path))
    assert nsfw_safety.cache_root() == tmp_path


# ------------------------------------------------------------------------------------------------ NSFWJS
class FakeSession:
    def __init__(self, scores):
        self.scores = np.asarray(scores, dtype=np.float32)
        self.calls = []

    def run(self, outputs, feeds):
        self.calls.append((outputs, {k: v.copy() for k, v in feeds.items()}))
        return [self.scores[None, :]]


def test_nsfwjs_input_is_nearest_224_nhwc_unit_range():
    img = Image.new("RGBA", (300, 100), (10, 20, 30, 40))
    img.putpixel((299, 99), (255, 0, 0, 255))
    x = nsfw_safety.nsfwjs_input(img)
    assert x.shape == (1, 224, 224, 3) and x.dtype == np.float32
    expected = np.asarray(img.convert("RGB").resize((224, 224), Image.NEAREST), dtype=np.float64) / 255.0
    assert np.array_equal(x[0], expected.astype(np.float32))
    assert np.allclose(x[0, 0, 0], [10 / 255, 20 / 255, 30 / 255])


def test_nsfwjs_runs_the_named_input_and_output():
    session = FakeSession([0.1, 0.1, 0.6, 0.1, 0.1])
    scores = nsfw_safety.nsfwjs_scores(Image.new("RGB", (64, 64)), session)
    assert session.calls[0][0] == ["dense_3"] and list(session.calls[0][1]) == ["input_1"]
    assert np.allclose(scores, [0.1, 0.1, 0.6, 0.1, 0.1])


@pytest.mark.parametrize("top,flags", [
    ("drawings", (False, False)), ("hentai", (True, True)), ("neutral", (False, False)), ("porn", (True, True)),
    ("sexy", (True, False))])
def test_nsfwjs_levels_flag_the_top_class(top, flags):
    scores = [0.1] * 5
    scores[nsfw_safety.NSFWJS_LABELS.index(top)] = 0.6
    for level, expected in zip(("safe", "questionable"), flags):
        flagged, label, _ = nsfw_safety.nsfwjs_classify(Image.new("RGB", (8, 8)), level, FakeSession(scores))
        assert (flagged, label) == (expected, top)


def test_explicit_level_never_flags_and_skips_classification():
    session = FakeSession([0, 0, 0, 1, 0])
    assert nsfw_safety.nsfwjs_classify(Image.new("RGB", (8, 8)), "explicit", session) == (False, None, None)
    assert session.calls == []
    with pytest.raises(ValueError, match="safety level"):
        nsfw_safety.check_level("strict")


def gradient(size=(64, 48)):
    arr = np.zeros((size[1], size[0], 3), dtype=np.uint8)
    arr[..., 0] = np.linspace(0, 255, size[0], dtype=np.uint8)[None, :]
    arr[: size[1] // 2, :, 1] = 255
    return Image.fromarray(arr)


def test_nsfwjs_blurs_flagged_images_with_radius_20_per_pass():
    img = gradient()
    flagged = FakeSession([0, 0, 0, 1, 0])
    once, labels = nsfw_safety.nsfwjs_censor(img, "safe", flagged)
    assert labels == ["porn"]
    assert once.tobytes() == img.filter(ImageFilter.GaussianBlur(20)).tobytes()
    twice, labels = nsfw_safety.nsfwjs_censor(img, "safe", flagged, passes=2)
    assert labels == ["porn", "porn"]
    assert twice.tobytes() == img.filter(ImageFilter.GaussianBlur(20)).filter(ImageFilter.GaussianBlur(20)).tobytes()
    clean = FakeSession([0, 0, 1, 0, 0])
    same, labels = nsfw_safety.nsfwjs_censor(img, "safe", clean, passes=2)
    assert same is img and labels == ["neutral"] and len(clean.calls) == 1


def test_force_flag_only_strengthens(monkeypatch):
    monkeypatch.setenv(nsfw_safety.FORCE_ENV, "nsfwjs")
    flagged, label, _ = nsfw_safety.nsfwjs_classify(Image.new("RGB", (8, 8)), "safe", FakeSession([0, 0, 1, 0, 0]))
    assert (flagged, label) == (True, "neutral")
    assert nsfw_safety.nsfwjs_classify(Image.new("RGB", (8, 8)), "explicit", FakeSession([1, 0, 0, 0, 0]))[0] is False


def test_real_nsfwjs_model_when_cached():
    path = nsfw_safety.model_dir(nsfw_safety.NSFWJS_REPO, nsfw_safety.NSFWJS_REVISION) / "nsfwjs.onnx"
    if not path.is_file():
        pytest.skip("pinned nsfwjs.onnx is not in the cache (not downloaded by tests)")
    pytest.importorskip("onnxruntime")
    session = nsfw_safety.nsfwjs_session(allow_download=False)
    assert [i.name for i in session.get_inputs()] == ["input_1"]
    assert [o.name for o in session.get_outputs()] == ["dense_3"]
    scores = nsfw_safety.nsfwjs_scores(Image.new("RGB", (512, 512), (255, 255, 255)), session)
    assert scores.shape == (5,) and abs(float(scores.sum()) - 1) < 1e-3
    flagged, label, _ = nsfw_safety.nsfwjs_classify(Image.new("RGB", (512, 512), (255, 255, 255)), "safe", session)
    assert not flagged and label in ("drawings", "neutral")


# ------------------------------------------------------------------------------------------------ SD safety checker
def tiny_safety(flag_all: bool):
    torch = pytest.importorskip("torch")
    pytest.importorskip("diffusers")
    from transformers import CLIPConfig, CLIPImageProcessor
    from diffusers.pipelines.stable_diffusion.safety_checker import StableDiffusionSafetyChecker
    torch.manual_seed(0)
    config = CLIPConfig(
        text_config=dict(hidden_size=16, intermediate_size=32, num_attention_heads=2, num_hidden_layers=1,
                         vocab_size=99, max_position_embeddings=16),
        vision_config=dict(hidden_size=16, intermediate_size=32, num_attention_heads=2, num_hidden_layers=1,
                           image_size=32, patch_size=8),
        projection_dim=8)
    model = StableDiffusionSafetyChecker(config).eval()
    with torch.no_grad():
        # cosine similarity is at most 1: a threshold of -2 flags everything, 2 flags nothing
        model.concept_embeds_weights.fill_(-2.0 if flag_all else 2.0)
        model.special_care_embeds_weights.fill_(2.0)
    processor = CLIPImageProcessor(size={"shortest_edge": 32}, crop_size={"height": 32, "width": 32})
    return processor, model


@pytest.mark.parametrize("flag_all", [False, True])
def test_safety_flags_follow_the_diffusers_checker(flag_all):
    model = tiny_safety(flag_all)
    images = [gradient(), Image.new("RGBA", (40, 70), (1, 2, 3, 4))]
    assert nsfw_safety.safety_flags(images, model) == [flag_all, flag_all]
    out, flagged = nsfw_safety.safety_censor(images[1], model)
    assert flagged is flag_all
    if flag_all:
        assert out.size == (40, 70) and out.mode == "RGBA"
        assert set(out.getdata()) == {(0, 0, 0, 255)}
    else:
        assert out is images[1]


def test_safety_special_care_adjustment_is_the_diffusers_one():
    torch = pytest.importorskip("torch")
    processor, model = tiny_safety(False)
    img = gradient()
    pixels = processor(images=[img], return_tensors="pt").pixel_values
    with torch.no_grad():
        embeds = model.visual_projection(model.vision_model(pixels)[1])
        cos = torch.nn.functional.cosine_similarity(embeds, model.concept_embeds).max().item()
        # concept threshold just above the score: not flagged alone, flagged with the 0.01 special-care adjustment
        model.concept_embeds_weights.fill_(cos + 0.005)
        assert nsfw_safety.safety_flags([img], (processor, model)) == [False]
        model.special_care_embeds_weights.fill_(-2.0)
        assert nsfw_safety.safety_flags([img], (processor, model)) == [True]


def test_safety_force_flag(monkeypatch):
    model = tiny_safety(False)
    monkeypatch.setenv(nsfw_safety.FORCE_ENV, "safety_checker, nsfwjs")
    assert nsfw_safety.safety_flags([gradient()], model) == [True]


def test_safety_model_loads_only_verified_pinned_files(tmp_path, monkeypatch):
    processor, model = tiny_safety(True)
    folder = nsfw_safety.model_dir(nsfw_safety.SAFETY_REPO, nsfw_safety.SAFETY_REVISION, tmp_path)
    model.save_pretrained(str(folder), safe_serialization=False)
    processor.save_pretrained(str(folder))
    names = ("config.json", "preprocessor_config.json", "pytorch_model.bin")
    monkeypatch.setattr(nsfw_safety, "SAFETY_FILES", tuple(asset((folder / n).read_bytes(), n) for n in names))
    monkeypatch.setattr(nsfw_safety, "_safety", {})
    loaded = nsfw_safety.safety_model(root=tmp_path, allow_download=False)
    assert nsfw_safety.safety_model(root=tmp_path, allow_download=False) is loaded
    assert nsfw_safety.safety_flags([gradient()], loaded) == [True]
    assert next(loaded[1].parameters()).device.type == "cpu"
    (folder / "preprocessor_config.json").write_bytes(b"{}")  # the weight itself is memory-mapped on Windows
    monkeypatch.setattr(nsfw_safety, "_safety", {})
    with pytest.raises(nsfw_safety.ModelUnavailable, match="refusing to load"):
        nsfw_safety.safety_model(root=tmp_path, allow_download=False)


# ------------------------------------------------------------------------------------------------ nsfw_checker glue
def test_generation_config_defaults_keep_nudenet_only():
    cfg = nsfw_checker.generation_config({})
    assert (cfg["nudenet"], cfg["safety_checker"], cfg["safety_checker_live_preview"], cfg["nsfwjs"],
            cfg["nsfwjs_safety_level"]) == (True, False, False, False, "safe")
    with pytest.raises(ValueError, match="safety level"):
        nsfw_checker.generation_config({"nsfwjs_safety_level": "strict"})
    assert not nsfw_checker.nsfwjs_active(nsfw_checker.generation_config({"nsfwjs": True,
                                                                          "nsfwjs_safety_level": "explicit"}))


@pytest.fixture
def stubs(monkeypatch):
    """Records every classifier call; the safety checker flags images whose red channel at (0, 0) is 255, NSFWJS
    flags images whose green channel at (0, 0) is 255 (top class porn, otherwise neutral)."""
    calls = []

    def flags(images, model=None):
        out = []
        for img in images:
            calls.append(("safety", img.convert("RGB").getpixel((0, 0))))
            out.append(img.convert("RGB").getpixel((0, 0))[0] >= 250)
        return out

    def scores(img, session=None):
        calls.append(("nsfwjs", img.convert("RGB").getpixel((0, 0)), img.mode))
        return np.array([0, 0, 0, 1, 0] if img.convert("RGB").getpixel((0, 0))[1] >= 250 else [0, 0, 1, 0, 0],
                        dtype=np.float32)

    monkeypatch.setattr(nsfw_safety, "safety_flags", flags)
    monkeypatch.setattr(nsfw_safety, "nsfwjs_scores", scores)
    monkeypatch.setattr(nsfw_safety, "safety_model", lambda **kw: calls.append(("load", "safety")))
    monkeypatch.setattr(nsfw_safety, "nsfwjs_session", lambda **kw: calls.append(("load", "nsfwjs")))
    monkeypatch.setattr(nsfw_checker, "session", lambda *a: calls.append(("load", "nudenet")))
    return calls


def box_detector(calls):
    def run(cfg, img, model_path=None, log=None):
        calls.append(("nudenet", img.convert("RGB").getpixel((0, 0))))
        mask = Image.new("1", img.size, 0)
        mask.paste(1, (0, 0, 4, 4))
        return mask
    return run


def cfg(**over):
    return nsfw_checker.generation_config(dict({"gen_filter_type": "Fill color", "fill_color": "#0000ff"}, **over))


def test_job_config_loads_exactly_the_enabled_models(stubs):
    nsfw_checker.job_config({"nsfw_checker": {}})
    nsfw_checker.job_config({"nsfw_checker": {"nudenet": False, "safety_checker": True}})
    nsfw_checker.job_config({"nsfw_checker": {"nudenet": False, "nsfwjs": True}})
    nsfw_checker.job_config({"nsfw_checker": {"nudenet": False, "nsfwjs": True, "nsfwjs_safety_level": "explicit"}})
    nsfw_checker.job_config({"nsfw_checker": {"gen_filter_type": "Disable", "live_preview_filter_type": "Disable",
                                              "safety_checker": True, "nsfwjs": True}})
    assert [c[1] for c in stubs] == ["nudenet", "safety", "nsfwjs", "safety", "nsfwjs"]


def test_job_config_maps_model_errors(monkeypatch):
    def missing(**kw):
        raise nsfw_safety.ModelUnavailable("not downloaded")
    monkeypatch.setattr(nsfw_safety, "safety_model", missing)
    monkeypatch.setattr(nsfw_safety, "nsfwjs_session", missing)
    for key, name in (("safety_checker", "SD safety checker"), ("nsfwjs", "NSFWJS filter")):
        with pytest.raises(EngineError, match=f"{name}: not downloaded") as err:
            nsfw_checker.job_config({"nsfw_checker": {"nudenet": False, key: True}})
        assert err.value.code == "unsupported"
    with pytest.raises(EngineError) as err:
        nsfw_checker.job_config({"nsfw_checker": {"nsfwjs_safety_level": "strict"}})
    assert err.value.code == "invalid_params"


def test_final_order_is_safety_nsfwjs_nudenet_then_nsfwjs_on_every_saved_file(stubs):
    img = Image.new("RGB", (32, 32), (0, 0, 0))
    img.paste((0, 255, 0), (0, 0, 2, 2))  # NSFWJS flags it, the safety checker does not
    out, aux = nsfw_checker.censor_final(img, cfg(safety_checker=True, nsfwjs=True, save_mask=True),
                                         detector=box_detector(stubs))
    kinds = [c[0] for c in stubs]
    assert kinds == ["safety", "nsfwjs", "nudenet", "nsfwjs", "nsfwjs", "nsfwjs"], stubs
    assert stubs[2][1] != (0, 255, 0), "NudeNet must see the NSFWJS-blurred image"
    assert [suffix for suffix, _ in aux] == ["-before-censor", "-censor-mask"]
    assert stubs[-1][2] == "L", "the saved mask passes the save-time NSFWJS check too"
    # the NudeNet box (blue) is on the final image
    assert out.getpixel((1, 1)) == (0, 0, 255)


def test_safety_checker_blacks_out_before_other_checks(stubs):
    img = Image.new("RGB", (16, 16), (255, 255, 0))
    out, aux = nsfw_checker.censor_final(img, cfg(nudenet=False, safety_checker=True, nsfwjs=True))
    assert set(out.getdata()) == {(0, 0, 0)} and aux == []
    assert [c[0] for c in stubs] == ["safety", "nsfwjs", "nsfwjs"]
    assert stubs[1][1] == (0, 0, 0), "NSFWJS sees the black image"


def test_unflagged_images_are_unchanged_and_nudenet_can_be_off(stubs):
    img = Image.new("RGB", (16, 16), (5, 6, 7))
    out, aux = nsfw_checker.censor_final(img, cfg(nudenet=False, safety_checker=True, nsfwjs=True),
                                         detector=box_detector(stubs))
    assert out is img and aux == []
    assert "nudenet" not in [c[0] for c in stubs]


def test_explicit_level_and_disabled_checkers_do_nothing(stubs):
    img = Image.new("RGB", (16, 16), (255, 255, 255))
    out, aux = nsfw_checker.censor_final(img, cfg(nudenet=False, nsfwjs=True, nsfwjs_safety_level="explicit"))
    assert out is img and stubs == []


def test_final_errors_name_the_checker(monkeypatch, stubs):
    def broken(images, model=None):
        raise OSError("model vanished")
    monkeypatch.setattr(nsfw_safety, "safety_flags", broken)
    with pytest.raises(EngineError, match=r"] SD safety checker: model vanished"):
        nsfw_checker.censor_final(Image.new("RGB", (8, 8)), cfg(safety_checker=True))


def test_preview_config_and_censor(stubs):
    def preview(**over):
        return nsfw_checker.preview_config({"nsfw_checker": dict({"live_preview_filter_type": "Disable",
                                                                  "nudenet": False}, **over)})
    assert preview() is None
    assert preview(safety_checker=True) is None, "upstream index 83 does not check previews"
    assert preview(safety_checker=True, safety_checker_live_preview=True) is not None
    assert preview(nsfwjs=True) is not None and preview(nsfwjs=True, nsfwjs_safety_level="explicit") is None
    flagged = Image.new("RGB", (16, 16), (255, 255, 0))
    out = nsfw_checker.censor_preview(flagged, preview(safety_checker=True, safety_checker_live_preview=True,
                                                       nsfwjs=True))
    assert set(out.getdata()) == {(0, 0, 0)}
    assert [c[0] for c in stubs] == ["safety", "nsfwjs"], "one NSFWJS pass on previews"
    stubs.clear()
    green = Image.new("RGB", (16, 16), (0, 255, 0))
    blurred = nsfw_checker.censor_preview(green, preview(nsfwjs=True))
    assert blurred is not green and [c[0] for c in stubs] == ["nsfwjs"]
    stubs.clear()
    assert nsfw_checker.censor_preview(flagged, preview(safety_checker=True, nsfwjs=True)) is not flagged
    assert [c[0] for c in stubs] == ["nsfwjs"], "the safety checker skips previews unless asked"


def test_preview_b64_round_trip_keeps_unflagged_previews(stubs):
    import base64
    from sdfx.images import to_jpeg_b64
    data = to_jpeg_b64(Image.new("RGB", (16, 16), (5, 6, 7)), max_side=16)
    conf = nsfw_checker.preview_config({"nsfw_checker": {"nudenet": False, "nsfwjs": True}})
    assert nsfw_checker.censor_preview_b64(data, conf) == data
    flagged = to_jpeg_b64(gradient(), max_side=64)
    assert nsfw_checker.censor_preview_b64(flagged, conf) != flagged
    with Image.open(io.BytesIO(base64.b64decode(nsfw_checker.censor_preview_b64(flagged, conf)))) as image:
        assert image.size == (64, 48)


def test_tool_nsfwjs_reports_scores_and_blurs_once(stubs):
    img = gradient()
    img.putpixel((0, 0), (0, 255, 0))
    out, extras = nsfw_checker.censor_tool(img, {"checker": "nsfwjs"})
    assert out.tobytes() == img.filter(ImageFilter.GaussianBlur(20)).tobytes()
    assert extras["is_nsfw"] == 1 and extras["censored"] == 1 and extras["label"] == "porn"
    assert extras["safety_level"] == "safe" and extras["score_porn"] == 1.0
    assert len([c for c in stubs if c[0] == "nsfwjs"]) == 1
    kept, extras = nsfw_checker.censor_tool(img, {"checker": "nsfwjs", "report_only": True})
    assert kept.tobytes() == img.tobytes() and kept is not img and (extras["is_nsfw"], extras["censored"]) == (1, 0)
    _, extras = nsfw_checker.censor_tool(img, {"checker": "nsfwjs", "safety_level": "explicit"})
    assert extras["is_nsfw"] == 0 and extras["label"] == "" and "score_porn" not in extras
    with pytest.raises(ValueError, match="checker"):
        nsfw_checker.censor_tool(img, {"checker": "clip"})
    with pytest.raises(ValueError, match="safety level"):
        nsfw_checker.censor_tool(img, {"checker": "nsfwjs", "safety_level": "x"})


def test_tool_safety_checker(stubs):
    flagged = Image.new("RGBA", (12, 10), (255, 0, 0, 128))
    out, extras = nsfw_checker.censor_tool(flagged, {"checker": "safety_checker"})
    assert set(out.getdata()) == {(0, 0, 0, 255)} and (extras["is_nsfw"], extras["censored"]) == (1, 1)
    kept, extras = nsfw_checker.censor_tool(flagged, {"checker": "safety_checker", "report_only": "true"})
    assert kept.tobytes() == flagged.tobytes() and (extras["is_nsfw"], extras["censored"]) == (1, 0)
    clean = Image.new("RGB", (12, 10), (1, 1, 1))
    out, extras = nsfw_checker.censor_tool(clean, {"checker": "safety_checker"})
    assert out.tobytes() == clean.tobytes() and extras["is_nsfw"] == 0


def test_tool_model_errors_are_engine_errors(monkeypatch):
    def missing(img, session=None):
        raise nsfw_safety.ModelUnavailable("offline")
    monkeypatch.setattr(nsfw_safety, "nsfwjs_scores", missing)
    with pytest.raises(EngineError, match=r"] NSFWJS filter: offline"):
        nsfw_checker.censor_tool(Image.new("RGB", (8, 8)), {"checker": "nsfwjs"})
