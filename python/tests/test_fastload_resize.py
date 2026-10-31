import pytest
from PIL import Image


def test_controlnet_resize_modes_are_distinct_and_ordered():
    from sdfx.controlnet import resize_control_images
    image = Image.new('RGB', (4, 2), (0, 0, 0))
    for y in range(2):
        image.putpixel((0, y), (255, 0, 0) if y == 0 else (0, 255, 0))
        image.putpixel((3, y), (0, 0, 255) if y == 0 else (255, 255, 0))
    stretched, cropped, padded = resize_control_images([image, image, image], [0, 1, 2], 8, 8)
    assert all(result.size == (8, 8) for result in (stretched, cropped, padded))
    assert cropped.getpixel((0, 4)) != stretched.getpixel((0, 4))
    assert padded.tobytes() != stretched.tobytes()


def test_controlnet_resize_rejects_invalid_modes_and_mismatched_count():
    from sdfx.controlnet import resize_control_images
    image = Image.new('RGB', (2, 2))
    with pytest.raises(ValueError): resize_control_images([image], [3], 2, 2)
    with pytest.raises(ValueError): resize_control_images([image], [], 2, 2)


@pytest.mark.parametrize('source,expected', [('Just Resize', 0), ('Crop and Resize', 1), ('Resize and Fill', 2), (3, 0)])
def test_legacy_resize_modes_roundtrip_to_native(source, expected):
    from sdfx.controlnet_fastload import map_native_unit, native_to_legacy_unit
    mapped = map_native_unit({'resize_mode': source})
    assert mapped['params']['resize_mode'] == expected
    assert 'resize_mode' not in mapped['unapplied']
    exported = native_to_legacy_unit({'resize_mode': expected})
    assert map_native_unit(exported)['params']['resize_mode'] == expected


@pytest.mark.parametrize('name', ['binary', 'binary_down', 'binary_solid', 'palette', 'gradient', 'gradient_up'])
@pytest.mark.parametrize('mode', [0, 1, 2])
def test_source_compatible_resize_matches_pinned_reference(name, mode):
    from pathlib import Path
    import numpy as np
    from sdfx.controlnet import source_controlnet_resize
    fixture = Path(__file__).parent / 'fixtures' / 'controlnet_fastload' / 'resize-reference.npz'
    with np.load(fixture) as data:
        image = Image.fromarray(data[f'{name}_input'])
        size = 48 if name == 'gradient_up' else 16
        actual = np.asarray(source_controlnet_resize(image, mode, size, size))
        assert np.array_equal(actual, data[f'{name}_{mode}'])


def test_per_unit_compatibility_keeps_native_default_and_routes_source_mode():
    from pathlib import Path
    import numpy as np
    from sdfx.controlnet import resize_control_images
    from sdfx.images import resize_image
    fixture = Path(__file__).parent / 'fixtures' / 'controlnet_fastload' / 'resize-reference.npz'
    with np.load(fixture) as data:
        image = Image.fromarray(data['binary_input'])
        native, source = resize_control_images([image, image], [1, 1], 16, 16, [False, True])
        assert np.array_equal(np.asarray(native), np.asarray(resize_image(1, image, 16, 16)))
        assert np.array_equal(np.asarray(source), data['binary_1'])
        assert not np.array_equal(np.asarray(native), np.asarray(source))


def test_resize_compatibility_marker_preserves_native_roundtrip():
    from sdfx.controlnet_fastload import map_native_unit, native_to_legacy_unit
    native = native_to_legacy_unit({'resize_mode': 2, 'source_compatible_resize': False})
    mapped = map_native_unit(native)
    assert mapped['params']['resize_mode'] == 2 and mapped['params']['source_compatible_resize'] is False
    assert map_native_unit({'resize_mode': 'Resize and Fill'})['params']['source_compatible_resize'] is True


def test_older_generation_requests_keep_native_resize_defaults():
    from sdfx.params import parse_generation_request
    unit = parse_generation_request({'params': {'controlnet': [{'model': 'm'}]}}).params.controlnet[0]
    assert unit.resize_mode == 1 and unit.source_compatible_resize is False


def test_source_compatible_controlnet_input_composites_alpha_like_upstream(tmp_path):
    from sdfx.controlnet import load_controlnet_image
    path = tmp_path / 'alpha.png'
    image = Image.new('RGBA', (1, 1), (1, 20, 100, 128)); image.save(path)
    assert load_controlnet_image(str(path), True).getpixel((0, 0)) == (127, 137, 177)
    assert load_controlnet_image(str(path), False).getpixel((0, 0)) == (128, 137, 177)
