"""Batch Face Swap: settings, face-shaped masks and the face pass inside generation (stubbed model calls)."""
import io
import sys
from types import SimpleNamespace

import numpy as np
import pytest

from sdfx import adetailer, batch_face_swap as bfs
from sdfx.protocol import INVALID_PARAMS, EngineError

Image = pytest.importorskip("PIL.Image")


def test_expected_face_checkpoint_hash_validation():
    assert bfs.normalize({"checkpoint": "face", "checkpoint_hash": "ABCDEF1234"})["checkpoint_hash"] == "abcdef1234"
    for value in ("invalid", "a" * 9, "a" * 11, 123):
        with pytest.raises(EngineError):
            bfs.normalize({"checkpoint": "face", "checkpoint_hash": value})
    with pytest.raises(EngineError):
        bfs.normalize({"checkpoint_hash": "0123456789"})


def test_expected_face_checkpoint_hash_must_match_loaded_identity():
    bfs.verify_checkpoint_hash("0123456789", {"hash": "0123456789"})
    bfs.verify_checkpoint_hash("", {"hash": "different"})
    for loaded in ({"hash": "1111111111"}, {}, None):
        with pytest.raises(EngineError, match="checkpoint hash"):
            bfs.verify_checkpoint_hash("0123456789", loaded)


def test_face_checkpoint_rejects_unapplied_lora():
    other = SimpleNamespace(_apply_loras=lambda *args: {}, _load_embeddings=lambda *args: pytest.fail("must not encode"))
    gp = SimpleNamespace(extra={}, hooks={}, loras=[SimpleNamespace(name="xl-only")], prompt="", negative_prompt="")
    with pytest.raises(EngineError, match="xl-only"):
        bfs.prepare_face_engine(other, gp, SimpleNamespace(), SimpleNamespace(), {})


def test_face_checkpoint_accepts_applied_lora():
    loaded = []
    other = SimpleNamespace(_apply_loras=lambda *args: {"compatible": "hash"},
                            _load_embeddings=lambda *args: loaded.append(True))
    gp = SimpleNamespace(extra={}, hooks={}, loras=[SimpleNamespace(name="compatible")], prompt="", negative_prompt="")
    bfs.prepare_face_engine(other, gp, SimpleNamespace(), SimpleNamespace(), {})
    assert loaded == [True]


def test_defaults_map_to_one_face_unit():
    unit = bfs.to_adetailer({})["units"][0]
    assert unit["model"] == "face_yolov8n.pt" and unit["mask_shape"] == "ellipse" and unit["mask_scale"] == 1.2
    assert unit["inpaint_only_masked"] is True and unit["dilate_erode"] == 0 and unit["mask_blur"] == 8
    assert unit["denoising_strength"] == 0.5 and unit["inpaint_padding"] == 32
    assert not (unit["use_steps"] or unit["use_cfg_scale"] or unit["use_sampler"] or unit["use_inpaint_width_height"])
    # the ADetailer layer accepts the unit unchanged
    assert adetailer.normalize_units(bfs.to_adetailer({}))[0]["mask_shape"] == "ellipse"


def test_face_checkpoint_is_optional_and_rejects_non_string_values():
    assert bfs.normalize({})["checkpoint"] == ""
    assert bfs.normalize({"checkpoint": " face.safetensors "})["checkpoint"] == "face.safetensors"
    for bad in (None, 123, {"path": "foo"}):
        with pytest.raises(EngineError) as error:
            bfs.normalize({"checkpoint": bad})
        assert error.value.code == INVALID_PARAMS


def test_face_engine_scope_isolated_and_restores_base_even_on_failure(tmp_path):
    import hashlib
    checkpoint = tmp_path / "other.safetensors"
    checkpoint.write_bytes(b"fixture")
    events = []
    class Base:
        pipe = object()
        def park(self): events.append("park")
        def _activate(self, pipe):
            assert pipe is self.pipe
            events.append("restore")
    class Other:
        _owns_shared_cache = True
        model = {"hash": hashlib.sha256(b"fixture").hexdigest()[:10]}
        def load_model(self, params, ctx):
            assert self._owns_shared_cache is False
            events.append(("load", params["path"]))
        def _free(self): events.append("free")
    ctx = SimpleNamespace(check_interrupt=lambda: None)
    base = Base()
    base.load_opts = SimpleNamespace(dtype="fp16", vram_mode="gpu")
    with pytest.raises(ValueError):
        with bfs.face_engine_scope(base, str(checkpoint), ctx, lambda: Other()):
            events.append("face")
            raise ValueError("test failure")
    assert events == ["park", ("load", str(checkpoint)), "face", "free", "restore"]


