"""CLIP Interrogator algorithm, caches, batch writers and engine entry point, driven by deterministic fakes."""
import csv
import hashlib
import os
import pathlib
import tempfile
import unittest

import pytest

torch = pytest.importorskip("torch")
PIL = pytest.importorskip("PIL")
from PIL import Image  # noqa: E402

from sdfx import clip_interrogator as ci  # noqa: E402
from sdfx.protocol import EngineError, INTERRUPTED  # noqa: E402

DIM = 32
COLORS = {"red": (255, 0, 0), "green": (0, 255, 0), "blue": (0, 0, 255)}


def word_vec(word):
    g = torch.Generator().manual_seed(int(hashlib.sha256(word.encode()).hexdigest()[:8], 16))
    return torch.randn(DIM, generator=g)


class FakeClip:
    """Text = normalised sum of word vectors; an image is the word vector of its dominant colour."""

    def __init__(self):
        self.text_calls = 0
        self.moved = []

    def encode_text(self, texts):
        self.text_calls += 1
        out = []
        for t in texts:
            v = sum((word_vec(w) for w in t.lower().replace(",", " ").split()), torch.zeros(DIM))
            out.append(v)
        f = torch.stack(out)
        return f / f.norm(dim=-1, keepdim=True)

    def encode_image(self, image):
        px = image.convert("RGB").getpixel((0, 0))
        name = max(COLORS, key=lambda c: sum(a * b for a, b in zip(COLORS[c], px)))
        f = word_vec(name).unsqueeze(0)
        return f / f.norm(dim=-1, keepdim=True)

    def at_max_len(self, text):
        return len(text.replace(",", " ").split()) >= 12

    def to(self, device):
        self.moved.append(device)


class FakeCaptioner:
    def __init__(self):
        self.calls = 0
        self.moved = []

    def caption(self, image):
        self.calls += 1
        px = image.convert("RGB").getpixel((0, 0))
        return "a %s square" % ("red", "green", "blue")[max(range(3), key=px.__getitem__)]

    def to(self, device):
        self.moved.append(device)


LISTS = {
    "artists.txt": ["alpha", "bravo red", "charlie green"],
    "flavors.txt": ["red", "green", "blue", "red glow", "green glow", "blue glow", "sharp", "soft", "calm", "wild", "dark", "bright"],
    "mediums.txt": ["a painting", "a photo of red"],
    "movements.txt": ["cubism", "red realism"],
    "negative.txt": ["ugly", "blurry"],
}


def make_data(root):
    d = pathlib.Path(root) / "data"
    d.mkdir()
    for name, lines in LISTS.items():
        (d / name).write_text("\n".join(lines) + "\n", encoding="utf-8")
    return d


def build(root, cache=None, captioner=True, **kw):
    return ci.Interrogator(FakeClip(), torch, "cpu", "ViT-L-14/openai", FakeCaptioner() if captioner else None,
                           cache_dir=cache, data_dir=make_data(root), chunk_size=4, flavor_intermediate_count=8, **kw)


def image(color="red", path=None):
    im = Image.new("RGB", (16, 16), COLORS[color])
    if path:
        im.save(path)
    return im


class Ctx:
    def __init__(self, interrupt_after=None):
        self.events, self.n = [], 0
        self.interrupt_after = interrupt_after

    def check_interrupt(self):
        self.n += 1
        if self.interrupt_after is not None and self.n > self.interrupt_after:
            raise EngineError(INTERRUPTED, "interrupted")

    def progress(self, step, total, pass_="base", **kw):
        self.events.append((step, total, pass_))


class Engine:
    device = "cpu"


class AlgorithmTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(ci.release)
        self.ci = build(self.tmp.name, cache=os.path.join(self.tmp.name, "cache"))

    def test_label_lists_follow_upstream_construction(self):
        self.assertEqual(self.ci.artists.labels, ["by alpha", "by bravo red", "by charlie green",
                                                  "inspired by alpha", "inspired by bravo red", "inspired by charlie green"])
        self.assertEqual(len(self.ci.trendings.labels), 18 * 4)
        self.assertIn("trending on Artstation", self.ci.trendings.labels)
        self.assertIn("pixiv contest winner", self.ci.trendings.labels)

    def test_bundled_lists_are_the_upstream_files(self):
        for name, size in (("artists.txt", 82438), ("flavors.txt", 1804274), ("mediums.txt", 1606), ("movements.txt", 2683), ("negative.txt", 418)):
            self.assertEqual((ci.DATA_DIR / name).stat().st_size, size, name)
            self.assertGreater(len(ci.load_list(name)), 30, name)

    def test_rank_matches_the_dominant_concept(self):
        f = self.ci.image_to_features(image("red"))
        self.assertEqual(self.ci.flavors.rank(f, 1), ["red"])
        self.assertEqual(self.ci.flavors.rank(image_features := self.ci.image_to_features(image("green")), 1), ["green"])
        top = self.ci.flavors.rank(f, 3)
        self.assertEqual(top[0], "red")
        self.assertEqual(top[1], "red glow")
        far = self.ci.flavors.rank(f, 1, reverse=True)[0]
        self.assertNotIn("red", far)

    def test_rank_clamps_to_label_count(self):
        f = self.ci.image_to_features(image("red"))
        self.assertEqual(len(self.ci.mediums.rank(f, 50)), 2)

    def test_modes(self):
        im = image("red")
        classic = self.ci.prompt(im, "classic")
        self.assertTrue(classic.startswith("a red square, a photo of red "), classic)
        self.assertIn("bravo red", classic)
        fast = self.ci.prompt(im, "fast", max_flavors=4)
        self.assertTrue(fast.startswith("a red square, "), fast)
        self.assertGreaterEqual(fast.count(", "), 1)
        self.assertEqual(self.ci.prompt(im, "caption"), "a red square")
        neg = self.ci.prompt(im, "negative", max_flavors=5)
        self.assertNotIn("red", neg.lower().split(", ")[0])
        best = self.ci.prompt(im, "best")
        self.assertTrue(best.startswith("a red square"), best)
        with self.assertRaises(EngineError):
            self.ci.prompt(im, "bogus")

    def test_best_never_scores_below_the_caption(self):
        im = image("blue")
        f = self.ci.image_to_features(im)
        best = self.ci.prompt(im, "best")
        self.assertGreaterEqual(self.ci.similarity(f, best), self.ci.similarity(f, "a blue square") - 1e-6)

    def test_prompt_is_truncated_at_context_length(self):
        out = self.ci.prompt(image("red"), "fast", max_flavors=30)
        self.assertLess(len(out.replace(",", " ").split()), 12 + 2)

    def test_classic_skips_medium_when_caption_starts_with_it(self):
        class Cap(FakeCaptioner):
            def caption(self, im):
                return "a photo of red fox"
        self.ci.captioner = Cap()
        out = self.ci.prompt(image("red"), "classic")
        self.assertTrue(out.startswith("a photo of red fox "), out)
        self.assertNotIn(", a photo of red", out)

    def test_analyze_has_five_ranked_tables(self):
        a = self.ci.analyze(image("green"))
        self.assertEqual(list(a), ["medium", "artist", "movement", "trending", "flavor"])
        self.assertEqual(len(a["medium"]), 2)
        flavors = list(a["flavor"].items())
        self.assertEqual(flavors[0][0], "green")
        self.assertGreaterEqual(flavors[0][1], flavors[1][1])
        self.assertTrue(all(-1.001 <= v <= 1.001 for t in a.values() for v in t.values()))

    def test_no_caption_model_is_a_clear_error(self):
        bare = build(tempfile.mkdtemp(dir=self.tmp.name), captioner=False)
        with self.assertRaises(EngineError):
            bare.prompt(image("red"), "fast")
        self.assertTrue(bare.prompt(image("red"), "negative"))

    def test_interrupt_stops_the_chain(self):
        calls = {"n": 0}

        def check():
            calls["n"] += 1
            if calls["n"] > 3:
                raise EngineError(INTERRUPTED, "interrupted")
        self.ci.check = check
        with self.assertRaises(EngineError) as r:
            self.ci.prompt(image("red"), "negative", max_flavors=10)
        self.assertEqual(r.exception.code, INTERRUPTED)


