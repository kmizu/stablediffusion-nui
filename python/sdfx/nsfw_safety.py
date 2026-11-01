"""Whole-image NSFW classifiers of the `nsfw_checker` hook, next to NudeNet (nsfw_checker.py):

* SD safety checker (extension index 83, AUTOMATIC1111/stable-diffusion-webui-nsfw-censor @ c21cc99, no licence):
  the CLIP-based `StableDiffusionSafetyChecker` of diffusers with the feature extractor and weights of
  CompVis/stable-diffusion-safety-checker; a flagged image is replaced with a black image of the same size.
* NSFWJS filter (extension index 231, aria1th/sd-webui-nsfw-filter @ ab49c73, no licence): the NSFWJS classifier
  (ONNX conversion deepghs/imgutils-models nsfw/nsfwjs.onnx, MIT); an image whose top class is in the safety level's
  set is blurred with a Gaussian blur of radius 20.

Neither upstream repository has a licence, so nothing was copied: both are re-implemented from their observed
behaviour. Neither model is bundled. Each is downloaded on first use from a pinned Hugging Face revision into
`SDFX_NSFW_CHECKER_CACHE` (default ~/.cache/stablediffusion-nui/nsfw_checker) and every file is size and SHA-256
checked before it is loaded. Both run on the CPU (the safety checker upstream is never moved off the CPU either).

`SDFX_NSFW_CHECKER_FORCE_FLAGGED` (comma list of `safety_checker`, `nsfwjs`) treats every classified image as
flagged after the model has run. It exists for real-model verification without explicit content and can only make
censoring stronger.
"""
from __future__ import annotations

import hashlib
import logging
import os
import tempfile
import threading
from pathlib import Path
from typing import Callable, Optional
from urllib.request import urlopen

import numpy as np

log = logging.getLogger(__name__)

SAFETY_REPO = "CompVis/stable-diffusion-safety-checker"
SAFETY_REVISION = "cb41f3a270d63d454d385fc2e4f571c487c253c5"
SAFETY_FILES = (
    {"filename": "config.json", "bytes": 4549,
     "sha256": "c879a63ee73f528bc5ec148aaea487b2b165f6a785f9cb55b90e590ba7977e0e"},
    {"filename": "preprocessor_config.json", "bytes": 342,
     "sha256": "2a1da83b5e1032aaeef397552ddb408dca0d8cd1dc58f61bf6abf38d6f33a0a2"},
    {"filename": "pytorch_model.bin", "bytes": 1216067303,
     "sha256": "64b8393f1afd5a0c1ed2aa5f341fa7c08286839a48f3743162a76a2835c808bd"},
)

NSFWJS_REPO = "deepghs/imgutils-models"
NSFWJS_REVISION = "35e0ed8f35ce0dbea674568161a48b31ba7d2a99"
NSFWJS_FILE = {"filename": "nsfw/nsfwjs.onnx", "bytes": 10318774,
               "sha256": "510dce55edcd1cf8281cbe906dedb3f4963f8814bc1edfbd42d3bf261148df2d"}
NSFWJS_INPUT = "input_1"
NSFWJS_OUTPUT = "dense_3"
NSFWJS_SIZE = 224
# NSFWJS class order (model output order).
NSFWJS_LABELS = ("drawings", "hentai", "neutral", "porn", "sexy")
# Top classes treated as NSFW per safety level; "explicit" never flags.
SAFETY_LEVELS = {"safe": ("hentai", "porn", "sexy"), "questionable": ("hentai", "porn"), "explicit": ()}
NSFWJS_BLUR_RADIUS = 20

FORCE_ENV = "SDFX_NSFW_CHECKER_FORCE_FLAGGED"
CACHE_ENV = "SDFX_NSFW_CHECKER_CACHE"
OFFLINE_ENV = "SDFX_NSFW_CHECKER_OFFLINE"
CHUNK = 1 << 20


class ModelUnavailable(OSError):
    """A classifier model could not be downloaded, found or verified."""


# ------------------------------------------------------------------------------------------------ downloads
def cache_root() -> Path:
    configured = os.environ.get(CACHE_ENV)
    return Path(configured) if configured else Path.home() / ".cache" / "stablediffusion-nui" / "nsfw_checker"


def model_dir(repo: str, revision: str, root=None) -> Path:
    return Path(root or cache_root()) / repo.replace("/", "__") / revision


def download_url(repo: str, revision: str, filename: str) -> str:
    return f"https://huggingface.co/{repo}/resolve/{revision}/{filename}"


