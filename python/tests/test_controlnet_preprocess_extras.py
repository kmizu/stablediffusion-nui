"""ControlNet Preprocessor in Extras (index 276): resolution rules, threshold fields, infotext and the engine request."""
import io

import pytest

np = pytest.importorskip("numpy")
cv2 = pytest.importorskip("cv2")
from PIL import Image

from sdfx import controlnet as cn
from sdfx.base import JobContext
from sdfx.dryrun import FakeEngine
from sdfx.protocol import Emitter, EngineError


def sample(width=96, height=64):
    a = np.zeros((height, width, 3), np.uint8)
    a[:, width // 2:] = 255
    a[height // 4:height // 2, :width // 4] = 128
    return Image.fromarray(a)


def test_canny_resolution_is_the_short_side_pixel_perfect_or_the_slider_or_512():
    img = sample(96, 64)
    out, info, _ = cn.standalone_preprocess("canny", img, {}, -1, True)
    assert (info["processor_res"], out.size) == (64, (96, 64))
    out, info, _ = cn.standalone_preprocess("canny", img, {}, 128, False)
    assert (info["processor_res"], out.size) == (128, (192, 128))
    out, info, _ = cn.standalone_preprocess("canny", img, {}, -1, False)
    assert (info["processor_res"], out.size) == (512, (768, 512))
    out, info, notes = cn.standalone_preprocess("canny", img, {}, 8, False)  # out of range -> default
    assert info["processor_res"] == 512 and notes == []


def test_canny_thresholds_reach_the_detector_and_the_info():
    img = sample()
    weak, info, _ = cn.standalone_preprocess("canny", img, {"low_threshold": 1, "high_threshold": 2}, 64)
    strong, _, _ = cn.standalone_preprocess("canny", img, {"low_threshold": 250, "high_threshold": 255}, 64)
    assert (info["threshold_a"], info["threshold_b"]) == (1, 2)
    assert np.asarray(weak).sum() >= np.asarray(strong).sum()
    assert set(np.unique(np.asarray(weak))) <= {0, 255}


def test_resolution_is_ignored_with_a_note_for_modules_without_one():
    img = sample()
    out, info, notes = cn.standalone_preprocess("invert", img, {}, 256, True)
    assert out.size == img.size and info["processor_res"] == -1
    assert np.array_equal(np.asarray(out), 255 - np.asarray(img))
    assert len(notes) == 1 and "invert" in notes[0]
    _, _, notes = cn.standalone_preprocess("invert", img, {}, -1, False)
    assert notes == []


@pytest.mark.parametrize("name,key", [("recolor_luminance", "gamma_correction"), ("blur_gaussian", "sigma"),
                                      ("tile_colorfix", "variation"), ("tile_resample", "down_sampling_rate")])
def test_first_slider_is_threshold_a(name, key):
    _, info, _ = cn.standalone_preprocess(name, sample(), {key: 3}, -1)
    assert (info["module"], info["threshold_a"], info["threshold_b"]) == (name, 3, 0)


def test_missing_arguments_report_the_defaults_that_were_used():
    _, info, _ = cn.standalone_preprocess("canny", sample(), {}, 64)
    assert (info["threshold_a"], info["threshold_b"]) == (100, 200)
    _, info, _ = cn.standalone_preprocess("blur_gaussian", sample(), {})
    assert (info["threshold_a"], info["threshold_b"]) == (9.0, 0)
    _, info, _ = cn.standalone_preprocess("invert", sample(), {})
    assert (info["threshold_a"], info["threshold_b"]) == (0, 0)


def test_unknown_module_raises():
    with pytest.raises(ValueError):
        cn.standalone_preprocess("nope", sample(), {})


def test_png_carries_the_infotext_and_keeps_the_source_parameters(tmp_path):
    from PIL.PngImagePlugin import PngInfo
    meta = PngInfo()
    meta.add_text("parameters", "a cat\nSteps: 20")
    src = tmp_path / "src.png"
    sample().save(src, pnginfo=meta)
    out, info, _ = cn.standalone_preprocess("canny", Image.open(src).convert("RGB"), {}, 64)
    dst = tmp_path / "out.png"
    cn.save_preprocessed(out, str(dst), info, str(src))
    text = Image.open(dst).text
    assert text["parameters"] == f'ControlNet Preprocessor: "{info}", Size: 96x64'
    assert text["Source parameters"] == "a cat\nSteps: 20"
    assert "'module': 'canny'" in text["parameters"]


def request(tmp_path, **extra):
    src = tmp_path / "in.png"
    sample().save(src)
    return {"preprocessor": "canny", "image_path": str(src), "output_dir": str(tmp_path / "o"), **extra}


def ctx():
    return JobContext(Emitter(io.BytesIO()), "t")


def test_engine_request_standalone_writes_info_and_plain_request_is_unchanged(tmp_path):
    engine = FakeEngine()
    r = engine.preprocess(request(tmp_path, standalone=True, processor_res=128, args={"low_threshold": 50}), ctx())
    assert r["info"]["processor_res"] == 128 and r["info"]["threshold_a"] == 50
    assert Image.open(r["path"]).size == (192, 128)
    assert "ControlNet Preprocessor" in Image.open(r["path"]).text["parameters"]
    plain = engine.preprocess(request(tmp_path), ctx())
    assert set(plain) == {"path"}


def test_engine_request_rejects_missing_image_and_unknown_module(tmp_path):
    engine = FakeEngine()
    with pytest.raises(EngineError):
        engine.preprocess({"preprocessor": "canny", "image_path": str(tmp_path / "x.png"), "output_dir": str(tmp_path)}, ctx())
    with pytest.raises(EngineError):
        engine.preprocess(request(tmp_path, preprocessor="nope", standalone=True), ctx())


def test_feature_is_advertised():
    assert "controlnet_preprocess_extras" in FakeEngine().features()
