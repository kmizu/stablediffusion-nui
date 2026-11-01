"""NudeNet NSFW Censor (w-e-w/sd-webui-nudenet-nsfw-censor@1fb21cf): detection geometry, masks, filters, workflows."""
import hashlib
import io
import math
from pathlib import Path

import numpy as np
import pytest
from PIL import Image, ImageChops, ImageDraw, ImageFilter

from sdfx import image_tools, nsfw_checker as nn
from sdfx.hooks import KNOWN_HOOKS

ROOT = Path(__file__).resolve().parents[2]


def outputs(*boxes, n=4):
    """Model output (1, 22, n): boxes are (cx, cy, w, h, class index, score) in 320px model space."""
    raw = np.zeros((1, 22, n), dtype=np.float32)
    for i, (cx, cy, w, h, cls, score) in enumerate(boxes):
        raw[0, :4, i] = (cx, cy, w, h)
        raw[0, 4 + cls, i] = score
    return [raw]


def on(thresholds_for=(), value=0.25):
    t = np.ones(18, dtype=np.float32)
    for name in thresholds_for:
        t[nn.LABELS.index(name)] = value
    return t


ONES = np.ones(18, dtype=np.float32)
FACE = nn.LABELS.index("Face_female")


def bbox(mask):
    return mask.getbbox()


# ------------------------------------------------------------------------------------------------ labels/config
def test_labels_keep_model_order_and_upstream_defaults():
    assert len(nn.LABELS) == 18 and nn.LABELS[0] == "Female_genitalia_covered" and nn.LABELS[-1] == "Buttocks_covered"
    assert nn.LABELS.index("Male_genitalia_exposed") == 14
    enabled = [nn.FRIENDLY[i] for i, t in enumerate(nn.DEFAULT_THRESHOLDS) if t < 1]
    assert sorted(enabled) == sorted(nn.DEFAULT_SELECTED)
    # the README's example threshold list for the same four categories, in model order
    assert [i for i, t in enumerate(nn.DEFAULT_THRESHOLDS) if t < 1] == [3, 4, 6, 14]
    assert all(t in (0.25, 1.0) for t in nn.DEFAULT_THRESHOLDS)
    assert "nsfw_checker" in KNOWN_HOOKS and "nsfw_checker" in image_tools.TOOLS


def test_generation_config_defaults_and_validation():
    cfg = nn.generation_config({"enabled": True})
    assert cfg["gen_filter_type"] == "Gaussian Blur" and cfg["live_preview_filter_type"] == "Gaussian Blur"
    assert cfg["save_before_censor"] is True and cfg["save_mask"] is False and cfg["nms_threshold"] == 0.5
    assert cfg["mask_shape"] == "Ellipse" and cfg["blur_radius"] == 10 and cfg["mask_blend_radius_variable_blur"] == 10
    assert cfg["thresholds"].tolist() == list(nn.DEFAULT_THRESHOLDS)
    assert nn.generation_config({"expand_horizontal": [0] * 18, "rectangle_round_radius": -4})["expand_horizontal"].sum() == 0
    bad = [{"gen_filter_type": "Blur"}, {"mask_shape": nn.ENTIRE_IMAGE_ALWAYS}, {"thresholds": [0.5] * 17},
           {"expand_vertical": [-1] + [1] * 17}, {"blur_radius": float("nan")}, {"nms_threshold": 2},
           {"fill_color": "not a colour"}, {"pixelation_factor": 0}, {"blur_radius": True}]
    for raw in bad:
        with pytest.raises(ValueError):
            nn.generation_config(raw)


def test_scale_factor_is_relative_to_512_square():
    assert nn.scale_factor((512, 512)) == 1
    assert math.isclose(nn.scale_factor((1024, 1024)), 2)


# ------------------------------------------------------------------------------------------------ geometry
def test_letterbox_preprocessing_matches_upstream_geometry():
    wide = nn.preprocess(Image.new("RGB", (640, 320), (255, 0, 0)))
    assert wide.shape == (1, 3, 320, 320) and wide.dtype == np.float32
    assert np.allclose(wide[0, :, :80, :], 1) and np.allclose(wide[0, :, 240:, :], 1)
    assert np.allclose(wide[0, 0, 80:240, :], 1) and np.allclose(wide[0, 1:, 80:240, :], 0)
    tall = nn.preprocess(Image.new("L", (100, 300), 0))  # non-RGB converts; width round(320/3) = 107, offset 106
    assert np.allclose(tall[0, :, :, :106], 1) and np.allclose(tall[0, :, :, 213:], 1)
    assert np.allclose(tall[0, :, :, 107:212], 0)


