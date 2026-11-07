"""Aesthetic Gradients, re-implemented from the documented method (extension index 8,
AUTOMATIC1111/stable-diffusion-webui-aesthetic-gradients @ 2624e5d, no licence; nothing was copied).

The extension's README is a stub that points to the original method, "Personalizing Text-to-Image Generation via
Aesthetic Gradients" (Gallego, 2022, github.com/vicgalle/stable-diffusion-aesthetic-gradients). What is implemented:

* an *aesthetic embedding* is the L2-normalised mean of the L2-normalised CLIP ViT-L/14 image embeddings of a few
  pictures (`create_embedding`); it is stored as a `.pt` file holding one (1, 768) tensor, and `.safetensors` is also
  read;
* for a prompt, the CLIP text encoder is copied (float32), and for `steps` Adam steps of learning rate `lr` it is moved
  so that the normalised projected text embedding gets closer (cosine similarity) to the aesthetic embedding; the
  personalised encoder encodes the prompt, and the conditioning used is the interpolation
  `(1 - weight) * original + weight * personalised`;
* `slerp` replaces the target embedding by the spherical interpolation, with `slerp_angle`, between the aesthetic
  embedding and the CLIP text embedding of `text` (an "aesthetic text");
* `negative` applies all of that to the negative prompt instead of the positive prompt.

Differences to the method / to the extension, all deliberate:

* The CLIP model with its text and visual projections is `openai/clip-vit-large-patch14` (the model SD 1.x and
  SDXL's first text encoder derive from). The OpenAI CLIP code is MIT; the model card states no licence. It is not bundled:
  `config.json` and `model.safetensors` (1,710,540,580
  bytes) are downloaded on first use from a pinned revision into `SDFX_AESTHETIC_GRADIENTS_CACHE` (default
  ~/.cache/stablediffusion-nui/aesthetic_gradients) and size/SHA-256 checked. The embedding creation needs the whole
  model; generation reads only the text projection (and, for SDXL, one layer and the final norm, see below) from it.
* SD 1.x/2.x: the checkpoint's own text encoder is personalised. SDXL: only its first text encoder (CLIP ViT-L/14,
  the 768-dimensional half of the context) is personalised; the OpenCLIP-bigG half and the pooled embedding are
  untouched, because the 768-dimensional aesthetic embedding lives in CLIP-L space. SD2.x (OpenCLIP-H text encoder)
  is rejected.
* The text projection that turns the pooled output into the CLIP embedding comes from the downloaded CLIP model, as do
  SDXL's missing encoder weights: SDXL checkpoints carry only 11 of the 12 layers of their first
  encoder and no final layer norm (loading one leaves them uninitialised, NaN in practice), so those are filled from
  the downloaded model for the loss; the conditioning never reads them.
* A prompt longer than 75 tokens is trained on the mean over its 77-token chunks; attention emphasis weights apply
  to the final conditioning but not to the loss.
* Personalised conditionings are cached per (text encoder, prompt, settings, embedding file state), so the batches
  and the hires pass that re-encode the same prompt do not train again.
"""
from __future__ import annotations

import collections
import hashlib
import io
import math
import os
import tempfile
import threading
import weakref
from pathlib import Path
from typing import Optional

from .protocol import EngineError, INVALID_PARAMS, UNSUPPORTED

CACHE_ENV = "SDFX_AESTHETIC_GRADIENTS_CACHE"
EMBEDDINGS_ENV = "SDFX_AESTHETIC_EMBEDDINGS"
CLIP_REPO = "openai/clip-vit-large-patch14"
CLIP_REVISION = "32bd64288804d66eefd0ccbe215aa642df71cc41"
CLIP_FILES = (
    {"filename": "config.json", "bytes": 4519,
     "sha256": "8a09b467700c58138c29d53c605b34ebc69beaadd13274a8a2af8ad2c2f4032a"},
    {"filename": "model.safetensors", "bytes": 1710540580,
     "sha256": "a2bf730a0c7debf160f7a6b50b3aaf3703e7e88ac73de7a314903141db026dcb"},
)
EMBED_DIM = 768
IMAGE_MEAN = (0.48145466, 0.4578275, 0.40821073)
IMAGE_STD = (0.26862954, 0.26130258, 0.27577711)
EMBEDDING_EXTS = (".pt", ".safetensors")

