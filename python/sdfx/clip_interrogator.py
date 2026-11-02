"""CLIP Interrogator: image -> prompt by ranking text labels against a CLIP image embedding.

Re-implemented from pharmapsychotic/clip-interrogator (MIT, bc07ce6) and clip-interrogator-ext (MIT, 0f1a459):
caption (BLIP / BLIP-2 / GIT) plus the artist / flavor / medium / movement / trending / negative label lists
ranked by cosine similarity, in the best / fast / classic / negative / caption modes, the Analyze table and the
three batch output formats.  CLIP runs through transformers' CLIPModel (the same weights open_clip ships)
instead of open_clip.  The label lists in data/clip_interrogator are the upstream files, unchanged.

The algorithm (`Interrogator`) only needs the small `ClipBackend` / `Captioner` interface, so the tests drive it with
deterministic fakes and the engine drives it with `HfClip` / `HfCaptioner`.
"""
from __future__ import annotations

import csv
import gc
import hashlib
import os
import threading
from pathlib import Path
from typing import Callable, Optional

from .protocol import EngineError, INVALID_PARAMS, UNSUPPORTED

DATA_DIR = Path(__file__).resolve().parent / "data" / "clip_interrogator"
DEFAULT_CLIP = "ViT-L-14/openai"
CONTEXT_LENGTH = 77

CAPTION_MODELS = {
    "blip-base": "Salesforce/blip-image-captioning-base",
    "blip-large": "Salesforce/blip-image-captioning-large",
    "blip2-2.7b": "Salesforce/blip2-opt-2.7b",
    "blip2-flan-t5-xl": "Salesforce/blip2-flan-t5-xl",
    "git-large-coco": "microsoft/git-large-coco",
}

# open_clip "<architecture>/<pretrained tag>" -> the transformers conversion of the same weights.
CLIP_MODELS = {
    "ViT-L-14/openai": "openai/clip-vit-large-patch14",
    "ViT-L-14-336/openai": "openai/clip-vit-large-patch14-336",
    "ViT-B-16/openai": "openai/clip-vit-base-patch16",
    "ViT-B-32/openai": "openai/clip-vit-base-patch32",
    "ViT-H-14/laion2b_s32b_b79k": "laion/CLIP-ViT-H-14-laion2B-s32B-b79K",
    "ViT-g-14/laion2b_s12b_b42k": "laion/CLIP-ViT-g-14-laion2B-s12B-b42K",
    "ViT-bigG-14/laion2b_s39b_b160k": "laion/CLIP-ViT-bigG-14-laion2B-39B-b160k",
    "ViT-L-14/laion2b_s32b_b82k": "laion/CLIP-ViT-L-14-laion2B-s32B-b82K",
    "ViT-B-16/laion2b_s34b_b88k": "laion/CLIP-ViT-B-16-laion2B-s34B-b88K",
    "ViT-B-32/laion2b_s34b_b79k": "laion/CLIP-ViT-B-32-laion2B-s34B-b79K",
}

MODES = ("best", "fast", "classic", "negative", "caption")
ANALYZE_KEYS = ("medium", "artist", "movement", "trending", "flavor")
BATCH_OUTPUT_MODES = ("Text file for each image", "Single text file with all prompts",
                      "csv file with columns for filenames and prompts")
BATCH_EXTENSIONS = (".png", ".jpg", ".jpeg")

SITES = ["Artstation", "behance", "cg society", "cgsociety", "deviantart", "dribbble", "flickr", "instagram",
         "pexels", "pinterest", "pixabay", "pixiv", "polycount", "reddit", "shutterstock", "tumblr", "unsplash",
         "zbrush central"]


def clip_repo(name: str) -> str:
    """Resolve an open_clip style name (or a local transformers folder) to a transformers source."""
    if name in CLIP_MODELS:
        return CLIP_MODELS[name]
    if name in CLIP_MODELS.values() or (name and os.path.isdir(name)):
        return name
    raise EngineError(INVALID_PARAMS, f"unsupported CLIP model '{name}'; choose one of: " + ", ".join(CLIP_MODELS))


