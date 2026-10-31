"""Read only locally generated trusted data using the pinned upstream reader."""
import ast, gzip, hashlib, os, pickle, tempfile, urllib.request
from pathlib import Path
import numpy as np
from sdfx.controlnet_fastload import encode_fastload
url='https://raw.githubusercontent.com/pk5ls20/sd-webui-controlnet-fastload/bbdb929f4285ea69d5099268496e4322dc7203ab/scripts/fastload.py'
data=urllib.request.urlopen(url,timeout=20).read()
assert hashlib.sha256(data).hexdigest()=='a10dc8eff063aef1ce9c3de470ef9bf84980c9777660316e78e16acc82b8882c'
function=next(n for n in ast.parse(data).body if isinstance(n,ast.FunctionDef) and n.name=='loadFromFile')
scope={'os':os,'gzip':gzip,'pickle':pickle,'start_marker':b'###START_OF_CONTROLNET_FASTLOAD###','end_marker':b'###END_OF_CONTROLNET_FASTLOAD###','print_err':print,'__name__':'trusted_fixture'}
future=ast.ImportFrom(module='__future__', names=[ast.alias(name='annotations')], level=0)
exec(compile(ast.fix_missing_locations(ast.Module(body=[future,function],type_ignores=[])),url,'exec'),scope)
units=[{'enabled':True,'model':'canny','image':{'image':{'dtype':'|u1','shape':(3,4,3),'data':bytes(range(36)),'order':'C'},'mask':{'dtype':'|u1','shape':(3,4),'data':bytes(range(12)),'order':'C'}},'weight':.75}]
with tempfile.TemporaryDirectory(prefix='fastload-trusted-') as tmp:
 path=Path(tmp)/'trusted.cni'; path.write_bytes(encode_fastload(b'',units))
 result=scope['loadFromFile'](str(path))
 assert result[0]['model']=='canny' and result[0]['weight']==.75
 assert np.array_equal(result[0]['image']['image'],np.arange(36,dtype='uint8').reshape(3,4,3))
 assert np.array_equal(result[0]['image']['mask'],np.arange(12,dtype='uint8').reshape(3,4))
print('PASS pinned upstream reader consumed locally generated trusted CNI with RGB+mask')
