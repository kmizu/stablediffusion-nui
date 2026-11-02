"""Character extraction, tagging and embedding for the Character Searcher tab (ledger target `chara_searcher`).

Re-implements the data preparation of NON906/chara-searcher (index 331, MIT, pinned dee0475; its README, scripts and
the structure of its pipeline were read, no code was copied):

1. every source image (or one frame per `frame_interval` seconds of a video) is searched for characters;
2. each character is cut out with a transparent background and saved as `<source>_<n>.png` in the dataset folder;
3. every cut-out is tagged (`<name>.txt`, comma separated, underscores replaced by spaces, parentheses escaped);
4. every cut-out is embedded for similarity search (`embedding.npz` key `embedding`, `embedding.json`
   `{"files": [{"path", "width", "height"}]}`, the upstream layout).

Where this differs from upstream (documented in docs/chara-searcher-verification.md): the upstream instance
segmentation needs mmdet/mmcv and the CartoonSegmentation code base, which cannot be shipped in this application.
Characters are found with the anime person detector of deepghs (YOLOv8, MIT) and cut out with the ISNet anime
segmentation of SkyTNT (Apache-2.0); only the pixels connected to a detection are kept, so two characters side by
side become two images. Tags come from the WD ViT tagger v3 (SmilingWolf, Apache-2.0) and embeddings from CLIP
ViT-B/32 (OpenAI, the model behind sentence-transformers' `clip-ViT-B-32`). Video frames are sampled by time with
OpenCV instead of ffmpeg key frames.

Nothing is bundled: every model file is downloaded on first use from a pinned revision into `SDFX_CHARA_CACHE`
(default ~/.cache/stablediffusion-nui/chara_searcher) and checked against a pinned size and SHA-256 before use.
"""
from __future__ import annotations

import json
import logging
import os
import re
import threading
from pathlib import Path
from typing import Callable, Optional

import numpy as np

from . import nsfw_safety

log = logging.getLogger(__name__)

CACHE_ENV = "SDFX_CHARA_CACHE"

PERSON_REPO = "deepghs/anime_person_detection"
PERSON_REVISION = "e39c744c22432ad01f91dd254fe2b02c8d878b8c"
PERSON_FILE = {"filename": "person_detect_v1.1_m/model.onnx", "bytes": 103459671,
               "sha256": "c80365a98ca8388fd2f01fd5abea0439e92265bceceb048bef38b0849303467c"}
PERSON_THRESHOLD = 0.348  # the F1-optimal threshold published next to the model
PERSON_SIZE = 640

SEG_REPO = "skytnt/anime-seg"
SEG_REVISION = "493cb60893f47441b26ec4fb9a306bce9e342982"
SEG_FILE = {"filename": "isnetis.onnx", "bytes": 176069933,
            "sha256": "f15622d853e8260172812b657053460e20806f04b9e05147d49af7bed31a6e99"}
SEG_SIZE = 1024

WD_REPO = "SmilingWolf/wd-vit-tagger-v3"
WD_REVISION = "7f6b584d0bd3f55c4531f14ba3d4761b2bccdc0f"
WD_MODEL = {"filename": "model.onnx", "bytes": 378536310,
            "sha256": "35f23693620b668f4d53fd3c62bf65e40af739bc52c7eb0fbc49258b58d065b6"}
WD_TAGS = {"filename": "selected_tags.csv", "bytes": 308468,
           "sha256": "298633d94d0031d2081c0893f29c82eab7f0df00b08483ba8f29d1e979441217"}

CLIP_REPO = "openai/clip-vit-base-patch32"
CLIP_REVISION = "3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268"
CLIP_FILES = (
    {"filename": "config.json", "bytes": 4186,
     "sha256": "b575ef3c36f2a057fa19e221650105052d61cc9c1a972ec15019c6261ec98770"},
    {"filename": "pytorch_model.bin", "bytes": 605247071,
     "sha256": "a63082132ba4f97a80bea76823f544493bffa8082296d62d71581a4feff1576f"},
)
CLIP_MEAN = (0.48145466, 0.4578275, 0.40821073)
CLIP_STD = (0.26862954, 0.26130258, 0.27577711)
CLIP_SIZE = 224

IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp", ".bmp")
VIDEO_SUFFIXES = (".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v", ".flv", ".wmv")
EMBEDDING_NPZ = "embedding.npz"
EMBEDDING_JSON = "embedding.json"

DEFAULTS = {
    "mask_threshold": 0.6,      # upstream segmentation mask threshold: alpha = (mask - t) / (1 - t)
    "instance_threshold": 0.3,  # upstream minimum instance score (the detector threshold)
    "padding": 0.1,             # fraction of the character box added on every side
    "tag_threshold": 0.35,      # upstream tagging threshold
    "frame_interval": 4.0,      # seconds between video frames (upstream: -r 0.25)
    "max_frames": 2000,
    "exclude_tags": "",
}
MAX_SOURCES = 5000


class ModelUnavailable(nsfw_safety.ModelUnavailable):
    """A model could not be downloaded, found or verified."""


def cache_root() -> Path:
    configured = os.environ.get(CACHE_ENV)
    return Path(configured) if configured else Path.home() / ".cache" / "stablediffusion-nui" / "chara_searcher"


def _fetch(repo: str, revision: str, asset: dict, progress=None) -> Path:
    try:
        return nsfw_safety.materialize(repo, revision, asset, cache_root(), progress=progress)
    except nsfw_safety.ModelUnavailable as exc:
        raise ModelUnavailable(str(exc)) from exc


def available() -> bool:
    from importlib.util import find_spec
    return all(find_spec(name) is not None for name in ("onnxruntime", "torch", "transformers", "PIL", "numpy", "cv2"))


# ------------------------------------------------------------------------------------------------ settings
def normalize(raw: Optional[dict]) -> dict:
    """Validated import settings (ValueError on anything the app would not honour)."""
    cfg = dict(DEFAULTS)
    for key, value in (raw or {}).items():
        if key not in DEFAULTS:
            continue
        if key == "exclude_tags":
            cfg[key] = str(value or "")
            continue
        try:
            number = float(value)
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{key} must be a number (got {value!r})") from exc
        if not np.isfinite(number):
            raise ValueError(f"{key} must be finite")
        cfg[key] = number
    for key in ("mask_threshold", "instance_threshold", "tag_threshold"):
        if not 0.0 <= cfg[key] < 1.0:
            raise ValueError(f"{key} must be at least 0 and below 1")
    if not 0.0 <= cfg["padding"] <= 1.0:
        raise ValueError("padding must be between 0 and 1")
    if cfg["frame_interval"] <= 0:
        raise ValueError("frame_interval must be positive")
    cfg["max_frames"] = max(1, int(cfg["max_frames"]))
    return cfg


# ------------------------------------------------------------------------------------------------ ONNX sessions
_lock = threading.Lock()
_sessions: dict = {}


def _session(path: Path):
    with _lock:
        cached = _sessions.get(str(path))
        if cached is not None:
            return cached
        try:
            import onnxruntime
        except ImportError as exc:
            raise ImportError("Character Searcher needs onnxruntime") from exc
        options = onnxruntime.SessionOptions()
        options.log_severity_level = 3
        # CPU only: the GPU belongs to the loaded checkpoint and these models are small enough for the CPU.
        value = onnxruntime.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        _sessions[str(path)] = value
        return value


def release_models() -> None:
    """Drop every cached model (the engine calls this when a request ends so the GPU/RAM is returned)."""
    with _lock:
        _sessions.clear()
        _clip.clear()