def downloads_allowed() -> bool:
    return os.environ.get(OFFLINE_ENV, "").strip().lower() not in ("1", "true", "yes", "on") and \
        os.environ.get("HF_HUB_OFFLINE", "").strip().lower() not in ("1", "true", "yes", "on")


_verified: dict = {}


def _digest(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(path: Path, asset: dict) -> bool:
    """Size and SHA-256 check, cached per file state (the safety checker weight is 1.2 GB)."""
    stat = path.stat()
    key = (stat.st_size, stat.st_mtime_ns)
    if _verified.get(str(path)) == key:
        return True
    if stat.st_size != asset["bytes"] or _digest(path) != asset["sha256"]:
        return False
    _verified[str(path)] = key
    return True


def materialize(repo: str, revision: str, asset: dict, root=None, allow_download: Optional[bool] = None,
                opener=None, progress: Optional[Callable[[str], None]] = None) -> Path:
    """The verified local copy of one pinned file; downloads it (atomically, verified before publishing) if absent."""
    directory = model_dir(repo, revision, root)
    name = Path(asset["filename"]).name
    path = directory / name
    label = f"{repo}@{revision[:7]}/{asset['filename']}"
    if path.exists():
        if not verify(path, asset):
            raise ModelUnavailable(f"{path} does not match the pinned size/SHA-256 of {label}; refusing to load it "
                                   f"(delete it to download it again)")
        return path
    if not (downloads_allowed() if allow_download is None else allow_download):
        raise ModelUnavailable(f"{label} is not downloaded ({path}); allow downloads or place the pinned file there")
    directory.mkdir(parents=True, exist_ok=True)
    url = download_url(repo, revision, asset["filename"])
    if progress is not None:
        progress(f"downloading {url} ({asset['bytes']:,} bytes)")
    descriptor, temporary = tempfile.mkstemp(prefix=f".{name}-", suffix=".part", dir=directory)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            with (opener or urlopen)(url, timeout=30) as response:
                count = 0
                while chunk := response.read(CHUNK):
                    count += len(chunk)
                    if count > asset["bytes"]:
                        raise ModelUnavailable(f"download of {label} exceeded the pinned size")
                    stream.write(chunk)
            stream.flush()
            os.fsync(stream.fileno())
        if not verify(temporary, asset):
            raise ModelUnavailable(f"download of {label} failed the pinned size/SHA-256 check")
        _verified.pop(str(temporary), None)
        os.replace(temporary, path)
        verify(path, asset)
        return path
    except ModelUnavailable:
        raise
    except Exception as exc:
        raise ModelUnavailable(f"download of {label} failed: {exc}") from exc
    finally:
        temporary.unlink(missing_ok=True)


def forced(name: str) -> bool:
    return name in {part.strip().lower() for part in os.environ.get(FORCE_ENV, "").split(",")}


# ------------------------------------------------------------------------------------------------ SD safety checker
_lock = threading.Lock()
_safety: dict = {}


def safety_model(root=None, allow_download: Optional[bool] = None, progress=None):
    """(feature extractor, StableDiffusionSafetyChecker) on the CPU in float32, cached."""
    paths = [materialize(SAFETY_REPO, SAFETY_REVISION, asset, root, allow_download, progress=progress)
             for asset in SAFETY_FILES]
    folder = paths[0].parent
    with _lock:
        cached = _safety.get(str(folder))
        if cached is not None:
            return cached
        try:
            import torch
            from transformers import CLIPImageProcessor
            from diffusers.pipelines.stable_diffusion.safety_checker import StableDiffusionSafetyChecker
        except ImportError as exc:
            raise ImportError(f"SD safety checker needs torch, transformers and diffusers ({exc})") from exc
        processor = CLIPImageProcessor.from_pretrained(str(folder), local_files_only=True)
        model = StableDiffusionSafetyChecker.from_pretrained(str(folder), local_files_only=True, use_safetensors=False,
                                                             torch_dtype=torch.float32)
        value = (processor, model.to("cpu").eval())
        _safety.clear()
        _safety[str(folder)] = value
        return value


def safety_flags(images, model=None) -> list:
    """Per image True when the safety checker flags it (diffusers' concept / special-care thresholds)."""
    processor, checker = model or safety_model()
    rgb = [img.convert("RGB") for img in images]
    clip_input = processor(images=rgb, return_tensors="pt").pixel_values.to(dtype=next(checker.parameters()).dtype)
    batch = [np.asarray(img, dtype=np.float32) / 255.0 for img in rgb]  # upstream: float HWC in 0..1
    _, flags = checker(images=batch, clip_input=clip_input)
    flags = [bool(flag) for flag in flags]
    if forced("safety_checker"):
        flags = [True] * len(flags)
    return flags


def black(img):
    """Black image of the same size and mode (opaque when the mode has alpha)."""
    from PIL import Image
    return Image.new("RGB", img.size, (0, 0, 0)).convert(img.mode)


def safety_censor(img, model=None, log_fn=None):
    """(image or its black replacement, flagged)."""
    flagged = safety_flags([img], model)[0]
    if flagged and log_fn is not None:
        log_fn("SD safety checker: potential NSFW content detected; the image was replaced with a black image")
    return (black(img) if flagged else img), flagged


# ------------------------------------------------------------------------------------------------ NSFWJS
_nsfwjs: dict = {}


def nsfwjs_session(root=None, allow_download: Optional[bool] = None, progress=None):
    path = materialize(NSFWJS_REPO, NSFWJS_REVISION, NSFWJS_FILE, root, allow_download, progress=progress)
    with _lock:
        cached = _nsfwjs.get(str(path))
        if cached is not None:
            return cached
        try:
            import onnxruntime
        except ImportError as exc:
            raise ImportError("NSFWJS filter needs onnxruntime (pip install onnxruntime==1.23.2)") from exc
        options = onnxruntime.SessionOptions()
        options.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_ENABLE_ALL
        value = onnxruntime.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
        _nsfwjs[str(path)] = value
        return value


def nsfwjs_input(img) -> np.ndarray:
    """RGB, nearest-neighbour resize to 224x224 (aspect ratio not kept), NHWC float32 in 0..1."""
    from PIL import Image
    resized = img.convert("RGB").resize((NSFWJS_SIZE, NSFWJS_SIZE), Image.NEAREST)
    return (np.asarray(resized) / 255.0)[None, ...].astype(np.float32)


def nsfwjs_scores(img, session=None) -> np.ndarray:
    s = session or nsfwjs_session()
    scores, = s.run([NSFWJS_OUTPUT], {NSFWJS_INPUT: nsfwjs_input(img)})
    return np.asarray(scores, dtype=np.float32).reshape(-1)[:len(NSFWJS_LABELS)]


def check_level(level) -> str:
    text = str(level if level is not None else "safe").strip().lower()
    if text not in SAFETY_LEVELS:
        raise ValueError(f"NSFWJS safety level must be one of {', '.join(SAFETY_LEVELS)} (got {level!r})")
    return text


def nsfwjs_classify(img, level: str, session=None):
    """(flagged, top label, scores). "explicit" never flags and is not classified."""
    if not SAFETY_LEVELS[check_level(level)]:
        return False, None, None
    scores = nsfwjs_scores(img, session)
    label = NSFWJS_LABELS[int(np.argmax(scores))]
    flagged = label in SAFETY_LEVELS[level] or forced("nsfwjs")
    return flagged, label, scores


def nsfwjs_censor(img, level: str, session=None, passes: int = 1, log_fn=None):
    """Classify and blur (radius 20) when flagged, `passes` times; returns (image, [top label per pass])."""
    from PIL import ImageFilter
    labels = []
    for _ in range(passes):
        flagged, label, _ = nsfwjs_classify(img, level, session)
        labels.append(label)
        if not flagged:
            break
        img = img.filter(ImageFilter.GaussianBlur(NSFWJS_BLUR_RADIUS))
        if log_fn is not None:
            log_fn(f"NSFWJS filter: top class '{label}' is NSFW at safety level '{level}'; the image was blurred")
    return img, labels


__all__ = ["SAFETY_REPO", "SAFETY_REVISION", "SAFETY_FILES", "NSFWJS_REPO", "NSFWJS_REVISION", "NSFWJS_FILE",
           "NSFWJS_LABELS", "SAFETY_LEVELS", "NSFWJS_BLUR_RADIUS", "FORCE_ENV", "CACHE_ENV", "OFFLINE_ENV",
           "ModelUnavailable", "cache_root", "model_dir", "download_url", "downloads_allowed", "verify", "materialize",
           "forced", "safety_model", "safety_flags", "black", "safety_censor", "nsfwjs_session", "nsfwjs_input",
           "nsfwjs_scores", "check_level", "nsfwjs_classify", "nsfwjs_censor"]