DEFAULTS = {"embedding": "", "weight": 0.9, "steps": 20, "lr": 0.0001, "slerp": False, "slerp_angle": 0.1,
            "text": "", "negative": False, "embedding_sha256": ""}
MAX_STEPS = 200


def normalize(config: dict) -> dict:
    """Validated settings; raises INVALID_PARAMS on anything the method cannot run with."""
    from .hooks import coerce_bool
    c = dict(DEFAULTS)
    c.update({k: v for k, v in (config or {}).items() if k in DEFAULTS and v is not None})
    try:
        out = {
            "embedding": str(c["embedding"]).strip(),
            "embedding_sha256": str(c["embedding_sha256"]).strip().lower(),
            "weight": float(c["weight"]),
            "steps": float(c["steps"]),
            "lr": float(c["lr"]),
            "slerp": coerce_bool(c["slerp"], False),
            "slerp_angle": float(c["slerp_angle"]),
            "text": str(c["text"]),
            "negative": coerce_bool(c["negative"], False),
        }
    except (TypeError, ValueError) as e:
        raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: invalid setting ({e})")
    for k in ("weight", "steps", "lr", "slerp_angle"):
        if not math.isfinite(out[k]):
            raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: {k} must be a finite number")
    if out["steps"] != int(out["steps"]) or not 0 <= out["steps"] <= MAX_STEPS:
        raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: steps must be a whole number from 0 to {MAX_STEPS}")
    out["steps"] = int(out["steps"])
    if not 0.0 <= out["weight"] <= 1.0:
        raise EngineError(INVALID_PARAMS, "Aesthetic Gradients: weight must be between 0 and 1")
    if not 0.0 <= out["slerp_angle"] <= 1.0:
        raise EngineError(INVALID_PARAMS, "Aesthetic Gradients: slerp angle must be between 0 and 1")
    if out["lr"] < 0.0:
        raise EngineError(INVALID_PARAMS, "Aesthetic Gradients: learning rate must not be negative")
    if not out["embedding"]:
        raise EngineError(INVALID_PARAMS, "Aesthetic Gradients needs an aesthetic embedding (file name or path)")
    if out["embedding_sha256"] and (len(out["embedding_sha256"]) != 64 or
                                     any(ch not in "0123456789abcdef" for ch in out["embedding_sha256"])):
        raise EngineError(INVALID_PARAMS, "Aesthetic Gradients: invalid embedding fingerprint")
    return out


# ------------------------------------------------------------------------------------------------ model files
def cache_root() -> Path:
    configured = os.environ.get(CACHE_ENV)
    return Path(configured) if configured else Path.home() / ".cache" / "stablediffusion-nui" / "aesthetic_gradients"


def embedding_dirs() -> list[Path]:
    dirs = [Path(p) for p in os.environ.get(EMBEDDINGS_ENV, "").split(os.pathsep) if p.strip()]
    dirs.append(cache_root() / "embeddings")
    return dirs


class ModelUnavailable(EngineError):
    def __init__(self, message: str):
        super().__init__(UNSUPPORTED, message)


def clip_folder(root=None, allow_download: Optional[bool] = None, progress=None) -> Path:
    """The verified local copy of the pinned CLIP ViT-L/14 files (downloaded on first use)."""
    from . import nsfw_safety
    try:
        paths = [nsfw_safety.materialize(CLIP_REPO, CLIP_REVISION, asset, root or cache_root(), allow_download,
                                         progress=progress) for asset in CLIP_FILES]
    except nsfw_safety.ModelUnavailable as e:
        raise ModelUnavailable(f"Aesthetic Gradients needs {CLIP_REPO}: {e}") from e
    return paths[0].parent


class TextHead:
    """The text projection of CLIP ViT-L/14 (the checkpoints do not carry it)."""

    def __init__(self, projection):
        self.projection = projection

    def to(self, device):
        return TextHead(self.projection.to(device))

    def embed(self, pooled):
        """Normalised projected text embedding of one pooled (post final norm) vector."""
        import torch
        return torch.nn.functional.normalize(pooled.float() @ self.projection.t(), dim=-1)


_heads: dict = {}
_lock = threading.Lock()


