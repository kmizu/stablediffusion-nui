"""Build trusted reference fixtures from pinned upstream classes; never load user pickle."""
import ast
import base64
import gzip
import hashlib
import io
import json
import pickle
import sys
import types
import urllib.request
from pathlib import Path
import numpy as np
from enum import Enum
from PIL import Image

URL = 'https://raw.githubusercontent.com/Mikubill/sd-webui-controlnet/6ed97a7da27203667da5c1ce5e2c2ecbc7bf79ed/internal_controlnet/external_code.py'
EXPECTED = 'db0c1c592bfea56d76e95a8fcfe9cc9db3a825fc3cc658d31bb9b8efa1256488'
data = urllib.request.urlopen(URL, timeout=20).read()
assert hashlib.sha256(data).hexdigest() == EXPECTED
nodes = ast.parse(data).body
classes = [n for n in nodes if isinstance(n, ast.ClassDef) and n.name in {'ControlMode', 'ResizeMode', 'ControlNetUnit'}]
assert [n.name for n in classes] == ['ControlMode', 'ResizeMode', 'ControlNetUnit']
module = types.ModuleType('internal_controlnet.external_code')
module.__dict__.update(Enum=Enum, np=np)
sys.modules['internal_controlnet'] = types.ModuleType('internal_controlnet')
sys.modules[module.__name__] = module
future = ast.ImportFrom(module='__future__', names=[ast.alias(name='annotations')], level=0)
exec(compile(ast.fix_missing_locations(ast.Module(body=[future, *classes], type_ignores=[])), URL, 'exec'), module.__dict__)
rgb = np.arange(36, dtype=np.uint8).reshape(3, 4, 3)
mask = np.arange(12, dtype=np.uint8).reshape(3, 4)
unit = module.ControlNetUnit(enabled=True, module='canny', model='fixture-model', weight=.75,
    image={'image':rgb, 'mask':mask}, resize_mode=module.ResizeMode.INNER_FIT,
    control_mode=module.ControlMode.BALANCED, guidance_start=.1, guidance_end=.9, pixel_perfect=True)
output = Path('python/tests/fixtures/controlnet_fastload')
for protocol in (2, 3, 4, 5):
    payload = pickle.dumps([unit], protocol=protocol)
    (output / f'unit-protocol-{protocol}.pickle').write_bytes(payload)
    print(protocol, len(payload), hashlib.sha256(payload).hexdigest())
fastload_url = 'https://raw.githubusercontent.com/pk5ls20/sd-webui-controlnet-fastload/bbdb929f4285ea69d5099268496e4322dc7203ab/scripts/fastload.py'
fastload_source = urllib.request.urlopen(fastload_url, timeout=20).read()
fastload_hash = 'a10dc8eff063aef1ce9c3de470ef9bf84980c9777660316e78e16acc82b8882c'
assert hashlib.sha256(fastload_source).hexdigest() == fastload_hash
function = next(n for n in ast.parse(fastload_source).body if isinstance(n, ast.FunctionDef) and n.name == 'addToPicture')
source_scope = {'gzip': gzip, 'pickle': pickle, 'base64': base64, 'start_marker': b'###START_OF_CONTROLNET_FASTLOAD###',
                'end_marker': b'###END_OF_CONTROLNET_FASTLOAD###', 'print_err': print, '__name__': 'trusted_fixture'}
exec(compile(ast.fix_missing_locations(ast.Module(body=[ast.ImportFrom(module='__future__', names=[ast.alias(name='annotations')], level=0), function], type_ignores=[])), fastload_url, 'exec'), source_scope)
buffer = io.BytesIO(); Image.new('RGB', (4, 3), (4, 80, 230)).save(buffer, format='PNG')
image = buffer.getvalue()
for name, prefix in [('upstream-image.png', image), ('upstream-unit.cni', b'')]:
    result = source_scope['addToPicture'](base64.b64encode(prefix).decode(), [unit], 'base64')
    raw = base64.b64decode(result)
    (output / name).write_bytes(raw)
    print(name, len(raw), hashlib.sha256(raw).hexdigest())
(output / 'provenance.json').write_text(json.dumps({'source':URL,'source_sha256':EXPECTED,'fastload_source':fastload_url,'fastload_source_sha256':fastload_hash,'generated_by':'scripts/dev/create_fastload_reference.py','numpy':np.__version__,'python':sys.version,'synthetic':True}, indent=2)+'\n', encoding='utf-8')