def test_landscape_and_portrait_boxes_are_restored_to_image_space():
    # landscape 640x320: factor 2, letterbox offset 160 on y
    mask = nn.mask_from_outputs(outputs((160, 160, 64, 32, FACE, 0.9)), (640, 320), on(["Face_female"]), ONES, ONES,
                                1, "Rectangle", 0)
    assert mask.mode == "1" and mask.size == (640, 320)
    assert bbox(mask) == (256, 128, 385, 193)
    # portrait 320x640: offset on x
    mask = nn.mask_from_outputs(outputs((160, 160, 32, 64, FACE, 0.9)), (320, 640), on(["Face_female"]), ONES, ONES,
                                1, "Rectangle", 0)
    assert bbox(mask) == (128, 256, 193, 385)


def test_threshold_is_per_category_strict_and_one_disables():
    out = outputs((160, 160, 40, 40, FACE, 0.25))
    assert nn.mask_from_outputs(out, (320, 320), on(["Face_female"]), ONES, ONES, 1, "Ellipse", 0) is None  # > not >=
    assert nn.mask_from_outputs(out, (320, 320), on(["Face_female"], 0.2), ONES, ONES, 1, "Ellipse", 0) is not None
    assert nn.mask_from_outputs(out, (320, 320), on(["Face_male"], 0.0), ONES, ONES, 1, "Ellipse", 0) is None
    assert nn.mask_from_outputs(out, (320, 320), np.asarray(nn.DEFAULT_THRESHOLDS), ONES, ONES, 1, "Ellipse", 0) is None
    exposed = outputs((160, 160, 40, 40, nn.LABELS.index("Anus_exposed"), 0.3))
    assert nn.mask_from_outputs(exposed, (320, 320), np.asarray(nn.DEFAULT_THRESHOLDS), ONES, ONES, 1, "Ellipse", 0)


def test_expansion_grows_around_the_centre_per_category():
    h = ONES.copy(); h[FACE] = 2
    v = ONES.copy(); v[FACE] = 0.5
    mask = nn.mask_from_outputs(outputs((160, 160, 64, 32, FACE, 0.9)), (640, 320), on(["Face_female"]), h, v, 1,
                                "Rectangle", 0)
    # 128x64 box at (256,128) -> 256x32 centred: x 192..448, y 144..176
    assert bbox(mask) == (192, 144, 449, 177)
    zero = nn.mask_from_outputs(outputs((160, 160, 64, 32, FACE, 0.9)), (320, 320), on(["Face_female"]),
                                ONES * 0, ONES * 0, 1, "Rectangle", 0)
    assert zero is not None and bbox(zero) == (160, 160, 161, 161)


def test_nms_runs_before_expansion_and_one_disables_it():
    pytest.importorskip("cv2")
    out = outputs((100, 100, 60, 60, FACE, 0.9), (104, 100, 60, 60, FACE, 0.8), (250, 250, 20, 20, FACE, 0.7))
    lines = []
    nn.mask_from_outputs(out, (320, 320), on(["Face_female"]), ONES, ONES, 0.5, "Rectangle", 0, log=lines.append)
    assert len(lines) == 2 and lines[0].startswith("Face female: score 0.89") and "x1 70.0" in lines[0]
    lines.clear()
    nn.mask_from_outputs(out, (320, 320), on(["Face_female"]), ONES, ONES, 1, "Rectangle", 0, log=lines.append)
    assert len(lines) == 3


