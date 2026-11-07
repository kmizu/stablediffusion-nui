from types import SimpleNamespace as NS
from io import BytesIO
import hashlib
import sys

import pytest
from PIL import Image

from sdfx import adetailer


def test_mediapipe_alias_and_box_conversion(monkeypatch):
    from sdfx import mediapipe_face

    monkeypatch.setattr(mediapipe_face, "model_path", lambda: "face.tflite")
    monkeypatch.setattr(mediapipe_face, "raw_detections", lambda image, path, confidence: [
        NS(bounding_box=NS(origin_x=-3, origin_y=8, width=30, height=20),
           categories=[NS(score=0.9)]),
        NS(bounding_box=NS(origin_x=40, origin_y=45, width=5, height=8),
           categories=[NS(score=0.7)]),
    ])
    assert adetailer.resolve_model("mediapipe_face") == "mediapipe_face"
    result = adetailer.detect(Image.new("RGB", (64, 48)), "mediapipe_face", 0.5, "cpu")
    assert result.boxes == [(0, 8, 27, 28), (40, 45, 45, 48)]
    assert result.confidences == [0.9, 0.7]
    assert result.masks[0].getpixel((5, 10)) == 255


def test_mediapipe_skips_invalid_or_empty_boxes(monkeypatch):
    from sdfx import mediapipe_face
    monkeypatch.setattr(mediapipe_face, "model_path", lambda: "face.tflite")
    monkeypatch.setattr(mediapipe_face, "raw_detections", lambda image, path, confidence: [
        NS(bounding_box=NS(origin_x=80, origin_y=1, width=5, height=5), categories=[NS(score=0.9)]),
        NS(bounding_box=NS(origin_x=1, origin_y=1, width=5, height=5), categories=[]),
    ])
    result = adetailer.detect(Image.new("RGB", (64, 48)), "mediapipe_face", 0.5, "cpu")
    assert result.boxes == [] and result.masks == [] and result.confidences == []


def test_model_cache_checks_hash_and_does_not_publish_bad_download(monkeypatch, tmp_path):
    from sdfx import mediapipe_face as mp
    good = b"verified fixture"
    monkeypatch.setenv("SDFX_MEDIAPIPE_DIR", str(tmp_path))
    monkeypatch.delenv("HF_HUB_OFFLINE", raising=False)
    monkeypatch.delenv("SDFX_MEDIAPIPE_OFFLINE", raising=False)
    monkeypatch.setattr(mp, "MODEL_SIZE", len(good))
    monkeypatch.setattr(mp, "MODEL_SHA256", hashlib.sha256(good).hexdigest())
    monkeypatch.setattr(mp, "urlopen", lambda *a, **k: BytesIO(good))
    path = mp.model_path()
    assert open(path, "rb").read() == good
    monkeypatch.setattr(mp, "urlopen", lambda *a, **k: pytest.fail("valid cache must not download"))
    assert mp.model_path() == path
    with open(path, "wb") as f:
        f.write(b"bad cache")
    monkeypatch.setattr(mp, "urlopen", lambda *a, **k: pytest.fail("bad cache must not be replaced"))
    with pytest.raises(ValueError, match="size/SHA-256"):
        mp.model_path()
    assert open(path, "rb").read() == b"bad cache"
    assert not list(tmp_path.glob(".face-*.tmp"))


def test_offline_does_not_download(monkeypatch, tmp_path):
    from sdfx import mediapipe_face as mp
    monkeypatch.setenv("SDFX_MEDIAPIPE_DIR", str(tmp_path))
    monkeypatch.setenv("HF_HUB_OFFLINE", "1")
    monkeypatch.setattr(mp, "urlopen", lambda *a, **k: pytest.fail("offline must not download"))
    with pytest.raises(FileNotFoundError, match="offline"):
        mp.model_path()


def test_bad_download_is_not_published(monkeypatch, tmp_path):
    from sdfx import mediapipe_face as mp
    monkeypatch.setenv("SDFX_MEDIAPIPE_DIR", str(tmp_path))
    monkeypatch.delenv("HF_HUB_OFFLINE", raising=False)
    monkeypatch.delenv("SDFX_MEDIAPIPE_OFFLINE", raising=False)
    monkeypatch.setattr(mp, "urlopen", lambda *a, **k: BytesIO(b"wrong"))
    with pytest.raises(ValueError, match="size/SHA-256"):
        mp.model_path()
    assert not list(tmp_path.iterdir())


def test_concurrent_cache_creation_is_not_overwritten(monkeypatch, tmp_path):
    from sdfx import mediapipe_face as mp
    good = b"verified fixture"
    monkeypatch.setenv("SDFX_MEDIAPIPE_DIR", str(tmp_path))
    monkeypatch.delenv("HF_HUB_OFFLINE", raising=False)
    monkeypatch.delenv("SDFX_MEDIAPIPE_OFFLINE", raising=False)
    monkeypatch.setattr(mp, "MODEL_SIZE", len(good))
    monkeypatch.setattr(mp, "MODEL_SHA256", hashlib.sha256(good).hexdigest())
    monkeypatch.setattr(mp, "urlopen", lambda *a, **k: BytesIO(good))
    def race(source, destination):
        from pathlib import Path
        Path(destination).write_bytes(b"another writer")
        raise FileExistsError(destination)
    monkeypatch.setattr(mp.os, "link", race)
    with pytest.raises(ValueError, match="size/SHA-256"):
        mp.model_path()
    assert (tmp_path / "blaze_face_short_range_v1.tflite").read_bytes() == b"another writer"
    assert not list(tmp_path.glob(".face-*.tmp"))


def test_detector_reads_verified_bytes_not_a_mutable_model_path(monkeypatch, tmp_path):
    from sdfx import mediapipe_face as mp
    good = b"verified model"
    path = tmp_path / "model.tflite"
    path.write_bytes(good)
    monkeypatch.setattr(mp, "MODEL_SIZE", len(good))
    monkeypatch.setattr(mp, "MODEL_SHA256", hashlib.sha256(good).hexdigest())
    assert mp.verified_bytes(path) == good
    path.write_bytes(b"bad model data")
    with pytest.raises(ValueError, match="size/SHA-256"):
        mp.verified_bytes(path)


def test_missing_mediapipe_package_fails_without_silent_fallback(monkeypatch):
    from sdfx import mediapipe_face as mp
    monkeypatch.setattr(mp, "model_path", lambda: "unused.tflite")
    monkeypatch.setitem(sys.modules, "mediapipe", None)
    with pytest.raises(ImportError):
        adetailer.detect(Image.new("RGB", (64, 48)), "mediapipe_face", 0.5, "cpu")
