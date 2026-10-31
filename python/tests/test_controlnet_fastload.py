import gzip
import pytest

from sdfx.controlnet_fastload import extract_payload, append_payload

START = b'###START_OF_CONTROLNET_FASTLOAD###'
END = b'###END_OF_CONTROLNET_FASTLOAD###'


def test_mapping_consumes_only_proven_inactive_unit_defaults():
    from sdfx.controlnet_fastload import map_native_unit
    unit = {'enabled': True, 'model': 'm', 'pixel_perfect': False,
            'advanced_weighting': None, 'effective_region_mask': None,
            'ipadapter_input': None, 'animatediff_batch': False,
            'save_detected_map': True, 'processor_res': -1}
    result = map_native_unit(unit)
    # save_detected_map only adds a UI preview; none ignores the processor resolution.
    assert set(result['unapplied']) == set()
    assert result['original'] == unit
    unit['pixel_perfect'] = True
    assert map_native_unit(unit)['params']['pixel_perfect'] is True
    unit['module'] = 'depth'
    assert {'pixel_perfect', 'processor_res'} <= set(map_native_unit(unit)['unapplied'])
    unit.update(module='none', control_mode='ControlNet is more important', low_vram=True, hr_option='Low res only')
    mapped = map_native_unit(unit)
    assert mapped['params']['control_mode'] == 'ControlNet is more important'
    assert set(mapped['unapplied']) == {'hr_option'}


def test_real_legacy_envelope_roundtrip_preserves_image_prefix():
    prefix = b'\x89PNG\r\n\x1a\noriginal image bytes'
    payload = b'\x80\x04]q\x00.'
    raw = prefix + START + gzip.compress(payload) + END
    assert extract_payload(raw) == (prefix, payload)
    assert extract_payload(append_payload(prefix, payload)) == (prefix, payload)


@pytest.mark.parametrize('raw', [b'', START, END, START + b'junk' + END, START + gzip.compress(b'x') + END + b'garbage', START + START + END])
def test_invalid_or_ambiguous_envelope_is_rejected(raw):
    with pytest.raises(ValueError): extract_payload(raw)


def test_expansion_limit_rejects_gzip_bomb():
    raw = START + gzip.compress(b'0' * 100_000) + END
    with pytest.raises(ValueError, match='limit'):
        extract_payload(raw, max_expanded=1024)


def test_symbolic_reader_accepts_primitive_pickle_without_executing():
    import pickle
    from sdfx.controlnet_fastload import read_legacy_pickle
    data = [{'enabled': True, 'weight': 0.5, 'image': None, 'model': 'canny', 'extra': [1, 2]}]
    assert read_legacy_pickle(pickle.dumps(data, protocol=4)) == data


def test_symbolic_reader_rejects_global_reduce_without_invocation(tmp_path):
    import os
    import pickle
    from sdfx.controlnet_fastload import read_legacy_pickle
    marker = tmp_path / 'must-not-exist'
    class Attack:
        def __reduce__(self): return (os.system, (f'echo bad > "{marker}"',))
    payload = pickle.dumps(Attack(), protocol=4)
    with pytest.raises(ValueError): read_legacy_pickle(payload)
    assert not marker.exists()


@pytest.mark.parametrize('protocol', [2, 3, 4, 5])
def test_actual_upstream_controlnet_unit_and_numpy_arrays_are_decoded(protocol):
    from pathlib import Path
    from sdfx.controlnet_fastload import read_legacy_pickle
    payload = (Path(__file__).parent / 'fixtures/controlnet_fastload' / f'unit-protocol-{protocol}.pickle').read_bytes()
    result = read_legacy_pickle(payload)
    unit = result[0]
    assert unit['enabled'] is True and unit['module'] == 'canny'
    assert unit['resize_mode'] == 'Crop and Resize' and unit['control_mode'] == 'Balanced'
    assert unit['image']['image'] == {'dtype': '|u1', 'shape': (3, 4, 3), 'data': bytes(range(36)), 'order': 'C'}
    assert unit['image']['mask'] == {'dtype': '|u1', 'shape': (3, 4), 'data': bytes(range(12)), 'order': 'C'}


def test_unresolved_global_cannot_escape_symbolic_reader():
    from sdfx.controlnet_fastload import read_legacy_pickle
    with pytest.raises(ValueError):
        read_legacy_pickle(b'cnumpy\ndtype\n.')


def test_cyclic_container_and_node_explosion_rejected():
    import pickle
    from sdfx.controlnet_fastload import read_legacy_pickle
    loop = []; loop.append(loop)
    with pytest.raises(ValueError, match='cyclic'): read_legacy_pickle(pickle.dumps(loop, protocol=4))
    with pytest.raises(ValueError, match='limit'): read_legacy_pickle(pickle.dumps([[]] * 100, protocol=4), max_items=20)


