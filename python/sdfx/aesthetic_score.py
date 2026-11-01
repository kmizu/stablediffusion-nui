"""Aesthetic scoring of images: the `aesthetic_score` target of the extension index, three extensions in one module.

* index 95, tsngo/stable-diffusion-webui-aesthetic-image-scorer (pinned 1c3ab7a, MIT): the improved aesthetic
  predictor (christophschuhmann, Apache-2.0, weight `sac+logos+ava1-l14-linearMSE.pth`) on the L2-normalised CLIP
  ViT-L/14 image embedding. The score is rounded to one decimal and written as `aesthetic_score` (generation
  parameters text and PNG info chunk); optional `sampler`, `cfg_scale`, `sd_model_hash`, `seed` and `hash` fields.
  Windows property tags and categories (pywin32) and EXIF are not provided.
* index 96, vladmandic/sd-extension-aesthetic-scorer (no licence file; implemented from its README only): the
  "simulacra" linear predictors of crowsonkb (MIT, `sac_public_2022_06_29_vit_{l_14,b_16}_linear.pth`) on CLIP ViT-L/14
  or ViT-B/16, scores 0..10, written as the PNG info chunk `score`.
* index 97, p1atdev/stable-diffusion-webui-cafe-aesthetic (AGPL-3.0, nothing copied): the three cafeai classifiers
  (BEiT, AGPL-3.0): `cafe_aesthetic` (aesthetic / not_aesthetic), `cafe_style` (anime, real_life, 3d, manga_like,
  other) and `cafe_waifu` (waifu / not_waifu). Probabilities are softmax over the model's logits.

One implementation serves the generation hook `aesthetic_score` (every final image, after censoring and any
upscale, just before it is saved) and the `image_tool` request (tool `aesthetic_score`, used by the Extras plugin and
therefore also by its batch processing).

Nothing is bundled: every model file is downloaded on first use from a pinned revision into `SDFX_AESTHETIC_CACHE`
(default ~/.cache/stablediffusion-nui/aesthetic_score) and checked against a pinned size and SHA-256 before it is loaded
(torch.load uses weights_only). CLIP and the classifiers stay in RAM and visit the GPU only while an image is scored.
"""
from __future__ import annotations

import hashlib
import json
import logging
import math
import os
import threading
from pathlib import Path
from typing import Callable, Optional

from . import nsfw_safety

log = logging.getLogger(__name__)

CACHE_ENV = "SDFX_AESTHETIC_CACHE"

CLIP_L14 = ("openai/clip-vit-large-patch14", "32bd64288804d66eefd0ccbe215aa642df71cc41", (
    {"filename": "config.json", "bytes": 4519,
     "sha256": "8a09b467700c58138c29d53c605b34ebc69beaadd13274a8a2af8ad2c2f4032a"},
    {"filename": "model.safetensors", "bytes": 1710540580,
     "sha256": "a2bf730a0c7debf160f7a6b50b3aaf3703e7e88ac73de7a314903141db026dcb"},
))
CLIP_B16 = ("openai/clip-vit-base-patch16", "57c216476eefef5ab752ec549e440a49ae4ae5f3", (
    {"filename": "config.json", "bytes": 4104,
     "sha256": "eaf1c9089a8553c913d27ea66407f8bfc2be9989c80c9f331ddb3d63d4c5e8ad"},
    {"filename": "pytorch_model.bin", "bytes": 598641023,
     "sha256": "ec89c7b09c749a60aae3c9cd910516f24b58214a7df060b48962d14c469cfbf0"},
))
CLIPS = {"l14": CLIP_L14, "b16": CLIP_B16}

IMPROVED_REPO = "christophschuhmann/improved-aesthetic-predictor"
IMPROVED_REVISION = "6934dd81792f086e613a121dbce43082cb8be85e"
IMPROVED_FILE = {"filename": "sac+logos+ava1-l14-linearMSE.pth", "bytes": 3714759,
                 "sha256": "21dd590f3ccdc646f0d53120778b296013b096a035a2718c9cb0d511bff0f1e0"}
