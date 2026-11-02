"""Batch Face Swap: settings, face-shaped masks and the face pass inside generation (stubbed model calls)."""
import io
import sys
from types import SimpleNamespace

import numpy as np
import pytest

from sdfx import adetailer, batch_face_swap as bfs
from sdfx.protocol import INVALID_PARAMS, EngineError

Image = pytest.importorskip("PIL.Image")


def test_defaults_map_to_one_face_unit():
    unit = bfs.to_adetailer({})["units"][0]
    assert unit["model"] == "face_yolov8n.pt" and unit["mask_shape"] == "ellipse" and unit["mask_scale"] == 1.2
    assert unit["inpaint_only_masked"] is True and unit["dilate_erode"] == 0 and unit["mask_blur"] == 8
    assert unit["denoising_strength"] == 0.5 and unit["inpaint_padding"] == 32
    assert not (unit["use_steps"] or unit["use_cfg_scale"] or unit["use_sampler"] or unit["use_inpaint_width_height"])
    # the ADetailer layer accepts the unit unchanged
    assert adetailer.normalize_units(bfs.to_adetailer({}))[0]["mask_shape"] == "ellipse"


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