def test_writer_interoperates_with_legacy_numpy_image_reader():
    import pickle
    np = pytest.importorskip('numpy')
    from sdfx.controlnet_fastload import write_legacy_pickle, read_legacy_pickle
    array = {'dtype': '|u1', 'shape': (2, 3, 3), 'data': bytes(range(18)), 'order': 'C'}
    units = [{'enabled': True, 'model': 'canny', 'image': array}]
    encoded = write_legacy_pickle(units)
    # Only the payload we just generated from the trusted test fixture is read by pickle.
    old_reader = pickle.loads(encoded)
    assert np.array_equal(old_reader[0]['image'], np.arange(18, dtype=np.uint8).reshape(2, 3, 3))
    assert read_legacy_pickle(encoded) == units


def test_malformed_container_opcode_fails_as_invalid_data():
    from sdfx.controlnet_fastload import read_legacy_pickle
    # MARK, integer target, integer item, APPENDS: target has no append operation.
    with pytest.raises(ValueError): read_legacy_pickle(b'K\x01(K\x02e.')


@pytest.mark.parametrize('protocol', [2, 3, 4, 5])
def test_modern_upstream_pydantic_unit_and_numpy_arrays_are_decoded(protocol):
    from pathlib import Path
    from sdfx.controlnet_fastload import read_legacy_pickle
    payload = (Path(__file__).parent / 'fixtures/controlnet_fastload' / f'modern-unit-protocol-{protocol}.pickle').read_bytes()
    unit = read_legacy_pickle(payload)[0]
    assert unit['enabled'] is True and unit['module'] == 'canny'
    assert unit['resize_mode'] == 'Crop and Resize' and unit['control_mode'] == 'Balanced'
    assert unit['hr_option'] == 'Both' and unit['union_control_type'] == 'Unknown'
    assert unit['image']['image'] == {'dtype': '|u1', 'shape': (3, 4, 3), 'data': bytes(range(36)), 'order': 'C'}
    assert unit['image']['mask'] == {'dtype': '|u1', 'shape': (3, 4), 'data': bytes(range(12)), 'order': 'C'}


def test_writer_rejects_custom_reducer_without_calling_it(tmp_path):
    pytest.importorskip('numpy')
    from sdfx.controlnet_fastload import write_legacy_pickle
    marker = tmp_path / 'writer-side-effect'
    class Attack:
        def __reduce__(self):
            marker.write_text('executed')
            return (str, ('bad',))
    with pytest.raises(ValueError, match='unsupported'):
        write_legacy_pickle([{'image': Attack()}])
    assert not marker.exists()


@pytest.mark.parametrize('dtype', ['uint16', 'float32'])
@pytest.mark.parametrize('protocol', [2, 3, 4, 5])
def test_symbolic_reader_preserves_supported_numeric_array_dtypes(dtype, protocol):
    import pickle
    np = pytest.importorskip('numpy')
    from sdfx.controlnet_fastload import read_legacy_pickle, write_legacy_pickle
    arr = np.arange(12, dtype=dtype).reshape(3, 4)
    actual = read_legacy_pickle(pickle.dumps([{'image': arr}], protocol=protocol))
    image = actual[0]['image']
    assert image == {'dtype': arr.dtype.str, 'shape': arr.shape, 'data': arr.tobytes(), 'order': 'C'}
    assert read_legacy_pickle(write_legacy_pickle(actual)) == actual


def test_big_endian_fortran_array_roundtrips_without_byte_swaps():
    import pickle
    np = pytest.importorskip('numpy')
    from sdfx.controlnet_fastload import read_legacy_pickle, write_legacy_pickle
    arr = np.asfortranarray(np.arange(12, dtype='>u2').reshape(3, 4))
    actual = read_legacy_pickle(pickle.dumps([{'image': arr}], protocol=4))
    image = actual[0]['image']
    assert image == {'dtype': '>u2', 'shape': (3, 4), 'data': arr.tobytes(order='F'), 'order': 'F'}
    assert read_legacy_pickle(write_legacy_pickle(actual)) == actual


def test_object_arrays_are_not_unpickled_or_executed(tmp_path):
    import pickle, os
    np = pytest.importorskip('numpy')
    from sdfx.controlnet_fastload import read_legacy_pickle
    marker = tmp_path / 'object-array-attack'
    class Attack:
        def __reduce__(self): return (os.system, (f'echo bad > "{marker}"',))
    raw = pickle.dumps([{'image': np.array([Attack()], dtype=object)}], protocol=4)
    with pytest.raises(ValueError): read_legacy_pickle(raw)
    assert not marker.exists()


def test_complete_fastload_cni_roundtrip_including_image_and_unknown_fields():
    np = pytest.importorskip('numpy')
    from sdfx.controlnet_fastload import decode_fastload, encode_fastload
    units = [{'enabled': True, 'model': 'canny', 'image': {'dtype': '|u1', 'shape': (2, 2, 3), 'data': bytes(range(12)), 'order': 'C'}, 'future_parameter': {'keep': 3}}]
    file_bytes = encode_fastload(b'PNG-data', units)
    prefix, result = decode_fastload(file_bytes)
    assert prefix == b'PNG-data' and result == units