SIMULACRA_REPO = "crowsonkb/simulacra-aesthetic-models"
SIMULACRA_REVISION = "701f7678b305acf8a0634350365a86beb95dd87d"
SIMULACRA_FILES = {
    "l14": {"filename": "models/sac_public_2022_06_29_vit_l_14_linear.pth", "bytes": 4135,
            "sha256": "f77d1d0f4bb04b0ff8e60c5747fd0bfff0cc4a85263a6106627522c06eae85c6"},
    "b16": {"filename": "models/sac_public_2022_06_29_vit_b_16_linear.pth", "bytes": 3111,
            "sha256": "cdc27b2196565bcb69f2692731084b0189612b98aae74adec033782469ab583c"},
}
CAFE = {
    "cafe_aesthetic": ("cafeai/cafe_aesthetic", "48a343764f786abc1ca8aaddfcd60a688a70da9b", (
        {"filename": "config.json", "bytes": 34764,
         "sha256": "58e7aec1b10c986767211be935de2c2b7e25d24c59cc70d77408acbb19c7ebb2"},
        {"filename": "preprocessor_config.json", "bytes": 302,
         "sha256": "597f235506333055f8b757bc37ec251ce0084ffbe16deec00253f0c8385bbc7f"},
        {"filename": "pytorch_model.bin", "bytes": 375948473,
         "sha256": "c6ec3b57bd37804535ad18b11abb50d951b465bab837fbab172f8277ace2bc8e"})),
    "cafe_style": ("cafeai/cafe_style", "de57fdb6bda44ac3d9c02641b6d8c3e36901138e", (
        {"filename": "config.json", "bytes": 34812,
         "sha256": "b79417d1f1a9ab38f4d28ea7c0834e27f61d19c3fdcbaa7b3eb6da84bb58d09e"},
        {"filename": "preprocessor_config.json", "bytes": 302,
         "sha256": "597f235506333055f8b757bc37ec251ce0084ffbe16deec00253f0c8385bbc7f"},
        {"filename": "pytorch_model.bin", "bytes": 375957689,
         "sha256": "c54847c4cc7b892017219bf5ba06a6214ad38a8b75a60733e37033c94a922a14"})),
    "cafe_waifu": ("cafeai/cafe_waifu", "c817cefe0f47d060e29e959a3915722735170f6e", (
        {"filename": "config.json", "bytes": 34756,
         "sha256": "606053ab029e7762b19dd84639ceb475220f3cc332fc95e5aa177055e91cb8b9"},
        {"filename": "preprocessor_config.json", "bytes": 302,
         "sha256": "597f235506333055f8b757bc37ec251ce0084ffbe16deec00253f0c8385bbc7f"},
        {"filename": "pytorch_model.bin", "bytes": 375948473,
         "sha256": "bd6411a1246239829e16bf9ef60fc12abf2baea36a4acd5b854259bdf453529d"})),
}
CAFE_LABELS = {
    "cafe_aesthetic": ("not_aesthetic", "aesthetic"),
    "cafe_style": ("anime", "real_life", "3d", "manga_like", "other"),
    "cafe_waifu": ("not_waifu", "waifu"),
}

CLIP_MEAN = (0.48145466, 0.4578275, 0.40821073)
CLIP_STD = (0.26862954, 0.26130258, 0.27577711)
CLIP_SIZE = 224

SIMULACRA_CHOICES = {"off": None, "vit-l-14": "l14", "vit-b-16": "b16"}
GENERATION_FIELDS = ("sampler", "cfg_scale", "sd_model_hash", "seed")

DEFAULTS = {
    "improved": True,              # tsngo: CLIP ViT-L/14 + improved aesthetic predictor -> `aesthetic_score`
    "simulacra": "off",            # vladmandic: off | vit-l-14 | vit-b-16 -> PNG chunk `score`
    "cafe_aesthetic": False, "cafe_style": False, "cafe_waifu": False,
    "png_chunks": True,            # also write the scores as separate PNG info chunks
    "generation_fields": False,    # also sampler, cfg_scale, sd_model_hash, seed (tsngo's other choices)
    "hash": False,                 # tsngo's experimental md5 of the generation parameters
    "force_cpu": False,
}


