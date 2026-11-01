"""Final-image post-processing of the Loopback Scaler script (Elldreth/loopback_scaler, MIT).

Re-implemented from the documented behaviour at revision 184a53e6d22579c578c6ee1bf55d821479213c49: after the last
loop of every batch the image goes through PIL's ImageEnhance Sharpness, Brightness, Color and Contrast (factor 1 is
no change), then the ImageFilter DETAIL, SMOOTH and BLUR filters repeated 0..3 times ("None", "Low", "Medium",
"High") and finally CONTOUR when it is ticked. The loop schedule itself lives in the Scala script plugin.
"""
from __future__ import annotations

import math

LEVELS = {"None": 0, "Low": 1, "Medium": 2, "High": 3}
FACTOR_MIN, FACTOR_MAX = 0.1, 2.0
FACTORS = ("sharpness", "brightness", "color", "contrast")
FILTERS = ("detail", "smooth", "blur")


def _factor(name: str, value) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be a number")
    v = float(value)
    if not math.isfinite(v) or v < FACTOR_MIN or v > FACTOR_MAX:
        raise ValueError(f"{name} must be in {FACTOR_MIN}..{FACTOR_MAX} (got {value})")
    return v


def _level(name: str, value) -> int:
    if not isinstance(value, str) or value not in LEVELS:
        raise ValueError(f"{name} must be one of {', '.join(LEVELS)} (got {value!r})")
    return LEVELS[value]


def finish(img, sharpness=1.0, brightness=1.0, color=1.0, contrast=1.0, detail="None", smooth="None", blur="None",
           contour=False):
    """Returns (image, {}) with the upstream enhancement order applied to an RGB copy of `img`."""
    from PIL import ImageEnhance, ImageFilter

    factors = {name: _factor(name, value) for name, value in
               zip(FACTORS, (sharpness, brightness, color, contrast))}
    repeats = {name: _level(name, value) for name, value in zip(FILTERS, (detail, smooth, blur))}
    if not isinstance(contour, bool):
        raise ValueError("contour must be a boolean")
    out = img.convert("RGB") if img.mode != "RGB" else img.copy()
    out = ImageEnhance.Sharpness(out).enhance(factors["sharpness"])
    out = ImageEnhance.Brightness(out).enhance(factors["brightness"])
    out = ImageEnhance.Color(out).enhance(factors["color"])
    out = ImageEnhance.Contrast(out).enhance(factors["contrast"])
    for name, kernel in (("detail", ImageFilter.DETAIL), ("smooth", ImageFilter.SMOOTH), ("blur", ImageFilter.BLUR)):
        for _ in range(repeats[name]):
            out = out.filter(kernel)
    if contour:
        out = out.filter(ImageFilter.CONTOUR)
    return out, {}


def run(img, params: dict):
    """`image_tool` entry: only the documented keys are read; absent keys use the upstream defaults."""
    keys = FACTORS + FILTERS + ("contour",)
    return finish(img, **{k: params[k] for k in keys if k in params})


__all__ = ["LEVELS", "FACTORS", "FILTERS", "finish", "run"]
