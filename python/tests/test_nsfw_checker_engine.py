"""NudeNet NSFW Censor in real generation control flow (model calls stubbed): ordering, auxiliary outputs, previews."""
import base64
import io
from types import SimpleNamespace

import pytest

torch = pytest.importorskip('torch')
pytest.importorskip('diffusers')
import numpy as np
from PIL import Image, ImageDraw
from test_hires_repeat_engine import harness, context  # noqa: F401  (fixture)
from sdfx import nsfw_checker
from sdfx.protocol import EngineError


def detector(calls, box=(0, 0, 31, 31)):
    def run(cfg, img, model_path=None, log=None):
        calls.append(img.getpixel((40, 40)))
        if box is None:
            return None
        mask = Image.new('1', img.size, 0)
        ImageDraw.Draw(mask).rectangle(box, 1)
        return mask
    return run


@pytest.fixture
def censored(harness, monkeypatch):
    engine, request, calls = harness
    request['params'].update(batch_size=1, n_iter=2)
    request['params']['hires'] = {'enabled': False}
    request['params']['hooks'] = {'nsfw_checker': {'gen_filter_type': 'Fill color', 'fill_color': '#0000ff'}}
    monkeypatch.setattr(nsfw_checker, 'session', lambda *a: None)
    seen = []
    monkeypatch.setattr(nsfw_checker, 'detect_with', detector(seen))
    return engine, request, seen


def pixels(path, xy):
    with Image.open(path) as image:
        return image.convert('RGB').getpixel(xy)


def test_final_censor_runs_after_img2img_hires_refinement_and_saves_auxiliaries(censored, monkeypatch):
    from sdfx import img2img_hires_fix
    engine, request, seen = censored
    request['params']['hooks']['nsfw_checker']['save_mask'] = True
    request['params']['hooks']['img2img_hires_fix'] = {'upscaler': 'Lanczos', 'ratio': 2}
    order = []

    def refine(engine, gp, req, ctx, cfg, image, seed, index, total, *args):
        order.append('refine')
        return Image.new('RGB', (image.width * 2, image.height * 2), (0, 200, 0))
    monkeypatch.setattr(img2img_hires_fix, 'refine', refine)
    result = engine.txt2img(request, context())
    assert seen == [(0, 200, 0), (0, 200, 0)], 'detector must see the refined image'
    assert len(result['images']) == 2
    for image in result['images']:
        assert (image['width'], image['height']) == (128, 128)
        assert pixels(image['path'], (5, 5)) == (0, 0, 255)
        assert pixels(image['path'], (100, 100)) == (0, 200, 0)
    aux = result['auxiliary_images']
    assert [(a['output_suffix'], a['auxiliary_of']) for a in aux] == [
        ('-before-censor', 0), ('-censor-mask', 0), ('-before-censor', 1), ('-censor-mask', 1)]
    assert [a['seed'] for a in aux] == [10, 10, 11, 11]
    assert pixels(aux[0]['path'], (5, 5)) == (0, 200, 0)
    assert pixels(aux[1]['path'], (5, 5)) == (255, 255, 255) and pixels(aux[1]['path'], (100, 100)) == (0, 0, 0)


def test_plain_generation_is_censored_after_post_images_and_before_copy_is_default(censored, monkeypatch):
    from sdfx import extensions
    engine, request, seen = censored
    original = extensions.post_images
    monkeypatch.setattr(extensions, 'post_images', lambda *a, **kw: [Image.new('RGB', im.size, (9, 9, 9))
                                                                    for im in original(*a, **kw)])
    result = engine.txt2img(request, context())
    assert seen == [(9, 9, 9), (9, 9, 9)]
    assert [a['output_suffix'] for a in result['auxiliary_images']] == ['-before-censor'] * 2
    assert pixels(result['images'][0]['path'], (5, 5)) == (0, 0, 255)


def test_no_detection_leaves_images_and_result_unchanged(censored, monkeypatch):
    engine, request, seen = censored
    monkeypatch.setattr(nsfw_checker, 'detect_with', detector(seen, None))
    result = engine.txt2img(request, context())
    assert len(seen) == 2 and 'auxiliary_images' not in result
    assert pixels(result['images'][0]['path'], (5, 5)) == (10, 0, 0)


def test_disabled_generation_filter_skips_detection(censored):
    engine, request, seen = censored
    request['params']['hooks']['nsfw_checker'].update(gen_filter_type='Disable')
    result = engine.txt2img(request, context())
    assert seen == [] and 'auxiliary_images' not in result


def test_invalid_settings_fail_before_sampling(censored, harness):
    engine, request, seen = censored
    calls = harness[2]
    request['params']['hooks']['nsfw_checker']['thresholds'] = [0.1]
    with pytest.raises(EngineError):
        engine.txt2img(request, context())
    assert not [c for c in calls if isinstance(c, dict)]


def test_missing_model_fails_before_sampling(harness, monkeypatch, tmp_path):
    engine, request, calls = harness
    request['params']['hooks'] = {'nsfw_checker': {}}
    monkeypatch.setattr(nsfw_checker, 'default_model_path', lambda: tmp_path / 'missing.onnx')
    with pytest.raises(EngineError, match='NudeNet'):
        engine.txt2img(request, context())
    assert not [c for c in calls if isinstance(c, dict)]


def preview_events(monkeypatch, run):
    from sdfx.engine import Engine
    from sdfx.params import GenerationParams, GenerationRequest
    engine = Engine()
    engine.kind = 'sd15'
    events = []
    ctx = SimpleNamespace(interrupted=False, start_pass=lambda: None, progress=lambda *a, **kw: None,
                          preview=lambda data, step: events.append(data))
    monkeypatch.setattr(nsfw_checker, 'detect_with', run)
    hooks = {'nsfw_checker': {'live_preview_filter_type': 'Fill color', 'fill_color': '#ffffff'}}
    request = GenerationRequest(preview_every=1, preview_method='approx', params=GenerationParams(hooks=hooks))
    callback = engine._callback(ctx, request, 'base', 0, 1, 3)
    values = {'latents': torch.zeros(1, 4, 8, 8)}
    assert callback(SimpleNamespace(_num_timesteps=3), 0, 0, values) is values
    return events


def test_live_preview_is_censored_before_it_is_emitted(monkeypatch):
    events = preview_events(monkeypatch, lambda cfg, img, *a: Image.new('1', img.size, 1))
    assert len(events) == 1
    with Image.open(io.BytesIO(base64.b64decode(events[0]))) as image:
        assert min(image.convert('RGB').getpixel((4, 4))) > 240


def test_preview_detector_failure_drops_the_preview_without_failing(monkeypatch):
    def broken(*args):
        raise RuntimeError('detector failed')
    assert preview_events(monkeypatch, broken) == []