def available() -> bool:
    """True when the packages the scorers need are installed (the models themselves download on first use)."""
    from importlib.util import find_spec
    return all(find_spec(name) is not None for name in ("torch", "transformers", "PIL", "numpy"))


class ModelUnavailable(nsfw_safety.ModelUnavailable):
    """A scoring model could not be downloaded, found or verified."""


# ------------------------------------------------------------------------------------------------ configuration
def normalize(raw: Optional[dict]) -> dict:
    """Validated settings (ValueError on anything the app would not honour)."""
    cfg = dict(DEFAULTS)
    from .hooks import coerce_bool
    for key, value in (raw or {}).items():
        if key == "enabled":
            continue
        if key not in DEFAULTS:
            raise ValueError(f"unknown aesthetic score setting '{key}'")
        if key == "simulacra":
            choice = str(value).strip().lower() if value is not None else "off"
            if choice in ("", "none", "false"):
                choice = "off"
            if choice not in SIMULACRA_CHOICES:
                raise ValueError(f"simulacra must be one of {', '.join(SIMULACRA_CHOICES)} (got {value!r})")
            cfg[key] = choice
        else:
            cfg[key] = coerce_bool(value, DEFAULTS[key])
    if not scorers(cfg):
        raise ValueError("Aesthetic score: enable at least one scorer")
    return cfg


def scorers(cfg: dict) -> list:
    """Active scorer names in output order."""
    out = []
    if cfg["improved"]:
        out.append("improved")
    if SIMULACRA_CHOICES[cfg["simulacra"]]:
        out.append("simulacra_" + SIMULACRA_CHOICES[cfg["simulacra"]])
    out += [name for name in CAFE if cfg[name]]
    return out


# ------------------------------------------------------------------------------------------------ models
def cache_root() -> Path:
    configured = os.environ.get(CACHE_ENV)
    return Path(configured) if configured else Path.home() / ".cache" / "stablediffusion-nui" / "aesthetic_score"


def _fetch(repo: str, revision: str, asset: dict, progress=None, github: bool = False) -> Path:
    url = None
    if github:
        from urllib.parse import quote
        url = f"https://raw.githubusercontent.com/{repo}/{revision}/{quote(asset['filename'])}"
    try:
        return nsfw_safety.materialize(repo, revision, asset, cache_root(), progress=progress, url=url)
    except nsfw_safety.ModelUnavailable as exc:
        raise ModelUnavailable(str(exc)) from exc


def _fetch_folder(spec, progress=None) -> Path:
    repo, revision, assets = spec
    paths = [_fetch(repo, revision, asset, progress) for asset in assets]
    return paths[0].parent


_lock = threading.Lock()
_models: dict = {}


class _Holder:
    """A model that lives on the CPU and visits `device` only inside `run`."""

    def __init__(self, module, label: str):
        self.module, self.label = module, label

    def run(self, fn, device: str):
        import torch
        self.module.to(device)
        try:
            with torch.inference_mode():
                return fn(self.module)
        finally:
            if device != "cpu":
                self.module.to("cpu")
                torch.cuda.empty_cache()


def _device(cfg: dict) -> str:
    if cfg.get("force_cpu"):
        return "cpu"
    try:
        import torch
        return "cuda" if torch.cuda.is_available() else "cpu"
    except ImportError:
        return "cpu"


def _clip(which: str, progress=None) -> _Holder:
    with _lock:
        key = ("clip", which, str(cache_root()))
        if key in _models:
            return _models[key]
    spec = CLIPS[which]
    folder = _fetch_folder(spec, progress)
    try:
        import torch
        from transformers import CLIPVisionModelWithProjection
    except ImportError as exc:
        raise ImportError(f"aesthetic scoring needs torch and transformers ({exc})") from exc
    model = CLIPVisionModelWithProjection.from_pretrained(
        str(folder), local_files_only=True, torch_dtype=torch.float32, use_safetensors=spec[2][1]["filename"].endswith(".safetensors"))
    holder = _Holder(model.eval(), spec[0])
    with _lock:
        _models[key] = holder
    return holder