def test_fastload_rejects_non_unit_document():
    import pickle
    from sdfx.controlnet_fastload import decode_fastload
    for obj in (123, {'model': 'canny'}, [None], [{'not-a-string-key': {3: 'bad'}}]):
        raw = START + gzip.compress(pickle.dumps(obj, protocol=4)) + END
        with pytest.raises(ValueError): decode_fastload(raw)


@pytest.mark.parametrize('name,has_image', [('upstream-image.png', True), ('upstream-unit.cni', False)])
def test_pinned_upstream_actual_writer_fixtures_decode_without_pickle_execution(name, has_image):
    from pathlib import Path
    from sdfx.controlnet_fastload import decode_fastload
    raw = (Path(__file__).parent / 'fixtures/controlnet_fastload' / name).read_bytes()
    prefix, units = decode_fastload(raw)
    assert prefix.startswith(b'\x89PNG\r\n\x1a\n') if has_image else prefix == b''
    assert len(units) == 1 and units[0]['model'] == 'fixture-model'
    assert units[0]['image']['image']['data'] == bytes(range(36))
    assert units[0]['image']['mask']['data'] == bytes(range(12))


def test_writer_uses_numpy_1_compatible_globals_without_rewriting_image_bytes():
    import pickletools
    pytest.importorskip('numpy')
    from sdfx.controlnet_fastload import write_legacy_pickle
    marker = b'numpy._core.multiarray'
    units = [{'image': {'dtype':'|u1', 'shape':(len(marker),), 'data':marker, 'order':'C'}}]
    payload = write_legacy_pickle(units)
    globals_ = [arg for op, arg, _ in pickletools.genops(payload) if op.name == 'GLOBAL']
    assert 'numpy.core.multiarray _reconstruct' in globals_
    assert 'numpy._core.multiarray _reconstruct' not in globals_
    from sdfx.controlnet_fastload import read_legacy_pickle
    assert read_legacy_pickle(payload)[0]['image']['data'] == marker


def test_wire_roundtrip_preserves_arrays_bytes_and_unknown_fields():
    from sdfx.controlnet_fastload import encode_wire, decode_wire
    source = [{'enabled': True, 'image': {'dtype':'|u1', 'shape':(2, 2, 3), 'data':bytes(range(12)), 'order':'C'}, 'mask': b'raw\x00', '$kind': 'user-data', 'unknown': {'value': [1, 2.5]}}]
    assert decode_wire(encode_wire(source)) == source


@pytest.mark.parametrize('wire', [None, {}, {'type':'bytes','base64':'not base64'}, {'type':'array','dtype':'|u1','shape':[100,100],'data':'AA==','order':'C'}, {'type':'dict','items':[['x',{'type':'null'}],['x',{'type':'null'}]]}])
def test_wire_rejects_malformed_values(wire):
    from sdfx.controlnet_fastload import decode_wire
    with pytest.raises(ValueError): decode_wire(wire)


def test_load_save_modes_and_priority_preserve_existing_and_all_image_data():
    from sdfx.controlnet_fastload import resolve_units
    active = [{'enabled': True, 'model': 'existing', 'image': b'existing'}]
    saved = [{'enabled': True, 'model': 'restored', 'image': {'image': b'original','mask': b'mask'}, 'future_field': 42}]
    picked, should_save = resolve_units('load_save', 'current_first', active, saved)
    assert picked == active and should_save
    picked, should_save = resolve_units('load_save', 'loaded_first', active, saved)
    assert picked == saved and should_save
    picked[0]['image']['mask'] = b'changed'
    assert saved[0]['image']['mask'] == b'mask'
    picked, should_save = resolve_units('load_only', 'current_first', [{'enabled':False}], saved)
    assert picked == saved and not should_save
    picked, should_save = resolve_units('save_only', 'loaded_first', active, saved)
    assert picked == active and should_save


@pytest.mark.parametrize('mode', ['load_only', 'save_only', 'load_save'])
@pytest.mark.parametrize('priority', ['current_first', 'loaded_first'])
@pytest.mark.parametrize('enabled', [False, True])
@pytest.mark.parametrize('has_input', [False, True])
def test_resolve_full_priority_matrix_retains_disabled_units(mode, priority, enabled, has_input):
    from sdfx.controlnet_fastload import resolve_units
    current = [{'enabled': False, 'model': 'disabled'}, {'enabled': enabled, 'model': 'current'}]
    loaded = [{'enabled': False, 'model': 'loaded-disabled'}, {'enabled': True, 'model': 'loaded'}] if has_input else None
    chosen, save = resolve_units(mode, priority, current, loaded)
    use_loaded = mode != 'save_only' and has_input and not (priority == 'current_first' and enabled)
    assert chosen == (loaded if use_loaded else current)
    assert save == (mode != 'load_only')
    assert chosen is not (loaded if use_loaded else current)


