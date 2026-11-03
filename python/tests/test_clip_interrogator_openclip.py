"""OpenCLIP routing and availability; no optional package or model is needed for these tests."""
from unittest.mock import patch
import builtins
import os
import sys
import threading
from types import SimpleNamespace

import pytest
from sdfx import clip_interrogator as ci
from sdfx.protocol import EngineError


def test_optional_registry_is_discovered_without_changing_existing_choices():
    registry = [("RN50", "openai"), ("convnext_base_w", "laion2b_s13b_b82k"), ("ViT-L-14", "openai")]
    with patch.object(ci, "openclip_pretrained", return_value=registry):
        choices = ci.models_info()["clip_models"]
    assert choices.count("ViT-L-14/openai") == 1
    assert "RN50/openai" in choices
    assert "convnext_base_w/laion2b_s13b_b82k" in choices


def test_registry_absence_keeps_original_models_available():
    with patch.object(ci, "openclip_pretrained", return_value=[]):
        choices = ci.models_info()["clip_models"]
    assert choices == list(ci.CLIP_MODELS)


def test_registry_metadata_distinguishes_known_tests_from_unverified_models():
    fake = SimpleNamespace(__version__="2.32.0", list_pretrained=lambda: [("RN50", "yfcc15m"), ("Other", "weights")],
                           get_pretrained_cfg=lambda model, _tag: {"hf_hub": "timm/resnet50_clip.yfcc15m/" if model == "RN50" else "test/model/"})
    with patch.dict(sys.modules, {"open_clip": fake}):
        result = ci.models_info()
    assert result["openclip_status"]["state"] == "available"
    assert result["openclip_status"]["registry_entries"] == 2
    assert result["openclip_model_info"]["RN50/yfcc15m"]["verification"] == "previous-engine-local-weights"
    assert result["openclip_model_info"]["Other/weights"]["verification"] == "not-tested"
    assert result["openclip_model_info"]["Other/weights"]["source"] == "test/model/"
    assert result["openclip_model_info"]["RN50/yfcc15m"]["card_license"] == "mit"
    assert result["openclip_model_info"]["Other/weights"]["card_license"] == "unknown"
    assert result["openclip_status"]["card_snapshot_date"] == "2026-11-03"


def test_registry_metadata_reports_incompatible_version():
    with patch.dict(sys.modules, {"open_clip": SimpleNamespace(__version__="9.0.0")}):
        result = ci.models_info()
    assert result["openclip_status"]["state"] == "incompatible-version"
    assert result["openclip_status"]["expected_version"] == "2.32.0"


def test_card_license_is_not_applied_to_a_different_model_repository():
    fake = SimpleNamespace(__version__="2.32.0", list_pretrained=lambda: [("RN50", "yfcc15m")],
                           get_pretrained_cfg=lambda *_: {"hf_hub": "different/repository/"})
    with patch.dict(sys.modules, {"open_clip": fake}):
        info = ci.models_info()["openclip_model_info"]["RN50/yfcc15m"]
    assert info["card_license"] == "unknown"
    assert info["card_revision"] == "unknown"


def test_registry_metadata_reports_broken_registry():
    fake = SimpleNamespace(__version__="2.32.0", list_pretrained=lambda: (_ for _ in ()).throw(RuntimeError("bad registry")))
    with patch.dict(sys.modules, {"open_clip": fake}):
        result = ci.models_info()
    assert result["openclip_status"]["state"] == "load-error"
    assert result["openclip_status"]["registry_entries"] == 0


def test_unrecognized_names_never_enter_openclip_loader():
    with patch.object(ci, "openclip_pretrained", return_value=[("RN50", "openai")]):
        assert ci.clip_backend_choice("RN50/openai") == ("openclip", "RN50", "openai")
        assert ci.clip_backend_choice("ViT-L-14/openai") == ("transformers", ci.CLIP_MODELS["ViT-L-14/openai"], None)
        with pytest.raises(EngineError):
            ci.clip_backend_choice("../not-an-available/model")


def test_missing_dependency_error_is_explicit():
    with patch.object(ci, "openclip_pretrained", return_value=[]):
        with pytest.raises(EngineError, match="open_clip"):
            ci.clip_backend_choice("RN50/openai")


def test_broken_optional_dependency_does_not_hide_existing_models():
    original_import = builtins.__import__
    def fake_import(name, *args, **kwargs):
        if name == "open_clip":
            raise RuntimeError("broken optional binary")
        return original_import(name, *args, **kwargs)
    with patch("builtins.__import__", side_effect=fake_import):
        assert ci.openclip_pretrained() == []
        assert ci.models_info()["clip_models"] == list(ci.CLIP_MODELS)


