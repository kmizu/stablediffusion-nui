"""Character Searcher data preparation with injected detector / matte / tagger / embedder (no model files)."""
import json
import threading

import numpy as np
import pytest

PIL = pytest.importorskip("PIL")
cv2 = pytest.importorskip("cv2")
from PIL import Image

from sdfx import chara_searcher as cs
from sdfx.protocol import Emitter, EngineError, JobContext


def test_pins_are_complete_and_consistent():
    for asset in (cs.PERSON_FILE, cs.SEG_FILE, cs.WD_MODEL, cs.WD_TAGS, *cs.CLIP_FILES):
        assert len(asset["sha256"]) == 64 and asset["bytes"] > 0
    assert len(cs.PERSON_REVISION) == len(cs.SEG_REVISION) == len(cs.WD_REVISION) == len(cs.CLIP_REVISION) == 40


def test_normalize_defaults_and_rejections():
    cfg = cs.normalize({})
    assert cfg["mask_threshold"] == 0.6 and cfg["instance_threshold"] == 0.3 and cfg["padding"] == 0.1
    assert cs.normalize({"padding": "0.25", "frame_interval": 2})["padding"] == 0.25
    for bad in ({"mask_threshold": 1.0}, {"instance_threshold": -0.1}, {"tag_threshold": 2}, {"padding": 1.5},
                {"frame_interval": 0}, {"padding": "x"}, {"padding": float("nan")}):
        with pytest.raises(ValueError):
            cs.normalize(bad)


def test_letterbox_centres_and_scales():
    rgb = np.zeros((100, 200, 3), np.uint8)
    tensor, scale, left, top = cs.letterbox(rgb, 640)
    assert tensor.shape == (1, 3, 640, 640) and scale == 3.2 and left == 0 and top == 160
    assert tensor[0, 0, 0, 0] == pytest.approx(114 / 255) and tensor[0, 0, 320, 10] == 0


def test_nms_keeps_best_of_overlapping_boxes():
    boxes = np.array([[0, 0, 100, 100], [5, 5, 100, 100], [200, 0, 300, 100]], np.float32)
    assert cs.nms(boxes, np.array([0.5, 0.9, 0.4], np.float32), 0.7) == [1, 2]


def test_decode_detections_maps_back_to_the_image():
    anchors = np.zeros((5, 3), np.float32)  # rows: centre x, centre y, width, height, score; one column per anchor
    # letterbox of a 200x100 image into 640: scale 3.2, top 160
    anchors[0] = [480, 320, 0.1]
    anchors[1] = [320, 320, 0.1]
    anchors[2] = [96, 64, 4]
    anchors[3] = [192, 192, 4]
    anchors[4] = [0.9, 0.8, 0.99]
    found = cs.decode_detections(anchors[None], 3.2, 0, 160, 200, 100, 0.3)
    assert [round(b[4], 1) for b in found] == [0.9, 0.8]  # the 4 px box is far below the minimum size
    x1, y1, x2, y2, _ = found[0]
    assert (x1, x2) == pytest.approx((135.0, 165.0)) and (y1, y2) == pytest.approx((20.0, 80.0))
    assert cs.decode_detections(anchors[None], 3.2, 0, 160, 200, 100, 0.95) == []


def test_crop_box_pads_and_clips():
    assert cs.crop_box((10, 10, 110, 60), 0.1, 200, 100) == (0, 5, 120, 65)
    assert cs.crop_box((0, 0, 50, 50), 0.5, 60, 60) == (0, 0, 60, 60)


def test_refine_alpha_curve():
    alpha = cs.refine_alpha(np.array([0.0, 0.45, 0.6, 0.75, 1.0], np.float32), 0.6)
    assert alpha.tolist() == pytest.approx([0, 0, 0.5, 1, 1], abs=1e-6)


