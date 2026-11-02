"""SD-CN-Animation video-to-video helpers for the engine (request "cn_animation").

The per-frame img2img calls are made by the Scala script (so ControlNet, LoRA, AlwaysOn plugins and every other
generation feature apply as in a normal job); this module does the parts around them that need pixels:

  prepare  decode the source video, resize every frame, write PNGs
  flow     RAFT optical flow between two source frames, warp the previous styled frame, occlusion mask, the init image
  finish   histogram-match a processed frame to the source frame and blend the occlusions back in
  release  free the RAFT model

Behaviour follows the README and code of volotat/SD-CN-Animation (MIT, commit 05e265f); written here from that
description, not copied. Differences are listed in docs/cn-animation-verification.md (RAFT weights are torchvision's,
flow normalisation uses the width for x and the height for y, the occlusion blur uses one sigma for both axes).
"""
from __future__ import annotations

import os
from typing import Optional

from .protocol import EngineError, INVALID_PARAMS, UNSUPPORTED

MAX_FRAMES = 5000
MAX_SIDE = 2048

DEFAULTS = {
    "occlusion_blur": 3.0,
    "trailing": True,
    "flow_multiplier": 5.0,
    "difo_multiplier": 2.0,
    "difs_multiplier": 0.0,
    "blend_alpha": 1.0,
    "raft_iters": 20,
}

_raft = {"key": None, "model": None, "device": None}


def _need_numpy():
    try:
        import numpy as np
        import cv2
        return np, cv2
    except ImportError as e:
        raise EngineError(UNSUPPORTED, f"SD-CN-Animation needs numpy and opencv-python: {e}")


def _number(params: dict, name: str, default, lo, hi, integer: bool = False):
    value = params.get(name, default)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value != value or abs(value) == float("inf"):
        raise EngineError(INVALID_PARAMS, f"cn_animation: {name} must be a number")
    if value < lo or value > hi:
        raise EngineError(INVALID_PARAMS, f"cn_animation: {name} must be in {lo}..{hi} (got {value})")
    if integer and value != int(value):
        raise EngineError(INVALID_PARAMS, f"cn_animation: {name} must be a whole number")
    return int(value) if integer else float(value)


def _path(params: dict, name: str, must_exist: bool = True) -> str:
    value = params.get(name)
    if not isinstance(value, str) or not value:
        raise EngineError(INVALID_PARAMS, f"cn_animation: {name} is required")
    if must_exist and not os.path.isfile(value):
        raise EngineError(INVALID_PARAMS, f"cn_animation: {name} not found: {value}")
    return value


def settings(params: dict) -> dict:
    """Validated occlusion / blend settings."""
    return {
        "occlusion_blur": _number(params, "occlusion_blur", DEFAULTS["occlusion_blur"], 0, 10),
        "trailing": bool(params.get("trailing", DEFAULTS["trailing"])),
        "flow_multiplier": _number(params, "flow_multiplier", DEFAULTS["flow_multiplier"], 0, 10),
        "difo_multiplier": _number(params, "difo_multiplier", DEFAULTS["difo_multiplier"], 0, 10),
        "difs_multiplier": _number(params, "difs_multiplier", DEFAULTS["difs_multiplier"], 0, 10),
        "blend_alpha": _number(params, "blend_alpha", DEFAULTS["blend_alpha"], 0, 1),
        "raft_iters": _number(params, "raft_iters", DEFAULTS["raft_iters"], 1, 64, integer=True),
    }


# --- pixels -----------------------------------------------------------------------------------------------------------

def read_rgb(path: str):
    np, _ = _need_numpy()
    from PIL import Image
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.uint8).copy()


def write_rgb(array, path: str) -> None:
    np, _ = _need_numpy()
    from PIL import Image
    Image.fromarray(np.clip(array, 0, 255).astype(np.uint8)).save(path)


def match_histograms(image, reference):
    """Match the pooled value histogram of `image` to `reference` (skimage's match_histograms with channel_axis=None):
    every source value goes to the reference value of the same cumulative frequency."""
    np, _ = _need_numpy()
    src = np.asarray(image)
    ref = np.asarray(reference)
    s_values, s_idx, s_counts = np.unique(src.ravel(), return_inverse=True, return_counts=True)
    r_values, r_counts = np.unique(ref.ravel(), return_counts=True)
    s_quant = np.cumsum(s_counts).astype(np.float64) / src.size
    r_quant = np.cumsum(r_counts).astype(np.float64) / ref.size
    mapped = np.interp(s_quant, r_quant, r_values)
    return mapped[s_idx].reshape(src.shape)