def _linear_heads(progress=None) -> dict:
    """The MLP of the improved predictor and the two simulacra linear heads (tiny, kept on the CPU)."""
    with _lock:
        key = ("heads", str(cache_root()))
        if key in _models:
            return _models[key]
    import torch
    from torch import nn
    improved_path = _fetch(IMPROVED_REPO, IMPROVED_REVISION, IMPROVED_FILE, progress, github=True)
    mlp = nn.Sequential(nn.Linear(768, 1024), nn.Dropout(0.2), nn.Linear(1024, 128), nn.Dropout(0.2),
                        nn.Linear(128, 64), nn.Dropout(0.1), nn.Linear(64, 16), nn.Linear(16, 1))
    mlp.load_state_dict({k[len("layers."):]: v for k, v in torch.load(improved_path, map_location="cpu", weights_only=True).items()})
    heads = {"improved": mlp.eval()}
    for which, asset in SIMULACRA_FILES.items():
        path = _fetch(SIMULACRA_REPO, SIMULACRA_REVISION, asset, progress, github=True)
        state = torch.load(path, map_location="cpu", weights_only=True)
        linear = nn.Linear(state["linear.weight"].shape[1], 1)
        linear.load_state_dict({"weight": state["linear.weight"], "bias": state["linear.bias"]})
        heads["simulacra_" + which] = linear.eval()
    with _lock:
        _models[key] = heads
    return heads


def _cafe(name: str, progress=None):
    with _lock:
        key = ("cafe", name, str(cache_root()))
        if key in _models:
            return _models[key]
    folder = _fetch_folder(CAFE[name], progress)
    try:
        from transformers import AutoImageProcessor, AutoModelForImageClassification
    except ImportError as exc:
        raise ImportError(f"aesthetic scoring needs torch and transformers ({exc})") from exc
    processor = AutoImageProcessor.from_pretrained(str(folder), local_files_only=True)
    model = AutoModelForImageClassification.from_pretrained(str(folder), local_files_only=True, use_safetensors=False)
    labels = tuple(model.config.id2label[i] for i in range(len(model.config.id2label)))
    if labels != CAFE_LABELS[name]:
        raise ModelUnavailable(f"{name}: unexpected class labels {labels}")
    value = (processor, _Holder(model.eval(), CAFE[name][0]))
    with _lock:
        _models[key] = value
    return value


def clear_cache() -> None:
    with _lock:
        _models.clear()


def preload(cfg: dict, progress=None) -> None:
    """Downloads, verifies and loads every model the settings need, so a job fails before sampling, not after."""
    names = scorers(cfg)
    if any(n == "improved" or n.startswith("simulacra") for n in names):
        _linear_heads(progress)
    for which in sorted({"l14" if n == "improved" else n[len("simulacra_"):] for n in names
                         if n == "improved" or n.startswith("simulacra")}):
        _clip(which, progress)
    for name in names:
        if name in CAFE:
            _cafe(name, progress)


# ------------------------------------------------------------------------------------------------ scoring
def _preprocess(image, resample):
    """OpenAI CLIP preprocessing: shortest side to 224 (torchvision's int() rounding), centre crop, normalise."""
    import numpy as np
    from PIL import Image
    rgb = _rgb(image)
    w, h = rgb.size
    if w <= h:
        size = (CLIP_SIZE, max(CLIP_SIZE, int(CLIP_SIZE * h / w)))
    else:
        size = (max(CLIP_SIZE, int(CLIP_SIZE * w / h)), CLIP_SIZE)
    if (w, h) != size:
        rgb = rgb.resize(size, resample)
    left, top = int(round((size[0] - CLIP_SIZE) / 2.0)), int(round((size[1] - CLIP_SIZE) / 2.0))
    rgb = rgb.crop((left, top, left + CLIP_SIZE, top + CLIP_SIZE))
    arr = (np.asarray(rgb, dtype=np.float32) / 255.0 - np.array(CLIP_MEAN, dtype=np.float32)) / np.array(CLIP_STD, dtype=np.float32)
    return arr.transpose(2, 0, 1).copy()