def test_isolate_instance_drops_the_neighbour():
    alpha = np.zeros((40, 100), np.float32)
    alpha[5:35, 5:35] = 1  # the character (box covers it)
    alpha[5:35, 70:95] = 1  # a neighbour that leaks into the padded crop
    out = cs.isolate_instance(alpha, (0, 0, 50, 40))
    assert out[20, 20] == 1 and out[20, 80] == 0
    assert cs.isolate_instance(alpha[:, :40], (0, 0, 40, 40)).sum() == alpha[:, :40].sum()


def _two_figures():
    image = Image.new("RGB", (200, 100), (250, 250, 250))
    for x0, color in ((20, (200, 30, 30)), (130, (30, 30, 200))):
        for x in range(x0, x0 + 40):
            for y in range(20, 90):
                image.putpixel((x, y), color)
    return image


def _fake_detect(rgb, threshold):
    return [(20, 20, 60, 90, 0.9), (130, 20, 170, 90, 0.8)]


def _fake_matte(crop):
    # everything that is not near white is foreground
    return (crop.astype(np.int32).sum(axis=2) < 600).astype(np.float32)


def test_extract_characters_makes_one_rgba_per_detection():
    out = cs.extract_characters(_two_figures(), cs.normalize({}), _fake_detect, _fake_matte)
    assert len(out) == 2 and all(i.mode == "RGBA" for i in out)
    first = np.asarray(out[0])
    assert first[..., 3].max() == 255 and first[0, 0, 3] == 0  # the corner is background
    assert first[first[..., 3] == 255][:, 0].mean() > 150  # red figure
    assert np.asarray(out[1])[np.asarray(out[1])[..., 3] == 255][:, 2].mean() > 150  # blue figure


def test_extract_characters_skips_empty_cutouts_and_respects_source_alpha():
    nothing = cs.extract_characters(_two_figures(), cs.normalize({}), _fake_detect, lambda crop: np.zeros(crop.shape[:2], np.float32))
    assert nothing == []
    clear = _two_figures().convert("RGBA")
    clear.putalpha(0)
    assert cs.extract_characters(clear, cs.normalize({}), _fake_detect, _fake_matte) == []


def test_tag_selection_formatting_and_exclusion():
    from sdfx.wd_v3_assets import Tag
    rows = [Tag(1, "1girl", 0, 9), Tag(2, "hatsune_miku", 4, 9), Tag(3, "general", 9, 9), Tag(4, "ear_(test)", 0, 9),
            Tag(5, "solo", 0, 9)]
    scores = [0.9, 0.7, 0.99, 0.5, 0.2]
    assert cs.select_tags(scores, rows, 0.35) == ["1girl", "hatsune miku", "ear \\(test\\)"]
    assert cs.select_tags(scores, rows, 0.35, exclude=["1GIRL", "ear (test)"]) == ["hatsune miku"]
    assert cs.normalize_tag("ear \\(test\\)") == "ear (test)" == cs.normalize_tag("Ear_(test)")
    assert cs.split_tags(" a, ,b ,") == ["a", "b"]


def test_clip_pixels_shape_and_white_composite():
    clear = Image.new("RGBA", (30, 60), (255, 0, 0, 0))
    assert cs.white_composite(clear).getpixel((0, 0)) == (255, 255, 255)
    pixels = cs.clip_pixels(Image.new("RGB", (300, 120), (255, 255, 255)))
    assert pixels.shape == (3, 224, 224) and pixels[0, 0, 0] == pytest.approx((1 - cs.CLIP_MEAN[0]) / cs.CLIP_STD[0])


def test_collect_sources(tmp_path):
    (tmp_path / "a").mkdir()
    for name in ("a/one.PNG", "a/two.jpg", "a/clip.mp4", "a/notes.txt", "three.webp"):
        (tmp_path / name).write_bytes(b"x")
    images, videos = cs.collect_sources([tmp_path / "a", tmp_path / "three.webp"])
    assert [p.name for p in images] == ["one.PNG", "two.jpg", "three.webp"] and [p.name for p in videos] == ["clip.mp4"]
    with pytest.raises(ValueError):
        cs.collect_sources([tmp_path / "a" / "notes.txt"])
    with pytest.raises(FileNotFoundError):
        cs.collect_sources([tmp_path / "missing.png"])