# ------------------------------------------------------------------------------------------------ detection
def letterbox(rgb: np.ndarray, size: int):
    """Resize to fit a size x size canvas (gray 114 padding, as the ultralytics exporter trains); NCHW float 0..1."""
    import cv2
    height, width = rgb.shape[:2]
    scale = min(size / width, size / height)
    new_w, new_h = max(1, round(width * scale)), max(1, round(height * scale))
    resized = cv2.resize(rgb, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((size, size, 3), 114, dtype=np.uint8)
    left, top = (size - new_w) // 2, (size - new_h) // 2
    canvas[top:top + new_h, left:left + new_w] = resized
    tensor = canvas.astype(np.float32).transpose(2, 0, 1)[None] / 255.0
    return tensor, scale, left, top


def nms(boxes: np.ndarray, scores: np.ndarray, iou: float = 0.7) -> list:
    """Greedy non maximum suppression over (x1, y1, x2, y2) boxes; indices in descending score order."""
    order = np.argsort(-scores)
    keep = []
    while order.size:
        i = int(order[0])
        keep.append(i)
        if order.size == 1:
            break
        rest = order[1:]
        x1 = np.maximum(boxes[i, 0], boxes[rest, 0])
        y1 = np.maximum(boxes[i, 1], boxes[rest, 1])
        x2 = np.minimum(boxes[i, 2], boxes[rest, 2])
        y2 = np.minimum(boxes[i, 3], boxes[rest, 3])
        inter = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
        area_i = (boxes[i, 2] - boxes[i, 0]) * (boxes[i, 3] - boxes[i, 1])
        area_r = (boxes[rest, 2] - boxes[rest, 0]) * (boxes[rest, 3] - boxes[rest, 1])
        union = area_i + area_r - inter
        overlap = np.where(union > 0, inter / np.maximum(union, 1e-9), 0.0)
        order = rest[overlap <= iou]
    return keep


def decode_detections(raw: np.ndarray, scale: float, left: int, top: int, width: int, height: int,
                      threshold: float, iou: float = 0.7):
    """YOLOv8 output [1, 4 + classes, anchors] (centre x/y, w, h in letterbox pixels) -> [(x1, y1, x2, y2, score)]."""
    rows = np.asarray(raw, dtype=np.float32)
    rows = rows.reshape(rows.shape[-2], rows.shape[-1]).T
    scores = rows[:, 4:].max(axis=1)
    keep = scores >= threshold
    if not np.any(keep):
        return []
    rows, scores = rows[keep], scores[keep]
    boxes = np.empty((rows.shape[0], 4), dtype=np.float32)
    boxes[:, 0] = rows[:, 0] - rows[:, 2] / 2
    boxes[:, 1] = rows[:, 1] - rows[:, 3] / 2
    boxes[:, 2] = rows[:, 0] + rows[:, 2] / 2
    boxes[:, 3] = rows[:, 1] + rows[:, 3] / 2
    boxes[:, [0, 2]] = (boxes[:, [0, 2]] - left) / scale
    boxes[:, [1, 3]] = (boxes[:, [1, 3]] - top) / scale
    boxes[:, [0, 2]] = boxes[:, [0, 2]].clip(0, width)
    boxes[:, [1, 3]] = boxes[:, [1, 3]].clip(0, height)
    picked = nms(boxes, scores, iou)
    return [(float(boxes[i, 0]), float(boxes[i, 1]), float(boxes[i, 2]), float(boxes[i, 3]), float(scores[i]))
            for i in picked if boxes[i, 2] - boxes[i, 0] >= 8 and boxes[i, 3] - boxes[i, 1] >= 8]


def detect_characters(rgb: np.ndarray, threshold: float, session=None, progress=None):
    """Character boxes of an RGB uint8 image, left to right."""
    sess = session or _session(_fetch(PERSON_REPO, PERSON_REVISION, PERSON_FILE, progress))
    tensor, scale, left, top = letterbox(rgb, PERSON_SIZE)
    raw = sess.run(None, {sess.get_inputs()[0].name: tensor})[0]
    found = decode_detections(raw, scale, left, top, rgb.shape[1], rgb.shape[0], threshold)
    return sorted(found, key=lambda box: box[0])


# ------------------------------------------------------------------------------------------------ cut-out
def foreground_alpha(rgb: np.ndarray, session=None, progress=None) -> np.ndarray:
    """Foreground probability 0..1 (H x W float32) of an RGB uint8 crop from the ISNet anime segmentation."""
    import cv2
    sess = session or _session(_fetch(SEG_REPO, SEG_REVISION, SEG_FILE, progress))
    height, width = rgb.shape[:2]
    scale = SEG_SIZE / max(height, width)
    new_w, new_h = max(1, round(width * scale)), max(1, round(height * scale))
    resized = cv2.resize(rgb, (new_w, new_h), interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR)
    canvas = np.zeros((SEG_SIZE, SEG_SIZE, 3), dtype=np.float32)
    left, top = (SEG_SIZE - new_w) // 2, (SEG_SIZE - new_h) // 2
    canvas[top:top + new_h, left:left + new_w] = resized.astype(np.float32) / 255.0
    mask = sess.run(None, {sess.get_inputs()[0].name: canvas.transpose(2, 0, 1)[None]})[0]
    mask = np.asarray(mask, dtype=np.float32).reshape(SEG_SIZE, SEG_SIZE)
    mask = mask[top:top + new_h, left:left + new_w]
    return np.clip(cv2.resize(mask, (width, height), interpolation=cv2.INTER_LINEAR), 0.0, 1.0)


EDGE_WIDTH = 0.3  # width of the soft edge around the mask threshold


def refine_alpha(alpha: np.ndarray, mask_threshold: float) -> np.ndarray:
    """Foreground probability -> opacity: a pixel is transparent below `threshold - edge/2` and opaque above
    `threshold + edge/2` (the probability of the anime ISNet is continuous, upstream's refine net is nearly binary,
    so a plain stretch of the upper range leaves dark clothes half transparent)."""
    low = mask_threshold - EDGE_WIDTH / 2
    return np.clip((alpha - low) / EDGE_WIDTH, 0.0, 1.0)


def isolate_instance(alpha: np.ndarray, box) -> np.ndarray:
    """Keep only the connected foreground regions that overlap the detection box (box in crop pixels).

    The crop of one character can contain a part of its neighbour; the neighbour is a different instance and is
    removed here. A region is kept when at least a fifth of its area lies inside the box.
    """
    import cv2
    binary = (alpha > 0.5).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
    if count <= 2:
        return alpha
    x1, y1, x2, y2 = (int(round(v)) for v in box)
    inside = np.zeros_like(binary)
    inside[max(0, y1):max(0, y2), max(0, x1):max(0, x2)] = 1
    keep = np.zeros(count, dtype=bool)
    for label in range(1, count):
        area = stats[label, cv2.CC_STAT_AREA]
        overlap = int(((labels == label) & (inside == 1)).sum())
        keep[label] = area > 0 and overlap * 5 >= area
    if not keep.any():
        return alpha
    allowed = keep[labels]
    # soften the edge: dilate the allowed region a little so the matte's anti-aliased rim survives
    allowed = cv2.dilate(allowed.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
    return np.where(allowed, alpha, 0.0).astype(np.float32)


def ownership(boxes, index: int, crop, padding: float) -> np.ndarray:
    """Boolean mask over `crop` (left, top, right, bottom) of the pixels that belong to character `index`.

    Neighbours that touch each other come out of the matting as one blob. Every pixel inside the (padded) box of at
    least one character is given to the character whose box centre is nearest in units of its own box size, so two
    figures that overlap are split along a line between them instead of leaving a sliver of the other one.
    Pixels outside every padded box belong to nobody.
    """
    left, top, right, bottom = crop
    ys, xs = np.mgrid[top:bottom, left:right].astype(np.float32)
    best = np.full(xs.shape, np.inf, np.float32)
    owner = np.full(xs.shape, -1, np.int32)
    for number, box in enumerate(boxes):
        x1, y1, x2, y2 = box[:4]
        half_w, half_h = max(1.0, (x2 - x1) / 2), max(1.0, (y2 - y1) / 2)
        cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
        dx, dy = np.abs(xs + 0.5 - cx) / half_w, np.abs(ys + 0.5 - cy) / half_h
        inside = (dx <= 1.0 + padding) & (dy <= 1.0 + padding)
        distance = np.where(inside, dx * dx + dy * dy, np.inf)
        better = distance < best
        best = np.where(better, distance, best)
        owner = np.where(better, number, owner)
    return owner == index


def crop_box(box, padding: float, width: int, height: int):
    """Integer (left, top, right, bottom) of a detection box grown by `padding` of its size and clipped."""
    x1, y1, x2, y2 = box[:4]
    bw, bh = x2 - x1, y2 - y1
    left = max(0, int(x1 - padding * bw))
    top = max(0, int(y1 - padding * bh))
    right = min(width, int(x2 + padding * bw + 0.999))
    bottom = min(height, int(y2 + padding * bh + 0.999))
    return left, top, right, bottom


def extract_characters(image, cfg: dict, detect=None, matte=None, progress=None) -> list:
    """RGBA PIL images, one per character found in a PIL image.

    `detect(rgb, threshold)` and `matte(rgb)` default to the pinned models; tests inject fakes.
    """
    from PIL import Image
    rgba = image.convert("RGBA")
    rgb = np.asarray(rgba.convert("RGB"))
    source_alpha = np.asarray(rgba.getchannel("A"), dtype=np.float32) / 255.0
    height, width = rgb.shape[:2]
    boxes = (detect or (lambda a, t: detect_characters(a, t, progress=progress)))(rgb, cfg["instance_threshold"])
    out = []
    for number, box in enumerate(boxes):
        left, top, right, bottom = crop_box(box, cfg["padding"], width, height)
        if right - left < 2 or bottom - top < 2:
            continue
        crop = np.ascontiguousarray(rgb[top:bottom, left:right])
        alpha = (matte or (lambda a: foreground_alpha(a, progress=progress)))(crop)
        alpha = isolate_instance(alpha, (box[0] - left, box[1] - top, box[2] - left, box[3] - top))
        if len(boxes) > 1:
            alpha = alpha * ownership(boxes, number, (left, top, right, bottom), cfg["padding"])
        alpha = refine_alpha(alpha, cfg["mask_threshold"]) * source_alpha[top:bottom, left:right]
        if float((alpha > 0.5).mean()) < 0.02:
            continue  # nothing recognisable was cut out
        pixels = np.dstack([crop, np.round(alpha * 255).astype(np.uint8)])
        out.append(Image.fromarray(pixels, "RGBA"))
    return out


# ------------------------------------------------------------------------------------------------ tagging
_ESCAPE = re.compile(r"([\\()])")
_tags_cache: dict = {}


def _wd_assets(progress=None):
    model = _fetch(WD_REPO, WD_REVISION, WD_MODEL, progress)
    tags = _fetch(WD_REPO, WD_REVISION, WD_TAGS, progress)
    with _lock:
        rows = _tags_cache.get(str(tags))
    if rows is None:
        from .wd_v3_assets import parse_tags
        rows = parse_tags(tags.read_bytes())
        with _lock:
            _tags_cache[str(tags)] = rows
    return model, rows


def format_tag(name: str) -> str:
    """Upstream (wd14-tagger-standalone) tag text: underscores become spaces, parentheses and backslashes escaped."""
    return _ESCAPE.sub(r"\\\1", name.replace("_", " "))


def select_tags(scores, rows, threshold: float, exclude=()) -> list:
    """General and character tags above `threshold`, highest score first; ratings are never part of the caption."""
    excluded = {normalize_tag(t) for t in exclude}
    picked = [(float(score), row.name) for score, row in zip(scores, rows)
              if row.category in (0, 4) and score >= threshold and normalize_tag(row.name) not in excluded]
    picked.sort(key=lambda item: -item[0])
    return [format_tag(name) for _, name in picked]


def normalize_tag(tag: str) -> str:
    """Comparison form of a tag: no escapes, underscores as spaces, lower case, trimmed."""
    return re.sub(r"\\([\\()])", r"\1", tag).replace("_", " ").strip().lower()


def split_tags(text: str) -> list:
    return [t.strip() for t in (text or "").split(",") if t.strip()]


def tag_image(image, threshold: float, exclude=(), progress=None, session=None, rows=None) -> list:
    from .wd_v3_assets import prepare_image
    if session is None or rows is None:
        model, rows = _wd_assets(progress)
        session = _session(model)
    shape = session.get_inputs()[0].shape
    size = shape[1] if isinstance(shape[1], int) else 448
    pixels = prepare_image(image, size)
    scores = session.run(None, {session.get_inputs()[0].name: pixels})[0][0]
    return select_tags(scores, rows, threshold, exclude)


# ------------------------------------------------------------------------------------------------ embeddings
_clip: dict = {}


def _clip_model(progress=None):
    paths = [_fetch(CLIP_REPO, CLIP_REVISION, asset, progress) for asset in CLIP_FILES]
    folder = str(paths[0].parent)
    with _lock:
        cached = _clip.get(folder)
    if cached is not None:
        return cached
    import torch
    from transformers import CLIPModel
    model = CLIPModel.from_pretrained(folder, local_files_only=True, torch_dtype=torch.float32).eval()
    with _lock:
        _clip.clear()
        _clip[folder] = model
    return model


def white_composite(image):
    """RGB on white (upstream convert_rgba_to_rgb); opaque images pass through."""
    from PIL import Image
    if image.mode in ("RGBA", "LA", "P"):
        rgba = image.convert("RGBA")
        background = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
        background.alpha_composite(rgba)
        return background.convert("RGB")
    return image.convert("RGB")


def clip_pixels(image) -> np.ndarray:
    """CLIP preprocessing: bicubic resize of the short side to 224, centre crop, normalise; CHW float32."""
    from PIL import Image
    rgb = white_composite(image)
    width, height = rgb.size
    scale = CLIP_SIZE / min(width, height)
    resized = rgb.resize((max(CLIP_SIZE, round(width * scale)), max(CLIP_SIZE, round(height * scale))), Image.BICUBIC)
    left, top = (resized.width - CLIP_SIZE) // 2, (resized.height - CLIP_SIZE) // 2
    cropped = resized.crop((left, top, left + CLIP_SIZE, top + CLIP_SIZE))
    array = np.asarray(cropped, dtype=np.float32) / 255.0
    return ((array - np.array(CLIP_MEAN, np.float32)) / np.array(CLIP_STD, np.float32)).transpose(2, 0, 1)


def embed_images(images, progress=None, device: str = "cpu") -> np.ndarray:
    """CLIP ViT-B/32 image embeddings [N, 512] float32 (unnormalised, like sentence-transformers' encode)."""
    import torch
    model = _clip_model(progress)
    target = device if device != "cuda" or torch.cuda.is_available() else "cpu"
    batch = torch.from_numpy(np.stack([clip_pixels(img) for img in images]))
    model.to(target)
    try:
        with torch.inference_mode():
            vectors = model.get_image_features(pixel_values=batch.to(target))
        return vectors.float().cpu().numpy().astype(np.float32)
    finally:
        if target != "cpu":
            model.to("cpu")
            torch.cuda.empty_cache()


# ------------------------------------------------------------------------------------------------ dataset files
def read_index(folder: Path):
    """(files, embedding) of a dataset folder; empty when there is no index yet."""
    index, npz = folder / EMBEDDING_JSON, folder / EMBEDDING_NPZ
    if not index.is_file() or not npz.is_file():
        return [], np.zeros((0, 512), np.float32)
    files = json.loads(index.read_text(encoding="utf-8")).get("files", [])
    with np.load(npz) as data:
        vectors = np.asarray(data["embedding"], dtype=np.float32)
    if len(files) != vectors.shape[0]:
        raise ValueError(f"{index} lists {len(files)} files but {npz} holds {vectors.shape[0]} vectors")
    return files, vectors


def write_index(folder: Path, files: list, vectors: np.ndarray) -> None:
    np.savez_compressed(folder / EMBEDDING_NPZ, embedding=np.asarray(vectors, dtype=np.float32))
    (folder / EMBEDDING_JSON).write_text(json.dumps({"files": files}), encoding="utf-8")


def character_files(folder: Path) -> list:
    """Relative POSIX paths of every cut-out PNG of a dataset (sub folders included)."""
    return sorted(p.relative_to(folder).as_posix() for p in folder.rglob("*.png") if p.is_file())


def collect_sources(paths) -> tuple:
    """(images, videos) found in files and folders (folders are walked recursively)."""
    images, videos = [], []
    for raw in paths:
        path = Path(raw)
        if path.is_dir():
            for item in sorted(path.rglob("*")):
                if item.is_file():
                    suffix = item.suffix.lower()
                    (images if suffix in IMAGE_SUFFIXES else videos if suffix in VIDEO_SUFFIXES else []).append(item) \
                        if suffix in IMAGE_SUFFIXES + VIDEO_SUFFIXES else None
        elif path.is_file():
            suffix = path.suffix.lower()
            if suffix in IMAGE_SUFFIXES:
                images.append(path)
            elif suffix in VIDEO_SUFFIXES:
                videos.append(path)
            else:
                raise ValueError(f"{path.name}: not an image or video file")
        else:
            raise FileNotFoundError(str(path))
        if len(images) + len(videos) > MAX_SOURCES:
            raise ValueError(f"more than {MAX_SOURCES} source files")
    return images, videos


def video_frames(path: Path, interval: float, max_frames: int):
    """Yield (frame index, PIL RGB frame) every `interval` seconds of a video (OpenCV)."""
    import cv2
    from PIL import Image
    capture = cv2.VideoCapture(str(path))
    try:
        if not capture.isOpened():
            raise ValueError(f"{path.name}: the video could not be opened")
        fps = capture.get(cv2.CAP_PROP_FPS) or 0.0
        if not fps or fps != fps or fps > 1000:
            fps = 25.0
        step = max(1, int(round(fps * interval)))
        index = produced = 0
        while produced < max_frames:
            if not capture.grab():
                break
            if index % step == 0:
                ok, frame = capture.retrieve()
                if ok:
                    yield index, Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
                    produced += 1
            index += 1
    finally:
        capture.release()


def safe_stem(text: str) -> str:
    return re.sub(r"[^\w.\-]+", "_", text).strip("._") or "image"


def source_name(path: Path) -> str:
    """Upstream names a cut-out after the last two components of the source path."""
    return safe_stem(f"{path.parent.name}_{path.stem}" if path.parent.name else path.stem)


def _write_caption(folder: Path, relative: str, tags: list) -> None:
    (folder / Path(relative).with_suffix(".txt")).write_text(", ".join(tags), encoding="utf-8")


def import_sources(folder, paths, cfg: Optional[dict] = None, check=None, progress=None, log=None,
                   device: str = "cpu", detect=None, matte=None, tagger=None, embedder=None) -> dict:
    """Cut out, tag and embed every character of the given images / videos into the dataset `folder`.

    Returns {"dataset", "sources", "created", "skipped", "errors", "total"}. Existing cut-outs keep their tags and
    embeddings; only new ones are processed, then the index is rewritten for the whole folder.
    """
    cfg = normalize(cfg)
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    check = check or (lambda: None)
    say = log or (lambda text: None)
    images, videos = collect_sources(paths)
    if not images and not videos:
        raise ValueError("no image or video files were given")
    exclude = split_tags(cfg["exclude_tags"])
    created, errors = [], []
    jobs = [("image", p) for p in images] + [("video", p) for p in videos]
    total = len(jobs)

    def handle(image, stem: str, label: str) -> None:
        for number, character in enumerate(extract_characters(image, cfg, detect, matte, say)):
            name = f"{stem}_{number}.png"
            target = folder / name
            counter = 1
            while target.exists():  # never overwrite a cut-out from another source with the same name
                target = folder / f"{stem}-{counter}_{number}.png"
                counter += 1
            character.save(target)
            created.append(target.relative_to(folder).as_posix())
        say(f"{label}: {sum(1 for c in created if c.startswith(stem))} character(s)")

    for done, (kind, path) in enumerate(jobs):
        check()
        if progress:
            progress(done, total)
        try:
            if kind == "image":
                from PIL import Image, ImageOps
                with Image.open(path) as source:
                    image = ImageOps.exif_transpose(source).copy()
                handle(image, source_name(path), path.name)
            else:
                for index, frame in video_frames(path, cfg["frame_interval"], int(cfg["max_frames"])):
                    check()
                    handle(frame, f"{source_name(path)}_{index:09d}", f"{path.name} frame {index}")
        except (OSError, ValueError) as exc:
            if type(exc).__name__ == "EngineError":
                raise
            errors.append(f"{path.name}: {exc}")
            say(f"{path.name}: {exc}")
    if progress:
        progress(total, total)

    files, vectors = read_index(folder)
    known = {entry["path"] for entry in files}
    todo = [name for name in character_files(folder) if name not in known]
    if todo:
        from PIL import Image
        if tagger is None:
            model, rows = _wd_assets(say)
            session = _session(model)
            tagger = lambda image, threshold, skip: tag_image(image, threshold, skip, session=session, rows=rows)
        embedder = embedder or (lambda batch: embed_images(batch, say, device))
        for name in todo:
            check()
            with Image.open(folder / name) as opened:
                character = opened.convert("RGBA")
            _write_caption(folder, name, tagger(character, cfg["tag_threshold"], exclude))
        new_vectors = []
        for start in range(0, len(todo), 16):
            check()
            batch = []
            for name in todo[start:start + 16]:
                with Image.open(folder / name) as opened:
                    batch.append(opened.convert("RGBA"))
            new_vectors.append(np.asarray(embedder(batch), dtype=np.float32))
        vectors = np.concatenate([vectors] + new_vectors, axis=0) if len(vectors) else np.concatenate(new_vectors, axis=0)
        for name in todo:
            with Image.open(folder / name) as opened:
                files.append({"path": name, "width": opened.width, "height": opened.height})
        write_index(folder, files, vectors)
    elif not (folder / EMBEDDING_JSON).exists():
        write_index(folder, files, vectors)
    return {"dataset": str(folder), "sources": total, "created": created, "new": len(todo), "errors": errors,
            "total": len(files)}


def embed_query(path, device: str = "cpu", embedder=None) -> list:
    """Embedding of one query image as a plain list (the Scala side runs the similarity search)."""
    from PIL import Image, ImageOps
    with Image.open(path) as opened:
        image = ImageOps.exif_transpose(opened).copy()
    vector = (embedder or (lambda images: embed_images(images, None, device)))([image])[0]
    return [float(v) for v in vector]


def run(params: dict, ctx) -> dict:
    """Engine entry point: `{action: "import" | "embed", ...}`."""
    from .protocol import EngineError, INVALID_PARAMS, UNSUPPORTED
    action = str(params.get("action", ""))
    device = "cuda" if params.get("device") == "cuda" else "cpu"
    try:
        if action == "import":
            folder = params.get("dataset_dir")
            if not folder:
                raise ValueError("dataset_dir is required")
            sources = params.get("paths") or []
            if not isinstance(sources, list) or not all(isinstance(p, str) for p in sources):
                raise ValueError("paths must be a list of file or folder paths")
            cfg = normalize(params.get("options") if isinstance(params.get("options"), dict) else {})
            ctx.check_interrupt()
            result = import_sources(folder, sources, cfg, check=ctx.check_interrupt,
                                    progress=lambda d, t: ctx.progress(d, t, "chara_searcher"),
                                    log=lambda text: ctx.log("info", text), device=device)
            return result
        if action == "embed":
            path = params.get("image_path")
            if not path:
                raise ValueError("image_path is required")
            ctx.check_interrupt()
            return {"embedding": embed_query(path, device)}
        raise ValueError(f"unknown Character Searcher action '{action}'")
    except ImportError as exc:
        raise EngineError(UNSUPPORTED, f"Character Searcher needs an optional package: {exc}") from exc
    except ModelUnavailable as exc:
        raise EngineError(UNSUPPORTED, str(exc)) from exc
    except (ValueError, FileNotFoundError) as exc:
        raise EngineError(INVALID_PARAMS, f"Character Searcher: {exc}") from exc
    finally:
        if device == "cuda":
            release_models()