class CacheTests(unittest.TestCase):
    def test_embeddings_are_cached_in_the_upstream_layout_and_reused(self):
        with tempfile.TemporaryDirectory() as root:
            cache = os.path.join(root, "cache")
            first = build(root, cache)
            files = sorted(os.listdir(cache))
            self.assertEqual(files, [f"ViT-L-14_openai_{n}.safetensors" for n in
                                     ("artists", "flavors", "mediums", "movements", "negative", "trendings")])
            from safetensors.numpy import load_file
            t = load_file(os.path.join(cache, files[1]))
            self.assertEqual(t["embeds"].shape, (len(LISTS["flavors.txt"]), DIM))
            self.assertEqual(str(t["embeds"].dtype), "float16")
            self.assertEqual(t["hash"].dtype.name, "int8")
            second = ci.Interrogator(FakeClip(), torch, "cpu", "ViT-L-14/openai", None, cache_dir=cache,
                                     data_dir=pathlib.Path(root) / "data", chunk_size=4)
            self.assertEqual(second.backend.text_calls, 0, "all six tables came from the cache")
            f = first.image_to_features(image("red"))
            self.assertEqual(first.flavors.rank(f, 4), second.flavors.rank(f, 4))

    def test_changed_label_list_invalidates_the_cache(self):
        with tempfile.TemporaryDirectory() as root:
            cache = os.path.join(root, "cache")
            build(root, cache)
            (pathlib.Path(root) / "data" / "flavors.txt").write_text("only one\n", encoding="utf-8")
            again = ci.Interrogator(FakeClip(), torch, "cpu", "ViT-L-14/openai", None, cache_dir=cache,
                                    data_dir=pathlib.Path(root) / "data", chunk_size=4)
            self.assertEqual(again.flavors.labels, ["only one"])
            self.assertGreaterEqual(again.backend.text_calls, 1)

    def test_corrupt_cache_is_recomputed(self):
        with tempfile.TemporaryDirectory() as root:
            cache = os.path.join(root, "cache")
            build(root, cache)
            (pathlib.Path(cache) / "ViT-L-14_openai_flavors.safetensors").write_bytes(b"junk")
            again = ci.Interrogator(FakeClip(), torch, "cpu", "ViT-L-14/openai", None, cache_dir=cache,
                                    data_dir=pathlib.Path(root) / "data", chunk_size=4)
            self.assertEqual(len(again.flavors.labels), len(LISTS["flavors.txt"]))
            self.assertEqual(again.flavors.embeds.shape[0], len(LISTS["flavors.txt"]))

    def test_cache_name_separates_models(self):
        with tempfile.TemporaryDirectory() as root:
            cache = os.path.join(root, "cache")
            ci.Interrogator(FakeClip(), torch, "cpu", "ViT-bigG-14/laion2b_s39b_b160k", None, cache_dir=cache,
                            data_dir=make_data(root), chunk_size=4)
            self.assertIn("ViT-bigG-14_laion2b_s39b_b160k_flavors.safetensors", os.listdir(cache))


class RunTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(ci.release)
        self.root = pathlib.Path(self.tmp.name)
        self.data = make_data(self.tmp.name)
        self.made = []

        def factory(clip_name, caption_name, device, cache_dir, check):
            self.made.append((clip_name, caption_name))
            return ci.Interrogator(FakeClip(), torch, "cpu", clip_name, FakeCaptioner() if caption_name != "none" else None,
                                   cache_dir, data_dir=self.data, chunk_size=4, flavor_intermediate_count=8, check=check)
        self.factory = factory

    def run_op(self, **params):
        return ci.run(Engine(), params, Ctx(), factory=self.factory)

    def test_models_lists_upstream_choices(self):
        m = self.run_op(operation="models")
        self.assertEqual(m["default_clip_model"], "ViT-L-14/openai")
        self.assertIn("ViT-bigG-14/laion2b_s39b_b160k", m["clip_models"])
        self.assertEqual(m["caption_models"], ["blip-base", "blip-large", "blip2-2.7b", "blip2-flan-t5-xl", "git-large-coco"])
        self.assertEqual(m["modes"], ["best", "fast", "classic", "negative", "caption"])

    def test_prompt_returns_prompt_caption_and_similarity(self):
        p = self.root / "a.png"
        image("red", p)
        r = self.run_op(operation="prompt", image_path=str(p), mode="fast", max_flavors=3)
        self.assertTrue(r["prompt"].startswith("a red square, "))
        self.assertEqual(r["caption"], "a red square")
        self.assertEqual(r["mode"], "fast")
        self.assertGreater(r["similarity"], 0.3)
        self.assertEqual(self.made, [("ViT-L-14/openai", "blip-large")])

    def test_models_are_released_unless_kept(self):
        p = self.root / "a.png"
        image("red", p)
        self.run_op(operation="prompt", image_path=str(p), mode="fast")
        self.assertIsNone(ci._session["ci"])
        self.run_op(operation="prompt", image_path=str(p), mode="fast", keep_loaded=True)
        self.assertIsNotNone(ci._session["ci"])
        self.run_op(operation="prompt", image_path=str(p), mode="classic", keep_loaded=True)
        self.assertEqual(len(self.made), 2, "the kept interrogator is reused")
        self.assertEqual(self.run_op(operation="models")["loaded"], "ViT-L-14/openai")
        self.assertEqual(self.run_op(operation="unload"), {"unloaded": True})
        self.assertEqual(self.run_op(operation="unload"), {"unloaded": False})

    def test_changing_clip_model_reloads(self):
        p = self.root / "a.png"
        image("red", p)
        self.run_op(operation="prompt", image_path=str(p), mode="fast", keep_loaded=True)
        self.run_op(operation="prompt", image_path=str(p), mode="fast", keep_loaded=True, clip_model="ViT-B-32/openai")
        self.assertEqual([m[0] for m in self.made], ["ViT-L-14/openai", "ViT-B-32/openai"])

    def test_negative_needs_no_caption_model(self):
        p = self.root / "a.png"
        image("red", p)
        r = self.run_op(operation="prompt", image_path=str(p), mode="negative")
        self.assertEqual(r["caption"], "")
        self.assertEqual(self.made, [("ViT-L-14/openai", "none")])

    def test_analyze(self):
        p = self.root / "a.png"
        image("blue", p)
        r = self.run_op(operation="analyze", image_path=str(p))
        self.assertEqual(list(r)[:2], ["clip_model", "medium"])
        self.assertEqual(next(iter(r["flavor"])), "blue")
        self.assertEqual(self.made[0][1], "none")

    def test_bad_inputs(self):
        for params in ({"operation": "prompt", "image_path": str(self.root / "missing.png")},
                       {"operation": "prompt", "image_path": str(self.root), "mode": "fast"},
                       {"operation": "prompt", "mode": "bogus", "image_path": "x"},
                       {"operation": "nope"},
                       {"operation": "batch", "folder": str(self.root / "nope")},
                       {"operation": "prompt", "image_path": __file__, "mode": "fast", "caption_model": "gpt"}):
            with self.assertRaises(EngineError, msg=str(params)):
                self.run_op(**params)

    def test_unsupported_clip_name_is_rejected(self):
        with self.assertRaises(EngineError):
            ci.clip_repo("RN50/openai")
        self.assertEqual(ci.clip_repo("ViT-B-32/openai"), "openai/clip-vit-base-patch32")
        self.assertEqual(ci.clip_repo("openai/clip-vit-large-patch14"), "openai/clip-vit-large-patch14")

    def make_folder(self):
        folder = self.root / "imgs"
        folder.mkdir()
        image("red", folder / "b.png")
        image("green", folder / "a.jpg")
        image("blue", folder / "c.jpeg")
        (folder / "bad.png").write_bytes(b"not a png")
        (folder / "note.webp").write_bytes(b"x")
        (folder / "d.txt").write_text("keep", encoding="utf-8")
        return folder

    def test_batch_text_file_per_image(self):
        folder = self.make_folder()
        ctx = Ctx()
        r = ci.run(Engine(), {"operation": "batch", "folder": str(folder), "mode": "caption"}, ctx, factory=self.factory)
        self.assertEqual(r["processed"], 3)
        self.assertEqual(r["failed"], ["bad.png"])
        self.assertEqual((folder / "a.txt").read_text(encoding="utf-8"), "a green square")
        self.assertEqual((folder / "b.txt").read_text(encoding="utf-8"), "a red square")
        self.assertEqual((folder / "c.txt").read_text(encoding="utf-8"), "a blue square")
        self.assertFalse((folder / "bad.txt").exists())
        self.assertFalse((folder / "note.txt").exists(), "only png/jpg/jpeg are processed, like upstream")
        self.assertEqual((folder / "d.txt").read_text(encoding="utf-8"), "keep")
        self.assertEqual(ctx.events[-1][:2], (8, 8))
        self.assertIsNone(ci._session["ci"])

    def test_batch_does_not_overwrite_existing_text_unless_asked(self):
        folder = self.make_folder()
        (folder / "b.txt").write_text("hand written", encoding="utf-8")
        r = self.run_op(operation="batch", folder=str(folder), mode="caption")
        self.assertEqual(r["skipped_existing"], ["b.png"])
        self.assertEqual((folder / "b.txt").read_text(encoding="utf-8"), "hand written")
        self.run_op(operation="batch", folder=str(folder), mode="caption", overwrite=True)
        self.assertEqual((folder / "b.txt").read_text(encoding="utf-8"), "a red square")

    def test_batch_single_file_and_csv(self):
        folder = self.make_folder()
        self.run_op(operation="batch", folder=str(folder), mode="caption", output_mode=ci.BATCH_OUTPUT_MODES[1])
        self.assertEqual((folder / "batch.txt").read_text(encoding="utf-8").splitlines(),
                         ["a green square", "a red square", "a blue square"])
        with self.assertRaises(EngineError):
            self.run_op(operation="batch", folder=str(folder), mode="caption", output_mode=ci.BATCH_OUTPUT_MODES[1])
        self.run_op(operation="batch", folder=str(folder), mode="fast", max_flavors=2, output_mode=ci.BATCH_OUTPUT_MODES[2])
        with open(folder / "batch.csv", encoding="utf-8", newline="") as f:
            rows = list(csv.reader(f))
        self.assertEqual(rows[0], ["filename", "prompt"])
        self.assertEqual([r[0] for r in rows[1:]], ["a.jpg", "b.png", "c.jpeg"])
        self.assertTrue(rows[2][1].startswith("a red square, "))

    def test_batch_interrupt_keeps_finished_outputs_and_releases(self):
        folder = self.make_folder()
        with self.assertRaises(EngineError) as r:
            ci.run(Engine(), {"operation": "batch", "folder": str(folder), "mode": "caption"}, Ctx(interrupt_after=6), factory=self.factory)
        self.assertEqual(r.exception.code, INTERRUPTED)
        self.assertIsNone(ci._session["ci"])
        self.assertFalse((folder / "a.txt").exists(), "captions are produced first; nothing half written")

    def test_batch_errors(self):
        empty = self.root / "empty"
        empty.mkdir()
        with self.assertRaises(EngineError):
            self.run_op(operation="batch", folder=str(empty))
        with self.assertRaises(EngineError):
            self.run_op(operation="batch", folder=str(self.make_folder()), output_mode="zip")


class OffloadTests(unittest.TestCase):
    def test_low_vram_keeps_one_model_on_the_device(self):
        with tempfile.TemporaryDirectory() as root:
            c = build(root, offload=True)
            c.generate_caption(image("red"))
            self.assertEqual(c.backend.moved, ["cpu"])
            c.image_to_features(image("red"))
            self.assertEqual(c.backend.moved, ["cpu", "cpu"])
            self.assertEqual(c.captioner.moved, ["cpu"])
            c.generate_caption(image("red"))
            self.assertEqual(c.captioner.moved, ["cpu", "cpu"])


if __name__ == "__main__":
    unittest.main()