# --- video ------------------------------------------------------------------------------------------------------------

def prepare(params: dict, ctx) -> dict:
    np, cv2 = _need_numpy()
    video = _path(params, "video")
    width = _number(params, "width", 512, 64, MAX_SIDE, integer=True)
    height = _number(params, "height", 512, 64, MAX_SIDE, integer=True)
    if width % 8 or height % 8:
        raise EngineError(INVALID_PARAMS, "cn_animation: width and height must be multiples of 8")
    limit = _number(params, "max_frames", 0, 0, MAX_FRAMES, integer=True) or MAX_FRAMES
    skip = _number(params, "frame_skip", 0, 0, 100, integer=True)  # keep every (skip+1)-th frame
    out_dir = params.get("output_dir")
    if not isinstance(out_dir, str) or not out_dir:
        raise EngineError(INVALID_PARAMS, "cn_animation: output_dir is required")
    os.makedirs(out_dir, exist_ok=True)
    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        raise EngineError(INVALID_PARAMS, f"cn_animation: cannot open the video: {video}")
    try:
        fps = cap.get(cv2.CAP_PROP_FPS) or 0.0
        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        src_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
        src_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
        paths = []
        index = 0
        while len(paths) < limit:
            ctx.check_interrupt()
            ok, frame = cap.read()
            if not ok or frame is None:
                break
            if index % (skip + 1) == 0:
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                interp = cv2.INTER_AREA if width < rgb.shape[1] or height < rgb.shape[0] else cv2.INTER_LINEAR
                rgb = cv2.resize(rgb, (width, height), interpolation=interp)
                path = os.path.join(out_dir, f"source-{len(paths):05d}.png")
                write_rgb(rgb, path)
                paths.append(path)
            index += 1
    finally:
        cap.release()
    if not paths:
        raise EngineError(INVALID_PARAMS, f"cn_animation: the video has no readable frames: {video}")
    fps = fps if fps and fps == fps and 0 < fps < 1000 else 24.0
    return {"frames": paths, "fps": fps / (skip + 1), "source_fps": fps, "source_frames": total, "source_size": [src_w, src_h],
            "decoded": index}


# --- optical flow -------------------------------------------------------------------------------------------------------

def _build_raft(kind: str, weights: Optional[str]):
    import torch
    from torchvision.models import optical_flow as of
    if kind == "small":
        factory, default = of.raft_small, of.Raft_Small_Weights.DEFAULT
    else:
        factory, default = of.raft_large, of.Raft_Large_Weights.DEFAULT
    if weights:
        model = factory(weights=None)
        state = torch.load(weights, map_location="cpu", weights_only=True)
        model.load_state_dict(state)
    else:
        model = factory(weights=default)
    return model.eval()


def _load_raft(engine, params: dict):
    try:
        import torch
    except ImportError as e:
        raise EngineError(UNSUPPORTED, f"cn_animation: optical flow needs torch and torchvision: {e}")
    kind = str(params.get("raft_model", "large"))
    if kind not in ("large", "small"):
        raise EngineError(INVALID_PARAMS, "cn_animation: raft_model must be large or small")
    weights = params.get("raft_weights") or None
    if weights is not None and not os.path.isfile(str(weights)):
        raise EngineError(INVALID_PARAMS, f"cn_animation: raft_weights not found: {weights}")
    device = getattr(engine, "device", None) or ("cuda" if torch.cuda.is_available() else "cpu")
    key = (kind, weights, str(device))
    if _raft["key"] != key:
        release()
        try:
            model = _build_raft(kind, weights)
        except EngineError:
            raise
        except Exception as e:
            raise EngineError(UNSUPPORTED, f"cn_animation: cannot load the RAFT optical flow model ({kind}): {e}")
        _raft.update(key=key, model=model.to(device), device=device)
    return _raft["model"], _raft["device"]


def release(*_args) -> dict:
    """Drop the cached RAFT model."""
    if _raft["model"] is not None:
        _raft.update(key=None, model=None, device=None)
        import gc
        gc.collect()
        try:
            import torch
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except ImportError:
            pass
    return {"released": True}


