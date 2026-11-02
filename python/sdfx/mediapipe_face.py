"""MediaPipe Tasks face detector. Model is downloaded separately, never bundled."""
from __future__ import annotations

import hashlib
import os
import tempfile
from pathlib import Path
from urllib.request import urlopen

MODEL_URL = "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite"
MODEL_SIZE = 229746
MODEL_SHA256 = "b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f"
_last_snapshot = None


def verified_bytes(path):
    with open(path, "rb") as stream:
        data = stream.read(MODEL_SIZE + 1)
    if len(data) != MODEL_SIZE or hashlib.sha256(data).hexdigest() != MODEL_SHA256:
        raise ValueError(f"MediaPipe face model failed size/SHA-256 validation: {path}")
    return data


def model_path():
    root = Path(os.environ.get("SDFX_MEDIAPIPE_DIR", Path.home() / ".cache" / "stablediffusion-nui" / "mediapipe"))
    path = root / "blaze_face_short_range_v1.tflite"
    if path.exists():
        verified_bytes(path)
        return str(path)
    if any(os.environ.get(name, "").strip().lower() in ("1", "true", "yes", "on")
           for name in ("HF_HUB_OFFLINE", "SDFX_MEDIAPIPE_OFFLINE")):
        raise FileNotFoundError(f"MediaPipe face model unavailable in offline mode: {path}")
    root.mkdir(parents=True, exist_ok=True)
    temp = None
    try:
        with urlopen(MODEL_URL, timeout=30) as response:
            data = response.read(MODEL_SIZE + 1)
        if len(data) != MODEL_SIZE or hashlib.sha256(data).hexdigest() != MODEL_SHA256:
            raise ValueError("MediaPipe face model failed size/SHA-256 validation")
        with tempfile.NamedTemporaryFile(dir=root, prefix=".face-", suffix=".tmp", delete=False) as f:
            temp = Path(f.name)
            f.write(data)
        try:
            # The temporary and target are in the same directory; link refuses an existing target.
            os.link(temp, path)
        except FileExistsError:
            # Another process won publication. Reuse only if its file has the same pinned bytes.
            verified_bytes(path)
        return str(path)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def raw_detections(image, path, confidence):
    import mediapipe as mp
    import numpy as np
    from .resource_hashes import FileSnapshot
    global _last_snapshot
    _last_snapshot = None
    _last_snapshot = FileSnapshot.capture(path)
    model_bytes = verified_bytes(path)
    options = mp.tasks.vision.FaceDetectorOptions(
        base_options=mp.tasks.BaseOptions(model_asset_buffer=model_bytes),
        min_detection_confidence=confidence)
    with mp.tasks.vision.FaceDetector.create_from_options(options) as detector:
        source = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(image.convert("RGB")))
        return detector.detect(source).detections


def detect(image, confidence):
    from .adetailer import Detection, _box_mask
    boxes, scores = [], []
    for detection in raw_detections(image, model_path(), confidence):
        if not detection.categories:
            continue
        b = detection.bounding_box
        x1, y1 = max(0, b.origin_x), max(0, b.origin_y)
        x2, y2 = min(image.width, b.origin_x + b.width), min(image.height, b.origin_y + b.height)
        if x2 <= x1 or y2 <= y1:
            continue
        boxes.append((x1, y1, x2, y2))
        scores.append(float(detection.categories[0].score))
    return Detection(boxes, _box_mask(image.size, boxes), scores)