def test_load_mode_requires_valid_document_without_mutating_current():
    from sdfx.controlnet_fastload import resolve_units
    active = [{'enabled': True, 'model': 'existing'}]
    with pytest.raises(ValueError): resolve_units('load_only', 'loaded_first', active, 'malformed')
    assert active == [{'enabled': True, 'model':'existing'}]


def test_native_mapping_keeps_assets_and_unmapped_values_visible():
    from sdfx.controlnet_fastload import map_native_unit
    image = {'dtype':'|u1','shape':(2,2,3),'data':bytes(range(12)),'order':'C'}
    mask = {'dtype':'|u1','shape':(2,2),'data':bytes(range(4)),'order':'C'}
    source = {'enabled':True,'module':'canny','model':'control','weight':.7,'guidance_start':.1,'guidance_end':.9,
              'threshold_a':64,'threshold_b':192,'image':{'image':image,'mask':mask},'resize_mode':'Crop and Resize',
              'pixel_perfect':True,'future_option':'preserve'}
    mapped = map_native_unit(source)
    assert mapped['params'] == {'enabled':True,'model':'control','preprocessor':'canny','weight':.7,
                                'guidance_start':.1,'guidance_end':.9,'preprocessor_args':{'low_threshold':64.0,'high_threshold':192.0},
                                'resize_mode':1,'source_compatible_resize':True,'control_mode':'Balanced',
                                'processor_res':-1,'pixel_perfect':True}
    assert mapped['image'] == image and mapped['mask'] == mask
    assert 'resize_mode' not in mapped['unapplied'] and 'image.mask' not in mapped['unapplied']
    assert set(mapped['unapplied']) == {'future_option'} and mapped['unapplied']['future_option'] == 'preserve'
    assert mapped['original'] == source


def test_native_mapping_rejects_invalid_values_without_mutating_input():
    from sdfx.controlnet_fastload import map_native_unit
    source={'enabled':True,'model':'control','weight':float('nan')}
    with pytest.raises(ValueError): map_native_unit(source)
    assert source['enabled'] is True


def test_publish_creates_cni_and_never_overwrites_existing(tmp_path):
    from sdfx.controlnet_fastload import publish_fastload, decode_fastload
    class Context:
        image_password = None
        def check_interrupt(self): pass
        def publish(self, operation): return operation()
    target=tmp_path/'sample.cni'
    units=[{'enabled':True,'model':'canny','image':b'input-image'}]
    publish_fastload(target, units, b'', Context())
    assert decode_fastload(target.read_bytes())[1] == units
    before=target.read_bytes()
    with pytest.raises(FileExistsError): publish_fastload(target, [{'model':'other'}], b'', Context())
    assert target.read_bytes()==before


def test_protected_session_never_writes_plaintext_fastload(tmp_path):
    from sdfx.controlnet_fastload import publish_fastload
    class Context:
        image_password='session-secret'
        def check_interrupt(self): pass
        def publish(self, operation): return operation()
    with pytest.raises(ValueError, match='protected'):
        publish_fastload(tmp_path/'sample.cni', [{'image':b'secret'}], b'', Context())
    assert list(tmp_path.iterdir()) == []


def test_cancel_before_commit_leaves_no_output_or_stage(tmp_path):
    from sdfx.controlnet_fastload import publish_fastload
    class Context:
        image_password=None
        calls=0
        def check_interrupt(self):
            self.calls+=1
            if self.calls==2: raise RuntimeError('interrupted')
        def publish(self, operation): return operation()
    with pytest.raises(RuntimeError, match='interrupted'):
        publish_fastload(tmp_path/'sample.cni', [{'model':'canny'}], b'', Context())
    assert list(tmp_path.iterdir()) == []


def test_service_save_inspect_and_map_roundtrip(tmp_path):
    from sdfx.controlnet_fastload import run_fastload_service, encode_wire, decode_wire
    class Context:
        image_password=None
        def check_interrupt(self): pass
        def publish(self, operation): return operation()
    unit={'enabled':True,'model':'canny-model','module':'canny','weight':0.75,'image':{'image':b'pixels','mask':b'mask'},'future':'preserve'}
    path=tmp_path/'unit.cni'; ctx=Context()
    result=run_fastload_service({'operation':'save','path':str(path),'units':encode_wire([unit])},ctx)
    assert result['path']==str(path)
    loaded=run_fastload_service({'operation':'inspect','path':str(path)},ctx)
    assert decode_wire(loaded['units']) == [unit]
    mapped=decode_wire(run_fastload_service({'operation':'map','units':loaded['units']},ctx)['mapped'])
    assert mapped[0]['params']['model']=='canny-model' and mapped[0]['mask']==b'mask'
    assert mapped[0]['unapplied']['future']=='preserve'


