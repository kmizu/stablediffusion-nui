import io
import json

from sdfx.base import BaseEngine
from sdfx.protocol import Emitter
from sdfx.server import Server


def test_fastload_worker_routes_data_and_blocks_protected_publication(tmp_path):
    from sdfx.controlnet_fastload import encode_wire
    stream=io.BytesIO(); server=Server(BaseEngine(), Emitter(stream)); server.worker.start()
    target=tmp_path/'secret.cni'
    msgs=[{'id':'version','type':'controlnet_fastload_data','params':{'operation':'version'}},
          {'id':'secret','type':'controlnet_fastload_data','image_password':'test-secret',
           'params':{'operation':'save','path':str(target),'units':encode_wire([{'image':b'private'}])}}]
    try:
        for message in msgs: assert server.handle_line(json.dumps(message))
        server.q.put(None); server.worker.join(10)
        assert not server.worker.is_alive()
    finally:
        if server.worker.is_alive(): server.q.put(None); server.worker.join(5)
    entries=[json.loads(line) for line in stream.getvalue().decode().splitlines()]
    version=next(m for m in entries if m.get('id')=='version')
    refused=next(m for m in entries if m.get('id')=='secret')
    assert version['type']=='result' and version['result']['protected_write'] is False
    assert version['result']['version'] == 1 and version['result']['upstream_version'] == 1.1
    assert refused['type']=='error' and refused['code']=='invalid_params'
    assert not target.exists() and not list(tmp_path.iterdir())
    assert b'test-secret' not in stream.getvalue() and b'private' not in stream.getvalue()


def test_fastload_data_capability_present_on_real_and_dry_workers():
    from sdfx.base import BaseEngine
    from sdfx.dryrun import FakeEngine
    assert 'controlnet_fastload_data' in BaseEngine().features()
    assert 'controlnet_fastload_data' in FakeEngine().features()


def test_protected_job_preparation_does_not_log_source_or_secret(tmp_path, caplog):
    from sdfx.controlnet_fastload import encode_wire, encode_fastload, decode_wire
    source = tmp_path / 'source.cni'
    source.write_bytes(encode_fastload(b'', [{'enabled': False, 'model': 'private-model-marker'}]))
    stream = io.BytesIO()
    server = Server(BaseEngine(), Emitter(stream)); server.worker.start()
    message = {'id': 'prepare', 'type': 'controlnet_fastload_data', 'image_password': 'private-password-marker',
        'params': {'operation': 'prepare_generation', 'mode': 'load_only', 'priority': 'loaded_first',
                   'current': encode_wire([]), 'path': str(source), 'native_forms': True}}
    try:
        assert server.handle_line(json.dumps(message))
        server.q.put(None); server.worker.join(10)
        assert not server.worker.is_alive()
    finally:
        if server.worker.is_alive(): server.q.put(None); server.worker.join(5)
    result = next(item for item in map(json.loads, stream.getvalue().decode().splitlines()) if item.get('id') == 'prepare')
    assert result['type'] == 'result'
    assert decode_wire(result['result']['units'])[0]['model'] == 'private-model-marker'
    assert 'private-password-marker' not in caplog.text
    assert 'private-model-marker' not in caplog.text
    assert 'private-password-marker' not in stream.getvalue().decode()
    assert list(tmp_path.iterdir()) == [source]


def test_protected_native_conversion_has_no_file_or_log_side_effects(tmp_path, caplog):
    import base64
    from sdfx.controlnet_fastload import encode_wire, decode_wire
    stream = io.BytesIO()
    server = Server(BaseEngine(), Emitter(stream))
    server.worker.start()
    from PIL import Image
    encoded = io.BytesIO(); Image.new('RGB', (2, 2), (22, 43, 65)).save(encoded, format='PNG')
    image = mask = encoded.getvalue()
    message = {'id': 'convert', 'type': 'controlnet_fastload_data', 'image_password': 'private-password-unique',
               'params': {'operation': 'from_native', 'items': encode_wire([
                   {'params': {'enabled': True, 'model': 'm'}, 'image': image, 'mask': mask}])}}
    try:
        assert server.handle_line(json.dumps(message))
        server.q.put(None)
        server.worker.join(10)
        assert not server.worker.is_alive()
    finally:
        if server.worker.is_alive(): server.q.put(None); server.worker.join(5)
    entries = [json.loads(line) for line in stream.getvalue().decode().splitlines()]
    result = next(item for item in entries if item.get('id') == 'convert')
    assert result['type'] == 'result'
    restored = decode_wire(result['result']['units'])[0]['image']
    assert base64.b64decode(restored['image']) == image
    assert base64.b64decode(restored['mask']) == mask
    assert not list(tmp_path.iterdir())
    assert 'private-password-unique' not in caplog.text
    assert 'private-image-unique' not in caplog.text and 'private-mask-unique' not in caplog.text
    assert base64.b64encode(image).decode() not in caplog.text
    assert base64.b64encode(mask).decode() not in caplog.text