def test_face_engine_scope_restores_base_after_checkpoint_load_failure(tmp_path):
    checkpoint = tmp_path / "bad.safetensors"
    checkpoint.write_bytes(b"fixture")
    events = []
    class Base:
        pipe = object()
        load_opts = SimpleNamespace(dtype="fp16", vram_mode="gpu")
        def park(self): events.append("park")
        def _activate(self, pipe): events.append("restore")
    class Other:
        def load_model(self, params, ctx): raise ValueError("load failure")
        def _free(self): events.append("free")
    with pytest.raises(ValueError, match="load failure"):
        with bfs.face_engine_scope(Base(), str(checkpoint), SimpleNamespace(check_interrupt=lambda: None), Other):
            pytest.fail("failed load must not run face pass")
    assert events == ["park", "free", "restore"]


def test_face_scope_keeps_original_failure_if_cleanup_and_restore_fail(tmp_path):
    import hashlib
    checkpoint = tmp_path / "other.safetensors"
    checkpoint.write_bytes(b"fixture")
    class Base:
        pipe = object()
        load_opts = SimpleNamespace(dtype="fp16", vram_mode="gpu")
        def park(self): pass
        def _activate(self, pipe): raise RuntimeError("restore error")
    class Other:
        model = {"hash": hashlib.sha256(b"fixture").hexdigest()[:10]}
        def load_model(self, params, ctx): pass
        def _free(self): raise RuntimeError("cleanup error")
    failure = ValueError("original error")
    with pytest.raises(ValueError) as caught:
        with bfs.face_engine_scope(Base(), str(checkpoint), SimpleNamespace(check_interrupt=lambda: None), Other):
            raise failure
    assert caught.value is failure
    assert any("cleanup error" in note for note in caught.value.__notes__)
    assert any("restore error" in note for note in caught.value.__notes__)


def test_face_scope_rejects_checkpoint_changed_during_load(tmp_path):
    checkpoint = tmp_path / "face.safetensors"
    checkpoint.write_bytes(b"first")
    events = []
    class Base:
        pipe = object()
        load_opts = SimpleNamespace(dtype="fp16", vram_mode="gpu")
        def park(self): events.append("park")
        def _activate(self, pipe): events.append("restore")
    class Other:
        model = {"hash": "irrelevant"}
        def load_model(self, params, ctx): checkpoint.write_bytes(b"changed")
        def _free(self): events.append("free")
    with pytest.raises((EngineError, ValueError)):
        with bfs.face_engine_scope(Base(), str(checkpoint), SimpleNamespace(check_interrupt=lambda: None), Other):
            pytest.fail("changed checkpoint must not run face pass")
    assert events == ["park", "free", "restore"]


def test_overrides_switch_the_use_flags_on():
    unit = bfs.to_adetailer({"resolution": 770, "steps": 12, "cfg_scale": 4.5, "sampler": "Euler a", "max_faces": 2,
                             "prompt": "[PROMPT], smile", "negative_prompt": "blurry", "mask_shape": "BOX",
                             "color_correction": "false"})["units"][0]
    assert unit["use_inpaint_width_height"] and (unit["inpaint_width"], unit["inpaint_height"]) == (768, 768)
    assert (unit["use_steps"], unit["steps"], unit["use_cfg_scale"], unit["cfg_scale"]) == (True, 12, True, 4.5)
    assert (unit["use_sampler"], unit["sampler"], unit["mask_k_largest"]) == (True, "Euler a", 2)
    assert unit["mask_shape"] == "box" and unit["color_correction"] is False and unit["prompt"] == "[PROMPT], smile"