def text_head(device, root=None) -> TextHead:
    """The text projection of the pinned CLIP ViT-L/14 as a float32 tensor on `device` (read once, cached on the CPU)."""
    folder = clip_folder(root)
    key = str(folder)
    with _lock:
        head = _heads.get(key)
        if head is None:
            from safetensors import safe_open
            with safe_open(str(folder / "model.safetensors"), framework="pt", device="cpu") as f:
                head = TextHead(f.get_tensor("text_projection.weight").float().clone())
            _heads.clear()
            _heads[key] = head
    return head.to(device)


def reference_tensors(prefixes, root=None) -> dict:
    """float32 tensors of the pinned CLIP ViT-L/14 whose names start with one of `prefixes` (text tower names)."""
    from safetensors import safe_open
    out = {}
    with safe_open(str(clip_folder(root) / "model.safetensors"), framework="pt", device="cpu") as f:
        for name in f.keys():
            if name.startswith(tuple(prefixes)):
                out[name] = f.get_tensor(name).float().clone()
    return out


# ------------------------------------------------------------------------------------------------ embeddings
def resolve_embedding(spec: str) -> Path:
    """A file path, or a name looked up (with and without `.pt` / `.safetensors`) in the embedding folders."""
    spec = spec.strip()
    candidates = [Path(spec)]
    if not os.path.isabs(spec):
        for d in embedding_dirs():
            for ext in ("",) + EMBEDDING_EXTS:
                candidates.append(d / (spec + ext))
    for p in candidates:
        if p.is_file():
            return p
    raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: embedding '{spec}' not found (searched "
                                      f"{', '.join(str(d) for d in embedding_dirs())})")


def list_embeddings() -> list[str]:
    names: dict = {}
    for d in embedding_dirs():
        if d.is_dir():
            for p in sorted(d.iterdir()):
                if p.suffix.lower() in EMBEDDING_EXTS and p.is_file():
                    names.setdefault(p.stem, str(p))
    return sorted(names)


def embedding_snapshot(path):
    """Read bounded bytes once; the digest describes exactly the bytes subsequently decoded."""
    path = Path(path)
    try:
        with path.open("rb") as source:
            data = source.read(64 * 1024 * 1024 + 1)
        if len(data) > 64 * 1024 * 1024:
            raise ValueError("embedding exceeds 64 MiB")
        return data, hashlib.sha256(data).hexdigest()
    except (OSError, ValueError) as e:
        raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: cannot read {path.name} ({e})") from e


def decode_embedding_snapshot(snapshot, name):
    """Decode the same bytes whose digest was recorded, without reopening the file."""
    import torch
    path = Path(name)
    try:
        if path.suffix.lower() == ".safetensors":
            from safetensors.torch import load
            data = load(snapshot)
            t = next(iter(data.values())) if len(data) == 1 else data.get("embedding", data.get("aesthetic_embedding"))
        else:
            data = torch.load(io.BytesIO(snapshot), map_location="cpu", weights_only=True)
            if isinstance(data, dict):
                tensors = [v for v in data.values() if isinstance(v, torch.Tensor)]
                t = tensors[0] if len(tensors) == 1 else data.get("embedding", data.get("aesthetic_embedding"))
            else:
                t = data
    except EngineError:
        raise
    except Exception as e:
        raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: cannot read {path.name} ({type(e).__name__}: {e})")
    if not isinstance(t, torch.Tensor) or t.numel() != EMBED_DIM:
        raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: {path.name} is not a {EMBED_DIM}-dimensional "
                                          f"CLIP ViT-L/14 embedding")
    t = t.detach().float().reshape(EMBED_DIM)
    if not bool(torch.isfinite(t).all()):
        raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: {path.name} holds non-finite values")
    norm = float(t.norm())
    if norm <= 0.0:
        raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: {path.name} is a zero embedding")
    return t / norm


def load_embedding(path):
    """The normalised (768,) float32 embedding from a .pt or .safetensors file."""
    data, _ = embedding_snapshot(path)
    return decode_embedding_snapshot(data, Path(path).name)


def add_provenance(result, digests, warn):
    """Attach a digest only when all aesthetic conditioning used one exact byte snapshot."""
    if len(digests) == 1:
        digest = next(iter(digests))
        for image in result.get("images", []) + result.get("auxiliary_images", []):
            image.setdefault("infotext_fields", []).append(["Aesthetic embedding SHA256", digest])
    elif len(digests) > 1:
        for image in result.get("images", []) + result.get("auxiliary_images", []):
            image.setdefault("infotext_fields", []).append(["Aesthetic embedding SHA256", "ambiguous"])
        warn("Aesthetic embedding changed during generation; no embedding fingerprint was recorded")