def load_list(filename: str, data_dir: Path = DATA_DIR) -> list[str]:
    with open(Path(data_dir) / filename, "r", encoding="utf-8", errors="replace") as f:
        return [line.strip() for line in f.readlines()]


def trending_list() -> list[str]:
    out = list(SITES)
    out += ["trending on " + s for s in SITES]
    out += ["featured on " + s for s in SITES]
    out += [s + " contest winner" for s in SITES]
    return out


# ------------------------------------------------------------------------------------------------ backends
class HfClip:
    """CLIPModel + tokenizer + image processor behind the interface the algorithm needs."""

    def __init__(self, source: str, device: str):
        import torch
        from transformers import CLIPImageProcessor, CLIPModel, CLIPTokenizerFast
        self.torch = torch
        self.device = device
        self.dtype = torch.float16 if device.startswith("cuda") else torch.float32
        self.model = CLIPModel.from_pretrained(source, torch_dtype=self.dtype).eval().to(device)
        self.tokenizer = CLIPTokenizerFast.from_pretrained(source)
        self.tokenizer.model_max_length = 1_000_000  # lengths are checked explicitly; no truncation warnings
        self.processor = CLIPImageProcessor.from_pretrained(source)
        self.context = min(int(getattr(self.model.config.text_config, "max_position_embeddings", CONTEXT_LENGTH)), CONTEXT_LENGTH)

    def to(self, device: str):
        self.model.to(device)
        self.device = device

    def encode_text(self, texts: list[str]):
        torch = self.torch
        tok = self.tokenizer(texts, padding="max_length", truncation=True, max_length=self.context, return_tensors="pt")
        with torch.no_grad():
            f = self.model.get_text_features(input_ids=tok["input_ids"].to(self.device),
                                             attention_mask=tok["attention_mask"].to(self.device))
        return f / f.norm(dim=-1, keepdim=True)

    def encode_image(self, image):
        torch = self.torch
        px = self.processor(images=image.convert("RGB"), return_tensors="pt")["pixel_values"].to(self.device, self.dtype)
        with torch.no_grad():
            f = self.model.get_image_features(pixel_values=px)
        return f / f.norm(dim=-1, keepdim=True)

    def at_max_len(self, text: str) -> bool:
        """True when the text fills the whole context window (upstream: last token slot is not padding)."""
        return len(self.tokenizer(text, truncation=False)["input_ids"]) >= self.context


class HfCaptioner:
    def __init__(self, name: str, device: str, max_length: int = 32):
        import torch
        from transformers import AutoModelForCausalLM, AutoProcessor, Blip2ForConditionalGeneration, BlipForConditionalGeneration
        source = CAPTION_MODELS[name]
        self.name, self.device, self.max_length, self.torch = name, device, max_length, torch
        self.dtype = torch.float16 if device.startswith("cuda") else torch.float32
        if name.startswith("git-"):
            self.dtype = torch.float32
            model = AutoModelForCausalLM.from_pretrained(source, torch_dtype=torch.float32)
        elif name.startswith("blip2-"):
            model = Blip2ForConditionalGeneration.from_pretrained(source, torch_dtype=self.dtype)
        else:
            model = BlipForConditionalGeneration.from_pretrained(source, torch_dtype=self.dtype)
        self.processor = AutoProcessor.from_pretrained(source)
        self.model = model.eval().to(device)

    def to(self, device: str):
        self.model.to(device)
        self.device = device

    def caption(self, image) -> str:
        torch = self.torch
        inputs = self.processor(images=image.convert("RGB"), return_tensors="pt").to(self.device)
        if not self.name.startswith("git-"):
            inputs = inputs.to(self.dtype)
        with torch.no_grad():
            tokens = self.model.generate(**inputs, max_new_tokens=self.max_length)
        return self.processor.batch_decode(tokens, skip_special_tokens=True)[0].strip()


