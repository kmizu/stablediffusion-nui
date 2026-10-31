"""Create a synthetic, representable CNI for the native transfer smoke check."""
import io
import sys
from pathlib import Path
from PIL import Image
from sdfx.controlnet_fastload import encode_fastload, native_to_legacy_unit

output = Path(sys.argv[1])
image = io.BytesIO()
Image.new('RGB', (32, 24), (20, 40, 60)).save(image, format='PNG')
unit = native_to_legacy_unit({'enabled': True, 'model': 'fixture-model', 'preprocessor': 'none'}, image.getvalue())
output.write_bytes(encode_fastload(b'', [unit]))
print('wrote synthetic native Fastload fixture')