def estimate_flow(engine, frame1, frame2, params: dict):
    """RAFT flow frame1 -> frame2 and frame2 -> frame1 in pixels of the frame size, as float32 (h, w, 2) arrays."""
    import torch
    np, cv2 = _need_numpy()
    model, device = _load_raft(engine, params)
    iters = settings(params)["raft_iters"]
    h, w = frame1.shape[:2]
    # RAFT needs feature maps of at least 16 px (128 px images); smaller frames are processed enlarged
    fw, fh = max(128, w // 16 * 16), max(128, h // 16 * 16)
    a = cv2.resize(frame1, (fw, fh)) if (fw, fh) != (w, h) else frame1
    b = cv2.resize(frame2, (fw, fh)) if (fw, fh) != (w, h) else frame2
    def tensor(x):
        return (torch.from_numpy(np.ascontiguousarray(x)).permute(2, 0, 1).float()[None] / 127.5 - 1.0).to(device)
    with torch.no_grad():
        ta, tb = tensor(a), tensor(b)
        forward = model(ta, tb, num_flow_updates=iters)[-1][0].permute(1, 2, 0).float().cpu().numpy()
        backward = model(tb, ta, num_flow_updates=iters)[-1][0].permute(1, 2, 0).float().cpu().numpy()
    def back(flow):
        if (fw, fh) == (w, h):
            return flow.astype(np.float32)
        out = cv2.resize(flow, (w, h))
        out[..., 0] *= w / fw
        out[..., 1] *= h / fh
        return out.astype(np.float32)
    return back(forward), back(backward)


def warp(image, flow):
    """Backward warp: out(x) = image(x + flow(x)), nearest neighbour, reflected borders (align_corners grid)."""
    import torch
    np, _ = _need_numpy()
    h, w = image.shape[:2]
    ys, xs = torch.meshgrid(torch.arange(h), torch.arange(w), indexing="ij")
    grid = torch.stack((xs, ys), dim=0).float() + torch.from_numpy(np.ascontiguousarray(flow)).permute(2, 0, 1)
    grid[0] = 2 * grid[0] / max(1, w - 1) - 1
    grid[1] = 2 * grid[1] / max(1, h - 1) - 1
    source = torch.from_numpy(np.ascontiguousarray(image)).float().permute(2, 0, 1)[None]
    out = torch.nn.functional.grid_sample(source, grid.permute(1, 2, 0)[None], mode="nearest", padding_mode="reflection",
                                          align_corners=True)
    return out[0].permute(1, 2, 0).numpy()


def occlusion(next_flow, prev_flow, prev_frame, cur_frame, prev_styled, cfg: dict, prev_alpha=None):
    """(alpha before clipping, warped previous styled frame). `next_flow` runs previous -> current frame, `prev_flow`
    current -> previous, both in pixels. The mask is the maximum of the forward-backward flow inconsistency (damped
    where the flow is large), the difference of the warped source frame to the current one and, optionally, of the
    warped styled frame; it is blurred and, with trailing, gets half of the previous frame's mask added."""
    np, cv2 = _need_numpy()
    h, w = cur_frame.shape[:2]
    scale = np.array([w, h], dtype=np.float32)
    nxt = next_flow / scale
    prv = prev_flow / scale
    fb = np.linalg.norm(nxt + prv, axis=2)
    still = np.clip(1.0 - np.linalg.norm(prv, axis=-1) * 20.0, 0.0, 1.0)
    flow_mask = (fb * still)[..., None]
    warped = warp(prev_frame, prev_flow)
    warped_styled = warp(prev_styled, prev_flow)
    cur = cur_frame.astype(np.float32)
    difo = (np.abs(warped.astype(np.float32) - cur) / 255.0).max(axis=-1, keepdims=True)
    difs = (np.abs(warped_styled.astype(np.float32) - cur) / 255.0).max(axis=-1, keepdims=True)
    alpha = np.maximum.reduce([flow_mask * cfg["flow_multiplier"] * 10.0, difo * cfg["difo_multiplier"],
                               difs * cfg["difs_multiplier"]])[..., 0]
    if cfg["occlusion_blur"] > 0:
        k = min(w, h) // 15 | 1
        alpha = cv2.GaussianBlur(alpha.astype(np.float32), (k, k), cfg["occlusion_blur"], borderType=cv2.BORDER_REFLECT)
    if cfg["trailing"] and prev_alpha is not None:
        alpha = alpha + prev_alpha * 0.5
    return alpha.astype(np.float32), warped_styled


def flow_request(engine, params: dict, ctx) -> dict:
    np, _ = _need_numpy()
    cfg = settings(params)
    prev_frame = read_rgb(_path(params, "prev_frame"))
    cur_frame = read_rgb(_path(params, "cur_frame"))
    prev_styled = read_rgb(_path(params, "prev_styled"))
    if not (prev_frame.shape == cur_frame.shape == prev_styled.shape):
        raise EngineError(INVALID_PARAMS, "cn_animation: the frames must have the same size")
    prev_alpha = None
    if params.get("prev_alpha"):
        prev_alpha = np.load(_path(params, "prev_alpha"))
        if prev_alpha.shape != cur_frame.shape[:2]:
            raise EngineError(INVALID_PARAMS, "cn_animation: prev_alpha has the wrong size")
    out_dir = params.get("output_dir")
    if not isinstance(out_dir, str) or not out_dir:
        raise EngineError(INVALID_PARAMS, "cn_animation: output_dir is required")
    os.makedirs(out_dir, exist_ok=True)
    stem = str(params.get("name") or "frame")
    ctx.check_interrupt()
    nxt, prv = estimate_flow(engine, prev_frame, cur_frame, params)
    ctx.check_interrupt()
    alpha_raw, warped_styled = occlusion(nxt, prv, prev_frame, cur_frame, prev_styled, cfg, prev_alpha)
    alpha = np.clip(alpha_raw, 0.0, 1.0)
    # The warped styled frame is wrong where the flow has no source to copy from; take the source frame there.
    fixed = cur_frame.astype(np.float32) * alpha[..., None] + warped_styled * (1.0 - alpha[..., None])
    blend = cfg["blend_alpha"]
    init = fixed * (1.0 - blend) + cur_frame.astype(np.float32) * blend
    paths = {
        "init": os.path.join(out_dir, f"{stem}-init.png"),
        "mask": os.path.join(out_dir, f"{stem}-mask.png"),
        "warped": os.path.join(out_dir, f"{stem}-warped.png"),
        "alpha": os.path.join(out_dir, f"{stem}-alpha.npy"),
    }
    write_rgb(init, paths["init"])
    from PIL import Image
    Image.fromarray(np.clip(alpha * 255.0, 0, 255).astype(np.uint8), "L").save(paths["mask"])
    write_rgb(fixed, paths["warped"])
    np.save(paths["alpha"], alpha_raw)
    return {**paths, "mask_mean": float(alpha.mean()), "mask_max": float(alpha.max()),
            "flow_mean": float(np.linalg.norm(nxt, axis=-1).mean())}


def finish_request(params: dict, ctx) -> dict:
    """Histogram-match `image` to `reference`; with `alpha` (+ `warped`) blend: matched * alpha + warped * (1 - alpha)."""
    np, _ = _need_numpy()
    image = read_rgb(_path(params, "image"))
    reference = read_rgb(_path(params, "reference"))
    if image.shape != reference.shape:
        raise EngineError(INVALID_PARAMS, "cn_animation: image and reference must have the same size")
    out = match_histograms(image, reference).astype(np.float32)
    if params.get("alpha"):
        alpha = np.clip(np.load(_path(params, "alpha")), 0.0, 1.0)
        warped = read_rgb(_path(params, "warped")).astype(np.float32)
        if alpha.shape != image.shape[:2] or warped.shape != image.shape:
            raise EngineError(INVALID_PARAMS, "cn_animation: alpha / warped have the wrong size")
        out = out * alpha[..., None] + warped * (1.0 - alpha[..., None])
    path = _path(params, "output", must_exist=False)
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    write_rgb(out, path)
    return {"path": path}


def run(engine, params: dict, ctx) -> dict:
    """Engine request `cn_animation`: params["operation"] is prepare | flow | finish | encode | release."""
    op = params.get("operation")
    if op == "prepare":
        return prepare(params, ctx)
    if op == "flow":
        return flow_request(engine, params, ctx)
    if op == "finish":
        return finish_request(params, ctx)
    if op == "encode":
        from .animatediff import encode_request
        return encode_request({**params, "closed_loop": "N", "loop_number": params.get("loop_number", 0)}, ctx)
    if op == "release":
        return release()
    raise EngineError(INVALID_PARAMS, "cn_animation: operation must be prepare, flow, finish, encode or release")
