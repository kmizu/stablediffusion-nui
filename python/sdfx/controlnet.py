"""ControlNet model loading and preprocessors.

Preprocessors: none / invert (PIL), canny (cv2), depth (transformers DPT, needs torch + HF weights),
and controlnet_aux detectors when that package is importable.
"""
from __future__ import annotations

import importlib.util
import logging
import os
from typing import Optional

from .images import Image, ImageOps, require_pil, resize_image, load_rgb

log = logging.getLogger("sdfx.controlnet")

AUX_DETECTORS = {
    # name -> (controlnet_aux class, from_pretrained repo)
    "openpose": ("OpenposeDetector", "lllyasviel/Annotators"),
    "lineart": ("LineartDetector", "lllyasviel/Annotators"),
    "lineart_anime": ("LineartAnimeDetector", "lllyasviel/Annotators"),
    "softedge_hed": ("HEDdetector", "lllyasviel/Annotators"),
    "mlsd": ("MLSDdetector", "lllyasviel/Annotators"),
    "normal_bae": ("NormalBaeDetector", "lllyasviel/Annotators"),
}


def _has(mod: str) -> bool:
    try:
        return importlib.util.find_spec(mod) is not None
    except (ImportError, ValueError):
        return False


def has_controlnet_aux() -> bool:
    return _has("controlnet_aux")


def available_preprocessors() -> list[str]:
    out = ["none", "invert", "inpaint"] + list(PIL_PREPROCESSORS)
    if _has("cv2"):
        out.append("canny")
    if _has("transformers") and _has("torch"):
        out.append("depth")
    if _has("diffusers") and _has("torch"):
        out.extend(["depth_marigold", "normal_marigold"])
    if has_controlnet_aux():
        out.extend(AUX_DETECTORS.keys())
    return out


_aux_cache: dict = {}
_depth_pipe = None


PIL_PREPROCESSORS = ("recolor_luminance", "recolor_intensity", "tile_resample", "tile_colorfix", "blur_gaussian")

CONTROL_MODES = ("Balanced", "My prompt is more important", "ControlNet is more important")
# Source ControlNet ignores the processor resolution for none/invert; canny resizes its input to it.
RESOLUTION_PREPROCESSORS = ("none", "", "invert", "canny")
SOURCE_DEFAULT_RESOLUTION = 512


def pixel_perfect_resolution(width, height, target_w, target_h, resize_mode):
    """Source ControlNet's estimate: fill the target, or fit inside it for Resize and Fill (mode 2)."""
    import numpy as np
    k0, k1 = float(target_h) / float(height), float(target_w) / float(width)
    scale = min(k0, k1) if resize_mode == 2 else max(k0, k1)
    return int(np.round(scale * float(min(height, width))))


def processor_resolution(unit, image, target_w, target_h):
    if unit.pixel_perfect:
        return pixel_perfect_resolution(image.width, image.height, target_w, target_h, unit.resize_mode)
    if 64 <= unit.processor_res <= 2048:
        return unit.processor_res
    return SOURCE_DEFAULT_RESOLUTION


def source_canny(img, resolution, args):
    """Source ControlNet canny: resize the short side to `resolution`, edge-pad to 64, detect, then crop the pad."""
    import cv2  # type: ignore
    import numpy as np

    def threshold(name, default):
        value = args.get(name, default)
        return int(value) if 1 <= value <= 255 else default

    a = np.asarray(img.convert("RGB"))
    height, width = a.shape[:2]
    k = float(resolution) / float(min(height, width))
    th, tw = int(np.round(float(height) * k)), int(np.round(float(width) * k))
    a = cv2.resize(a, (tw, th), interpolation=cv2.INTER_CUBIC if k > 1 else cv2.INTER_AREA)
    pad = lambda x: int(np.ceil(float(x) / 64.0) * 64 - x)
    a = np.pad(a, [[0, pad(th)], [0, pad(tw)], [0, 0]], mode="edge")
    edges = cv2.Canny(a, threshold("low_threshold", 100), threshold("high_threshold", 200))[:th, :tw]
    return Image.fromarray(np.ascontiguousarray(edges)).convert("RGB")


def resize_control_images(images, modes, width, height, compatible=None):
    """Apply each unit's native resize mode in unit order."""
    if len(images) != len(modes) or any(type(mode) is not int or mode not in (0, 1, 2) for mode in modes):
        raise ValueError('invalid ControlNet resize modes')
    compatible = [False] * len(images) if compatible is None else compatible
    if len(compatible) != len(images) or any(type(value) is not bool for value in compatible):
        raise ValueError('invalid ControlNet resize compatibility flags')
    return [source_controlnet_resize(image, mode, width, height) if source else resize_image(mode, image, width, height)
            for image, mode, source in zip(images, modes, compatible)]


def source_controlnet_resize(image, mode, width, height):
    from .controlnet_resize_compat import resize
    return resize(image, mode, width, height)