def test_service_protected_save_refuses_before_file_creation(tmp_path):
    from sdfx.controlnet_fastload import run_fastload_service, encode_wire
    class Context:
        image_password='password'
        def check_interrupt(self): pass
        def publish(self, operation): return operation()
    with pytest.raises(ValueError, match='protected'):
        run_fastload_service({'operation':'save','path':str(tmp_path/'private.cni'),'units':encode_wire([{'image':b'private'}])},Context())
    assert not list(tmp_path.iterdir())


def test_inspect_summary_does_not_expose_image_or_mask_bytes(tmp_path):
    from sdfx.controlnet_fastload import run_fastload_service, encode_wire
    class Context:
        image_password=None
        def check_interrupt(self): pass
        def publish(self, operation): return operation()
    path=tmp_path/'unit.cni'
    unit={'enabled':True,'model':'m','module':'inpaint_only','image':{'image':b'private-image','mask':b'private-mask'}}
    run_fastload_service({'operation':'save','path':str(path),'units':encode_wire([unit])},Context())
    result=run_fastload_service({'operation':'inspect','path':str(path)},Context())
    assert result['summary'][0]['model']=='m'
    assert 'image.mask' in result['summary'][0]['unapplied']
    assert 'private-image' not in str(result['summary']) and 'private-mask' not in str(result['summary'])


def test_image_and_mask_view_are_lossless_png_in_memory():
    import base64, io
    np=pytest.importorskip('numpy')
    Image=pytest.importorskip('PIL.Image')
    from sdfx.controlnet_fastload import run_fastload_service, encode_wire
    class Context:
        image_password='protected-secret'
        def check_interrupt(self): pass
    image={'dtype':'|u1','shape':(2,2,3),'data':bytes(range(12)),'order':'C'}
    mask={'dtype':'|u1','shape':(2,2),'data':bytes((0,127,254,255)),'order':'C'}
    units=[{'image':{'image':image,'mask':mask}}]
    result=run_fastload_service({'operation':'preview','units':encode_wire(units),'index':0},Context())
    im=np.asarray(Image.open(io.BytesIO(base64.b64decode(result['image']))))
    ma=np.asarray(Image.open(io.BytesIO(base64.b64decode(result['mask']))))
    assert im.tobytes()==image['data'] and ma.tobytes()==mask['data']


def test_image_view_rejects_unsupported_dtype_without_file_creation(tmp_path):
    from sdfx.controlnet_fastload import run_fastload_service, encode_wire
    class Context:
        image_password='protected-secret'
        def check_interrupt(self): pass
    arr={'dtype':'<f4','shape':(1,1),'data':bytes(4),'order':'C'}
    with pytest.raises(ValueError, match='preview'):
        run_fastload_service({'operation':'preview','units':encode_wire([{'image':arr}]),'index':0},Context())
    assert not list(tmp_path.iterdir())
def test_native_unit_export_preserves_image_mask_and_canny_parameters():
    import base64, io
    from PIL import Image
    from sdfx.controlnet_fastload import native_to_legacy_unit, map_native_unit
    buf = io.BytesIO(); Image.new('RGB', (2, 2), (23, 45, 67)).save(buf, format='PNG')
    image = buf.getvalue()
    native = {'enabled': True, 'model': 'canny-model', 'preprocessor': 'canny',
              'weight': 0.7, 'guidance_start': 0.1, 'guidance_end': 0.9,
              'preprocessor_args': {'low_threshold': 40.0, 'high_threshold': 160.0}, 'resize_mode': 1,
              'source_compatible_resize': False}
    legacy = native_to_legacy_unit(native, image, image)
    mapped = map_native_unit(legacy)
    assert mapped['params'] == dict(native, control_mode='Balanced', processor_res=-1, pixel_perfect=False)
    native.update(control_mode='My prompt is more important', processor_res=768, pixel_perfect=True)
    assert map_native_unit(native_to_legacy_unit(native, image))['params'] == native
    assert base64.b64decode(mapped['image']) == image
    assert base64.b64decode(mapped['mask']) == image


def test_native_unit_export_rejects_unrepresentable_args():
    from sdfx.controlnet_fastload import native_to_legacy_unit
    with pytest.raises(ValueError, match='preprocessor'):
        native_to_legacy_unit({'preprocessor': 'depth', 'preprocessor_args': {'foo': 1}})
    with pytest.raises(ValueError, match='image'):
        native_to_legacy_unit({}, b'not an image')


def test_native_mapping_finds_separate_legacy_mask_without_losing_conflicts():
    from sdfx.controlnet_fastload import map_native_unit
    source = {'image': b'input', 'mask': b'separate'}
    mapped = map_native_unit(source)
    assert mapped['image'] == b'input' and mapped['mask'] == b'separate'
    # Source uses a non-inpaint mask only to select the input; native_form applies that rule.
    assert 'image.mask' not in mapped['unapplied']
    assert map_native_unit(dict(source, module='inpaint'))['unapplied']['image.mask'] == b'separate'
    conflict = map_native_unit({'image': {'image': b'input', 'mask': b'nested'}, 'mask': b'separate'})
    assert conflict['mask'] == b'nested'
    assert conflict['unapplied']['mask'] == b'separate'
    empty_nested = map_native_unit({'image': {'image': b'input', 'mask': None}, 'mask': b'separate'})
    assert empty_nested['mask'] is None
    assert empty_nested['unapplied']['mask'] == b'separate'