def _rgb(image):
    from PIL import Image
    if image.mode in ("RGBA", "LA") or (image.mode == "P" and "transparency" in image.info):
        rgba = image.convert("RGBA")
        base = Image.new("RGB", rgba.size, (255, 255, 255))
        base.paste(rgba, mask=rgba.getchannel("A"))
        return base
    return image.convert("RGB")


def clip_embedding(image, which: str, device: str, resample=None, progress=None):
    """Raw CLIP image embedding (projection output) as a float32 CPU tensor of shape (1, dim)."""
    import torch
    from PIL import Image
    pixels = torch.from_numpy(_preprocess(image, resample if resample is not None else Image.BICUBIC)).unsqueeze(0)
    holder = _clip(which, progress)
    return holder.run(lambda module: module(pixel_values=pixels.to(device)).image_embeds.float().cpu(), device)


def score_clip(image, cfg: dict, wanted: list, progress=None) -> dict:
    """{scorer: float} for the CLIP based scorers in `wanted`."""
    import torch
    from PIL import Image
    from torch.nn import functional as F
    device = _device(cfg)
    heads = _linear_heads(progress)
    out = {}
    if "improved" in wanted:
        emb = F.normalize(clip_embedding(image, "l14", device, Image.BICUBIC, progress), dim=-1)
        with torch.inference_mode():
            out["improved"] = round(float(heads["improved"](emb).item()), 1)
    for which in ("l14", "b16"):
        name = "simulacra_" + which
        if name in wanted:
            emb = clip_embedding(image, which, device, Image.LANCZOS, progress)
            with torch.inference_mode():
                scaled = F.normalize(emb, dim=-1) * math.sqrt(emb.shape[-1])
                out[name] = round(float(heads[name](scaled).item()), 2)
    return out


def score_cafe(image, name: str, cfg: dict, progress=None) -> dict:
    """{label: probability} of one cafeai classifier (softmax over its logits), in the model's class order."""
    import torch
    processor, holder = _cafe(name, progress)
    inputs = processor(images=_rgb(image), return_tensors="pt")
    device = _device(cfg)
    logits = holder.run(lambda module: module(**{k: v.to(device) for k, v in inputs.items()}).logits.float().cpu(), device)
    probs = torch.softmax(logits[0], dim=-1).tolist()
    return {label: round(p, 4) for label, p in zip(CAFE_LABELS[name], probs)}


def score_image(image, cfg: dict, progress=None) -> dict:
    """All active scorers on one image: {scorer: float} for CLIP scorers, {scorer: {label: p}} for cafe."""
    names = scorers(cfg)
    out = score_clip(image, cfg, [n for n in names if n not in CAFE], progress) if any(n not in CAFE for n in names) else {}
    for name in names:
        if name in CAFE:
            out[name] = score_cafe(image, name, cfg, progress)
    return {name: out[name] for name in names}


# ------------------------------------------------------------------------------------------------ outputs
def top_label(distribution: dict) -> tuple:
    label = max(distribution, key=distribution.get)
    return label, distribution[label]


def fmt(value: float) -> str:
    return f"{float(value):g}"


def infotext_fields(scores: dict) -> list:
    """[(key, value)] for the generation parameters text."""
    fields = []
    for name, value in scores.items():
        if name == "improved":
            fields.append(("aesthetic_score", f"{value:.1f}"))
        elif name.startswith("simulacra_"):
            fields.append((f"Aesthetic Scorer {'ViT-L/14' if name.endswith('l14') else 'ViT-B/16'}", f"{value:.2f}"))
        else:
            label, p = top_label(value)
            fields.append((name.replace("_", " ").capitalize(), f"{label} {p:.4f}"))
    return fields


