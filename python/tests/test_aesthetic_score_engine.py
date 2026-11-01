"""The aesthetic_score hook in the real generation control flow (model calls stubbed): the score is taken from the
final image, goes to the result, the dry-run engine and the feature lists."""
import pytest

torch = pytest.importorskip('torch')
pytest.importorskip('diffusers')
from PIL import Image
from test_hires_repeat_engine import harness, context  # noqa: F401  (fixture)
from sdfx import aesthetic_score
from sdfx.protocol import EngineError


@pytest.fixture
def scored(harness, monkeypatch):
    engine, request, calls = harness
    request['params'].update(batch_size=2, n_iter=1)
    request['params']['hires'] = {'enabled': False}
    request['params']['hooks'] = {'aesthetic_score': {'improved': True, 'generation_fields': True, 'hash': True}}
    seen = []
    monkeypatch.setattr(aesthetic_score, 'preload', lambda cfg, progress=None: seen.append(('preload', dict(cfg))))

    def fake(image, cfg, progress=None):
        seen.append(image.getpixel((0, 0)))
        return {'improved': 5.0 + image.getpixel((0, 0))[0] / 100.0}
    monkeypatch.setattr(aesthetic_score, 'score_image', fake)
    return engine, request, seen


def test_every_final_image_is_scored_once_and_the_fields_reach_the_result(scored):
    engine, request, seen = scored
    result = engine.txt2img(request, context())
    assert seen[0][0] == 'preload' and seen[0][1]['improved'] is True
    assert seen[1:] == [(10, 0, 0), (11, 0, 0)]
    assert len(result['images']) == 2
    for index, image in enumerate(result['images']):
        fields = dict(image['infotext_fields'])
        assert fields['aesthetic_score'] == f'{5.1 + index / 100:.1f}'
        assert len(fields['hash']) == 32
        chunks = dict(map(tuple, image['png_chunks']))
        assert chunks['sampler'] == 'DPM++ 2M' and chunks['seed'] == '10' and chunks['sd_model_hash'] == 'fixture'
        assert chunks['aesthetic_score'] == fields['aesthetic_score']
        with Image.open(image['path']) as saved:
            assert saved.size == (64, 64)  # scoring never changes the pixels


def test_the_score_sees_the_image_after_img2img_hires_refinement(scored, monkeypatch):
    from sdfx import img2img_hires_fix
    engine, request, seen = scored
    request['params']['hooks']['img2img_hires_fix'] = {'upscaler': 'Lanczos', 'ratio': 2}

    def refine(engine_, gp, req, ctx, cfg, image, seed, index, total, *args):
        return Image.new('RGB', (image.width * 2, image.height * 2), (0, 200, 0))
    monkeypatch.setattr(img2img_hires_fix, 'refine', refine)
    engine.txt2img(request, context())
    assert seen[1:] == [(0, 200, 0), (0, 200, 0)]


def test_without_the_hook_nothing_is_scored(scored):
    engine, request, seen = scored
    del request['params']['hooks']['aesthetic_score']
    result = engine.txt2img(request, context())
    assert seen == [] and all('infotext_fields' not in image for image in result['images'])


def test_an_invalid_setting_fails_before_sampling(scored):
    engine, request, seen = scored
    request['params']['hooks']['aesthetic_score'] = {'improved': False}
    with pytest.raises(EngineError) as error:
        engine.txt2img(request, context())
    assert error.value.code == 'invalid_params'


def test_engine_and_dry_run_advertise_the_hook():
    from sdfx.engine import Engine
    from sdfx.dryrun import FakeEngine
    assert 'aesthetic_score' in Engine().features()
    assert 'aesthetic_score' in FakeEngine().features()
