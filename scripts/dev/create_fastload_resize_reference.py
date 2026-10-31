"""Generate synthetic resize fixtures using SHA-pinned upstream code, never reForge."""
import ast
import hashlib
import json
import urllib.request
from enum import Enum
from pathlib import Path
import cv2
import numpy as np

revision = '56cec5b2958edf3b1807b7e7b2b1b5186dbd2f81'
base = f'https://raw.githubusercontent.com/Mikubill/sd-webui-controlnet/{revision}/'
expected = {
    'scripts/controlnet.py': '2cfb0a110575e3f4eb66161d193585954b760c5bcdbfed7d8d05d32d531546f3',
    'scripts/lvminthin.py': '0ca260c87caedf7ea08267dd288dfe216bf93f1a0f7066e228bb76363c6e2ae1',
}
data = {}
for path, digest in expected.items():
    raw = urllib.request.urlopen(base + path, timeout=20).read()
    assert hashlib.sha256(raw).hexdigest() == digest
    data[path] = raw
thin = {}
exec(compile(data['scripts/lvminthin.py'], base + 'scripts/lvminthin.py', 'exec'), thin)
tree = ast.parse(data['scripts/controlnet.py'])
cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == 'Script')
function = next(node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name == 'detectmap_proc')
function.decorator_list = []
class ResizeMode(Enum):
    RESIZE = 0
    INNER_FIT = 1
    OUTER_FIT = 2
scope = {'np': np, 'cv2': cv2, 'ResizeMode': ResizeMode, 'HWC3': lambda a: a,
         'get_unique_axis0': lambda a: np.unique(a, axis=0), 'get_pytorch_control': lambda a: a,
         'nake_nms': thin['nake_nms'], 'lvmin_thin': thin['lvmin_thin']}
exec(compile(ast.fix_missing_locations(ast.Module(body=[function], type_ignores=[])), base + 'scripts/controlnet.py', 'exec'), scope)
binary = np.zeros((9, 13, 3), dtype=np.uint8)
binary[1:8, 6] = 255; binary[4, 2:11] = 255
palette = np.zeros((9, 13, 3), dtype=np.uint8)
palette[:, :4] = (255, 0, 0); palette[:, 4:8] = (0, 255, 0); palette[:, 8:] = (0, 0, 255)
gradient = np.random.default_rng(178).integers(0, 256, size=(23, 31, 3), dtype=np.uint8)
solid = np.zeros((9, 13, 3), dtype=np.uint8); solid[1:8, 2:11] = 255
cases = {'binary': (binary, 16, 16), 'binary_down': (np.repeat(np.repeat(binary, 4, 0), 4, 1), 16, 16),
         'binary_solid': (solid, 16, 16), 'palette': (palette, 16, 16),
         'gradient': (gradient, 16, 16), 'gradient_up': (gradient, 48, 48)}
arrays = {}
for name, (image, height, width) in cases.items():
    arrays[f'{name}_input'] = image
    for mode in ResizeMode:
        _, result = scope['detectmap_proc'](image.copy(), 'canny', mode, height, width)
        arrays[f'{name}_{mode.value}'] = result
out = Path('python/tests/fixtures/controlnet_fastload')
np.savez_compressed(out / 'resize-reference.npz', **arrays)
(out / 'resize-provenance.json').write_text(json.dumps({'revision': revision, 'sources': expected,
    'synthetic': True, 'opencv': cv2.__version__, 'numpy': np.__version__}, indent=2) + '\n', encoding='utf8')
print('wrote trusted pinned-source synthetic reference fixtures')
