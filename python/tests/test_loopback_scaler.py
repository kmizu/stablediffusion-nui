"""Loopback Scaler final-image filters (Elldreth/loopback_scaler@184a53e) behind the `image_tool` request."""
import math

import numpy as np
import pytest

Image = pytest.importorskip("PIL.Image")
from PIL import ImageEnhance, ImageFilter  # noqa: E402

from sdfx import image_tools, loopback_scaler as ls  # noqa: E402


def _img(seed=3, w=40, h=28, mode="RGB"):
    rng = np.random.default_rng(seed)
    a = rng.integers(0, 256, (h, w, 4 if mode == "RGBA" else 3), dtype=np.uint8)
    return Image.fromarray(a, mode)


def _upstream(img, sharp, bright, color, contrast, detail, smooth, blur, contour):
    """Literal restatement of the source order: four enhancers, then DETAIL, SMOOTH, BLUR repeats, then CONTOUR."""
    n = {"None": 0, "Low": 1, "Medium": 2, "High": 3}
    x = ImageEnhance.Sharpness(img).enhance(sharp)
    x = ImageEnhance.Brightness(x).enhance(bright)
    x = ImageEnhance.Color(x).enhance(color)
    x = ImageEnhance.Contrast(x).enhance(contrast)
    for _ in range(n[detail]):
        x = x.filter(ImageFilter.DETAIL)
    for _ in range(n[smooth]):
        x = x.filter(ImageFilter.SMOOTH)
    for _ in range(n[blur]):
        x = x.filter(ImageFilter.BLUR)
    if contour:
        x = x.filter(ImageFilter.CONTOUR)
    return x


def test_defaults_are_pixel_identical():
    src = _img()
    out, info = ls.finish(src)
    assert info == {}
    assert out is not src and out.mode == "RGB" and out.size == src.size
    assert np.array_equal(np.asarray(out), np.asarray(src))


@pytest.mark.parametrize("args", [
    (1.6, 1.0, 1.0, 1.0, "None", "None", "None", False),
    (1.0, 0.7, 1.3, 1.2, "None", "None", "None", False),
    (0.4, 1.1, 0.9, 1.8, "High", "None", "None", False),
    (1.0, 1.0, 1.0, 1.0, "Low", "Medium", "High", False),
    (1.25, 0.9, 1.4, 0.8, "Medium", "Low", "Low", True),
    (0.1, 2.0, 0.1, 2.0, "None", "None", "None", True),
])
def test_matches_literal_upstream_order(args):
    src = _img()
    out, _ = ls.finish(src, *args)
    assert np.array_equal(np.asarray(out), np.asarray(_upstream(src, *args)))


def test_order_is_significant():
    src = _img(seed=9)
    out, _ = ls.finish(src, contrast=1.8, sharpness=1.9)
    swapped = ImageEnhance.Sharpness(ImageEnhance.Contrast(src).enhance(1.8)).enhance(1.9)
    assert not np.array_equal(np.asarray(out), np.asarray(swapped))
    a, _ = ls.finish(src, detail="Low", blur="Low")
    b = src.filter(ImageFilter.BLUR).filter(ImageFilter.DETAIL)
    assert not np.array_equal(np.asarray(a), np.asarray(b))


@pytest.mark.parametrize("level,times", [("Low", 1), ("Medium", 2), ("High", 3)])
def test_levels_repeat_the_filter(level, times):
    src = _img()
    expected = src
    for _ in range(times):
        expected = expected.filter(ImageFilter.SMOOTH)
    out, _ = ls.finish(src, smooth=level)
    assert np.array_equal(np.asarray(out), np.asarray(expected))


def test_rgba_input_becomes_rgb_like_generated_images():
    src = _img(mode="RGBA")
    out, _ = ls.finish(src, brightness=1.5)
    assert out.mode == "RGB"
    assert np.array_equal(np.asarray(out), np.asarray(ImageEnhance.Brightness(src.convert("RGB")).enhance(1.5)))


@pytest.mark.parametrize("kw", [
    {"sharpness": 0.05}, {"brightness": 2.01}, {"color": math.nan}, {"contrast": math.inf}, {"sharpness": True},
    {"color": "1.0"}, {"detail": "Extreme"}, {"smooth": None}, {"blur": 1}, {"contour": "true"}, {"contour": 1},
])
def test_invalid_values_are_rejected(kw):
    with pytest.raises(ValueError):
        ls.finish(_img(), **kw)


def test_image_tool_dispatch_reads_only_documented_keys():
    assert "loopback_scaler" in image_tools.TOOLS
    src = _img()
    params = {"tool": "loopback_scaler", "image_path": "ignored", "output_dir": "ignored", "sharpness": 1.4,
              "contrast": 1.2, "detail": "Low", "contour": True}
    out, extra = image_tools.run("loopback_scaler", src, params)
    expected = _upstream(src, 1.4, 1.0, 1.0, 1.2, "Low", "None", "None", True)
    assert extra == {} and np.array_equal(np.asarray(out), np.asarray(expected))


def test_dry_run_engine_request_writes_processed_png(tmp_path):
    from sdfx.dryrun import FakeEngine
    from sdfx.protocol import EngineError
    src = _img()
    path = tmp_path / "in.png"
    src.save(path)
    engine = FakeEngine()
    assert "loopback_scaler" in engine.features()
    res = engine.image_tool({"tool": "loopback_scaler", "image_path": str(path), "output_dir": str(tmp_path),
                             "color": 1.5, "blur": "Medium"}, None)
    with Image.open(res["path"]) as written:
        got = np.asarray(written.convert("RGB"))
    assert np.array_equal(got, np.asarray(_upstream(src, 1.0, 1.0, 1.5, 1.0, "None", "None", "Medium", False)))
    assert res["outputs"] == {} and res["info"] == {}
    with pytest.raises(EngineError):
        engine.image_tool({"tool": "loopback_scaler", "image_path": str(path), "output_dir": str(tmp_path),
                           "detail": "Ultra"}, None)


def test_real_engine_advertises_feature():
    pytest.importorskip("torch")
    pytest.importorskip("diffusers")
    from sdfx.engine import Engine
    assert "loopback_scaler" in Engine.__new__(Engine).features()