def test_mask_shapes_and_entire_image():
    out = outputs((160, 160, 100, 60, FACE, 0.9))
    rect = nn.mask_from_outputs(out, (320, 320), on(["Face_female"]), ONES, ONES, 1, "Rectangle", 0)
    ellipse = nn.mask_from_outputs(out, (320, 320), on(["Face_female"]), ONES, ONES, 1, "Ellipse", 0)
    rounded = nn.mask_from_outputs(out, (320, 320), on(["Face_female"]), ONES, ONES, 1, "Rounded rectangle", 0.5)
    whole = nn.mask_from_outputs(out, (320, 320), on(["Face_female"]), ONES, ONES, 1, "Entire image", 0)
    count = lambda m: int(np.asarray(m.convert("L")).astype(bool).sum())
    assert bbox(rect) == bbox(ellipse) == bbox(rounded) == (110, 130, 211, 191)
    assert count(ellipse) < count(rounded) < count(rect)
    assert count(whole) == 320 * 320
    with pytest.raises(ValueError):
        nn.mask_from_outputs(out, (320, 320), on(["Face_female"]), ONES, ONES, 1, nn.ENTIRE_IMAGE_ALWAYS, 0)


class Recorder:
    def __init__(self):
        self.calls = []

    def rounded_rectangle(self, box, radius, fill):
        self.calls.append((box, radius))


@pytest.mark.parametrize("radius,expected", [
    (0.5, 15),                                              # fraction of half the shorter side (60 / 2 * 0.5)
    (1.0, 30),
    (2.0, round(2 * math.sqrt((100 ** 2 + 60 ** 2) / 524288))),  # unit scaled with the mask size
    (-7.0, 7),                                              # absolute pixels
])
def test_rounded_rectangle_radius_modes(radius, expected):
    draw = Recorder()
    nn._rounded_rectangle(draw, 10.0, 20.0, 110.0, 80.0, 100.0, 60.0, radius)
    assert [r for _, r in draw.calls] == [expected, expected]
    assert draw.calls[1][0] == (10, 20, 110, 80)


# ------------------------------------------------------------------------------------------------ filters
def picture(size=(96, 64)):
    rng = np.random.default_rng(3)
    return Image.fromarray(rng.integers(0, 256, (size[1], size[0], 3), dtype=np.uint8), "RGB")


def box_mask(size=(96, 64), box=(20, 10, 60, 40), value=255):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rectangle(box, value)
    return mask


def test_blur_radii_curve_endpoints():
    levels = np.array([0, 51, 255])
    assert np.allclose(nn.blur_radii(levels, 10, 3), [0, 10 * 0.2 ** 2, 10])  # linear strength (squared radius)
    assert np.allclose(nn.blur_radii(levels, 10, 4), [0, 2, 10])              # linear radius
    assert np.allclose(nn.blur_radii(levels, 10, 6), [10, 10, 10])            # uniform


def upstream_variable_blur(img, mask, radius, curve):
    """Literal sequential form of upstream's tree composite: each grey level pasted over the lowest level's blur."""
    m = np.asarray(mask)
    levels = np.unique(m)
    radii = np.power(levels / 255, (12 - 2 * curve) / curve) * radius
    base = img.filter(ImageFilter.GaussianBlur(float(radii[0])))
    for level, r in zip(levels[1:], radii[1:]):
        sel = Image.frombuffer("L", img.size, np.where(m == level, np.uint8(255), np.uint8(0)))
        base.paste(img.filter(ImageFilter.GaussianBlur(float(r))), sel)
    return base


def test_variable_blur_matches_upstream_composite_and_curve_extremes():
    img = picture()
    mask = box_mask().filter(ImageFilter.GaussianBlur(4))
    actual = nn.variable_blur(img, mask, 6, 3)
    assert np.array_equal(np.asarray(actual), np.asarray(upstream_variable_blur(img, mask, 6, 3)))
    assert np.array_equal(np.asarray(nn.variable_blur(img, mask, 6, 0)), np.asarray(img))
    assert np.array_equal(np.asarray(nn.variable_blur(img, mask, 6, 6.5)),
                          np.asarray(img.filter(ImageFilter.GaussianBlur(6))))
    rgba = img.convert("RGBA")
    assert nn.variable_blur(rgba, mask, 6, 4).mode == "RGBA"


@pytest.mark.parametrize("name,expected", [
    ("Gaussian Blur", lambda img: img.filter(ImageFilter.GaussianBlur(5))),
    ("Pixelate", lambda img: img.resize((round(96 / 4), round(64 / 4)), Image.Resampling.BILINEAR)
     .resize((96, 64), Image.Resampling.NEAREST)),
    ("Fill color", lambda img: Image.new("RGB", img.size, (255, 0, 255))),
])
def test_masked_filters_change_only_the_mask(name, expected):
    img, mask = picture(), box_mask()
    out = nn.apply_filter(img, mask, name, blur_radius=5, pixelation_factor=2, color="#ff00ff", blur_strength_curve=3)
    inside, outside = np.asarray(mask) == 255, np.asarray(mask) == 0
    assert np.array_equal(np.asarray(out)[outside], np.asarray(img)[outside])
    assert np.array_equal(np.asarray(out)[inside], np.asarray(expected(img))[inside])