def load_controlnet_image(path, source_compatible=False):
    if not source_compatible:
        return load_rgb(path)
    import numpy as np
    require_pil()
    with Image.open(path) as opened:
        image = ImageOps.exif_transpose(opened)
        if image.mode == 'RGBA' or (image.mode == 'P' and 'transparency' in image.info):
            rgba = np.asarray(image.convert('RGBA'), dtype=np.uint8)
            alpha = rgba[:, :, 3:4].astype(np.float32) / 255.0
            pixels = rgba[:, :, :3].astype(np.float32) * alpha + 255.0 * (1.0 - alpha)
            return Image.fromarray(pixels.clip(0, 255).astype(np.uint8), 'RGB')
        return image.convert('RGB')


def pil_preprocess(name: str, img, args: dict):
    """forge_preprocessor_recolor / forge_preprocessor_tile equivalents (pure PIL/numpy)."""
    import numpy as np
    from PIL import ImageFilter
    rgb = img.convert("RGB")
    if name in ("recolor_luminance", "recolor_intensity"):
        a = np.asarray(rgb, dtype=np.float32) / 255.0
        if name == "recolor_luminance":
            y = 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]
        else:
            y = a.max(axis=2)
        y = np.clip(y, 0, 1) ** float(args.get("gamma_correction", 1.0))
        return Image.fromarray((y * 255).astype("uint8")).convert("RGB")
    if name == "tile_resample":
        down = float(args.get("down_sampling_rate", 1.0))
        if down <= 1.0:
            return rgb
        small = rgb.resize((max(1, int(rgb.width / down)), max(1, int(rgb.height / down))), Image.BICUBIC)
        return small.resize(rgb.size, Image.BICUBIC)
    if name == "tile_colorfix":
        r = int(args.get("variation", 8))
        return rgb.filter(ImageFilter.GaussianBlur(radius=max(1, r)))
    if name == "blur_gaussian":
        return rgb.filter(ImageFilter.GaussianBlur(radius=float(args.get("sigma", 9.0))))
    raise ValueError(name)


_marigold: dict = {}


def depth_to_image(pred):
    """Marigold affine-invariant depth (N,H,W[,1] or H,W, 0 = near) -> RGB grayscale with near = bright, like the
    ControlNet depth maps the models were trained on."""
    import numpy as np
    a = np.asarray(pred.cpu().numpy() if hasattr(pred, "cpu") else pred, dtype=np.float32)
    a = a.reshape(a.shape[-3], a.shape[-2]) if a.ndim == 4 and a.shape[-1] == 1 else a
    while a.ndim > 2:
        a = a[0]
    lo, hi = float(a.min()), float(a.max())
    a = (a - lo) / (hi - lo) if hi > lo else np.zeros_like(a)
    return Image.fromarray(((1.0 - a) * 255).round().astype("uint8")).convert("RGB")


def marigold(name: str, img, args: dict):
    """forge_preprocessor_marigold: diffusers Marigold depth / normals (LCM variants, few steps)."""
    import torch
    import diffusers  # type: ignore
    if name == "depth_marigold":
        cls, repo = getattr(diffusers, "MarigoldDepthPipeline", None), "prs-eth/marigold-depth-lcm-v1-0"
    else:
        cls, repo = getattr(diffusers, "MarigoldNormalsPipeline", None), "prs-eth/marigold-normals-lcm-v0-1"
    if cls is None:
        raise ValueError(f"{name} needs diffusers >= 0.28")
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    pipe = _marigold.get(name)
    if pipe is None:
        pipe = cls.from_pretrained(repo, torch_dtype=torch.float16 if dev == "cuda" else torch.float32)
        pipe.set_progress_bar_config(disable=True)
        _marigold[name] = pipe
    pipe.to(dev)
    try:
        out = pipe(img.convert("RGB"), num_inference_steps=int(args.get("steps", 4)))
        if name == "depth_marigold":
            res = depth_to_image(out.prediction)
        else:
            res = pipe.image_processor.visualize_normals(out.prediction)[0].convert("RGB")
        return res.resize(img.size, Image.BILINEAR)
    finally:
        pipe.to("cpu")