# ------------------------------------------------------------------------------------------------ labels
class LabelTable:
    """Labels with their normalised CLIP text embeddings, cached on disk in the upstream safetensors layout."""

    def __init__(self, labels: list[str], desc: Optional[str], backend, torch, device: str, clip_name: str,
                 cache_dir: Optional[str], chunk_size: int, check: Callable[[], None]):
        self.labels, self.torch, self.device, self.backend = labels, torch, device, backend
        self.embeds = None
        if desc is None or not labels:
            return
        digest = hashlib.sha256(",".join(labels).encode()).hexdigest()
        sane = clip_name.replace("/", "_").replace("@", "_")
        path = os.path.join(cache_dir, f"{sane}_{desc}.safetensors") if cache_dir else None
        loaded = self._load(path, digest)
        if loaded is None:
            parts = []
            for start in range(0, len(labels), chunk_size):
                check()
                parts.append(backend.encode_text(labels[start:start + chunk_size]).half().cpu())
            loaded = torch.cat(parts)
            if path:
                self._save(path, loaded, digest)
        self.embeds = loaded.to(device=device, dtype=torch.float16 if device.startswith("cuda") else torch.float32)

    def _load(self, path, digest):
        if not path or not os.path.isfile(path):
            return None
        try:
            from safetensors.numpy import load_file
            import numpy as np
            tensors = load_file(path)
            want = np.array([ord(c) for c in digest], dtype=np.int8)
            if "hash" in tensors and "embeds" in tensors and np.array_equal(tensors["hash"], want):
                e = tensors["embeds"]
                if e.ndim == 2 and e.shape[0] == len(self.labels):
                    return self.torch.from_numpy(e.copy())
        except Exception:
            return None
        return None

    @staticmethod
    def _save(path, embeds, digest):
        import numpy as np
        from safetensors.numpy import save_file
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        save_file({"embeds": embeds.numpy(), "hash": np.array([ord(c) for c in digest], dtype=np.int8)}, tmp)
        os.replace(tmp, path)

    def rank(self, image_features, top_count: int = 1, reverse: bool = False) -> list[str]:
        return [self.labels[i] for i in self.top_indices(image_features, top_count, reverse)]

    def top_indices(self, image_features, top_count: int = 1, reverse: bool = False) -> list[int]:
        torch = self.torch
        top_count = min(top_count, len(self.labels))
        sim = (image_features.to(self.embeds.dtype) @ self.embeds.T).float()
        if reverse:
            sim = -sim
        return torch.topk(sim[0], top_count).indices.tolist()


def merge_tables(tables: list[LabelTable]) -> LabelTable:
    m = LabelTable.__new__(LabelTable)
    m.torch, m.device, m.backend = tables[0].torch, tables[0].device, tables[0].backend
    m.labels = [l for t in tables for l in t.labels]
    m.embeds = m.torch.cat([t.embeds for t in tables])
    return m