def test_cancel_during_encode_returns_no_payload_or_file(tmp_path, monkeypatch):
    import threading
    import sdfx.controlnet_fastload as module
    entered, release = threading.Event(), threading.Event()
    real_encode = module.encode_fastload
    def held_encode(*args):
        entered.set()
        assert release.wait(5)
        return real_encode(*args)
    monkeypatch.setattr(module, 'encode_fastload', held_encode)
    stream = io.BytesIO()
    server = Server(BaseEngine(), Emitter(stream)); server.worker.start()
    request = {'id': 'encode', 'type': 'controlnet_fastload_data',
               'params': {'operation': 'encode', 'units': module.encode_wire([{'model': 'm'}])}}
    try:
        assert server.handle_line(json.dumps(request))
        assert entered.wait(5)
        assert server.handle_line(json.dumps({'id': 'stop', 'type': 'interrupt', 'params': {'target_id': 'encode'}}))
        release.set(); server.q.put(None); server.worker.join(10)
        assert not server.worker.is_alive()
    finally:
        release.set()
        if server.worker.is_alive(): server.q.put(None); server.worker.join(5)
    entries = [json.loads(line) for line in stream.getvalue().decode().splitlines()]
    outcome = next(item for item in entries if item.get('id') == 'encode')
    assert outcome['type'] == 'error' and outcome['code'] == 'interrupted'
    assert 'base64' not in outcome and not list(tmp_path.iterdir())


def test_cancel_at_encode_commit_is_serialized_with_result(monkeypatch):
    import threading
    from sdfx.protocol import JobContext
    from sdfx.controlnet_fastload import encode_wire
    entered, release = threading.Event(), threading.Event()
    publish = JobContext.publish
    def held_publish(self, operation):
        entered.set()
        assert release.wait(5)
        return publish(self, operation)
    monkeypatch.setattr(JobContext, 'publish', held_publish)
    stream = io.BytesIO()
    server = Server(BaseEngine(), Emitter(stream)); server.worker.start()
    request = {'id': 'encode', 'type': 'controlnet_fastload_data',
               'params': {'operation': 'encode', 'units': encode_wire([{'model': 'm'}])}}
    try:
        assert server.handle_line(json.dumps(request))
        assert entered.wait(5), 'encode did not claim publication'
        assert server.handle_line(json.dumps({'id': 'stop', 'type': 'interrupt', 'params': {'target_id': 'encode'}}))
        release.set(); server.q.put(None); server.worker.join(10)
        assert not server.worker.is_alive()
    finally:
        release.set()
        if server.worker.is_alive(): server.q.put(None); server.worker.join(5)
    entries = [json.loads(line) for line in stream.getvalue().decode().splitlines()]
    outcome = next(item for item in entries if item.get('id') == 'encode')
    assert outcome['type'] == 'error' and outcome['code'] == 'interrupted'


def test_cancel_after_encode_commit_is_reported_too_late(monkeypatch):
    import threading
    from sdfx.protocol import JobContext
    from sdfx.controlnet_fastload import encode_wire
    committed, release = threading.Event(), threading.Event()
    publish = JobContext.publish
    def held_after_publish(self, operation):
        result = publish(self, operation)
        committed.set()
        assert release.wait(5)
        return result
    monkeypatch.setattr(JobContext, 'publish', held_after_publish)
    stream = io.BytesIO()
    server = Server(BaseEngine(), Emitter(stream)); server.worker.start()
    request = {'id': 'encode', 'type': 'controlnet_fastload_data',
               'params': {'operation': 'encode', 'units': encode_wire([{'model': 'm'}])}}
    try:
        assert server.handle_line(json.dumps(request))
        assert committed.wait(5)
        assert server.handle_line(json.dumps({'id': 'stop', 'type': 'interrupt', 'params': {'target_id': 'encode'}}))
        release.set(); server.q.put(None); server.worker.join(10)
        assert not server.worker.is_alive()
    finally:
        release.set()
        if server.worker.is_alive(): server.q.put(None); server.worker.join(5)
    entries = [json.loads(line) for line in stream.getvalue().decode().splitlines()]
    stop = next(item for item in entries if item.get('id') == 'stop')
    outcome = next(item for item in entries if item.get('id') == 'encode')
    assert stop['result']['too_late'] is True and stop['result']['accepted'] is False
    assert outcome['type'] == 'result' and outcome['result']['base64']


def test_gallery_worker_does_not_log_password_or_metadata(tmp_path, caplog):
    from PIL import Image, PngImagePlugin
    info = PngImagePlugin.PngInfo(); info.add_text('parameters', 'ControlNet 0: "Model: private-model-name"')
    image = tmp_path / 'a.png'; Image.new('RGB', (2, 2)).save(image, pnginfo=info)
    stream = io.BytesIO(); server = Server(BaseEngine(), Emitter(stream)); server.worker.start()
    request = {'id': 'gallery', 'type': 'controlnet_fastload_data', 'image_password': 'private-session-password',
               'params': {'operation': 'gallery', 'path': str(tmp_path)}}
    try:
        assert server.handle_line(json.dumps(request))
        server.q.put(None); server.worker.join(10)
        assert not server.worker.is_alive()
    finally:
        if server.worker.is_alive(): server.q.put(None); server.worker.join(5)
    result = next(json.loads(line) for line in stream.getvalue().decode().splitlines() if json.loads(line).get('id') == 'gallery')
    assert result['type'] == 'result' and result['result']['choices']['Model'] == ['private-model-name']
    assert 'private-session-password' not in caplog.text and 'private-model-name' not in caplog.text
    assert [path.name for path in tmp_path.iterdir()] == ['a.png']