def test_no_censor_and_disable_keep_the_image():
    img = picture()
    assert nn.apply_filter(img, box_mask(), "No censor") is img
    assert nn.apply_filter(img, box_mask(), "Disable") is img
    with pytest.raises(ValueError):
        nn.apply_filter(img, box_mask(), "Blur")


# ------------------------------------------------------------------------------------------------ workflows
def fixed_detector(mask_box=(20, 10, 60, 40), calls=None):
    def detector(cfg, img, model_path=None, log=None):
        if calls is not None:
            calls.append((img.copy(), dict(cfg)))
        if mask_box is None:
            return None
        mask = Image.new("1", img.size, 0)
        ImageDraw.Draw(mask).rectangle(mask_box, 1)
        return mask
    return detector


def test_generation_censor_returns_before_copy_and_optional_mask():
    img = picture()
    cfg = nn.generation_config({"gen_filter_type": "Fill color", "fill_color": "#00ff00", "save_mask": True})
    out, before, mask = nn.censor_generation(img, cfg, detector=fixed_detector())
    assert np.array_equal(np.asarray(before), np.asarray(img)) and before is not img
    assert mask.mode == "L" and set(np.unique(np.asarray(mask))) == {0, 255}  # blend radius 0 keeps the box sharp
    assert out.getpixel((30, 20)) == (0, 255, 0) and out.getpixel((80, 50)) == img.getpixel((80, 50))
    cfg = nn.generation_config({"gen_filter_type": "Variable blur", "save_before_censor": False})
    out, before, mask = nn.censor_generation(img, cfg, detector=fixed_detector())
    assert before is None and mask is None and out.size == img.size


def test_generation_censor_skips_without_detection_or_when_disabled():
    img, calls = picture(), []
    cfg = nn.generation_config({})
    assert nn.censor_generation(img, cfg, detector=fixed_detector(None, calls)) == (img, None, None)
    assert len(calls) == 1
    calls.clear()
    disabled = nn.generation_config({"gen_filter_type": "Disable"})
    assert nn.censor_generation(img, disabled, detector=fixed_detector(calls=calls)) == (img, None, None)
    assert calls == []


def test_generation_blend_and_blur_radii_scale_with_the_image(monkeypatch):
    seen = {}

    def capture(img, mask, filter_type, **settings):
        seen.update(settings, filter_type=filter_type, mask=mask)
        return img
    monkeypatch.setattr(nn, "apply_filter", capture)
    img = Image.new("RGB", (1024, 1024))
    cfg = nn.generation_config({"gen_filter_type": "Variable blur", "blur_radius": 7})
    nn.censor_generation(img, cfg, detector=fixed_detector((100, 100, 400, 400)))
    assert seen["blur_radius"] == 14 and seen["filter_type"] == "Variable blur"
    assert len(np.unique(np.asarray(seen["mask"]))) > 2  # variable-blur blend radius 10 * 2


def test_preview_censor_uses_live_filter_and_generation_blend():
    img = picture()
    cfg = nn.generation_config({"gen_filter_type": "Variable blur", "live_preview_filter_type": "Fill color",
                                "fill_color": "#ff0000"})
    out = nn.censor_preview(img, cfg, detector=fixed_detector())
    assert out.getpixel((40, 25)) == (255, 0, 0)
    assert out.getpixel((60, 41)) not in ((255, 0, 0), img.getpixel((60, 41)))  # softened by the 10px blend
    cfg = nn.generation_config({"live_preview_filter_type": "Disable"})
    assert nn.censor_preview(img, cfg, detector=fixed_detector()) is img


def test_preview_b64_is_reencoded_only_when_censored():
    from sdfx.images import to_jpeg_b64
    data = to_jpeg_b64(picture(), max_side=96)
    cfg = nn.generation_config({"live_preview_filter_type": "Fill color"})
    assert nn.censor_preview_b64(data, cfg, detector=fixed_detector(None)) == data
    censored = nn.censor_preview_b64(data, cfg, detector=fixed_detector())
    import base64
    with Image.open(io.BytesIO(base64.b64decode(censored))) as decoded:
        assert decoded.size == (96, 64) and max(decoded.convert("RGB").getpixel((40, 25))) < 20