# ------------------------------------------------------------------------------------------------ algorithm
class Interrogator:
    def __init__(self, backend, torch, device: str, clip_name: str, captioner=None, cache_dir: Optional[str] = None,
                 chunk_size: int = 2048, flavor_intermediate_count: int = 2048, data_dir: Path = DATA_DIR,
                 offload: bool = False, check: Callable[[], None] = lambda: None):
        self.backend, self.captioner, self.torch, self.device = backend, captioner, torch, device
        self.clip_name, self.offload, self.check = clip_name, offload, check
        self.flavor_intermediate_count = flavor_intermediate_count
        self.on_gpu = {"clip": True, "caption": True}
        make = lambda labels, desc: LabelTable(labels, desc, backend, torch, device, clip_name, cache_dir, chunk_size, check)
        raw = load_list("artists.txt", data_dir)
        artists = [f"by {a}" for a in raw] + [f"inspired by {a}" for a in raw]
        self.artists = make(artists, "artists")
        self.flavors = make(load_list("flavors.txt", data_dir), "flavors")
        self.mediums = make(load_list("mediums.txt", data_dir), "mediums")
        self.movements = make(load_list("movements.txt", data_dir), "movements")
        self.trendings = make(trending_list(), "trendings")
        self.negative = make(load_list("negative.txt", data_dir), "negative")
        self._merged = None

    @property
    def merged(self) -> LabelTable:
        if self._merged is None:
            self._merged = merge_tables([self.artists, self.flavors, self.mediums, self.movements, self.trendings])
        return self._merged

    # -- low-VRAM shuffling between the caption model and CLIP
    def _prepare_clip(self):
        if self.offload and self.captioner is not None and self.on_gpu["caption"]:
            self.captioner.to("cpu"); self.on_gpu["caption"] = False
            self._gc()
        if not self.on_gpu["clip"]:
            self.backend.to(self.device); self.on_gpu["clip"] = True

    def _prepare_caption(self):
        if self.offload and self.on_gpu["clip"]:
            self.backend.to("cpu"); self.on_gpu["clip"] = False
            self._gc()
        if self.captioner is not None and not self.on_gpu["caption"]:
            self.captioner.to(self.device); self.on_gpu["caption"] = True

    def _gc(self):
        gc.collect()
        if self.torch.cuda.is_available():
            self.torch.cuda.empty_cache()

    # -- primitives
    def generate_caption(self, image) -> str:
        if self.captioner is None:
            raise EngineError(INVALID_PARAMS, "no caption model is loaded")
        self._prepare_caption()
        return self.captioner.caption(image)

    def image_to_features(self, image):
        self._prepare_clip()
        return self.backend.encode_image(image)

    def _text_features(self, texts: list[str], step: int = 512):
        self._prepare_clip()
        return self.torch.cat([self.backend.encode_text(texts[i:i + step]) for i in range(0, len(texts), step)])

    def rank_top(self, image_features, texts: list[str], reverse: bool = False) -> str:
        tf = self._text_features(texts)
        sim = (tf @ image_features.to(tf.dtype).T).float()
        if reverse:
            sim = -sim
        return texts[int(sim.argmax().item())]

    def similarity(self, image_features, text: str) -> float:
        return self.similarities(image_features, [text])[0]

    def similarities(self, image_features, texts: list[str]) -> list[float]:
        tf = self._text_features(texts)
        return (tf @ image_features.to(tf.dtype).T).float().T[0].tolist()

    def at_max_len(self, text: str) -> bool:
        return self.backend.at_max_len(text)

    def truncate_to_fit(self, text: str) -> str:
        parts = text.split(", ")
        new = parts[0]
        for part in parts[1:]:
            if self.at_max_len(new + part):
                break
            new += ", " + part
        return new

    def chain(self, image_features, phrases: list[str], best_prompt: str = "", best_sim: float = 0.0, min_count: int = 8,
              max_count: int = 32, reverse: bool = False) -> str:
        phrases = set(phrases)
        if not best_prompt:
            best_prompt = self.rank_top(image_features, sorted(phrases), reverse=reverse)
            best_sim = self.similarity(image_features, best_prompt)
            phrases.discard(best_prompt)
        curr_prompt, curr_sim = best_prompt, best_sim
        for idx in range(max_count):
            self.check()
            if not phrases:
                break
            ordered = sorted(phrases)  # deterministic tie-breaking; upstream iterates a set
            best = self.rank_top(image_features, [f"{curr_prompt}, {f}" for f in ordered], reverse=reverse)
            flavor = best[len(curr_prompt) + 2:]
            prompt = curr_prompt + ", " + flavor
            sim = self.similarity(image_features, prompt)
            if reverse:
                sim = -sim
            if sim > best_sim:
                best_prompt, best_sim = prompt, sim
            if sim > curr_sim or idx < min_count:
                curr_prompt, curr_sim = prompt, sim
            else:
                break
            if self.at_max_len(curr_prompt):
                break
            phrases.discard(flavor)
        return best_prompt

    # -- modes
    def interrogate_classic(self, image, max_flavors: int = 3, caption: Optional[str] = None) -> str:
        caption = caption or self.generate_caption(image)
        f = self.image_to_features(image)
        medium = self.mediums.rank(f, 1)[0]
        artist = self.artists.rank(f, 1)[0]
        trending = self.trendings.rank(f, 1)[0]
        movement = self.movements.rank(f, 1)[0]
        flaves = ", ".join(self.flavors.rank(f, max_flavors))
        if caption.startswith(medium):
            prompt = f"{caption} {artist}, {trending}, {movement}, {flaves}"
        else:
            prompt = f"{caption}, {medium} {artist}, {trending}, {movement}, {flaves}"
        return self.truncate_to_fit(prompt)

    def interrogate_fast(self, image, max_flavors: int = 32, caption: Optional[str] = None) -> str:
        caption = caption or self.generate_caption(image)
        f = self.image_to_features(image)
        tops = self.merged.rank(f, max_flavors)
        return self.truncate_to_fit(caption + ", " + ", ".join(tops))

    def interrogate_negative(self, image, max_flavors: int = 32) -> str:
        f = self.image_to_features(image)
        flaves = self.flavors.rank(f, self.flavor_intermediate_count, reverse=True) + self.negative.labels
        return self.chain(f, flaves, max_count=max_flavors, reverse=True)

    def interrogate(self, image, min_flavors: int = 8, max_flavors: int = 32, caption: Optional[str] = None) -> str:
        caption = caption or self.generate_caption(image)
        f = self.image_to_features(image)
        flaves = self.merged.rank(f, self.flavor_intermediate_count)
        best = self.chain(f, flaves, caption, self.similarity(f, caption), min_count=min_flavors, max_count=max_flavors)
        fast = self.interrogate_fast(image, max_flavors, caption=caption)
        classic = self.interrogate_classic(image, max_flavors, caption=caption)
        candidates = [caption, classic, fast, best]
        sims = self.similarities(f, candidates)
        return candidates[max(range(len(sims)), key=sims.__getitem__)]

    def prompt(self, image, mode: str, caption: Optional[str] = None, min_flavors: Optional[int] = None,
               max_flavors: Optional[int] = None) -> str:
        if mode == "best":
            return self.interrogate(image, min_flavors if min_flavors is not None else 8,
                                    max_flavors if max_flavors is not None else 32, caption)
        if mode == "caption":
            return caption if caption is not None else self.generate_caption(image)
        if mode == "classic":
            return self.interrogate_classic(image, max_flavors if max_flavors is not None else 3, caption)
        if mode == "fast":
            return self.interrogate_fast(image, max_flavors if max_flavors is not None else 32, caption)
        if mode == "negative":
            return self.interrogate_negative(image, max_flavors if max_flavors is not None else 32)
        raise EngineError(INVALID_PARAMS, f"unknown mode '{mode}' (best, fast, classic, negative, caption)")

    def analyze(self, image) -> dict:
        f = self.image_to_features(image)
        out = {}
        for key, table in (("medium", self.mediums), ("artist", self.artists), ("movement", self.movements),
                           ("trending", self.trendings), ("flavor", self.flavors)):
            top = table.rank(f, 5)
            out[key] = dict(zip(top, self.similarities(f, top)))
        return out


