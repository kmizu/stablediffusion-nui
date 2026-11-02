import os

import numpy as np
import pytest
from PIL import Image

from sdfx import cn_animation as cn
from sdfx.protocol import EngineError

cv2 = pytest.importorskip("cv2")


class Ctx:
    def check_interrupt(self):
        pass

    def warn(self, message):
        pass


def png(path, array):
    Image.fromarray(array.astype(np.uint8)).save(path)
    return str(path)


def scene(w=64, h=48, shift=0):
    """A flat background with a bright square that moves right with `shift`."""
    img = np.full((h, w, 3), 60, np.uint8)
    img[:, :, 1] = np.linspace(40, 120, w, dtype=np.uint8)[None, :]
    img[16:32, 10 + shift:26 + shift] = (230, 200, 40)
    return img


def test_match_histograms_maps_by_cumulative_frequency():
    image = np.array([[0, 0, 10, 10], [10, 20, 20, 20]], np.uint8)
    reference = np.array([[100, 100, 100, 100], [200, 200, 200, 200]], np.uint8)
    out = cn.match_histograms(image, reference)
    assert out.shape == image.shape
    # cumulative frequencies 0.25 / 0.625 / 1.0 interpolate between the reference quantiles 0.5 -> 100 and 1.0 -> 200
    assert np.array_equal(out, np.array([[100, 100, 125, 125], [125, 200, 200, 200]], float))
    # a reference with the same histogram is the identity
    assert np.array_equal(cn.match_histograms(image, image), image)


def test_match_histograms_pools_all_channels():
    rng = np.random.default_rng(0)
    image = rng.integers(0, 256, (16, 16, 3)).astype(np.uint8)
    reference = rng.integers(60, 120, (16, 16, 3)).astype(np.uint8)
    out = cn.match_histograms(image, reference)
    assert out.min() >= reference.min() and out.max() <= reference.max()
    assert abs(out.mean() - reference.mean()) < 3


def test_settings_validation():
    assert cn.settings({})["occlusion_blur"] == 3.0
    for bad in ({"occlusion_blur": 11}, {"flow_multiplier": -1}, {"blend_alpha": 2}, {"raft_iters": 0}, {"raft_iters": 1.5},
                {"difo_multiplier": float("nan")}, {"occlusion_blur": "x"}):
        with pytest.raises(EngineError) as info:
            cn.settings(bad)
        assert info.value.code == "invalid_params"


def test_warp_follows_the_flow_and_reflects_borders():
    img = scene()
    zero = np.zeros(img.shape[:2] + (2,), np.float32)
    assert np.array_equal(cn.warp(img, zero).astype(np.uint8), img)
    flow = zero.copy()
    flow[..., 0] = 4  # out(x) = img(x + 4): the square moves left
    out = cn.warp(img, flow).astype(np.uint8)
    assert np.array_equal(out[:, :40], img[:, 4:44])
    assert np.array_equal(out[:, -1], img[:, 59])  # x = 63 + 4 reflects to 59


def test_occlusion_is_empty_for_a_correct_flow_and_marks_changes():
    prev, cur = scene(shift=0), scene(shift=6)
    cfg = cn.settings({"occlusion_blur": 0})
    h, w = prev.shape[:2]
    nxt = np.zeros((h, w, 2), np.float32)
    nxt[..., 0] = 6
    prv = -nxt
    alpha, warped = cn.occlusion(nxt, prv, prev, cur, prev, cfg)
    inside = (slice(16, 32), slice(18, 32))
    assert alpha[inside].max() < 0.2
    # no flow at all: the moved square is a large difference
    zero = np.zeros_like(nxt)
    alpha0, _ = cn.occlusion(zero, zero, prev, cur, prev, cfg)
    assert alpha0.max() > 0.5
    assert alpha0.shape == (h, w) and alpha0.dtype == np.float32


def test_occlusion_trailing_and_blur():
    prev, cur = scene(shift=0), scene(shift=6)
    zero = np.zeros(prev.shape[:2] + (2,), np.float32)
    plain = cn.occlusion(zero, zero, prev, cur, prev, cn.settings({"occlusion_blur": 0}))[0]
    trailed = cn.occlusion(zero, zero, prev, cur, prev, cn.settings({"occlusion_blur": 0}), prev_alpha=np.ones_like(plain))[0]
    assert np.allclose(trailed, plain + 0.5)
    off = cn.occlusion(zero, zero, prev, cur, prev, cn.settings({"occlusion_blur": 0, "trailing": False}), prev_alpha=np.ones_like(plain))[0]
    assert np.allclose(off, plain)
    blurred = cn.occlusion(zero, zero, prev, cur, prev, cn.settings({"occlusion_blur": 3}))[0]
    assert blurred.std() < plain.std() and abs(blurred.sum() - plain.sum()) / plain.sum() < 0.2


