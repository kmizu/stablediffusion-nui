import pytest
from sdfx.controlnet_fastload import encode_wire, decode_wire, run_fastload_service, encode_fastload

class Context:
    def check_interrupt(self): pass
    def publish(self, operation): return operation()


def test_prepare_generation_uses_source_only_if_required_and_copies_raw_units(tmp_path):
    source = [{'enabled': True, 'model': 'saved', 'image': b'private'}]
    path = tmp_path / 'source.cni'
    path.write_bytes(encode_fastload(b'', source))
    current = [{'enabled': True, 'model': 'current'}]
    args = {'operation': 'prepare_generation', 'mode': 'load_save', 'priority': 'current_first',
            'current': encode_wire(current), 'path': str(path)}
    first = run_fastload_service(args, Context())
    assert decode_wire(first['units']) == current and first['save'] and first['load_skipped']
    args['current'] = encode_wire([{'enabled': False, 'model': 'disabled'}])
    second = run_fastload_service(args, Context())
    assert decode_wire(second['units']) == source and second['save'] and not second['load_skipped']
    args['mode'] = 'load_only'
    assert not run_fastload_service(args, Context())['save']


def test_prepare_generation_does_not_read_unused_or_missing_input(tmp_path):
    missing = str(tmp_path / 'missing.cni')
    args = {'operation': 'prepare_generation', 'mode': 'save_only', 'priority': 'loaded_first',
            'current': encode_wire([{'enabled': True}]), 'path': missing}
    assert run_fastload_service(args, Context())['save']
    args['mode'] = 'load_save'
    args['path'] = ''
    assert run_fastload_service(args, Context())['load_skipped']
    args['path'] = missing
    with pytest.raises(FileNotFoundError): run_fastload_service(args, Context())


def test_prepare_generation_returns_native_forms_without_losing_original_options(tmp_path):
    source = [{'enabled': True, 'model': 'saved', 'future_option': 'retain'}]
    path = tmp_path / 'saved.cni'
    path.write_bytes(encode_fastload(b'', source))
    result = run_fastload_service({'operation': 'prepare_generation', 'mode': 'load_only',
        'priority': 'loaded_first', 'current': encode_wire([]), 'path': str(path),
        'native_forms': True}, Context())
    assert decode_wire(result['units']) == source
    assert result['forms'][0]['params']['enabled'] is True
    assert 'future_option' in result['forms'][0]['unapplied']


def test_prepare_generation_rejects_extra_units_instead_of_silently_dropping_them():
    args = {'operation': 'prepare_generation', 'mode': 'save_only', 'priority': 'current_first',
            'current': encode_wire([{'enabled': False}] * 4), 'native_forms': True}
    with pytest.raises(ValueError, match='three'):
        run_fastload_service(args, Context())


def test_disabled_unit_does_not_decode_its_unused_image(tmp_path):
    source = [{'enabled': False, 'model': 'saved', 'image': b'not-a-valid-image'}]
    path = tmp_path / 'saved.cni'
    path.write_bytes(encode_fastload(b'', source))
    result = run_fastload_service({'operation': 'prepare_generation', 'mode': 'load_only',
        'priority': 'loaded_first', 'current': encode_wire([]), 'path': str(path),
        'native_forms': True}, Context())
    assert decode_wire(result['units']) == source
    assert result['forms'][0]['params']['enabled'] is False


def test_prepared_units_reencode_with_real_legacy_codec_and_do_not_affect_next_job(tmp_path):
    import base64
    from sdfx.controlnet_fastload import decode_fastload
    source = [{'enabled': False, 'model': 'disabled', 'future': 'preserve'}]
    path = tmp_path / 'source.cni'
    original = encode_fastload(b'', source)
    path.write_bytes(original)
    result = run_fastload_service({'operation': 'prepare_generation', 'mode': 'load_save',
        'priority': 'loaded_first', 'current': encode_wire([]), 'path': str(path),
        'native_forms': True}, Context())
    encoded = run_fastload_service({'operation': 'encode', 'units': result['units']}, Context())
    _, decoded = decode_fastload(base64.b64decode(encoded['base64']))
    assert decoded == source and path.read_bytes() == original
    next_job = run_fastload_service({'operation': 'prepare_generation', 'mode': 'load_only',
        'priority': 'current_first', 'current': encode_wire([{'enabled': True, 'model': 'current'}]),
        'path': str(tmp_path / 'missing.cni')}, Context())
    assert not next_job['save'] and next_job['load_skipped']
