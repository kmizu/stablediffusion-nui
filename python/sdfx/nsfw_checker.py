"""NudeNet NSFW Censor, re-implemented from w-e-w/sd-webui-nudenet-nsfw-censor
(pinned 1fb21cf9c3f72915ea1b5091bd51aedf91eae9d0, AGPL-3.0; detector weight from notAI-tech/NudeNet, AGPL-3.0).

One implementation serves the generation hook `nsfw_checker` (final images and live previews), the Extras plugin
and the `image_tool` request (tool `nsfw_checker`):

* detection: the bundled NudeNet 320n ONNX detector (models/nudenet/best.onnx, SHA-256 checked) on a cached CPU
  session; RGB input letterboxed onto a white 320x320 canvas; 18 labels in model order, each with a confidence
  threshold (1 disables it) and horizontal / vertical expansion multipliers; optional non-maximum suppression
  before the expansion; boxes restored to the original landscape / portrait geometry.
* masks: Ellipse, Rectangle, Rounded rectangle (radius ratio in (0, 1], scaled unit above 1, absolute pixels below
  0), Entire image (when anything is detected) and, for Extras, Entire image (always). An optional custom mask is
  combined with the detected regions by union and kept when nothing is detected.
* filters: Variable blur (blur strength follows the blended mask's grey level along a curve), Gaussian Blur,
  Pixelate, Fill color, No censor (mask only) and Disable. Radii scale with the image diagonal as upstream does.
"""
from __future__ import annotations

import hashlib
import logging
import math
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable, Optional, Sequence

import numpy as np

# Model output order of the 18 NudeNet classes (upstream nudenet_labels_dict).
LABELS = (
    "Female_genitalia_covered", "Face_female", "Buttocks_exposed", "Female_breast_exposed",
    "Female_genitalia_exposed", "Male_breast_exposed", "Anus_exposed", "Feet_exposed", "Belly_covered",
    "Feet_covered", "Armpits_covered", "Armpits_exposed", "Face_male", "Belly_exposed", "Male_genitalia_exposed",
    "Anus_covered", "Female_breast_covered", "Buttocks_covered",
)
FRIENDLY = tuple(label.replace("_", " ") for label in LABELS)
DEFAULT_SELECTED = ("Anus exposed", "Female breast exposed", "Female genitalia exposed", "Male genitalia exposed")
# Upstream sets every category that is not selected to threshold 1 (never triggers).
DEFAULT_THRESHOLDS = tuple(0.25 if name in DEFAULT_SELECTED else 1.0 for name in FRIENDLY)
DEFAULT_EXPAND = (1.0,) * len(LABELS)

MASK_SHAPES = ("Ellipse", "Rectangle", "Rounded rectangle", "Entire image")
ENTIRE_IMAGE_ALWAYS = "Entire image (always)"
FILTERS = ("Variable blur", "Gaussian Blur", "Pixelate", "Fill color", "No censor", "Disable")

MODEL_SHA256 = "9832f15515bdb06bcb5a77beb60bc8ea54439bd7ecbaac46dac3b760b3dd13cc"
MODEL_SIZE = 12125773
INPUT_SIZE = 320
# Upstream's thread pool is unbounded (1024 workers for up to 256 grey levels); keep it small.
MAX_BLUR_WORKERS = 4

# Generation (AlwaysOn) settings: upstream's Settings > NudeNet NSFW Censor defaults.
GENERATION_DEFAULTS = {
    "gen_filter_type": "Gaussian Blur",
    "live_preview_filter_type": "Gaussian Blur",
    "save_before_censor": True,
    "save_mask": False,
    "mask_shape": "Ellipse",
    "rectangle_round_radius": 0.5,
    "blur_radius": 10.0,
    "mask_blend_radius": 0.0,
    "mask_blend_radius_variable_blur": 10.0,
    "blur_strength_curve": 3.0,
    "pixelation_factor": 5.0,
    "fill_color": "#000000",
    "nms_threshold": 0.5,
    "verbose_detection": False,
    "thresholds": list(DEFAULT_THRESHOLDS),
    "expand_horizontal": list(DEFAULT_EXPAND),
    "expand_vertical": list(DEFAULT_EXPAND),
    # which checkers run (NudeNet keeps its default so settings from before indices 83 / 231 behave the same)
    "nudenet": True,
    "safety_checker": False,
    "safety_checker_live_preview": False,
    "nsfwjs": False,
    "nsfwjs_safety_level": "safe",
}


