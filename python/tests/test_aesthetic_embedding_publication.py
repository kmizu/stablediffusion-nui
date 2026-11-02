from pathlib import Path
import io
import threading
import types

import pytest

torch = pytest.importorskip("torch")

from sdfx import aesthetic_gradients as ag


def _fake_images(monkeypatch, tmp_path):
    image = tmp_path / "reference.png"
    image.write_bytes(b"fixture")
    monkeypatch.setattr(ag, "embed_images", lambda *args, **kwargs: torch.ones((1, 768)))
    return [str(image)]


def test_cancel_after_embedding_preserves_existing_output(tmp_path, monkeypatch):
    images = _fake_images(monkeypatch, tmp_path)
    output = tmp_path / "style.pt"
    output.write_bytes(b"old embedding")

    def cancelled():
        raise RuntimeError("cancelled")

    with pytest.raises(RuntimeError, match="cancelled"):
        ag.create_embedding(images, output, check_interrupt=cancelled, overwrite=True)
    assert output.read_bytes() == b"old embedding"


def test_no_overwrite_even_if_destination_appears_during_embedding(tmp_path, monkeypatch):
    images = _fake_images(monkeypatch, tmp_path)
    output = tmp_path / "style.pt"

    def concurrent_create(*args, **kwargs):
        output.write_bytes(b"other writer")
        return torch.ones((1, 768))

    monkeypatch.setattr(ag, "embed_images", concurrent_create)
    with pytest.raises(FileExistsError):
        ag.create_embedding(images, output, overwrite=False)
    assert output.read_bytes() == b"other writer"


def test_save_failure_keeps_existing_output_and_removes_temporary_file(tmp_path, monkeypatch):
    images = _fake_images(monkeypatch, tmp_path)
    output = tmp_path / "style.pt"
    output.write_bytes(b"old embedding")

    def fail_save(value, path):
        Path(path).write_bytes(b"incomplete")
        raise OSError("disk failure")

    monkeypatch.setattr(torch, "save", fail_save)
    with pytest.raises(OSError, match="disk failure"):
        ag.create_embedding(images, output, overwrite=True)
    assert output.read_bytes() == b"old embedding"
    assert sorted(p.name for p in tmp_path.iterdir()) == ["reference.png", "style.pt"]


def test_successful_creation_and_publish_cancellation(tmp_path, monkeypatch):
    images = _fake_images(monkeypatch, tmp_path)
    output = tmp_path / "style.pt"
    result = ag.create_embedding(images, output)
    assert result["count"] == 1
    assert torch.load(output, weights_only=True).shape == (1, 768)
    original = output.read_bytes()

    def cancel_publication(operation):
        raise RuntimeError("publication cancelled")

    with pytest.raises(RuntimeError, match="publication cancelled"):
        ag.create_embedding(images, output, overwrite=True, publish=cancel_publication)
    assert output.read_bytes() == original
    assert sorted(p.name for p in tmp_path.iterdir()) == ["reference.png", "style.pt"]


def test_engine_request_uses_publication_ownership_and_preserves_no_overwrite(tmp_path, monkeypatch):
    from sdfx.base import BaseEngine
    from sdfx.protocol import Emitter, EngineError, JobContext

    images = _fake_images(monkeypatch, tmp_path)
    ctx = JobContext(Emitter(io.BytesIO()), "test", publication_lock=threading.Lock())
    engine = types.SimpleNamespace(dry_run=False, cuda=False)
    params = {"op": "create", "name": "style", "images": images, "output_dir": str(tmp_path)}
    result = BaseEngine.aesthetic_embedding(engine, params, ctx)
    assert result["count"] == 1
    assert ctx.publication_state == "committed"
    original = (tmp_path / "style.pt").read_bytes()
    second = JobContext(Emitter(io.BytesIO()), "second", publication_lock=threading.Lock())
    with pytest.raises(EngineError, match="exists"):
        BaseEngine.aesthetic_embedding(engine, params, second)
    assert (tmp_path / "style.pt").read_bytes() == original


def test_engine_rejects_non_boolean_overwrite(tmp_path, monkeypatch):
    from sdfx.base import BaseEngine
    from sdfx.protocol import Emitter, EngineError, JobContext

    images = _fake_images(monkeypatch, tmp_path)
    output = tmp_path / "style.pt"
    output.write_bytes(b"old embedding")
    ctx = JobContext(Emitter(io.BytesIO()), "test", publication_lock=threading.Lock())
    with pytest.raises(EngineError, match="overwrite"):
        BaseEngine.aesthetic_embedding(types.SimpleNamespace(dry_run=False, cuda=False),
            {"op": "create", "name": "style", "images": images, "output_dir": str(tmp_path),
             "overwrite": "false"}, ctx)
    assert output.read_bytes() == b"old embedding"