def test_native_mapping_accepts_image_mask_list_pair():
    from sdfx.controlnet_fastload import map_native_unit
    mapped = map_native_unit({'image': [b'image', b'mask']})
    assert mapped['image'] == b'image'
    assert mapped['mask'] == b'mask'
    assert 'image.mask' not in mapped['unapplied']
    batch = [{'image': b'one'}, {'image': b'two'}]
    mapped_batch = map_native_unit({'image': batch, 'mask': b'ignored'})
    assert mapped_batch['unapplied']['image.batch'] == batch
    assert mapped_batch['mask'] is None and mapped_batch['unapplied']['mask'] == b'ignored'


def test_native_export_service_roundtrips_image_and_mask():
    import base64, io
    from PIL import Image
    from sdfx.controlnet_fastload import run_fastload_service, encode_wire, decode_wire
    class Context:
        def check_interrupt(self): pass
    buf = io.BytesIO(); Image.new('RGB', (2, 2)).save(buf, format='PNG')
    image = buf.getvalue()
    items = [{'params': {'enabled': True, 'model': 'm', 'preprocessor': 'canny',
                         'preprocessor_args': {'low_threshold': 11}},
              'image': image, 'mask': image}]
    result = run_fastload_service({'operation': 'from_native', 'items': encode_wire(items)}, Context())
    unit = decode_wire(result['units'])[0]
    assert result['summary'][0]['model'] == 'm'
    assert base64.b64decode(unit['image']['image']) == image
    assert base64.b64decode(unit['image']['mask']) == image
    assert unit['threshold_a'] == 11


def test_native_form_service_returns_image_mask_and_unapplied_names():
    import io
    from PIL import Image
    from sdfx.controlnet_fastload import run_fastload_service, encode_wire
    class Context:
        def check_interrupt(self): pass
    buf = io.BytesIO(); Image.new('RGB', (2, 2)).save(buf, format='PNG')
    unit = {'enabled': True, 'model': 'm', 'module': 'canny',
            'image': {'image': buf.getvalue(), 'mask': buf.getvalue()}, 'pixel_perfect': True}
    result = run_fastload_service({'operation': 'native_form', 'units': encode_wire([unit]), 'index': 0}, Context())
    assert result['params']['model'] == 'm' and result['params']['pixel_perfect'] is True
    # An all-dark mask selects the RGB source; the mask is consumed.
    assert result['image'] and result['mask'] is None
    assert result['unapplied'] == []
    import base64
    scribble = io.BytesIO(); Image.new('L', (2, 2), 128).save(scribble, format='PNG')
    unit['image'] = {'image': buf.getvalue(), 'mask': scribble.getvalue()}
    result = run_fastload_service({'operation': 'native_form', 'units': encode_wire([unit]), 'index': 0}, Context())
    with Image.open(io.BytesIO(base64.b64decode(result['image']))) as selected:
        assert selected.mode == 'RGB' and selected.getpixel((0, 0)) == (128, 128, 128)
    unit['module'] = 'inpaint_only'
    result = run_fastload_service({'operation': 'native_form', 'units': encode_wire([unit]), 'index': 0}, Context())
    assert result['mask'] and 'image.mask' in result['unapplied']


def test_native_images_survive_legacy_file_and_preview():
    import base64, io
    from PIL import Image
    from sdfx.controlnet_fastload import native_to_legacy_unit, encode_fastload, decode_fastload, encode_wire, run_fastload_service
    class Context:
        def check_interrupt(self): pass
    source = io.BytesIO(); mask = io.BytesIO()
    Image.new('RGB', (3, 4), (12, 34, 56)).save(source, format='PNG')
    Image.new('L', (3, 4), 80).save(mask, format='PNG')
    unit = native_to_legacy_unit({'model': 'm'}, source.getvalue(), mask.getvalue())
    _, restored = decode_fastload(encode_fastload(b'', [unit]))
    result = run_fastload_service({'operation': 'preview', 'units': encode_wire(restored), 'index': 0}, Context())
    with Image.open(io.BytesIO(base64.b64decode(result['image']))) as image:
        assert image.convert('RGB').getpixel((0, 0)) == (12, 34, 56)
    with Image.open(io.BytesIO(base64.b64decode(result['mask']))) as image:
        assert image.convert('L').getpixel((0, 0)) == 80