# ------------------------------------------------------------------------------------------------ batch output
class BatchWriter:
    def __init__(self, folder: str, mode: str, overwrite: bool):
        self.folder, self.mode, self.overwrite = folder, mode, overwrite
        self.file = self.csv = None
        self.written: list[str] = []
        self.skipped_existing: list[str] = []
        name = {BATCH_OUTPUT_MODES[1]: "batch.txt", BATCH_OUTPUT_MODES[2]: "batch.csv"}.get(mode)
        if name:
            path = os.path.join(folder, name)
            if os.path.exists(path) and not overwrite:
                raise EngineError(INVALID_PARAMS, f"{name} already exists in the folder; enable overwrite to replace it")
            self.file = open(path, "w", encoding="utf-8", newline="" if mode == BATCH_OUTPUT_MODES[2] else None)
            self.written.append(path)
            if mode == BATCH_OUTPUT_MODES[2]:
                self.csv = csv.writer(self.file, quoting=csv.QUOTE_MINIMAL)
                self.csv.writerow(["filename", "prompt"])

    def target_exists(self, file: str) -> bool:
        return self.mode == BATCH_OUTPUT_MODES[0] and not self.overwrite and \
            os.path.exists(os.path.join(self.folder, os.path.splitext(file)[0] + ".txt"))

    def add(self, file: str, prompt: str):
        if self.mode == BATCH_OUTPUT_MODES[0]:
            path = os.path.join(self.folder, os.path.splitext(file)[0] + ".txt")
            with open(path, "w", encoding="utf-8") as f:
                f.write(prompt)
            self.written.append(path)
        elif self.mode == BATCH_OUTPUT_MODES[1]:
            self.file.write(f"{prompt}\n")
        else:
            self.csv.writerow([file, prompt])

    def close(self):
        if self.file is not None:
            self.file.close()


