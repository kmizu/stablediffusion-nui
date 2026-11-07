import hashlib
import io
import types

import pytest

torch = pytest.importorskip("torch")
from sdfx import aesthetic_gradients as ag


def test_snapshot_digest_is_of_the_exact_bytes_decoded_even_if_file_is_replaced(tmp_path):
    path = tmp_path / "style.pt"
    torch.save(torch.arange(1, 769, dtype=torch.float32), path)
    snapshot, digest = ag.embedding_snapshot(path)
    expected = hashlib.sha256(snapshot).hexdigest()
    torch.save(torch.ones(768), path)
    decoded = ag.decode_embedding_snapshot(snapshot, path.name)
    assert digest == expected
    assert digest != hashlib.sha256(path.read_bytes()).hexdigest()
    assert torch.allclose(decoded, torch.nn.functional.normalize(torch.arange(1, 769, dtype=torch.float32), dim=0))


def test_generation_provenance_never_claims_a_single_hash_when_multiple_embeddings_were_used():
    warnings = []
    result = {"images": [{"infotext_fields": [["Other", "ok"]]}, {}]}
    ag.add_provenance(result, {"a" * 64}, warnings.append)
    assert result["images"][0]["infotext_fields"] == [["Other", "ok"], ["Aesthetic embedding SHA256", "a" * 64]]
    assert result["images"][1]["infotext_fields"] == [["Aesthetic embedding SHA256", "a" * 64]]
    ambiguous = {"images": [{}]}
    ag.add_provenance(ambiguous, {"a" * 64, "b" * 64}, warnings.append)
    assert ambiguous["images"][0]["infotext_fields"] == [["Aesthetic embedding SHA256", "ambiguous"]]
    assert warnings


def test_generation_records_the_consumed_snapshot_even_if_source_changes_later(tmp_path):
    from sdfx.engine import Engine
    path = tmp_path / "style.pt"
    torch.save(torch.arange(1, 769, dtype=torch.float32), path)
    snapshot, digest = ag.embedding_snapshot(path)

    class FakeEngine:
        _generate = Engine._generate

        def _generate_recorded(self, params, ctx, mode):
            ag.decode_embedding_snapshot(snapshot, path.name)
            self._aesthetic_digests.add(digest)
            torch.save(torch.ones(768), path)  # another process replaces it before generation finishes
            return {"images": [{}]}

    result = FakeEngine()._generate({}, types.SimpleNamespace(warnings=[], warn=lambda message: None), "txt2img")
    assert result["images"][0]["infotext_fields"] == [["Aesthetic embedding SHA256", digest]]
    assert digest != hashlib.sha256(path.read_bytes()).hexdigest()


def test_generation_returns_warning_when_multiple_aesthetic_snapshots_are_used():
    from sdfx.engine import Engine

    class FakeEngine:
        _generate = Engine._generate

        def _generate_recorded(self, params, ctx, mode):
            self._aesthetic_digests.update({"a" * 64, "b" * 64})
            return {"images": [{}], "warnings": list(ctx.warnings)}

    warnings = []
    ctx = types.SimpleNamespace(warnings=warnings, warn=warnings.append)
    result = FakeEngine()._generate({}, ctx, "txt2img")
    assert result["warnings"] and "changed during generation" in result["warnings"][0]
    assert result["images"][0]["infotext_fields"] == [["Aesthetic embedding SHA256", "ambiguous"]]