def test_offline_mode_refuses_openclip_url_downloads():
    with patch.dict(os.environ, {"HF_HUB_OFFLINE": "1"}):
        with pytest.raises(EngineError, match="offline"):
            ci.check_openclip_offline({"url": "https://example.invalid/model.pt", "hf_hub": ""})
        ci.check_openclip_offline({"url": "", "hf_hub": "laion/example/"})
        with pytest.raises(EngineError, match="tokenizer"):
            ci.check_openclip_offline({"hf_hub": "laion/example/"}, "ViT-SO400M-14-SigLIP", {"text_cfg": {}})
        ci.check_openclip_offline({"hf_hub": "laion/example/"}, "ViT-SO400M-14-SigLIP", {"text_cfg": {"hf_tokenizer_name": "cached-model"}})


def test_hub_routing_never_falls_back_to_unchecked_direct_url(monkeypatch):
    monkeypatch.delenv("HF_HUB_OFFLINE", raising=False)
    monkeypatch.delenv("TRANSFORMERS_OFFLINE", raising=False)
    cfg = {"url": "https://example.invalid/model.pt", "hf_hub": "test/model/"}
    ci.require_openclip_hub(cfg, True)
    with pytest.raises(EngineError, match="Hugging Face"):
        ci.require_openclip_hub(cfg, False)
    with pytest.raises(EngineError, match="Hugging Face"):
        ci.require_openclip_hub({"url": cfg["url"]}, True)
    with pytest.raises(EngineError, match="Hugging Face"):
        ci.require_openclip_hub({}, True)
    calls = []
    api = SimpleNamespace(has_hf_hub=lambda: True,
        hf_hub_download=lambda repo, filename=None, cache_dir=None, local_files_only=False: calls.append((repo, filename, cache_dir, local_files_only)) or "safe")
    assert ci.openclip_hub_download(api, cfg, "cache") == "safe"
    assert calls == [("test/model", "open_clip_model.safetensors", "cache", False)]


def test_hub_download_failure_propagates_and_next_attempt_can_succeed():
    calls = []
    def download(repo, filename=None, cache_dir=None, local_files_only=False):
        calls.append(repo)
        if len(calls) == 1: raise OSError("interrupted")
        return "recovered"
    api = SimpleNamespace(has_hf_hub=lambda: True, hf_hub_download=download)
    cfg = {"url": "https://must-not-be-called.invalid/model.pt", "hf_hub": "test/model/"}
    with pytest.raises(OSError, match="interrupted"):
        ci.openclip_hub_download(api, cfg)
    assert ci.openclip_hub_download(api, cfg) == "recovered"
    assert calls == ["test/model", "test/model"]


def test_hub_only_falls_back_from_safetensors_when_file_is_missing():
    from huggingface_hub.utils import EntryNotFoundError
    calls = []
    def download(repo, filename=None, cache_dir=None, local_files_only=False):
        calls.append(filename)
        if filename.endswith(".safetensors"): raise EntryNotFoundError("missing")
        return "binary-weights"
    api = SimpleNamespace(has_hf_hub=lambda: True, hf_hub_download=download)
    assert ci.openclip_hub_download(api, {"hf_hub": "test/model/"}) == "binary-weights"
    assert calls == ["open_clip_model.safetensors", "open_clip_pytorch_model.bin"]


def test_local_cache_miss_falls_back_only_when_offline_is_requested():
    from huggingface_hub.utils import LocalEntryNotFoundError
    calls = []
    def download(repo, filename=None, cache_dir=None, local_files_only=False):
        calls.append((filename, local_files_only))
        if filename.endswith(".safetensors"): raise LocalEntryNotFoundError("cache miss or connection failure")
        return "cached-bin"
    api = SimpleNamespace(has_hf_hub=lambda: True, hf_hub_download=download)
    with patch.dict(os.environ, {"HF_HUB_OFFLINE": "", "TRANSFORMERS_OFFLINE": ""}):
        with pytest.raises(LocalEntryNotFoundError): ci.openclip_hub_download(api, {"hf_hub": "test/model/"})
    assert calls == [("open_clip_model.safetensors", False)]
    calls.clear()
    with patch.dict(os.environ, {"HF_HUB_OFFLINE": "", "TRANSFORMERS_OFFLINE": "1"}):
        assert ci.openclip_hub_download(api, {"hf_hub": "test/model/"}) == "cached-bin"
    assert calls == [("open_clip_model.safetensors", True), ("open_clip_pytorch_model.bin", True)]