@pytest.mark.parametrize("bad", [{"bogus": 1}, {"confidence": 1.5}, {"confidence": "x"}, {"max_faces": 1.5},
                                 {"max_faces": -1}, {"mask_shape": "star"}, {"mask_size": 0}, {"mask_size": float("nan")},
                                 {"mask_blur": 100}, {"denoising_strength": 2}, {"resolution": 32}, {"steps": 500},
                                 {"cfg_scale": -1}, {"detector": " "}, {"detector": "None"}, {"padding": -4}])
def test_bad_settings_are_rejected(bad):
    with pytest.raises(EngineError) as e:
        bfs.normalize(bad)
    assert e.value.code == INVALID_PARAMS


def test_shaped_masks_are_scaled_ellipses_or_boxes():
    d = adetailer.Detection([(20, 20, 60, 60), (100, 10, 120, 50)], [None, None], [1.0, 1.0])
    ell = adetailer.shaped_masks(d, (128, 96), "ellipse", 1.0)
    a = np.asarray(ell[0])
    assert a[40, 40] == 255 and a[21, 21] == 0 and a[0, 0] == 0          # corner of the box is outside the ellipse
    box = np.asarray(adetailer.shaped_masks(d, (128, 96), "box", 1.0)[0])
    assert box[21, 21] == 255 and box[15, 15] == 0
    big = np.asarray(adetailer.shaped_masks(d, (128, 96), "box", 1.5)[0])
    assert (big > 0).sum() > (box > 0).sum() * 2 and big[15, 15] == 255   # 1.5x the size in each direction
    assert len(ell) == 2


def test_unit_masks_use_shapes_only_when_asked(monkeypatch):
    img = Image.new("RGB", (128, 96))
    seg = Image.new("L", (128, 96), 255)
    det = adetailer.Detection([(20, 20, 60, 60)], [seg], [0.9])
    monkeypatch.setattr(adetailer, "detect", lambda *a, **k: det)
    monkeypatch.setattr(adetailer, "resolve_model", lambda name, dirs=None: name)
    plain = adetailer.normalize_units({"model": "x.pt", "dilate_erode": 0})[0]
    assert adetailer.unit_masks(img, plain, "cpu")[0].getpixel((0, 0)) == 255      # the model's own mask is kept
    shaped = adetailer.normalize_units(bfs.to_adetailer({"detector": "x.pt"}))[0]
    assert adetailer.unit_masks(img, shaped, "cpu")[0].getpixel((0, 0)) == 0


# ------------------------------------------------------------------------------------------------ engine routing
torch = pytest.importorskip("torch")
pytest.importorskip("diffusers")


@pytest.fixture
def harness(monkeypatch, tmp_path):
    from sdfx.engine import Engine
    engine = Engine()
    engine.model = dict(name="fixture", hash="fixture")
    engine.kind = "sd15"
    engine.vae_info = None
    engine.loaded_embedding_files = {}
    engine._intermediate_recorder = None
    calls = []
    monkeypatch.setattr(engine, "ensure_model", lambda *a: None)
    monkeypatch.setattr(engine, "_apply_loras", lambda *a: {})
    monkeypatch.setattr(engine, "_load_embeddings", lambda *a: None)
    monkeypatch.setattr(engine, "_encode_scheduled",
                        lambda prompt, neg, *a, **kw: calls.append(("encode", prompt, neg)) or {})
    monkeypatch.setattr(engine, "_adjust_embeds", lambda gp, embeds, ctx: embeds)
    monkeypatch.setattr(engine, "_controlnets", lambda *a: ([], [], {}))
    monkeypatch.setattr(engine, "_noise", lambda *a: torch.zeros(1, 4, 8, 8))
    monkeypatch.setattr(engine, "_pag_config", lambda *a: None)

    def run(task, gp, req, ctx, **kw):
        calls.append(("run", task, kw["pass_"], kw["w"], kw["h"], kw["steps"], kw["cfg_scale"], kw["sampler"],
                      sorted(gp.hooks)))
        color = (200, 30, 30) if kw["pass_"] == "adetailer" else (30, 30, 200)
        return [Image.new("RGB", (kw["w"], kw["h"]), color)], SimpleNamespace(schedule="Normal")
    monkeypatch.setattr(engine, "_run", run)
    request = dict(params=dict(prompt="a girl", negative_prompt="bad", seed=10, steps=20, width=128, height=128,
                               sampler_name="Euler", hooks={}), output_dir=str(tmp_path), preview_method="none")
    return engine, request, calls


