"""ControlNet RGB control-map resize semantics, independently implemented.

Behavior reference: Mikubill/sd-webui-controlnet at 56cec5b2958edf3b1807b7e7b2b1b5186dbd2f81,
including Lvmin Zhang's high-quality edge thinning. Inpaint/CLIP alpha masks are
not handled here and must be rejected by the caller until separately supported.
"""
import numpy as np
import cv2
from PIL import Image


def _edge_thin(image, prune):
    # Rotated hit-or-miss patterns implement iterative contour thinning.
    bases = (
        ((-1, -1, -1), (0, 1, 0), (1, 1, 1)),
        ((0, -1, -1), (1, 1, -1), (0, 1, 0)),
    )
    pruning = (
        ((-1, -1, -1), (-1, 1, -1), (0, 0, -1)),
        ((-1, -1, -1), (-1, 1, -1), (-1, 0, 0)),
    )
    kernels = [np.rot90(np.array(base, dtype=np.int32), turns) for turns in range(4) for base in bases]
    result = image.copy()
    for _ in range(32):
        changed = False
        for kernel in kernels:
            match = cv2.morphologyEx(result, cv2.MORPH_HITMISS, kernel) > 127
            changed |= bool(np.any(match))
            result[match] = 0
        if not changed: break
    if prune:
        for turns in range(4):
            for base in pruning:
                match = cv2.morphologyEx(result, cv2.MORPH_HITMISS, np.rot90(np.array(base, dtype=np.int32), turns)) > 127
                result[match] = 0
    return result


def _edge_nms(image):
    kernels = (
        ((0, 0, 0), (1, 1, 1), (0, 0, 0)),
        ((0, 1, 0), (0, 1, 0), (0, 1, 0)),
        ((1, 0, 0), (0, 1, 0), (0, 0, 1)),
        ((0, 0, 1), (0, 1, 0), (1, 0, 0)),
    )
    result = np.zeros_like(image)
    for kernel in kernels:
        maxima = cv2.dilate(image, np.array(kernel, dtype=np.uint8)) == image
        result[maxima] = image[maxima]
    return result


def _resize_map(image, width, height):
    if image.shape[1] == width and image.shape[0] == height:
        return image.copy()
    area = image.shape[0] * image.shape[1]
    target = width * height
    packed = (image[:, :, 0].astype(np.uint32) << 16) | (image[:, :, 1].astype(np.uint32) << 8) | image[:, :, 2].astype(np.uint32)
    count = len(np.unique(packed))
    binary = count == 2 and image.min() < 16 and image.max() > 240
    one_pixel = False
    if binary:
        opened = cv2.dilate(cv2.erode(image, np.ones((3, 3), np.uint8), iterations=1), np.ones((3, 3), np.uint8), iterations=1)
        one_pixel = np.count_nonzero(opened < image) * 2 > np.count_nonzero(image > 127)
    interpolation = cv2.INTER_NEAREST if 2 < count < 200 else cv2.INTER_AREA if target < area else cv2.INTER_CUBIC
    resized = cv2.resize(image, (width, height), interpolation=interpolation)
    if binary:
        gray = np.mean(resized.astype(np.float32), axis=2).clip(0, 255).astype(np.uint8)
        if one_pixel: gray = _edge_nms(gray)
        _, gray = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        if one_pixel: gray = _edge_thin(gray, target > area)
        resized = np.repeat(gray[:, :, None], 3, axis=2)
    return resized


def resize(image, mode, width, height):
    if type(mode) is not int or mode not in (0, 1, 2) or width <= 0 or height <= 0:
        raise ValueError('invalid source ControlNet resize')
    source = np.asarray(image.convert('RGB'))
    old_h, old_w = source.shape[:2]
    if mode == 0:
        return Image.fromarray(_resize_map(source, width, height))
    scale = min(height / old_h, width / old_w) if mode == 2 else max(height / old_h, width / old_w)
    new_w = max(1, int(np.round(old_w * scale)))
    new_h = max(1, int(np.round(old_h * scale)))
    resized = _resize_map(source, new_w, new_h)
    if mode == 1:
        top, left = max(0, (new_h - height) // 2), max(0, (new_w - width) // 2)
        result = resized[top:top + height, left:left + width]
    else:
        border = np.concatenate((source[0], source[-1], source[:, 0], source[:, -1]), axis=0)
        color = np.median(border, axis=0).astype(source.dtype)
        result = np.empty((height, width, 3), dtype=source.dtype)
        result[:] = color
        top, left = max(0, (height - new_h) // 2), max(0, (width - new_w) // 2)
        result[top:top + new_h, left:left + new_w] = resized
    return Image.fromarray(np.ascontiguousarray(result))