def test_hf_cache_interrupted_stream_resumes_without_publishing_partial(tmp_path):
    from huggingface_hub import file_download, hf_hub_download
    payload = b"0123456789abcdef"
    requests = []
    def metadata(**kwargs):
        requests.append(kwargs["filename"])
        return ("https://unit.invalid/weights", "a" * 40, "b" * 40, len(payload), None, None)
    resumed = []
    def stream(url, temp_file, *, resume_size=0, **kwargs):
        resumed.append(resume_size)
        if len(resumed) == 1:
            temp_file.write(payload[:4]); temp_file.flush()
            raise OSError("simulated interrupted stream")
        temp_file.write(payload[resume_size:]); temp_file.flush()
    api = SimpleNamespace(has_hf_hub=lambda: True, hf_hub_download=hf_hub_download)
    cfg = {"hf_hub": "test/isolated-model/"}
    with patch.object(file_download, "_get_metadata_or_catch_error", side_effect=metadata), \
         patch.object(file_download, "http_get", side_effect=stream):
        with pytest.raises(OSError, match="interrupted"):
            ci.openclip_hub_download(api, cfg, str(tmp_path))
        assert not list(tmp_path.rglob("open_clip_model.safetensors")), "partial file was published"
        path = ci.openclip_hub_download(api, cfg, str(tmp_path))
        assert open(path, "rb").read() == payload
        assert ci.openclip_hub_download(api, cfg, str(tmp_path)) == path
    assert resumed == [0, 4]
    assert all(name == "open_clip_model.safetensors" for name in requests)


def test_openclip_backend_encodes_normalizes_and_offloads():
    import torch
    from PIL import Image
    class Model:
        def __init__(self): self.device = "cpu"
        def eval(self): return self
        def to(self, device): self.device = device; return self
        def encode_text(self, tokens): return torch.tensor([[3.0, 4.0]] * len(tokens))
        def encode_image(self, pixels): return torch.tensor([[0.0, 2.0]])
    class Tokenizer:
        context_length = 5
        def __call__(self, texts): return torch.zeros((len(texts), 5), dtype=torch.long)
        def encode(self, text): return [1] * len(text.split())
    model = Model()
    factory = SimpleNamespace(load_state_dict=lambda *a, **kw: None)
    original_loader = factory.load_state_dict
    fake = SimpleNamespace(__version__="2.32.0", factory=factory, pretrained=SimpleNamespace(has_hf_hub=lambda: True), get_pretrained_cfg=lambda *_: {"hf_hub": "test/model/"},
                           get_model_config=lambda *_: {"text_cfg": {}},
                           create_model_and_transforms=lambda *a, **kw: (model, None, lambda image: torch.ones((3, 2, 2))),
                           get_tokenizer=lambda *_: Tokenizer())
    with patch.dict(sys.modules, {"open_clip": fake}), patch.object(ci, "openclip_create_safely", return_value=(model, None, lambda image: torch.ones((3, 2, 2)))):
        backend = ci.OpenClip("fake", "weights", "cpu")
        assert factory.load_state_dict is original_loader
        assert torch.allclose(backend.encode_text(["one"]), torch.tensor([[0.6, 0.8]]))
        assert torch.allclose(backend.encode_image(Image.new("RGB", (2, 2))), torch.tensor([[0.0, 1.0]]))
        assert not backend.at_max_len("one two")
        assert backend.at_max_len("one two three")
        backend.to("cpu")
        assert model.device == "cpu"


def test_openclip_pickle_error_never_retries_without_weights_only():
    with patch("torch.load", side_effect=TypeError("malformed checkpoint")) as load:
        with pytest.raises(TypeError, match="malformed"):
            ci.openclip_safe_state_dict("broken.pt")
        assert load.call_count == 1
        assert load.call_args.kwargs["weights_only"] is True


def test_pretrained_quick_gelu_setting_is_applied():
    fake = SimpleNamespace(__version__="2.32.0", factory=object(), pretrained=SimpleNamespace(has_hf_hub=lambda: True),
                           get_pretrained_cfg=lambda *_: {"hf_hub": "example/", "quick_gelu": True},
                           get_model_config=lambda *_: {"text_cfg": {}},
                           get_tokenizer=lambda *_: SimpleNamespace(context_length=77))
    model = SimpleNamespace(eval=lambda: None)
    with patch.dict(sys.modules, {"open_clip": fake}), \
         patch.object(ci, "openclip_create_safely", return_value=(model, None, None)) as create:
        ci.OpenClip("RN50", "yfcc15m", "cpu")
    assert create.call_args.kwargs["force_quick_gelu"] is True