def run_preprocessor(name: str, img, args: Optional[dict] = None):
    """Apply preprocessor `name` to a PIL RGB image; returns a PIL RGB image of the same size."""
    require_pil()
    args = args or {}
    if name in ("none", "", None):
        return img
    if name == "invert":
        return ImageOps.invert(img.convert("RGB"))
    if name in PIL_PREPROCESSORS:
        return pil_preprocess(name, img, args)
    if name == "inpaint":
        return img.convert("RGB")  # the masked area is set to -1 by the engine when a mask is available
    if name in ("depth_marigold", "normal_marigold"):
        return marigold(name, img, args)
    if name == "canny":
        import cv2  # type: ignore
        import numpy as np
        lo = int(args.get("low_threshold", 100))
        hi = int(args.get("high_threshold", 200))
        edges = cv2.Canny(np.asarray(img.convert("RGB")), lo, hi)
        return Image.fromarray(edges).convert("RGB")
    if name == "depth":
        global _depth_pipe
        from transformers import pipeline  # type: ignore
        if _depth_pipe is None:
            _depth_pipe = pipeline("depth-estimation", model=os.environ.get("SDFX_DEPTH_MODEL", "Intel/dpt-hybrid-midas"))
        d = _depth_pipe(img.convert("RGB"))["depth"]
        return d.convert("RGB").resize(img.size, Image.BILINEAR)
    if name in AUX_DETECTORS:
        import controlnet_aux  # type: ignore
        cls_name, repo = AUX_DETECTORS[name]
        det = _aux_cache.get(name)
        if det is None:
            det = getattr(controlnet_aux, cls_name).from_pretrained(repo)
            _aux_cache[name] = det
        res = int(args.get("resolution", min(img.width, img.height)))
        out = det(img.convert("RGB"), detect_resolution=res, image_resolution=res)
        return out.convert("RGB").resize(img.size, Image.BILINEAR)
    raise ValueError(f"unknown preprocessor '{name}'")


# Preprocessors whose detector takes a resolution (the others ignore it, like the ControlNet unit does).
STANDALONE_RESOLUTION = ("canny",) + tuple(AUX_DETECTORS)


def standalone_preprocess(name: str, img, args: Optional[dict] = None, processor_res: int = -1, pixel_perfect: bool = False):
    """ControlNet preprocessor as an Extras step. Returns (image, info) where info is the ordered dict that is
    written to the output as `ControlNet Preprocessor: {...}` (module, processor_res, threshold_a, threshold_b)
    and `notes` lists values that were ignored.

    Pixel perfect uses the input size as the target, so the resolution is the short side of the image."""
    require_pil()
    args = dict(args or {})
    notes: list[str] = []
    res = -1
    if name in STANDALONE_RESOLUTION:
        if pixel_perfect:
            res = min(img.width, img.height)
        elif 64 <= processor_res <= 2048:
            res = int(processor_res)
        else:
            res = SOURCE_DEFAULT_RESOLUTION
    elif pixel_perfect or processor_res != -1:
        notes.append(f"preprocessor '{name}' has no resolution; processor_res and pixel_perfect are ignored")
    if name == "canny":
        out = source_canny(img, res, args)
    else:
        if name in AUX_DETECTORS:
            args["resolution"] = res
        out = run_preprocessor(name, img, args)
    a_key, b_key = THRESHOLD_KEYS.get(name, (None, None))
    info = {"module": name, "processor_res": res,
            "threshold_a": args.get(a_key, THRESHOLD_DEFAULTS.get(a_key, 0)) if a_key else 0,
            "threshold_b": args.get(b_key, THRESHOLD_DEFAULTS.get(b_key, 0)) if b_key else 0}
    return out, info, notes


# The keys of `args` that the two threshold sliders of the original extension map to.
THRESHOLD_KEYS = {
    "canny": ("low_threshold", "high_threshold"),
    "recolor_luminance": ("gamma_correction", None), "recolor_intensity": ("gamma_correction", None),
    "tile_resample": ("down_sampling_rate", None), "tile_colorfix": ("variation", None),
    "blur_gaussian": ("sigma", None), "depth_marigold": ("steps", None), "normal_marigold": ("steps", None),
}
# What the module uses when an argument is not given (the defaults of `run_preprocessor` / `pil_preprocess`).
THRESHOLD_DEFAULTS = {"low_threshold": 100, "high_threshold": 200, "gamma_correction": 1.0, "down_sampling_rate": 1.0,
                      "variation": 8, "sigma": 9.0, "steps": 4}


def preprocess_infotext(info: dict, size) -> str:
    """`ControlNet Preprocessor: "{...}", Size: WxH` -- the field the original extension adds to the extras infotext."""
    return f'ControlNet Preprocessor: "{info}", Size: {size[0]}x{size[1]}'


def save_preprocessed(img, path: str, info: dict, source_path: str = "") -> str:
    """Write the preprocessor output as PNG with the infotext; the input's own parameters are kept as
    `Source parameters` (never overwritten)."""
    from PIL.PngImagePlugin import PngInfo
    meta = PngInfo()
    meta.add_text("parameters", preprocess_infotext(info, img.size))
    try:
        with Image.open(source_path) as src:
            text = getattr(src, "text", {}) or {}
            if text.get("parameters"):
                meta.add_text("Source parameters", text["parameters"])
    except Exception:
        pass
    try:
        img.save(path, format="PNG", compress_level=4, pnginfo=meta)
    except BaseException:
        try:
            os.remove(path)
        except OSError:
            pass
        raise
    return path


def load_controlnet(path: str, dtype):
    """ControlNetModel from a diffusers directory or a single .safetensors/.pth file."""
    from diffusers import ControlNetModel  # type: ignore
    if os.path.isdir(path):
        return ControlNetModel.from_pretrained(path, torch_dtype=dtype)
    return ControlNetModel.from_single_file(path, torch_dtype=dtype)