# ------------------------------------------------------------------------------------------------ config
def _number(cfg: dict, key: str, low: Optional[float] = None, high: Optional[float] = None) -> float:
    value = cfg.get(key)
    if isinstance(value, bool):
        raise ValueError(f"{key} must be a number")
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise ValueError(f"{key} must be a number") from None
    if not math.isfinite(number) or (low is not None and number < low) or (high is not None and number > high):
        bounds = f" in {low if low is not None else '-inf'}..{high if high is not None else 'inf'}"
        raise ValueError(f"{key} must be a finite number{bounds} (got {value})")
    return number


def _vector(value, key: str, default: Sequence[float], low: float = -math.inf) -> np.ndarray:
    if value is None:
        return np.asarray(default, dtype=np.float32)
    if isinstance(value, str):
        value = [part for part in value.replace(";", ",").split(",") if part.strip()]
    if not isinstance(value, (list, tuple)) or len(value) != len(LABELS):
        raise ValueError(f"{key} must list {len(LABELS)} numbers in NudeNet label order")
    out = []
    for item in value:
        try:
            number = float(item)
        except (TypeError, ValueError):
            raise ValueError(f"{key} must list {len(LABELS)} numbers in NudeNet label order") from None
        if isinstance(item, bool) or not math.isfinite(number) or number < low:
            raise ValueError(f"{key} values must be finite numbers >= {low} (got {item})")
        out.append(number)
    return np.asarray(out, dtype=np.float32)


def _color(value) -> str:
    from PIL import ImageColor
    text = str(value if value is not None else "#000000").strip()
    try:
        ImageColor.getrgb(text)
    except ValueError:
        raise ValueError(f"fill_color must be a colour such as #000000 (got {value!r})") from None
    return text


def _choice(value, key: str, options: Sequence[str]) -> str:
    if value not in options:
        raise ValueError(f"{key} must be one of {', '.join(options)} (got {value!r})")
    return value


def _flag(value, default: bool) -> bool:
    from .hooks import coerce_bool
    return coerce_bool(value, default)


def generation_config(raw: Optional[dict]) -> dict:
    """Validated generation hook settings (upstream defaults for omitted keys)."""
    cfg = dict(GENERATION_DEFAULTS)
    cfg.update({k: v for k, v in (raw or {}).items() if k != "enabled"})
    return {
        "gen_filter_type": _choice(cfg["gen_filter_type"], "gen_filter_type", FILTERS),
        "live_preview_filter_type": _choice(cfg["live_preview_filter_type"], "live_preview_filter_type", FILTERS),
        "save_before_censor": _flag(cfg["save_before_censor"], True),
        "save_mask": _flag(cfg["save_mask"], False),
        "mask_shape": _choice(cfg["mask_shape"], "mask_shape", MASK_SHAPES),
        "rectangle_round_radius": _number(cfg, "rectangle_round_radius"),
        "blur_radius": _number(cfg, "blur_radius", 0),
        "mask_blend_radius": _number(cfg, "mask_blend_radius", 0),
        "mask_blend_radius_variable_blur": _number(cfg, "mask_blend_radius_variable_blur", 0),
        "blur_strength_curve": _number(cfg, "blur_strength_curve"),
        "pixelation_factor": _number(cfg, "pixelation_factor", 1e-6),
        "fill_color": _color(cfg["fill_color"]),
        "nms_threshold": _number(cfg, "nms_threshold", 0, 1),
        "verbose_detection": _flag(cfg["verbose_detection"], False),
        "thresholds": _vector(cfg["thresholds"], "thresholds", DEFAULT_THRESHOLDS),
        "expand_horizontal": _vector(cfg["expand_horizontal"], "expand_horizontal", DEFAULT_EXPAND, 0.0),
        "expand_vertical": _vector(cfg["expand_vertical"], "expand_vertical", DEFAULT_EXPAND, 0.0),
        "nudenet": _flag(cfg["nudenet"], True),
        "safety_checker": _flag(cfg["safety_checker"], False),
        "safety_checker_live_preview": _flag(cfg["safety_checker_live_preview"], False),
        "nsfwjs": _flag(cfg["nsfwjs"], False),
        "nsfwjs_safety_level": _safety().check_level(cfg["nsfwjs_safety_level"]),
    }


def _safety():
    from . import nsfw_safety
    return nsfw_safety


def nudenet_active(cfg: dict, preview: bool = False) -> bool:
    key = "live_preview_filter_type" if preview else "gen_filter_type"
    return bool(cfg.get("nudenet", True)) and cfg[key] != "Disable"