# ------------------------------------------------------------------------------------------------ session
_lock = threading.Lock()
_session: dict = {"key": None, "ci": None}


def release() -> bool:
    """Drop the loaded models and free the memory they held.  Returns whether anything was loaded."""
    with _lock:
        had = _session["ci"] is not None
        _session["key"], _session["ci"] = None, None
    gc.collect()
    try:
        import torch
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:
        pass
    return had


def low_vram(device: str) -> bool:
    try:
        import torch
        if device.startswith("cuda") and torch.cuda.is_available():
            return torch.cuda.get_device_properties(torch.device(device)).total_memory <= 12 * 1024 ** 3
    except Exception:
        pass
    return False


def _open_image(path: str):
    from PIL import Image
    try:
        with Image.open(path) as im:
            return im.convert("RGB")
    except OSError as e:
        raise EngineError(INVALID_PARAMS, f"cannot read image '{path}': {e}")


def _get(params: dict, device: str, check, factory: Optional[Callable] = None, need_caption: bool = True) -> Interrogator:
    clip_name = str(params.get("clip_model") or DEFAULT_CLIP)
    caption_name = params.get("caption_model")
    if caption_name in (None, ""):
        caption_name = "blip-base" if low_vram(device) else "blip-large"
    if caption_name != "none" and caption_name not in CAPTION_MODELS:
        raise EngineError(INVALID_PARAMS, f"unknown caption model '{caption_name}'; choose one of: " + ", ".join(CAPTION_MODELS) + ", none")
    if not need_caption:
        caption_name = "none"
    key = (clip_name, caption_name, device)
    with _lock:
        have, have_key = _session["ci"], _session["key"]
        if have is not None and have_key[0] == clip_name and have_key[2] == device and                 (caption_name == "none" or have_key[1] == caption_name):
            have.check = check
            return have
    release()
    cache_dir = params.get("cache_dir") or None
    if factory is not None:
        ci = factory(clip_name, caption_name, device, cache_dir, check)
    else:
        low = low_vram(device)
        try:
            clip = HfClip(clip_repo(clip_name), device)
            captioner = HfCaptioner(caption_name, device, int(params.get("caption_max_length", 32))) if caption_name != "none" else None
        except ImportError as e:
            raise EngineError(UNSUPPORTED, f"CLIP Interrogator needs transformers, torch and safetensors: {e}")
        except OSError as e:
            raise EngineError(INVALID_PARAMS, f"cannot load model: {e}")
        import torch
        ci = Interrogator(clip, torch, device, clip_name, captioner, cache_dir,
                          chunk_size=1024 if low else 2048, flavor_intermediate_count=1024 if low else 2048,
                          offload=low, check=check)
    with _lock:
        _session["key"], _session["ci"] = key, ci
    return ci


def models_info(device: str = "cpu") -> dict:
    return {"clip_models": list(CLIP_MODELS), "caption_models": list(CAPTION_MODELS), "default_clip_model": DEFAULT_CLIP,
            "default_caption_model": "blip-base" if low_vram(device) else "blip-large", "modes": list(MODES),
            "batch_output_modes": list(BATCH_OUTPUT_MODES), "low_vram": low_vram(device),
            "loaded": _session["key"][0] if _session["key"] else None}