def context():
    from sdfx.protocol import Emitter, JobContext
    return JobContext(Emitter(io.BytesIO()), "bfs-test")


def stub_detect(monkeypatch, boxes):
    def detect(image, model, confidence, device="cuda"):
        return adetailer.Detection(list(boxes), adetailer._box_mask(image.size, boxes), [0.9] * len(boxes))
    monkeypatch.setattr(adetailer, "detect", detect)
    monkeypatch.setattr(adetailer, "resolve_model", lambda name, dirs=None: name)


def passes(calls):
    return [c for c in calls if c[0] == "run" and c[2] == "adetailer"]


def test_every_face_is_redrawn_with_the_overrides_and_the_hook_is_not_inherited(harness, monkeypatch):
    engine, request, calls = harness
    stub_detect(monkeypatch, [(70, 20, 100, 50), (10, 20, 40, 50)])
    request["params"]["hooks"]["batch_face_swap"] = dict(prompt="[PROMPT], smile [SEP] [PROMPT], wink",
        negative_prompt="ugly", resolution=96, steps=10, cfg_scale=3.5, sampler="Euler a", max_faces=0, color_correction=False)
    result = engine.txt2img(request, context())
    runs = passes(calls)
    assert len(runs) == 2
    assert all(r[3:8] == (96, 96, 10, 3.5, "Euler a") for r in runs), runs
    assert all("batch_face_swap" not in r[8] for r in runs)
    encodes = [c for c in calls if c[0] == "encode"]
    assert [c[1] for c in encodes][-2:] == ["a girl, smile", "a girl, wink"]      # left to right
    assert [c[2] for c in encodes][-2:] == ["ugly", "ugly"]
    with Image.open(result["images"][0]["path"]) as im:                          # redrawn faces are red
        px = im.convert("RGB")
        assert px.getpixel((25, 35))[0] > 100 and px.getpixel((85, 35))[0] > 100 and px.getpixel((60, 100))[0] < 100


def test_max_faces_keeps_the_largest(harness, monkeypatch):
    engine, request, calls = harness
    stub_detect(monkeypatch, [(70, 20, 80, 30), (10, 20, 60, 70), (90, 90, 100, 100)])
    request["params"]["hooks"]["batch_face_swap"] = dict(max_faces=1)
    engine.txt2img(request, context())
    assert len(passes(calls)) == 1


def test_nothing_detected_leaves_the_image_alone(harness, monkeypatch):
    engine, request, calls = harness
    stub_detect(monkeypatch, [])
    request["params"]["hooks"]["batch_face_swap"] = {}
    result = engine.txt2img(request, context())
    assert passes(calls) == []
    with Image.open(result["images"][0]["path"]) as im:
        assert im.convert("RGB").getpixel((60, 60)) == (30, 30, 200)


@pytest.mark.parametrize("aesthetic", [False, True])
def test_face_checkpoint_load_is_lazy_and_used_only_for_face_pass(harness, monkeypatch, tmp_path, aesthetic):
    from contextlib import contextmanager
    engine, request, calls = harness
    checkpoint = tmp_path / "face.safetensors"
    checkpoint.write_bytes(b"fixture")
    request["params"]["hooks"]["batch_face_swap"] = dict(checkpoint=str(checkpoint))
    if aesthetic:
        from sdfx import aesthetic_score
        monkeypatch.setattr(aesthetic_score, "job_config", lambda hooks, ctx: {})
        monkeypatch.setattr(aesthetic_score, "score_final", lambda *a: {"infotext_fields": [["Aesthetic score", "9"]]})
    events = []
    class Face:
        model = {"hash": "abcd123456"}
        def _adjust_embeds(self, gp, emb, ctx): return emb
        def _encode_scheduled(self, *a, **k): events.append("face encode"); return {}
        def _run(self, task, gp, req, ctx, **kw):
            events.append("face run")
            return [Image.new("RGB", (kw["w"], kw["h"]), (200, 30, 30))], SimpleNamespace(schedule="Normal")
    @contextmanager
    def scope(base, path, ctx, factory):
        events.append(("load", path))
        try: yield Face()
        finally: events.append("free")
    monkeypatch.setattr(bfs, "face_engine_scope", scope)
    monkeypatch.setattr(bfs, "prepare_face_engine", lambda *a: None, raising=False)
    stub_detect(monkeypatch, [])
    request["params"]["hooks"]["batch_face_swap"]["checkpoint_hash"] = "0000000000"
    no_face = engine.txt2img(request, context())
    assert not events
    assert not any(field[0] == "Face checkpoint hash" for field in no_face["images"][0].get("infotext_fields", []))
    request["params"]["hooks"]["batch_face_swap"]["checkpoint_hash"] = "abcd123456"
    stub_detect(monkeypatch, [(10, 20, 40, 50)])
    result = engine.txt2img(request, context())
    assert events == [("load", str(checkpoint)), "face encode", "face run", "free"]
    assert not passes(calls)
    assert ["Face checkpoint hash", "abcd123456"] in result["images"][0]["infotext_fields"]
    if aesthetic:
        assert ["Aesthetic score", "9"] in result["images"][0]["infotext_fields"]
    request["params"]["hooks"]["batch_face_swap"]["checkpoint_hash"] = "0000000000"
    events.clear()
    with pytest.raises(EngineError, match="checkpoint hash"):
        engine.txt2img(request, context())
    assert events == [("load", str(checkpoint)), "free"]