def nsfwjs_active(cfg: dict) -> bool:
    """NSFWJS runs unless it is off or the level is "explicit" (which never flags)."""
    return bool(cfg.get("nsfwjs")) and bool(_safety().SAFETY_LEVELS[cfg.get("nsfwjs_safety_level", "safe")])


def safety_active(cfg: dict, preview: bool = False) -> bool:
    return bool(cfg.get("safety_checker")) and (not preview or bool(cfg.get("safety_checker_live_preview")))


def scale_factor(size) -> float:
    """Upstream radius scale: image diagonal relative to a 512x512 image."""
    return math.sqrt((size[0] ** 2 + size[1] ** 2) / 524288)


# ------------------------------------------------------------------------------------------------ model
def default_model_path() -> Path:
    return Path(__file__).resolve().parents[2] / "models" / "nudenet" / "best.onnx"


_verified: dict = {}
_sessions: dict = {}
_session_lock = threading.Lock()


def verify_model(path) -> Path:
    """Refuse anything but the exact pinned upstream weight (size and SHA-256), cached per file state."""
    path = Path(path).resolve()
    try:
        stat = path.stat()
    except OSError:
        raise FileNotFoundError(f"NudeNet model not found: {path} (reinstall models/nudenet/best.onnx)") from None
    key = (str(path), stat.st_size, stat.st_mtime_ns)
    if _verified.get(str(path)) == key:
        return path
    if stat.st_size != MODEL_SIZE:
        raise ValueError(f"NudeNet model {path} has {stat.st_size} bytes, expected {MODEL_SIZE}; refusing to load it")
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    if digest.hexdigest() != MODEL_SHA256:
        raise ValueError(f"NudeNet model {path} SHA-256 {digest.hexdigest()} does not match the pinned "
                         f"{MODEL_SHA256}; refusing to load it")
    _verified[str(path)] = key
    return path


def session(model_path=None):
    """Cached CPU ONNX Runtime session (upstream recommends CPU: the detector is small)."""
    path = verify_model(model_path or default_model_path())
    with _session_lock:
        cached = _sessions.get(str(path))
        if cached is not None and cached[0] == _verified.get(str(path)):
            return cached[1]
        try:
            import onnxruntime
        except ImportError as exc:
            raise ImportError("NudeNet NSFW Censor needs onnxruntime (pip install onnxruntime==1.23.2)") from exc
        providers = onnxruntime.get_available_providers()
        provider = "CPUExecutionProvider" if "CPUExecutionProvider" in providers else providers[0]
        value = onnxruntime.InferenceSession(str(path), providers=[provider])
        _sessions[str(path)] = (_verified.get(str(path)), value)
        return value