@pytest.mark.parametrize('mode,should_save', [('load_only', False), ('load_save', True)])
def test_resolve_missing_file_keeps_current_units(mode, should_save):
    from sdfx.controlnet_fastload import resolve_units
    current = [{'enabled': True, 'model': 'existing'}]
    chosen, save = resolve_units(mode, 'loaded_first', current, None)
    assert chosen == current and chosen is not current
    assert save is should_save


def test_resolve_service_reports_skipped_load():
    from sdfx.controlnet_fastload import run_fastload_service, encode_wire, decode_wire
    class Context:
        def check_interrupt(self): pass
    result = run_fastload_service({'operation': 'resolve', 'mode': 'load_save', 'priority': 'loaded_first',
                                   'current': encode_wire([{'enabled': True, 'model': 'm'}])}, Context())
    assert result['load_skipped'] is True and result['save'] is True
    assert decode_wire(result['units']) == [{'enabled': True, 'model': 'm'}]


def test_current_priority_does_not_read_unneeded_loaded_data():
    from sdfx.controlnet_fastload import resolve_units, run_fastload_service, encode_wire, decode_wire
    class Context:
        def check_interrupt(self): pass
    current = [{'enabled': True, 'model': 'keep'}]
    picked, _ = resolve_units('load_only', 'current_first', current, 'malformed')
    assert picked == current
    result = run_fastload_service({'operation': 'resolve', 'mode': 'load_only', 'priority': 'current_first',
                                   'current': encode_wire(current), 'loaded': {'bad': 'wire'}}, Context())
    assert decode_wire(result['units']) == current


def test_encode_service_never_writes_and_rejects_protected_session(tmp_path):
    import base64
    from sdfx.controlnet_fastload import run_fastload_service, encode_wire, decode_fastload
    class Context:
        image_password = None
        def check_interrupt(self): pass
        def publish(self, operation): return operation()
    ctx = Context()
    request = {'operation': 'encode', 'units': encode_wire([{'enabled': True, 'model': 'm'}])}
    result = run_fastload_service(request, ctx)
    _, units = decode_fastload(base64.b64decode(result['base64']))
    assert units == [{'enabled': True, 'model': 'm'}]
    assert not list(tmp_path.iterdir())
    ctx.image_password = 'secret'
    with pytest.raises(ValueError, match='protected'):
        run_fastload_service(request, ctx)


def test_noninpaint_mask_uses_source_or_mask_scribble():
    from PIL import Image
    from sdfx.controlnet_fastload import noninpaint_mask_input
    image = Image.new('RGB', (2, 1), (12, 34, 56))
    assert noninpaint_mask_input(image, Image.new('L', (2, 1), 5)).getpixel((0, 0)) == (12, 34, 56)
    assert noninpaint_mask_input(image, Image.new('L', (2, 1), 250)).getpixel((0, 0)) == (12, 34, 56)
    mask = Image.new('L', (2, 1)); mask.putdata([0, 255])
    assert noninpaint_mask_input(image, mask).getpixel((1, 0)) == (255, 255, 255)
    assert noninpaint_mask_input(image, mask, ignore_mask=True).getpixel((1, 0)) == (12, 34, 56)
    color_mask = Image.new('RGB', (2, 1)); color_mask.putdata([(6, 200, 200), (249, 0, 0)])
    assert noninpaint_mask_input(image, color_mask).getpixel((0, 0)) == (6, 6,6)
    assert noninpaint_mask_input(image, color_mask).getpixel((1, 0)) == (249,249,249)
    with pytest.raises(ValueError, match='size'):
        noninpaint_mask_input(image, Image.new('L', (1, 1)))


def test_gallery_metadata_filters_intersect_and_page_stably():
    from sdfx.controlnet_fastload import metadata_controlnet_pairs, filter_gallery_page
    text = 'ControlNet 0: "Module: canny, Model: one, Starting/Ending: (0.1, 0.9)", ControlNet 1: "Module: depth, Model: two"'
    pairs = metadata_controlnet_pairs(text)
    assert ('Module', 'canny') in pairs[0] and ('Starting/Ending', '(0.1, 0.9)') in pairs[0]
    entries = [(f'{i:03}.png', text if i < 38 else 'ControlNet 0: "Module: depth, Model: three"') for i in range(40)]
    page, pages = filter_gallery_page(entries, [('Module', 'canny'), ('Model', 'two')], 2)
    assert page == ['036.png', '037.png'] and pages == 2
    assert filter_gallery_page(entries, [('Model', 'missing')], 1) == ([], 0)
    assert metadata_controlnet_pairs('ControlNet' * 100_000) == []
    assert metadata_controlnet_pairs('ControlNet 0: "' + 'x' * 100_000 + '"') == [[]]
    calls = []
    def cancel():
        calls.append(1)
        if len(calls) == 2: raise RuntimeError('cancelled')
    with pytest.raises(RuntimeError, match='cancelled'):
        filter_gallery_page(entries, [], 1, check=cancel)