def png_chunks(scores: dict) -> list:
    """[(chunk, text)]: `aesthetic_score` (tsngo) and `score` (vladmandic), plus a JSON chunk per cafe classifier."""
    chunks = []
    for name, value in scores.items():
        if name == "improved":
            chunks.append(("aesthetic_score", f"{value:.1f}"))
        elif name.startswith("simulacra_"):
            chunks.append(("score", f"{value:.2f}"))
        else:
            chunks.append((name, json.dumps(value, separators=(",", ":"))))
    return chunks


def params_hash(gp, seed: int, model_hash: str) -> str:
    """tsngo's `hash`: md5 of the JSON of steps, sampler, cfg_scale, seed, size, model hash and prompts. Upstream uses
    the A1111 sampler name and Python's default json.dumps separators; the values here are this app's."""
    data = {"steps": gp.steps, "sampler": gp.sampler_name, "cfg_scale": gp.cfg_scale, "seed": seed, "width": gp.width,
            "height": gp.height, "model": model_hash, "prompt": gp.prompt, "negative_prompt": gp.negative_prompt}
    return hashlib.md5(json.dumps(data).encode("utf-8")).hexdigest()


# ------------------------------------------------------------------------------------------------ generation hook
def job_config(hooks: Optional[dict], ctx=None) -> Optional[dict]:
    """Validated settings of the `aesthetic_score` hook or None. Loads every model up front."""
    from .hooks import hook_config
    from .protocol import INVALID_PARAMS, UNSUPPORTED, EngineError
    raw = hook_config(hooks or {}, "aesthetic_score", {})
    if raw is None:
        return None
    try:
        cfg = normalize(raw)
    except ValueError as exc:
        raise EngineError(INVALID_PARAMS, str(exc)) from exc
    try:
        preload(cfg, (lambda line: ctx.log("info", f"Aesthetic score: {line}")) if ctx is not None else None)
    except (ImportError, OSError, ValueError) as exc:
        raise EngineError(UNSUPPORTED, f"Aesthetic score: {exc}") from exc
    return cfg


def score_final(image, cfg: dict, ctx, gp, seed: int, model_hash: str) -> dict:
    """Scores one final image. Returns the fields the host writes: infotext_fields, png_chunks and the raw scores."""
    try:
        scores = score_image(image, cfg)
    except (ImportError, OSError, ValueError, RuntimeError) as exc:
        from .protocol import UNSUPPORTED, EngineError
        raise EngineError(UNSUPPORTED, f"Aesthetic score: {exc}") from exc
    fields = infotext_fields(scores)
    chunks = png_chunks(scores) if cfg["png_chunks"] else []
    extra = []
    if cfg["generation_fields"]:
        extra = [("sampler", gp.sampler_name), ("cfg_scale", fmt(float(gp.cfg_scale))), ("sd_model_hash", model_hash),
                 ("seed", str(int(seed)))]
        if cfg["png_chunks"]:
            chunks += extra
    if cfg["hash"]:
        digest = params_hash(gp, seed, model_hash)
        fields.append(("hash", digest))
        if cfg["png_chunks"]:
            chunks.append(("hash", digest))
    if ctx is not None:
        ctx.log("info", "Aesthetic score: " + "; ".join(f"{k}={v}" for k, v in fields))
    return {"infotext_fields": [list(f) for f in fields], "png_chunks": [list(c) for c in chunks], "scores": scores}


# ------------------------------------------------------------------------------------------------ image_tool
def tool(img, p: dict, log: Optional[Callable[[str], None]] = None):
    """`image_tool` entry: scores the image and returns it unchanged with the scores as info (strings)."""
    try:
        cfg = normalize({k: p[k] for k in DEFAULTS if k in p})
    except ValueError as exc:
        raise ValueError(str(exc)) from exc
    try:
        scores = score_image(img, cfg, progress=log)
    except nsfw_safety.ModelUnavailable as exc:
        raise ImportError(str(exc)) from exc
    info = {}
    for key, value in infotext_fields(scores):
        info[key] = value
    if log is not None:
        log("Aesthetic score: " + "; ".join(f"{k}={v}" for k, v in info.items()))
    return img, info