def make_video(path, frames, fps=10):
    h, w = frames[0].shape[:2]
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
    if not writer.isOpened():
        pytest.skip("this OpenCV cannot write mp4v")
    for frame in frames:
        writer.write(cv2.cvtColor(frame, cv2.COLOR_RGB2BGR))
    writer.release()
    return str(path)


def test_prepare_decodes_resizes_and_skips(tmp_path):
    video = make_video(tmp_path / "in.mp4", [scene(shift=s) for s in range(0, 12, 2)])
    out = cn.run(None, {"operation": "prepare", "video": video, "width": 64, "height": 64, "output_dir": str(tmp_path / "w")}, Ctx())
    assert len(out["frames"]) == 6 and abs(out["fps"] - 10) < 0.5
    assert Image.open(out["frames"][0]).size == (64, 64)
    assert out["source_size"] == [64, 48]
    out = cn.run(None, {"operation": "prepare", "video": video, "width": 64, "height": 64, "max_frames": 3, "frame_skip": 1,
                        "output_dir": str(tmp_path / "w2")}, Ctx())
    assert len(out["frames"]) == 3 and abs(out["fps"] - 5) < 0.5
    assert out["decoded"] == 5  # frames 0, 2, 4 are kept, so five were read


def test_prepare_rejects_bad_input(tmp_path):
    for params in ({"video": str(tmp_path / "missing.mp4"), "width": 64, "height": 64},
                   {"video": str(tmp_path / "bad.mp4"), "width": 60, "height": 64},
                   {"video": str(tmp_path / "bad.mp4"), "width": 64, "height": 64}):
        (tmp_path / "bad.mp4").write_bytes(b"not a video")
        with pytest.raises(EngineError) as info:
            cn.run(None, dict(params, operation="prepare", output_dir=str(tmp_path / "w")), Ctx())
        assert info.value.code == "invalid_params"
    with pytest.raises(EngineError):
        cn.run(None, {"operation": "nope"}, Ctx())


def test_flow_request_writes_the_files_and_keeps_alpha_for_trailing(tmp_path, monkeypatch):
    prev, cur = scene(shift=0), scene(shift=6)
    h, w = prev.shape[:2]
    nxt = np.zeros((h, w, 2), np.float32)
    nxt[..., 0] = 6
    monkeypatch.setattr(cn, "estimate_flow", lambda engine, a, b, params: (nxt, -nxt))
    styled = np.clip(prev.astype(int) + 20, 0, 255).astype(np.uint8)
    params = {"operation": "flow", "prev_frame": png(tmp_path / "p.png", prev), "cur_frame": png(tmp_path / "c.png", cur),
              "prev_styled": png(tmp_path / "s.png", styled), "output_dir": str(tmp_path / "o"), "name": "f1", "occlusion_blur": 0}
    out = cn.run(None, params, Ctx())
    for key in ("init", "mask", "warped", "alpha"):
        assert os.path.isfile(out[key]), key
    assert Image.open(out["mask"]).mode == "L" and Image.open(out["init"]).size == (w, h)
    assert out["mask_max"] <= 1.0
    raw = np.load(out["alpha"])
    assert raw.shape == (h, w) and raw.dtype == np.float32
    # the second frame gets half of the first mask on top
    again = cn.run(None, dict(params, name="f2", prev_alpha=out["alpha"]), Ctx())
    assert np.allclose(np.load(again["alpha"]), raw + 0.5 * raw, atol=1e-5)
    # blend_alpha 1 (default): the init is the (occlusion-corrected) warped frame mixed completely with the current frame
    assert np.array_equal(np.asarray(Image.open(out["init"])), cur)
    half = cn.run(None, dict(params, name="f3", blend_alpha=0.0), Ctx())
    assert np.array_equal(np.asarray(Image.open(half["init"])), np.asarray(Image.open(half["warped"])))


def test_flow_request_rejects_mismatched_sizes(tmp_path):
    params = {"operation": "flow", "prev_frame": png(tmp_path / "p.png", scene()), "cur_frame": png(tmp_path / "c.png", scene(w=32)),
              "prev_styled": png(tmp_path / "s.png", scene()), "output_dir": str(tmp_path / "o")}
    with pytest.raises(EngineError) as info:
        cn.run(None, params, Ctx())
    assert info.value.code == "invalid_params"