def _write_video(path, seconds=3, fps=10):
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (64, 48))
    if not writer.isOpened():
        pytest.skip("OpenCV cannot write mp4 here")
    for n in range(seconds * fps):
        writer.write(np.full((48, 64, 3), n * 5 % 255, np.uint8))
    writer.release()


def test_video_frames_are_sampled_by_time(tmp_path):
    clip = tmp_path / "clip.mp4"
    _write_video(clip, seconds=5, fps=10)
    frames = list(cs.video_frames(clip, 2.0, 100))
    assert [index for index, _ in frames] == [0, 20, 40] and frames[0][1].size == (64, 48)
    assert len(list(cs.video_frames(clip, 2.0, 2))) == 2
    with pytest.raises(ValueError):
        list(cs.video_frames(tmp_path / "missing.mp4", 1.0, 5))


class _Fakes:
    def __init__(self):
        self.tagged, self.embedded = 0, 0

    def tagger(self, image, threshold, exclude):
        self.tagged += 1
        return ["1girl", "solo"] + (["red"] if np.asarray(image)[..., 0].max() > 150 else ["blue"])

    def embedder(self, images):
        self.embedded += len(images)
        return np.stack([np.full(512, float(np.asarray(i)[..., 0].mean() > 100), np.float32) for i in images])