def run(engine, params: dict, ctx, factory: Optional[Callable] = None) -> dict:
    """Engine entry point: operation = models | prompt | analyze | batch | unload."""
    operation = str(params.get("operation", "prompt"))
    device = str(getattr(engine, "device", "cpu"))
    if operation == "models":
        return models_info(device)
    if operation == "unload":
        return {"unloaded": release()}
    if operation not in ("prompt", "analyze", "batch"):
        raise EngineError(INVALID_PARAMS, "operation must be models, prompt, analyze, batch or unload")
    mode = str(params.get("mode", "best"))
    if operation != "analyze" and mode not in MODES:
        raise EngineError(INVALID_PARAMS, f"unknown mode '{mode}' (best, fast, classic, negative, caption)")
    keep = bool(params.get("keep_loaded", False))
    ctx.check_interrupt()
    try:
        if operation == "batch":
            return _batch(params, ctx, device, mode, factory, keep)
        path = str(params.get("image_path", ""))
        if not os.path.isfile(path):
            raise EngineError(INVALID_PARAMS, f"image not found: {path}")
        image = _open_image(path)
        ci = _get(params, device, ctx.check_interrupt, factory, need_caption=operation == "prompt" and mode != "negative")
        ctx.progress(0, 1, "clip_interrogator")
        if operation == "analyze":
            result = {"clip_model": ci.clip_name, **ci.analyze(image)}
        else:
            caption = ci.generate_caption(image) if mode in ("caption", "best", "fast", "classic") else ""
            prompt = ci.prompt(image, mode, caption=caption or None, min_flavors=params.get("min_flavors"),
                               max_flavors=params.get("max_flavors"))
            sim = ci.similarity(ci.image_to_features(image), prompt) if prompt else 0.0
            result = {"prompt": prompt, "mode": mode, "clip_model": ci.clip_name, "caption": caption, "similarity": round(sim, 4)}
        ctx.progress(1, 1, "clip_interrogator")
        return result
    finally:
        if not keep:
            release()


def _batch(params: dict, ctx, device: str, mode: str, factory, keep: bool) -> dict:
    folder = str(params.get("folder", ""))
    if not os.path.isdir(folder):
        raise EngineError(INVALID_PARAMS, f"folder not found: {folder}")
    output_mode = str(params.get("output_mode") or BATCH_OUTPUT_MODES[0])
    if output_mode not in BATCH_OUTPUT_MODES:
        raise EngineError(INVALID_PARAMS, "output_mode must be one of: " + "; ".join(BATCH_OUTPUT_MODES))
    files = sorted(f for f in os.listdir(folder) if f.lower().endswith(BATCH_EXTENSIONS) and os.path.isfile(os.path.join(folder, f)))
    if not files:
        raise EngineError(INVALID_PARAMS, "Folder has no images (.png, .jpg, .jpeg)")
    writer = BatchWriter(folder, output_mode, bool(params.get("overwrite", False)))
    skipped, failed, done = [], [], 0
    try:
        todo = [f for f in files if not writer.target_exists(f)]
        writer.skipped_existing = [f for f in files if f not in todo]
        if not todo:
            raise EngineError(INVALID_PARAMS, "every image already has a .txt output; enable overwrite to replace them")
        ci = _get(params, device, ctx.check_interrupt, factory, need_caption=mode != "negative")
        total = len(todo)
        captions: dict[str, str] = {}
        # pass 1: captions (so the caption model can be offloaded once), pass 2: the rest, as upstream does
        if mode != "negative":
            for i, f in enumerate(todo):
                ctx.check_interrupt()
                try:
                    captions[f] = ci.generate_caption(_open_image(os.path.join(folder, f)))
                except EngineError:
                    captions[f] = ""
                ctx.progress(i + 1, total * 2, "clip_interrogator_caption")
        for i, f in enumerate(todo):
            ctx.check_interrupt()
            try:
                image = _open_image(os.path.join(folder, f))
            except EngineError:
                failed.append(f)
                ctx.progress(total + i + 1, total * 2, "clip_interrogator")
                continue
            prompt = ci.prompt(image, mode, caption=captions.get(f) or None, min_flavors=params.get("min_flavors"),
                               max_flavors=params.get("max_flavors"))
            writer.add(f, prompt)
            done += 1
            ctx.progress(total + i + 1, total * 2, "clip_interrogator")
    finally:
        writer.close()
        if not keep:
            release()
    return {"processed": done, "total": len(files), "failed": failed, "skipped_existing": writer.skipped_existing,
            "outputs": writer.written[:200], "mode": mode, "output_mode": output_mode, "folder": folder}