def image_paths(images) -> list:
    paths: list[Path] = []
    for entry in ([images] if isinstance(images, (str, os.PathLike)) else list(images)):
        p = Path(entry)
        if p.is_dir():
            paths += sorted(q for q in p.iterdir() if q.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp", ".bmp"))
        elif p.is_file():
            paths.append(p)
        else:
            raise EngineError(INVALID_PARAMS, f"Aesthetic Gradients: {p} does not exist")
    if not paths:
        raise EngineError(INVALID_PARAMS, "Aesthetic Gradients: no images to embed")
    return paths


def embed_images(paths, device=None, batch_size: int = 8, check_interrupt=None, root=None):
    """The (N, 768) float32 L2-normalised CLIP ViT-L/14 image embeddings (CPU tensor) of the image files."""
    import torch
    from PIL import Image
    folder = clip_folder(root)
    from transformers import CLIPImageProcessor, CLIPModel
    processor = CLIPImageProcessor(size={"shortest_edge": 224}, crop_size={"height": 224, "width": 224},
                                   image_mean=list(IMAGE_MEAN), image_std=list(IMAGE_STD), resample=3)
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    model = CLIPModel.from_pretrained(str(folder), local_files_only=True, torch_dtype=torch.float32).to(device).eval()
    rows = []
    try:
        with torch.inference_mode():
            for i in range(0, len(paths), max(1, batch_size)):
                if check_interrupt is not None:
                    check_interrupt()
                imgs = []
                for p in paths[i:i + batch_size]:
                    with Image.open(p) as im:
                        imgs.append(im.convert("RGB"))
                pixels = processor(images=imgs, return_tensors="pt").pixel_values.to(device)
                e = model.get_image_features(pixel_values=pixels)
                e = e.pooler_output if hasattr(e, "pooler_output") else e
                rows.append(torch.nn.functional.normalize(e.float(), dim=-1).cpu())
    finally:
        del model
        if str(device) != "cpu":
            torch.cuda.empty_cache()
    return torch.cat(rows)


def create_embedding(images, output, device=None, batch_size: int = 8, check_interrupt=None, root=None,
                     overwrite=False, publish=None) -> dict:
    """Embeds the images with CLIP ViT-L/14 and stores the normalised mean as a (1, 768) `.pt` file at `output`.
    `images` are file paths or a folder. Returns {path, count, norm_of_mean}."""
    import torch
    paths = image_paths(images)
    mean = embed_images(paths, device, batch_size, check_interrupt, root).double().mean(dim=0)
    norm = float(mean.norm())
    if norm <= 0.0:
        raise EngineError(INVALID_PARAMS, "Aesthetic Gradients: the image embeddings cancel out")
    out = (mean / norm).float().reshape(1, EMBED_DIM)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Stage in the destination directory so a failed save or an interruption cannot damage the old file.
    fd, name = tempfile.mkstemp(prefix=".aesthetic-", suffix=".tmp", dir=output.parent)
    os.close(fd)
    staged = Path(name)
    try:
        torch.save(out, str(staged))
        if check_interrupt is not None:
            check_interrupt()
        def commit():
            if overwrite:
                os.replace(staged, output)
            else:
                # link is atomic and fails if another writer created the name during embedding.
                os.link(staged, output)
        if publish is None:
            commit()
        else:
            publish(commit)
    finally:
        staged.unlink(missing_ok=True)
    return {"path": str(output), "count": len(paths), "norm_of_mean": norm}


def score_images(images, embedding, device=None, root=None) -> list:
    """Cosine similarity of each image's CLIP embedding to the aesthetic embedding (`embedding`: name or path)."""
    paths = image_paths(images)
    target = load_embedding(resolve_embedding(str(embedding)))
    return [float(x) for x in (embed_images(paths, device, root=root) @ target)]


def slerp(a, b, t: float):
    """Spherical interpolation of two unit vectors (falls back to a normalised lerp when they are parallel)."""
    import torch
    dot = float((a * b).sum().clamp(-1.0, 1.0))
    omega = math.acos(dot)
    if abs(math.sin(omega)) < 1e-6:
        r = (1 - t) * a + t * b
    else:
        s = math.sin(omega)
        r = (math.sin((1 - t) * omega) / s) * a + (math.sin(t * omega) / s) * b
    return r / r.norm()


# ------------------------------------------------------------------------------------------------ personalisation
class _Shim:
    """The pipeline with another first text encoder; everything else is forwarded."""

    def __init__(self, pipe, text_encoder):
        self._pipe = pipe
        self.text_encoder = text_encoder

    def __getattr__(self, name):
        return getattr(self._pipe, name)


_cache: "collections.OrderedDict" = collections.OrderedDict()
CACHE_LIMIT = 16


def _eos_position(tokens, eos_id: int) -> int:
    return tokens.index(eos_id) if eos_id in tokens else len(tokens) - 1


def _embed_text(te, chunks, head, eos_id: int, device):
    """Mean over chunks of the normalised projected pooled text embedding (differentiable)."""
    import torch
    es = []
    for toks, _ in chunks:
        ids = torch.tensor([toks], dtype=torch.long, device=device)
        h = te(ids).last_hidden_state  # already through the final layer norm; pooled = the EOS position
        es.append(head.embed(h[0, _eos_position(toks, eos_id)]))
    return torch.stack(es).mean(dim=0)


def state_copy(engine, te, sdxl: bool = False, root=None) -> dict:
    """The text encoder's weights as float32 CPU tensors. With sequential offload the weights live behind accelerate
    hooks (meta tensors), so those are lifted for the copy and put back, as the embedding tools do.

    SDXL checkpoints carry only the first 11 layers of the first encoder (the conditioning is taken from the
    penultimate layer) and no final layer norm; loading them leaves the last layer and the norm uninitialised (NaN in
    practice). Those weights are replaced from the downloaded CLIP ViT-L/14 so the copy is a complete, valid encoder:
    the loss needs its last layer, and the conditioning never reads the replaced weights."""
    def copy():
        state = te.state_dict()
        if any(getattr(v, "is_meta", False) for v in state.values()):
            return None
        return {k: v.detach().to("cpu").float().clone() for k, v in state.items()}
    state = copy()
    if state is None:
        access = getattr(engine, "_with_embedding_encoder_access", None)
        state = access(copy) if access is not None else None
    if state is None:
        raise EngineError(UNSUPPORTED, "Aesthetic Gradients cannot read the offloaded text encoder weights")
    if sdxl:
        last = int(te.config.num_hidden_layers) - 1
        state.update(reference_tensors((f"text_model.encoder.layers.{last}.", "text_model.final_layer_norm."), root))
    return state


def build_encoder(te, state, device):
    """A float32 eval-mode copy of the text encoder `te` holding `state`, on `device`."""
    clone = type(te)(te.config)
    clone.load_state_dict(state)
    return clone.to(device).float().eval()


def personalized_encoder(pipe, te, text: str, target, steps: int, lr: float, head, device, state=None):
    """A float32 copy of `te` (weights `state`) moved for `steps` Adam steps towards `target`; (encoder, losses)."""
    import torch
    from . import prompt as prompt_mod
    chunks = prompt_mod._chunks_for(pipe, pipe.tokenizer, text)
    eos_id = int(pipe.tokenizer.eos_token_id)
    with torch.inference_mode(False), torch.enable_grad():
        clone = build_encoder(te, state if state is not None else
                              {k: v.detach().to("cpu").float().clone() for k, v in te.state_dict().items()}, device)
        head = head.to(device)
        goal = target.detach().clone().to(device)
        losses = []
        opt = torch.optim.Adam(clone.parameters(), lr=lr)
        for _ in range(steps):
            opt.zero_grad(set_to_none=True)
            loss = -(_embed_text(clone, chunks, head, eos_id, device) * goal).sum()
            loss.backward()
            opt.step()
            losses.append(float(loss.detach()))
        for p in clone.parameters():
            p.requires_grad_(False)
    return clone, losses


def _target_embedding(pipe, te, c, emb, device, head, state):
    import torch
    target = emb.to(device)
    if c["slerp"] and c["text"].strip():
        from . import prompt as prompt_mod
        chunks = prompt_mod._chunks_for(pipe, pipe.tokenizer, c["text"])[:1]
        with torch.no_grad():
            t = _embed_text(build_encoder(te, state, device), chunks, head.to(device),
                            int(pipe.tokenizer.eos_token_id), device).float()
        target = slerp(target, t / t.norm(), c["slerp_angle"])
    return target


def encode_prompt(engine, pipe, text: str, gp, c: dict, plain_encode):
    """Conditioning of `text` blended with its personalised version. `plain_encode(pipe_like, text)` is the engine's
    ordinary encoder returning the embeds (SD1.x: tensor, SDXL: (embeds, pooled)). Returns the same shape."""
    import torch
    if engine.kind == "sd2":
        raise EngineError(UNSUPPORTED, "Aesthetic Gradients needs a CLIP ViT-L/14 text encoder (SD 1.x or SDXL); "
                                       "SD 2.x uses OpenCLIP-H")
    te = pipe.text_encoder
    if te is None or not hasattr(te, "text_model") or int(te.config.hidden_size) != EMBED_DIM:
        raise EngineError(UNSUPPORTED, "Aesthetic Gradients needs a 768-dimensional CLIP ViT-L/14 text encoder")
    sdxl = bool(engine.sdxl)
    base = plain_encode(pipe, text)
    path = resolve_embedding(c["embedding"])
    snapshot, digest = embedding_snapshot(path)
    if c.get("embedding_sha256") and c["embedding_sha256"] != digest:
        raise EngineError(INVALID_PARAMS, "Aesthetic Gradients: embedding fingerprint does not match the recorded file")
    stat = path.stat()
    key = (id(te), text, path.resolve().as_posix(), stat.st_size, stat.st_mtime_ns, digest, c["steps"], c["lr"],
           c["slerp"], c["slerp_angle"], c["text"] if c["slerp"] else "", sdxl, gp.clip_skip)
    hit = _cache.get(key)
    if hit is not None and hit[0]() is te:
        _cache.move_to_end(key)
        tuned = hit[1]
    else:
        from . import prompt as prompt_mod
        device = prompt_mod._te_device(pipe, te)
        emb = decode_embedding_snapshot(snapshot, path.name)
        head = text_head(device)
        state = state_copy(engine, te, sdxl)
        target = _target_embedding(pipe, te, c, emb, device, head, state)
        clone, _ = personalized_encoder(pipe, te, text, target, c["steps"], c["lr"], head, device, state)
        shim = _Shim(pipe, clone)
        tuned = plain_encode(shim, text)
        tuned = tuple(x.detach().cpu() for x in tuned) if sdxl else tuned.detach().cpu()
        del clone
        if str(device).startswith("cuda"):
            torch.cuda.empty_cache()
        _cache[key] = (weakref.ref(te), tuned)
        while len(_cache) > CACHE_LIMIT:
            _cache.popitem(last=False)
    if getattr(engine, "_aesthetic_digests", None) is not None:
        engine._aesthetic_digests.add(digest)
    w = c["weight"]
    def mix(b, t):
        t = t.to(b.device, torch.float32)
        return ((1.0 - w) * b.float() + w * t).to(b.dtype)
    if sdxl:
        return mix(base[0], tuned[0]), base[1]
    return mix(base, tuned)


def main(argv=None) -> int:
    """`python -m sdfx.aesthetic_gradients create NAME IMAGE_OR_FOLDER... [--output-dir DIR]` / `list`."""
    import argparse
    parser = argparse.ArgumentParser(prog="python -m sdfx.aesthetic_gradients")
    sub = parser.add_subparsers(dest="command", required=True)
    make = sub.add_parser("create", help="embed pictures into an aesthetic embedding (.pt)")
    make.add_argument("name")
    make.add_argument("images", nargs="+")
    make.add_argument("--output-dir")
    make.add_argument("--overwrite", action="store_true")
    sub.add_parser("list", help="list the embeddings found in the embedding folders")
    rate = sub.add_parser("score", help="cosine similarity of images to an embedding")
    rate.add_argument("embedding")
    rate.add_argument("images", nargs="+")
    args = parser.parse_args(argv)
    if args.command == "list":
        for name in list_embeddings():
            print(name)
        return 0
    if args.command == "score":
        for path, value in zip(image_paths(args.images), score_images(args.images, args.embedding)):
            print(f"{value:.6f}  {path}")
        return 0
    folder = Path(args.output_dir) if args.output_dir else embedding_dirs()[0]
    output = folder / (args.name if args.name.lower().endswith(".pt") else args.name + ".pt")
    if output.exists() and not args.overwrite:
        print(f"{output} exists (use --overwrite)")
        return 1
    result = create_embedding(args.images, output, overwrite=args.overwrite)
    print(f"{result['count']} image(s) -> {result['path']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