def test_tool_unions_custom_mask_and_keeps_it_without_detection(tmp_path):
    img = picture()
    custom = tmp_path / "mask.png"
    box_mask((48, 32), (36, 20, 47, 31)).save(custom)  # resized to the image: bottom-right corner
    params = {"mask_path": str(custom), "filter_type": "Fill color", "fill_color": "#0000ff", "output_mask": True}
    out, extra = nn.censor_tool(img, params, detector=fixed_detector())
    assert out.getpixel((30, 20)) == (0, 0, 255) and out.getpixel((90, 60)) == (0, 0, 255)
    assert out.getpixel((5, 60)) == img.getpixel((5, 60))
    assert extra["censored"] == 1 and extra["detected"] == 1 and extra["mask"].mode == "L"
    out, extra = nn.censor_tool(img, params, detector=fixed_detector(None))  # upstream 20240829 fix
    assert out.getpixel((90, 60)) == (0, 0, 255) and out.getpixel((30, 20)) == img.getpixel((30, 20))
    assert extra["detected"] == 0 and extra["censored"] == 1


def test_tool_entire_image_always_and_disabled_detection(tmp_path):
    img, calls = picture(), []
    out, extra = nn.censor_tool(img, {"mask_shape": nn.ENTIRE_IMAGE_ALWAYS, "filter_type": "Fill color"},
                                detector=fixed_detector(calls=calls))
    assert calls == [] and set(np.unique(np.asarray(out))) == {0} and extra["censored"] == 1
    out, extra = nn.censor_tool(img, {"enable_nudenet": False}, detector=fixed_detector(calls=calls))
    assert calls == [] and extra == {"censored": 0, "detected": 0}
    assert np.array_equal(np.asarray(out), np.asarray(img)) and out is not img


def test_tool_defaults_scale_blur_but_explicit_radius_is_absolute(monkeypatch):
    seen = []
    monkeypatch.setattr(nn, "apply_filter", lambda img, mask, f, **s: seen.append((f, s)) or img)
    img = Image.new("RGB", (1024, 1024))
    nn.censor_tool(img, {}, detector=fixed_detector((0, 0, 100, 100)))
    nn.censor_tool(img, {"blur_radius": 4, "filter_type": "Pixelate", "pixelation_factor": 3},
                   detector=fixed_detector((0, 0, 100, 100)))
    assert seen[0][0] == "Variable blur" and seen[0][1]["blur_radius"] == 20
    assert seen[1][1]["blur_radius"] == 4 and seen[1][1]["pixelation_factor"] == 3
    with pytest.raises(ValueError):
        nn.censor_tool(img, {"mask_shape": "Star"})
    with pytest.raises(ValueError):
        nn.censor_tool(img, {"thresholds": [0.1, 0.2]}, detector=fixed_detector())


def test_tool_receives_upstream_detection_settings():
    calls = []
    nn.censor_tool(picture(), {"nms_threshold": 1, "mask_shape": "Rounded rectangle", "rectangle_round_radius": -3,
                               "thresholds": [0.4] * 18, "expand_vertical": [2] * 18},
                   detector=fixed_detector(calls=calls))
    cfg = calls[0][1]
    assert cfg["nms_threshold"] == 1 and cfg["mask_shape"] == "Rounded rectangle"
    assert cfg["rectangle_round_radius"] == -3 and np.allclose(cfg["thresholds"], 0.4)
    assert np.allclose(cfg["expand_vertical"], 2) and np.allclose(cfg["expand_horizontal"], 1)


def test_image_tool_dispatch_returns_mask_output(monkeypatch, tmp_path):
    monkeypatch.setattr(nn, "detect_with", fixed_detector())
    out, extra = image_tools.run("nsfw_checker", picture(), {"output_mask": True, "filter_type": "Gaussian Blur"})
    assert out.size == (96, 64) and "mask" in extra and extra["censored"] == 1