def test_missing_face_checkpoint_is_rejected_before_base_generation(harness, tmp_path):
    engine, request, calls = harness
    request["params"]["hooks"]["batch_face_swap"] = dict(checkpoint=str(tmp_path / "missing.safetensors"))
    with pytest.raises(EngineError):
        engine.txt2img(request, context())
    assert not [c for c in calls if c[0] == "run"]


def test_same_checkpoint_reuses_base_engine(harness, monkeypatch, tmp_path):
    engine, request, calls = harness
    checkpoint = tmp_path / "base.safetensors"
    checkpoint.write_bytes(b"fixture")
    engine.model["path"] = str(checkpoint)
    request["params"]["hooks"]["batch_face_swap"] = dict(checkpoint=str(checkpoint))
    stub_detect(monkeypatch, [(10, 20, 40, 50)])
    monkeypatch.setattr(bfs, "face_engine_scope", lambda *a: pytest.fail("same checkpoint must not load again"))
    engine.txt2img(request, context())
    assert len(passes(calls)) == 1
    request["params"]["hooks"]["batch_face_swap"]["checkpoint_hash"] = "0000000000"
    with pytest.raises(EngineError, match="checkpoint hash"):
        engine.txt2img(request, context())


def test_runs_after_adetailer_and_without_the_hook_nothing_changes(harness, monkeypatch):
    engine, request, calls = harness
    stub_detect(monkeypatch, [(10, 20, 40, 50)])
    request["params"]["hooks"]["adetailer"] = dict(units=[dict(model="face_yolov8n.pt")])
    request["params"]["hooks"]["batch_face_swap"] = dict(denoising_strength=0.3)
    engine.txt2img(request, context())
    assert len(passes(calls)) == 2
    calls.clear()
    request["params"]["hooks"] = {}
    engine.txt2img(request, context())
    assert passes(calls) == []


def test_bad_settings_fail_before_any_image_is_drawn(harness):
    engine, request, calls = harness
    request["params"]["hooks"]["batch_face_swap"] = dict(confidence=3)
    with pytest.raises(EngineError):
        engine.txt2img(request, context())
    assert not [c for c in calls if c[0] == "run"]


def test_missing_mediapipe_reports_the_optional_package(harness, monkeypatch):
    from sdfx import mediapipe_face
    from sdfx.protocol import UNSUPPORTED
    engine, request, _ = harness
    monkeypatch.setattr(mediapipe_face, "model_path", lambda: "unused.tflite")
    monkeypatch.setitem(sys.modules, "mediapipe", None)
    request["params"]["hooks"]["batch_face_swap"] = dict(detector="mediapipe_face")
    with pytest.raises(EngineError) as error:
        engine.txt2img(request, context())
    assert error.value.code == UNSUPPORTED
    assert "needs the mediapipe package" in str(error.value)


def test_feature_is_advertised_and_known():
    from sdfx import hooks
    from sdfx.dryrun import FakeEngine
    assert "batch_face_swap" in hooks.KNOWN_HOOKS and "batch_face_swap" in FakeEngine().features()