def test_gallery_scan_reads_image_metadata_without_writing(tmp_path, monkeypatch):
    from PIL import Image, PngImagePlugin
    from sdfx.controlnet_fastload import scan_gallery
    class Context:
        def check_interrupt(self): pass
    nested = tmp_path / 'nested'; nested.mkdir()
    info = PngImagePlugin.PngInfo(); info.add_text('parameters', 'ControlNet 0: "Module: canny"')
    Image.new('RGB', (2, 2)).save(nested / 'sample.png', pnginfo=info)
    (tmp_path / 'not-image.txt').write_text('ignore', encoding='utf8')
    result = scan_gallery(tmp_path, Context())
    assert len(result) == 1 and result[0][0] == str(nested / 'sample.png')
    assert 'Module: canny' in result[0][1]
    assert sorted(p.name for p in tmp_path.iterdir()) == ['nested', 'not-image.txt']
    monkeypatch.chdir(tmp_path)
    assert scan_gallery('nested', Context())[0][0] == str(nested / 'sample.png')


def test_gallery_scan_propagates_interruption(tmp_path):
    from PIL import Image
    from sdfx.controlnet_fastload import scan_gallery
    Image.new('RGB', (2, 2)).save(tmp_path / 'sample.png')
    class Context:
        def __init__(self): self.calls = 0
        def check_interrupt(self):
            self.calls += 1
            if self.calls > 1: raise RuntimeError('cancelled')
    with pytest.raises(RuntimeError, match='cancelled'):
        scan_gallery(tmp_path, Context())


def test_gallery_service_returns_filter_choices_and_page(tmp_path):
    from PIL import Image, PngImagePlugin
    from sdfx.controlnet_fastload import run_fastload_service
    class Context:
        image_password = 'protected-session'
        def check_interrupt(self): pass
    info = PngImagePlugin.PngInfo(); info.add_text('parameters', 'ControlNet 0: "Module: canny, Model: m"')
    Image.new('RGB', (2, 2)).save(tmp_path / 'a.png', pnginfo=info)
    result = run_fastload_service({'operation': 'gallery', 'path': str(tmp_path),
        'filters': [['Module', 'canny']], 'page': 1}, Context())
    assert result['paths'] == [str(tmp_path / 'a.png')]
    assert result['choices']['Module'] == ['canny'] and result['pages'] == 1
    assert result['skipped_links'] == 0 and result['skipped_oversize'] == 0
    result = run_fastload_service({'operation': 'gallery', 'path': str(tmp_path), 'page': 99}, Context())
    assert result['page'] == 1


def test_gallery_reports_skipped_symlink(tmp_path):
    from PIL import Image
    from sdfx.controlnet_fastload import run_fastload_service
    class Context:
        def check_interrupt(self): pass
    original = tmp_path / 'original.png'
    Image.new('RGB', (2, 2)).save(original)
    try: (tmp_path / 'linked.png').symlink_to(original)
    except OSError: pytest.skip('symlink creation is unavailable on this host')
    result = run_fastload_service({'operation': 'gallery', 'path': str(tmp_path)}, Context())
    assert result['paths'] == [str(original)] and result['skipped_links'] == 1


def test_gallery_source_prefers_nonempty_embedded_then_sidecar(tmp_path):
    from PIL import Image
    from sdfx.controlnet_fastload import run_fastload_service, encode_fastload, START
    class Context:
        def check_interrupt(self): pass
    image = tmp_path / 'image.jpeg'; Image.new('RGB', (2, 2)).save(image)
    sidecar = image.with_suffix('.cni'); sidecar.write_bytes(encode_fastload(b'', [{'model': 'sidecar'}]))
    request = {'operation': 'gallery_source', 'path': str(image)}
    assert run_fastload_service(request, Context()) == {'path': str(sidecar), 'kind': 'sidecar'}
    original = image.read_bytes()
    image.write_bytes(encode_fastload(original, []))
    assert run_fastload_service(request, Context()) == {'path': str(sidecar), 'kind': 'sidecar'}
    image.write_bytes(encode_fastload(original, [{'model': 'embedded'}]))
    assert run_fastload_service(request, Context()) == {'path': str(image), 'kind': 'embedded'}
    image.write_bytes(b'broken' + START)
    with pytest.raises(ValueError): run_fastload_service(request, Context())


def test_gallery_source_rejects_symlink_sidecar(tmp_path):
    from PIL import Image
    from sdfx.controlnet_fastload import run_fastload_service, encode_fastload
    class Context:
        def check_interrupt(self): pass
    image = tmp_path / 'a.png'; Image.new('RGB', (2, 2)).save(image)
    other = tmp_path / 'other.cni'; other.write_bytes(encode_fastload(b'', [{'model': 'm'}]))
    try: image.with_suffix('.cni').symlink_to(other)
    except OSError: pytest.skip('symlink creation is unavailable on this host')
    with pytest.raises(ValueError, match='symbolic link'):
        run_fastload_service({'operation': 'gallery_source', 'path': str(image)}, Context())