def preprocess(img, width: int = INPUT_SIZE, height: int = INPUT_SIZE) -> np.ndarray:
    """Resize to fit, centre on a white canvas, NCHW float32 in 0..1 (upstream pre_process_pil)."""
    from PIL import Image
    if img.mode != "RGB":
        img = img.convert("RGB")
    w, h = img.size
    new_size = (width, round(height * h / w)) if w > h else (round(width * w / h), height)
    resized = img.resize(new_size)
    canvas = Image.new(resized.mode, (width, height), (255, 255, 255))
    canvas.paste(resized, ((width - new_size[0]) // 2, (height - new_size[1]) // 2))
    return np.expand_dims(np.array(canvas, dtype=np.float32).transpose(2, 0, 1) / 255.0, axis=0)


# ------------------------------------------------------------------------------------------------ masks
def _rounded_rectangle(draw, x1, y1, x2, y2, w, h, radius):
    if radius > 1:  # scale with the mask size
        r = radius * math.sqrt((w ** 2 + h ** 2) / 524288)
    elif radius < 0:  # absolute pixels
        r = -radius
    else:  # fraction of the shorter side's half
        r = (w if w < h else h) / 2 * radius
    draw.rounded_rectangle((x1, y1, x2, y2), round(r), 1)
    draw.rounded_rectangle((round(x1), round(y1), round(x2), round(y2)), round(r), 1)


def _draw(shape: str):
    if shape == "Ellipse":
        return lambda draw, x1, y1, x2, y2, w, h, r: draw.ellipse((x1, y1, x2, y2), 1)
    if shape == "Rectangle":
        return lambda draw, x1, y1, x2, y2, w, h, r: draw.rectangle((x1, y1, x2, y2), 1)
    if shape == "Rounded rectangle":
        return _rounded_rectangle
    return None


def mask_from_outputs(outputs, img_size, thresholds, expand_horizontal, expand_vertical, nms_threshold: float,
                      mask_shape: str, rectangle_round_radius: float, input_size=(INPUT_SIZE, INPUT_SIZE),
                      log: Optional[Callable[[str], None]] = None):
    """Binary ('1') PIL mask of the detections above their category threshold, or None (calculate_censor_mask)."""
    from PIL import Image, ImageDraw
    _choice(mask_shape, "mask_shape", MASK_SHAPES)
    thresholds = np.asarray(thresholds, dtype=np.float32)
    expand_horizontal = np.asarray(expand_horizontal, dtype=np.float32)
    expand_vertical = np.asarray(expand_vertical, dtype=np.float32)
    # rows: [x_center, y_center, width, height, score_0 .. score_17]
    raw = np.asarray(outputs[0], dtype=np.float32)
    rows = raw.reshape(raw.shape[-2], raw.shape[-1]).T
    scores_all = rows[:, 4:]
    best = np.argmax(scores_all, axis=1)
    keep = np.max(scores_all, axis=1) > thresholds[best]
    if not np.any(keep):
        return None
    draw_func = _draw(mask_shape)
    if draw_func is None:
        return Image.new("1", img_size, 1)
    mask = Image.new("1", img_size, 0)
    draw = ImageDraw.Draw(mask)
    kept = rows[keep]
    boxes = np.array(kept[:, :4], dtype=np.float32)
    class_index = best[keep]
    scores = kept[:, 4:][np.arange(kept.shape[0]), class_index]
    boxes[:, 0:2] -= boxes[:, 2:4] / 2  # centre -> top-left
    if nms_threshold < 1:
        import cv2
        picked = np.asarray(cv2.dnn.NMSBoxes(boxes, scores, 0, nms_threshold), dtype=np.int64).reshape(-1)
        boxes, scores, class_index = boxes[picked], scores[picked], class_index[picked]
    offset = abs(img_size[0] - img_size[1]) / 2
    if img_size[0] > img_size[1]:
        boxes *= img_size[0] / input_size[0]
        boxes[:, 1] -= offset
    else:
        boxes *= img_size[1] / input_size[1]
        boxes[:, 0] -= offset
    wh = boxes[:, 2:4] * np.vstack((expand_horizontal[class_index], expand_vertical[class_index])).T
    boxes[:, 0:2] -= (wh - boxes[:, 2:4]) / 2
    boxes[:, 2:4] = boxes[:, 0:2] + wh  # x1 y1 x2 y2
    boxes = boxes.round()
    lines = []
    for i in range(scores.shape[0]):
        x1, y1, x2, y2 = (float(v) for v in boxes[i])
        w, h = float(wh[i][0]), float(wh[i][1])
        draw_func(draw, x1, y1, x2, y2, w, h, rectangle_round_radius)
        lines.append(f"{FRIENDLY[class_index[i]]}: score {float(scores[i])}, x1 {x1} y1 {y1}, w {round(w)} h {round(h)}, "
                     f"x2 {x2} y2 {y2}")
    if log is not None:
        for line in lines:
            log(line)
    return mask


def detect(img, thresholds, expand_horizontal, expand_vertical, nms_threshold: float, mask_shape: str,
           rectangle_round_radius: float, model_path=None, log=None):
    """Run NudeNet on a PIL image and return its censor mask ('1') or None."""
    s = session(model_path)
    inputs = s.get_inputs()[0]
    shape = inputs.shape
    width = shape[2] if isinstance(shape[2], int) else INPUT_SIZE
    height = shape[3] if isinstance(shape[3], int) else INPUT_SIZE
    outputs = s.run(None, {inputs.name: preprocess(img, width, height)})
    return mask_from_outputs(outputs, img.size, thresholds, expand_horizontal, expand_vertical, nms_threshold,
                             mask_shape, rectangle_round_radius, (width, height), log)


def detect_with(cfg: dict, img, model_path=None, log=None):
    return detect(img, cfg["thresholds"], cfg["expand_horizontal"], cfg["expand_vertical"], cfg["nms_threshold"],
                  cfg["mask_shape"], cfg["rectangle_round_radius"], model_path,
                  log if cfg.get("verbose_detection") else None)


# ------------------------------------------------------------------------------------------------ filters
def _combine(img, mask, processed):
    canvas = img.copy()
    canvas.paste(processed, mask)
    return canvas


def blur_radii(levels, blur_radius: float, curve: float) -> np.ndarray:
    """Per grey level blur radius: (level / 255) ** ((12 - 2c) / c) * radius; 3 = linear strength, 4 = linear radius."""
    return np.power(np.asarray(levels) / 255, (12 - 2 * curve) / curve) * blur_radius


def variable_blur(img, control_mask, blur_radius: float = 10, blur_strength_curve: float = 3, **_):
    """Gaussian blur whose radius follows the mask's grey value; curve 0 = none, above 6 = uniform full blur."""
    from PIL import Image, ImageFilter
    if blur_strength_curve <= 0:
        return img.copy()
    if blur_strength_curve > 6:
        return img.filter(ImageFilter.GaussianBlur(blur_radius))
    control = np.asarray(control_mask.convert("L") if control_mask.mode != "L" else control_mask)
    levels = np.unique(control)
    radii = blur_radii(levels, blur_radius, blur_strength_curve)
    out = None

    def blurred(i):
        return np.asarray(img.filter(ImageFilter.GaussianBlur(float(radii[i]))))

    workers = max(1, min(MAX_BLUR_WORKERS, os.cpu_count() or 1, levels.size))
    with ThreadPoolExecutor(workers) as executor:
        for start in range(0, levels.size, workers):
            indices = range(start, min(levels.size, start + workers))
            for i, layer in zip(indices, executor.map(blurred, indices)):
                if out is None:
                    out = np.array(layer)  # the lowest level is the base layer, as in upstream
                else:
                    selected = control == levels[i]
                    out[selected] = layer[selected]
    return Image.fromarray(out, img.mode)


def gaussian_blur(img, mask, blur_radius: float = 10, **_):
    from PIL import ImageFilter
    return _combine(img, mask, img.filter(ImageFilter.GaussianBlur(blur_radius)))


def pixelate(img, mask, pixelation_factor: float = 5, **_):
    from PIL import Image
    small = tuple(max(1, round(d / pixelation_factor ** 2)) for d in img.size)
    return _combine(img, mask, img.resize(small, Image.Resampling.BILINEAR).resize(img.size, Image.Resampling.NEAREST))


def fill_color(img, mask, color: str = "#000000", **_):
    from PIL import Image
    return _combine(img, mask, Image.new(img.mode, img.size, color))


def apply_filter(img, mask, filter_type: str, **settings):
    _choice(filter_type, "filter_type", FILTERS)
    if filter_type == "Variable blur":
        return variable_blur(img, mask, **settings)
    if filter_type == "Gaussian Blur":
        return gaussian_blur(img, mask, **settings)
    if filter_type == "Pixelate":
        return pixelate(img, mask, **settings)
    if filter_type == "Fill color":
        return fill_color(img, mask, **settings)
    return img  # No censor / Disable


def _settings(cfg: dict, blur_radius: float) -> dict:
    return {"blur_radius": blur_radius, "blur_strength_curve": cfg["blur_strength_curve"],
            "pixelation_factor": cfg["pixelation_factor"], "color": cfg["fill_color"]}


# ------------------------------------------------------------------------------------------------ workflows
def censor_generation(img, cfg: dict, model_path=None, log=None, detector=None):
    """Final image censor (postprocess_image_after_composite): returns (image, before_censor or None, mask or None).

    `before_censor` / `mask` are only returned when something was detected and the matching save option is on.
    """
    from PIL import ImageFilter
    filter_type = cfg["gen_filter_type"]
    if filter_type == "Disable":
        return img, None, None
    mask = (detector or detect_with)(cfg, img, model_path, log)
    if mask is None:
        return img, None, None
    before = img.copy() if cfg["save_before_censor"] else None
    factor = scale_factor(img.size)
    blend = cfg["mask_blend_radius_variable_blur"] if filter_type == "Variable blur" else cfg["mask_blend_radius"]
    mask = mask.convert("L").filter(ImageFilter.GaussianBlur(blend * factor))
    censored = apply_filter(img, mask, filter_type, **_settings(cfg, cfg["blur_radius"] * factor))
    return censored, before, mask if cfg["save_mask"] else None


def censor_preview(img, cfg: dict, model_path=None, detector=None):
    """Live preview censor (assign_current_image wrappers): the SD safety checker when its live preview option is on
    (NUI addition; upstream does not check previews), one NSFWJS pass, then NudeNet's live preview filter. Returns
    `img` itself when nothing changed."""
    out = img
    if safety_active(cfg, preview=True):
        out, _ = _safety().safety_censor(out)
    if nsfwjs_active(cfg):
        out, _ = _safety().nsfwjs_censor(out, cfg["nsfwjs_safety_level"])
    if nudenet_active(cfg, preview=True):
        out = _nudenet_preview(out, cfg, model_path, detector)
    return out


def _nudenet_preview(img, cfg: dict, model_path=None, detector=None):
    """NudeNet live preview censor. Like upstream, the blend radius follows the generation filter while the live
    preview filter is applied."""
    from PIL import ImageFilter
    filter_type = cfg["live_preview_filter_type"]
    if filter_type == "Disable":
        return img
    mask = (detector or detect_with)(cfg, img, model_path, None)
    if mask is None:
        return img
    factor = scale_factor(img.size)
    blend = (cfg["mask_blend_radius_variable_blur"] if cfg["gen_filter_type"] == "Variable blur"
             else cfg["mask_blend_radius"])
    mask = mask.convert("L").filter(ImageFilter.GaussianBlur(blend * factor))
    return apply_filter(img, mask, filter_type, **_settings(cfg, cfg["blur_radius"] * factor))


def censor_preview_b64(data: str, cfg: dict, model_path=None, detector=None) -> str:
    """Censor a base64 JPEG preview and re-encode it at the same size."""
    import base64
    import io
    from PIL import Image
    from .images import to_jpeg_b64
    with Image.open(io.BytesIO(base64.b64decode(data))) as decoded:
        img = decoded.convert("RGB")
    out = censor_preview(img, cfg, model_path, detector)
    return data if out is img else to_jpeg_b64(out, max_side=max(out.size))


def _param(p: dict, key: str, default, low: Optional[float] = None, high: Optional[float] = None) -> float:
    value = p.get(key)
    return _number({key: default if value is None else value}, key, low, high)


def _optional(p: dict, key: str):
    value = p.get(key)
    return None if value is None or (isinstance(value, str) and not value.strip()) else value


def censor_tool(img, params: dict, model_path=None, log=None, detector=None):
    """Extras / image_tool censor (/nudenet/censor semantics): returns (image, extras).

    Omitted values use upstream's settings defaults, with the blur radius scaled by the image diagonal; an explicit
    `blur_radius` is used as is (upstream's override settings). `mask_path` adds a custom grey mask (union with the
    detections); `mask_shape` "Entire image (always)" censors the whole image without detection. extras["mask"] is
    the blended mask when `output_mask` is set and something is censored.
    """
    from PIL import Image, ImageFilter
    p = dict(params or {})
    checker = _choice(_optional(p, "checker") or "nudenet", "checker", TOOL_CHECKERS)
    if checker != "nudenet":
        return _classify_tool(img, checker, p, log)
    filter_type = _choice(_optional(p, "filter_type") or "Variable blur", "filter_type", FILTERS)
    mask_shape = _choice(_optional(p, "mask_shape") or "Ellipse", "mask_shape", MASK_SHAPES + (ENTIRE_IMAGE_ALWAYS,))
    enable_nudenet = _flag(p.get("enable_nudenet"), True)
    censor_mask = None
    detected = False
    if mask_shape == ENTIRE_IMAGE_ALWAYS:
        censor_mask = Image.new("L", img.size, 255)
    else:
        mask_path = _optional(p, "mask_path")
        if mask_path:
            with Image.open(mask_path) as custom:
                censor_mask = custom.convert("L").resize(img.size)
        if enable_nudenet:
            cfg = {
                "thresholds": _vector(p.get("thresholds"), "thresholds", DEFAULT_THRESHOLDS),
                "expand_horizontal": _vector(p.get("expand_horizontal"), "expand_horizontal", DEFAULT_EXPAND, 0.0),
                "expand_vertical": _vector(p.get("expand_vertical"), "expand_vertical", DEFAULT_EXPAND, 0.0),
                "nms_threshold": _param(p, "nms_threshold", GENERATION_DEFAULTS["nms_threshold"], 0, 1),
                "mask_shape": mask_shape,
                "rectangle_round_radius": _param(p, "rectangle_round_radius", GENERATION_DEFAULTS["rectangle_round_radius"]),
                "verbose_detection": _flag(p.get("verbose_detection"), False),
            }
            found = (detector or detect_with)(cfg, img, model_path, log)
            if found is not None:
                detected = True
                found = found.convert("L")
                if censor_mask is not None:
                    censor_mask.paste(found, found)
                else:
                    censor_mask = found
    if censor_mask is None:
        return img.copy(), {"censored": 0, "detected": 0}
    factor = scale_factor(img.size)
    default_blend = 10.0 if filter_type == "Variable blur" else 0.0
    blend = _param(p, "mask_blend_radius", default_blend, 0)
    censor_mask = censor_mask.filter(ImageFilter.GaussianBlur(blend * factor))
    blur_radius = (_param(p, "blur_radius", 0, 0) if p.get("blur_radius") is not None
                   else GENERATION_DEFAULTS["blur_radius"] * factor)
    settings = {"blur_radius": blur_radius,
                "blur_strength_curve": _param(p, "blur_strength_curve", GENERATION_DEFAULTS["blur_strength_curve"]),
                "pixelation_factor": _param(p, "pixelation_factor", GENERATION_DEFAULTS["pixelation_factor"], 1e-6),
                "color": _color(p.get("fill_color", GENERATION_DEFAULTS["fill_color"]))}
    out = apply_filter(img, censor_mask, filter_type, **settings)
    extras = {"censored": 1, "detected": int(detected)}
    if _flag(p.get("output_mask"), False):
        extras["mask"] = censor_mask
    return (out.copy() if out is img else out), extras


TOOL_CHECKERS = ("nudenet", "nsfwjs", "safety_checker")


def _classify_tool(img, checker: str, p: dict, log=None):
    """Whole-image check for Extras / image_tool: the SD safety checker (black image) or NSFWJS (blur radius 20, one
    pass as for an Extras save). `report_only` keeps the image (NSFWJS's /safety/predict: is_nsfw only)."""
    from PIL import ImageFilter
    safety = _safety()
    report_only = _flag(p.get("report_only"), False)
    name = "SD safety checker" if checker == "safety_checker" else "NSFWJS filter"
    try:
        if checker == "safety_checker":
            flagged = safety.safety_flags([img])[0]
            extras = {"checker": checker, "is_nsfw": int(flagged)}
            out = safety.black(img) if flagged and not report_only else img.copy()
        else:
            level = safety.check_level(p.get("safety_level"))
            flagged, label, scores = safety.nsfwjs_classify(img, level)
            extras = {"checker": checker, "is_nsfw": int(flagged), "safety_level": level, "label": label or ""}
            if scores is not None:
                extras.update({f"score_{label_}": round(float(score), 6)
                               for label_, score in zip(safety.NSFWJS_LABELS, scores)})
            blurred = flagged and not report_only
            out = img.filter(ImageFilter.GaussianBlur(safety.NSFWJS_BLUR_RADIUS)) if blurred else img.copy()
    except ValueError:
        raise
    except (ImportError, OSError) as exc:
        raise _engine_error(exc, name) from exc
    extras.update(censored=int(flagged and not report_only), detected=int(flagged))
    if log is not None:
        log(f"{name}: {'NSFW' if flagged else 'not NSFW'}"
            + (f" (top class {extras['label']})" if extras.get("label") else ""))
    return out, extras


# ------------------------------------------------------------------------------------------------ engine glue
AUXILIARY_SUFFIXES = ("-before-censor", "-censor-mask")


def available() -> bool:
    """Engine feature check: onnxruntime, OpenCV (NMS) and the bundled weight present."""
    from importlib.util import find_spec
    return bool(find_spec("onnxruntime") and find_spec("cv2") and find_spec("PIL") and default_model_path().is_file())


def _engine_error(exc: Exception, name: str = "NudeNet NSFW Censor"):
    from .protocol import INVALID_PARAMS, UNSUPPORTED, EngineError
    code = INVALID_PARAMS if isinstance(exc, ValueError) and "refusing to load" not in str(exc) else UNSUPPORTED
    return EngineError(code, f"{name}: {exc}")


def job_config(hooks: Optional[dict], ctx=None) -> Optional[dict]:
    """Validated generation settings for the `nsfw_checker` hook, or None. Loads (and hash-checks; the SD safety
    checker and NSFWJS download on first use) every enabled model up front, so a missing or altered model fails the
    job before sampling instead of saving unchecked images."""
    from .hooks import hook_config
    raw = hook_config(hooks or {}, "nsfw_checker", {})
    if raw is None:
        return None
    try:
        cfg = generation_config(raw)
    except ValueError as exc:
        raise _engine_error(exc) from exc
    safety = _safety()

    def progress(name):
        if ctx is not None:
            return lambda line: ctx.log("info", f"{name}: {line}")
        return lambda line: logging.getLogger(__name__).info("%s: %s", name, line)
    steps = []
    if nudenet_active(cfg) or nudenet_active(cfg, preview=True):
        steps.append(("NudeNet NSFW Censor", session))
    if cfg["safety_checker"]:
        steps.append(("SD safety checker", lambda: safety.safety_model(progress=progress("SD safety checker"))))
    if nsfwjs_active(cfg):
        steps.append(("NSFWJS filter", lambda: safety.nsfwjs_session(progress=progress("NSFWJS filter"))))
    for name, load in steps:
        try:
            load()
        except (ImportError, OSError, ValueError) as exc:
            raise _engine_error(exc, name) from exc
    return cfg


def preview_config(hooks: Optional[dict]) -> Optional[dict]:
    """Settings for live preview censoring, or None (never raises; job_config already validated the job)."""
    from .hooks import hook_config
    raw = hook_config(hooks or {}, "nsfw_checker", {})
    if raw is None:
        return None
    try:
        cfg = generation_config(raw)
    except ValueError:
        return None
    active = nudenet_active(cfg, preview=True) or safety_active(cfg, preview=True) or nsfwjs_active(cfg)
    return cfg if active else None


def censor_final(img, cfg: Optional[dict], ctx=None, detector=None):
    """Censor one finished image; returns (image, [(suffix, auxiliary image), ...])."""
    if cfg is None:
        return img, []
    log = (lambda line: ctx.log("info", f"NudeNet: {line}")) if ctx is not None else None
    if ctx is not None:
        def note(line):
            ctx.log("warning", line)
    else:
        def note(line):
            logging.getLogger(__name__).warning("%s", line)
    safety = _safety()
    level = cfg.get("nsfwjs_safety_level", "safe")
    name = "SD safety checker"
    try:
        # index 83 (postprocess_batch upstream): here on the final image, after every refinement
        if safety_active(cfg):
            img, _ = safety.safety_censor(img, log_fn=note)
        name = "NSFWJS filter"
        if nsfwjs_active(cfg):  # index 231: postprocess_image
            img, _ = safety.nsfwjs_censor(img, level, log_fn=note)
        name = "NudeNet NSFW Censor"
        if nudenet_active(cfg):  # index 238: postprocess_image_after_composite
            censored, before, mask = censor_generation(img, cfg, log=log, detector=detector)
        else:
            censored, before, mask = img, None, None
        name = "NSFWJS filter"
        if nsfwjs_active(cfg):  # index 231: on_before_image_saved, for every file written
            censored, _ = safety.nsfwjs_censor(censored, level, log_fn=note)
            before = safety.nsfwjs_censor(before, level, log_fn=note)[0] if before is not None else None
            mask = safety.nsfwjs_censor(mask, level, log_fn=note)[0] if mask is not None else None
    except (ImportError, OSError, ValueError) as exc:
        raise _engine_error(exc, name) from exc
    auxiliaries = [(suffix, image) for suffix, image in zip(AUXILIARY_SUFFIXES, (before, mask)) if image is not None]
    return censored, auxiliaries


def save_auxiliaries(entry: dict, auxiliaries, output_dir: str, index: int) -> list:
    """Engine temp PNGs for the before-censor copy / mask of result image `index`; the app names and saves them
    next to that image (`-before-censor`, `-censor-mask`) with the same infotext."""
    from .images import next_output_path, save_png
    out = []
    for suffix, image in auxiliaries:
        path = save_png(image if image.mode in ("RGB", "RGBA") else image.convert("RGB"),
                        next_output_path(output_dir, f"{entry['seed']}{suffix}"))
        out.append(dict(entry, path=path, width=image.width, height=image.height, output_suffix=suffix,
                        auxiliary_of=index))
    return out


__all__ = ["LABELS", "FRIENDLY", "DEFAULT_SELECTED", "DEFAULT_THRESHOLDS", "MASK_SHAPES", "ENTIRE_IMAGE_ALWAYS",
           "FILTERS", "MODEL_SHA256", "MODEL_SIZE", "GENERATION_DEFAULTS", "generation_config", "scale_factor",
           "default_model_path", "verify_model", "session", "preprocess", "mask_from_outputs", "detect",
           "detect_with", "blur_radii", "variable_blur", "gaussian_blur", "pixelate", "fill_color", "apply_filter",
           "censor_generation", "censor_preview", "censor_preview_b64", "censor_tool", "AUXILIARY_SUFFIXES", "available",
           "job_config", "preview_config", "censor_final", "save_auxiliaries", "TOOL_CHECKERS", "nudenet_active",
           "nsfwjs_active", "safety_active"]