def test_import_sources_writes_the_upstream_layout_and_is_incremental(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _two_figures().save(src / "pair.png")
    folder = tmp_path / "data"
    fakes = _Fakes()
    seen = []
    result = cs.import_sources(folder, [src], {"tag_threshold": 0.5}, progress=lambda d, t: seen.append((d, t)),
                               detect=_fake_detect, matte=_fake_matte, tagger=fakes.tagger, embedder=fakes.embedder)
    assert result["created"] == ["src_pair_0.png", "src_pair_1.png"] and result["total"] == 2 and result["errors"] == []
    assert seen[-1] == (1, 1)
    assert (folder / "src_pair_0.txt").read_text() == "1girl, solo, red"
    index = json.loads((folder / "embedding.json").read_text())
    assert [e["path"] for e in index["files"]] == ["src_pair_0.png", "src_pair_1.png"]
    assert index["files"][0]["width"] > 30 and index["files"][0]["height"] > 60
    with np.load(folder / "embedding.npz") as data:
        assert data["embedding"].shape == (2, 512) and data["embedding"].dtype == np.float32
    # a second import of another file keeps the first rows and only processes the new cut-outs
    other = tmp_path / "other"
    other.mkdir()
    _two_figures().save(other / "pair.png")
    again = cs.import_sources(folder, [other], {}, detect=_fake_detect, matte=_fake_matte, tagger=fakes.tagger,
                              embedder=fakes.embedder)
    assert again["new"] == 2 and again["total"] == 4 and fakes.tagged == 4 and fakes.embedded == 4
    files, vectors = cs.read_index(folder)
    assert len(files) == 4 and vectors.shape == (4, 512)
    assert sorted(p.name for p in folder.glob("*.png")) == ["other_pair_0.png", "other_pair_1.png", "src_pair_0.png", "src_pair_1.png"]


def test_same_source_name_never_overwrites(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _two_figures().save(src / "pair.png")
    folder = tmp_path / "data"
    fakes = _Fakes()
    kwargs = dict(detect=_fake_detect, matte=_fake_matte, tagger=fakes.tagger, embedder=fakes.embedder)
    cs.import_sources(folder, [src], {}, **kwargs)
    cs.import_sources(folder, [src], {}, **kwargs)
    assert len(list(folder.glob("*.png"))) == 4 and len(cs.read_index(folder)[0]) == 4


def test_import_reports_unreadable_files_and_stops_on_interrupt(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    (src / "broken.png").write_bytes(b"not a png")
    _two_figures().save(src / "pair.png")
    fakes = _Fakes()
    folder = tmp_path / "data"
    result = cs.import_sources(folder, [src], {}, detect=_fake_detect, matte=_fake_matte, tagger=fakes.tagger,
                               embedder=fakes.embedder)
    assert result["total"] == 2 and len(result["errors"]) == 1 and result["errors"][0].startswith("broken.png")

    def stop():
        raise EngineError("interrupted", "interrupted")
    with pytest.raises(EngineError):
        cs.import_sources(tmp_path / "data2", [src], {}, check=stop, detect=_fake_detect, matte=_fake_matte,
                          tagger=fakes.tagger, embedder=fakes.embedder)


def test_import_with_video_names_frames(tmp_path):
    clip = tmp_path / "scene.mp4"
    _write_video(clip, seconds=3, fps=10)
    fakes = _Fakes()
    folder = tmp_path / "data"
    result = cs.import_sources(folder, [clip], {"frame_interval": 1.0, "instance_threshold": 0.2},
                               detect=lambda rgb, t: [(5, 5, 50, 40, 0.9)], matte=lambda crop: np.ones(crop.shape[:2], np.float32),
                               tagger=fakes.tagger, embedder=fakes.embedder)
    assert len(result["created"]) == 3
    assert all("_0000000" in name for name in result["created"])


def test_run_validates_and_reports_missing_files(tmp_path):
    context = JobContext(Emitter(open(tmp_path / "log", "wb")), "r1", threading.Event())
    for params in ({"action": "nope"}, {"action": "import"}, {"action": "import", "dataset_dir": str(tmp_path / "d"), "paths": [str(tmp_path / "gone.png")]},
                   {"action": "embed"}, {"action": "import", "dataset_dir": str(tmp_path / "d"), "paths": [], "options": {"padding": 9}}):
        with pytest.raises(EngineError) as raised:
            cs.run(params, context)
        assert raised.value.code == "invalid_params"


def test_the_request_type_reaches_the_dry_run_engine_over_pipes(tmp_path):
    from test_server_dryrun import Engine
    engine = Engine()
    try:
        hello = engine.next()
        assert "chara_searcher" in hello["features"]
        result, _ = engine.call("chara_searcher", {"action": "embed"})
        assert result["type"] == "error" and result["code"] == "invalid_params"
        (tmp_path / "src").mkdir()
        result, _ = engine.call("chara_searcher", {"action": "import", "dataset_dir": str(tmp_path / "set"),
                                                   "paths": [str(tmp_path / "src")]})
        assert result["type"] == "error" and "no image or video" in result["message"]
    finally:
        engine.p.kill()

def test_touching_characters_are_split_between_their_boxes():
    image = Image.new("RGB", (200, 100), (250, 250, 250))
    for x in range(20, 100):
        for y in range(20, 90):
            image.putpixel((x, y), (200, 30, 30))
    for x in range(100, 170):
        for y in range(20, 90):
            image.putpixel((x, y), (30, 30, 200))
    boxes = [(20, 20, 105, 90, 0.9), (95, 20, 170, 90, 0.8)]
    out = cs.extract_characters(image, cs.normalize({}), lambda rgb, t: boxes, _fake_matte)
    assert len(out) == 2
    first, second = (np.asarray(i) for i in out)
    opaque = lambda a: a[..., 3] > 128
    assert opaque(first).any() and opaque(second).any()
    assert not (opaque(first) & (first[..., 2] > first[..., 0])).any(), "the blue neighbour leaked into the red cut-out"
    assert not (opaque(second) & (second[..., 0] > second[..., 2])).any(), "the red neighbour leaked into the blue cut-out"


def test_ownership_gives_each_pixel_to_the_nearest_centre_and_nothing_outside_the_boxes():
    boxes = [(0, 0, 40, 40, 1.0), (30, 0, 70, 40, 1.0)]
    left = cs.ownership(boxes, 0, (0, 0, 100, 40), 0.0)
    right = cs.ownership(boxes, 1, (0, 0, 100, 40), 0.0)
    assert not (left & right).any()
    assert left[20, 10] and not left[20, 60] and right[20, 60] and not right[20, 10]
    assert left[20, 33] and right[20, 36]  # the overlap is split near x = 35
    assert not (left | right)[20, 90]  # outside every box