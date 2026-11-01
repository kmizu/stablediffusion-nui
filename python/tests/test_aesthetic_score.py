"""Aesthetic scoring (index 95 / 96 / 97): preprocessing, tiny random CLIP / BEiT models through the real loaders,
output fields, settings validation, the image_tool entry and the generation control flow."""
import hashlib
import json
import math
from types import SimpleNamespace

import pytest

torch = pytest.importorskip('torch')
transformers = pytest.importorskip('transformers')
import numpy as np
from PIL import Image
from sdfx import aesthetic_score as a
from sdfx import nsfw_safety
from sdfx.protocol import EngineError


# ------------------------------------------------------------------------------------------ tiny model cache
def asset(path):
    data = path.read_bytes()
    return {"filename": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


@pytest.fixture
def cache(tmp_path, monkeypatch):
    """A cache holding tiny random CLIP / BEiT models and predictor heads under the pinned layout; the pinned
    tables are patched to the tiny files' sizes and hashes."""
    from transformers import BeitConfig, BeitForImageClassification, BeitImageProcessor, CLIPVisionConfig, CLIPVisionModelWithProjection
    root = tmp_path / "cache"
    monkeypatch.setenv(a.CACHE_ENV, str(root))
    monkeypatch.setenv(nsfw_safety.OFFLINE_ENV, "1")
    a.clear_cache()
    torch.manual_seed(0)

    def folder(spec):
        directory = nsfw_safety.model_dir(spec[0], spec[1], root)
        directory.mkdir(parents=True)
        return directory
    patched = {}
    for which, dim, ext in (("l14", 768, "safetensors"), ("b16", 512, "bin")):
        spec = a.CLIPS[which]
        directory = folder(spec)
        config = CLIPVisionConfig(hidden_size=16, intermediate_size=32, num_hidden_layers=1, num_attention_heads=2,
                                  image_size=224, patch_size=112, projection_dim=dim)
        CLIPVisionModelWithProjection(config).save_pretrained(directory, safe_serialization=ext == "safetensors")
        names = ("config.json", "model.safetensors" if ext == "safetensors" else "pytorch_model.bin")
        patched[which] = (spec[0], spec[1], tuple(asset(directory / n) for n in names))
    monkeypatch.setattr(a, "CLIPS", patched)
    monkeypatch.setattr(a, "CLIP_L14", patched["l14"])
    monkeypatch.setattr(a, "CLIP_B16", patched["b16"])

    # predictor heads: random weights in the pinned file names
    improved_dir = nsfw_safety.model_dir(a.IMPROVED_REPO, a.IMPROVED_REVISION, root)
    improved_dir.mkdir(parents=True)
    mlp = torch.nn.Sequential(torch.nn.Linear(768, 1024), torch.nn.Dropout(0.2), torch.nn.Linear(1024, 128), torch.nn.Dropout(0.2),
                              torch.nn.Linear(128, 64), torch.nn.Dropout(0.1), torch.nn.Linear(64, 16), torch.nn.Linear(16, 1))
    torch.save({"layers." + k: v for k, v in mlp.state_dict().items()}, improved_dir / Path_name(a.IMPROVED_FILE))
    monkeypatch.setattr(a, "IMPROVED_FILE", asset(improved_dir / Path_name(a.IMPROVED_FILE)))
    sim_dir = nsfw_safety.model_dir(a.SIMULACRA_REPO, a.SIMULACRA_REVISION, root)
    sim_dir.mkdir(parents=True)
    sim = {}
    for which, dim in (("l14", 768), ("b16", 512)):
        linear = torch.nn.Linear(dim, 1)
        name = Path_name(a.SIMULACRA_FILES[which])
        torch.save({"linear.weight": linear.weight, "linear.bias": linear.bias}, sim_dir / name)
        sim[which] = dict(asset(sim_dir / name), filename="models/" + name)
    monkeypatch.setattr(a, "SIMULACRA_FILES", sim)

    # cafe classifiers: tiny BEiT with the pinned class labels
    cafe = {}
    for name, labels in a.CAFE_LABELS.items():
        spec = a.CAFE[name]
        directory = folder(spec)
        config = BeitConfig(image_size=32, patch_size=16, hidden_size=16, num_hidden_layers=1, num_attention_heads=2,
                            intermediate_size=32, num_labels=len(labels), id2label=dict(enumerate(labels)),
                            label2id={l: i for i, l in enumerate(labels)})
        BeitForImageClassification(config).save_pretrained(directory, safe_serialization=False)
        BeitImageProcessor(size=32, do_center_crop=False).save_pretrained(directory)
        cafe[name] = (spec[0], spec[1], tuple(asset(directory / n) for n in ("config.json", "preprocessor_config.json", "pytorch_model.bin")))
    monkeypatch.setattr(a, "CAFE", cafe)
    yield root
    a.clear_cache()


def Path_name(asset_):
    return asset_["filename"].rsplit("/", 1)[-1]


def photo(size=(300, 200), seed=1, mode="RGB"):
    rng = np.random.default_rng(seed)
    return Image.fromarray(rng.integers(0, 255, (size[1], size[0], 3), dtype=np.uint8)).convert(mode)


ALL = {"improved": True, "simulacra": "vit-l-14", "cafe_aesthetic": True, "cafe_style": True, "cafe_waifu": True}


# ------------------------------------------------------------------------------------------ configuration
def test_normalize_defaults_and_validation():
    cfg = a.normalize(None)
    assert cfg["improved"] is True and cfg["simulacra"] == "off" and cfg["png_chunks"] is True
    assert a.scorers(a.normalize({"improved": False, "simulacra": "vit-b-16", "cafe_style": "true"})) == ["simulacra_b16", "cafe_style"]
    assert a.scorers(a.normalize(ALL)) == ["improved", "simulacra_l14", "cafe_aesthetic", "cafe_style", "cafe_waifu"]
    assert a.normalize({"simulacra": None})["simulacra"] == "off"
    for bad, message in (({"improved": False}, "at least one scorer"), ({"simulacra": "vit-b-32"}, "simulacra must be one of"),
                         ({"nope": True}, "unknown aesthetic score setting")):
        with pytest.raises(ValueError, match=message):
            a.normalize(bad)


def test_job_config_reports_invalid_and_unavailable(monkeypatch, tmp_path):
    assert a.job_config({}) is None and a.job_config({"aesthetic_score": {"enabled": False}}) is None
    with pytest.raises(EngineError) as error:
        a.job_config({"aesthetic_score": {"improved": False}})
    assert error.value.code == "invalid_params"
    monkeypatch.setenv(a.CACHE_ENV, str(tmp_path / "empty"))
    monkeypatch.setenv(nsfw_safety.OFFLINE_ENV, "1")
    with pytest.raises(EngineError, match="not downloaded") as error:
        a.job_config({"aesthetic_score": {}})
    assert error.value.code == "unsupported"


# ------------------------------------------------------------------------------------------ preprocessing
def test_preprocess_matches_clip_geometry_and_normalisation():
    flat = Image.new("RGB", (448, 224), (255, 255, 255))
    out = a._preprocess(flat, Image.BICUBIC)
    assert out.shape == (3, 224, 224) and out.dtype == np.float32
    expected = [(1 - m) / s for m, s in zip(a.CLIP_MEAN, a.CLIP_STD)]
    assert np.allclose(out.reshape(3, -1)[:, 0], expected, atol=1e-5)
    # shortest side to 224 then a centre crop: a vertical gradient keeps its centre rows
    tall = Image.fromarray(np.tile(np.linspace(0, 255, 900).astype(np.uint8)[:, None, None], (1, 300, 3)))
    cropped = a._preprocess(tall, Image.BICUBIC)
    rows = cropped[0, :, 0] * a.CLIP_STD[0] + a.CLIP_MEAN[0]
    assert 0.30 < rows[0] < 0.37 and 0.63 < rows[-1] < 0.70, (rows[0], rows[-1])


def test_alpha_is_composited_on_white():
    rgba = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    assert a._rgb(rgba).getpixel((3, 3)) == (255, 255, 255)
    opaque = Image.new("RGBA", (64, 64), (10, 20, 30, 255))
    assert a._rgb(opaque).getpixel((3, 3)) == (10, 20, 30)
    assert a._rgb(Image.new("L", (4, 4), 7)).getpixel((0, 0)) == (7, 7, 7)


# ------------------------------------------------------------------------------------------ real loaders, tiny models
def test_all_scorers_run_through_the_real_loaders(cache):
    cfg = a.normalize(ALL)
    a.preload(cfg)
    scores = a.score_image(photo(), cfg)
    assert list(scores) == a.scorers(cfg)
    assert isinstance(scores["improved"], float) and scores["improved"] == round(scores["improved"], 1)
    assert scores["simulacra_l14"] == round(scores["simulacra_l14"], 2)
    for name in ("cafe_aesthetic", "cafe_style", "cafe_waifu"):
        dist = scores[name]
        assert tuple(dist) == a.CAFE_LABELS[name]
        assert math.isclose(sum(dist.values()), 1.0, abs_tol=2e-4)
    # deterministic, and the input image is not modified
    source = photo()
    before = source.tobytes()
    assert a.score_image(source, cfg) == scores
    assert source.tobytes() == before


def test_improved_score_follows_the_documented_formula(cache):
    """aesthetic_score = MLP(L2-normalised CLIP embedding) with CLIP preprocessing, rounded to one decimal."""
    from torch.nn import functional as F
    image = photo((260, 330), seed=3)
    cfg = a.normalize({})
    embedding = a.clip_embedding(image, "l14", "cpu", Image.BICUBIC)
    heads = a._linear_heads()
    with torch.inference_mode():
        expected = float(heads["improved"](F.normalize(embedding, dim=-1)).item())
    assert a.score_clip(image, cfg, ["improved"]) == {"improved": round(expected, 1)}


def test_simulacra_head_scales_the_normalised_embedding(cache):
    from torch.nn import functional as F
    image = photo((200, 200), seed=4)
    for which, dim in (("l14", 768), ("b16", 512)):
        emb = a.clip_embedding(image, which, "cpu", Image.LANCZOS)
        assert emb.shape == (1, dim)
        head = a._linear_heads()["simulacra_" + which]
        with torch.inference_mode():
            expected = float(head(F.normalize(emb, dim=-1) * math.sqrt(dim)).item())
        got = a.score_clip(image, a.normalize({"improved": False, "simulacra": "vit-l-14" if which == "l14" else "vit-b-16"}),
                           ["simulacra_" + which])
        assert got == {"simulacra_" + which: round(expected, 2)}


def test_model_files_are_hash_checked(cache):
    path = nsfw_safety.model_dir(a.IMPROVED_REPO, a.IMPROVED_REVISION, cache) / Path_name(a.IMPROVED_FILE)
    path.write_bytes(path.read_bytes() + b"x")
    a.clear_cache()
    nsfw_safety._verified.clear()
    with pytest.raises(a.ModelUnavailable, match="pinned size/SHA-256"):
        a.preload(a.normalize({}))


def test_cafe_labels_must_match_the_pinned_ones(cache, monkeypatch):
    monkeypatch.setitem(a.CAFE_LABELS, "cafe_waifu", ("a", "b"))
    with pytest.raises(a.ModelUnavailable, match="unexpected class labels"):
        a.preload(a.normalize({"improved": False, "cafe_waifu": True}))


# ------------------------------------------------------------------------------------------ outputs
def test_infotext_and_chunk_formats():
    scores = {"improved": 5.94, "simulacra_l14": 4.1, "simulacra_b16": 4.37, "cafe_aesthetic": {"not_aesthetic": 0.0269, "aesthetic": 0.9731},
              "cafe_style": {"anime": 0.6, "real_life": 0.1, "3d": 0.1, "manga_like": 0.1, "other": 0.1},
              "cafe_waifu": {"not_waifu": 0.2441, "waifu": 0.7559}}
    assert a.infotext_fields(scores) == [
        ("aesthetic_score", "5.9"), ("Aesthetic Scorer ViT-L/14", "4.10"), ("Aesthetic Scorer ViT-B/16", "4.37"),
        ("Cafe aesthetic", "aesthetic 0.9731"), ("Cafe style", "anime 0.6000"), ("Cafe waifu", "waifu 0.7559")]
    chunks = dict(a.png_chunks(scores))
    assert chunks["aesthetic_score"] == "5.9" and chunks["score"] == "4.37"
    assert json.loads(chunks["cafe_waifu"]) == scores["cafe_waifu"]


def test_score_final_fields_chunks_hash_and_generation_fields(cache):
    gp = SimpleNamespace(steps=20, sampler_name="Euler a", cfg_scale=7.0, width=64, height=64, prompt="cat", negative_prompt="ugly")
    logs = []
    ctx = SimpleNamespace(log=lambda level, text: logs.append(text))
    cfg = a.normalize({"improved": True, "generation_fields": True, "hash": True})
    out = a.score_final(photo(), cfg, ctx, gp, 1234, "abcdef1234")
    keys = [k for k, _ in out["infotext_fields"]]
    assert keys == ["aesthetic_score", "hash"]
    digest = dict(out["infotext_fields"])["hash"]
    expected = hashlib.md5(json.dumps({"steps": 20, "sampler": "Euler a", "cfg_scale": 7.0, "seed": 1234, "width": 64, "height": 64,
                                       "model": "abcdef1234", "prompt": "cat", "negative_prompt": "ugly"}).encode()).hexdigest()
    assert digest == expected
    chunks = dict(map(tuple, out["png_chunks"]))
    assert chunks["sampler"] == "Euler a" and chunks["cfg_scale"] == "7" and chunks["sd_model_hash"] == "abcdef1234"
    assert chunks["seed"] == "1234" and chunks["hash"] == digest and chunks["aesthetic_score"] == dict(out["infotext_fields"])["aesthetic_score"]
    assert logs and logs[0].startswith("Aesthetic score: aesthetic_score=")
    quiet = a.score_final(photo(), a.normalize({"png_chunks": False, "hash": True, "generation_fields": True}), None, gp, 5, "m")
    assert quiet["png_chunks"] == [] and [k for k, _ in quiet["infotext_fields"]] == ["aesthetic_score", "hash"]


def test_image_tool_returns_the_same_image_and_string_info(cache):
    image = photo()
    out, info = a.tool(image, {"tool": "aesthetic_score", "improved": True, "cafe_waifu": True, "image_path": "x"})
    assert out is image
    assert set(info) == {"aesthetic_score", "Cafe waifu"} and all(isinstance(v, str) for v in info.values())
    with pytest.raises(ValueError, match="at least one scorer"):
        a.tool(image, {"improved": False})
    from sdfx import image_tools
    assert "aesthetic_score" in image_tools.TOOLS
    out, info = image_tools.run("aesthetic_score", image, {"improved": True}, "cpu")
    assert "aesthetic_score" in info


def test_a_large_alpha_image_and_tiny_image_are_scored(cache):
    cfg = a.normalize({"improved": True, "cafe_aesthetic": True})
    assert set(a.score_image(Image.new("RGBA", (5, 3), (1, 2, 3, 128)), cfg)) == {"improved", "cafe_aesthetic"}
    assert set(a.score_image(photo((1200, 80)), cfg)) == {"improved", "cafe_aesthetic"}
    assert set(a.score_image(Image.new("L", (224, 224), 9), cfg)) == {"improved", "cafe_aesthetic"}


def test_features_list_the_hook():
    from sdfx import hooks
    assert "aesthetic_score" in hooks.KNOWN_HOOKS
    assert a.available()