def test_openclip_state_dict_reads_safe_torch_and_safetensors(tmp_path):
    import torch
    from safetensors.torch import save_file
    pt = tmp_path / "weights.pt"
    st = tmp_path / "weights.safetensors"
    torch.save({"state_dict": {"module.test": torch.tensor([4.0])}}, pt)
    save_file({"module.test": torch.tensor([4.0])}, st)
    assert ci.openclip_safe_state_dict(pt)["test"].item() == 4.0
    assert ci.openclip_safe_state_dict(st)["test"].item() == 4.0


def test_openclip_rejects_pickle_callable_without_executing_it(tmp_path):
    import torch
    marker = tmp_path / "executed"
    class Malicious:
        def __reduce__(self):
            return (os.system, (f'echo bad > "{marker}"',))
    pt = tmp_path / "bad.pt"
    torch.save(Malicious(), pt)
    with pytest.raises(Exception):
        ci.openclip_safe_state_dict(pt)
    assert not marker.exists()


def _factory_build(name, **kwargs):
    return create_model(name)


def _factory_create(name):
    download_pretrained({"hf_hub": "test/model/"})
    return load_checkpoint(name)


def _factory_checkpoint(name):
    return load_state_dict(name)


def test_private_factory_does_not_modify_global_loader_on_failure_or_concurrency():
    factory = SimpleNamespace(create_model_and_transforms=_factory_build, create_model=_factory_create,
                              load_checkpoint=_factory_checkpoint, load_state_dict=lambda *a: "original")
    original = factory.load_state_dict
    barrier = threading.Barrier(2)
    results, failures = [], []
    def safe(name):
        barrier.wait(timeout=5)
        if name == "fail": raise RuntimeError("failed safely")
        return "safe"
    def run(name):
        try: results.append(ci.openclip_create_safely(factory, name, "w", "fp32", "cpu", hub_download=lambda *_a, **_kw: None))
        except Exception as e: failures.append(str(e))
    with patch.object(ci, "openclip_safe_state_dict", side_effect=safe):
        a = threading.Thread(target=run, args=("ok",)); b = threading.Thread(target=run, args=("fail",))
        a.start(); b.start(); a.join(8); b.join(8)
    assert not a.is_alive() and not b.is_alive()
    assert results == ["safe"] and failures == ["failed safely"]
    assert factory.load_state_dict is original


def test_unsupported_openclip_versions_and_changed_factory_are_rejected():
    fake = SimpleNamespace(__version__="9.0.0")
    with patch.dict(sys.modules, {"open_clip": fake}):
        assert ci.openclip_pretrained() == []
        with pytest.raises(EngineError, match="2.32.0"):
            ci.OpenClip("fake", "weights", "cpu")
    with pytest.raises(EngineError, match="factory API"):
        ci.openclip_create_safely(SimpleNamespace(), "x", "y", "fp32", "cpu")
    changed = SimpleNamespace(create_model_and_transforms=lambda *a, **kw: None,
                              create_model=_factory_create, load_checkpoint=_factory_checkpoint)
    with pytest.raises(EngineError, match="call chain"):
        ci.openclip_create_safely(changed, "x", "y", "fp32", "cpu")


def test_openclip_never_reuses_legacy_unfingerprinted_embeddings():
    assert ci.clip_cache_dir("openclip", "C:/cache") is None
    assert ci.clip_cache_dir("transformers", "C:/cache") == "C:/cache"
    with patch.object(ci, "openclip_pretrained", return_value=[("RN50", "openai")]), \
         patch.object(ci, "OpenClip"), patch.object(ci, "Interrogator", return_value=object()) as constructor, \
         patch.object(ci, "_session", {"key": None, "ci": None}), patch.object(ci, "release"):
        ci._get({"clip_model": "RN50/openai", "cache_dir": "C:/cache"}, "cpu", lambda: None, need_caption=False)
        assert constructor.call_args.args[5] is None


def test_failed_openclip_load_does_not_poison_next_request():
    loaded = object()
    with patch.object(ci, "openclip_pretrained", return_value=[("RN50", "openai")]), \
         patch.object(ci, "OpenClip", side_effect=[OSError("download failed"), object()]), \
         patch.object(ci, "Interrogator", return_value=loaded), \
         patch.object(ci, "_session", {"key": None, "ci": None}):
        with pytest.raises(EngineError, match="download failed"):
            ci._get({"clip_model": "RN50/openai"}, "cpu", lambda: None, need_caption=False)
        assert ci._session == {"key": None, "ci": None}
        assert ci._get({"clip_model": "RN50/openai"}, "cpu", lambda: None, need_caption=False) is loaded
        assert ci._session["key"] == ("RN50/openai", "none", "cpu")