def test_base_engine_image_tool_saves_result_and_mask(monkeypatch, tmp_path):
    from sdfx.dryrun import FakeEngine
    from sdfx.protocol import Emitter, JobContext
    monkeypatch.setattr(nn, "detect_with", fixed_detector())
    source = tmp_path / "in.png"
    picture().save(source)
    result = FakeEngine().image_tool({"tool": "nsfw_checker", "image_path": str(source), "output_dir": str(tmp_path),
                                      "output_mask": True}, JobContext(Emitter(io.BytesIO()), "t"))
    assert Path(result["path"]).is_file() and Path(result["outputs"]["mask"]).is_file()
    assert result["info"] == {"censored": 1, "detected": 1}


# ------------------------------------------------------------------------------------------------ model
def test_bundled_model_is_the_pinned_upstream_file():
    path = nn.default_model_path()
    assert path == ROOT / "models" / "nudenet" / "best.onnx"
    data = path.read_bytes()
    assert len(data) == nn.MODEL_SIZE == 12125773
    assert hashlib.sha256(data).hexdigest() == nn.MODEL_SHA256
    assert hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest() == "12f4f7808735fb2f8a0fb9beca635a1426bfdcad"
    assert nn.verify_model(path) == path.resolve()


def test_model_verification_rejects_missing_and_altered_files(tmp_path):
    with pytest.raises(FileNotFoundError):
        nn.verify_model(tmp_path / "missing.onnx")
    small = tmp_path / "small.onnx"
    small.write_bytes(b"onnx")
    with pytest.raises(ValueError, match="refusing"):
        nn.verify_model(small)
    same_size = tmp_path / "same.onnx"
    same_size.write_bytes(b"\0" * nn.MODEL_SIZE)
    with pytest.raises(ValueError, match="SHA-256"):
        nn.verify_model(same_size)
    with pytest.raises(ValueError):
        nn.session(same_size)


def test_job_config_reports_configuration_and_model_errors(monkeypatch, tmp_path):
    from sdfx.protocol import EngineError, INVALID_PARAMS, UNSUPPORTED
    assert nn.job_config({}) is None and nn.job_config({"nsfw_checker": {"enabled": False}}) is None
    with pytest.raises(EngineError) as error:
        nn.job_config({"nsfw_checker": {"gen_filter_type": "Blur"}})
    assert error.value.code == INVALID_PARAMS
    monkeypatch.setattr(nn, "default_model_path", lambda: tmp_path / "missing.onnx")
    with pytest.raises(EngineError) as error:
        nn.job_config({"nsfw_checker": {}})
    assert error.value.code == UNSUPPORTED and "not found" in error.value.message
    disabled = nn.job_config({"nsfw_checker": {"gen_filter_type": "Disable", "live_preview_filter_type": "Disable"}})
    assert disabled["gen_filter_type"] == "Disable"
    assert nn.preview_config({"nsfw_checker": {"live_preview_filter_type": "Disable"}}) is None
    assert nn.preview_config({"nsfw_checker": {}})["live_preview_filter_type"] == "Gaussian Blur"


def test_censor_final_and_auxiliary_entries(tmp_path):
    img = picture()
    cfg = nn.generation_config({"gen_filter_type": "Pixelate", "save_mask": True})
    out, aux = nn.censor_final(img, cfg, detector=fixed_detector())
    assert [s for s, _ in aux] == ["-before-censor", "-censor-mask"]
    entries = nn.save_auxiliaries({"path": "x", "seed": 7, "subseed": 8, "width": 1, "height": 1}, aux, str(tmp_path), 3)
    assert [e["output_suffix"] for e in entries] == ["-before-censor", "-censor-mask"]
    assert all(e["auxiliary_of"] == 3 and e["seed"] == 7 and e["subseed"] == 8 for e in entries)
    with Image.open(entries[1]["path"]) as mask:
        assert mask.mode == "RGB" and mask.size == img.size
    assert nn.censor_final(img, None) == (img, [])


def test_real_session_shape_and_clean_image_has_no_detection():
    pytest.importorskip("onnxruntime")
    pytest.importorskip("cv2")
    s = nn.session()
    assert s.get_inputs()[0].shape == [1, 3, 320, 320]
    assert s.get_outputs()[0].shape == [1, 22, 2100]
    assert nn.session() is s
    white = Image.new("RGB", (512, 768), "white")
    assert nn.detect(white, np.asarray(nn.DEFAULT_THRESHOLDS), ONES, ONES, 0.5, "Ellipse", 0.5) is None
    assert nn.available()
