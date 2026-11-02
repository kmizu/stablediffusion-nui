from types import SimpleNamespace

import pytest

torch = pytest.importorskip("torch")
from sdfx import deepcache


class ToyUnet(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.down_blocks = torch.nn.ModuleList([torch.nn.Identity() for _ in range(3)])
        self.up_blocks = torch.nn.ModuleList([torch.nn.Identity() for _ in range(3)])
        self.mid_block = torch.nn.Identity()


def test_cleanup_preserves_existing_instance_forward():
    unet = ToyUnet()
    block = unet.down_blocks[1]
    original = lambda x: x + 7
    block.forward = original
    state = deepcache.DeepCache(unet, interval=2, branch=0)
    state.install()
    state.remove()
    assert block.__dict__["forward"] is original
    assert torch.equal(block(torch.tensor(1)), torch.tensor(8))


def test_partial_install_failure_removes_all_hooks_and_restores_forwards(monkeypatch):
    unet = ToyUnet()
    block = unet.down_blocks[1]
    original = lambda x: x + 7
    block.forward = original
    original_shim = deepcache.DeepCache._shim
    calls = 0

    def fail_after_first(self, module, make):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise RuntimeError("injected install failure")
        return original_shim(self, module, make)

    monkeypatch.setattr(deepcache.DeepCache, "_shim", fail_after_first)
    cleanups = []
    with pytest.raises(RuntimeError, match="injected install failure"):
        deepcache.install(SimpleNamespace(_offload=False), SimpleNamespace(unet=unet),
                          SimpleNamespace(hooks={"deepcache": {}}), None, cleanups,
                          controlnet=False, neutral=False, pass_="base")
    assert not unet._forward_pre_hooks
    assert not unet.up_blocks[-1]._forward_pre_hooks
    assert block.__dict__["forward"] is original
    assert all("forward" not in m.__dict__ for m in unet.modules() if m is not block)