def test_finish_matches_the_histogram_and_blends_the_occlusions(tmp_path):
    reference = scene()
    processed = np.clip(reference.astype(int) // 2 + 90, 0, 255).astype(np.uint8)  # lower contrast
    out = cn.run(None, {"operation": "finish", "image": png(tmp_path / "i.png", processed), "reference": png(tmp_path / "r.png", reference),
                        "output": str(tmp_path / "o" / "m.png")}, Ctx())
    matched = np.asarray(Image.open(out["path"])).astype(int)
    assert abs(matched.mean() - reference.mean()) < 2 and abs(matched.std() - reference.std()) < 3
    h, w = reference.shape[:2]
    alpha = np.zeros((h, w), np.float32)
    alpha[:, : w // 2] = 1.0
    alpha[0, 0] = 3.0  # values above one are clipped
    np.save(tmp_path / "a.npy", alpha)
    warped = np.full_like(reference, 7)
    blended = cn.run(None, {"operation": "finish", "image": png(tmp_path / "i.png", processed), "reference": png(tmp_path / "r.png", reference),
                            "alpha": str(tmp_path / "a.npy"), "warped": png(tmp_path / "w.png", warped),
                            "output": str(tmp_path / "o" / "b.png")}, Ctx())
    b = np.asarray(Image.open(blended["path"])).astype(int)
    assert np.array_equal(b[:, w // 2:], np.full_like(b[:, w // 2:], 7))
    assert np.abs(b[:, : w // 2] - matched[:, : w // 2]).max() <= 1


def test_encode_writes_a_gif(tmp_path):
    frames = [png(tmp_path / f"{i}.png", scene(shift=i)) for i in range(4)]
    out = cn.run(None, {"operation": "encode", "images": frames, "formats": ["GIF"], "fps": 5, "name": "clip", "output_dir": str(tmp_path / "v")}, Ctx())
    assert out["frames"] == 4 and os.path.getsize(out["outputs"]["GIF"]) > 100
    with Image.open(out["outputs"]["GIF"]) as gif:
        assert gif.n_frames == 4


def test_real_raft_small_on_cpu(tmp_path):
    torch = pytest.importorskip("torch")
    pytest.importorskip("torchvision")
    from torchvision.models import optical_flow as of
    torch.manual_seed(0)
    model = of.raft_small(weights=None).eval()
    cn._raft.update(key=("small", None, "cpu"), model=model, device="cpu")
    try:
        prev, cur = scene(shift=0), scene(shift=6)
        nxt, prv = cn.estimate_flow(None, prev, cur, {"raft_model": "small", "raft_iters": 2})
        assert nxt.shape == (48, 64, 2) and prv.shape == (48, 64, 2)
        assert nxt.dtype == np.float32 and np.isfinite(nxt).all()
        cn.release()
        assert cn._raft["model"] is None
    finally:
        cn.release()


def test_request_is_queued_advertised_and_routed():
    import io
    import json
    from sdfx.base import BaseEngine
    from sdfx.dryrun import FakeEngine
    from sdfx.protocol import Emitter
    from sdfx.server import QUEUED_TYPES, Server
    assert "cn_animation" in QUEUED_TYPES
    assert "cn_animation" in FakeEngine().features()
    stream = io.BytesIO()
    server = Server(BaseEngine(), Emitter(stream))
    server.worker.start()
    try:
        server.handle_line(json.dumps({"id": "r", "type": "cn_animation", "params": {"operation": "release"}}))
        server.handle_line(json.dumps({"id": "bad", "type": "cn_animation", "params": {"operation": "nope"}}))
    finally:
        server.q.put(None)
        server.worker.join(5)
    replies = {x["id"]: x for x in (json.loads(l) for l in stream.getvalue().decode().splitlines()) if x.get("id") in ("r", "bad")}
    assert replies["r"]["result"] == {"released": True}
    assert replies["bad"]["code"] == "invalid_params"


def test_missing_raft_weights_file_is_reported():
    with pytest.raises(EngineError) as info:
        cn._load_raft(None, {"raft_weights": "does-not-exist.pth"})
    assert info.value.code == "invalid_params"
    with pytest.raises(EngineError):
        cn._load_raft(None, {"raft_model": "huge"})
